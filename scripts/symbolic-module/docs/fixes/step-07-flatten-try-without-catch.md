# Step 7 – Flatten try statements without catch clauses

**Date:** 2026-10-23
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `b87e674c`)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeTest.java` (five tests added; `java.io.*` added to
  the imports of the `normalizeHelperTest` fixture)

## Context

The configuration discussion (see `docs/oracles-dataset-config.md`) led to these decisions for try statements:
- the `try-catch-finally` key keeps two modes, both handled in the normalization phase: `to-assert-throws` (committed
  config) and `keep-try-catch`; the old `flat` behaviour is dropped;
- in the `to-assert-throws` mode, try-with-resources and try/finally statements without catch clauses are flattened;
- the test cases with bad or unsupported try patterns are discarded (next step).

A count over the 257 repositories in `input/github-repos` (88,624 test methods, 11,825 try statements in 8,292 test
methods) showed that try statements without catch clauses are the most common ones:

| Shape | Try statements | Share |
|-------|----------------|-------|
| try-with-resources without catch (`try (R r = ...) { ... }`) | 5,056 | 42.7% |
| expected exception (`try { ...; call(); fail(); } catch (E e) { ... }`) | 2,497 | 21.2% |
| catch without any `fail()` | 1,831 | 15.5% |
| must-not-throw (`catch (E e) { fail(); }`) | 1,449 | 12.3% |
| try/finally without catch | 653 | 5.5% |
| empty catch | 229 | 1.9% |
| expected exception with more than one catch clause | 98 | 0.8% |
| union catch / `fail()` not last in the try block | 12 | 0.1% |

## Problem

`flatTryStmt` only handled a try statement with exactly one catch clause. For a try statement **without** catch
clauses, it skipped everything except the finally block, so the resources and the whole try block were **silently
dropped** (no error logged). Verified on a minimal example:

```java
// original                                                       // normalized (before the fix)
try (InputStream in = new ByteArrayInputStream(new byte[] {7})) {  { assertTrue(true); }
    assertEquals(7, in.read());
}
assertTrue(true);

