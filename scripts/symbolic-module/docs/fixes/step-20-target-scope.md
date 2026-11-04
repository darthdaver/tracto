# Step 20 – Local variables and parameters in scope at the position of the target

**Date:** 2026-11-03/04 (the A/B ended on 2026-11-04)
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `8b89040`, whose code is the one of `28c7ee56`, Step 19)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TargetScope.java` (new)
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java` (`setUpRootTokenIdentifiers` takes the position of the
  target; the split test class is parsed to locate it, in the generation of the dataset and in the inference)
- tests: `TargetScopeTest` (new, 13 tests), `TokensDatasetTest` (new, 2 tests: the first end-to-end test of the tokens
  stage)
- tools: `tools/ab/compare_tokens.py` (new: compares the tokens datasets of two runs, oracle by oracle)

## Problem

The measurement of the nine reference projects (`docs/missing-candidate-tokens.md`, 2026-11-03) ranked first, by lost
oracles, the variables declared within a nested block (706 oracles, 414 recovered by fixing that cause alone) and,
further down, the parameters of the test method (60, 50 alone). Examples:
`assertEquals(rs.getString(2), ▸ row` (eec, `row` declared in the body of a `while`), `assertEquals( ▸ isAllowed`
(crawler-commons, parameter of a parameterized test), `assertTrue(uuid_time <= end_time, "UUID: " + ▸ i7` (uuid, the
variable of a `for`).

Two causes in `TokensUtils.setUpRootTokenIdentifiers`:

- the local variables were collected with `hierarchicalLevel = 0`: only the declarations at the top level of the body of
  the test prefix. The variables of the loop bodies, the for-each and `for` variables, the variables of the blocks of
  `if`, `try`, lambda expressions were never candidates;
- the parameters were collected with `getBody().findAll(Parameter.class)`: the parameters of all the catch clauses and
  lambda expressions of the body, in scope or not, and never the parameters of the test method itself.

The test prefix alone does not tell where the target was: the prefix class (`...STARSplitTestPrefix.java`) is printed
without the target, and a target at the end of a loop body and a target right after the loop leave the same prefix (the
`/*<MASK_PLACEHOLDER>*/` comment exists only in the body of the datapoint, not in the file the tokens stage parses).

## Fix

`TargetScope` locates the target and collects the declarations a Java compiler accepts at its position.

- **Position.** The target is the last assertion of the split test case (`...STARSplitTest.java`, the `filePath` of the
  test class of the datapoint), found with `JavaParserUtils.getLastAssertionInMethodDeclaration`, as the oracles stage
  does. Its block (or switch entry) is mapped to the prefix by its index among the statements of the method in pre-order:
  the splitter removes the target and everything after it (`TestCaseSplitter.truncateAfter`, `removeSplitPoint`), so the
  statements before the target are the same in both methods. The mapping is checked (the prefix has the statements of
  the split test case minus the target and the statements within it, of the same classes up to the target); when the
  check fails, or the split test class cannot be parsed, the previous collection is kept and the message
  `Target position not found in the test prefix: ...` is recorded in the statistics of the datapoint.
- **Declarations in scope**, walking up from the position to the test method: the local variables declared before the
  target in each enclosing block (and in the previous entries of an enclosing switch block), the variables of the
  enclosing `for` initializations, for-each statements and try-with-resources, the parameters of the enclosing catch
  clauses, lambda expressions and methods (the methods of anonymous and local classes are crossed, up to the test
  method). The innermost declarations come first.
- `setUpRootTokenIdentifiers` puts the variables in the map of the field identifiers (as the top-level variables before)
  and the parameters in the map of the parameter identifiers. The two modes (generation and inference) use it.

Not supported: the pattern variables (`x instanceof Foo foo`), the fields of anonymous and local classes, the inference
on a test prefix without its split test case (the inference of the module runs on the oracles datasets, which have it).

## Tests

- `TargetScopeTest` (13): top-level target with the parameters of the method; target within a `for`, a for-each, a
  `while` within an `if`, a lambda block, a try-with-resources, a catch clause, a switch entry, a method of an anonymous
  class (inner declarations first); the declarations of previous sibling blocks, previous lambdas and catch clauses are
  not in scope; the position belongs to the prefix; no position when the prefix does not match or without assertion.
  The prefix is parsed from its own source, as in the pipeline.
- `TokensDatasetTest` (2), end to end on a fixture repository (oracles stage with the committed configuration, then the
  tokens stage): the variables of a `for` body, of a for-each, of a lambda block and the parameter of a parameterized test
  are candidates (no `Unexpected token`), and the variables of a previous loop and the parameter of a previous lambda are
  not.

Before the fix the 15 tests fail (the end-to-end test reproduces the missing `doubled`, `i`, `value`, `sum`, `input`
and the out-of-scope `x`); after the fix all the 360 tests of the module pass.

## Verification on real projects (A/B)

A = jar of `28c7ee56` (Step 19), B = this step; the nine reference projects, oracles and tokens stages on all of them,
the two sides run at the same time on the same machine (Linux, 32 cores, 314 GB; Corretto 21.0.6; `-j 9`,
`-Xmx24g`), `tmp/ab/step20-A` and `tmp/ab/step20-B`; `tools/ab/compare_tokens.py`.

