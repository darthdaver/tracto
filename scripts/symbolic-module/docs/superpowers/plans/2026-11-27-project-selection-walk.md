# Selection of the experiment and validation projects (the walk) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Choose the 120 experiment projects (3 size groups × 2 JUnit versions × 20), the 30 validation projects and
the eligible repositories created after the cutoff, and write the split of the 1,572 Maven repositories with the
target test methods of each chosen project.

**Architecture:** Four small modules in `tools/gen`: `walk_java.py` parses Java test files (tree-sitter),
`walk_git.py` fetches the history since the cutoff and finds the test methods added after it, `walk_build.py` builds
a project and runs those tests with the JDKs in order, `walk.py` orders the candidates, evaluates them (resumable,
cached per candidate) and assigns them to the cells deterministically.

**Tech Stack:** Python 3.12 of the project virtual environment (`/Users/usi/repositories/tracto/.venv/bin/python`,
which has `tree-sitter` 0.24 and `tree-sitter-java` 0.23.5), git, Maven, the JDKs of SDKMAN; `unittest`.
`run_process` and `kill_group` of `tools/gen/generate.py` for every external command.

**Spec:** `docs/data-collection-issta2027.md` (steps 6–8), numbers in `docs/issta2027-dataset-numbers.md`.

## Global Constraints

- Cutoff `T = 2026-03-02T00:00:00Z`; a target test method has every line authored on or after T.
- Seed of the walk: `20261127`.
- Size groups by `javaCodeLines`: small `< 13207`, medium `13207 ≤ x < 50647`, large `≥ 50647`.
- Cells: (size group, JUnit version) with JUnit version `junit4` or `jupiter`; 20 experiment then 5 validation projects
  per cell; at least 10 oracles in passing target methods.
- Walk candidates: Maven list `resources/issta2027-maven-repositories.csv`, created before T
  (`createdAt` from `resources/issta2027-repositories.csv`), owner (`name` before `/`, ignoring case) with a single
  repository in the Maven list. The repositories created on or after T are evaluated apart (all of them).
- Test annotations, as the module (`TestClassScope.TEST_ANNOTATIONS`): `Test`, `ParameterizedTest`, `RepeatedTest`,
  `TestFactory`, `TestTemplate`, `Theory`.
- Assertion names, as the module (`JUnitAssertionType`): `assertAll`, `assertArrayEquals`, `assertDoesNotThrow`,
  `assertEquals`, `assertFalse`, `assertInstanceOf`, `assertIterableEquals`, `assertLinesMatch`, `assertNotEquals`,
  `assertNotNull`, `assertNotSame`, `assertNull`, `assertSame`, `assertThat`, `assertThrows`, `assertThrowsExactly`,
  `assertTimeout`, `assertTimeoutPreemptively`, `assertTrue`, `fail`; plus `assert` statements.
- JDKs (major → home): 8 `~/Library/Java/JavaVirtualMachines/corretto-1.8.0_482/Contents/Home`; 11, 17, 21, 25, 26,
  27 `~/.sdkman/candidates/java/` `11.0.30-amzn`, `17.0.21.1.2-amzn`, `21.0.6-amzn`, `25.0.5.1.2-amzn`,
  `26.0.2.0+11.1-amzn`, `27.0.1.1.4-amzn`.
- Maven flags of RQ2 (`rqs/bash/rq2_correctness_all.sh:88`): `-Dgpg.skip=true -Dspotless.check.skip=true
  -Dmaven.compiler.failOnWarning=false -Dcheckstyle.skip=true -Dlicense.skip=true -Drat.skip=true
  -Ddependency-check.skip=true`.
- Deterministic: the same evaluations give the same assignment and the same files byte for byte; every candidate
  examined is written with its outcome and reason; nothing is dropped silently.
- Tests run offline: `cd scripts/symbolic-module && ../../.venv/bin/python -m unittest tools/gen/test_walk*.py`.
- Not in this plan: the cap of 50 oracles per project and the mapping of targets to datapoints (after the large run),
  the runner changes (`2026-11-27-runner-before-large-run.md`).

## Review Focus

- A pinned commit that cannot be fetched (repository deleted, renamed, history rewritten): refused with reason
  `clone`, the walk goes on (Task 4).
- A multi-module project: the surefire reports are under each module's `target/surefire-reports` (Task 3).
- Parameterized and repeated tests appear in the reports as `m(String)[1]`, `m()[2]` or `[1] a, b`: they must match
  their method, and a method passes only if every run of it passed (Task 3).
