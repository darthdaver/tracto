# Step 3 – Inline auxiliary methods using an attached copy

**Date:** 2026-10-22
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `b87e674c`)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeTest.java` (two tests added)

## Problem

### Symptom
Inlining an auxiliary method (a helper method of the test class called by a test case) failed for every helper that
has a parameter, or a local variable whose name clashes with a variable of the test case. The log showed:

```
ERROR TestUtils:1607 - The node is not inserted in a CompilationUnit
```

24 times in crawler-commons and 187 times in java-uuid-generator. Each failure kept the helper call unchanged, so the
assertions inside the helper never became datapoints. Inlining these helpers is the main reason normalization exists.

### Root causes
`integrateAuxiliaryMethodIntoBlockStmt` had three related problems.

1. **Renaming ran on a detached clone.** The parameters and local variables of the helper are renamed with
   `JavaParserUtils.replaceParameters` and `replaceVariables`, which resolve symbols. They ran on
   `clonedAuxBody = auxBody.clone()`, a clone of the helper body only, which is not attached to a compilation unit.
   This is the same mistake as Step 1.
2. **The rename maps referred to the original helper, not to the copy.** The parameter map was keyed by the
   parameters of the original helper (`auxiliaryMethodDeclaration.getParameters()`), and the local-variable map was
   built from the original body (`collectVariableMapping(auxBody, ...)`), while the usages were searched in the copy.
   Even with working resolution, the renames would have been applied to the original helper, and the inlined copy
   would have been only partly renamed.
3. **The local-variable map used structural keys.** `collectVariableMapping` returned a `HashMap<VariableDeclarator, String>`.
   JavaParser nodes implement a structural `equals`/`hashCode`, so:
   - distinct declarations with the same content (for example two `int i = 0` loops) collapse into one key;
   - a declaration modified while used as a key can no longer be found. This happened as soon as problem 1 was fixed:
     `replaceParameters` runs first and rewrites `v` into `v_arg` inside `int r = v + 1`, which changes the hash of
     that declarator, so `replaceVariables` renamed the declaration but not its usages.

   `replaceVariables` already documents its lookup as an "identity check", which the `HashMap` did not provide.

## Changes applied

### `TestUtils.integrateAuxiliaryMethodIntoBlockStmt`
The helper is now copied **within a copy of the compilation unit**, so the copy is resolvable and changes to it do not
affect the helper declared in the test class. The body, the parameters and the local-variable mapping are all taken
from the copy.

```diff
-            // Clone and modify the auxiliary method body to replace variables
-            BlockStmt clonedAuxBody = auxBody.clone();
+            // Copy the auxiliary method within a copy of the compilation unit. The copy is still attached to a
+            // compilation unit, so its parameters and local variables can be renamed resolving their symbols, without
+            // modifying the auxiliary method declared in the test class
+            MethodDeclaration auxiliaryMethodCopy = copyMethodWithinCompilationUnit(testClass, methodSignature);
+            BlockStmt clonedAuxBody = auxiliaryMethodCopy.getBody().get();
```

```diff
-            List<Parameter> parameters = auxiliaryMethodDeclaration.getParameters();
+            List<Parameter> parameters = auxiliaryMethodCopy.getParameters();
```

```diff
-                varMapping = collectVariableMapping(auxBody, existingVariables);
+                varMapping = collectVariableMapping(clonedAuxBody, existingVariables);
```

### New helper `TestUtils.copyMethodWithinCompilationUnit`
Clones the compilation unit of the test class and returns the method with the given signature from the cloned test
class (matched by fully qualified class name and signature). It throws `IllegalStateException` if the method is not
found; the caller already catches exceptions and falls back to keeping the call unchanged.

```java
private static MethodDeclaration copyMethodWithinCompilationUnit(TypeDeclaration<?> testClass, String methodSignature) {
    CompilationUnit cuCopy = testClass.findCompilationUnit()
            .orElseThrow(() -> new IllegalStateException("The test class " + testClass.getNameAsString() + " is not inserted in a compilation unit."))
            .clone();
    String testClassName = testClass.getFullyQualifiedName().orElse(testClass.getNameAsString());
    return cuCopy.findAll(TypeDeclaration.class).stream()
            .filter(t -> t.getFullyQualifiedName().orElse(t.getNameAsString()).equals(testClassName))
            .flatMap(t -> ((TypeDeclaration<?>) t).getMethods().stream())
            .filter(m -> m.getSignature().asString().equals(methodSignature))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Method " + methodSignature + " not found in the copy of the test class " + testClassName));
}
```

### `TestUtils.collectVariableMapping(BlockStmt, ...)`
The returned map now compares declarations by reference.

```diff
-        HashMap<VariableDeclarator, String> varMapping = new HashMap<>();
+        // The keys are compared by reference: JavaParser nodes implement a structural equals/hashCode, so distinct
+        // declarations with the same content would collide, and a declaration modified while used as a key (for
+        // example when a parameter used in its initializer is renamed) could not be found anymore
+        Map<VariableDeclarator, String> varMapping = new IdentityHashMap<>();
```

The same method is used by `flatTryStmt` for the try/catch/finally blocks, which now get reference-based keys too, as
their comments intended. The other structurally keyed maps (`normalizeVariableNames`, the parameter map in
`integrateAuxiliaryMethodIntoBlockStmt`, `flatTryStmt`'s resources map) are left for a dedicated step, because no test
exercises them yet.

## Tests added

### `auxiliaryMethodWithParameterIsInlinedWithTheArgument`
```java
@Test
public void testUsesHelper() {
    int x = 1;
    check(x);
}

