# Step 9 – Classify try statements and discard the unsupported shapes

**Date:** 2026-10-26
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `4e6f6978`, which commits Steps 2–8)
**Files changed:**
- `src/main/java/star/tracto/data/enums/TryStatementShape.java` (new)
- `src/main/java/star/tracto/data/exceptions/DiscardedTestCaseException.java` (new)
- `src/main/java/star/tracto/preprocessing/utils/TryStatementClassifier.java` (new)
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`
- `src/main/java/star/tracto/data/enums/TryCatchFinallyStrategyType.java`
- `src/main/java/star/tracto/data/records/OraclesDatasetConfig.java`
- `src/main/java/star/tracto/preprocessing/components/TestStats.java`, `.../builders/TestStatsBuilder.java`
- `src/main/java/star/tracto/preprocessing/OraclesDataset.java`
- `src/main/resources/dataset/oracles-dataset_config.json`
- tests: `TryStatementClassifierTest` (new), `OraclesDatasetConfigTest` (new), `TestUtilsNormalizeTest`,
  `TestUtilsSplitTest`, `OraclesDatasetTest`, `ConfigBuilder`
- docs: `docs/oracles-dataset-config.md` (section `try-catch-finally` rewritten), `docs/design-review-2026-10-26.md`

## Problem

Before this step, the normalization checked part of the shape of a try statement (`checkTryStmt`), and
`flatTryStmt`/`generateAssertThrowsFromTryStmt` checked other parts. Three defects followed from the scattered checks:

- **D10 – `fail()` not last in the try block.** `findFailMethodCall` accepted `fail()` anywhere in the try block, and
  the conversion assumed it was last. `try { parse(s); fail("should throw"); assertEquals("never", s); } catch (E e) {..}`
  became `parse(s); E e = assertThrows(E.class, () -> fail("should throw"), "never");`, with no error logged.
- **D11 – two or more catch clauses without `fail()`.** No branch of `flatTryStmt` applied, so the try block was
  silently dropped and the test case produced datapoints with missing statements.
- **D3 – lost error flag.** `thrownedException = parseNormalizeTestStatementsBlock(...)` overwrote the flag, so a
  normalization error followed by a block statement (a loop, an `if`, …) was not reported.

The other unsupported shapes threw an `IllegalStateException`. The statement was then appended unchanged, and the
splitter later dropped the test case with the generic message "Try statement should not appear in a test after the
normalization".

The configuration key `try-catch-finally` had been unused since March (its values belonged to the old splitter).

## Decisions applied

From `docs/oracles-dataset-config.md` (2026-10-23) and the review of 2026-10-26:
- `try-catch-finally` takes two values: `to-assert-throws` (committed) and `keep-try-catch`;
- in both modes, a test case containing a try statement with an unsupported shape is discarded, with the reason logged;
- incremental restructuring: the classification is extracted into its own class.

## Changes applied

### Classification: `TryStatementClassifier` and `TryStatementShape`
Each try statement reached by the normalization is classified once. The checks are applied in order:

| Shape | Condition | Supported |
|-------|-----------|-----------|
| `RESOURCES_WITHOUT_CATCH` / `FINALLY_WITHOUT_CATCH` | no catch clause | yes |
| `MULTI_CATCH` | more than one catch clause | no |
| `UNION_CATCH` | union type in the catch clause | no |
| `FAIL_NOT_LAST` | a `fail()` in the try block (any depth), but not as the last statement, or more than one | no |
| `UNSUPPORTED_FAILING_STATEMENT` | `fail()` is last, but the statement before it is not a method call or an object creation (possibly assigned to a variable, or declared as a single variable) | no |
| `EXPECTED_EXCEPTION` | `fail()` is last and the statement before it is such a call | yes |
| `MUST_NOT_THROW` | a `fail()` in the catch block | no |
| `EMPTY_CATCH` | no `fail()`, empty catch block | no |
| `CATCH_WITHOUT_FAIL` | no `fail()`, non-empty catch block | no |

`UNSUPPORTED_FAILING_STATEMENT` also covers an expression that cannot be the body of the lambda of an `assertThrows`:
`() -> a / b` is not a valid `Executable`, and before this step it was generated anyway.

`getFailingExpression` moved into the classifier, so the classification and the conversion use the same definition
of the failing call. `checkTryStmt`, `findFailMethodCall` and `isFailMethodCall` were removed from `TestUtils`, and
`generateAssertThrowsFromTryStmt` now returns the call directly (its precondition is `EXPECTED_EXCEPTION`).

### Discarding: `DiscardedTestCaseException`
For an unsupported shape, the normalizer throws `DiscardedTestCaseException(reason, description)`. The per-statement
`catch (Exception e)` of `parseNormalizeTestStatementsBlock` rethrows it, so it reaches `normalizeTest`, which:
- does not add the test case to the normalized class (so it produces no datapoints);
- logs `Test case <name> discarded: <description>` (warning);
- writes the test case to `discarded/normalized/<path>STARNormalizedTest.java` (same format as the error report);
- adds its statistics (measured on the original body) with the new field `TestStats.discardReason`.

`OraclesDataset.generate` adds `discardedTestCases` to `statistics-<project>.json`. The statistics of the test cases
were built inline; they are now built by `buildTestStats`, used for both normalized and discarded test cases.

### Strategies: `try-catch-finally`
`TryCatchFinallyStrategyType` now has `TO_ASSERT_THROWS("to-assert-throws")` and `KEEP_TRY_CATCH("keep-try-catch")`; the old
values were removed, so an old configuration fails at load time (`Unknown value: standard`). `OraclesDatasetConfig`
has a compact constructor that rejects a missing key. The committed configuration uses `to-assert-throws`.

`parseNormalizeTestStatementsBlock` now receives the whole configuration instead of the `integrateAuxiliaryMethods`
flag. For a supported try statement:
- `to-assert-throws`: unchanged behaviour (`flatTryStmt`, Steps 6–7);
- `keep-try-catch`: the try statement is cloned with empty blocks (resources and catch parameters kept), and the
  statements of the try, catch and finally blocks are normalized into them (helpers are inlined, nested try statements
  are classified, …).

The splitter does not support try statements yet. Its message is now "Splitting within try statements is not
supported yet.", so with `keep-try-catch` the test cases with a try statement still produce no datapoints until
Step 10.

### D3
Every recursive call now accumulates the flag: `thrownedException |= parseNormalizeTestStatementsBlock(...)`.

## Tests

| Test | Checks |
|------|--------|
| `TryStatementClassifierTest` (23 cases) | Every shape, including the variants: qualified `Assert.fail(..)`, assignment and declaration of the failing call, object creation, resources and finally with an expected exception, `fail()` nested in an `if`, missing failing statement, a loop or an arithmetic expression before `fail()`, a declaration of two variables, `fail()` nested in the catch block, a catch block with only a comment. |
| `TestUtilsNormalizeTest.testCaseWithUnsupportedTryStatementIsDiscarded` (9 cases) | For each unsupported shape (two for `MULTI_CATCH`, with and without `fail()`, the second being D11; `FAIL_NOT_LAST` being D10; one try nested in a loop): the test case is removed from the normalized class, the other test case is kept, `discardReason` is the shape, the discarded report contains it. |
| `...normalizationErrorFollowedByABlockStatementIsReported` | D3: a `switch` (still unsupported by the normalizer) followed by a loop is reported in the error file. |
| `...normalizationErrorInOneTestCaseKeepsTheRestOfTheTestClass` | Updated: it used a union catch to trigger an error, which is now a discard; it uses a `switch` instead. |
| `...tryStatementIsKeptWithItsBlocksNormalizedWithKeepTryCatch` | `keep-try-catch`: a helper called in the try, catch and finally blocks is inlined in each block, and the structure is kept. |
| `...tryWithResourcesIsKeptWithKeepTryCatch` | `keep-try-catch` keeps a try-with-resources. |
| `...testCaseWithUnsupportedTryStatementIsDiscardedWithKeepTryCatch` | Discarding applies to `keep-try-catch` too. |
| `OraclesDatasetConfigTest` (4) | The committed config uses `to-assert-throws`; `keep-try-catch` is accepted; a missing key and an old value are rejected. |
| `OraclesDatasetTest` (updated) | A must-not-throw test case added to the fixture is counted in `discardedTestCases` and listed with `MUST_NOT_THROW` in the test statistics; the 4 datapoints are unchanged. |
| `TestUtilsSplitTest.failAndAssertionsWithinATryStatementAreSplitPointsWithKeepTryCatch` | `@Disabled`, Step 10. |
| `TestUtilsSplitTest.statementContainingAnAnonymousClassIsKeptAsItIs` | `@Disabled`, D26 (found in the A/B run, see below). |

The tests were written after the code. To check they can fail, two mutations were applied and reverted:
- `thrownedException = ...` instead of `|=`: the D3 test fails;
- the discard disabled (`if (false)`): all 10 discard tests fail.

Full suite: 133 tests, 0 failures, 0 errors, 22 skipped (the known-defect and Step 10 tests).

## Verification on real projects

Fresh copies of two repositories from `input/github-repos`, committed config of each version. The baseline was
re-run on `4e6f6978` (Step 8) and matches the numbers after Step 7:

| Project | Step 8 (`4e6f6978`) | Step 9 |
|---------|---------------------|--------|
| crawler-commons | 13 classes, 634 datapoints | 13 classes, 634 datapoints, 2 test cases discarded |
| java-uuid-generator | 8 classes, 349 datapoints | 8 classes, 349 datapoints, 15 test cases discarded |

The two datasets are **byte-identical** to the baseline (same SHA-256 of the datapoints). Every discarded test case was
already dropped by the splitter before this step; it is now discarded earlier, with its reason:

| Project | Discarded |
|---------|-----------|
| crawler-commons | `EMPTY_CATCH` 1 (`SiteMapIndexTest.testNPE`), `UNION_CATCH` 1 (`SiteMapParserTest.testMissingLocSitemapIndexFile`) |
| java-uuid-generator | `MULTI_CATCH` 9 (`EthernetAddressTest` ×8, `UUIDTimerTest.testGetTimestamp`), `CATCH_WITHOUT_FAIL` 5 (`EgressInterfaceFinderTest`), `MUST_NOT_THROW` 1 (`UUIDGeneratorTest`) |

The splitter no longer meets any try statement (2 and 14 "Try statement should not appear" errors before, 0 after).
The test cases it still drops have two other causes:
- **D9** (7 test cases: 2 + 5): the `flat` handling of the generated `E e = assertThrows(...)`, removed in Step 14;
- **D26** (3 test cases: `SimpleRobotRulesParserTest.testOverrideUserAgentMatcher` in crawler-commons,
  `UUIDTimerTest.testGetTimestampReproducible` and `UUIDGeneratorTest.testGenerateTimeBasedEpochUUIDWithFixedClock`):
  a statement containing an **anonymous class with methods** (`new UUIDClock() { public long currentTimeMillis() { ... } }`)
  makes the splitter throw "Unexpected block statement parent node of block in expression different from lambda
  expression". The truncation splitter (Step 10) copies such statements as they are.

No D10 or D11 case occurs in these two projects, so the fix has no visible effect on their datasets.

## Known remaining issues

1. The splitter does not split within try statements (`keep-try-catch`), and drops statements with anonymous classes
   (D26): Step 10.
2. The discarded share on the whole corpus is expected to be large: in the count of Step 7, multi-catch, must-not-throw,
   catch without `fail()`, empty catch and the rare shapes are 30.6% of the 11,825 try statements. The statistics now
   make this measurable per project.
3. Try statements inside lambdas or anonymous classes of a test case are not reached by the normalization, so they are
   neither classified nor flattened.
4. A comment within a declaration is printed in odd places by the parser (seen in crawler-commons:
   `final // String // robotsTxt = // "..."`). It still compiles, but it is noise in the test prefixes; worth a look
   when deciding how comments should appear in the dataset.

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
