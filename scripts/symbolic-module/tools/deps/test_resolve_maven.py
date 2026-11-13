#!/usr/bin/env python3
"""Tests of resolve_maven.py. The unit tests run offline; the integration test needs Maven and the network and runs
only with TRACTO_DEPS_IT=1:

  python3 -m unittest tools/deps/test_resolve_maven.py
  TRACTO_DEPS_IT=1 python3 -m unittest tools/deps/test_resolve_maven.py
"""
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import resolve_maven as rm  # noqa: E402

EFFECTIVE_POM = """<?xml version="1.0" encoding="UTF-8"?>
<projects>
  <project xmlns="http://maven.apache.org/POM/4.0.0">
    <groupId>x</groupId><artifactId>m1</artifactId><version>1-SNAPSHOT</version>
    <dependencyManagement><dependencies>
      <dependency><groupId>managed.only</groupId><artifactId>never</artifactId><version>1</version></dependency>
    </dependencies></dependencyManagement>
    <dependencies>
      <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.2.1-jre</version></dependency>
      <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.12</version><scope>test</scope>
        <exclusions><exclusion><groupId>org.hamcrest</groupId><artifactId>hamcrest-core</artifactId></exclusion></exclusions>
      </dependency>
      <dependency><groupId>bad</groupId><artifactId>unset</artifactId><version>${missing.version}</version></dependency>
    </dependencies>
    <repositories><repository><id>central</id><url>https://repo.maven.apache.org/maven2</url></repository></repositories>
    <build><plugins><plugin><artifactId>p</artifactId>
      <dependencies><dependency><groupId>plugin.only</groupId><artifactId>dep</artifactId><version>1</version></dependency></dependencies>
    </plugin></plugins></build>
  </project>
  <project xmlns="http://maven.apache.org/POM/4.0.0">
    <groupId>x</groupId><artifactId>m2</artifactId><version>1-SNAPSHOT</version>
    <dependencies>
      <dependency><groupId>x</groupId><artifactId>m1</artifactId><version>1-SNAPSHOT</version></dependency>
      <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
      <dependency><groupId>x</groupId><artifactId>m1</artifactId><version>1-SNAPSHOT</version><type>test-jar</type><scope>test</scope></dependency>
    </dependencies>
  </project>
</projects>
"""


class VersionOrderTest(unittest.TestCase):

    def test_numbers_compare_as_numbers(self):
        self.assertGreater(rm.version_key('4.13.2'), rm.version_key('4.9'))
        self.assertGreater(rm.version_key('1.10'), rm.version_key('1.9.9'))

    def test_a_release_is_after_its_qualifiers(self):
        self.assertGreater(rm.version_key('1.0'), rm.version_key('1.0-RC1'))
        self.assertGreater(rm.version_key('1.0'), rm.version_key('1.0-SNAPSHOT'))


class ExternalDependenciesTest(unittest.TestCase):

    def setUp(self):
        self.work = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.work)
        self.path = os.path.join(self.work, 'effective.xml')
        with open(self.path, 'w') as out:
            out.write(EFFECTIVE_POM)

    def test_the_modules_of_the_repository_are_left_out_and_one_version_is_kept(self):
        projects = rm.effective_projects(self.path)
        self.assertEqual(2, len(projects))
        dependencies, repositories, unresolvable = rm.external_dependencies(projects)
        coordinates = [(d['groupId'], d['artifactId'], d['version']) for d in dependencies]
        # Not the sibling module (jar nor test-jar), not the managed-only nor the plugin dependencies; the highest JUnit
        self.assertEqual([('com.google.guava', 'guava', '33.2.1-jre'), ('junit', 'junit', '4.13.2')], coordinates)
        self.assertEqual({'https://repo.maven.apache.org/maven2': 'central'}, repositories)
        self.assertEqual(['bad'], [d['groupId'] for d in unresolvable])

    def test_the_synthetic_pom_keeps_scope_and_exclusions(self):
        dependencies, repositories, _ = rm.external_dependencies(rm.effective_projects(self.path))
        dependencies = [dict(d, exclusions=[('org.hamcrest', 'hamcrest-core')]) if d['artifactId'] == 'junit' else d
                        for d in dependencies]
        pom = rm.synthetic_pom('whole', dependencies, repositories)
        self.assertIn('<scope>test</scope>', pom)
        self.assertIn('<artifactId>hamcrest-core</artifactId>', pom)
        self.assertIn('<packaging>pom</packaging>', pom)


