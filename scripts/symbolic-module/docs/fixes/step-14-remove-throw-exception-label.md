# Step 14 – Remove the `THROW EXCEPTION` label

**Date:** 2026-10-27
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `e7b6347f`, which commits Step 13)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (targets of `processSplitTestClass`, plain
  `assertThrows` in `flatTryStmt`)
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java` (label removed from the tokens dataset and from the
  inference)
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (`getLastStatementInMethodDeclaration`, D32)
- `utils/find_assertion_type.py`, `utils/assertion_distribution.py`
- tests: `TestUtilsDatapointsTest`, `TestUtilsNormalizeTest`, `JavaParserUtilsTest`, `OraclesDatasetTest`
- docs: `docs/design-review-2026-10-26.md`, `docs/oracles-dataset-config.md`

## Problem

A target starting with `fail` became the label `THROW EXCEPTION` (`processSplitTestClass`), whatever the message of the
`fail(...)`. The tokens stage (`TokensUtils`) treated the label as a single token, offered at the first step of every
target as the only "generic" alternative to the name of an assertion, and the inference stopped when the model
generated it. The Python utilities listed the label among the assertion types. With the `flat` mode of `assertThrows`
(removed in Step 10) the label had a second origin, a fake assertion marked with `FAKE_ELEMENT_LABEL`, still handled by
`processSplitTestClass`.

A second item of the decision: the normalization declared a variable for the exception of every expected exception,
`E e = assertThrows(...)`, even when the catch block did not use it (an empty catch block, for example).

While writing the tests, one more defect was found:

- **D32**: the target of a split test case is searched from its last statement in pre-order. For a split point within a
  try block, that is the empty catch (or finally) block kept by the truncation (Step 10): with `keep-try-catch`, the
  `fail(...)` and the assertions of the try blocks produced no datapoint ("No assertion found"). With the committed
  configuration the same happens to the try statements the normalizer does not rewrite: those within a lambda block
  (`Runnable task = () -> { try { ... } finally { ... } };`). With `split = statement`, an empty compound statement
  (`for (...) {}`) got its empty body `{}` as target.

## Decision applied

From the decisions of 2026-10-26: the `THROW EXCEPTION` label is removed everywhere, a `fail(...)` is a target like
any other statement; when the catch block does not use the exception, a plain `assertThrows(...)` is generated.

## Changes applied

- `processSplitTestClass`: the target is the statement as written, also for `fail(...)` (with the `Assert.` /
  `Assertions.` qualifiers removed, as for the other assertions). The handling of `FAKE_ELEMENT_LABEL` (the removal of
  the fake comments and the fake assertion) is removed with the constants: nothing produces it since Step 10.
- `flatTryStmt`: the exception of an expected exception is assigned to a variable only if the catch block uses it;
  otherwise the statement is `assertThrows(E.class, () -> call());`.
- `TokensUtils`: a `fail(...)` target is tokenized like the other assertions (`fail` is a JUnit assertion type); the
  first step offers no generic token; the inference no longer stops on the label.
- Python utilities: the label is removed from the lists of assertion types. In `assertion_distribution.py` a target
  counts for a type if it contains `<type>(`, so the `THROW EXCEPTION` targets were counted as "unknown"; the
  `fail(...)` targets now count as `fail`.
- D32: `JavaParserUtils.getLastStatementInMethodDeclaration` skips the empty blocks; `processSplitTestClass` uses it for
  `split = statement` too.

## Tests

Written before the implementation.

| Test | Before | After |
|------|--------|-------|
| `TestUtilsDatapointsTest.failIsTheTargetAsItIsWritten` (committed configuration, `Assertions.fail("negative sum")` within an `if`) | fails (`THROW EXCEPTION`) | passes |
| `TestUtilsDatapointsTest.failAndAssertionsWithinATryStatementAreTargetsWithKeepTryCatch` | fails (`THROW EXCEPTION`; after the label removal, the `fail(...)` datapoint was missing: D32) | passes |
| `TestUtilsDatapointsTest.emptyLoopIsATargetWithStatementSplitAndStatementTarget` (D32, `split = statement`) | fails with the old target selection (target `{}`) | passes |
| `JavaParserUtilsTest.getLastAssertionInMethodDeclarationFindsTheLastAssertion`, 2 new cases: an assertion within a try block followed by an empty catch block, and by an empty finally block (D32) | fail | pass |
| `TestUtilsNormalizeTest.assertThrowsIsNotAssignedWhenTheCatchBlockDoesNotUseTheException` (empty catch block; catch block with other statements) | fail (`NumberFormatException e = assertThrows(...)`) | pass |
| `OraclesDatasetTest`: the fixture has one more test case, with a `fail(...)`; the end-to-end test expects 5 datapoints and the `fail(...)` target | fails | passes |
| `OraclesDatasetTest.tokenStepsOfAFailTargetAreTheTokensOfTheCall`: generates the tokens dataset from the oracles dataset of the fixture; the first token step of the `fail(...)` target has target `ASSERT:fail` and no generic token, followed by the steps of the other tokens | fails | passes |

Full suite: 179 tests, 0 failures, 0 errors, 5 skipped (D17, D18, D20, D21, D25).

## Verification on real projects

**Oracles dataset.** The seven projects of the previous steps, fresh copies, committed configuration, compared with
Step 13 datapoint by datapoint:

| Project | Datapoints (Step 13 → 14) | `THROW EXCEPTION` → `fail(...)` | Plain `assertThrows` (target or prefix) |
|---------|---------------------------|---------------------------------|------------------------------------------|
| crawler-commons | 655 → 655 | 0 | 1 |
| java-uuid-generator | 369 → 369 | 42 | 5 |
| ffmpeg-cli-wrapper | 586 → 586 | 0 | 0 |
| plexus-utils | 785 → 785 | 6 | 0 |
| jsoup | 3551 → **3553** | 0 | 4 |
| eec | 1427 → 1427 | 0 | 0 |
| esapi | 528 → 528 | 15 | 0 |

Every changed datapoint falls in the two categories of the table (the prefix unchanged for a `fail(...)` target), except
the 2 datapoints added in jsoup: D32 with the committed configuration. In `SoftPoolTest.testSoftLocalPool` a
try/finally within a lambda block (`Runnable task = () -> { try { ... } finally { ... } };`, not rewritten by the
normalizer) is truncated within the try block, and the empty finally block made `split_0` and `split_1` fail with
"No assertion found" in Step 13.

**Tokens dataset.** The tokens stage, which the A/B run does not cover, was run on java-uuid-generator with the jars of
Step 13 and Step 14:

| | Step 13 | Step 14 |
|-|---------|---------|
| Datapoints of the tokens dataset | 364 | 366 |
| Targets `THROW EXCEPTION` / `fail(...)` | 42, one token step each (`ASSERT:THROW EXCEPTION`) | 42, from 5 to 28 token steps each, the first one `ASSERT:fail` |
| Generic tokens offered at the first step | `THROW EXCEPTION`, for each of the 364 datapoints | none |

The 2 datapoints added are the plain `assertThrows(...)` of `LockedFileTest` and `UUIDTimerTest`: in Step 13 their
targets started with the declaration of the exception (`NullPointerException e = assertThrows(...)`), and the tokens
stage rejects a target that does not start with an assertion ("Assertion do not start with JUnit api").
The tokens stage logs 91 more "Unexpected token" errors: 89 for the `fail(...)` targets, now tokenized, 2 for the new
`assertThrows` datapoints. They are limitations of the tokens stage, not of this step: its candidate tokens do not
include the variables declared within nested blocks (`i3`, the loop variables printed by the `fail` messages) nor the
`length` of an array; the same error is logged 411 times for the other targets in Step 13. No datapoint is lost (the
token steps are generated anyway).

## Known remaining issues

- The inference (`TokensUtils.generateInference`) runs against the model API and is not tested; only its use of the
  label was removed. A model trained on a tokens dataset with the label must be retrained.
- The candidate tokens of the tokens stage do not include the variables declared within nested blocks nor the
  `length` of an array ("Unexpected token"; see above). Not in the plan of the review: to be decided.

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
