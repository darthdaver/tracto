"""Compares two A/B runs datapoint by datapoint: target, test prefix and invoked methods.

Usage: python3 compare17.py <runA> <runB> <id>...
"""
import collections
import glob
import json
import sys


def load(run, project):
    datapoints = {}
    for path in glob.glob(f'{run}/{project}/out/raw-oracles-dataset-training/*/*.json'):
        for test_class in json.load(open(path)):
            for dp in test_class['datapoints']:
                key = (test_class['testClass']['identifier'], dp['testPrefix']['identifier'])
                datapoints[key] = dp
    return datapoints


def load_classes(run, project):
    classes = {}
    for path in glob.glob(f'{run}/{project}/out/raw-oracles-dataset-training/*/*.json'):
        for test_class in json.load(open(path)):
            classes[test_class['testClass']['identifier']] = {k: v for k, v in test_class.items() if k != 'datapoints'}
    return classes


def errors(run, project):
    counts = collections.Counter()
    for path in glob.glob(f'{run}/{project}/out/statistics-oracles-dataset-training/*/errors-statistics-*.json'):
        for classes in json.load(open(path)).values():
            for tests in classes.values():
                for messages in tests.values():
                    for m in messages:
                        counts['total'] += 1
                        if 'Unsolved symbol' in m:
                            counts['unsolved symbol'] += 1
                        elif 'cannot be resolved' in m:
                            counts['cannot be resolved'] += 1
                        else:
                            counts['other'] += 1
    return counts


def statistics(run, project):
    for path in glob.glob(f'{run}/{project}/out/statistics-oracles-dataset-training/*/statistics-*.json'):
        stats = json.load(open(path))
        return {k: stats.get(k) for k in ('javaVersion', 'javaVersionSource', 'jdkVersions')}
    return {}


def signatures(dp):
    return [m['signature'] for m in dp['testPrefix']['invokedMethods'] or []]


def main():
    run_a, run_b, projects = sys.argv[1], sys.argv[2], sys.argv[3:]
    for project in projects:
        a, b = load(run_a, project), load(run_b, project)
        common = a.keys() & b.keys()
        target_diff = sum(a[k]['target'] != b[k]['target'] for k in common)
        body_diff = sum(a[k]['testPrefix']['body'] != b[k]['testPrefix']['body'] for k in common)
        added = removed = changed = 0
        empty_a = sum(not signatures(a[k]) for k in a)
        empty_b = sum(not signatures(b[k]) for k in b)
        total_a = sum(len(signatures(a[k])) for k in common)
        total_b = sum(len(signatures(b[k])) for k in common)
        removed_examples = []
        for k in common:
            sa, sb = collections.Counter(signatures(a[k])), collections.Counter(signatures(b[k]))
            if sa != sb:
                changed += 1
            added += sum((sb - sa).values())
            removed += sum((sa - sb).values())
            if sa - sb and len(removed_examples) < 3:
                removed_examples.append((k, list((sa - sb).elements())))
        other_a = {k: v for k, v in a[next(iter(common))].items() if k not in ('testPrefix', 'target')} if common else {}
        print(f'== {project}: datapoints {len(a)} -> {len(b)} (only A {len(a.keys() - b.keys())}, only B {len(b.keys() - a.keys())})')
        print(f'   targets changed {target_diff}, prefix bodies changed {body_diff}')
        print(f'   invoked methods {total_a} -> {total_b} (+{added} -{removed}, datapoints changed {changed}); '
              f'datapoints without invoked methods {empty_a} -> {empty_b}')
        print(f'   java B {statistics(run_b, project)}')
        print(f'   errors A {dict(errors(run_a, project))}')
        print(f'   errors B {dict(errors(run_b, project))}')
        ca, cb = load_classes(run_a, project), load_classes(run_b, project)
        class_diff = [k for k in ca.keys() & cb.keys() if json.dumps(ca[k], sort_keys=True) != json.dumps(cb[k], sort_keys=True)]
        print(f'   test classes {len(ca)} -> {len(cb)}, class-level fields changed in {len(class_diff)}: {class_diff[:5]}')
        for k, sigs in removed_examples:
            print(f'   removed in {k}: {sigs}')
        if other_a:
            print(f'   other datapoint fields: {list(other_a)}')


if __name__ == '__main__':
    main()
