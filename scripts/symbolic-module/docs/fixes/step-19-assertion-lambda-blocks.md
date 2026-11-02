# Step 19 – Lambda blocks of `assertThrows`, `assertThrowsExactly` and `assertDoesNotThrow`

**Date:** 2026-11-02
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `94d26378`, which commits Step 18)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/AssertionLambdaBlockClassifier.java` (new)
- `src/main/java/star/tracto/data/enums/AssertionLambdaBlockShape.java`, `AssertionLambdaBlocksStrategyType.java` (new)
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (rewrite of the lambda blocks, `throws Exception`,
  statistics, final copies extracted from `flatTryStmt`, D38)
- `src/main/java/star/tracto/preprocessing/utils/TestCaseSplitter.java` (assertions without datapoint)
- `src/main/java/star/tracto/data/records/OraclesDatasetConfig.java`, `src/main/resources/dataset/oracles-dataset_config.json`
  (required key `assertion-lambda-blocks`)
- `src/main/java/star/tracto/preprocessing/components/TestStats.java`, `builders/TestStatsBuilder.java`,
  `preprocessing/OraclesDataset.java` (statistics `assertionLambdaBlocks`)
- `bash/oracles-dataset.sh` (regression of Step 17: the space between `auto` and the configuration path)
- tests: `AssertionLambdaBlockClassifierTest`, `TestUtilsAssertionLambdaBlocksTest` (new), `TestUtilsNormalizeTest`,
  `TestUtilsSplitTest`, `OraclesDatasetConfigTest`, `OraclesDatasetTest`, `ConfigBuilder`
- docs: `docs/oracles-dataset-config.md`, `docs/design-review-2026-10-26.md`

## Problem

Developers often write a whole scenario within the lambda expression of an exception assertion:

```java
assertThrows(WireParseException.class, () -> {
    m_di.jump(7);
    m_di.readU32();
});
```

The normalizer copied these statements unchanged and the splitter made the whole `assertThrows` one split point: the
target of the datapoint contained the scenario (`m_di.jump(7)`), which belongs to the test prefix, and the assertions
within the block were never targets. The `flat` mode of the `assertThrows` key, removed in Step 10 (D9), had handled the
block by moving all of it out and using the `THROW EXCEPTION` label (removed in Step 14) as target.

Survey of the 24,367 test files of `input/github-repos` (a text scan, approximate):

| Assertion | Calls | With a lambda block | Blocks of 2+ statements |
|-----------|-------|---------------------|-------------------------|
| `assertThrows` | 7,815 | 1,061 (62 projects) | 294 |
| `assertThrowsExactly` | 343 | 301 | 1 |
| `assertDoesNotThrow` | 828 | 250 | 125 |
| `assertAll` | 396 | 37 | 31 |

The last statement of the `assertThrows` blocks is a call in 836 cases, an assertion around the throwing call in 166
(`assertEquals(ZERO, Rational.NaN.bigIntegerValue())`, almost always alone), a declaration in 20, a try, loop or `if`
in 23, a `throw` in 9.

## Decisions (2026-11-02)

- The statements before the last one move before the assertion, assertions included; only the last statement, the one
  expected to throw, stays within the lambda. `assertDoesNotThrow` is treated the same way, and is unwrapped when its
  block ends with an assertion (the wrapper adds nothing an exception would not already do: the inner assertions are
  the oracles).
- The shapes whose throwing statement is unclear, or that recall a bad design (an `assertThrows` ending with an
  assertion, a block ending with a try, a loop, an `if` or a `throw`), produce no datapoint: only the datapoint of that
  assertion is dropped, the rest of the test case is kept.
- The conservative option (no datapoint for any of these assertions) is available as a configuration value.
- `assertAll`, `assertTimeout`, AssertJ `assertThatThrownBy` (about 50 blocks) and the lambda handling of the tokens
  dataset are out of scope.

## Fix

**Configuration.** New required key `"assertion-lambda-blocks"`: `move-to-prefix` (committed) or `skip`. A
configuration without it is rejected when it is loaded, like `try-catch-finally` (D9 came from a missing key that
selected a mode silently). Documented in `docs/oracles-dataset-config.md`.

**`AssertionLambdaBlockClassifier`** (like `TryStatementClassifier`). An assertion is in scope when it is an
`assertThrows`, `assertThrowsExactly` or `assertDoesNotThrow` statement (or the initializer of the only variable of a
declaration) whose first lambda argument (the executable; a message supplier follows it) is a block of at least two
statements. The shape is decided by the last statement (`AssertionLambdaBlockShape`):

| Shape | Last statement | `assertThrows`, `assertThrowsExactly` | `assertDoesNotThrow` |
|-------|----------------|----------------------------------------|----------------------|
| `LAST_CALL` | call or object creation, possibly assigned, declared or returned | moved | moved |
| `LAST_ASSERTION` | `assert`, JUnit assertion, `fail()` | no datapoint | unwrapped |
| `LAST_COMPOUND` | try, loop, `if`, `switch`, `synchronized`, labeled, block | no datapoint | no datapoint |
| `LAST_THROW` | `throw` | no datapoint | no datapoint |
| `LAST_OTHER` | bare `return`, `return v;`, `int r = 1 / a;` (not a call: it cannot be the body of an executable) | no datapoint | no datapoint |
| `EARLY_RETURN` | a `return` of the lambda (not of a nested lambda or class) before the last statement: it would end the test case | no datapoint | no datapoint |

With `skip` every assertion in scope produces no datapoint. The decision depends only on the source code of the
statement, so the splitter, which reads the normalized test class again, reaches the same decision as the normalizer.

**Normalizer** (`TestUtils.normalizeAssertionLambdaBlock`):

```java
// before
assertThrows(IndexOutOfBoundsException.class, () -> { int i = 0; i += 5; values.get(i); });
// after
int i = 0;
i += 5;
final int finalI = i;
assertThrows(IndexOutOfBoundsException.class, () -> values.get(finalI));
```

- The moved statements are normalized like the others: auxiliary methods inlined, try statements converted, a nested
  lambda block moved in turn.
- A variable of the block hiding a field is renamed (`count2`); a variable reassigned within the block and used by the
  remaining call gets a final copy (`finalI`, the logic of Step 13, now shared with `flatTryStmt` as
  `finalCopiesOfReassignedVariables`).
- The test case declares `throws Exception` (unless it declares `Exception` or `Throwable`): the lambda absorbed the
  checked exceptions of the moved statements.
- The outcome of each assertion is counted in the statistics: `assertionLambdaBlocks` in `test-stats-<project>.json`
  and in `statistics-<project>.json`, with the keys `moved-to-prefix`, `unwrapped`, `skipped-<shape>`.

**Splitter.** An assertion without datapoint is not a split point (with both `split` values). It stays in the prefixes
of the following assertions as ordinary code, it is not counted by `num-assertions-match`, and it does not end the
extra split test case of `keep-statements-after-last-assertion`. An assertion in scope left unchanged by the normalizer
in `move-to-prefix` mode (an error while moving it, or an assertion within the lambda block of another statement, which
the normalizer does not visit) stays a split point, as before.

**D38 (found while writing the tests).** When a try/catch/fail is converted (`to-assert-throws`), a variable of the try
block (or a resource) hiding a field is renamed, but the copy of the failing call within the `assertThrows` kept the old
name, so it read the field:

```java
private String s = "field";
...
try { String s = "x"; Integer.parseInt(s); fail(); } catch (NumberFormatException e) { }
// normalized before the fix:
String s2 = "x";
assertThrows(NumberFormatException.class, () -> Integer.parseInt(s));   // the field
```

The variables are now renamed before the failing call is copied; a renamed variable reassigned within its block keeps
its final copy (`finalS2`).

**Regression of Step 17.** In `bash/oracles-dataset.sh` the replacement of `24` with `auto` had dropped the space before
the configuration path (`auto"${SRC_RESOURCES_DIR}/..."`): the jar received the Java version and the configuration as a
single argument.

## Tests

| Test class | Tests |
|------------|-------|
| `AssertionLambdaBlockClassifierTest` (new) | 32 statements classified (each shape, the forms in and out of scope: qualified call, declaration, JUnit 4 message first, message supplier, nested return); the outcome for each shape, assertion and strategy (10). |
| `TestUtilsAssertionLambdaBlocksTest` (new) | Normalization: 13 moved shapes (call, declaration with message, `assertThrowsExactly`, last declaration and assignment, assertions moved out, `assertDoesNotThrow`, supplier form, unwrap, nested blocks, final copy, field hiding, inlining); 6 shapes left unchanged; the comment of the assertion before the moved (or unwrapped) statements (3); `skip`; `throws Exception` in 8 situations; statistics. Split: no split point (assertion and statement strategies), lenient split point for a block left by the normalizer, strict `num-assertions-match`, `keep-statements-after-last-assertion`. Datapoints: shortened target with the moved statements in the prefix, unwrapped assertions as targets, the other datapoints of a test case kept. Behaviour: the normalized test class compiles and runs like the original (final copy, supplier form, checked exception of an unwrapped block). |
| `TestUtilsSplitTest` | Characterization of the lambda paths of the splitter not tested yet: truncation within a lambda block, a nested lambda block, two lambda blocks in one statement (not split), an anonymous class (not split). They passed at once: no defect. |
| `TestUtilsNormalizeTest` | D38: a variable and a resource hiding a field renamed within the `assertThrows`; a renamed reassigned variable gets its final copy. |
| `OraclesDatasetConfigTest` | Committed value, `skip` accepted, missing key rejected. |
| `OraclesDatasetTest` | `assertionLambdaBlocks` in the statistics of the project; the skipped assertion produces no datapoint. |

Before the fix the new tests failed for the missing feature (and the D38 test showed `Integer.parseInt(s)`); the
inliner path with a `return` within a lambda was already covered (`returnWithinLambdaOfAuxiliaryMethodDoesNotPreventInlining`).
Full suite: 345 tests, all passing.

## Verification on real projects

**Oracles dataset.** The nine projects of Step 18, with the jar of Step 18 (A), Step 19 with `move-to-prefix` (B,
committed) and Step 19 with `skip` (C), on fresh copies (`tmp/ab-tools/compare19.py`):

| Project | Datapoints A → B (C) | Assertions in scope (B) | Errors of the invoked methods A → B |
|---------|----------------------|-------------------------|-------------------------------------|
| crawler-commons | 732 → 732 (730) | 2 moved | 22 → 20 |
| java-uuid-generator | 369 → 369 (369) | – | 13 → 13 |
| ffmpeg-cli-wrapper | 586 → 586 (586) | – | 7,002 → 7,002 |
| plexus-utils | 785 → **787** (780) | 4 moved, 1 unwrapped | 85 → 83 |
| esapi | 528 → 528 (528) | – | 2,965 → 2,965 |
| eec | 1,427 → 1,427 (1,427) | – | 516 → 515 |
| jsoup | 3,653 → 3,653 (3,643) | 10 moved | 134 → 124 |
| cryptofs | 909 → 909 (907) | 2 moved | 3,444 → 3,444 |
| steve | 163 → 163 (163) | – | 608 → 608 |
| **Total** | **9,152 → 9,154 (9,133)** | 18 moved, 1 unwrapped | |

- The 19 targets with a lambda block of two or more statements are replaced: 18 by the shortened assertion
  (`assertThrows(IllegalArgumentException.class, () -> attr.setKey(" "))`, with `Attribute attr = new Attribute("One",
  "Check");` in the prefix), and the `assertDoesNotThrow` of plexus-utils by its three inner assertions (+2 datapoints).
  No assertion of these projects has a shape without datapoint: the `skipped-*` statistics are empty with
  `move-to-prefix`. With `skip` the 19 assertions produce no datapoint (−19).
- The other targets, and their prefixes, are unchanged, except in `CycleDetectorTest` of plexus-utils: the following
  datapoints of the test case show the moved statements, the shortened `assertDoesNotThrow` and `throws Exception`
  (29 prefixes). There the A/B showed that the comment of the assertion (`// --------->`) stayed on the shortened
  assertion, after the moved statements (and the comment of an unwrapped `assertDoesNotThrow` was lost): the comment now
  moves to the first moved statement (tests added, rerun of the four projects with moved blocks: only those 29 prefixes
  differ).
