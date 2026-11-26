#!/usr/bin/env python3
"""The list of repositories of the ISSTA 2027 datasets, from the SEART export (docs/data-collection-issta2027.md).

Usage: repo_list.py [--export CSV] [--out CSV]

Drops the forks (they contain the tests of the repository they come from) and the licences that are not permissive
(the datasets and the models will be published). A licence string that is in neither list stops the script, so a new
name is classified by hand and never dropped silently.

Writes the repositories sorted by SEART id, with the columns of COLUMNS only (`select` of generate.py reads `id`,
`name`, `lastCommitSHA` and `codeLines`; `javaCodeLines` is the Java entry of SEART's `metrics`, 0 when there is none),
and prints the count of each filter. Same input, same bytes.
"""
import argparse
import collections
import csv
import json
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

COLUMNS = ['id', 'name', 'lastCommitSHA', 'lastCommit', 'codeLines', 'javaCodeLines', 'license', 'createdAt',
           'isArchived', 'commits', 'contributors', 'stargazers', 'totalIssues']


def java_code_lines(metrics):
    java = [m for m in json.loads(metrics or '[]') if m.get('language') == 'Java']
    if len(java) > 1:
        raise ValueError(f'more than one Java entry in the metrics: {java}')
    return java[0]['codeLines'] if java else 0


def read_export(path):
    """The rows of the SEART export, checked: a known licence, a fork flag, a commit, each name once."""
    csv.field_size_limit(1 << 30)  # the `metrics` and `languages` columns can be long
    rows, names = [], set()
    with open(path, newline='', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            where = f'id {r["id"]} ({r["name"]})'
            if r['license'] not in PERMISSIVE | NOT_PERMISSIVE:
                raise ValueError(f'{where}: licence {r["license"]!r} is not classified (add it to PERMISSIVE or '
                                 f'NOT_PERMISSIVE)')
            if r['isFork'] not in ('true', 'false'):
                raise ValueError(f'{where}: unexpected isFork {r["isFork"]!r}')
            if not r['lastCommitSHA'].strip():
                raise ValueError(f'{where}: no lastCommitSHA')
            name = r['name'].lower()
            if name in names:
                raise ValueError(f'the name {name} appears twice')
            names.add(name)
            row = {c: r[c].strip() for c in COLUMNS if c != 'javaCodeLines'}
            row['javaCodeLines'] = str(java_code_lines(r['metrics']))
            rows.append(dict(row, isFork=r['isFork']))
    return rows


def build(export_path, out_path):
    counts = collections.Counter()
    kept = []
    for r in read_export(export_path):
        counts['rows'] += 1
        if r['isFork'] == 'true':
            counts['fork'] += 1
        elif r['license'] not in PERMISSIVE:
            counts['licence not permissive'] += 1
        else:
            kept.append({c: r[c] for c in COLUMNS})
    kept.sort(key=lambda r: int(r['id']))
    temporary = f'{out_path}.tmp{os.getpid()}'
    with open(temporary, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, COLUMNS, lineterminator='\n')
        writer.writeheader()
        writer.writerows(kept)
    os.replace(temporary, out_path)
    counts['kept'] = len(kept)
    for key in ['rows', 'fork', 'licence not permissive', 'kept']:
        print(f'{key:24} {counts[key]:6}')
    print(f'-> {out_path}')
    return counts


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--export', default=os.path.join(RESOURCES, 'seart-github-repos-updated.csv'))
    p.add_argument('--out', default=os.path.join(RESOURCES, 'issta2027-repositories.csv'))
    a = p.parse_args(argv)
    build(a.export, a.out)


if __name__ == '__main__':
    sys.exit(main())