class LayoutTest(unittest.TestCase):

    def test_coordinates_from_the_repository_layout(self):
        root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root)
        for relative in ('com/google/guava/guava/33.2.1-jre/guava-33.2.1-jre.jar',
                         'com/google/guava/guava/33.2.1-jre/guava-33.2.1-jre-sources.jar',
                         'junit/junit/4.12/junit-4.12.jar', 'junit/junit/4.13.2/junit-4.13.2.jar'):
            os.makedirs(os.path.join(root, os.path.dirname(relative)), exist_ok=True)
            open(os.path.join(root, relative), 'w').close()
        found = rm.artifacts_in_layout(root)
        self.assertIn(('com.google.guava', 'guava', '33.2.1-jre', ''), found)
        self.assertIn(('com.google.guava', 'guava', '33.2.1-jre', 'sources'), found)
        highest = rm.highest_versions(found)
        self.assertIn(('junit', 'junit', '4.13.2', ''), highest)
        self.assertNotIn(('junit', 'junit', '4.12', ''), highest)


class HasClassesTest(unittest.TestCase):

    def test_a_jar_with_only_a_module_descriptor_has_no_classes(self):
        import zipfile
        work = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, work)
        cases = {'module.jar': ['META-INF/versions/11/module-info.class'], 'root-module.jar': ['module-info.class'],
                 'classes.jar': ['module-info.class', 'org/lib/Box.class'], 'empty.jar': ['META-INF/MANIFEST.MF']}
        for name, entries in cases.items():
            with zipfile.ZipFile(os.path.join(work, name), 'w') as jar:
                for entry in entries:
                    jar.writestr(entry, b'')
        self.assertEqual({'module.jar': False, 'root-module.jar': False, 'classes.jar': True, 'empty.jar': False},
                         {name: rm.has_classes(os.path.join(work, name)) for name in cases})


LOMBOK_JAR = os.path.expanduser('~/.m2/repository/org/projectlombok/lombok/1.18.42/lombok-1.18.42.jar')
TRACTO_JAR = rm.DEFAULT_TRACTO_JAR


@unittest.skipUnless(os.path.exists(LOMBOK_JAR) and os.path.exists(TRACTO_JAR) and shutil.which('java'),
                     'needs java, the Lombok jar in ~/.m2 and the jar of the module')
class DelombokTest(unittest.TestCase):
    """The delomboked copies of the files that use Lombok (Step 43), for the symbol solver only."""

    def write(self, path, text):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as out:
            out.write(text)

    def test_the_files_that_use_lombok_are_copied_delomboked(self):
        repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, repo)
        protocol = ('package com.acme;\n\nimport lombok.Getter;\n\n@Getter\npublic enum Protocol {\n    V16("1.6");\n\n'
                    '    private final String version;\n\n    Protocol(String version) {\n        // the version\n'
                    '        this.version = version;\n    }\n}\n')
        self.write(os.path.join(repo, 'core/src/main/java/com/acme/Protocol.java'), protocol)
        self.write(os.path.join(repo, 'core/src/main/java/com/acme/Plain.java'), 'package com.acme;\n\npublic class Plain {\n}\n')
        self.write(os.path.join(repo, 'core/src/test/java/com/acme/Dto.java'),
                   'package com.acme;\n\n@lombok.Data\npublic class Dto {\n    private int size;\n}\n')
        self.write(os.path.join(repo, 'core/src/test/java/com/acme/DtoTestSTARSplitTest.java'),
                   'package com.acme;\n\n@lombok.Data\npublic class DtoTestSTARSplitTest {\n    private int size;\n}\n')
        self.write(os.path.join(repo, 'core/target/generated-sources/com/acme/Gen.java'), 'package com.acme;\n@lombok.Data class Gen {}\n')
        libs = os.path.join(repo, rm.LIBS)
        os.makedirs(libs)

        report = rm.delombok(repo, libs, LOMBOK_JAR, os.path.join(libs, 'maven.log'), TRACTO_JAR)

        self.assertEqual('ok', report['status'], report)
        self.assertTrue(report['merged'])
        self.assertEqual(2, report['files'])
        delombok = os.path.join(libs, 'delombok')
        with open(os.path.join(delombok, 'roots.json')) as manifest:
            self.assertEqual({'roots': [{'folder': 'core__src__main__java', 'sourceRoot': 'core/src/main/java'},
                                        {'folder': 'core__src__test__java', 'sourceRoot': 'core/src/test/java'}]},
                             json.load(manifest))
        with open(os.path.join(delombok, 'core__src__main__java/com/acme/Protocol.java')) as copy:
            text = copy.read()
        self.assertIn('public String getVersion()', text)
        self.assertNotIn('Generated by delombok at', text)  # no date: the same output at each run
        # The original file, unchanged, with the generated members before the closing brace of the type
        self.assertTrue(text.startswith(protocol[:protocol.rindex('}')]), text)
        with open(os.path.join(delombok, 'core__src__test__java/com/acme/Dto.java')) as copy:
            self.assertIn('public int getSize()', copy.read())
        # Only the files that use Lombok, not the classes generated by the pipeline; the sources unchanged
        self.assertFalse(os.path.exists(os.path.join(delombok, 'core__src__main__java/com/acme/Plain.java')))
        self.assertFalse(os.path.exists(os.path.join(delombok, 'core__src__test__java/com/acme/DtoTestSTARSplitTest.java')))
        with open(os.path.join(repo, 'core/src/main/java/com/acme/Protocol.java')) as source:
            self.assertEqual(protocol, source.read())

    def test_no_file_using_lombok_writes_nothing(self):
        repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, repo)
        self.write(os.path.join(repo, 'src/main/java/com/acme/Plain.java'), 'package com.acme;\n\npublic class Plain {\n}\n')
        libs = os.path.join(repo, rm.LIBS)
        os.makedirs(libs)
        report = rm.delombok(repo, libs, LOMBOK_JAR, os.path.join(libs, 'maven.log'))
        self.assertEqual({'status': 'ok', 'files': 0}, {k: report[k] for k in ('status', 'files')})
        self.assertFalse(os.path.exists(os.path.join(libs, 'delombok')))


