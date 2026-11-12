#!/usr/bin/env python3
"""Regression corpus of the tokens stage: a fast, exact check of a change on real test classes (16 GB of RAM).

A fixed set of real test classes of the nine reference projects (tools/ab/corpus/corpus.json), chosen to be fast:
- guard: the classes of the oracles recovered by the earlier steps (a change must not lose them again);
- target: classes with oracles still lost, a few per cause (the work list of the next fixes);
- sample: a seeded random sample of the other classes of each project.

The expected results (tools/ab/corpus/expected.json) hold, for each oracle of these classes, its status (complete,
loses a step, dropped), the tokens missing from its candidates, and a digest of its token steps (candidates in their
order, contextual information). Since Steps 21-24 the tokens stage is deterministic, so the digests are exact: any
difference is a real change.

Commands (from scripts/symbolic-module):
  corpus.py prepare --jar J [-j N] [--jvm-options=...] [--no-deps]
      runs the oracles stage of J on the corpus projects (tmp/ab/corpus-src); again only when the oracles stage changes.
      Since Step 38 the dependencies of the projects are resolved into their copies first (ab.py run --deps), unless
      --no-deps (the corpus before Step 38)
  corpus.py check --jar J [-j N] [--jvm-options=...]
      runs the tokens stage of J on the corpus classes and compares with the expected results; exit status 1 on any
      difference. The results are kept in tmp/ab/corpus/last-check.json
  corpus.py accept --reason "..."
      makes the last check the expected results (after reviewing its differences), with the reason in the history
  corpus.py select <run A>:<run B> [...] --from <run>
      (once, on the server) chooses the classes from complete A/B runs: the oracles fixed from A to B of each pair are
      the guard set; the durations and the lost oracles of <run> give the cost and the targets

The tokens stage is run on the corpus classes only, in a fixed order: a class can depend on the classes processed
before it in the same JVM (a known defect), so the corpus results are compared only with corpus results.
"""
import argparse
import collections
import datetime
import glob
import hashlib
import json
import os
import random
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ab  # noqa: E402
import dev_tokens as dt  # noqa: E402
import missing_tokens as mt  # noqa: E402