- A build or test run that hangs: killed at its time limit, recorded, the next JDK is tried (Task 3).
- The walk interrupted and restarted: the finished evaluations are reused, the interrupted one is evaluated again,
  the assignment is the same as without interruption (Task 4).

---

### Task 1: Test methods of a Java file (`walk_java.py`)

**Files:**
- Create: `tools/gen/walk_java.py`
- Test: `tools/gen/test_walk_java.py`

**Interfaces:**
- Produces:
  - `ASSERTION_NAMES: frozenset[str]`, `TEST_ANNOTATIONS: frozenset[str]` (values in Global Constraints)
  - `@dataclass(frozen=True) class TestMethod: cls: str; name: str; body: str; oracles: int; start_line: int;
    end_line: int; oracle_keys: tuple[str, ...]; oracle_texts: tuple[str, ...]; tokens: tuple[str, ...]`
    — `cls` is the binary name (`com.x.FooTest`, nested `com.x.FooTest$Inner`); `tokens` the tokens of the method
    body, comments left out, with the **local names normalized** (the local variables, parameters, catch, for and
    lambda parameters of the method renamed `v1`, `v2`, ... in order of first appearance; fields, methods and types
    unchanged); `body` the tokens joined by one space; lines are 1-based and inclusive (the whole declaration,
    annotations included); one `oracle_keys` entry per oracle: the normalized tokens of the body from its start to the
    end of that assertion; one `oracle_texts` entry per oracle: the normalized tokens of the assertion alone.
  - `test_methods(source: bytes) -> list[TestMethod]` in source order.
  - `junit_flavour(source: bytes) -> str`: `'jupiter'`, `'junit4'` or `'unknown'`.

- [ ] **Step 1: Write the failing tests**

```python
class TestMethodsTest(unittest.TestCase):
    def test_annotated_methods_only(self):          # @Test m1, @org.junit.jupiter.params.ParameterizedTest m2, helper m3
        self.assertEqual(['m1', 'm2'], [m.name for m in wj.test_methods(SOURCE)])
    def test_binary_class_name_with_nested_classes(self):  # package com.x; class FooTest { @Nested class Inner { @Test void a() } }
        self.assertEqual('com.x.FooTest$Inner', wj.test_methods(NESTED)[0].cls)
    def test_body_ignores_whitespace_and_comments(self):    # same method reformatted and commented -> equal body
    def test_body_differs_on_a_literal(self):               # assertEquals(1, x) vs assertEquals(2, x) -> different body
    def test_local_names_normalized(self):                  # `int a = f(); assertEquals(1, a);` vs `int b = f(); ...` -> equal
        # a renamed field, method or type -> different body
    def test_one_key_per_oracle(self):                      # two assertions -> two keys, the first a prefix of the second
    def test_oracles_as_the_module(self):
        # assertEquals(..); Assertions.assertTrue(..); assertThat(x, is(y)); assert x > 0;
        # IllegalStateException e = assertThrows(...); fail("no");  -> 6
        # assertThat(x).isEqualTo(y); values.forEach(v -> assertTrue(v > 0)); helper.check(x); -> not counted
        self.assertEqual(6, wj.test_methods(ORACLES)[0].oracles)
    def test_lines_cover_the_annotations(self):             # @Test on line 3, method ends on line 6 -> (3, 6)
    def test_junit_flavour(self):
        # import org.junit.jupiter.api.Test -> jupiter; import org.junit.Test -> junit4;
        # both imports with the jupiter Test -> jupiter; static import org.junit.Assert.* only -> junit4; none -> unknown
    def test_newer_syntax_does_not_raise(self):             # `import module java.base;` + `int _ = f();` in a test
        self.assertEqual(['m'], [m.name for m in wj.test_methods(JAVA25)])
```

- [ ] **Step 2: Run them to verify they fail**

Run: `../../.venv/bin/python -m unittest tools/gen/test_walk_java.py`
Expected: ERROR `No module named 'walk_java'`.

- [ ] **Step 3: Implement `walk_java.py`**

