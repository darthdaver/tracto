#!/usr/bin/env python3
"""Compares the tokens datasets of two A/B runs, oracle by oracle.

Usage: python3 compare_tokens.py <runA> <runB> <id>...   (run: tmp/ab/<label>, after the oracles and tokens stages)

For each project:
- whether the oracles datasets of the two runs are identical, except for the random names of the anonymous classes (a
  change of the tokens stage must not change them);
- the status of each oracle in A and in B: complete, loses a step (a target missing from the candidates: "Unexpected
  token"), dropped (no tokens datapoint); the transitions between them, with the regressions (an oracle complete in A
  and not in B) listed;
- the candidates (project tokens) of the token steps present in both runs: the steps whose candidates gained or lost
  names, and the names most often added and removed;
- the number of targets whose position was not found in their test prefix (message of TokensUtils).

The oracles are identified by test class file, test prefix and target; the token steps by oracle and partial oracle.
The tokens datasets are read one at a time (they can be several GB).
"""
import collections
import glob
import hashlib
import json
import os
import re
import sys

UNEXPECTED = 'Unexpected token'
POSITION_NOT_FOUND = 'Target position not found in the test prefix'


# The oracles stage names the anonymous classes with a random UUID (Anonymous-<uuid>), different at each run
ANONYMOUS_UUID = re.compile(rb'Anonymous-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')


def oracles_digest(run, project):
    """The digest of each oracles dataset file, with the random names of the anonymous classes replaced."""
    digests = {}
    for path in sorted(glob.glob(f'{run}/{project}/out/raw-oracles-dataset-training/{project}/*.json')):
        with open(path, 'rb') as f:
            digests[os.path.basename(path)] = hashlib.sha256(ANONYMOUS_UUID.sub(b'Anonymous-<uuid>', f.read())).hexdigest()
    return digests


def summary(run, project):
    """The status of each oracle, the candidates of each token step, and the count of positions not found."""
    status, missing, not_found = {}, {}, 0
    for path in glob.glob(f'{run}/{project}/out/statistics-tokens-dataset-training/*.json'):
        for file, tests in json.load(open(path)).items():
            for test, entries in tests.items():
                for target, messages in entries.items():
                    if target in ('targetFound', 'targetMissing'):
                        continue
                    key = (file, test, target)
                    unexpected = [m for m in messages if m.startswith(UNEXPECTED)]
                    not_found += any(m.startswith(POSITION_NOT_FOUND) for m in messages)
                    status[key] = 'loses a step' if unexpected else 'dropped'
                    if unexpected:
                        missing[key] = unexpected[0][len(UNEXPECTED):][:150]
    candidates = {}
    for path in glob.glob(f'{run}/{project}/out/raw-tokens-dataset-training/{project}/*.json'):
        for test_class in json.load(open(path)):
            file = test_class['testClass']['filePath']
            for dp in test_class['datapoints']:
                key = (file, dp['testPrefix']['identifier'], dp['target'])
                if status.get(key) == 'dropped':
                    status[key] = 'complete'
                for step in dp['tokenSteps']:
                    candidates[key + (step['partialSnippet'],)] = frozenset(step['nextPossibleProjectTokens'])
    return status, missing, candidates, not_found


def main():
    run_a, run_b, projects = sys.argv[1], sys.argv[2], sys.argv[3:]
    totals = collections.Counter()
    for project in projects:
        same_oracles = oracles_digest(run_a, project) == oracles_digest(run_b, project)
        status_a, missing_a, candidates_a, not_found_a = summary(run_a, project)
        status_b, missing_b, candidates_b, not_found_b = summary(run_b, project)
        transitions = collections.Counter((status_a.get(k, 'absent'), status_b.get(k, 'absent')) for k in status_a.keys() | status_b.keys())
        regressions = [k for k in status_a if status_a[k] == 'complete' and status_b.get(k) != 'complete']
        common = candidates_a.keys() & candidates_b.keys()
        added, removed = collections.Counter(), collections.Counter()
        steps_added = steps_removed = 0
        for step in common:
            plus, minus = candidates_b[step] - candidates_a[step], candidates_a[step] - candidates_b[step]
            steps_added += bool(plus)
            steps_removed += bool(minus)
            added.update(plus)
            removed.update(minus)
        lost_a = sum(s != 'complete' for s in status_a.values())
        lost_b = sum(s != 'complete' for s in status_b.values())
        print(f'== {project}: oracles datasets {"identical" if same_oracles else "DIFFERENT"}; oracles A {len(status_a)}  B {len(status_b)}; '
              f'lost A {lost_a}  B {lost_b} ({lost_b - lost_a:+d}); target positions not found A {not_found_a}  B {not_found_b}')
        for (a, b), n in sorted(transitions.items()):
            if a != b:
                print(f'   {a:>12s} -> {b:<12s} {n:6d}')
        print(f'   token steps in both runs {len(common)}: candidates added in {steps_added}, removed in {steps_removed}')
        print(f'   most added:   {added.most_common(8)}')
        print(f'   most removed: {removed.most_common(8)}')
        for k in regressions[:10]:
            print(f'   REGRESSION {k[1]}: {k[2][:100]!r} -> {status_b.get(k)}: {missing_b.get(k, "")}')
        totals.update({'oracles': len(status_a), 'lost A': lost_a, 'lost B': lost_b, 'regressions': len(regressions),
                       'not found B': not_found_b, 'different oracles': not same_oracles})
    print(f'== total: {dict(totals)}')


if __name__ == '__main__':
    main()