CORPUS_DIR = os.path.join(HERE, 'corpus')
CORPUS = os.path.join(CORPUS_DIR, 'corpus.json')
EXPECTED = os.path.join(CORPUS_DIR, 'expected.json')
WORK = os.path.join(ab.MODULE, 'tmp', 'ab', 'corpus')
SOURCE_RUN = 'corpus-src'
CONFIG = os.path.join(ab.MODULE, 'src', 'main', 'resources', 'dataset', 'oracles-dataset_config.json')
ANONYMOUS = re.compile(r'Anonymous-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
RANK = {'complete': 0, 'loses a step': 1, 'dropped': 2}
MAX_CLASS_SECONDS = 120   # a corpus class takes at most this long on the server (jsoup ElementTest: 80 min)
SAMPLE_CLASSES = 2        # random classes per project, besides the guard and target ones
TARGETS_PER_CAUSE = 2     # classes per project and cause of lost oracles
SEED = 2026


def oracle_key(test_class, test, target):
    return f'{test_class}|{test}|{target}'


# ---------------------------------------------------------------------------------------------------------- select

def class_durations(log_path):
    """Seconds spent by the tokens stage on each test class (from the timestamps of its log)."""
    durations, previous, current = collections.Counter(), None, None
    pattern = re.compile(r'(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) INFO  TokensUtils:\d+ - Processing test class (\S+) - target')
    for line in open(log_path, errors='replace'):
        m = pattern.match(line)
        if m:
            ts = datetime.datetime.strptime(m.group(1), '%Y-%m-%d %H:%M:%S')
            if previous:
                durations[current] += (ts - previous).total_seconds()
            previous, current = ts, m.group(2).split('.')[-1].replace('STARSplitTestPrefix', 'STARSplitTest')
            durations[current] += 0
    return durations


def run_statuses(run, project):
    """{oracle key: status} and {oracle key: causes of the missing tokens} of a complete run (loads its datasets)."""
    out, repo = os.path.join(run, project, 'out'), os.path.join(run, project, 'repo')
    oracles = {}
    for path in glob.glob(f'{out}/raw-oracles-dataset-training/{project}/*.json'):
        for tc in json.load(open(path)):
            for dp in tc['datapoints']:
                oracles[oracle_key(tc['testClass']['identifier'], dp['testPrefix']['identifier'], dp['target'])] = 'dropped'
    for path in glob.glob(f'{out}/raw-tokens-dataset-training/{project}/*.json'):
        for tc in json.load(open(path)):
            for dp in tc['datapoints']:
                k = oracle_key(tc['testClass']['identifier'], dp['testPrefix']['identifier'], dp['target'])
                if k in oracles:
                    oracles[k] = 'complete'
    packages = mt.project_packages(repo)
    sources = {os.path.basename(p)[:-5]: open(p, errors='replace').read()
               for p in glob.glob(f'{repo}/**/*STARSplitTestPrefix.java', recursive=True)}
    causes = {}
    for path in glob.glob(f'{out}/statistics-tokens-dataset-training/*.json'):
        for file, tests in json.load(open(path)).items():
            test_class = os.path.basename(file)[:-5]
            source = mt.TestClass(sources.get(test_class.replace('STARSplitTest', 'STARSplitTestPrefix'), ''), packages)
            for test, entries in tests.items():
                for target, messages in entries.items():
                    k = oracle_key(test_class, test, target)
                    if k not in oracles or not isinstance(messages, list):
                        continue
                    found = {mt.cause(m.group(1), m.group(2), source, test) for m in map(mt.UNEXPECTED.match, messages) if m}
                    if found and oracles[k] == 'complete':
                        oracles[k] = 'loses a step'
                    if found:
                        causes[k] = sorted(found)
    return oracles, causes


def select(args):
    rng = random.Random(SEED)
    corpus = {'created': datetime.date.today().isoformat(), 'from': args.source, 'pairs': args.pairs,
              'max_class_seconds': MAX_CLASS_SECONDS, 'projects': {}}
    total = 0
    for project, _ in ab.PROJECTS:
        durations = class_durations(os.path.join(args.source, project, 'log-tokens.txt'))
        cheap = {c for c, s in durations.items() if s <= MAX_CLASS_SECONDS}
        reasons = collections.defaultdict(set)
        skipped = collections.Counter()
        for pair in args.pairs:
            run_a, run_b = pair.split(':')
            status_a, _ = run_statuses(run_a, project)
            status_b, _ = run_statuses(run_b, project)
            for k in status_a.keys() & status_b.keys():
                if RANK[status_b[k]] < RANK[status_a[k]]:
                    test_class = k.split('|')[0]
                    if test_class in cheap:
                        reasons[test_class].add(f'guard:{os.path.basename(run_b)}')
                    else:
                        skipped[f'guard:{os.path.basename(run_b)}'] += 1
        status, causes = run_statuses(args.source, project)
        by_cause = collections.defaultdict(set)
        for k, cs in causes.items():
            for c in cs:
                by_cause[c].add(k.split('|')[0])
        for c, classes in sorted(by_cause.items()):
            for test_class in sorted(classes & cheap, key=lambda t: durations[t])[:TARGETS_PER_CAUSE]:
                reasons[test_class].add(f'target:{c}')
        others = sorted(cheap - set(reasons))
        for test_class in rng.sample(others, min(SAMPLE_CLASSES, len(others))):
            reasons[test_class].add('sample')
        seconds = sum(durations[c] for c in reasons)
        total += seconds
        corpus['projects'][project] = {
            'classes': {c: sorted(reasons[c]) for c in sorted(reasons)},
            'seconds_on_server': round(seconds), 'guard_oracles_in_slow_classes_skipped': dict(skipped)}
        print(f'{project}: {len(reasons)} classes, {seconds / 60:.1f} min on the server; skipped (slow classes): {dict(skipped)}')
    print(f'total {total / 60:.1f} min of tokens stage on the server (one JVM)')
    os.makedirs(CORPUS_DIR, exist_ok=True)
    json.dump(corpus, open(CORPUS, 'w'), indent=1, sort_keys=True)


# ---------------------------------------------------------------------------------------------- prepare and check

def load_corpus():
    return json.load(open(CORPUS))


def prepare(args):
    projects = list(load_corpus()['projects'])
    ok = ab.run(SOURCE_RUN, os.path.abspath(args.jar), args.config, args.j, False, projects, jvm_options=args.jvm_options.split(),
                deps=args.deps)
    sys.exit(0 if ok else 1)


def digest(datapoint, replacements):
    text = json.dumps(datapoint['tokenSteps'], sort_keys=True, ensure_ascii=False)
    for old, new in replacements:
        text = text.replace(old, new)
    return hashlib.sha256(ANONYMOUS.sub('Anonymous-<uuid>', text).encode()).hexdigest()[:16]


def check_project(jar, project, classes, jvm_options):
    exit_status, out, oracles = dt.run_project(jar, SOURCE_RUN, 'corpus', 'check', project, set(classes), jvm_options)
    status, unexpected = dt.statuses(out, project, oracles)
    source = os.path.join(ab.MODULE, 'tmp', 'ab', SOURCE_RUN, project)
    replacements = [(os.path.join(source, 'repo'), '<repo>'), (ab.MODULE, '<module>')]
    digests = {}
    for path in glob.glob(f'{out}/raw-tokens-dataset-training/{project}/*.json'):
        for tc in json.load(open(path)):
            for dp in tc['datapoints']:
                digests[oracle_key(tc['testClass']['identifier'], dp['testPrefix']['identifier'], dp['target'])] = digest(dp, replacements)
    results = {}
    for k in sorted(oracles):
        ok = oracle_key(*k)
        results[ok] = {'status': status[k], 'missing': sorted({t for t, _ in unexpected.get(k, [])}), 'digest': digests.get(ok)}
    return exit_status, results


def check(args):
    import concurrent.futures
    corpus = load_corpus()
    jar = os.path.abspath(args.jar)
    with concurrent.futures.ThreadPoolExecutor(args.j) as pool:
        futures = {p: pool.submit(check_project, jar, p, list(spec['classes']), args.jvm_options.split())
                   for p, spec in corpus['projects'].items()}
        checked = {p: f.result() for p, f in futures.items()}
    results = {'jar': jar, 'jar_sha256': ab.sha256(jar), 'date': datetime.datetime.now().isoformat(timespec='seconds'),
               'environment': environment(),
               'exit': {p: e for p, (e, _) in checked.items()}, 'oracles': {p: r for p, (_, r) in checked.items()}}
    os.makedirs(WORK, exist_ok=True)
    json.dump(results, open(os.path.join(WORK, 'last-check.json'), 'w'), indent=1, sort_keys=True)
    expected = json.load(open(EXPECTED)) if os.path.exists(EXPECTED) else None
    sys.exit(0 if report(results, expected) else 1)


def environment():
    """What the results depend on besides the code: the JDK, the JDK sources, the input repositories (ab.py)."""
    e = ab.fingerprint()
    return {k: e[k] for k in ('java', 'java_classpaths_json_sha256', 'jdk_sources', 'inputs')}


def report(results, expected):
    clean = all(e == 0 for e in results['exit'].values())
    if expected is not None and expected.get('environment') and expected['environment'] != results['environment']:
        clean = False
        print('WARNING: the environment differs from the one of the expected results (JDK, JDK sources or inputs);')
        print('         the differences below may come from it, not from the code:')
        for k in expected['environment']:
            if expected['environment'][k] != results['environment'].get(k):
                print(f'         {k}: expected {str(expected["environment"][k])[:200]}')
                print(f'         {" " * len(k)}  here     {str(results["environment"].get(k))[:200]}')
    for p, e in results['exit'].items():
        if e != 0:
            print(f'{p}: TOKENS STAGE EXIT STATUS {e} (see tmp/ab/dev/corpus/check/{p}/log-tokens.txt)')
    totals = collections.Counter()
    for p, oracles in sorted(results['oracles'].items()):
        counts = collections.Counter(o['status'] for o in oracles.values())
        totals.update(counts)
        line = f'{p}: {len(oracles)} oracles, ' + ', '.join(f'{s} {n}' for s, n in counts.most_common())
        if expected is None:
            print(line)
            continue
        before = expected['oracles'].get(p, {})
        better = [k for k in oracles if k in before and RANK[oracles[k]['status']] < RANK[before[k]['status']]]
        worse = [k for k in oracles if k in before and RANK[oracles[k]['status']] > RANK[before[k]['status']]]
        changed = [k for k in oracles if k in before and oracles[k]['status'] == before[k]['status']
                   and (oracles[k]['digest'] != before[k]['digest'] or oracles[k]['missing'] != before[k]['missing'])]
        new, gone = sorted(oracles.keys() - before.keys()), sorted(before.keys() - oracles.keys())
        print(f'{line}; vs expected: better {len(better)}, WORSE {len(worse)}, same status but other candidates '
              f'{len(changed)}, new {len(new)}, gone {len(gone)}')
        for label, keys in (('WORSE', worse), ('better', better), ('candidates changed', changed), ('new', new), ('gone', gone)):
            for k in keys[:15]:
                b, a = before.get(k, {}), oracles.get(k, {})
                print(f'   {label}: {k[:150]}  {b.get("status")} {b.get("missing", "")} -> {a.get("status")} {a.get("missing", "")}')
            if len(keys) > 15:
                print(f'   ... {len(keys) - 15} more {label}')
        clean &= not (better or worse or changed or new or gone)
    print(f'total: {dict(totals)}')
    if expected is not None:
        print('IDENTICAL to the expected results' if clean else 'DIFFERENT from the expected results: review, then fix or accept')
    return clean


def accept(args):
    results = json.load(open(os.path.join(WORK, 'last-check.json')))
    history = json.load(open(EXPECTED)).get('history', []) if os.path.exists(EXPECTED) else []
    history.append({'date': results['date'], 'jar_sha256': results['jar_sha256'], 'reason': args.reason})
    json.dump({'history': history, 'environment': results.get('environment') or environment(), 'oracles': results['oracles']},
              open(EXPECTED, 'w'), indent=1, sort_keys=True)
    print(f'expected results updated ({sum(len(o) for o in results["oracles"].values())} oracles): {args.reason}')


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='command', required=True)
    s = sub.add_parser('select'); s.add_argument('pairs', nargs='+'); s.add_argument('--from', dest='source', required=True)
    for name in ('prepare', 'check'):
        c = sub.add_parser(name); c.add_argument('--jar', required=True); c.add_argument('-j', type=int, default=2)
        c.add_argument('--jvm-options', default='-Xmx6g')
        if name == 'prepare':
            c.add_argument('--config', default=CONFIG)
            c.add_argument('--deps', action=argparse.BooleanOptionalAction, default=True,
                           help='resolve the dependencies of the projects first (Step 38; --no-deps: as before)')
    a = sub.add_parser('accept'); a.add_argument('--reason', required=True)
    args = p.parse_args()
    {'select': select, 'prepare': prepare, 'check': check, 'accept': accept}[args.command](args)


if __name__ == '__main__':
    main()