@unittest.skipUnless(os.environ.get('TRACTO_DEPS_IT') == '1', 'needs Maven and the network (TRACTO_DEPS_IT=1)')
class MultiModuleIntegrationTest(unittest.TestCase):
    """A module depending on a sibling module that is not built, a missing artifact, a managed version."""

    PARENT = """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>x</groupId><artifactId>parent</artifactId><version>1-SNAPSHOT</version><packaging>pom</packaging>
<modules><module>m1</module><module>m2</module></modules>
<dependencyManagement><dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId>
<version>4.13.2</version></dependency></dependencies></dependencyManagement></project>"""
    M1 = """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<parent><groupId>x</groupId><artifactId>parent</artifactId><version>1-SNAPSHOT</version></parent><artifactId>m1</artifactId>
<dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId><scope>test</scope></dependency>
<dependency><groupId>does.not</groupId><artifactId>exist</artifactId><version>1.0</version></dependency></dependencies></project>"""
    M2 = """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<parent><groupId>x</groupId><artifactId>parent</artifactId><version>1-SNAPSHOT</version></parent><artifactId>m2</artifactId>
<dependencies><dependency><groupId>x</groupId><artifactId>m1</artifactId><version>1-SNAPSHOT</version></dependency></dependencies></project>"""

    def test_resolves_the_external_dependencies(self):
        work = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, work)
        repo, cache = os.path.join(work, 'repo'), os.path.join(work, 'cache')
        for relative, content in (('pom.xml', self.PARENT), ('m1/pom.xml', self.M1), ('m2/pom.xml', self.M2)):
            os.makedirs(os.path.dirname(os.path.join(repo, relative)), exist_ok=True)
            with open(os.path.join(repo, relative), 'w') as out:
                out.write(content)
        report = rm.run(repo, cache)
        self.assertEqual('partial', report['status'])
        self.assertEqual('per-dependency', report['mode'])
        self.assertEqual(['does.not:exist:1.0'], report['failed'])
        provided = {(a['artifactId'], a['version']): a['provided'] for a in report['artifacts']}
        self.assertEqual({('junit', '4.13.2'): 'sources', ('hamcrest-core', '1.3'): 'sources'}, provided)
        libs = os.path.join(repo, rm.LIBS)
        self.assertTrue(os.path.isfile(os.path.join(libs, 'decompiled', 'junit__junit-4.13.2', 'org', 'junit', 'Assert.java')))
        self.assertEqual(report['counts'], json.load(open(os.path.join(libs, 'dependencies.json')))['counts'])
        # Second run: from the cache, the same artifacts
        again = rm.run(repo, cache)
        self.assertTrue(again['cached'])
        self.assertEqual(provided, {(a['artifactId'], a['version']): a['provided'] for a in again['artifacts']})


if __name__ == '__main__':
    unittest.main()