tree-sitter with `tree_sitter_java.language()`; package from `package_declaration`; a method is a test when one of
its `modifiers` annotations (marker or not) has a name whose last identifier is in `TEST_ANNOTATIONS`. Oracles: the
`assert_statement` nodes, the `expression_statement` nodes whose expression is a `method_invocation` with a name in
`ASSERTION_NAMES`, and the `local_variable_declaration` nodes whose single initializer is such an invocation; none
counted when a `lambda_expression` lies between it and the method. Flavour: the import of the `Test` annotation
decides (`org.junit.jupiter.api.Test` or `org.junit.jupiter.api.*` → jupiter, `org.junit.Test` or `org.junit.*` →
junit4), else any `org.junit.jupiter` import → jupiter, else any `org.junit.` import → junit4.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `../../.venv/bin/python -m unittest tools/gen/test_walk_java.py` — Expected: OK.

- [ ] **Step 5: Commit** `tools/gen/walk_java.py tools/gen/test_walk_java.py`, message
  `[SYMBOLIC MODULE] Walk: the test methods of a Java file (tree-sitter, oracles counted as the module)`.

### Task 2: Test methods added after the cutoff (`walk_git.py`)

**Files:**
- Create: `tools/gen/walk_git.py`
- Test: `tools/gen/test_walk_git.py`

**Interfaces:**
- Consumes: `walk_java.test_methods`, `walk_java.junit_flavour`; `generate.run_process(command, log_path, cwd, timeout, job=None, env=None) -> dict`.
- Produces:
  - `CUTOFF = '2026-03-02T00:00:00Z'`
  - `fetch(name: str, commit: str, path: str, log_path: str, url: str | None = None) -> bool` — `git init`, then
    `git fetch --no-tags --shallow-since=2026-03-02 origin <commit>` and `git fetch --no-tags --deepen=1 origin
    <commit>` (the parents of the first commits after T), `git checkout -q --detach <commit>`; `url` defaults to
    `https://github.com/<name>.git` (tests pass a `file://` URL); True when HEAD is `commit`. Time limit 1,200 s per
    command, `GIT_TERMINAL_PROMPT=0`.
  - `base_commit(path: str, commit: str) -> str | None` — the newest first-parent ancestor of `commit` with
    committer date before `CUTOFF`; None when the history starts after it.
  - `@dataclass(frozen=True) class Target: file: str; cls: str; name: str; oracles: int; junit: str; start_line: int;
    end_line: int; duplicates: int; oracles_seen: int; similarity: float; most_similar: str` — `oracles` leaves out
    the `duplicates`: the oracles whose `oracle_keys` entry is the key of an oracle of a test method at the base commit
    (the same test prefix and oracle, local names aside, already written before T); `oracles_seen`: the oracles whose
    `oracle_texts` entry appears in a test method at the base (recorded, not dropped); `similarity`: the largest
    Jaccard similarity of token multisets (Σ min / Σ max of the counts of `tokens`) with a test method at the base, 0.0
    when there is no base; `most_similar`: that method as `cls#name`.
  - `@dataclass class Mined: base: str | None; targets: list[Target]; old_lines: int; test_files: int; parse_errors: int`
    — `old_lines`: methods new by name and body but with a line authored before T (dropped, counted).
  - `mine(path: str, commit: str) -> Mined`

- [ ] **Step 1: Write the failing tests** (a fixture repository built in a temporary folder with
  `GIT_AUTHOR_DATE`/`GIT_COMMITTER_DATE` per commit and `uploadpack.allowReachableSHA1InWant=true`, fetched through
  `file://`)

```python
class MineTest(unittest.TestCase):
    # commits: 2026-02-01 FooTest{a}; 2026-03-05 FooTest{a,b} + BarTest{c};
    #          2026-03-10 FooTest renamed a -> a2 (same body); 2026-04-01 d moved from FooTest to BazTest (same body)
    def test_only_methods_new_by_name_and_body(self):
        self.assertEqual({('FooTest', 'b'), ('BarTest', 'c')}, {(t.cls.split('.')[-1], t.name) for t in mined.targets})
    def test_base_is_the_last_commit_before_the_cutoff(self):     # == sha of the 2026-02-01 commit
    def test_history_after_the_cutoff_only_has_no_base(self):     # first commit 2026-03-20 -> base None, all methods targets
    def test_a_line_authored_before_the_cutoff_drops_the_method(self):
        # commit authored 2026-02-20, committed 2026-03-06 (a rebased pull request) adds e -> not a target, old_lines == 1
    def test_copied_prefix_and_oracle_is_a_duplicate(self):
        # 2026-03-07 adds f: the first assertion with its prefix copied from a (a local renamed), then a new one
        # -> f is a target with oracles 1, duplicates 1; a method whose only oracle is copied is not a target
    def test_similarity_to_the_tests_before_the_cutoff(self):
        # b differs from a by one literal -> similarity > 0.9 and most_similar 'FooTest#a'; c shares no token
        # sequence with a -> similarity < 0.3; history after the cutoff only -> 0.0 and ''
    def test_test_files_only_under_src_test(self):                # a @Test method in src/main/java is ignored
    def test_generated_star_files_ignored(self):                  # FooSTARSplitTest.java is ignored
    def test_unknown_commit_is_not_fetched(self):
        self.assertFalse(wg.fetch('x/y', '0' * 40, dest, log, url=f'file://{fixture}'))
```

