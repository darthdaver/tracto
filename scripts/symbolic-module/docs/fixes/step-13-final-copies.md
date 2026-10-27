# Step 13 – Final copies of the variables captured by `assertThrows`

**Date:** 2026-10-27
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `cc11a14f`, which commits Step 12)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (final copies in `flatTryStmt`, normalization
  context)
- `src/main/java/star/tracto/preprocessing/utils/AuxiliaryMethodInliner.java` (no set of existing variables)
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (`getAssignedVariableNames` added,
  `getVarFromNameExpr` removed)
- tests: `TestUtilsNormalizeTest`, `TestUtilsNormalizeBehaviourTest`, `JavaParserUtilsTest`
- docs: `docs/design-review-2026-10-26.md`, `docs/oracles-dataset-config.md`

## Problem

With `to-assert-throws`, the failing call of an expected exception is moved into a lambda expression
(`assertThrows(E.class, () -> call(x))`), and a local variable captured by a lambda must be effectively final. The
normalizer defined a copy, `final T x_final = x;`, for each variable of the failing call found in the *set of existing
variables*: the fields of the test class, the variables declared at the top level of the test case, and the variables
added during the normalization. This caused:

- **D8**: a variable outside the set (a loop variable, a variable declared within a nested block, a parameter of a
  lambda expression or of a catch clause) was captured as it was: if reassigned, the normalized test case does not
  compile (`() -> Integer.parseInt("x" + i)` within `for (int i = 0; i < 2; i++)`);
- a copy for every top-level variable used in the call, even if never reassigned (almost always): lines that never
  appear in real tests (dataset decision 1 of the review);
- an `ERROR` log for each class name used in a failing call (`Variable from name expression Integer not found among
  the existing variables`).

## Decision applied

From the decisions of 2026-10-26: a copy only for the variables that need it (the reassigned ones), named in Java
style, `finalX`.

## Changes applied

- The normalization context records the names of the **reassigned variables** of the test case: the variables
  assigned, incremented or decremented besides their declarations (`JavaParserUtils.getAssignedVariableNames`),
  collected from the test case before the normalization and from the body of each inlined auxiliary method. The names
  of the variables are unique within the test case (Step 12), so a name identifies a variable.
- `flatTryStmt` defines a copy, `final T finalX = x;` (allocated name, so `finalX2` if `finalX` is in use), before the
  `assertThrows`, for each variable of the failing call that is reassigned and is a local variable or a parameter. The
  type is the declared one (`var` for a lambda parameter without type). The other variables and the fields are
  captured as they are (a field does not need to be effectively final).
- The set of existing variables is no longer used by the normalizer: it is removed from the normalization context,
  from `AuxiliaryMethodInliner`, from the flattening of the try statements, and `JavaParserUtils.getVarFromNameExpr`
  is removed with its tests (`JavaParserUtils.collectExistingVariables` stays: `TokensUtils` uses it).

## Tests

Written before the implementation.

| Test | Before | After |
|------|--------|-------|
| Enabled: D8, a loop variable used in the failing call gets `final int finalI = i;` | fails | passes |
| Updated to the decision: the four JUnit variants of `tryCatchFailIsFlattenedIntoAssertThrows`, `catchParameterClashingWithFieldIsRenamed`, `finallyOfFlattenedTryCatchFailIsAddedOnce` (no copies: the variables are not reassigned) | fail | pass |
| Updated, with a reassigned variable to keep their purpose: `classNameInFailingCallIsNotCopiedAsVariable` (`s = s + "d";` → `finalS`, no copy of `Integer`), `variableUsedTwiceInFailingCallGetsASingleFinalCopy` (`x--;` → one `finalX`) | fail | pass |
| New: a reassigned field is captured without a copy | passes (characterization) | passes |
| New: `finalX` already declared by the test case → `finalX2` | fails | passes |
| New: a loop variable of an inlined auxiliary method gets a copy | fails | passes |
| New, `TestUtilsNormalizeBehaviourTest`: a loop with an expected exception, compiled and run before and after the normalization | fails (the normalized class does not compile) | passes |
| Removed, `JavaParserUtilsTest`: the three tests of `getVarFromNameExpr` | | |

Before the implementation 12 tests failed. Mutation checks: without the reassigned variables of the inlined methods,
the test of the inlined loop variable fails; copying every variable, 8 tests fail. Without the explicit check for
fields, no test fails: the declaration of a field resolves to a `FieldDeclaration`, not to a variable declarator, so
it gets no copy anyway; the check stays, as it states the rule.

Full suite: 171 tests, 0 failures, 0 errors, 5 skipped (D17, D18, D20, D21, D25).

## Verification on real projects

The seven projects of the previous steps, fresh copies, committed configuration, compared with Step 12 datapoint by
datapoint.

| Project | Datapoints (Step 12 → 13) | Changed datapoints | Final copies (Step 12 → 13) |
|---------|---------------------------|--------------------|-----------------------------|
| crawler-commons | 655 → 655 | 7 | 4 → 0 |
| java-uuid-generator | 369 → 369 | 8 | 4 → 0 |
| ffmpeg-cli-wrapper | 586 → 586 | 0 | 0 → 0 |
| plexus-utils | 785 → 785 | 16 | 1 → 0 |
| jsoup | 3551 → 3551 | 4 | 6 → 0 |
| eec | 1427 → 1427 | 0 | 0 → 0 |
| esapi | 528 → 528 | 55 | 38 → 1 |

No datapoint was added or removed, and no discarded test case changed. Of the 90 changed datapoints, 87 differ only by
the removed copies (the line `final T x_final = x;` in the prefix, and `x_final` → `x` in the prefix or in the
`assertThrows` target); the other 3 by the name of the only copy left, `secConf_final` → `finalSecConf` in
`DefaultSecurityConfigurationTest.testIV` (esapi), where `secConf` is reassigned before the try statement.

A scan of the normalized test cases (scratchpad `scan/ScanCaptures.java`): in both steps no `assertThrows` lambda
captures a reassigned local variable, so D8 does not occur in these projects (the tests cover it), and no failing call
assigns a variable. The logs have 13 errors fewer: the lookups of class names (`Long`, `HttpURLConnection`,
`CryptoHelper`) and of variables outside the old set of existing variables (`pattern2`, `dag4`, …, none of them
reassigned) used in failing calls.

## Known remaining issues

- A failing call that assigns a variable itself (`parse(x++)`) cannot be moved into a lambda expression; such a call
  is not checked (none in the seven projects of the A/B run, see above).

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
