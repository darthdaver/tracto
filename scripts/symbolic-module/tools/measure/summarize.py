#!/usr/bin/env python3
"""Summary of the expected-type measurement (ExpectedTypes.java): python3 tools/measure/summarize.py tmp/measure/*.jsonl

For the arguments of the calls of the target oracles: how often the expected type is informative (not Object or a type
variable), and, when the first token of the argument is a project token, how many candidates reach the expected type
within k member accesses (depth 0..k), and whether the target is among them.
"""
import collections
import json
import statistics
import sys

PRIMITIVE_NUMBERS = {'int', 'long', 'short', 'byte', 'char', 'float', 'double'}


def category(expected):
    names = set(expected)
    if names & {'boolean', 'java.lang.Boolean'}:
        return 'boolean'
    if names & {'java.lang.String', 'java.lang.CharSequence'}:
        return 'String'
    if names & PRIMITIVE_NUMBERS or any(n.startswith('java.lang.') and n[10:] in ('Integer', 'Long', 'Double', 'Float', 'Short', 'Byte', 'Character') for n in names):
        return 'number'
    if any(n.startswith('java.') or n.startswith('javax.') for n in names):
        return 'JDK type'
    return 'other type'


def pct(a, b):
    return f'{100 * a / b:5.1f}%' if b else '    -'


def main(paths):
    records = [json.loads(line) for path in paths for line in open(path)]
    c = collections.Counter()
    kinds = collections.Counter()
    by_category = collections.defaultdict(list)
    for r in records:
        c['arguments'] += 1
        c['aligned'] += r['aligned']
        if r.get('expected') is None:
            c['call not resolved'] += 1
            continue
        c['expected resolved'] += 1
        c['assertion argument' if r['callIndex'] == 0 else 'nested call argument'] += 1
        if r.get('informative'):
            c['informative'] += 1
            kinds[r['argKind']] += 1
            if 'compatible' in r:
                c['informative, argument type known'] += 1
                c['informative, argument type compatible'] += r['compatible']
            if r['argKind'] in ('literal', 'null'):
                c['informative, literal'] += 1
                c['informative, literal compatible'] += bool(r.get('compatible'))
            if 'strictDepths' in r and r['strictDepths']:
                by_category[category(r['expected'])].append(r)
                by_category['all'].append(r)
    print(f"arguments {c['arguments']}, aligned with their step {pct(c['aligned'], c['arguments'])}, "
          f"expected type resolved {pct(c['expected resolved'], c['arguments'])} "
          f"(assertion arguments {c['assertion argument']}, arguments of nested calls {c['nested call argument']})")
    print(f"informative (not Object or a type variable): {c['informative']} = {pct(c['informative'], c['expected resolved'])} of the resolved")
    print(f"  argument type compatible with the expected type: {pct(c['informative, argument type compatible'], c['informative, argument type known'])} "
          f"of {c['informative, argument type known']}; literals compatible {pct(c['informative, literal compatible'], c['informative, literal'])} of {c['informative, literal']}")
    print('  kinds of the informative arguments:', dict(kinds.most_common()))
    print()
    print('Informative arguments starting with a project token: candidates reaching the expected type within k accesses')
    print('(strict: without the members of Object and the type variables; unknown: the type of the candidate is not known)')
    for name in ['all', 'boolean', 'String', 'number', 'JDK type', 'other type']:
        rs = by_category.get(name, [])
        if not rs:
            continue
        sizes = [len(r['strictDepths']) for r in rs]
        print(f'\n[{name}] {len(rs)} arguments, candidates per step: median {statistics.median(sizes)}, mean {statistics.mean(sizes):.0f}')
        unknown = statistics.mean(sum(v == -2 for v in r['strictDepths'].values()) / len(r['strictDepths']) for r in rs)
        target_unknown = sum(r['targetStrictDepth'] == -2 for r in rs)
        print(f'  candidates of unknown type {100 * unknown:4.1f}% on average; target of unknown type {pct(target_unknown, len(rs))}')
        for key, label in (('strictDepths', 'strict'), ('depths', 'all members')):
            target_key = 'targetStrictDepth' if key == 'strictDepths' else 'targetDepth'
            for k in range(4):
                flagged = statistics.mean(sum(0 <= v <= k for v in r[key].values()) / len(r[key]) for r in rs)
                target = sum(0 <= r[target_key] <= k for r in rs)
                missed = sum(r[target_key] == -1 or r[target_key] > k for r in rs)
                print(f'  {label:12} k={k}: flagged {100 * flagged:5.1f}% of the candidates on average; '
                      f'target flagged {pct(target, len(rs))}, target not flagged although known {pct(missed, len(rs))}')
        depths = collections.Counter(r['targetStrictDepth'] for r in rs)
        print('  strict depth of the target:', dict(sorted(depths.items())))


if __name__ == '__main__':
    main(sys.argv[1:])
