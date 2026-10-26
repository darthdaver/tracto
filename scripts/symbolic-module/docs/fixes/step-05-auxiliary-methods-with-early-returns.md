# Step 5 – Inline auxiliary methods with early returns as labeled blocks

**Date:** 2026-10-22
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `b87e674c`)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`
- `src/main/java/star/tracto/data/enums/BlockStatementsType.java`
- `src/test/java/star/tracto/preprocessing/utils/FixtureRepo.java` (new, shared test setup)
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeTest.java` (four tests added, setup moved to `FixtureRepo`)
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsSplitTest.java` (new)
- `src/test/java/star/tracto/preprocessing/utils/TestUtilsNormalizeBehaviourTest.java` (new)

## Problem

An auxiliary method (helper) with an **early return** (a `return` that is not its last statement) cannot be inlined
as it is: once its body is copied into the test case, the `return` would terminate the test case, skipping the
statements that follow the call. For this reason `integrateAuxiliaryMethodIntoBlockStmt` rejected these helpers, as
well as helpers with more than one return statement, and kept the call unchanged. The assertions inside them never
became datapoints (7 rejections in java-uuid-generator).

Example from java-uuid-generator (`UUIDTimerTest`, `EthernetAddressTest`):

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

## Solution

A helper with early returns is inlined as a **labeled block**, and each of its own `return` statements becomes
`break <label>;`. A `break` with a label leaves the labeled block (also from within loops and `switch` statements, and
running any `finally` block on the way), and execution continues with the statement after the block, which is the
statement of the test case that followed the call. This is exactly the behaviour of returning from the helper:

```java
assertArrayNotEqual: {
    for (int i = 0; i < length_arg; i++) {
        if (a1[i] != a2[i]) {
            break assertArrayNotEqual;
        }
    }
    fail("Array1 and Array2 matched");
}
// the following statements of the test case still run
```

Helpers whose only return is the trailing one are still inlined flat, as in Step 4.

## Changes applied

### Normalization: `TestUtils.integrateAuxiliaryMethodIntoBlockStmt`
The rejection of multiple and non-trailing returns was replaced by a loop over the helper's own return statements
(collected with `isReturnOfMethod`, see Step 4):

- the **trailing** return is handled as in Step 4 (replaced by the evaluation of its value, if needed, or removed);
- each **early** return is replaced by `break <label>;`, preceded by the evaluation of its value when it has side
  effects (same rules as Step 4, `evaluateDiscardedValue`). The label is generated once per helper.

At the end, the inlined statements are wrapped in the labeled block when an early return was found:

```java
if (earlyReturnLabel.isPresent()) {
    currentBlockStmt.addStatement(new LabeledStmt(earlyReturnLabel.get(), new BlockStmt(new NodeList<>(clonedAuxBody.getStatements()))));
} else {
    currentBlockStmt.getStatements().addAll(clonedAuxBody.getStatements());
}
```

New helper methods:

| Method | Purpose |
|--------|---------|
| `generateLabelName(String, BlockStmt)` | Uses the helper name as label, adding `_0`, `_1`, … if the helper body already defines a label with that name (a nested label with the same name would not compile). |
| `replaceWithBreak(ReturnStmt, Optional<Statement>, String)` | Replaces an early return with `break <label>;`. If the value must be evaluated, the evaluation is inserted before the `break` in the same block; if the return is the body of another statement (for example `if (...) return x.y();`), both are wrapped in a new block. |

### Splitting: `TestUtils.parseSplitTestStatementsBlock` and `addStatement`
The splitter did not know labeled statements: a labeled block was added as one opaque statement, so the assertions
inside it were never split points. A new branch handles `LabeledStmt` like the other block statements (`for`, `while`,
`synchronized`, …): it adds a clone of the labeled statement with an empty block, and parses its body recursively with
the new block type `LABELED`. `addStatement` appends statements to the block of the last labeled statement.

```java
} else if (statement.isLabeledStmt()) {
    Statement labeledStmtBody = statement.asLabeledStmt().getStatement();
    LabeledStmt labeledStmtClone = statement.asLabeledStmt().clone();
    labeledStmtClone.setStatement(new BlockStmt());
    splitTestCasePrefix = splitTestCase.clone();
    addStatement(labeledStmtClone, splitTestCaseBody, blockStatementsType);
    Triplet<...> result = parseSplitTestStatementsBlock(config, junitVersion, new ArrayList<>(Arrays.asList(labeledStmtBody)),
            splitTestCase, idx, auxiliaryMethods, BlockStatementsType.LABELED, exceptionsToThrow, recursionLevel + 1);
    // same bookkeeping as the other block statements
}
```

```java
case LABELED:
    body = lastStatement.asLabeledStmt().getStatement().asBlockStmt();
    body.addStatement(statement);
    return;