- [ ] **Step 2: Run them to verify they fail**

Run: `../../.venv/bin/python -m unittest tools/gen/test_walk_git.py` — Expected: ERROR `No module named 'walk_git'`.

- [ ] **Step 3: Implement `walk_git.py`**

Files: `git ls-tree -r --name-only <rev>`, the `*.java` paths with a `src/test/` component, not matching
`STAR(SplitTest|NormalizedTest|SplitTestPrefix)\.java$`; contents with one `git cat-file --batch`. A method at
`commit` is new when its `(cls, name)` and its `body` are both absent from the methods at `base` (every method of
`commit` is new when `base` is None). For the new methods, one `git blame --porcelain <commit> -- <file>` per file
gives the author time of each line; a method is a target only when every line from `start_line` to `end_line` has an
author time at or after `CUTOFF`, and it keeps at least one oracle that is not a duplicate. Similarity: compare with
the base methods in decreasing order of the bound `min(n, m) / max(n, m)` (n, m their numbers of tokens) and stop when
the bound is below the best value found; log the time of a repository that needs more than 10 minutes. A file tree-sitter cannot fully parse counts in `parse_errors` (its parsed methods
are still used).

- [ ] **Step 4: Run the tests to verify they pass** — Expected: OK.

- [ ] **Step 5: Commit** `tools/gen/walk_git.py tools/gen/test_walk_git.py`, message
  `[SYMBOLIC MODULE] Walk: the test methods added after the cutoff (new name, new body, every line authored after T)`.

### Task 3: Build and run the target tests (`walk_build.py`)

**Files:**
- Create: `tools/gen/walk_build.py`
- Test: `tools/gen/test_walk_build.py`

**Interfaces:**
- Consumes: `walk_git.Target`; `generate.run_process`.
- Produces:
  - `JDK_HOMES: dict[int, str]`, `JDK_VERSIONS = [8, 11, 17, 21, 25, 26, 27]`, `RQ2_FLAGS: list[str]` (Global Constraints)
  - `declared_java_version(pom: str) -> int | None` — from the root `pom.xml`: `maven.compiler.release`, else
    `<release>` of `maven-compiler-plugin`, else `maven.compiler.target`, `maven.compiler.source`, `<source>`,
    `java.version`; `${property}` resolved from `<properties>`; `1.8` → 8.
  - `jdk_order(declared: int | None) -> list[int]` — `[v ≥ declared ascending] + [v < declared descending]`; None →
    `[21, 17, 11, 8, 25, 26, 27]`.
  - `surefire_results(repo: str) -> dict[tuple[str, str], bool]` — `(class binary name, method)` → passed, from every
    `TEST-*.xml` under a `target/surefire-reports` folder of the repository.
  - `check_jdks() -> None` — raises when a home of `JDK_HOMES` is missing or its `bin/java -version` is not that
    major version (called once when the walk starts).
  - `build_and_test(repo: str, targets: list[Target], log_path: str, timeout: int = 1800) -> dict` — returns
    `{'jdk': int | None, 'attempts': [{'jdk', 'build': {run_process result}}, ...], 'results': {(cls, name): bool}}`.

- [ ] **Step 1: Write the failing tests**

