# Step 6 – Flatten the standard try/catch/fail pattern into `assertThrows`

**Date:** 2026-10-22
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `b87e674c`)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java`
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeTest.java` (one parameterized test with four
  variants and three tests added)

## Problem

During normalization, `flatTryStmt` converts the classic expected-exception pattern into an `assertThrows`:

```java
// original
Calc calc = new Calc();
int x = -1;
try {
    calc.sqrt(x);
    fail("should throw");
} catch (IllegalArgumentException e) {
    assertEquals("negative", e.getMessage());
}

// normalized (JUnit 5)
Calc calc = new Calc();
int x = -1;
final Calc calc_final = calc;
final int x_final = x;
IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> calc_final.sqrt(x_final), "should throw");
assertEquals("negative", e.getMessage());
```

The `_final` copies are part of the design introduced with the refactoring: a lambda can only capture effectively
final variables, so every variable used in the failing call is copied into a final variable, which keeps the code
compilable even if the original variable is reassigned elsewhere in the test.

The conversion failed in practically every case, and the original `try` statement was kept. The splitter then drops
every test case that still contains a `try` ("Try statement should not appear in a test after the normalization"), so
these test cases produced no datapoints. Five bugs were on this path, each reached only after the previous one:

1. **Wrong argument for the exception class.** The class was read at `args[size - 2]` of the generated `assertThrows`.
   With JUnit 5 and a `fail` message the arguments are `(Class, lambda, message)`, so this was the lambda
   (`() -> calc.sqrt(x) is not ClassExpr, it is LambdaExpr`, 7 times in the two projects). The March baseline had the
   opposite bug for JUnit 4 without a message.
2. **Symbols resolved in a detached copy.** The variables to copy were resolved inside the generated lambda, which is
   not attached to a compilation unit (`The node is not inserted in a CompilationUnit`). In addition,
   `generateAssertThrowsFromTryStmt` *moved* the failing call into the lambda instead of copying it, so the original
   call was detached as well.
3. **Inverted check on the catch parameter.** `renameLocalParam` returns an empty optional when the name of the catch
   parameter does **not** clash, and the code threw an exception exactly in that case.
4. **Class names treated as variables.** `getVarFromNameExpr` did not handle resolution failures, so a class name in the
   failing call (`Integer` in `Integer.parseInt(s)`, `UUID` in `UUID.fromString(s)`) made the whole conversion fail
   (`Unsolved symbol : org.junit.jupiter.api.Assertions`).
5. **One copy per occurrence.** A variable used twice in the failing call got two copies (`x_final`, `x_final1`), and the
   copies were inserted in reverse order.

## Changes applied

### `TestUtils.flatTryStmt`
- The exception class and the lambda are found in the `assertThrows` arguments **by type** (`isClassExpr`,
  `isLambdaExpr`), so the JUnit version and the presence of a message no longer matter.
- The variables used in the failing call are resolved on the **original** failing call, which stays attached in the
  try block, and the corresponding name expressions are renamed in the **copy** inside the lambda. The two expressions
  have the same structure, so `findAll(NameExpr.class)` returns their name expressions in the same order.
- A map from each variable to its final copy (`IdentityHashMap<VariableDeclarator, SimpleName>`) reuses the copy when a
  variable occurs more than once.
- The final copies are inserted in the order the variables appear (running insertion index).
- The catch parameter keeps its name when it does not clash, and is renamed (together with its usages in the catch
  block) only when it does:

```java
String newExceptionParamName = catchExceptionParamName.orElse(catchExceptionParam.getNameAsString());
if (catchExceptionParamName.isPresent()) {
    Map<Parameter,String>  paramMapping = new IdentityHashMap<>();
    paramMapping.put(catchExceptionParam, newExceptionParamName);
    JavaParserUtils.replaceParameters(catchBlockStmt, paramMapping);
}
```

### `TestUtils.generateAssertThrowsFromTryStmt`
The lambda now contains a **copy** of the failing call (`lastMethodCall.clone()`), so the original call stays in the try
block, attached to the compilation unit.

### New helper `TestUtils.getFailingExpression(TryStmt)`
Returns the expression of the penultimate statement of the try block (the one expected to throw), unwrapping the value
of an assignment or of a variable declaration. It is used both to build the lambda and to resolve the variables, so the
two always refer to the same expression.

### `JavaParserUtils.getVarFromNameExpr`
Returns an empty optional when the name expression cannot be resolved (`UnsolvedSymbolException`,
`UnsupportedOperationException`), as documented ("an empty optional, otherwise"), instead of throwing:

```java
Optional<Node> declaration;
try {
    declaration = nameExpr.resolve().toAst();
} catch (UnsolvedSymbolException | UnsupportedOperationException e) {
    // The name expression does not refer to a resolvable variable (for example it is the name of a class,
    // like `Integer` in `Integer.parseInt(s)`)
    return Optional.empty();
}
```

## Tests added

