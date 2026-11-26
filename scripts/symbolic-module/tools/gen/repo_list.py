#!/usr/bin/env python3
"""The list of repositories of the ISSTA 2027 datasets, from two SEART exports (docs/data-collection-issta2027.md).

Usage: repo_list.py [--new CSV] [--old CSV] [--out CSV]

Keeps the union of the two exports, matched by SEART id or by name (ignoring case: a repository deleted and created
again under the same name gets a new id). When a repository is in both, the row of the new export is kept, with its
newer commit and its current licence. Drops the forks (they contain the tests of the repository they come from) and
the licences that are not permissive (the datasets and the models will be published). A licence string that is in
neither list stops the script, so a new name is classified by hand and never dropped silently.

Writes the repositories sorted by SEART id, with the columns of COLUMNS only (`select` of generate.py reads `id`,
`name`, `lastCommitSHA` and `codeLines`), and prints the count of each filter. Same inputs, same bytes.
"""
import argparse
import collections
import csv
import os
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..'))
RESOURCES = os.path.join(REPO_ROOT, 'resources')

# The licence names as SEART writes them (GitHub's names).
PERMISSIVE = {
    'Apache License 2.0', 'MIT License', 'MIT No Attribution',
    'BSD Zero Clause License', 'BSD 2-Clause Simplified License', 'BSD 3-Clause New or Revised License',
    'BSD 3-Clause Clear License', 'BSD 4-Clause Original or Old License',
    'ISC License', 'The Unlicense', 'Creative Commons Zero v1.0 Universal', 'Universal Permissive License v1.0',
    'zlib License', 'Boost Software License 1.0', 'University of Illinois/NCSA Open Source License',
    'PostgreSQL License', 'Academic Free License v3.0', 'Educational Community License v2.0',
    'Do What The F*ck You Want To Public License', 'Artistic License 2.0',
    'Mulan Permissive Software License, Version 2',
}
NOT_PERMISSIVE = {
    '',  # no licence: all rights reserved
    'Other',  # a licence GitHub does not recognize: unknown terms
    'GNU General Public License v2.0', 'GNU General Public License v3.0',
    'GNU Lesser General Public License v2.1', 'GNU Lesser General Public License v3.0',
    'GNU Affero General Public License v3.0',
    'Eclipse Public License 1.0', 'Eclipse Public License 2.0', 'Mozilla Public License 2.0',
    'European Union Public License 1.1', 'European Union Public License 1.2', 'Open Software License 3.0',
    'CeCILL Free Software License Agreement v2.1',
    'Creative Commons Attribution 4.0 International', 'Creative Commons Attribution Share Alike 4.0 International',
}

COLUMNS = ['id', 'name', 'lastCommitSHA', 'lastCommit', 'codeLines', 'license', 'createdAt', 'isArchived', 'commits',
           'contributors', 'stargazers', 'totalIssues', 'source']


def read_export(path, label):
    """The rows of one SEART export, checked: a known licence, a fork flag, a commit, each name once."""
    csv.field_size_limit(1 << 30)  # the `metrics` and `languages` columns can be long
    rows, names = [], set()
    with open(path, newline='', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            where = f'{label} export, id {r["id"]} ({r["name"]})'
            if r['license'] not in PERMISSIVE | NOT_PERMISSIVE:
                raise ValueError(f'{where}: licence {r["license"]!r} is not classified (add it to PERMISSIVE or '
                                 f'NOT_PERMISSIVE)')
            if r['isFork'] not in ('true', 'false'):
                raise ValueError(f'{where}: unexpected isFork {r["isFork"]!r}')
            if not r['lastCommitSHA'].strip():
                raise ValueError(f'{where}: no lastCommitSHA')
            name = r['name'].lower()
            if name in names:
                raise ValueError(f'{label} export: the name {name} appears twice')
            names.add(name)
            rows.append({c: r[c].strip() for c in COLUMNS if c != 'source'} | {'isFork': r['isFork']})
    return rows


def merge(new_path, old_path, out_path):
    new = read_export(new_path, 'new')
    old = read_export(old_path, 'old')
    counts = collections.Counter()
    new_ids = {r['id'] for r in new}
    new_names = {r['name'].lower() for r in new}
    old_ids = {r['id'] for r in old}
    old_names = {r['name'].lower() for r in old}
    kept = []
    for label, rows in (('new', new), ('old', old)):
        for r in rows:
            counts[f'{label}: rows'] += 1
            if label == 'old' and (r['id'] in new_ids or r['name'].lower() in new_names):
                counts['old: also in the new export'] += 1
                continue
            if r['isFork'] == 'true':
                counts[f'{label}: fork'] += 1
                continue
            if r['license'] not in PERMISSIVE:
                counts[f'{label}: licence not permissive'] += 1
                continue
            in_old = label == 'new' and (r['id'] in old_ids or r['name'].lower() in old_names)
            source = 'new+old' if in_old else label
            counts[f'kept: {source}'] += 1
            kept.append(dict({c: r[c] for c in COLUMNS if c != 'source'}, source=source))
    kept.sort(key=lambda r: int(r['id']))
    temporary = f'{out_path}.tmp{os.getpid()}'
    with open(temporary, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, COLUMNS, lineterminator='\n')
        writer.writeheader()
        writer.writerows(kept)
    os.replace(temporary, out_path)
    for key in ['new: rows', 'new: fork', 'new: licence not permissive', 'old: rows', 'old: also in the new export',
                'old: fork', 'old: licence not permissive', 'kept: new', 'kept: new+old', 'kept: old']:
        print(f'{key:32} {counts[key]:6}')
    print(f'{"kept":32} {len(kept):6}  -> {out_path}')
    return counts


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--new', default=os.path.join(RESOURCES, 'seart-github-repos-updated.csv'))
    p.add_argument('--old', default=os.path.join(RESOURCES, 'seart-github-repos-info.csv'))
    p.add_argument('--out', default=os.path.join(RESOURCES, 'issta2027-repositories.csv'))
    a = p.parse_args(argv)
    merge(a.new, a.old, a.out)


if __name__ == '__main__':
    sys.exit(main())