```

### `BlockStatementsType`
New constant `LABELED(LabeledStmt.class)`.

## Tests added

### Shared setup: `FixtureRepo`
The temporary repository, the parser configuration and the test config used by the normalization tests were moved
into the package-private test helper `FixtureRepo`, now also used by the new split tests.
`TestUtilsNormalizeTest` was updated to use it, with no change to its tests.

### Normalization (`TestUtilsNormalizeTest`)

| Test | What it checks |
|------|----------------|
| `auxiliaryMethodWithEarlyReturnIsInlinedAsLabeledBlock` | The helper `assertArrayNotEqual` above is inlined as the labeled block, the following statement `assertEquals(2, a1.length);` stays after the block, and the helper is unchanged in the output. |
| `earlyReturnWithSideEffectsEvaluatesTheValueBeforeLeavingTheLabeledBlock` | `if (seen.contains(v)) { return seen.remove((Integer) v); }` becomes `if (seen.contains(x)) { seen.remove((Integer) x); break recordOnce; }`; the trailing `return seen.add(v);` becomes `seen.add(x);`. |
| `labelOfInlinedAuxiliaryMethodDoesNotClashWithItsOwnLabels` | A helper named `search` that already contains a `search:` loop label is inlined with the label `search_0`; its own `break search;` is kept. |
| `auxiliaryMethodWhoseReturnedValueIsUsedIsNotInlined` | Characterization test (passed without changes): when the test uses the value returned by a helper (`int doubled = doubleChecked(x);`, `doubled = doubleChecked(doubled);`, `assertEquals(8, doubleChecked(x) * doubled);`), the call is kept unchanged, so the value is never lost. |

### Splitting (`TestUtilsSplitTest`, new)

`assertionsWithinLabeledBlockAreSplitPoints` splits a normalized test case containing a labeled block with two
assertions, followed by a third assertion, and checks the three split test cases:

```java
// testLabeled_split_0
int x = 1;
check: { if (x < 0) { break check; } assertTrue(x > 0); }
// testLabeled_split_1
int x = 1;
check: { if (x < 0) { break check; } assertTrue(x > 0); assertEquals(1, x); }
// testLabeled_split_2
int x = 1;
check: { if (x < 0) { break check; } assertTrue(x > 0); assertEquals(1, x); }
assertNotNull("done");
```

### Behaviour (`TestUtilsNormalizeBehaviourTest`, new)

The tests above check the structure of the generated code. Since an early return must skip **the rest of the
helper**, not only the innermost loop, a behaviour test also checks what the generated code does at runtime.

`earlyReturnOfInlinedAuxiliaryMethodSkipsTheRestOfTheAuxiliaryMethod` normalizes a test class whose helper has an
early `return` inside a nested loop, followed by statements in the inner loop, after the inner loop and after the outer
loop. The test case calls the helper twice: once where the early return is taken, and once where it is not. Every
statement records itself in a static `TRACE` list:

```java
private void scan(int[] values, int stop) {
    TRACE.add("helper:start");
    for (int v : values) {
        for (int w = 0; w < 2; w++) {
            if (v == stop) {
                TRACE.add("helper:early-return");
                return;
            }
            TRACE.add("helper:visit " + v + "/" + w);
        }
        TRACE.add("helper:after-inner-loop " + v);
    }
    TRACE.add("helper:end");
}
```

The original and the normalized classes are compiled in memory (`javax.tools.JavaCompiler`) and executed, and both
traces must equal the hand-written expected trace. In particular, after `helper:early-return` the next entry must be
`test:after-first-call`. The test also checks that the helper was actually inlined (`break scan;` in the output),
because without inlining the traces would trivially be equal.

Mutation check: replacing the labeled `break scan;` with an unlabeled `break;` makes the test fail, and shows the risk
the labeled block avoids. After `helper:early-return` the helper continues with `helper:after-inner-loop 3`,
`helper:visit 4/0`, `helper:visit 4/1`, `helper:after-inner-loop 4`, `helper:end` before returning to the test case.

Why the labeled block is correct: a `break` with a label makes the labeled statement complete immediately (JLS
§14.15), and the labeled statement is the block containing the whole inlined helper. Other cases considered:
- a label with the same name inside the helper would capture the `break`: avoided by `generateLabelName` (tested);
- `break`, like `return`, runs the `finally` blocks it leaves (language semantics, not tested here);
- `return` statements inside lambdas or anonymous classes of the helper belong to them and are not changed (Step 4);
- the helper's local variables are scoped to the labeled block, as they were scoped to the helper.

### Results

| Test | Before the fix |
|------|----------------|
| early return | **Failed**: helper not inlined (`assertArrayNotEqual(a1, a2, 2);` kept) |
| early return with side effects | **Failed**: helper not inlined (`recordOnce(x);` kept) |
| label clash | **Failed**: helper not inlined (`search(xs, 1);` kept) |
| split of labeled block | **Failed**: a single split test `testLabeled_split_0` with the whole body (the labeled block was opaque) |

Full suite after the fix: 16 tests (including the behaviour test), 0 failures, 0 errors.

## Verification on real projects

Fresh copies of two repositories from `input/github-repos`, committed config, baseline = March commit `7a583a0`:

| Project | Baseline `7a583a0` | After Step 4 | After Step 5 |
|---------|--------------------|--------------|--------------|
| crawler-commons | 13 classes, 574 datapoints | 13 classes, 634 datapoints | 13 classes, 634 datapoints |
| java-uuid-generator | 8 classes, 141 datapoints | 8 classes, 329 datapoints | **8 classes, 349 datapoints** |

- The 7 `Return statement not at the end of the auxiliary method.` rejections are gone; the normalized output of
  java-uuid-generator contains 7 labeled blocks (`assertArrayNotEqual` ×3, `assertEthernetAddressArraysAreNotEqual` ×4).
- 22 datapoints of `EthernetAddressTest` have a test prefix containing a labeled block. A manual check shows a valid
  structure: for the target `fail(...)` at the end of the helper, the prefix ends with the labeled block containing the
  loop with `break assertEthernetAddressArraysAreNotEqual;` and the mask placeholder in place of the target.
- The 3 labeled blocks of `UUIDTimerTest` produce no datapoints, for an unrelated reason: the test cases calling the
  helper also contain a `try` statement that cannot be flattened, so the splitter drops the whole test case. The class
  had 0 datapoints in the baseline as well.

## Known remaining issues

1. **Try statements that cannot be flattened (next step).** They are the main remaining source of lost test cases:
   the splitter drops every test case still containing a `try` (19 test cases in java-uuid-generator, 4 in
   crawler-commons). Causes logged during normalization: more than one catch clause (20), `fail()` not in the try
   block (6), the `assertThrows` argument index for JUnit 5 with a message (7, a regression of the refactoring), union
   type in the catch clause (1).
2. **Support-variable names grow with each clash.** The names of the support variables created for non-variable
   arguments accumulate the index (`array1_start_arg1`, `array1_start_arg12`, `array1_start_arg123`), the same pattern
   already found in `JavaParserUtils.generateNewVariable`.
3. **`fail(...)` as target.** Every `fail(...)` becomes the target `THROW EXCEPTION`, including a `fail` that does not
   express an expected exception (for example the final `fail` of `assertArrayNotEqual`). This predates the refactoring.
4. Still open from earlier steps: name clashes with loop variables of the test case, partial inlining on failure,
   helpers inlined into value-returning lambdas.
5. **Possible extension.** Helpers whose returned value is used by the test case are not inlined (see the
   characterization test). They could be supported with the same labeled-block technique, assigning the value to the
   caller's variable before `break`.

## How to run the tests

The module requires JDK 21 (the default `java` on this machine is JDK 11):

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