StringBuilder sb = new StringBuilder();                            { StringBuilder sb = new StringBuilder();
try {                                                                sb.setLength(0); }
    sb.append("a");
    assertEquals("a", sb.toString());
} finally {
    sb.setLength(0);
}
```

In the March baseline these test cases were dropped by the splitter (normalization never ran there); after the
refactoring they produced datapoints with missing statements.

Two related bugs in the same method were fixed:
- **Duplicated finally.** With one catch clause and a finally block, the finally statements were added twice (once in
  the catch branch, once after it).
- **Resource references.** A Java 9 resource that refers to an existing variable (`try (in) { ... }`) was added as the
  statement `in;`, which is not valid Java.

## Changes applied

### `TestUtils.flatTryStmt`
- New branch for try statements **without catch clauses**: the resources, then the statements of the try block, whose
  local variables are renamed if they clash with a variable of the main block (as already done for the try block in
  the one-catch branch). Renaming matters because, once flattened, a local variable of the try block would otherwise
  shadow a field with the same name for the rest of the test case.
- The finally block is handled **once**, after both branches, with the same renaming.

```java
} else if (tryStmt.getCatchClauses().isEmpty()) {
    // A try statement without catch clauses (try-with-resources or try/finally) is flattened as it is: the
    // resources, then the statements of the try block
    statements.addAll(flatResources(tryStmt, existingVariables));
    // Replace local variables in the try block, if already defined within the main block
    Map<VariableDeclarator,String> tryVarMapping = collectVariableMapping(tryStmt.getTryBlock(), existingVariables);
    JavaParserUtils.replaceVariables(tryStmt.getTryBlock(), tryVarMapping);
    statements.addAll(tryStmt.getTryBlock().getStatements());
}
// Check if the try statement contains a finally block, in which case collect the statements within it
Optional<BlockStmt> finallyBlockStmt = tryStmt.getFinallyBlock();
if (finallyBlockStmt.isPresent()) {
    Map<VariableDeclarator,String> finallyVarMapping = collectVariableMapping(finallyBlockStmt.get(), existingVariables);
    JavaParserUtils.replaceVariables(finallyBlockStmt.get(), finallyVarMapping);
    statements.addAll(finallyBlockStmt.get().getStatements());
}
```

### New helper `TestUtils.flatResources(TryStmt, Set<VariableDeclarator>)`
The resource handling of the one-catch branch was moved into this helper, now used by both branches. It renames the
resource variables that clash with existing variables (unchanged logic) and returns a declaration statement only for
the resources that **declare** a variable; a resource that refers to an existing variable produces no statement.

## Tests added

| Test | Original | Expected normalized body | Before the fix |
|------|----------|--------------------------|----------------|
| `tryWithResourcesWithoutCatchIsFlattened` | `try (InputStream in = new ByteArrayInputStream(new byte[] {7})) { assertEquals(7, in.read()); } assertTrue(true);` | `InputStream in = new ByteArrayInputStream(new byte[] {7}); assertEquals(7, in.read()); assertTrue(true);` | `{ assertTrue(true); }` |
| `tryWithResourceReferenceIsFlattenedWithoutAddingTheReference` | `InputStream in = ...; try (in) { assertEquals(7, in.read()); }` | `InputStream in = ...; assertEquals(7, in.read());` | `{ InputStream in = ...; }` |
| `tryFinallyWithoutCatchIsFlattened` | the `StringBuilder` example above | `StringBuilder sb = new StringBuilder(); sb.append("a"); assertEquals("a", sb.toString()); sb.setLength(0);` | `{ StringBuilder sb = new StringBuilder(); sb.setLength(0); }` |
| `flattenedTryLocalClashingWithFieldIsRenamed` | field `count`; `try { int count = 1; assertEquals(1, count); } finally { count = 0; } assertEquals(0, count);` | `int count_0 = 1; assertEquals(1, count_0); count = 0; assertEquals(0, count);` (the finally block and the last assertion still refer to the field) | `{ count = 0; assertEquals(0, count); }` |
| `finallyOfFlattenedTryCatchFailIsAddedOnce` | `try { calc.sqrt(-1); fail(); } catch (IllegalArgumentException e) { assertNotNull(e); } finally { calc.reset(); }` | `... IllegalArgumentException e = assertThrows(...); assertNotNull(e); calc.reset();` | `calc.reset();` twice |

Full suite after the fix: 28 tests, 0 failures, 0 errors.

## Verification on real projects

Fresh copies of two repositories from `input/github-repos`, committed config, baseline = March commit `7a583a0`:

| Project | Baseline `7a583a0` | After Step 6 | After Step 7 |
|---------|--------------------|--------------|--------------|
| crawler-commons | 13 classes, 574 datapoints | 13 classes, 634 datapoints | 13 classes, 634 datapoints |
| java-uuid-generator | 8 classes, 141 datapoints | 8 classes, 349 datapoints | 8 classes, 349 datapoints |

The counts do not change: crawler-commons contains no try statement without catch clauses, and the 7 of
java-uuid-generator (all in `LockedFileTest`) are setup code without assertions. The datapoints are nevertheless
different: before the fix their prefixes lacked the setup. For example, in
`readStamp_givenTimestampedFile_shouldReturnValueInside` the prefix of `assertEquals(expectedTimestamp, stamp)` now
contains the statements that write the timestamp file:

```java
File timeStampedFile = Files.createTempFile(temporaryFolder, null, null).toFile();
FileWriter fileWriter = new FileWriter(timeStampedFile);
// we are faking the timestamp format
fileWriter.write("[0x0000000000000001]");
fileWriter.flush();
// when
LockedFile lockedFile = new LockedFile(timeStampedFile);
long stamp = lockedFile.readStamp();
```

On the whole set of repositories the effect is larger: 48% of the try statements have no catch clause, and those with
assertions inside the try block now contribute their assertions as datapoints.

## Known remaining issues (next steps)

1. Try statements with more than one catch clause and without `fail()` (or with `fail()` in the catch block) still reach
   `flatTryStmt`, where neither branch applies, so their try block is still silently dropped. The classification of the
   try shapes, with the bad patterns discarded, is the next step.
2. The resource rename map is still a structural `HashMap` (see the identity-map issue found in Step 3).
