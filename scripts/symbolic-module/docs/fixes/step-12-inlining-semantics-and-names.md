# Step 12 – Inlining semantics and generated names

**Date:** 2026-10-27
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `80d6972d`, which commits Step 11)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/AuxiliaryMethodInliner.java` (new: the inlining, extracted from
  `TestUtils`)
- `src/main/java/star/tracto/utils/javaParser/NameAllocator.java` (new)
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (normalization context, recursive normalization of
  the inlined statements, names of the flattened try statements, labeled statements)
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (`normalizeVariableNames`; `generateNewVariable`
  and `renameLocalParam` removed)
- tests: `TestUtilsNormalizeTest`, `TestUtilsNormalizeBehaviourTest`, `JavaParserUtilsTest`, `NameAllocatorTest` (new)
- docs: `docs/design-review-2026-10-26.md`, `docs/oracles-dataset-config.md`

## Problem

The inliner replaced each parameter with the name of the argument, renamed the local variables of the auxiliary method
that clashed with the top-level variables of the test case, and appended the body of the method to the test case as it
was. This changed the semantics of the test case or produced code that does not compile:

| Defect | Example | Effect |
|--------|---------|--------|
| D4 | an auxiliary method containing `try { parse(); fail(); } catch (E e) {..}`, or calling another auxiliary method | its statements were not normalized: the try statement was kept, the nested call was not inlined |
| D5 | `bump(x)` with `void bump(int v) { v = v + 1; .. }` | `x = x + 1;`: the test case modified its own variable |
| D6 | the method reads the field `base`, the test case declares a local `base` | the inlined code read the local |
| D7 | the method declares `for (int i ..)`, the call is within the test's `for (int i ..)` | `variable i is already defined`: only the top-level variables of the test case were known |
| D12 | `checkAll(x)` with `void checkAll(int... values)` | `int values = x;` followed by `values.length`: does not compile |
| D13 | three sibling `for (int i ..)` loops | renamed `i`, `i1`, `i`: the rename map compared the declarations by content |

The generated names followed six schemes (`i1`, `r_0`, `v_arg`, `v_arg1`, `p_executable`, `search_0`), and some were
never registered: two calls to the same method with a returned value to evaluate both declared
`recordPositiveResult`.

While writing the tests, one more defect was found:

- **D31**: the normalizer had no branch for labeled statements: `outer: for (..) { .. }` was added as it was, so the
  statements within it were not normalized (a try/catch/fail stayed a try statement, an auxiliary method was not
  inlined).

## Decisions applied

From the decisions of 2026-10-26 (generated variables only when needed, with Java-style names):

- **One naming scheme**, `NameAllocator`: a name in use gets the first free numeric suffix, starting from 2 (`count2`,
  `i2`, `e2`, `search2`), for variables and labels.
- **Parameter bindings only when needed**, named after the parameter (`int v = x;`).
- The final copies made for the lambda of an `assertThrows` keep their name (`x_final`) in this step: their policy
  (only for reassigned variables, `finalX`) is Step 13.

Two choices made in this step, open to review:

- An auxiliary method containing a try statement the dataset does not support is **not inlined** (the call is kept),
  instead of discarding the test case: the test case is discarded only for what is written in it (Step 9).
- The labels generated for the inlined methods are unique within the test case: two sibling calls to the same method
  with early returns give `check: {..}` and `check2: {..}` (Java would accept the same label twice for sibling
  blocks).

## Changes applied

### `NameAllocator`
Records the names in use within a method, either *declared* (its variables and parameters, at any depth, and the names
allocated) or *reserved* (any other name a generated name must not hide: the fields of the test class, the names the
method references), and allocates new names (`allocate("count")` → `count`, or `count2`, `count3`, …).

### `JavaParserUtils.normalizeVariableNames` (D13)
Uses a `NameAllocator` and identity maps: each declaration gets a unique name (`i`, `i2`, `i3`).

### `AuxiliaryMethodInliner` (D5, D6, D7, D12)
Extracted from `TestUtils` (`integrateAuxiliaryMethodIntoBlockStmt` and its helpers), as an AST-in/AST-out class:
`findAuxiliaryMethod(call, testClass, auxiliaryMethods)` and `inline(call, method, testClass, variableNames,
labelNames, existingVariables)`, which returns the bindings of the parameters, the body of the adapted copy and the
label of the block (with early returns). On a copy of the method, attached to a copy of the compilation unit:

1. The names the method references without declaring them (fields, constants) are reserved.
2. A field used by the method whose name is declared by the test case is qualified: `this.base`, or `Type.base` for a
   static field (D6). The fields used within a nested class are not qualified (`this` would refer to the nested
   class).
3. The variables declared by the method, and the parameters of its lambda expressions and catch clauses, get a name
   from the allocator (D7: the allocator knows the variables of the test case at any depth). Its labels too.
4. Each parameter is **replaced with the argument** if the argument is a variable and the method does not assign the
   parameter (D5), and either the argument is a final field, or it is a local variable of the caller that the method
   does not use within a lambda expression or a nested class (a captured variable must be effectively final, the
   caller's may not be) and that no other argument modifies (`check(x, x++)`). Otherwise it is **bound** to a new
   local variable named after the parameter: `int v = x;` (a non-final field is bound, since the method could modify
   it). The arguments of a varargs parameter are collected in an array, `int[] values = new int[] {x};`, unless a
   single array is passed (D12). Whether the argument is an array is resolved from its type or, for a variable whose
   declaration was already moved to the normalized test case (out of the compilation unit, where a reference type
   cannot be resolved), from its declared type. A method whose varargs element type is generic, or whose varargs
   argument cannot be resolved, is not inlined.
5. The return statements are replaced as before (Steps 4–5); the `…Result` variables and the labels come from the
   allocators.

The special case of the parameters of type `Executable` (`p_executable`) is gone: they follow the general rule.

### Normalizer (D4, D31)
- `parseNormalizeTestStatementsBlock` takes a `NormalizationContext` (test class, auxiliary methods, existing variables,
  field names, the two allocators, the stack of the methods being inlined, JUnit version, configuration) instead of
  nine parameters.
- `inlineAuxiliaryMethod` normalizes the inlined statements recursively, like the statements of the test case (D4):
  their try statements are converted, their calls to auxiliary methods are inlined. A recursive call is kept as it is
  (stack of the methods being inlined). The statements are normalized into a separate block, added to the test case
  only if the normalization succeeds: if the method contains an unsupported try statement, the call is kept.
- The allocators of a test case are seeded with its parameters and variables (declared), and with the fields of the
  test class and the names it references (reserved).
- A flattened try statement renames its variables (try, catch and finally blocks, resources, the exception variable)
  only if they would hide a field: the variables of the test case already have unique names, and the inlined ones do
  not clash with them. The final copies get their names from the allocator.
- Labeled statements are normalized (D31): a loop keeps its label, any other statement becomes a labeled block.
- Removed: the branch of the expression statements for a lambda expression (dead code: a lambda cannot be an
  expression statement), `collectVariableMapping` (both), `JavaParserUtils.generateNewVariable` and
  `JavaParserUtils.renameLocalParam`.

## Tests

Written before the implementation (the D31 test after the first run of the new tests, before its fix).

| Test class | Tests |
|------------|-------|
| `TestUtilsNormalizeTest` | Enabled: D4 (×2), D5, D6, D7, D12, D13. New: an argument that is a field is bound; a parameter used within a lambda is bound; a variable modified by another argument is bound; a local of the method clashing with a field is renamed; a lambda parameter of the method clashing with a variable of the test case is renamed; two calls to the same method get different `…Result` names; the label of an inlined method does not clash with a label of the test case; a final field replaces the parameter (even within a lambda); a recursive call is kept; a method with an unsupported try statement is not inlined (no discard, no error); several varargs arguments are collected in an array (characterization); an array passed to a varargs parameter is passed as it is, for a primitive and for a reference array; the statements within a labeled loop are normalized (D31). Updated to the naming decision: `i1` → `i2`, `v_arg` → `v`, `r_0` → `r2`, `length_arg` → `length`, `target_arg` → `target`, `search_0` → `search2`, `e_0` → `e2`, `count_0` → `count2`. |
| `TestUtilsNormalizeBehaviourTest` | New: compiles and runs a test case calling three auxiliary methods (one assigning its parameter, one reading a field hidden by a local, one with varargs, called twice), before and after the normalization, and compares the traces. Before the fix the normalized class did not compile (`int values = x;`). |
| `JavaParserUtilsTest` | Updated: `a1` → `a2`, `e1` → `e2`. Removed: the tests of `renameLocalParam`. |
| `NameAllocatorTest` | New: 4 tests. |

Before the implementation 26 tests failed. Mutation checks: replacing every parameter with its argument and dropping
the qualification of the fields (one run) makes the four binding tests, the D6 test and the behaviour test fail;
without the recursion guard (inlining up to a depth of 50), the recursive-call test fails; propagating the discard of
an auxiliary method, the unsupported-try test fails (its check of the error report was added for this mutation: the
discard was caught by the generic handler of the call, which kept the call too).

Two tests were added after the first A/B run (below), each failing before its change: a reference array passed to a
varargs parameter, and a final field passed as argument.

Full suite: 170 tests, 0 failures, 0 errors, 6 skipped (D8, D17, D18, D20, D21, D25).

## Verification on real projects

The seven projects of Step 11 (crawler-commons, java-uuid-generator, ffmpeg-cli-wrapper, plexus-utils, jsoup, eec,
esapi), fresh copies, committed configuration, compared with Step 11 datapoint by datapoint.

The first run found two cases the tests did not cover, each fixed test-first before the final run:

- a `String[]` passed to a varargs parameter (`createFiles(dir, excludedPaths)`, plexus-utils) was not recognized as
  an array, so the method was not inlined: the declaration of the array was already moved to the normalized test
  case, where the type `String` cannot be resolved. The declared type of the variable is now used;
- a final field passed as argument (`VALID_ETHERNET_ADDRESS_BYTE_ARRAY`, java-uuid-generator) was bound to a new
  variable, although the method cannot modify it: final fields now replace the parameter.

Final run:

| Project | Step 11 | Step 12 | Differences |
|---------|---------|---------|-------------|
| crawler-commons | 649 | **655** | +6 from a nested auxiliary method (D4): `parseCompareDate(zdt, date)` calls `parseCompareDate(zdt, date, null)`, which Step 11 left as a call. 74 datapoints with renamed variables only. |
| java-uuid-generator | 369 | 369 | 209 datapoints with renamed variables only (`array2_arg` → `array2`, `i_0` → `i2`, …). |
| ffmpeg-cli-wrapper | 586 | 586 | 1 renamed variable (`e1` → `e2`). |
| plexus-utils | 778 | **785** | +7 from the nested `assertEqualContent(testFile, file)` of `checkFile` in `IOUtilTest` (D4), and the renumbering of the following split test cases; a try-with-resources within an auxiliary method of `CommandlineTest` flattened (D4); `DirectoryScannerTest` no longer declares `String[] paths` twice in the same block; 72 datapoints with renamed variables only. |
| jsoup | 3551 | 3551 | `SelectorTest`: the arguments of a varargs auxiliary method were bound as `String[] ids = ...;` twice in the same block, before the first argument; now `els`, `ids`, `els2`, `ids2`, in the order of the parameters (58 datapoints). 132 datapoints with renamed variables only. |
| eec | 1427 | 1427 | try-with-resources within auxiliary methods flattened (D4: 92 datapoints of `StatementSheetTest` and `ExcelReaderTest`); 209 datapoints with renamed variables only. |
| esapi | 540 | **528** | −12: `CryptoTokenTest.CTORtest` contains a try statement whose catch block calls `fail()` (unsupported): the call is not inlined anymore (in Step 11 the method was inlined with its try statements as they were). 61 datapoints with renamed variables only. |

The changed datapoints were classified with a script (scratchpad `ab/renamediff.py`): same tokens up to a consistent
renaming of identifiers, or up to the suffixes of the generated names; the others were checked by reading the diff of
their prefixes. No discarded test case was added or removed. The logs of jsoup have 7 errors fewer (an auxiliary
method with type parameters is now logged as information), the logs of eec 2 more (two `switch` statements, still not
supported, now reached within inlined auxiliary methods).

A scan of the normalized test cases of the seven projects for variables or parameters declared twice (at any depth):

| | Step 11 | Step 12 |
|-|---------|---------|
| Test cases with a name declared twice | 45 of 2246 | **0** |
| ... with the two declarations in the same block (the class does not compile) | 32 | **0** |

## Known remaining issues

- The final copies of the variables used in a failing call (`x_final`), and the loop variables without a copy (D8):
  Step 13.
- The fields inherited by the test class are not reserved, unless the test case references them: a variable of a
  flattened try block named like an inherited field is not renamed.

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
