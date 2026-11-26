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

SEART_HEADER = ['id', 'name', 'isFork', 'commits', 'license', 'stargazers', 'contributors', 'createdAt', 'totalIssues',
                'codeLines', 'metrics', 'lastCommit', 'lastCommitSHA', 'isArchived', 'topics']


def row(id, name, license='MIT License', fork='false', sha=None, last='2026-10-01T00:00:00', lines='1000'):
    return {'id': id, 'name': name, 'isFork': fork, 'commits': '500', 'license': license, 'stargazers': '20',
            'contributors': '12', 'createdAt': '2015-01-01T00:00:00', 'totalIssues': '60', 'codeLines': lines,
            'metrics': '[{"a": 1}]', 'lastCommit': last, 'lastCommitSHA': sha or f'sha-{id}', 'isArchived': 'false',
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

    def seart(self, name, rows):
        path = os.path.join(self.dir, name)
        with open(path, 'w', newline='') as f:
            writer = csv.DictWriter(f, SEART_HEADER, quoting=csv.QUOTE_ALL)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def merge(self, new_rows, old_rows):
        new, old = self.seart('new.csv', new_rows), self.seart('old.csv', old_rows)
        with redirect_stdout(io.StringIO()):
            counts = rl.merge(new, old, self.out)
        with open(self.out, newline='') as f:
            return counts, list(csv.DictReader(f))

    def test_only_permissive_licences_are_kept(self):
        counts, rows = self.merge([row('1', 'a/mit'), row('2', 'b/gpl', 'GNU General Public License v3.0'),
                                   row('3', 'c/none', ''), row('4', 'd/other', 'Other'),
                                   row('5', 'e/bsd', 'BSD 3-Clause New or Revised License')], [])
        self.assertEqual(['a/mit', 'e/bsd'], [r['name'] for r in rows])
        self.assertEqual(3, counts['new: licence not permissive'])

    def test_forks_are_dropped(self):
        counts, rows = self.merge([row('1', 'a/x'), row('2', 'b/x', fork='true')], [row('3', 'c/y', fork='true')])
        self.assertEqual(['a/x'], [r['name'] for r in rows])
        self.assertEqual(1, counts['new: fork'])
        self.assertEqual(1, counts['old: fork'])

    def test_a_repository_in_both_exports_keeps_the_row_of_the_new_one(self):
        _, rows = self.merge([row('1', 'a/x', sha='new-sha', last='2026-10-01T00:00:00')],
                             [row('1', 'a/x', sha='old-sha', last='2025-04-01T00:00:00')])
        self.assertEqual([('1', 'new-sha', '2026-10-01T00:00:00', 'new+old')],
                         [(r['id'], r['lastCommitSHA'], r['lastCommit'], r['source']) for r in rows])

    def test_the_licence_of_the_new_export_decides(self):
        # liquibase: Apache 2.0 in the old export, "Other" in the new one
        counts, rows = self.merge([row('1', 'a/x', 'Other'), row('2', 'b/y', 'MIT License')],
                                  [row('1', 'a/x', 'Apache License 2.0'), row('2', 'b/y', '')])
        self.assertEqual(['b/y'], [r['name'] for r in rows])
        self.assertEqual(1, counts['new: licence not permissive'])
        self.assertEqual(0, counts['old: licence not permissive'])

    def test_same_name_with_another_id_is_the_same_repository(self):
        # a repository deleted and created again under the same name gets a new SEART id
        counts, rows = self.merge([row('95', 'Owner/Repo', sha='new-sha')], [row('7', 'owner/repo', sha='old-sha')])
        self.assertEqual([('95', 'new-sha', 'new+old')], [(r['id'], r['lastCommitSHA'], r['source']) for r in rows])
        self.assertEqual(1, counts['old: also in the new export'])

    def test_a_repository_only_in_the_old_export_is_kept(self):
        _, rows = self.merge([row('2', 'b/y')], [row('1', 'a/x', sha='old-sha', last='2025-04-01T00:00:00')])
        self.assertEqual([('1', 'old-sha', '2025-04-01T00:00:00', 'old'), ('2', 'sha-2', '2026-10-01T00:00:00', 'new')],
                         [(r['id'], r['lastCommitSHA'], r['lastCommit'], r['source']) for r in rows])

    def test_sorted_by_numeric_id_with_the_columns_needed_only(self):
        _, rows = self.merge([row('10', 'a/x'), row('9', 'b/y')], [row('100', 'c/z')])
        self.assertEqual(['9', '10', '100'], [r['id'] for r in rows])
        self.assertEqual(rl.COLUMNS, list(rows[0].keys()))
        self.assertNotIn('metrics', rows[0])

    def test_same_inputs_same_bytes(self):
        new, old = self.seart('new.csv', [row('2', 'b/y'), row('1', 'a/x')]), self.seart('old.csv', [row('3', 'c/z')])
        with redirect_stdout(io.StringIO()):
            rl.merge(new, old, self.out)
            first = read_bytes(self.out)
            rl.merge(new, old, self.out)
        self.assertEqual(first, read_bytes(self.out))

    def test_an_unknown_licence_stops_without_writing(self):
        new, old = self.seart('new.csv', [row('1', 'a/x', 'Some New Licence')]), self.seart('old.csv', [])
        with self.assertRaisesRegex(ValueError, 'Some New Licence'):
            rl.merge(new, old, self.out)
        self.assertFalse(os.path.exists(self.out))

    def test_an_unexpected_fork_value_stops(self):
        new, old = self.seart('new.csv', [row('1', 'a/x', fork='')]), self.seart('old.csv', [])
        with self.assertRaisesRegex(ValueError, 'isFork'):
            rl.merge(new, old, self.out)

    def test_a_name_twice_in_one_export_stops(self):
        new, old = self.seart('new.csv', [row('1', 'a/x'), row('2', 'A/X')]), self.seart('old.csv', [])
        with self.assertRaisesRegex(ValueError, 'a/x'):
            rl.merge(new, old, self.out)

    def test_the_two_exports_of_the_repository_classify_every_licence(self):
        # every licence string of the real exports is either permissive or known as not permissive
        resources = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..', 'resources')
        for name in ['seart-github-repos-info.csv', 'seart-github-repos-updated.csv']:
            path = os.path.join(resources, name)
            if not os.path.exists(path):
                continue
            csv.field_size_limit(1 << 30)
            with open(path, newline='', encoding='utf-8') as f:
                licences = {r['license'] for r in csv.DictReader(f)}
            self.assertEqual(set(), licences - rl.PERMISSIVE - rl.NOT_PERMISSIVE, name)


if __name__ == '__main__':
    unittest.main()