```python
class BuildTest(unittest.TestCase):
    def test_declared_version(self):
        # release 17 -> 17; <maven.compiler.source>1.8</...> -> 8; <java.version>21</> with
        # <maven.compiler.release>${java.version}</> -> 21; compiler plugin <release>25</release> -> 25; nothing -> None
    def test_jdk_order(self):
        self.assertEqual([17, 21, 25, 26, 27, 11, 8], wb.jdk_order(17))
        self.assertEqual([21, 17, 11, 8, 25, 26, 27], wb.jdk_order(None))
        self.assertEqual([25, 26, 27, 21, 17, 11, 8], wb.jdk_order(24))
    def test_reports_of_every_module(self):          # core/target/surefire-reports and api/target/surefire-reports
    def test_parameterized_runs_match_their_method(self):
        # testcases 'm(String)[1]' ok, 'm(String)[2]' failure, 'n()[1]' ok, '[1] a, b' with classname of p ok
        self.assertEqual({('c.FooTest', 'm'): False, ('c.FooTest', 'n'): True}, ...)
    def test_skipped_is_not_passed(self):
    def test_next_jdk_after_a_failed_or_timed_out_build(self):
        # run_process mocked: build with 17 times out, with 21 exits 0, tests exit 1 -> jdk 21, two attempts,
        # results from the reports (failed tests are a result, not a reason to try another JDK)
    def test_commands(self):
        # build: [mvn, -B, -ntp, clean, install, -DskipTests, *RQ2_FLAGS]; tests: [mvn, -B, -ntp, test,
        # -Dtest=c.FooTest#a+b,c.BarTest$Inner#d, -Dsurefire.failIfNoSpecifiedTests=false, -DfailIfNoTests=false,
        # *RQ2_FLAGS]; env JAVA_HOME = JDK_HOMES[jdk], PATH starting with its bin
```

- [ ] **Step 2: Run them to verify they fail** — Expected: ERROR `No module named 'walk_build'`.

- [ ] **Step 3: Implement `walk_build.py`** (pom read with `xml.etree`, namespaces stripped; a testcase `name` matches
  method `m` when it equals `m` or starts with `m(` or `m[`; a name starting with `[` belongs to the only target
  method of its class whose other runs are absent; a method passes when it has at least one run and none has
  `failure`, `error` or `skipped`).

- [ ] **Step 4: Run the tests to verify they pass** — Expected: OK.

- [ ] **Step 5: Commit** `tools/gen/walk_build.py tools/gen/test_walk_build.py`, message
  `[SYMBOLIC MODULE] Walk: build a project and run its target tests under the conditions of RQ2, JDK 8 to 27`.

### Task 4: The walk and the split (`walk.py`)

**Files:**
- Create: `tools/gen/walk.py`
- Test: `tools/gen/test_walk.py`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `SEED = 20261127`, `BOUNDS = (13207, 50647)`, `EXPERIMENT_PER_CELL = 20`, `VALIDATION_PER_CELL = 5`, `MIN_ORACLES = 10`
  - `size_group(java_lines: int) -> str` (`'small' | 'medium' | 'large'`)
  - `candidates(maven_csv: str, info_csv: str) -> tuple[dict[str, list[dict]], list[dict]]` — the walk candidates by
    group in walk order (sorted by numeric id, then `random.Random(SEED).shuffle` per group), and the repositories
    created after T.
  - `evaluate(repo: dict, work: str, logs: str) -> dict` — `{'id', 'name', 'status': 'eligible' | 'refused',
    'reason', 'jdk', 'junit', 'oracles', 'targets': [Target as dict + 'passed']}`; reasons: `clone`, `fewer than 10
    oracles in new tests (N)` (no build then), `does not build with any JDK`, `fewer than 10 oracles in new tests that
    pass (N)`, `JUnit version unknown`; the clone deleted afterwards.
  - `assign(order: dict[str, list[str]], evaluations: dict[str, dict]) -> dict[str, tuple[str, str]]` — id →
    (`'experiment' | 'validation' | 'eligible, cell full'`, cell `'<group>/<junit>'`), walking the groups round-robin
    (small, medium, large) in their orders; a candidate without evaluation stops its group.
  - CLI `walk.py run --dir tmp/gen/issta2027/walk [-j 3] [--until ISO-datetime] [--limit N]` (evaluations appended to
    `<dir>/evaluations.jsonl`, one log folder per candidate; stops a group when both its cells have 25 projects; stops
    starting candidates after `--until`) and `walk.py split --dir ... --out resources/issta2027-split.csv --targets
    resources/issta2027-targets.csv` (also writes `<dir>/summary.json`).

- [ ] **Step 1: Write the failing tests** (evaluations given as data: no clone, no build)

