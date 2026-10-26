# Step 4 – Handle the trailing return of auxiliary methods on the copy

**Date:** 2026-10-22
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `b87e674c`)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeTest.java` (six tests added)

## Problem

When an auxiliary method (helper) is inlined into a test case, its `return` statement must be removed: otherwise it
would terminate the test case. `integrateAuxiliaryMethodIntoBlockStmt` did this **before** making the copy of the
helper introduced in Step 3, directly on the helper declared in the test class, and with several logic errors:

1. **The real helper was modified.** A trailing `return expr;` was replaced by `expr;` in the helper of the test
   class, so the normalized file (and the contextual information built from it) contained a modified helper.
2. **Helpers ending with `return;` were never inlined.** The empty return was removed first, then the check "return
   statement not at the end" compared the last statement with the removed return, failed, and rejected the helper
   (7 log lines `Empty return statement in the auxiliary method. Removed.` in java-uuid-generator).
3. **Invalid Java was generated.** A trailing `return v > 0;` became the statement `x > 0;`, which is not valid Java.
   When the normalized file is parsed again, the parser's error recovery discards the whole body of the test case
   (the test body came back as `{ }`). `return seen.add(v) && v > 0;` produced a file that does not parse at all.
4. **Returns of lambdas were counted as returns of the helper.** The returns were collected with `ReturnStmtVisitor`,
   which also visits lambda expressions and nested classes, so a helper such as
   `vs.forEach(v -> { if (v < 0) { return; } ... })` was rejected because of the lambda's `return`.

Helpers with an **early** return (a `return` that is not the last statement, for example inside an `if`) remain
rejected: inlining them as they are would interrupt the test case. They are addressed in the next step.

## Changes applied

### `TestUtils.integrateAuxiliaryMethodIntoBlockStmt`
The return handling now runs **after** the copy of the helper is made, only on the copy. The helper's own returns are
collected with `isReturnOfMethod`, the "last statement" check compares against the copy's body, and the trailing
return is replaced by the evaluation of its value, when needed:

```java
// Collect the return statements of the auxiliary method (the return statements within lambda expressions
// or nested classes defined in the auxiliary method belong to them)
List<ReturnStmt> returnStmts = clonedAuxBody.findAll(ReturnStmt.class).stream()
        .filter(returnStmt -> isReturnOfMethod(returnStmt, auxiliaryMethodCopy))
        .toList();
