"""Measures the oracles lost by the tokens dataset and classifies their causes.

Usage: python3 missing_tokens.py <run dir>:<project>[,<project>...] ...   (run dir: tmp/ab/<label>, with <project>/repo
and <project>/out after the oracles and tokens stages)

An oracle (a datapoint of the oracles dataset) is lost when it produces no datapoint of the tokens dataset (the tokens
stage stopped on it), or when one of its token steps has a target missing from the candidates ("Unexpected token: X
not found in the collected list of candidate tokens: <partial oracle>"). Each missing target is attributed to a cause
by looking at the partial oracle and at the source of the split test prefix (regular expressions, not the symbol
solver: the classification is a heuristic, checked by hand on samples):

- dependency: the token belongs to a class of a dependency (the class name, a static member, a member of a variable
  whose declared type is imported from a package of neither the project nor the JDK);
- nested-block variable: a variable declared within a nested block (a loop body, a for-each variable), or a member of
  such a variable;
- test method parameter: a parameter of the test method (parameterized tests), or a member of it;
- test class member: a field or a method of the test class, or a member of a field;
- static import: a method or a field imported statically from a class of the project or of the JDK;
- class name: the name of a class of the project or of the JDK (for example the exception of an assertThrows);
- member of a chain: a member of an expression whose type is inferred (a call chain, a cast, an array access);
- member of a known type: a member of a variable declared at the top level of the test method, with a project or JDK
  type (a failure of the resolution);
- grammar: a Java token (not an identifier) missing from the candidates of the ANTLR grammar;
- other: anything else.
"""
import collections
import glob
import json
import os
import re
import sys

UNEXPECTED = re.compile(r'^Unexpected token: (.*?) not found in the collected list of candidate tokens: (.*)$', re.S)
JDK_PREFIXES = ('java.', 'javax.', 'jdk.', 'sun.', 'com.sun.')
JAVA_LANG = {'String', 'Object', 'Integer', 'Long', 'Double', 'Float', 'Boolean', 'Character', 'Byte', 'Short', 'Math',
             'System', 'Thread', 'Class', 'Exception', 'RuntimeException', 'Iterable', 'StringBuilder', 'Number', 'Enum',
             'Throwable', 'Error', 'Void', 'IllegalArgumentException', 'IllegalStateException', 'NullPointerException',
             'UnsupportedOperationException', 'IndexOutOfBoundsException', 'ArithmeticException', 'ClassCastException',
             'NumberFormatException', 'ArrayIndexOutOfBoundsException', 'CloneNotSupportedException', 'InterruptedException',
             'AssertionError', 'StackOverflowError', 'OutOfMemoryError', 'Runnable', 'Comparable', 'CharSequence'}


def project_packages(repo):
    packages = set()
    for path in glob.glob(f'{repo}/**/*.java', recursive=True):
        try:
            with open(path, errors='replace') as f:
                for line in f:
                    m = re.match(r'\s*package\s+([\w.]+)\s*;', line)
                    if m:
                        packages.add(m.group(1))
                        break
        except OSError:
            pass
    return packages


def origin_of_qualified(name, packages):
    if name.startswith(JDK_PREFIXES):
        return 'jdk'
    package = name.rsplit('.', 1)[0] if '.' in name else ''
    while package:
        if package in packages:
            return 'project'
        package = package.rsplit('.', 1)[0] if '.' in package else ''
    return 'dependency'


class TestClass:
    """The source of a split test prefix class: its imports, its members and the bodies of its methods."""

    def __init__(self, source, packages):
        self.source = source
        self.packages = packages
        self.package = (re.search(r'^\s*package\s+([\w.]+)\s*;', source, re.M) or [None, ''])[1]
        self.imports = re.findall(r'^\s*import\s+(static\s+)?([\w.]+(?:\.\*)?)\s*;', source, re.M)

    def origin_of_class(self, name):
        simple = name.split('<')[0].split('[')[0].strip()
        if '.' in simple:
            first = simple.split('.')[0]
            if first[:1].isupper():
                return self.origin_of_class(first)  # a nested class: the origin of its outer class
            return origin_of_qualified(simple, self.packages)
        for static, imported in self.imports:
            if not static and imported.endswith('.' + simple):
                return origin_of_qualified(imported, self.packages)
        if simple in JAVA_LANG:
            return 'jdk'
        if re.search(rf'\b(class|interface|enum|record)\s+{re.escape(simple)}\b', self.source):
            return 'project'
        wildcards = [imported[:-2] for static, imported in self.imports if not static and imported.endswith('.*')]
        if wildcards:
            origins = {origin_of_qualified(w + '.' + simple, self.packages) for w in wildcards}
            if len(origins) == 1:
                return origins.pop()
        return 'project'  # a class of the same package

    def method(self, name):
        """The parameters and the body of a method."""
        m = re.search(rf'\b{re.escape(name)}\s*\(([^)]*)\)[^{{;]*\{{', self.source)
        if not m:
            return '', ''
        depth, start = 0, m.end() - 1
        for j in range(start, len(self.source)):
            if self.source[j] == '{':
                depth += 1
            elif self.source[j] == '}':
                depth -= 1
                if depth == 0:
                    return m.group(1), self.source[start:j + 1]
        return m.group(1), self.source[start:]

    def field_type(self, name):
        m = re.search(rf'^\s*(?:(?:public|protected|private|static|final|transient|volatile)\s+)*([\w.]+(?:<[^;=]*?>)?(?:\[\])*)\s+{re.escape(name)}\s*[=;]',
                      self.source, re.M)
        return m.group(1) if m else None

    def is_method(self, name):
        return re.search(rf'^\s*(?:(?:public|protected|private|static|final|synchronized)\s+)*(?:<[^>]*>\s*)?[\w.<>\[\]?, ]+\s+{re.escape(name)}\s*\(',
                         self.source, re.M) is not None

    def static_import_origin(self, member):
        for static, imported in self.imports:
            if static and imported.endswith('.' + member):
                return origin_of_qualified(imported.rsplit('.', 1)[0], self.packages)
        owners = [imported[:-2] for static, imported in self.imports if static and imported.endswith('.*')
                  and (not imported.startswith('org.junit') or member.startswith(('assert', 'fail')))]
        if owners:
            origins = {origin_of_qualified(owner, self.packages) for owner in owners}
            return 'dependency' if origins == {'dependency'} else 'project' if 'dependency' not in origins else 'mixed'
        return None


