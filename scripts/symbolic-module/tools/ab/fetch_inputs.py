#!/usr/bin/env python3
"""Clones the reference projects of the A/B runs at their pinned commits and checks them.

Usage: python3 fetch_inputs.py [--only id,...]

The projects are listed in input-repos.csv (id, repository, url, commit), next to this script. Each one is cloned into
TRACTO_INPUT/<repository> (default: <repository root>/input/github-repos/<repository>) with its full history, and checked
out at its commit. A project already present is never modified: it is only checked. A project is OK when its HEAD is the
pinned commit and its working tree has no change, untracked or ignored file (the pipeline reads every file of the
repository, so a leftover file, such as an old build output or a generated test class, would change its results).
Exits with 1 if a project is not OK.
"""
import argparse
import csv
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..', '..'))
INPUT = os.environ.get('TRACTO_INPUT', os.path.join(REPO_ROOT, 'input', 'github-repos'))


def git(*args, cwd=None):
    return subprocess.run(['git', *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def check(path, commit):
    head = git('rev-parse', 'HEAD', cwd=path)
    if head != commit:
        return f'HEAD is {head}, not {commit}'
    leftovers = git('status', '--porcelain', '--ignored', cwd=path)
    if leftovers:
        return f'the working tree is not clean: {leftovers.splitlines()[:5]}'
    if os.path.exists(os.path.join(path, '.gitmodules')):
        return 'the repository has submodules (not expected)'
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--only', help='the ids of the projects to fetch (default: all)')
    args = parser.parse_args()
    only = set(args.only.split(',')) if args.only else None
    failures = []
    with open(os.path.join(HERE, 'input-repos.csv')) as f:
        for row in csv.DictReader(f):
            if only is not None and row['id'] not in only:
                continue
            path = os.path.join(INPUT, row['repository'])
            if not os.path.exists(path):
                print(f'{row["id"]}: cloning {row["url"]}', flush=True)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                try:
                    git('clone', '--quiet', row['url'], path)
                    git('checkout', '--quiet', '--detach', row['commit'], cwd=path)
                except subprocess.CalledProcessError as e:
                    failures.append(row['id'])
                    print(f'{row["id"]}: FAILED: {e.stderr.strip()}')
                    continue
            problem = check(path, row['commit'])
            if problem:
                failures.append(row['id'])
                print(f'{row["id"]}: NOT OK ({path}): {problem}')
            else:
                print(f'{row["id"]}: OK at {row["commit"][:12]} ({path})')
    if failures:
        sys.exit(f'projects not OK: {failures}')


if __name__ == '__main__':
    main()
