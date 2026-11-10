#!/usr/bin/env python3
"""Development loop of a fix of the tokens stage: runs the tokens stage of a jar on selected test classes only.

Usage:
  dev_tokens.py <jar-A> <jar-B> <source-run> <label> <project>:<TestClass>[,<TestClass>...] [...] [-j N] [--jvm-options=...]

The oracles of the selected test classes are taken from a previous complete run (tmp/ab/<source-run>, made by ab.py
run --tokens): its oracles dataset is filtered to them and the tokens stages of the two jars run on it, side by side,
with the copy of the repository of that run (which holds the split test files; the tokens stage only reads it). The
outputs go to tmp/ab/dev/<label>/<A|B>/<project>.

Reports, for each oracle of the selected classes, its status with A and with B (complete, loses a step: a target token
missing from the candidates, dropped: no tokens datapoint): the oracles fixed, the regressions (worse with B), and the
tokens still missing with B with their cause (missing_tokens.py). A fast check during the development of a fix, not a
replacement of the A/B: only the selected classes are run.
"""
import argparse
import collections
import concurrent.futures
import glob
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ab  # noqa: E402
import missing_tokens as mt  # noqa: E402


def selected_classes(spec):
    project, classes = spec.split(':', 1)
    return project, {c + 'STARSplitTest' for c in classes.split(',')}


def unexpected_by_oracle(out):
    """{(test class, test prefix, target): [(token, partial snippet)]} from the statistics of the tokens stage."""
    result = {}
    for path in glob.glob(f'{out}/statistics-tokens-dataset-training/*.json'):
        for file, tests in json.load(open(path)).items():
            test_class = os.path.basename(file)[:-5]
            for test, entries in tests.items():
                for target, messages in entries.items():
                    if target in ('targetFound', 'targetMissing'):
                        continue
                    result[(test_class, test, target)] = [m.groups() for m in map(mt.UNEXPECTED.match, messages) if m]
    return result


def tokens_datapoints(out, project):
    keys = set()
    for path in glob.glob(f'{out}/raw-tokens-dataset-training/{project}/*.json'):
        for test_class in json.load(open(path)):
            for dp in test_class['datapoints']:
                keys.add((test_class['testClass']['identifier'], dp['testPrefix']['identifier'], dp['target']))
    return keys


def run_project(jar, source_run, label, side, project, classes, jvm_options):
    source = os.path.join(ab.MODULE, 'tmp', 'ab', source_run, project)
    out = os.path.join(ab.MODULE, 'tmp', 'ab', 'dev', label, side, project, 'out')
    oracles_dir = os.path.join(out, 'raw-oracles-dataset-training', project)
    os.makedirs(oracles_dir, exist_ok=True)
    selected, found = [], set()
    for path in sorted(glob.glob(f'{source}/out/raw-oracles-dataset-training/{project}/*.json')):
        for test_class in json.load(open(path)):
            if test_class['testClass']['identifier'] in classes:
                selected.append(test_class)
                found.add(test_class['testClass']['identifier'])
    if classes - found:
        raise SystemExit(f'{project}: test classes not found in the oracles of {source_run}: {sorted(classes - found)}')
    json.dump(selected, open(os.path.join(oracles_dir, f'oracles-datapoints-{project}-0.json'), 'w'))
    with open(os.path.join(os.path.dirname(out), 'log-tokens.txt'), 'w') as log:
        exit_status = subprocess.run([ab.JAVA, *jvm_options, '-jar', jar, 'tokens', project, os.path.join(source, 'repo'), out,
                                      ab.MODULE, ab.JAVA_VERSION_ARG], cwd=ab.MODULE, stdout=log, stderr=subprocess.STDOUT).returncode
    oracles = {(tc['testClass']['identifier'], dp['testPrefix']['identifier'], dp['target'])
               for tc in selected for dp in tc['datapoints']}
    return exit_status, out, oracles


def statuses(out, project, oracles):
    unexpected = unexpected_by_oracle(out)
    datapoints = tokens_datapoints(out, project)
    return {k: ('dropped' if k not in datapoints else 'loses a step' if unexpected.get(k) else 'complete') for k in oracles}, unexpected


RANK = {'complete': 0, 'loses a step': 1, 'dropped': 2}


def report(project, source, run_a, run_b):
    (exit_a, out_a, oracles), (exit_b, out_b, _) = run_a, run_b
    if exit_a != 0 or exit_b != 0:
        # A JVM that failed or was killed (by the RAM watchdog, for instance) leaves its outputs empty or cut off
        print(f'== {project}: exit status A {exit_a}, B {exit_b}: no report (run the project again)')
        return False
    status_a, _ = statuses(out_a, project, oracles)
    status_b, unexpected_b = statuses(out_b, project, oracles)
    print(f'== {project}: exit status A {exit_a}, B {exit_b}; {len(oracles)} oracles')
    for name, status in (('A', status_a), ('B', status_b)):
        print(f'   {name}: ' + ', '.join(f'{s} {n}' for s, n in collections.Counter(status.values()).most_common()))
    fixed = sorted(k for k in oracles if RANK[status_b[k]] < RANK[status_a[k]])
    regressions = sorted(k for k in oracles if RANK[status_b[k]] > RANK[status_a[k]])
    print(f'   better with B {len(fixed)}, worse with B {len(regressions)}')
    for k in regressions:
        print(f'   REGRESSION {k[0]} {k[1]}: {status_a[k]} -> {status_b[k]} {unexpected_b.get(k, "")}')
    packages = mt.project_packages(os.path.join(source, 'repo'))
    sources = {os.path.basename(p)[:-5]: open(p, errors='replace').read()
               for p in glob.glob(f'{source}/repo/**/*STARSplitTestPrefix.java', recursive=True)}
    causes = collections.Counter()
    for k in sorted(oracles):
        if status_b[k] != 'loses a step':
            continue
        test_class = mt.TestClass(sources.get(k[0].replace('STARSplitTest', 'STARSplitTestPrefix'), ''), packages)
        for token, partial in unexpected_b[k]:
            cause = mt.cause(token, partial, test_class, k[1])
            causes[cause] += 1
            print(f'   still missing [{cause}] {k[0][:-len("STARSplitTest")]}.{k[1]}: …{partial[-50:]} ▸ {token}')
    if causes:
        print('   causes of the tokens still missing with B:', dict(causes.most_common()))
    return not regressions and exit_a == 0 and exit_b == 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('jar_a'); p.add_argument('jar_b'); p.add_argument('source_run'); p.add_argument('label'); p.add_argument('selection', nargs='+')
    p.add_argument('-j', type=int, default=1); p.add_argument('--jvm-options', default='')
    args = p.parse_args()
    selections = dict(selected_classes(s) for s in args.selection)
    jars = {'A': os.path.abspath(args.jar_a), 'B': os.path.abspath(args.jar_b)}
    jobs = [(side, project) for project in selections for side in jars]
    with concurrent.futures.ThreadPoolExecutor(args.j) as pool:
        futures = {job: pool.submit(run_project, jars[job[0]], args.source_run, args.label, job[0], job[1],
                                    selections[job[1]], args.jvm_options.split()) for job in jobs}
        runs = {job: future.result() for job, future in futures.items()}
    ok = all([report(project, os.path.join(ab.MODULE, 'tmp', 'ab', args.source_run, project), runs[('A', project)],
                     runs[('B', project)]) for project in selections])
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
