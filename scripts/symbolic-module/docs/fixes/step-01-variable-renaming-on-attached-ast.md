# Step 1 – Run variable renaming on the attached AST

**Date:** 2026-10-21
**Branch:** `22-test-processing-refactoring` (committed in `b87e674c` together with the in-progress refactoring)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeTest.java` (new)

## Problem

### Symptom
After the in-progress refactoring, the oracles dataset lost entire test classes. For example, a run on
`java-uuid-generator` produced 4 classes and 49 datapoints, compared with 8 classes and 141 datapoints for the
committed `HEAD`. The log showed a single error per lost class:

```
ERROR OraclesDataset:158 - The node is not inserted in a CompilationUnit
```

### Root cause
In `TestUtils.normalizeTest`, the new variable renaming (`JavaParserUtils.normalizeVariableNames`) was run on the
body of a **clone** of the test method:

```java
MethodDeclaration normalizedTestCase = originalTestCase.clone();
BlockStmt normalizedTestCaseBody = normalizedTestCase.getBody().orElseThrow();
JavaParserUtils.normalizeVariableNames(normalizedTestCaseBody);
// Normalize test case only if the configuration file require the normalization of the test case
normalizedTestCaseBody = new BlockStmt();
normalizedTestCase.setBody(normalizedTestCaseBody);
```

This caused two problems:

1. **It crashed.** A clone is not attached to a `CompilationUnit`. `normalizeVariableNames` resolves each name with the
   symbol solver (`nameExpr.resolve()`), and JavaParser refuses to resolve nodes outside a compilation unit
   (`IllegalStateException: The node is not inserted in a CompilationUnit`). `normalizeTest` only catches
   `IOException`, so the exception aborted the whole test class. The crash occurred whenever a test reused a
   local name in sibling scopes, which is legal and common Java (two `for (int i …)` loops, two `catch (Exception e)`
   blocks, and so on).
2. **Its result was discarded.** Two lines later the clone's body was replaced with a new empty `BlockStmt`, and the
   normalization loop iterates over the statements of the *original* test case. Even without the crash, the renaming
   had no effect.

## Changes applied

### `TestUtils.normalizeTest`
The renaming now runs on the original test-case body, which is still attached to the compilation unit, so the symbol
solver can resolve names. It runs before the statements are collected, so the normalization loop processes the
renamed statements. The discarded call on the clone was removed.

```diff
+                // Give a unique name to each local variable declared within the original test case. The renaming
+                // resolves the symbols, so it must run on the original body, still attached to the compilation unit
+                JavaParserUtils.normalizeVariableNames(originalTestCase.getBody().orElseThrow());
                 // Get the list of statements within the original test case
                 List<Statement> originalTestCaseStatements = originalTestCase.getBody().orElseThrow().getStatements();
                 // Generate a mold of the test case and set the body to an empty block
                 MethodDeclaration normalizedTestCase = originalTestCase.clone();
-                BlockStmt normalizedTestCaseBody = normalizedTestCase.getBody().orElseThrow();
-                JavaParserUtils.normalizeVariableNames(normalizedTestCaseBody);
-                // Normalize test case only if the configuration file require the normalization of the test case
-                normalizedTestCaseBody = new BlockStmt();
+                BlockStmt normalizedTestCaseBody = new BlockStmt();
                 normalizedTestCase.setBody(normalizedTestCaseBody);
```

### Removed dead code
An empty private instance method in `TestUtils` with the same name as the real implementation was deleted. It was
never called and made it unclear which method did the work.

```diff
-    /**
-     *
-     */
-    private void normalizeVariableNames(BlockStmt blockStmt) {
-
-    }
```

## Test added

`TestUtilsNormalizeTest.normalizeTestRenamesLoopVariableReusedInSiblingLoop` is the first test of the module. It:

1. Creates a fixture repository in a JUnit `@TempDir` (the pipeline writes generated files into the repository, so a
   temporary copy keeps the source tree clean).
2. Configures the parser with `JavaParserUtils.setRepoJavaParser(repoRoot, <empty dir>)`. An empty directory is
   passed as the classpath, because an empty string makes the parser scan the current working directory.
3. Normalizes this JUnit 5 test class:

   ```java
   @Test
   public void testTwoLoops() {
       int sum = 0;
       for (int i = 0; i < 2; i++) { sum += i; }
       for (int i = 0; i < 3; i++) { sum += i; }
       assertEquals(4, sum);
   }
   ```

4. Asserts that `normalizeTest` does not throw and that the normalized method body is exactly:

   ```java
   int sum = 0;
   for (int i = 0; i < 2; i++) { sum += i; }
   for (int i1 = 0; i1 < 3; i1++) { sum += i1; }
   assertEquals(4, sum);
   ```

| Run | Result |
|-----|--------|
| Before the fix | **Failed**: `Unexpected exception thrown: java.lang.IllegalStateException: The node is not inserted in a CompilationUnit` |
| After the fix | **Passed** (full suite: 1 test, 0 failures, 0 errors) |

No `pom.xml` change was needed; the existing JUnit 5 and Surefire configuration runs the test.

## Verification on real projects

The pipeline was run on fresh copies of two repositories from `input/github-repos`, using the committed config
(`src/main/resources/dataset/oracles-dataset_config.json`):

| Project | `HEAD` (committed) | Working tree before Step 1 | Working tree after Step 1 |
|---------|--------------------|----------------------------|---------------------------|
| crawler-commons | 13 classes, 574 datapoints | 11 classes, 463 datapoints | **13 classes, 574 datapoints** |
| java-uuid-generator | 8 classes, 141 datapoints | 4 classes, 49 datapoints | **5 classes, 84 datapoints** |

crawler-commons is back to the `HEAD` result. java-uuid-generator still loses three classes because of a different,
pre-existing bug (see below).

## Known remaining issue (next step)

`TestUtils.collectNormalizationErrors` writes the failing test case to an error file, but it starts from the **real**
compilation unit instead of a copy:

```java
CompilationUnit errorCu = cu;
TypeDeclaration errorTestClass = cu.getPrimaryType().get();
if (!Files.exists(errorOutputPath)) {
    errorTestClass.setMembers(new NodeList<>());
}
```

The first normalization error in a class therefore removes every field, setup method, helper method and remaining test
case from the test class being normalized. Later test cases become detached and fail with the same "not inserted in a
CompilationUnit" error. When the class survives, its normalized file is written without its fields and setup methods.
This bug existed in `HEAD` too, but was never reached there, because an inverted `integrateAuxiliaryMethods` check
skipped normalization entirely with the committed config.

## How to run the tests

The module requires JDK 21 (the default `java` on this machine is JDK 11):

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