### `tryCatchFailIsFlattenedIntoAssertThrows` (parameterized, 4 variants)
The test case of the example above, in JUnit 4 and JUnit 5, with `fail("should throw")` and with `fail()`. The expected
`assertThrows` for each variant:

| Variant | Expected |
|---------|----------|
| JUnit 5 with message | `assertThrows(IllegalArgumentException.class, () -> calc_final.sqrt(x_final), "should throw")` |
| JUnit 5 without message | `assertThrows(IllegalArgumentException.class, () -> calc_final.sqrt(x_final))` |
| JUnit 4 with message | `assertThrows("should throw", IllegalArgumentException.class, () -> calc_final.sqrt(x_final))` |
| JUnit 4 without message | `assertThrows(IllegalArgumentException.class, () -> calc_final.sqrt(x_final))` |

Before the fix all four kept the original `try` statement; the logged causes were bug 1 for "JUnit 5 with message" and
bug 2 for the other three.

### Additional tests
| Test | Checks | Before the fix |
|------|--------|----------------|
| `classNameInFailingCallIsNotCopiedAsVariable` | `Integer.parseInt(s)` becomes `() -> Integer.parseInt(s_final)` with only `final String s_final = s;` | **Failed**: `try` kept (`Unsolved symbol : org.junit.jupiter.api.Assertions`) |
| `variableUsedTwiceInFailingCallGetsASingleFinalCopy` | `calc.add(x, x)` becomes `calc_final.add(x_final, x_final)` with one copy of `x` | **Failed**: `final int x_final1 = x;` and `calc_final.add(x_final, x_final1)` |
| `catchParameterClashingWithFieldIsRenamed` | A catch parameter `e` clashing with a field `e` becomes `e_0` (`IllegalStateException e_0 = assertThrows(...)`, `assertNotNull(e_0.getMessage())`), while the later `assertEquals("field", e)` still refers to the field | Passed once bug 3 was fixed (this path was unreachable before). Mutation check: ignoring the new name makes the test fail, and the output shows the field shadowed by the exception variable. |

Full suite after the fix: 23 tests, 0 failures, 0 errors.

## Verification on real projects

Fresh copies of two repositories from `input/github-repos`, committed config, baseline = March commit `7a583a0`:

| Project | Baseline `7a583a0` | After Step 5 | After Step 6 |
|---------|--------------------|--------------|--------------|
| crawler-commons | 13 classes, 574 datapoints | 13 classes, 634 datapoints | 13 classes, 634 datapoints |
| java-uuid-generator | 8 classes, 141 datapoints | 8 classes, 349 datapoints | 8 classes, 349 datapoints |

The normalization now flattens the standard pattern: the normalized output contains 7 generated `assertThrows` (2 in
crawler-commons, 5 in java-uuid-generator), and the `... is not ClassExpr, it is LambdaExpr` errors are gone. A manual
check of `LockedFileTest.writeStamp_givenNegativeTimestamps_shouldThrowIOException` (JUnit 5 with a message) shows the
expected result:

```java
LockedFile lockedFile = new LockedFile(timeStampedFile);
final LockedFile lockedFile_final = lockedFile;
IOException ioException = assertThrows(IOException.class, () -> lockedFile_final.writeStamp(Long.MIN_VALUE), "This should throw an exception");
// then
assertTrue(ioException.getMessage().contains("trying to overwrite existing value"));
assertTrue(ioException.getMessage().contains("with an earlier timestamp"));
```

The datapoint counts did not change because the **splitter** now fails on these 7 test cases (next step).

## Known remaining issues

1. **The splitter cannot handle the generated `assertThrows` declarations (next step).** The committed config has no
   `assertThrows` key, so `assertThrowsStrategy` is `null` and the splitter takes its non-`STANDARD` (flat) branch. That
   branch reads the call with `statement.asExpressionStmt().getExpression().asMethodCallExpr()`, which fails on
   `X e = assertThrows(...)` (`is not MethodCallExpr, it is VariableDeclarationExpr`), and it also reads the exception
   class at `args[size - 2]` for JUnit 5.
2. **Other try shapes are still kept as they are**, and their test cases are dropped by the splitter: more than one catch
   clause (20 occurrences in java-uuid-generator), `fail()` not in the try block (6), union type in the catch clause (1).
   Each needs a decision on what the dataset should contain. The duplicated `finally` statements and the try body
   dropped for try statements without a catch clause (found in the analysis) are also still open.
3. **Escaping of the `fail` message.** `generateAssertThrowsFromTryStmt` rebuilds the message with
   `asString().replaceAll("\"", "\\\\\"")`, which re-escapes only double quotes: a message containing `\n` or `\\` would
   produce an invalid string literal. Reusing a copy of the original literal would avoid it.
4. The log line `Variable from name expression ... not found among the existing variables.` is logged as an error also
   for class names (`Integer`, `Long`, …), which is expected and harmless.

## How to run the tests

The module requires JDK 21 (the default `java` on this machine is JDK 11):

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