| Project | Oracles | Lost A | Lost B | Recovered | Regressions | Steps with candidates added / removed |
|---------|---------|--------|--------|-----------|-------------|----------------------------------------|
| crawler-commons | 732 | 142 | 87 | 55 | 0 | 411 / 9 |
| ffmpeg-cli-wrapper | 586 | 401 | 401 | 0 | 0 | 4 / 4 |
| jsoup | 3,653 | 524 | 490 | 34 | 0 | 671 / 317 |
| eec | 1,427 | 590 | 212 | 378 | 0 | 3,493 / 1,942 |
| plexus-utils | 787 | 70 | 58 | 12 | 0 | 103 / 9 |
| esapi | 528 | 184 | 169 | 15 | 0 | 92 / 0 |
| cryptofs | 909 | 288 | 271 | 17 | 0 | 210 / 138 |
| java-uuid-generator | 369 | 209 | 122 | 87 | 0 | 883 / 17 |
| steve | 163 | 87 | 82 | 5 | 0 | 22 / 0 |
| **total** | **9,154** | **2,495 (27.3%)** | **1,892 (20.7%)** | **603** | **0** | **5,889 / 2,436** |

(The oracles are counted by test class file, test prefix and target; `missing_tokens.py` counts the distinct test
prefixes and targets, 9,049, and gives 27.1% lost before and 20.8% after.) By cause (`missing_tokens.py`,
`tmp/ab/step20-B/missing_tokens.txt`): the variables of nested blocks go from 706 lost oracles to 10 (all members of
such a variable after a call, `fill.getFgColor(). ▸ equals`: type inference), the parameters of the test method from 60
to 1. ffmpeg recovers nothing: its loop variable `i` is now offered, but its oracles are lost to the unsupported
`assertThat` and to the members of chains. The first causes are now the members of chains (457), the class names (331),
the unsupported `assertThat` (314) and the targets that do not start with a JUnit assertion (201).

Cost: the tokens stage of jsoup took 15,506 s wall and 20,716 s CPU in B against 15,309 s and 20,672 s in A (+1.3%
wall, +0.2% CPU), eec 9,828 s and 12,082 s against 9,605 s and 11,969 s; the peak memory is the same (jsoup 20.6 GB
against 20.5 GB). The split test class is parsed once per test class.

- The oracles datasets of A and B are identical in every project (except the random names of the anonymous classes,
  `Anonymous-<uuid>`, different at each run).
- The position of the target is found for every datapoint of the nine projects (no `Target position not found`).
- No oracle complete in A is lost in B.
- Candidates removed (2,436 token steps): checked on all the nine projects with an independent text-based check of the
  scope of each removed name in the body of the prefix (it does not use `TargetScope`), and by hand on samples. They are
  parameters of the methods of anonymous classes (`frame`, `header`, `e2` in ffmpeg; `reader`, `dir`, `vpath`, `fast` in
  plexus; `userAgent`, `targetTokens` in crawler-commons), parameters of the lambda expressions of previous statements
  (`u`, `bytes`, `invocation`, `pathToVault`, the style processors `(o, style2, sst) -> ...` of eec, `el`, `tag` in
  jsoup), parameters of previous catch clauses (`e`), and a variable referenced within its own initializer (the target
  within `Runnable task = () -> { ... }`, jsoup: Java rejects `task` there). The names in scope are removed only where an
  identifier cannot follow (after a dot or a closing parenthesis): there A offered the parameters of all the lambdas of
  the body, B the members of the receiver (eec, `testFormula_split_0`: after `formula.` A offered only `sheet`, B the 57
  methods of `String`).
- Labels of the targets: some local variables that A labelled `LAMBDA_PARAM` (for example `LAMBDA_PARAM:row` and
  `LAMBDA_PARAM:start` in eec, `testFormula_split_0`, where `row` and `start` are local variables of a loop within a
  lambda block) are labelled `IDENTIFIER` in B (observed; the labelling code was not traced); the `LAMBDA_PARAM` placeholder offered by A instead of the parameter `input` of a parameterized test
  (cryptofs) is replaced by `input`.
- Exceptions: in cryptofs, 5 token steps (after `inUseManager.validate(props)` and after `stepParentDir.`) gain or lose
  methods of `Object` (`finalize`, `wait0`, `clone`, `notifyAll`, ...), whose scope did not change. These candidates
  depend on the history of the run: the jar A itself gives different sets of methods of `Object` at these steps when the
  test class is processed alone instead of after the other classes of the project (the same jar on the same input is
  deterministic: the two runs of A on 2026-11-03, `measure` and `step20-A`, have the same candidates at all the 7,703
  steps of cryptofs). The state that crosses the datapoints (the caches of the symbol solver, or the static fields of
  `TokensUtils`) is a separate problem, not of this step; none of these steps has an identifier as target.
- Other finding, not of this step: the methods of a type in another package are offered with their protected members
  when the package of the test class *contains* the package of the type as a string (`TokensUtils`, the check
  `testClassPackageDeclaration...contains(packageName)`), so `Object.finalize` and the private `Object.registerNatives`
  appear among the candidates.

## How to run the tests

```bash
mvn test -Dtest='TargetScopeTest,TokensDatasetTest'
```
