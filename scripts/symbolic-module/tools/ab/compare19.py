"""Compares the A/B runs of Step 19 (lambda blocks of assertThrows, assertThrowsExactly and assertDoesNotThrow).

Usage: python3 compare19.py <runA> <runB> <runC> <id>...   (A = Step 18, B = move-to-prefix, C = skip)
"""
import collections
import glob
import json
import re
import sys

from compare17 import errors, load, load_classes


def norm(text):
    return re.sub(r'\s+', ' ', text).strip()


def stats(run, project):
    for path in glob.glob(f'{run}/{project}/out/statistics-oracles-dataset-training/*/statistics-*.json'):
        return json.load(open(path))
    return {}


def split_errors(run, project):
    return len(glob.glob(f'{run}/{project}/out/**/error/split/**/*.java', recursive=True))


def is_block_assertion(target):
    return bool(re.match(r'^(\w+\s+\w+\s*=\s*)?(Assertions\.)?(assertThrows|assertThrowsExactly|assertDoesNotThrow)\(', target)) and '-> {' in target


def main():
    run_a, run_b, run_c, projects = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
    totals = collections.Counter()
    for project in projects:
        a, b, c = load(run_a, project), load(run_b, project), load(run_c, project)
        ta = collections.Counter(norm(dp['target']) for dp in a.values())
        tb = collections.Counter(norm(dp['target']) for dp in b.values())
        tc = collections.Counter(norm(dp['target']) for dp in c.values())
        only_a, only_b = ta - tb, tb - ta
        block_only_a = sum(n for t, n in only_a.items() if is_block_assertion(t))
        block_a = sum(n for t, n in ta.items() if is_block_assertion(t))
        block_b = sum(n for t, n in tb.items() if is_block_assertion(t))
        common = a.keys() & b.keys()
        same_target = [k for k in common if norm(a[k]['target']) == norm(b[k]['target'])]
        prefix_changed = sum(norm(a[k]['testPrefix']['body']) != norm(b[k]['testPrefix']['body']) for k in same_target)
        sa, sb, sc = stats(run_a, project), stats(run_b, project), stats(run_c, project)
        print(f'== {project}: datapoints A {len(a)}  B {len(b)}  C {len(c)}')
        print(f'   targets with a lambda block: A {block_a}  B {block_b}  C {sum(n for t, n in tc.items() if is_block_assertion(t))}')
        print(f'   targets only in A {sum(only_a.values())} (lambda blocks {block_only_a}), only in B {sum(only_b.values())}')
        print(f'   same datapoint and target, prefix changed: {prefix_changed} of {len(same_target)}')
        print(f'   statistics B {sb.get("assertionLambdaBlocks")}  C {sc.get("assertionLambdaBlocks")}')
        print(f'   discarded test cases A {sa.get("discardedTestCases")}  B {sb.get("discardedTestCases")}  C {sc.get("discardedTestCases")}')
        print(f'   errors A {errors(run_a, project).get("total", 0)}  B {errors(run_b, project).get("total", 0)}  C {errors(run_c, project).get("total", 0)}')
        print(f'   split errors A {split_errors(run_a, project)}  B {split_errors(run_b, project)}  C {split_errors(run_c, project)}')
        print(f'   test classes A {len(load_classes(run_a, project))}  B {len(load_classes(run_b, project))}  C {len(load_classes(run_c, project))}')
        for t, n in list(only_a.items())[:2]:
            print(f'   only A: {t[:160]}')
        for t, n in list(only_b.items())[:3]:
            print(f'   only B: {t[:160]}')
        totals.update({'A': len(a), 'B': len(b), 'C': len(c), 'blockA': block_a})
    print(f'== total datapoints A {totals["A"]}  B {totals["B"]}  C {totals["C"]}; lambda block targets in A {totals["blockA"]}')


if __name__ == '__main__':
    main()
