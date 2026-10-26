# Step 8 – Test infrastructure, characterization tests and known-defect tests

**Date:** 2026-10-26
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `b87e674c`)
**Production code changed:** none
**Files changed:**
- `src/test/java/star/tracto/preprocessing/utils/ConfigBuilder.java` (new)
- `src/test/java/star/tracto/preprocessing/utils/FixtureRepo.java` (extended)
- `src/test/java/star/tracto/preprocessing/utils/JavaCompilation.java` (new, moved out of the behaviour test)
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeBehaviourTest.java` (uses `JavaCompilation`)
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeTest.java` (11 known-defect tests added)
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsSplitTest.java` (rewritten around a `split` helper; 19 tests added)
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsDatapointsTest.java` (new)
- `src/test/java/star/tracto/preprocessing/OraclesDatasetTest.java` (new)
- `src/test/java/star/tracto/utils/javaParser/JavaParserUtilsTest.java` (new)

## Goal

First of the steps proposed in `docs/design-review-2026-10-26.md`: before changing the splitter (truncation rewrite,
decided on 2026-10-26) and the normalization, pin the behaviour that is already correct, and turn every confirmed
defect into an executable test.

## Test infrastructure

| Helper | Purpose |
|--------|---------|
| `ConfigBuilder` | Builds an `OraclesDatasetConfig` with the defaults of the tests (`FixtureRepo.CONFIG`), changing one key at a time (`split`, `target`, `assertion`, `keep-statements-after-last-assertion`, …). |
| `FixtureRepo.committedConfig()` | Loads `src/main/resources/dataset/oracles-dataset_config.json`, so that tests can run the production configuration (D19: the tests used a different configuration). |
| `FixtureRepo.writeSourceClass` | Writes a focal class in `src/main/java`, needed by the datapoint phase. |
| `FixtureRepo.refreshParser` | Configures the parser again after a phase writes new files, as `OraclesDataset.generate` does. |
| `JavaCompilation` | `assertCompiles` and `runTrace`, moved out of `TestUtilsNormalizeBehaviourTest`, so any test can compile and run generated code. Compilation now uses `-proc:none`. |

"Mocks" are fixture repositories in a JUnit `@TempDir`: the code transforms real JavaParser trees and resolves
symbols with the real solver, so mocking nodes or the solver would test nothing.

**Known limitation.** The fixture configures the parser with an empty classpath, so JDK types are not resolvable in
the tests (for example the ancestors of a class: `Error resolving ancestors of type declaration` in the logs). The
pipeline passes the JDK sources of `lib/javaNN`. Behaviour that depends on resolving JDK types can therefore differ
between the tests and a real run; the A/B runs on real projects remain necessary.

## Characterization tests (pass today)

| Class | Tests | What they pin |
|-------|-------|---------------|
| `JavaParserUtilsTest` | 21 | `normalizeVariableNames` (sibling blocks, sibling catch parameters, names declared once), `replaceVariables` (field with the same name untouched, multiple declarators), `replaceParameters`, `getVarFromNameExpr` (declarator, outside the set, class name, parameter), `renameLocalParam`, `collectExistingVariables` (the hierarchical-level example of its documentation, levels 0–2; class fields), `getLastAssertionInMethodDeclaration` (assertion call, `assert`, `assertThrows` declaration and statement, assertion in a loop, last statement not an assertion). |
| `TestUtilsSplitTest` | 15 | Split points and split test prefixes for: a flat sequence, statements after the last assertion (dropped / kept), `split = statement`, loops (`for`, for-each, `while`, `do`), `if`/`else if`/`else` chains, labeled blocks (existing test), a lambda block passed as argument, `assertThrows` with the standard strategy, `@Disabled` test cases, `num-assertions-match` strict/loose. These are the outputs the truncation rewrite must reproduce. |
| `TestUtilsDatapointsTest` | 7 | Through the three phases on a focal class `Calc`: target and masked prefix, qualifier `Assertions.` removed from the target, `assertion = keep` and `remove`, mask placed inside the loop of the target, `<NO_ASSERTION>` targets with `split = statement`, statement targets, invoked methods of the test prefix, focal and test class of the datapoints. |
| `OraclesDatasetTest` | 1 | End to end with the committed config: statistics (2 test classes, 1 matched, 4 datapoints) and the four targets, including the assertion of an inlined auxiliary method. |

## Known-defect tests (`@Disabled`)

Each confirmed defect has a test asserting the **correct** behaviour, disabled with the defect id as reason. The fix
step enables it. To check that they still reproduce their defect:

```bash
mvn test -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
```

On 2026-10-26 all 20 failed this way, each with an assertion failure on the output (no unrelated exception), and all
the other tests passed.

