# Step 2 – The normalization error report must not modify the test class

**Date:** 2026-10-22
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `b87e674c`)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeTest.java` (one test added)

## Problem

### Symptom
After Step 1, `java-uuid-generator` still lost three whole test classes (`EthernetAddressTest`, `UUIDGeneratorTest`,
`UUIDTimerTest`). Each lost class showed one class-level error in the log:

```
ERROR OraclesDataset:158 - The node is not inserted in a CompilationUnit
```

A debug run showed that, after the first test case of the class was normalized, every following test case was no
longer attached to the compilation unit.

### Root cause
When a test case has a normalization error, `normalizeTest` calls `collectNormalizationErrors` to save that test case
to an error file. The method was meant to start from a copy of the test class containing only the failing test, but
it used the **real** compilation unit being normalized:

```java
CompilationUnit errorCu = cu;
TypeDeclaration errorTestClass = cu.getPrimaryType().get();
if (!Files.exists(errorOutputPath)) {
    errorTestClass.setMembers(new NodeList<>());
}
...
errorTestClass.addMember(originalTestCase);
```

`setMembers(new NodeList<>())` removed every field, setup/teardown method, auxiliary method and remaining test case
from the test class under normalization. As a result:

1. **Classes were lost.** The next test case was detached from the compilation unit, so its variable renaming
   (`JavaParserUtils.normalizeVariableNames`, see Step 1) threw, and the whole class was dropped.
2. **Data degraded silently.** When no later test case needed symbol resolution, the class survived, but its
   normalized file was written without its fields, setup methods and auxiliary methods. The split test classes and
   datapoints generated from it lacked that contextual information, and no error was logged.

`splitTest` already does the same job correctly, starting from `cuTestClass.clone()`. The bug also existed in the
March baseline (`7a583a0`), but was never reached there, because an inverted `integrateAuxiliaryMethods` check skipped
normalization entirely with the committed config.

## Changes applied

### `TestUtils.collectNormalizationErrors`
The error report now starts from a copy of the compilation unit and adds a copy of the failing test case, so the test
class under normalization is never modified.

```diff
-        // Define the error test class compilation unit
-        CompilationUnit errorCu = cu;
+        // Define the error test class compilation unit as a copy, so that the test class under normalization is not
+        // modified
+        CompilationUnit errorCu = cu.clone();
         // Define the error test class
-        TypeDeclaration errorTestClass = cu.getPrimaryType().get();
+        TypeDeclaration errorTestClass = errorCu.getPrimaryType().get();
```

```diff
-            // Add the original test case to the error test class
-            errorTestClass.addMember(originalTestCase);
+            // Add a copy of the original test case to the error test class (adding the original node would move it
+            // out of the test class under normalization)
+            errorTestClass.addMember(originalTestCase.clone());
```

The second change is a safeguard: in the current flow nothing reads the original method node after the report is
written, so no test can observe the difference between adding the node and adding a copy.

## Test added

`TestUtilsNormalizeTest.normalizationErrorInOneTestCaseKeepsTheRestOfTheTestClass` normalizes this JUnit 5 class:

```java
public class ErrorsTest {
    private int base;

    @BeforeEach
    public void setUp() { base = 1; }

    @Test
    public void testMultiCatch() {
        try {
            Integer.parseInt("x");
            fail("should throw");
        } catch (IllegalArgumentException | IllegalStateException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void testTwoLoops() {
        int sum = base;
        for (int i = 0; i < 2; i++) { sum += i; }
        for (int i = 0; i < 3; i++) { sum += i; }
        assertEquals(5, sum);
    }
}
```

- `testMultiCatch` triggers a normalization error, because `checkTryStmt` rejects multi-catch clauses. This is a
  deliberate limitation, so it is a stable trigger.
- `testTwoLoops` reuses a variable name, so its renaming needs symbol resolution and fails if the class was wiped.

The test asserts that:
1. `normalizeTest` does not throw;
2. the normalized file still contains the field `base` and the methods `setUp`, `testMultiCatch`, `testTwoLoops`;
3. the error file `error/normalized/repo/src/test/java/com/acme/ErrorsTestSTARNormalizedTest.java` contains only
   `testMultiCatch`.

| Run | Result |
|-----|--------|
| Before the fix | **Failed**: `Unexpected exception thrown: java.lang.IllegalStateException: The node is not inserted in a CompilationUnit` |
| After the fix | **Passed** (full suite: 2 tests, 0 failures, 0 errors) |

Reverting either `clone()` on the compilation unit makes the test fail again.

## Verification on real projects

The pipeline was run on fresh copies of two repositories from `input/github-repos`, using the committed config. The
baseline is the March commit `7a583a0` (the last commit before the variable-renaming refactoring):

| Project | Baseline `7a583a0` | After Step 1 | After Step 2 |
|---------|--------------------|--------------|--------------|
| crawler-commons | 13 classes, 574 datapoints | 13 classes, 574 datapoints | **13 classes, 574 datapoints** |
| java-uuid-generator | 8 classes, 141 datapoints | 5 classes, 84 datapoints | **8 classes, 150 datapoints** |

No class is lost at class level any more (0 `OraclesDataset:158` errors in both runs). java-uuid-generator now
produces more datapoints than the baseline, because normalization actually runs.

## Known remaining issue (next step)

Auxiliary-method inlining fails for every helper method that has a parameter, logging
`TestUtils:1607 - The node is not inserted in a CompilationUnit` (24 times in crawler-commons, 187 times in
java-uuid-generator). The parameters and local variables of the helper are renamed with symbol resolution on
`clonedAuxBody`, a detached clone of the helper body. The call is then kept unchanged, so the assertions inside the
helper never become datapoints.

## How to run the tests

The module requires JDK 21 (the default `java` on this machine is JDK 11):

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