def local_declaration(body, variable):
    """The declared type of a local variable and whether it is declared within a nested block (or a for-each)."""
    pattern = re.compile(rf'([A-Z][\w.]*(?:<[^;=()]*?>)?(?:\[\])*|int|long|double|float|boolean|char|byte|short|var)(?:\[\])*\s+{re.escape(variable)}\s*([=;:,)])')
    m = pattern.search(body)
    if not m:
        return None, None
    depth = body[:m.start()].count('{') - body[:m.start()].count('}')
    nested = depth > 1 or m.group(2) == ':' or re.search(r'\bfor\s*\([^;)]*$', body[:m.start()]) is not None
    return m.group(1), nested


def parameter_type(parameters, name):
    m = re.search(rf'(?:^|,)\s*(?:final\s+)?(?:@\w+\s+)*([\w.<>\[\]?, ]+?)\s+{re.escape(name)}\s*(?:,|$)', parameters)
    return m.group(1).strip() if m else None


def receiver(partial):
    """The receiver before the final dot of a partial oracle, and its root identifier."""
    text = partial[:-1]
    depth, i = 0, len(text) - 1
    while i >= 0:
        ch = text[i]
        if ch in ')]':
            depth += 1
        elif ch in '([':
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and not (ch.isalnum() or ch in '_.$'):
            break
        i -= 1
    expression = text[i + 1:]
    root = re.match(r'[\w$]+', expression)
    return expression, (root.group(0) if root else '')


def cause(token, partial, test_class, test_name):
    if not re.match(r'^[A-Za-z_$][\w$]*$', token):
        return f'grammar'
    parameters, body = test_class.method(test_name)
    if partial.endswith('.'):
        expression, root = receiver(partial)
        if token == 'length':
            return 'member of a chain'  # the length of an array
        if not root or root in ('this', 'super'):
            return 'member of a chain' if not root else 'test class member'
        chained = '(' in expression or expression.count('.') > 0
        if root[:1].isupper():
            origin = test_class.origin_of_class(root)
            return 'dependency' if origin == 'dependency' else ('member of a chain' if chained and expression.count('(') else 'class name')
        declared, nested = local_declaration(body, root)
        if declared is None:
            declared = parameter_type(parameters, root)
            if declared is not None:
                return 'dependency' if test_class.origin_of_class(declared) == 'dependency' else 'test method parameter'
            declared = test_class.field_type(root)
            if declared is not None:
                return 'dependency' if test_class.origin_of_class(declared) == 'dependency' else 'test class member'
            origin = test_class.static_import_origin(root)
            if origin is not None:
                return 'dependency' if origin == 'dependency' else 'static import'
            return 'other'
        if declared[0].isupper() and test_class.origin_of_class(declared) == 'dependency':
            return 'dependency'
        if nested:
            return 'nested-block variable'
        return 'member of a chain' if chained else 'member of a known type'
    declared, nested = local_declaration(body, token)
    if declared is not None:
        return 'nested-block variable' if nested else 'other'
    if parameter_type(parameters, token) is not None:
        return 'test method parameter'
    if test_class.field_type(token) is not None or test_class.is_method(token):
        return 'test class member'
    if token[:1].isupper():
        return 'dependency' if test_class.origin_of_class(token) == 'dependency' else 'class name'
    origin = test_class.static_import_origin(token)
    if origin is not None:
        return 'dependency' if origin == 'dependency' else 'static import'
    return 'other'