private void check(int v) {
    assertEquals(1, v);
}
```
Asserts that the normalized `testUsesHelper` body is `{ int x = 1; assertEquals(1, x); }` and that `check` is
unchanged in the normalized file.

### `auxiliaryMethodLocalClashingWithTestVariableIsRenamedOnlyInTheInlinedCopy`
```java
@Test
public void testLocalClash() {
    int r = 5;
    checkNext(1);
    assertEquals(5, r);
}

private void checkNext(int v) {
    int r = v + 1;
    assertEquals(2, r);
}
```
Asserts that the normalized body is:
```java
int r = 5;
int v_arg = 1;
int r_0 = v_arg + 1;
assertEquals(2, r_0);
assertEquals(5, r);
```
and that `checkNext` is unchanged in the normalized file.

### Results

| Run | `...WithTheArgument` | `...OnlyInTheInlinedCopy` |
|-----|----------------------|---------------------------|
| Before the fix | **Failed**: body was `{ int x = 1; check(x); }` | **Failed**: body was `{ int r = 5; int v_arg = 1; checkNext(1); assertEquals(5, r); }` |
| With the attached copy only | Passed | **Failed**: body had `int r_0 = v_arg + 1; assertEquals(2, r);` (problem 3) |
| After the full fix | Passed | Passed |

Full suite: 4 tests, 0 failures, 0 errors.

Mutation check: taking the parameters from the original helper instead of the copy makes both tests fail (verified).
Building the local-variable map from the original body, or reverting to a `HashMap`, makes the second test fail.

## Verification on real projects

Fresh copies of two repositories from `input/github-repos`, committed config, baseline = March commit `7a583a0`:

| Project | Baseline `7a583a0` | After Step 2 | After Step 3 |
|---------|--------------------|--------------|--------------|
| crawler-commons | 13 classes, 574 datapoints | 13 classes, 574 datapoints | **13 classes, 634 datapoints** |
| java-uuid-generator | 8 classes, 141 datapoints | 8 classes, 150 datapoints | **8 classes, 329 datapoints** |

There are no more "not inserted in a CompilationUnit" errors. Most of the increase comes from `UUIDGeneratorTest`
(15 → 164 datapoints), whose tests delegate their assertions to helpers. A manual check of the normalized output shows
correctly inlined code, for example `checkUUIDArrayForCorrectVariantAndVersion(uuid_array, UUIDType.RANDOM_BASED)`
becomes the helper body with `uuidArray` replaced by `uuid_array`, a support variable
`UUIDType expectedType_arg = UUIDType.RANDOM_BASED;`, and the helper loop variable renamed to `i_0`.

## Known remaining issues

1. **Return statements of helpers (next step).** `integrateAuxiliaryMethodIntoBlockStmt` still handles the helper's
   `return` on the **original** helper, before the copy is made:
   - `return expr;` at the end is replaced by `expr;` in the helper of the test class, so the normalized file contains
     a modified helper;
   - a trailing `return;` is removed, and then the check "return statement not at the end" fails, so the helper is not
     inlined (7 + 7 log lines in java-uuid-generator).
2. **Name clashes with loop variables (expected from reading, not yet tested).** Since the refactoring changed
   `checkNodeScope`, variables declared in `for`/`foreach`/`try` headers of the test are no longer in the set of
   existing variables. A helper that declares `int i` inlined inside a loop that uses `i` would produce code that does
   not compile.
3. **Partial inlining on failure.** If inlining fails after support variables were added (for example `int v_arg = 1;`),
   they remain in the test next to the kept call.

## How to run the tests

The module requires JDK 21 (the default `java` on this machine is JDK 11):

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