| Defect | Test | Planned step |
|--------|------|--------------|
| D1 | `TestUtilsSplitTest.statementAfterAnInnerLoopStaysInTheOuterLoop` | 10 (splitter rewrite) |
| D2 | `TestUtilsSplitTest.elseBranchStaysOnItsIfWhenTheThenBranchContainsAnotherIf`, `TestUtilsNormalizeTest.elseBranchStaysOnItsIfWhenTheThenBranchContainsAnotherIf` | 10, 11 |
| D4 | `TestUtilsNormalizeTest.tryCatchFailWithinAuxiliaryMethodIsFlattenedOnceInlined`, `auxiliaryMethodCalledByAnInlinedAuxiliaryMethodIsInlined` | 12 |
| D5 | `TestUtilsNormalizeTest.parameterAssignedByTheAuxiliaryMethodIsBoundToALocalVariable` | 12 |
| D6 | `TestUtilsNormalizeTest.fieldReadByTheAuxiliaryMethodIsNotShadowedByALocalOfTheTestCase` | 12 |
| D7 | `TestUtilsNormalizeTest.auxiliaryMethodLocalClashingWithALoopVariableOfTheTestCaseIsRenamed` | 12 |
| D8 | `TestUtilsNormalizeTest.reassignedVariableUsedInTheFailingCallGetsAFinalCopy` | 13 |
| D9 | `TestUtilsSplitTest.generatedAssertThrowsDeclarationIsASplitPointWithTheCommittedConfiguration` | 9/14 (`assertThrows` key removed) |
| D12 | `TestUtilsNormalizeTest.singleArgumentOfAVarargsParameterIsWrappedInAnArray` | 12 |
| D13 | `TestUtilsNormalizeTest.normalizeTestGivesAUniqueNameToEachLoopVariable` | 12 |
| D17 | `TestUtilsNormalizeTest.junitVersionIsTakenFromTheTestAnnotationImport` | 15 |
| D18 | `TestUtilsNormalizeTest.statisticsCountTheAssertStatementsOfTheWholeTestCase` | 15 |
| D20 | `OraclesDatasetTest.runningTheGenerationAgainGivesTheSameResult` | 15 |
| D21 | `OraclesDatasetTest.focalClassWhoseNameContainsTestIsMatched` | 15 |
| D23 | `JavaParserUtilsTest.getLastAssertionInMethodDeclarationReturnsAssertAllRatherThanTheAssertionsInItsLambdas`, `TestUtilsDatapointsTest.assertAllIsTheTargetAsAWhole` | 10 |
| D24 | `TestUtilsSplitTest.assertionWithinASynchronizedStatementIsASplitPoint` | 10 |
| D25 | `TestUtilsSplitTest.disabledTestCaseWithFullyQualifiedAnnotationIsKeptUnchanged` | 15 |

The expected results follow the decisions of 2026-10-26: generated variables only when needed and with Java-style
names (`int v = x;` for a parameter binding, `finalI` for a final copy, `i2`/`i3` for clashes, `this.base` for a
shadowed field). The tests that pin the current naming (`i1`, `a1`, `e1`, `x_final`, `v_arg`, `count_0`) will be
updated in the step that introduces the name allocator.

D3, D10 and D11 have no test yet: their expected result depends on how a discarded test case is reported, which is
designed in Step 9. D14, D15, D16 and D22 were found by reading the code and have no simple trigger yet.

## New defects found while writing the tests

| ID | Where | Defect | How it was confirmed |
|----|-------|--------|----------------------|
| D20 | `JavaFileCollector.cleanRepository` | The generated `*STARSplitTestPrefix.java` files are not deleted. On the next run they are collected as test classes (`testFilePaths` 2 → 3, `javaFilePaths` 3 → 4); the output directories it deletes do not match the ones written (`test-split/<project>/regular`). | `OraclesDatasetTest` (run twice) |
| D21 | `OraclesDataset.testClassPathToSourceClassNormPath` | The focal class name is obtained by removing **every** `test` from the lower-case test file name: `LatestTest` → `la`, so classes whose name contains `test` (`Latest`, `Contest`, `TestUtils`, …) are never matched and produce no datapoints. | `OraclesDatasetTest` |
| D22 | `JavaParserUtils.checkModifiersRules` | Always returns `true` (the final `return true` is reached also when no rule is satisfied), so the modifier rules used by `TokensUtils` to select fields are never applied. | reading |
| D23 | `JavaParserUtils.getLastAssertionInMethodDeclaration` | For `assertAll(() -> assertEquals(..), () -> assertTrue(..))` the last assertion found is the `assertTrue` inside the last lambda: the datapoint target is that inner assertion instead of the `assertAll`. | `JavaParserUtilsTest` |
| D24 | `parseSplitTestStatementsBlock`, `synchronized` branch | The body is parsed with the block type `METHOD_BODY`, so its statements are added **after** the `synchronized` statement, which stays empty. | `TestUtilsSplitTest` |
| D25 | `splitTest`, `processSplitTestClass`, `distributeMethods` | Annotations are compared by simple name: `@org.junit.jupiter.api.Disabled` or `@org.junit.Test` are not recognized (a test method annotated `@org.junit.Test` is treated as an auxiliary method), although `JavaFileCollector` looks for them explicitly. | `TestUtilsSplitTest` |

## Results

```
Tests run: 91, Failures: 0, Errors: 0, Skipped: 20
```

Line coverage (JaCoCo): `TestUtils` 41% → 62%, `OraclesDataset` 0% → 86%, `JavaParserUtils` 21% → 30%.

No production code changed, so no A/B run was needed.

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
