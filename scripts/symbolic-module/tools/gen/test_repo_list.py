#!/usr/bin/env python3
"""Tests of repo_list.py (offline): python3 -m unittest tools/gen/test_repo_list.py"""
import csv
import io
import os
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
import repo_list as rl  # noqa: E402

METRICS = ('[{"commentLines":1,"language":"XML","blankLines":2,"codeLines":300},'
           '{"commentLines":5,"language":"Java","blankLines":9,"codeLines":700}]')
SEART_HEADER = ['id', 'name', 'isFork', 'commits', 'license', 'stargazers', 'contributors', 'createdAt', 'totalIssues',
                'codeLines', 'metrics', 'lastCommit', 'lastCommitSHA', 'isArchived', 'topics']


def row(id, name, license='MIT License', fork='false', sha=None, last='2026-10-01T00:00:00', lines='1000'):
    return {'id': id, 'name': name, 'isFork': fork, 'commits': '500', 'license': license, 'stargazers': '20',
            'contributors': '12', 'createdAt': '2015-01-01T00:00:00', 'totalIssues': '60', 'codeLines': lines,
            'metrics': METRICS, 'lastCommit': last, 'lastCommitSHA': sha or f'sha-{id}', 'isArchived': 'false',
            'topics': '["x"]'}


def read_bytes(path):
    with open(path, 'rb') as f:
        return f.read()


class RepoListTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.out = os.path.join(self.dir, 'out.csv')

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def seart(self, rows):
        path = os.path.join(self.dir, 'export.csv')
        with open(path, 'w', newline='') as f:
            writer = csv.DictWriter(f, SEART_HEADER, quoting=csv.QUOTE_ALL)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def build(self, rows):
        export = self.seart(rows)
        with redirect_stdout(io.StringIO()):
            counts = rl.build(export, self.out)
        with open(self.out, newline='') as f:
            return counts, list(csv.DictReader(f))

    def test_only_permissive_licences_are_kept(self):
        counts, rows = self.build([row('1', 'a/mit'), row('2', 'b/gpl', 'GNU General Public License v3.0'),
                                   row('3', 'c/none', ''), row('4', 'd/other', 'Other'),
                                   row('5', 'e/bsd', 'BSD 3-Clause New or Revised License')])
        self.assertEqual(['a/mit', 'e/bsd'], [r['name'] for r in rows])
        self.assertEqual(3, counts['licence not permissive'])

    def test_forks_are_dropped(self):
        counts, rows = self.build([row('1', 'a/x'), row('2', 'b/x', fork='true')])
        self.assertEqual(['a/x'], [r['name'] for r in rows])
        self.assertEqual(1, counts['fork'])

    def test_the_commit_of_the_export_is_kept(self):
        _, rows = self.build([row('1', 'a/x', sha='abc', last='2026-10-01T00:00:00')])
        self.assertEqual([('1', 'abc', '2026-10-01T00:00:00')],
                         [(r['id'], r['lastCommitSHA'], r['lastCommit']) for r in rows])

    def test_the_java_lines_come_from_the_metrics(self):
        no_java = dict(row('2', 'b/y'), metrics='[{"commentLines":0,"language":"XML","blankLines":0,"codeLines":5}]')
        _, rows = self.build([row('1', 'a/x', lines='1000'), no_java])
        self.assertEqual([('1000', '700'), ('1000', '0')], [(r['codeLines'], r['javaCodeLines']) for r in rows])

    def test_sorted_by_numeric_id_with_the_columns_needed_only(self):
        _, rows = self.build([row('10', 'a/x'), row('9', 'b/y'), row('100', 'c/z')])
        self.assertEqual(['9', '10', '100'], [r['id'] for r in rows])
        self.assertEqual(rl.COLUMNS, list(rows[0].keys()))
        self.assertNotIn('metrics', rows[0])

    def test_same_input_same_bytes(self):
        export = self.seart([row('2', 'b/y'), row('1', 'a/x')])
        with redirect_stdout(io.StringIO()):
            rl.build(export, self.out)
            first = read_bytes(self.out)
            rl.build(export, self.out)
        self.assertEqual(first, read_bytes(self.out))

    def test_an_unknown_licence_stops_without_writing(self):
        export = self.seart([row('1', 'a/x', 'Some New Licence')])
        with self.assertRaisesRegex(ValueError, 'Some New Licence'):
            rl.build(export, self.out)
        self.assertFalse(os.path.exists(self.out))

    def test_an_unexpected_fork_value_stops(self):
        export = self.seart([row('1', 'a/x', fork='')])
        with self.assertRaisesRegex(ValueError, 'isFork'):
            rl.build(export, self.out)

    def test_a_name_twice_stops(self):
        export = self.seart([row('1', 'a/x'), row('2', 'A/X')])
        with self.assertRaisesRegex(ValueError, 'a/x'):
            rl.build(export, self.out)

    def test_the_exports_of_the_repository_classify_every_licence(self):
        # every licence string of the real exports is either permissive or known as not permissive
        resources = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..', 'resources')
        for name in ['seart-github-repos-updated.csv', 'repositories-after-cutoff.csv']:
            path = os.path.join(resources, name)
            if not os.path.exists(path):
                continue
            csv.field_size_limit(1 << 30)
            with open(path, newline='', encoding='utf-8') as f:
                licences = {r['license'] for r in csv.DictReader(f)}
            self.assertEqual(set(), licences - rl.PERMISSIVE - rl.NOT_PERMISSIVE, name)


if __name__ == '__main__':
    unittest.main()
