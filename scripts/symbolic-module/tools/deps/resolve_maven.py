#!/usr/bin/env python3
"""Resolves the dependencies of a Maven repository and makes them visible to the symbolic module (Step 38).

Usage:
  resolve_maven.py <repo> [--cache DIR] [--copy] [--refresh]

What it does:
  1. The effective POM of every module of the repository (`help:effective-pom` at its root): it needs no dependency
     resolution, so it works when a module depends on a sibling module that is not built (which makes
     `dependency:copy-dependencies` fail at the root of a multi-module repository).
  2. A synthetic POM with the external dependencies of all the modules (the modules of the repository are left out:
     their sources are in the repository), the repositories they declare, and Maven resolves it with their transitive
     dependencies, test scope included: the binary jars, the `-sources` jars, and the `-javadoc` jars of the artifacts
     without sources. When the whole set cannot be resolved (an artifact missing), each dependency is resolved on its
     own (a reactor of one module per dependency, `--fail-at-end`), the missing ones are reported, and of the versions
     of one artifact the highest is kept.
  3. In the repository, `processed_libs/` (replaced at each run):
       decompiled/<groupId>__<artifactId>-<version>[-<classifier>]/  the `.java` files of the sources jar, or for a jar
                                                                    without sources its stubs (generated from the
                                                                    bytecode by the module: `java -jar tracto.jar stubs`;
                                                                    the module gives these folders to the symbol solver
                                                                    and searches them for the classes by name);
       jars/<groupId>__<artifactId>-<version>.jar                   the binary jars without sources whose stubs could
                                                                    not be generated (read by the solver as binaries);
       delombok/<source root>/, delombok/roots.json                  for a project that uses Lombok, the delomboked
                                                                    copies of its files that use it (Step 43): the
                                                                    members Lombok generates, for the symbol solver;
       dependencies.json                                            the report.
     The folders and jars are symbolic links to the cache (`--copy`: copies), where each artifact is unpacked once.

The result of the resolution is cached by the hash of the POM files of the repository (`--refresh` resolves again).
The cache: `--cache`, else $TRACTO_DEPS_CACHE, else ~/.cache/tracto-deps. Maven: `mvn` on the PATH, with JAVA_HOME.
The module: `--tracto-jar`, else $TRACTO_JAR, else target/tracto-jar-with-dependencies.jar of the module.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

DEPENDENCY_PLUGIN = 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1'
HELP_PLUGIN = 'org.apache.maven.plugins:maven-help-plugin:3.5.1'
LIBS = 'processed_libs'
POM_NS = 'http://maven.apache.org/POM/4.0.0'
REPORT_VERSION = 1
# The version of the stubs in the cache: increased when the stub generator changes what it writes
STUBS_VERSION = 1
MODULE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_TRACTO_JAR = os.path.join(MODULE, 'target', 'tracto-jar-with-dependencies.jar')


# ---------------------------------------------------------------------------------------------------------- POMs

def strip_namespaces(element):
    for node in element.iter():
        if isinstance(node.tag, str) and node.tag.startswith('{'):
            node.tag = node.tag.split('}', 1)[1]
    return element


def text(element, path, default=None):
    node = element.find(path)
    return node.text.strip() if node is not None and node.text else default


def effective_projects(effective_pom_path):
    """The <project> elements of the output of help:effective-pom (one, or several under <projects>)."""
    root = strip_namespaces(ET.parse(effective_pom_path).getroot())
    return [root] if root.tag == 'project' else root.findall('project')


def dependency_key(dependency):
    return (dependency['groupId'], dependency['artifactId'], dependency['type'], dependency['classifier'])


def version_key(version):
    """A sort key close to the ordering of Maven: numbers as numbers, qualifiers after (a release after its
    milestones and snapshots: 1.0 > 1.0-RC1)."""
    parts = re.split(r'[.\-]', version)
    key = []
    for part in parts:
        if part.isdigit():
            key.append((2, int(part), ''))
        else:
            key.append((0, 0, part.lower()))
    key.append((1, 0, ''))
    return key


def external_dependencies(projects):
    """The dependencies of the modules that are not modules of the repository, one version per artifact (the
    highest), and the repositories the modules declare."""
    reactor = {(text(p, 'groupId'), text(p, 'artifactId')) for p in projects}
    chosen = {}
    repositories = {}
    unresolvable = []
    for project in projects:
        for node in project.findall('dependencies/dependency'):
            dependency = {
                'groupId': text(node, 'groupId'),
                'artifactId': text(node, 'artifactId'),
                'version': text(node, 'version'),
                'type': text(node, 'type', 'jar'),
                'classifier': text(node, 'classifier', ''),
                'scope': text(node, 'scope', 'compile'),
                'systemPath': text(node, 'systemPath'),
                'exclusions': [(text(e, 'groupId'), text(e, 'artifactId')) for e in node.findall('exclusions/exclusion')],
            }
            if (dependency['groupId'], dependency['artifactId']) in reactor:
                continue
            if not dependency['version'] or '${' in dependency['version'] or '${' in dependency['groupId']:
                unresolvable.append(dependency)
                continue
            if dependency['scope'] == 'system' and (not dependency['systemPath'] or not os.path.exists(dependency['systemPath'])):
                unresolvable.append(dependency)
                continue
            key = dependency_key(dependency)
            if key not in chosen or version_key(dependency['version']) > version_key(chosen[key]['version']):
                chosen[key] = dependency
        for node in project.findall('repositories/repository'):
            url = text(node, 'url')
            if url and url.startswith(('http://', 'https://')) and '${' not in url:
                repositories.setdefault(url, text(node, 'id', url))
    return sorted(chosen.values(), key=dependency_key), repositories, unresolvable


def synthetic_pom(artifact_id, dependencies, repositories, modules=()):
    """A POM that depends on the given dependencies (or aggregates the given modules)."""
    project = ET.Element('project', xmlns=POM_NS)
    for tag, value in (('modelVersion', '4.0.0'), ('groupId', 'tracto.deps'), ('artifactId', artifact_id),
                       ('version', '1'), ('packaging', 'pom')):
        ET.SubElement(project, tag).text = value
    if modules:
        modules_node = ET.SubElement(project, 'modules')
        for module in modules:
            ET.SubElement(modules_node, 'module').text = module
    if repositories:
        repositories_node = ET.SubElement(project, 'repositories')
        for url, repository_id in sorted(repositories.items()):
            repository = ET.SubElement(repositories_node, 'repository')
            ET.SubElement(repository, 'id').text = repository_id
            ET.SubElement(repository, 'url').text = url
    if dependencies:
        dependencies_node = ET.SubElement(project, 'dependencies')
        for dependency in dependencies:
            node = ET.SubElement(dependencies_node, 'dependency')
            for tag in ('groupId', 'artifactId', 'version', 'type', 'classifier', 'scope', 'systemPath'):
                if dependency.get(tag):
                    ET.SubElement(node, tag).text = dependency[tag]
            if dependency['exclusions']:
                exclusions = ET.SubElement(node, 'exclusions')
                for group_id, artifact in dependency['exclusions']:
                    exclusion = ET.SubElement(exclusions, 'exclusion')
                    ET.SubElement(exclusion, 'groupId').text = group_id or '*'
                    ET.SubElement(exclusion, 'artifactId').text = artifact or '*'
    return ET.tostring(project, encoding='unicode')


# --------------------------------------------------------------------------------------------------------- Maven

# A slow or unreachable repository must not stall the generation: network timeouts of the Maven resolver (ms), and a
# limit on each Maven call (s), after which it fails like a missing artifact
MAVEN_NETWORK_OPTIONS = ['-Daether.connector.connectTimeout=20000', '-Daether.connector.requestTimeout=120000']
MAVEN_CALL_SECONDS = int(os.environ.get('TRACTO_DEPS_MAVEN_SECONDS', 1200))


def maven(arguments, cwd, log_path):
    command = ['mvn', '-B', '-ntp', *MAVEN_NETWORK_OPTIONS, *arguments]
    with open(log_path, 'a') as log:
        log.write('$ ' + ' '.join(command) + '\n')
        log.flush()
        try:
            status = subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                    timeout=MAVEN_CALL_SECONDS).returncode
        except subprocess.TimeoutExpired:
            log.write(f'TIMEOUT: Maven stopped after {MAVEN_CALL_SECONDS} s\n')
            status = 124
    return status


def copy_dependencies(pom_dir, output, log_path, classifier=None, include_artifacts=None, fail_at_end=False):
    arguments = [f'{DEPENDENCY_PLUGIN}:copy-dependencies', '-DincludeScope=test', '-Dmdep.useRepositoryLayout=true',
                 '-Dmdep.copyPom=false', f'-DoutputDirectory={output}']
    if classifier:
        arguments.append(f'-Dclassifier={classifier}')
    if include_artifacts is not None:
        arguments.append('-DincludeArtifactIds=' + ','.join(sorted(include_artifacts)))
    if fail_at_end:
        arguments.append('-fae')
    return maven(arguments, pom_dir, log_path)


def artifacts_in_layout(root):
    """The artifacts of a folder in repository layout: {(groupId, artifactId, version, classifier): path}."""
    found = {}
    for directory, _, files in os.walk(root):
        for name in files:
            if not name.endswith('.jar'):
                continue
            relative = os.path.relpath(directory, root).split(os.sep)
            if len(relative) < 3:
                continue
            group_id, artifact_id, version = '.'.join(relative[:-2]), relative[-2], relative[-1]
            stem = name[:-len('.jar')]
            prefix = f'{artifact_id}-{version}'
            if not stem.startswith(prefix):
                # A snapshot resolved to a timestamped version, or a file renamed: keep it whole
                classifier = ''
            else:
                classifier = stem[len(prefix) + 1:] if len(stem) > len(prefix) else ''
            found[(group_id, artifact_id, version, classifier)] = os.path.join(directory, name)
    return found


def highest_versions(artifacts):
    """One version per artifact (groupId, artifactId, classifier): the highest."""
    best = {}
    for (group_id, artifact_id, version, classifier), path in artifacts.items():
        key = (group_id, artifact_id, classifier)
        if key not in best or version_key(version) > version_key(best[key][0]):
            best[key] = (version, path)
    return {(g, a, v, c): p for (g, a, c), (v, p) in best.items()}


def resolve(dependencies, repositories, work, log_path):
    """Resolves the dependencies (binaries, sources, javadoc of the artifacts without sources) into folders of `work`.
    Returns the binaries, the sources, the javadocs (each {coordinates: path}), the mode and the failed dependencies."""
    binaries_dir, sources_dir, javadoc_dir = (os.path.join(work, d) for d in ('binaries', 'sources', 'javadoc'))
    whole = os.path.join(work, 'whole')
    os.makedirs(whole)
    with open(os.path.join(whole, 'pom.xml'), 'w') as pom:
        pom.write(synthetic_pom('whole', dependencies, repositories))
    failed = []
    if copy_dependencies(whole, binaries_dir, log_path) == 0:
        mode, pom_dirs = 'whole', [whole]
    else:
        # One module per dependency in a reactor: a missing artifact makes only its own module fail
        mode = 'per-dependency'
        shutil.rmtree(binaries_dir, ignore_errors=True)
        split = os.path.join(work, 'split')
        os.makedirs(split)
        modules = []
        for index, dependency in enumerate(dependencies):
            module = f'd{index:04d}'
            os.makedirs(os.path.join(split, module))
            with open(os.path.join(split, module, 'pom.xml'), 'w') as pom:
                pom.write(synthetic_pom(module, [dependency], repositories))
            modules.append(module)
        with open(os.path.join(split, 'pom.xml'), 'w') as pom:
            pom.write(synthetic_pom('split', [], {}, modules))
        copy_dependencies(split, binaries_dir, log_path, fail_at_end=True)
        pom_dirs = [split]
        resolved = artifacts_in_layout(binaries_dir)
        resolved_keys = {(g, a) for g, a, _, _ in resolved}
        failed = [d for d in dependencies if (d['groupId'], d['artifactId']) not in resolved_keys]
    for pom_dir in pom_dirs:
        copy_dependencies(pom_dir, sources_dir, log_path, classifier='sources', fail_at_end=True)
    binaries = highest_versions(artifacts_in_layout(binaries_dir))
    sources = artifacts_in_layout(sources_dir)
    without_sources = {a for (g, a, v, c) in binaries if (g, a, v, 'sources') not in sources}
    javadocs = {}
    if without_sources:
        for pom_dir in pom_dirs:
            copy_dependencies(pom_dir, javadoc_dir, log_path, classifier='javadoc', include_artifacts=without_sources,
                              fail_at_end=True)
        javadocs = artifacts_in_layout(javadoc_dir)
    return binaries, sources, javadocs, mode, failed


# --------------------------------------------------------------------------------------------------------- Cache

def has_classes(jar_path):
    """Whether a jar has classes (not only a `module-info.class`, nor only classes of the multi-release folders)."""
    try:
        with zipfile.ZipFile(jar_path) as jar:
            return any(name.endswith('.class') and not name.endswith('module-info.class') and not name.startswith('META-INF/')
                       for name in jar.namelist())
    except zipfile.BadZipFile:
        return False


def cached_copy(cache, kind, coordinates, path):
    """The path of the file in the cache (copied there once)."""
    group_id, artifact_id, version, classifier = coordinates
    target_dir = os.path.join(cache, kind, *group_id.split('.'), artifact_id, version)
    target = os.path.join(target_dir, os.path.basename(path))
    if not os.path.exists(target):
        os.makedirs(target_dir, exist_ok=True)
        temporary = target + f'.tmp{os.getpid()}'
        shutil.copyfile(path, temporary)
        os.replace(temporary, target)
    return target


def unpacked_sources(cache, coordinates, sources_jar):
    """The folder of the `.java` files of a sources jar in the cache (unpacked there once). None if it has none."""
    group_id, artifact_id, version, classifier = coordinates
    target = os.path.join(cache, 'unpacked', *group_id.split('.'), artifact_id, version + (f'-{classifier}' if classifier else ''))
    if not os.path.isdir(target):
        temporary = tempfile.mkdtemp(prefix='.unpack-', dir=os.path.dirname(target) if os.path.isdir(os.path.dirname(target)) else _mkdir(os.path.dirname(target)))
        try:
            with zipfile.ZipFile(sources_jar) as jar:
                for member in jar.infolist():
                    name = member.filename
                    if member.is_dir() or not name.endswith('.java') or name.startswith('/') or '..' in name.split('/'):
                        continue
                    jar.extract(member, temporary)
        except zipfile.BadZipFile:
            shutil.rmtree(temporary, ignore_errors=True)
            return None
        try:
            os.rename(temporary, target)
        except OSError:
            # Unpacked meanwhile by another process
            shutil.rmtree(temporary, ignore_errors=True)
    return target if any(files for _, _, files in os.walk(target)) else None


def generated_stubs(cache, coordinates, jar, tracto_jar, log_path):
    """The folder of the stubs of a jar without sources in the cache (generated there once). None if none."""
    group_id, artifact_id, version, classifier = coordinates
    target = os.path.join(cache, f'stubs-v{STUBS_VERSION}', *group_id.split('.'), artifact_id, version + (f'-{classifier}' if classifier else ''))
    if not os.path.isdir(target):
        if not tracto_jar or not os.path.exists(tracto_jar):
            return None
        parent = _mkdir(os.path.dirname(target))
        temporary = tempfile.mkdtemp(prefix='.stubs-', dir=parent)
        java = os.path.join(os.environ['JAVA_HOME'], 'bin', 'java') if os.environ.get('JAVA_HOME') else 'java'
        with open(log_path, 'a') as log:
            status = subprocess.run([java, '-jar', tracto_jar, 'stubs', jar, temporary], stdout=log, stderr=subprocess.STDOUT,
                                    stdin=subprocess.DEVNULL).returncode
        if status != 0:
            shutil.rmtree(temporary, ignore_errors=True)
            return None
        try:
            os.rename(temporary, target)
        except OSError:
            shutil.rmtree(temporary, ignore_errors=True)
    return target if any(files for _, _, files in os.walk(target)) else None


def _mkdir(path):
    os.makedirs(path, exist_ok=True)
    return path


def pom_hash(repo):
    digest = hashlib.sha256()
    for directory, directories, files in os.walk(repo):
        directories[:] = sorted(d for d in directories if d not in ('.git', LIBS, 'target'))
        for name in sorted(files):
            if name == 'pom.xml':
                path = os.path.join(directory, name)
                digest.update(os.path.relpath(path, repo).encode())
                with open(path, 'rb') as pom:
                    digest.update(pom.read())
    return digest.hexdigest()


# ---------------------------------------------------------------------------------------------------------- Lombok

# The source roots of the modules whose files are delomboked
SOURCE_ROOTS = ('src/main/java', 'src/test/java')
# The folders never searched for source roots (the build output, the libraries)
SKIPPED_FOLDERS = {'.git', LIBS, 'target', 'node_modules'}
# The test classes generated by the pipeline (they are not part of the project)
GENERATED_TEST_CLASS = re.compile(r'STAR(SplitTest|NormalizedTest|SplitTestPrefix)\.java$')
USES_LOMBOK = re.compile(r'\blombok\.')
DELOMBOK_HEADER = re.compile(r'^// Generated by delombok at .*\n')


def java_command():
    java_home = os.environ.get('JAVA_HOME')
    return os.path.join(java_home, 'bin', 'java') if java_home else 'java'


def source_roots(repo):
    """The source roots of the modules of the repository (relative paths, sorted)."""
    roots = []
    for directory, directories, _ in os.walk(repo):
        directories[:] = sorted(d for d in directories if d not in SKIPPED_FOLDERS)
        relative = os.path.relpath(directory, repo).replace(os.sep, '/')
        if any(relative == root or relative.endswith('/' + root) for root in SOURCE_ROOTS):
            roots.append(relative)
            directories[:] = []
    return sorted(roots)


def delombok(repo, libs, lombok_jar, log_path, tracto_jar=None):
    """The delomboked copies of the files of the repository that use Lombok (Step 43): the members Lombok generates
    (getters, setters, builders, constructors) do not exist in the sources. For each source root, the files that use
    Lombok are delomboked by the Lombok jar of the project into `processed_libs/delombok/<folder>/` (the path of the
    root, `/` replaced by `__`), listed in `processed_libs/delombok/roots.json`. Delombok reprints the whole file and
    rewrites code of the developer (`@Builder.Default` initializers, `val`, comments): each copy is then the original
    file with the members delombok marks `@lombok.Generated` added (`java -jar tracto.jar lombok-members`; without the
    module jar, or for a file that cannot be merged, the output of delombok). The module gives these folders to the
    symbol solver before the sources and reads them for the members after the name of a class; the sources of the
    repository (the text seen by the model) are left unchanged. Without a classpath: the features of Lombok that need
    the types (`val`, `@Delegate`, `@ExtensionMethod`) may stay as they are."""
    start = time.time()
    report = {'status': 'ok', 'jar': lombok_jar, 'roots': 0, 'files': 0, 'merged': False, 'error': None}
    merge = bool(tracto_jar and os.path.exists(tracto_jar))
    output = os.path.join(libs, 'delombok')
    manifest = []
    with tempfile.TemporaryDirectory(prefix='tracto-delombok-') as work:
        for root in source_roots(repo):
            files = []
            for directory, directories, names in os.walk(os.path.join(repo, root)):
                directories.sort()
                for name in sorted(names):
                    path = os.path.join(directory, name)
                    if not name.endswith('.java') or GENERATED_TEST_CLASS.search(name):
                        continue
                    with open(path, encoding='utf-8', errors='replace') as source:
                        if USES_LOMBOK.search(source.read()):
                            files.append(os.path.relpath(path, os.path.join(repo, root)))
            if not files:
                continue
            folder = root.replace('/', '__')
            sources = os.path.join(work, folder)
            for relative in files:
                os.makedirs(os.path.dirname(os.path.join(sources, relative)), exist_ok=True)
                shutil.copyfile(os.path.join(repo, root, relative), os.path.join(sources, relative))
            target = os.path.join(output, folder)
            with open(log_path, 'a') as log:
                log.write(f'\n$ delombok {root} ({len(files)} files)\n')
                log.flush()
                code = subprocess.call([java_command(), '-jar', lombok_jar, 'delombok', '-e', 'UTF-8', '-d', target, sources],
                                       stdout=log, stderr=subprocess.STDOUT)
            copies = [relative for relative in files if os.path.exists(os.path.join(target, relative))]
            if code != 0 or len(copies) != len(files):
                report['status'] = 'partial'
                report['error'] = f'delombok of {root}: exit {code}, {len(copies)} of {len(files)} files (see maven.log)'
            for relative in copies:
                # Without the date of the header: the same copies at each run
                path = os.path.join(target, relative)
                with open(path, encoding='utf-8') as copy:
                    text = DELOMBOK_HEADER.sub('', copy.read(), count=1)
                with open(path, 'w', encoding='utf-8') as copy:
                    copy.write(text)
            if copies and merge:
                # The original files with the generated members
                with open(log_path, 'a') as log:
                    code = subprocess.call([java_command(), '-jar', tracto_jar, 'lombok-members', os.path.join(repo, root), target],
                                           stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
                if code != 0:
                    report['status'] = 'partial'
                    report['error'] = f'lombok-members of {root}: exit {code} (see maven.log)'
            if copies:
                manifest.append({'folder': folder, 'sourceRoot': root})
                report['roots'] += 1
                report['files'] += len(copies)
    if manifest:
        with open(os.path.join(output, 'roots.json'), 'w') as out:
            json.dump({'roots': manifest}, out, indent=1)
    report['merged'] = merge
    report['seconds'] = round(time.time() - start, 1)
    return report


# ---------------------------------------------------------------------------------------------------------- Main

def link_or_copy(source, target, copy):
    if copy:
        if os.path.isdir(source):
            shutil.copytree(source, target)
        else:
            shutil.copyfile(source, target)
    else:
        os.symlink(source, target)


def run(repo, cache, copy=False, refresh=False, tracto_jar=DEFAULT_TRACTO_JAR):
    repo = os.path.abspath(repo)
    cache = os.path.abspath(cache)
    start = time.time()
    report = {'version': REPORT_VERSION, 'repo': repo, 'status': 'failed', 'artifacts': [], 'failed': [],
              'unresolvable': [], 'error': None}
    libs = os.path.join(repo, LIBS)
    if os.path.lexists(libs):
        shutil.rmtree(libs)
    os.makedirs(libs)
    if not os.path.exists(os.path.join(repo, 'pom.xml')):
        report['error'] = 'no pom.xml at the root of the repository'
        return finish(report, libs, start)
    key = pom_hash(repo)
    report['pomHash'] = key
    resolution_path = os.path.join(cache, 'resolutions', key + '.json')
    log_path = os.path.join(libs, 'maven.log')
    resolution = None
    if not refresh and os.path.exists(resolution_path):
        with open(resolution_path) as cached:
            resolution = json.load(cached)
        if not all(os.path.exists(a['jar']) for a in resolution['artifacts']):
            resolution = None
    if resolution is None:
        resolution = resolve_repository(repo, cache, log_path, tracto_jar)
        if resolution['status'] != 'failed':
            os.makedirs(os.path.dirname(resolution_path), exist_ok=True)
            with open(resolution_path, 'w') as out:
                json.dump(resolution, out, indent=1)
        report['cached'] = False
    else:
        report['cached'] = True
    for field in ('status', 'mode', 'modules', 'failed', 'unresolvable', 'error'):
        report[field] = resolution.get(field)
    decompiled, jars = os.path.join(libs, 'decompiled'), os.path.join(libs, 'jars')
    for artifact in resolution['artifacts']:
        if report['cached'] and not artifact.get('unpackedSources') and artifact['hasClasses'] and not artifact.get('stubs'):
            # Resolved when the stubs could not be generated (no module jar yet): try again
            coordinates = (artifact['groupId'], artifact['artifactId'], artifact['version'], artifact['classifier'])
            artifact['stubs'] = generated_stubs(cache, coordinates, artifact['jar'], tracto_jar, log_path)
        name = f"{artifact['groupId']}__{artifact['artifactId']}-{artifact['version']}" + (f"-{artifact['classifier']}" if artifact['classifier'] else '')
        if artifact.get('unpackedSources'):
            os.makedirs(decompiled, exist_ok=True)
            link_or_copy(artifact['unpackedSources'], os.path.join(decompiled, name), copy)
            artifact['provided'] = 'sources'
        elif artifact.get('stubs'):
            os.makedirs(decompiled, exist_ok=True)
            link_or_copy(artifact['stubs'], os.path.join(decompiled, name), copy)
            artifact['provided'] = 'stubs'
        elif artifact['hasClasses']:
            os.makedirs(jars, exist_ok=True)
            link_or_copy(artifact['jar'], os.path.join(jars, name + '.jar'), copy)
            artifact['provided'] = 'jar'
        else:
            artifact['provided'] = 'none (no classes)'
        report['artifacts'].append(artifact)
    # The projects that use Lombok: the delomboked copies of their files (Step 43)
    lombok = [a for a in report['artifacts'] if (a['groupId'], a['artifactId']) == ('org.projectlombok', 'lombok') and not a['classifier']
              and a.get('jar') and os.path.exists(a['jar'])]
    if lombok:
        try:
            report['delombok'] = delombok(repo, libs, lombok[-1]['jar'], log_path, tracto_jar)
        except (OSError, subprocess.SubprocessError) as e:
            report['delombok'] = {'status': 'failed', 'error': str(e)}
    return finish(report, libs, start)


def resolve_repository(repo, cache, log_path, tracto_jar):
    resolution = {'status': 'failed', 'artifacts': [], 'failed': [], 'unresolvable': [], 'error': None}
    with tempfile.TemporaryDirectory(prefix='tracto-deps-') as work:
        effective = os.path.join(work, 'effective-pom.xml')
        if maven([f'{HELP_PLUGIN}:effective-pom', f'-Doutput={effective}'], repo, log_path) != 0 or not os.path.exists(effective):
            resolution['error'] = 'help:effective-pom failed (see maven.log)'
            return resolution
        projects = effective_projects(effective)
        dependencies, repositories, unresolvable = external_dependencies(projects)
        resolution['modules'] = len(projects)
        resolution['unresolvable'] = [f"{d['groupId']}:{d['artifactId']}:{d['version']}" for d in unresolvable]
        if not dependencies:
            resolution['status'], resolution['mode'] = 'ok', 'none'
            return resolution
        binaries, sources, javadocs, mode, failed = resolve(dependencies, repositories, work, log_path)
        resolution['mode'] = mode
        resolution['failed'] = [f"{d['groupId']}:{d['artifactId']}:{d['version']}" for d in failed]
        for coordinates, path in sorted(binaries.items()):
            group_id, artifact_id, version, classifier = coordinates
            sources_jar = sources.get((group_id, artifact_id, version, 'sources' if not classifier else f'{classifier}-sources')) \
                or sources.get((group_id, artifact_id, version, 'sources'))
            javadoc_jar = javadocs.get((group_id, artifact_id, version, 'javadoc'))
            artifact = {'groupId': group_id, 'artifactId': artifact_id, 'version': version, 'classifier': classifier,
                        'jar': cached_copy(cache, 'jars', coordinates, path), 'hasClasses': has_classes(path),
                        'sourcesJar': cached_copy(cache, 'jars', coordinates, sources_jar) if sources_jar else None,
                        'javadocJar': cached_copy(cache, 'jars', coordinates, javadoc_jar) if javadoc_jar else None,
                        'unpackedSources': None}
            if artifact['sourcesJar']:
                artifact['unpackedSources'] = unpacked_sources(cache, coordinates, artifact['sourcesJar'])
            artifact['stubs'] = None
            if not artifact['unpackedSources'] and artifact['hasClasses']:
                artifact['stubs'] = generated_stubs(cache, coordinates, artifact['jar'], tracto_jar, log_path)
            resolution['artifacts'].append(artifact)
        resolution['status'] = 'partial' if failed or unresolvable else 'ok'
    return resolution


def finish(report, libs, start):
    report['seconds'] = round(time.time() - start, 1)
    artifacts = report['artifacts']
    report['counts'] = {
        'artifacts': len(artifacts),
        'sources': sum(1 for a in artifacts if a.get('provided') == 'sources'),
        'stubs': sum(1 for a in artifacts if a.get('provided') == 'stubs'),
        'stubsWithJavadoc': sum(1 for a in artifacts if a.get('provided') == 'stubs' and a.get('javadocJar')),
        'jarOnly': sum(1 for a in artifacts if a.get('provided') == 'jar'),
        'jarOnlyWithJavadoc': sum(1 for a in artifacts if a.get('provided') == 'jar' and a.get('javadocJar')),
        'noClasses': sum(1 for a in artifacts if a.get('provided') == 'none (no classes)'),
        'failed': len(report['failed'] or []),
        'unresolvable': len(report['unresolvable'] or []),
    }
    with open(os.path.join(libs, 'dependencies.json'), 'w') as out:
        json.dump(report, out, indent=1)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('repo')
    parser.add_argument('--cache', default=os.environ.get('TRACTO_DEPS_CACHE', os.path.expanduser('~/.cache/tracto-deps')))
    parser.add_argument('--copy', action='store_true', help='copy the artifacts into the repository instead of linking them')
    parser.add_argument('--refresh', action='store_true', help='resolve again even if the POMs are unchanged')
    parser.add_argument('--tracto-jar', default=os.environ.get('TRACTO_JAR', DEFAULT_TRACTO_JAR),
                        help='the jar of the module, to generate the stubs of the jars without sources')
    args = parser.parse_args()
    report = run(args.repo, args.cache, args.copy, args.refresh, args.tracto_jar)
    print(json.dumps({k: report.get(k) for k in ('status', 'mode', 'modules', 'counts', 'seconds', 'error', 'cached')}))
    return 0 if report['status'] != 'failed' else 1


if __name__ == '__main__':
    sys.exit(main())