...
if (returnStmts.size() == 1) {
    ReturnStmt returnStmt = returnStmts.get(0);
    // A return statement that is not the last statement of the auxiliary method would interrupt the test
    // case where the method is integrated
    if (clonedAuxBody.getStatements().getLast().orElse(null) != returnStmt) {
        logger.error("Return statement not at the end of the auxiliary method.");
        return Optional.empty();
    }
    // The value returned is discarded (only the auxiliary methods invoked as statements are integrated),
    // so the return statement is replaced with the evaluation of the value, if needed
    Optional<Statement> returnedValueEvaluation = returnStmt.getExpression()
            .flatMap(returnExpr -> evaluateDiscardedValue(returnExpr, auxiliaryMethodCopy));
    if (returnedValueEvaluation.isPresent()) {
        returnStmt.replace(returnedValueEvaluation.get());
    } else {
        clonedAuxBody.remove(returnStmt);
    }
}
```

The old block that operated on `auxBody` (the helper of the test class) was removed, together with the now unused
`ReturnStmtVisitor` import.

### New helper methods

| Method | Purpose |
|--------|---------|
| `isReturnOfMethod(ReturnStmt, MethodDeclaration)` | Walks up from the return to the nearest lambda, callable (method or constructor, including those of nested/anonymous classes) or initializer, and checks that it is the given method. |
| `evaluateDiscardedValue(Expression, MethodDeclaration)` | Decides how to keep the value of a trailing `return`, given that the caller discards it (see table below). |
| `isStatementExpression(Expression)` | True for method calls, object creations, assignments, and increments/decrements: the expressions that Java accepts as statements. |

How the trailing return is replaced:

| Trailing return | Replaced by | Example |
|-----------------|-------------|---------|
| `return;` | nothing | — |
| a statement expression | the expression as a statement | `return seen.add(v);` → `seen.add(x);` |
| an expression containing a call, creation, assignment, increment or decrement | a local variable `<helperName>Result` of the helper's return type | `return seen.add(v) && v > 0;` → `boolean recordPositiveResult = seen.add(x) && x > 0;` |
| an expression without side effects | nothing | `return v > 0;` → — |

The result variable is created inside the helper copy, so the existing renaming of local variables also renames it if
its name clashes with a variable of the test case.

## Tests added

All tests use a new test helper, `normalizeHelperTest(members)`, which wraps the given members in a JUnit 5 test class
`com.acme.HelperTest` and normalizes it.

| Test | Helper | Expected normalized test body |
|------|--------|-------------------------------|
| `auxiliaryMethodEndingWithEmptyReturnIsInlined` | `checkPositive(int v) { assertTrue(v > 0); return; }` | `int x = 1; assertTrue(x > 0);` (and the helper is unchanged in the output) |
| `auxiliaryMethodReturningAStatementExpressionKeepsItAsStatement` | `record(int v) { assertTrue(v > 0); return seen.add(v); }` | `int x = 1; assertTrue(x > 0); seen.add(x); assertEquals(1, seen.size());` (and the helper is unchanged) |
| `auxiliaryMethodReturningAValueWithoutSideEffectsDropsIt` | `isPositive(int v) { assertTrue(v > 0); return v > 0; }` | `int x = 1; assertTrue(x > 0);` |
| `auxiliaryMethodReturningAValueWithSideEffectsKeepsItInAResultVariable` | `recordPositive(int v) { assertTrue(v > 0); return seen.add(v) && v > 0; }` | `int x = 1; assertTrue(x > 0); boolean recordPositiveResult = seen.add(x) && x > 0;` |
| `auxiliaryMethodReturningAnIncrementKeepsItAsStatement` | `count(int v) { assertTrue(v > 0); return counter++; }` | `int x = 1; assertTrue(x > 0); counter++; assertEquals(1, counter);` |
| `returnWithinLambdaOfAuxiliaryMethodDoesNotPreventInlining` | `checkAll(List<Integer> vs) { vs.forEach(v -> { if (v < 0) { return; } assertTrue(v > 0); }); }` | `List<Integer> xs = List.of(1, 2); xs.forEach(v -> { if (v < 0) { return; } assertTrue(v > 0); });` |

Results before the fix:

| Test | Failure before the fix |
|------|------------------------|
| empty return | helper not inlined: `{ int x = 1; checkPositive(x); }` |
| statement expression | inlined, but the helper in the output had lost its return: `private boolean record(int v) { assertTrue(v > 0); seen.add(v); }` |
| value without side effects | the test body came back empty, `{ }` (the invalid statement `x > 0;` broke the parsing of the body) |
| value with side effects | the normalized file did not parse (`Parse error. Found "?"`) |
| return within lambda | helper not inlined: `{ List<Integer> xs = List.of(1, 2); checkAll(xs); }` |

The increment test was written after the code. To check it can fail, the increment branch of `isStatementExpression`
was removed temporarily: the test failed, and passed again once the branch was restored. Without that branch,
`return counter++;` would be treated as having no side effects and the increment would be lost.

Full suite after the fix: 10 tests, 0 failures, 0 errors.

## Verification on real projects

Fresh copies of two repositories from `input/github-repos`, committed config, baseline = March commit `7a583a0`:

| Project | Baseline `7a583a0` | After Step 3 | After Step 4 |
|---------|--------------------|--------------|--------------|
| crawler-commons | 13 classes, 574 datapoints | 13 classes, 634 datapoints | 13 classes, 634 datapoints |
| java-uuid-generator | 8 classes, 141 datapoints | 8 classes, 329 datapoints | 8 classes, 329 datapoints |

The datapoint counts are unchanged: in these two projects the helpers that were blocked have **early** returns, which
are still rejected (7 `Return statement not at the end of the auxiliary method.` log lines in java-uuid-generator).
The `Empty return statement ... Removed.` log lines are gone.

The step improves data quality: every helper containing a `return` was compared with its counterpart in the
normalized output. In java-uuid-generator all 16 are unchanged. In crawler-commons 13 are unchanged and 2 differ only
by the intended renaming of the test class in self-references (`MimeTypeDetectorTest.class` →
`MimeTypeDetectorTestSTARNormalizedTest.class`), exactly as in the baseline output.

## Known remaining issues

1. **Early returns (next step).** Helpers whose `return` is not the last statement are still not inlined. Example from
   java-uuid-generator (`UUIDTimerTest`, `EthernetAddressTest`):

   ```java
   private void assertArrayNotEqual(byte[] array1, byte[] array2, int length) {
       for (int i = 0; i < length; i++) {
           if (array1[i] != array2[i]) {
               return;
           }
       }
       fail("Array1 and Array2 matched");
   }
   ```
2. **Helpers inlined into a value-returning lambda.** A helper call can also be inlined when it is the single
   expression of a lambda (for example `() -> helper(x)`). If the lambda's functional interface returns a value, the
   inlined block no longer returns it. This behaviour predates this step and is unchanged.
3. Still open from Step 3: name clashes with loop variables of the test case, and partial inlining on failure.

## How to run the tests

The module requires JDK 21 (the default `java` on this machine is JDK 11):

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
