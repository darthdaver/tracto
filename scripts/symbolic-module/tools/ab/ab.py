#!/usr/bin/env python3
"""A/B runs of the symbolic module on the reference projects (outputs in tmp/ab/<label>, git-ignored).

Requirements: Python 3.9+, Maven, the JDK of TRACTO_JAVA_HOME (default: the project-local Corretto 21.0.6), the JDK
sources in lib/ (bash/utils/setup-jdk-sources.sh or the archive of LIB_JDKS_LINK), the input repositories in
TRACTO_INPUT (default: <repository>/input/github-repos) at the commits listed in docs/missing-candidate-tokens.md.

Subcommands:
  build <commit|WORKTREE> <jar>
      Builds the jar of a commit in a temporary git worktree (WORKTREE: the working tree of the module).
  run <label> <jar> <config> [-j N] [--tokens] [--projects id,...] [--tokens-projects id,...] [--jvm-options "..."]
      Runs the oracles stage on fresh copies of the projects into tmp/ab/<label>, one project after another (-j 1, the
      default: the projects are not run in parallel), and the tokens stage with --tokens (on crawler-commons, or on --tokens-projects). The two
      sides of an A/B are run at the same time as two commands. Records for each project the exit status, the wall and
      CPU time and the peak memory, and checks that the run is complete: exit status 0, statistics written,
      "Oracles datapoints generated" in the log, no OutOfMemoryError; checks that nothing is written outside the
      output folders; records the fingerprint of the environment in summary.json. Exits with 1 if a run is not
      complete.
  fingerprint
      Prints the fingerprint of the environment: the JDK running the pipeline, the JDK sources, the input
      repositories (commit and status), the runner.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

# The paths are derived from the location of this script (scripts/symbolic-module/tools/ab); the input repositories and
# the JDK running the pipeline can be set with TRACTO_INPUT and TRACTO_JAVA_HOME
MODULE = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
REPO_ROOT = os.path.abspath(os.path.join(MODULE, '..', '..'))
INPUT = os.environ.get('TRACTO_INPUT', os.path.join(REPO_ROOT, 'input', 'github-repos'))
JAVA_HOME = os.environ.get('TRACTO_JAVA_HOME', os.path.join(REPO_ROOT, 'scripts', 'utils', 'resources', 'sdkman', 'candidates', 'java', '21.0.6-amzn'))
JAVA = os.path.join(JAVA_HOME, 'bin', 'java')
AB = os.path.join(MODULE, 'tmp', 'ab')
JAVA_VERSION_ARG = 'auto'
# The longest runs first (with -j > 1, the parallel runs end together)
PROJECTS = [
    ('crawler', 'crawler-commons/crawler-commons'),
    ('ffmpeg', 'bramp/ffmpeg-cli-wrapper'),
    ('jsoup', 'jhy/jsoup'),
    ('eec', 'wangguanquan/eec'),
    ('plexus', 'codehaus-plexus/plexus-utils'),
    ('esapi', 'esapi/esapi-java-legacy'),
    ('cryptofs', 'cryptomator/cryptofs'),
    ('uuid', 'cowtowncoder/java-uuid-generator'),
    ('steve', 'steve-community/steve'),
]
DEFAULT_TOKENS_PROJECTS = {'crawler'}
print_lock = threading.Lock()


def log(message):
    with print_lock:
        print(f'{time.strftime("%H:%M:%S")} {message}', flush=True)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def git(*args, cwd=REPO_ROOT):
    return subprocess.run(['git', *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def build(commit, jar):
    """Builds the jar of a commit in a temporary worktree, or of the working tree (WORKTREE)."""
    os.makedirs(os.path.dirname(os.path.abspath(jar)), exist_ok=True)
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    if commit == 'WORKTREE':
        subprocess.run(['mvn', '-q', '-o', 'clean', 'package', '-DskipTests'], cwd=MODULE, env=env, check=True)
        shutil.copy(os.path.join(MODULE, 'target', 'tracto-jar-with-dependencies.jar'), jar)
        return
    full = git('rev-parse', commit)
    worktree = os.path.join(AB, f'wt-{full[:8]}')
    if os.path.exists(worktree):
        git('worktree', 'remove', '--force', worktree)
    git('worktree', 'add', '--detach', worktree, full)
    try:
        module = os.path.join(worktree, 'scripts', 'symbolic-module')
        subprocess.run(['mvn', '-q', '-o', 'clean', 'package', '-DskipTests'], cwd=module, env=env, check=True)
        shutil.copy(os.path.join(module, 'target', 'tracto-jar-with-dependencies.jar'), jar)
    finally:
        git('worktree', 'remove', '--force', worktree)


def run_process(command, log_path, cwd):
    """Runs a command with its output in a log file; returns the exit status, wall time, CPU time and peak memory."""
    start = time.time()
    with open(log_path, 'w') as log_file:
        process = subprocess.Popen(command, cwd=cwd, stdout=log_file, stderr=subprocess.STDOUT)
        _, status, usage = os.wait4(process.pid, 0)
    process.returncode = os.waitstatus_to_exitcode(status)
    return {
        'exit': process.returncode,
        'wall_s': round(time.time() - start, 1),
        'cpu_s': round(usage.ru_utime + usage.ru_stime, 1),
        # ru_maxrss is in bytes on macOS, in kilobytes on Linux
        'max_rss_mb': round(usage.ru_maxrss / ((1 << 20) if sys.platform == 'darwin' else (1 << 10))),
    }


def run_project(label, jar, config, project_id, relative_path, tokens, jvm_options):
    base = os.path.join(AB, label, project_id)
    shutil.rmtree(base, ignore_errors=True)
    os.makedirs(base)
    repo, out = os.path.join(base, 'repo'), os.path.join(base, 'out')
    # The pipeline writes the generated test classes into the repository: each run works on its own copy
    subprocess.run(['cp', '-R', os.path.join(INPUT, relative_path), repo], check=True)
    result = {'project': project_id}
    result['oracles'] = run_process([JAVA, *jvm_options, '-jar', jar, 'oracles', project_id, repo, out, MODULE, JAVA_VERSION_ARG, config],
                                    os.path.join(base, 'log.txt'), MODULE)
    statistics = os.path.join(out, 'statistics-oracles-dataset-training', project_id, f'statistics-{project_id}.json')
    log_text = open(os.path.join(base, 'log.txt'), errors='replace').read()
    problems = []
    if result['oracles']['exit'] != 0:
        problems.append(f'oracles exit status {result["oracles"]["exit"]}')
    if not os.path.exists(statistics):
        problems.append('no statistics')
    if 'Oracles datapoints generated' not in log_text:
        problems.append('the log does not end the generation')
    if 'OutOfMemoryError' in log_text:
        problems.append('OutOfMemoryError')
    if os.path.exists(statistics):
        result['datapoints'] = json.load(open(statistics)).get('oracleDatapoints')
    if tokens and not problems:
        result['tokens'] = run_process([JAVA, *jvm_options, '-jar', jar, 'tokens', project_id, repo, out, MODULE, JAVA_VERSION_ARG],
                                       os.path.join(base, 'log-tokens.txt'), MODULE)
        tokens_dir = os.path.join(out, 'raw-tokens-dataset-training', project_id)
        tokens_log = open(os.path.join(base, 'log-tokens.txt'), errors='replace').read()
        if result['tokens']['exit'] != 0:
            problems.append(f'tokens exit status {result["tokens"]["exit"]}')
        if not os.path.isdir(tokens_dir) or not os.listdir(tokens_dir):
            problems.append('no tokens dataset')
        if 'OutOfMemoryError' in tokens_log:
            problems.append('OutOfMemoryError in the tokens stage')
    result['problems'] = problems
    json.dump(result, open(os.path.join(base, 'run.json'), 'w'), indent=2)
    return result


def run(label, jar, config, jobs, tokens, project_ids=None, tokens_projects=DEFAULT_TOKENS_PROJECTS, jvm_options=()):
    projects = [p for p in PROJECTS if project_ids is None or p[0] in project_ids]
    os.makedirs(os.path.join(AB, label), exist_ok=True)
    marker = os.path.join(AB, label, '.start')
    open(marker, 'w').close()
    results = []
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {pool.submit(run_project, label, jar, config, pid, rel, tokens and pid in tokens_projects, list(jvm_options)): pid for pid, rel in projects}
        for future in as_completed(futures):
            result = future.result()
            results.append(result)
            stages = ', '.join(f'{stage} {result[stage]["wall_s"]} s wall, {result[stage]["cpu_s"]} s CPU, {result[stage]["max_rss_mb"]} MB'
                               for stage in ('oracles', 'tokens') if stage in result)
            log(f'{label} {result["project"]}: {result.get("datapoints")} datapoints; {stages}'
                + (f'; PROBLEMS: {result["problems"]}' if result['problems'] else ''))
    # The pipeline must not write outside its output folder and the copies of the repositories (the IDE writes its
    # own files in .idea at any time)
    environment = fingerprint()
    written = subprocess.run(['find', MODULE, '-newer', marker, '-type', 'f', '-not', '-path', f'{MODULE}/tmp/*',
                              '-not', '-path', f'{MODULE}/target/*', '-not', '-path', f'{MODULE}/.idea/*'], capture_output=True, text=True).stdout.split()
    summary = {'label': label, 'jar': jar, 'jar_sha256': sha256(jar), 'config': config, 'jobs': jobs, 'jvm_options': list(jvm_options),
               'results': sorted(results, key=lambda r: r['project']), 'written_outside': written,
               'environment': environment}
    json.dump(summary, open(os.path.join(AB, label, 'summary.json'), 'w'), indent=2)
    incomplete = [r['project'] for r in results if r['problems']]
    if written:
        log(f'{label}: FILES WRITTEN OUTSIDE THE OUTPUT FOLDERS: {written[:10]}')
    if incomplete or written:
        log(f'{label}: NOT COMPLETE ({incomplete})')
        return False
    log(f'{label}: complete')
    return True


def fingerprint():
    """The environment of a run, besides the code: the JDK running the pipeline, the JDK sources given to the symbol
    solver, the input repositories, the runner and its arguments."""
    java = subprocess.run([JAVA, '-version'], capture_output=True, text=True).stderr.strip()
    lib = os.path.join(MODULE, 'lib')
    jdk_sources = {}
    for entry in sorted(os.listdir(lib)):
        path = os.path.join(lib, entry)
        if os.path.isdir(path):
            files = size = 0
            for directory, _, names in os.walk(path):
                for name in names:
                    files += 1
                    size += os.path.getsize(os.path.join(directory, name))
            jdk_sources[entry] = {'files': files, 'bytes': size}
    inputs = {}
    for pid, rel in PROJECTS:
        repo = os.path.join(INPUT, rel)
        status = git('status', '--porcelain', cwd=repo)
        inputs[pid] = {'head': git('rev-parse', 'HEAD', cwd=repo), 'status_sha256': hashlib.sha256(status.encode()).hexdigest()}
    return {
        'java': java,
        'java_version_argument': JAVA_VERSION_ARG,
        'java_classpaths_json_sha256': sha256(os.path.join(lib, 'java_classpaths.json')),
        'jdk_sources': jdk_sources,
        'inputs': inputs,
        'runner_sha256': sha256(os.path.abspath(__file__)),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('build'); p.add_argument('commit'); p.add_argument('jar')
    p = commands.add_parser('run'); p.add_argument('label'); p.add_argument('jar'); p.add_argument('config')
    p.add_argument('-j', type=int, default=1); p.add_argument('--tokens', action='store_true'); p.add_argument('--projects')
    p.add_argument('--tokens-projects', help='the projects of the tokens stage (default: crawler)')
    p.add_argument('--jvm-options', default='', help='options of the JVM running the pipeline, e.g. "-Xmx16g"')
    commands.add_parser('fingerprint')
    args = parser.parse_args()
    if args.command == 'build':
        build(args.commit, args.jar)
    elif args.command == 'run':
        ok = run(args.label, os.path.abspath(args.jar), os.path.abspath(args.config), args.j, args.tokens,
                 set(args.projects.split(',')) if args.projects else None,
                 set(args.tokens_projects.split(',')) if args.tokens_projects else DEFAULT_TOKENS_PROJECTS,
                 args.jvm_options.split())
        sys.exit(0 if ok else 1)
    elif args.command == 'fingerprint':
        print(json.dumps(fingerprint(), indent=2))


if __name__ == '__main__':
    main()