```python
class WalkTest(unittest.TestCase):
    def test_groups(self):  # 13206 small, 13207 medium, 50646 medium, 50647 large
    def test_candidates(self):
        # owner with two Maven repositories left out; owner case ignored; created on 2026-03-02 -> after-T list;
        # same inputs -> same order; the order is a permutation of the ids sorted numerically, shuffled with SEED
    def test_assignment_fills_experiment_then_validation(self):
        # 26 eligible small/jupiter in order -> 20 experiment, 5 validation, 1 'eligible, cell full'
    def test_assignment_ignores_completion_order(self):
        # the same evaluations inserted in two different dict orders -> the same assignment
    def test_a_missing_evaluation_stops_its_group_only(self):
    def test_eligibility_reasons(self):
        # 9 oracles before build -> refused without calling build_and_test (mock asserts not called);
        # 12 oracles, 3 of them in failing methods -> refused 'fewer than 10 oracles in new tests that pass (9)';
        # fetch False -> 'clone'; junit label = flavour of most passing target classes
    def test_resume(self):
        # evaluations.jsonl with 2 lines + a truncated third -> 2 reused, the third evaluated again
    def test_split(self):
        # every Maven repository exactly once; walk refusals and 'cell full' -> fine-tuning; after-T eligible ->
        # experiment (cell recorded, not counted in the 20); after-T not eligible -> excluded; two after-T
        # repositories with the same repository name -> the one with more passing oracles (then lower id), the other
        # excluded 'duplicate of'; a fine-tuning repository with the owner or the repository name of an experiment or
        # validation project -> excluded with that reason; the file sorted by numeric id; same inputs -> same bytes
```

- [ ] **Step 2: Run them to verify they fail** — Expected: ERROR `No module named 'walk'`.

- [ ] **Step 3: Implement `walk.py`**

Evaluation in a thread pool of `-j` workers fed in the round-robin order; each finished evaluation is appended to
`evaluations.jsonl` (one JSON line, flushed); `assign` runs on the evaluations so far after each one, and a group whose
two cells are full gets no new candidate. The after-T repositories are evaluated first (all 19). Split columns: `id,
name, commit, javaCodeLines, sizeGroup, createdAfterCutoff, set, junit, reason, walkOrder`; targets columns: `id,
name, file, class, method, startLine, endLine, oracles, duplicates, oraclesSeen, similarity, mostSimilar, junit,
passed` (experiment and validation projects, every
target, sorted). `summary.json`: seed, start and end, per group and cell the candidates examined, refused by reason,
experiment, validation, oracles; the after-T results.

- [ ] **Step 4: Run all the walk tests** — `../../.venv/bin/python -m unittest tools/gen/test_walk*.py` and
  `python3 -m unittest tools/gen/test_generate.py tools/gen/test_repo_list.py` — Expected: OK.

- [ ] **Step 5: Commit** `tools/gen/walk.py tools/gen/test_walk.py`, message
  `[SYMBOLIC MODULE] Walk: seeded order per size group, resumable evaluations, deterministic cells and split`.

### Task 5: Run the walk and record the numbers

**Files:**
- Create: `resources/issta2027-split.csv`, `resources/issta2027-targets.csv`
- Modify: `docs/issta2027-dataset-numbers.md` (section 5 results, section 4, section 9 hashes and seed),
  `docs/data-collection-issta2027.md` (steps 6–8 done)

- [ ] **Step 1: Smoke run on two candidates** — `../../.venv/bin/python tools/gen/walk.py run --dir
  tmp/gen/issta2027/walk-smoke -j 1 --limit 2`; check
  the two log folders and the two lines of `evaluations.jsonl` by hand (targets plausible, build commands as RQ2).
- [ ] **Step 2: The walk** — `nohup ../../.venv/bin/python tools/gen/walk.py run --dir tmp/gen/issta2027/walk -j 3
  --until 2026-12-04T12:00:00 > tmp/gen/issta2027/walk.log 2>&1 &`; check the refusal reasons after the first 30
  evaluations and report any systematic one (e.g. every build failing) to the author before going on.
- [ ] **Step 3: Split** — `walk.py split ...`; run it twice and `cmp` the outputs.
- [ ] **Step 4: Numbers** — fill the **(to fill)** results of sections 4, 5 and 9 from `summary.json`; report to the
  author, with any cell that is not full.
- [ ] **Step 5: Commit** the two resources files and the two docs, message
  `[SYMBOLIC MODULE] The experiment, validation and fine-tuning projects of the ISSTA 2027 datasets`.