- The targets with a lambda block left in B are single-statement blocks (`assertThrows(NoSuchFileException.class, () ->
  { prov.inflate(...); })`: 70 in cryptofs, 2 in jsoup, 1 in plexus-utils and ffmpeg-cli-wrapper) and
  `assertTimeoutPreemptively` (5, cryptofs): out of scope.
- The errors of the invoked methods decrease where blocks moved (the calls of the moved statements are resolved as
  statements of the prefix). In eec the generated files are identical in A and B: the one error less (and one invoked
  method more) is the run-to-run variation of the type inference of JavaParser for a lambda (`Equivalent types are:
  [InferenceVariableType{id=1}, ...]`).
- No new split error, no new discarded test case; run time of the three runs (in parallel) 13 minutes.

**Tokens dataset.** The tokens stage on crawler-commons, Step 18 and Step 19: 727 → **729** datapoints. The two
`assertThrows` with a lambda block, whose targets produced no tokens datapoint in Step 18, produce one each (21 steps).
Ignoring the order of the candidates (which changes at each run), 16 of 9,596 steps differ, all at
`sm.getSiteMapUrls().iterator().next()` in three test cases not changed by Step 19: the element type of the iterator is
inferred at some runs only (the known issue of Step 18; this time it failed in one test case instead of two).

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
mvn test
```