def analyse(run, project):
    repo, out = os.path.join(run, project, 'repo'), os.path.join(run, project, 'out')
    packages = project_packages(repo)
    oracles = set()
    for path in glob.glob(f'{out}/raw-oracles-dataset-training/{project}/*.json'):
        for test_class in json.load(open(path)):
            for dp in test_class['datapoints']:
                oracles.add((dp['testPrefix']['identifier'], dp['target']))
    tokens, steps = set(), 0
    for path in glob.glob(f'{out}/raw-tokens-dataset-training/{project}/*.json'):
        for test_class in json.load(open(path)):
            for dp in test_class['datapoints']:
                tokens.add((dp['testPrefix']['identifier'], dp['target']))
                steps += len(dp['tokenSteps'])
    sources = {}
    for path in glob.glob(f'{repo}/**/*STARSplitTestPrefix.java', recursive=True):
        sources[os.path.basename(path)[:-5]] = open(path, errors='replace').read()
    dropped = collections.Counter()
    oracle_causes = {}
    step_causes = collections.Counter()
    examples = collections.defaultdict(list)
    for path in glob.glob(f'{out}/statistics-tokens-dataset-training/*.json'):
        for file, tests in json.load(open(path)).items():
            test_class = TestClass(sources.get(os.path.basename(file)[:-5].replace('STARSplitTest', 'STARSplitTestPrefix'), ''), packages)
            for test, entries in tests.items():
                for target, messages in entries.items():
                    if target in ('targetFound', 'targetMissing'):
                        continue
                    if (test, target) not in tokens:
                        reasons = [m for m in messages if not UNEXPECTED.match(m)]
                        reason = reasons[-1] if reasons else 'no message'
                        if 'Unsupported assertion type' in reason:
                            name = re.match(r'[\w.]+', target).group(0).split('.')[-1]
                            reason = f'unsupported assertion {name}'
                        else:
                            reason = re.sub(r'\d+', 'N', reason.split(':')[0][:60])
                        dropped[reason] += 1
                        continue
                    causes = set()
                    for message in messages:
                        m = UNEXPECTED.match(message)
                        if m:
                            c = cause(m.group(1), m.group(2), test_class, test)
                            causes.add(c)
                            step_causes[c] += 1
                            if len(examples[c]) < 2:
                                examples[c].append(f'{m.group(2)[-55:]} ▸ {m.group(1)}')
                    if causes:
                        oracle_causes[(test, target)] = causes
    return {'oracles': len(oracles), 'tokens': len(tokens), 'steps': steps, 'dropped': dropped,
            'oracle_causes': oracle_causes, 'step_causes': step_causes, 'examples': examples}


def main():
    results = {}
    for spec in sys.argv[1:]:
        run, projects = spec.split(':')
        for project in projects.split(','):
            results[project] = analyse(run, project)
    total = collections.Counter()
    by_cause = collections.defaultdict(collections.Counter)
    examples = collections.defaultdict(list)
    print(f'{"project":10s} {"oracles":>8s} {"dropped":>8s} {"lose a step":>12s} {"complete":>9s} {"lost %":>7s} {"steps":>7s} {"missing":>8s}')
    for project, r in results.items():
        dropped = sum(r['dropped'].values())
        losing = len(r['oracle_causes'])
        missing = sum(r['step_causes'].values())
        complete = r['oracles'] - dropped - losing
        print(f'{project:10s} {r["oracles"]:8d} {dropped:8d} {losing:12d} {complete:9d} {100 * (dropped + losing) / max(r["oracles"], 1):6.1f}% {r["steps"]:7d} {missing:8d}')
        total.update({'oracles': r['oracles'], 'dropped': dropped, 'losing': losing, 'steps': r['steps'], 'missing': missing})
        for reason, n in r['dropped'].items():
            by_cause[f'(dropped) {reason}']['oracles'] += n
            by_cause[f'(dropped) {reason}']['only'] += n
            by_cause[f'(dropped) {reason}'][project] += n
        for causes in r['oracle_causes'].values():
            for c in causes:
                by_cause[c]['oracles'] += 1
                by_cause[c][project] += 1
                if len(causes) == 1:
                    by_cause[c]['only'] += 1
        for c, n in r['step_causes'].items():
            by_cause[c]['steps'] += n
            examples[c].extend(r['examples'][c][:1])
    lost = total['dropped'] + total['losing']
    print(f'{"total":10s} {total["oracles"]:8d} {total["dropped"]:8d} {total["losing"]:12d} {total["oracles"] - lost:9d} {100 * lost / max(total["oracles"], 1):6.1f}% {total["steps"]:7d} {total["missing"]:8d}')
    print('\ncauses by lost oracles (an oracle can have several causes; "only": the oracles recovered by fixing that cause alone)')
    for c, counts in sorted(by_cause.items(), key=lambda kv: -kv[1]['oracles']):
        per_project = ', '.join(f'{p} {counts[p]}' for p in results if counts[p])
        print(f'  {counts["oracles"]:5d} oracles ({counts["only"]:5d} only), {counts["steps"]:5d} steps  {c:32s} [{per_project}]')
        for e in examples[c][:2]:
            print(f'           e.g. {e}')


if __name__ == '__main__':
    main()
