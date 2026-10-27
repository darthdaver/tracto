# Step 11 – Normalizer `if` chains

**Date:** 2026-10-27
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `e6e365b2`, which commits Step 10)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (`if` branch of `parseNormalizeTestStatementsBlock`,
  `traverseIfElseStatement` removed)
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (`getLastStatementTypeOccurrence` removed)
- `src/main/java/star/tracto/utils/javaParser/visitors/statements/helper/StmtVisitorHelper.java`
  (`getLastStatementOccurrence` removed)
- `src/main/java/star/tracto/preprocessing/utils/TestCaseSplitter.java` (D30, found by the A/B run of this step)
- tests: `TestUtilsNormalizeTest`, `TestUtilsNormalizeBehaviourTest`, `TestUtilsSplitTest`
- docs: `docs/design-review-2026-10-26.md`

## Problem

The normalizer rebuilt an `if` statement branch by branch: it created the `if` with the first condition and an empty
block, normalized the `then` branch into the block, and then attached each `else if`/`else` branch to the last `if` of
the chain. It did not keep a reference to the `if` it had created: after each branch it searched the current block
for the **last `if` statement** (`getLastStatementTypeOccurrence`), and walked its `else` chain
(`traverseIfElseStatement`). When the branch just normalized contained an `if` (directly or within a loop, a lambda, …),
the search returned that nested `if`:

| Shape | Effect |
|-------|--------|
| `if (a) { if (b) {..} } else {X}` | The `else` is attached to the nested `if`: `if (a) { if (b) {..} else {X} }` (D2). |
| `if (a) { if (b) {..} else {Y} } else {X}` | The walk of the nested `else` chain fails (`else {Y}` is not an `if`). The error is caught, and the original `if` is added **after** the partially built one: the statement appears twice, and the test case is reported as a normalization error. |
| `if (a) {..} else if (b) { for (..) { if (c) {..} } } else {X}` | The `else` is attached to the `if` within the loop. |

In every case the normalized test case runs different branches than the original one. The behaviour test written for
this step shows it: for `x` in `{-1, 1, 7}` the original trace is
`[a:non-positive -1, b:non-positive -1, b:small 1, a:big 7, b:big 7]`, the normalized one was
`[b:non-positive -1, a:non-positive 1, b:small 1, b:small 1, a:big 7, b:big 7, b:big 7]`.

The splitter part of D2 was fixed in Step 10 by the rewrite of the splitter.

While writing the tests, one more difference with the other compound statements was found:

- **D29**: the `if` statement was rebuilt with `new IfStmt(condition, block, null)`, so its comment
  (`// positive values` before the `if`) was dropped. Loops, `synchronized` and try statements are cloned, and keep it.

The A/B run of this step found a defect of the splitter written in Step 10:

- **D30**: `TestCaseSplitter` wraps the single-statement bodies of the compound statements in blocks while it collects
  the split points, then locates each split point in the copies of the test case by its index in pre-order. The
  setters used to wrap a body (`setThenStmt`, …) append the new block **after** the other children of the statement;
  the copies (`clone()`) rebuild the children in the order of the source code. When only the `then` branch of an `if`
  is wrapped (the `else` branch is already a block or an `else if`), the `else` branch comes first in the pre-order of
  the wrapped test case and last in the copies, so the indexes point to other statements:

  ```java
  if (x > 0) assertTrue(x > 0); else { assertTrue(x <= 0); }
  ```

  gave `split_0` ending with the `else` branch and `split_1` ending with the `then` branch, and with an `else if` the
  split test cases were cut at the wrong statement. Since this step the normalizer writes every branch as a block, so
  only the code it does not rewrite reaches the splitter in this shape: the bodies of inlined auxiliary methods (D4)
  and the lambda blocks passed as arguments. In Step 10 it also affected the `if` statements duplicated by D2 (see the
  esapi results below).

## Changes applied

The `if` branch now works like the other compound statements (loops, `synchronized`, the `keep-try-catch` branch of
try statements): the `if` statement is **cloned** and added to the current block, and each branch of the clone is
replaced with an empty block, filled with the normalized statements of the corresponding branch of the original. The
original `if` and its clone are walked together along the `else if` chain, so each branch is filled in its own `if`,
with no search:

```java
IfStmt ifStmt = statement.asIfStmt();
IfStmt ifStmtClone = ifStmt.clone();
currentBlockStmt.addStatement(ifStmtClone);
while (true) {
    // the then branch of ifStmtClone <- the normalized then branch of ifStmt
    if (ifStmt.getElseStmt().isEmpty()) break;
    Statement elseStmt = ifStmt.getElseStmt().get();
    if (elseStmt.isIfStmt()) {
        // next step of the else-if chain, in both statements
        ifStmt = elseStmt.asIfStmt();
        ifStmtClone = ifStmtClone.getElseStmt().get().asIfStmt();
    } else {
        // the else branch of ifStmtClone <- the normalized else branch; break
    }
}
```

As before, single-statement branches are wrapped in blocks, an `else if` stays an `else if`, and an `if` written within
an `else` block stays within the block. The conditions are now copied instead of being moved from the original test
case, like the conditions of the loops.

Removed, since nothing else used them: `traverseIfElseStatement`, `JavaParserUtils.getLastStatementTypeOccurrence` and
`StmtVisitorHelper.getLastStatementOccurrence`. The statement visitors that only this last method used (all the
classes of `utils/javaParser/visitors/statements` except `AssertStmtVisitor` and `ExpressionStmtVisitor`) are now
unreferenced; they are listed with the dead code of Step 15.

### `TestCaseSplitter` (D30)
The split points are collected twice: a first time on a copy of the test case, to wrap the bodies; a second time on a
copy of the wrapped test case, whose children are in the order of the source code like the copies made for each split
point. No other change to the splitter.

## Tests

Written before the fix.

| Test | Before the fix | After |
|------|----------------|-------|
| `TestUtilsNormalizeTest.ifStatementKeepsTheStructureOfItsBranches` (parameterized, 6 cases; replaces the `@Disabled` D2 test) | 2 pass (characterization: single-statement branches of an `else if` chain wrapped in blocks; `if` within an `else` block), 4 fail (the three shapes of the table above, D29) | pass |
| `TestUtilsNormalizeBehaviourTest.elseBranchesOfNestedIfStatementsRunAsInTheOriginalTestCase`: compiles and runs the original and the normalized test case and compares their traces | fails (traces above) | passes |
| `TestUtilsSplitTest.singleStatementThenBranchFollowedByAnElseIsSplitInTheOrderOfTheSourceCode` (D30, written after the A/B run, before its fix) | fails: `split_0` and `split_1` swapped, `split_2` cut at the wrong statement | passes |

Each case of the parameterized test also checks that no normalization error is reported.

Full suite: 153 tests, 0 failures, 0 errors, 13 skipped (10 normalization defects for Steps 12–15, D20, D21, D25).

## Verification on real projects

Fresh copies of the repositories, committed configuration, compared with Step 10 datapoint by datapoint (key: test
class and split test case). Besides the two usual projects, five repositories were added because their test cases
contain the D2 shape (an `if` with an `else` whose `then` branch contains another `if`): they were found with a
JavaParser scan of the test methods of the 121 repositories of `input/github-repos` (19 repositories have the shape).

| Project | Step 10 | Step 11 | Differences |
|---------|---------|---------|-------------|
| crawler-commons | 649 | 649 | none (byte-identical normalized files and datasets) |
| java-uuid-generator | 369 | 369 | none (byte-identical) |
| ffmpeg-cli-wrapper | 586 | 586 | none |
| plexus-utils | 778 | 778 | 1 prefix: the `else { fail(..) }` of `FileUtilsTest.testcopyDirectoryLayoutWithExcludesIncludes` is back on the outer `if` (D2, first shape) |
| jsoup | 3551 | 3551 | 1 prefix: the comment before an `if` is kept (D29) |
| eec | 1435 | **1427** | 8 datapoints removed from 2 test cases whose `if` statement was duplicated (D2, second shape: `ExcelReaderTest.testForEach`, `PictureTest.testExportPictureAutoSizePaging`), 2 targets changed by the renumbering of the following split test cases, 6 prefixes with the comment before an `if` (D29) |
| esapi | 544 | **540** | 4 test cases of `ReferenceEncryptedPropertiesTest` with a duplicated `if` statement (D2, second shape): 7 datapoints of the copies removed, 3 recovered in `testKeySet` (lost in Step 10 with "No assertion found": D30 on the copy, whose branches were not blocks), 19 targets changed by the renumbering, 3 prefixes without the copy |

In the D2 test cases the Step 10 datapoints were wrong: the normalized test case ran other branches than the original
one (plexus-utils), or contained the `if` statement twice, with the targets of the copy as extra datapoints (eec,
esapi). The logs of eec and esapi have 6 normalization errors fewer (the failed walk of the `else` chain), and esapi
3 "No assertion found" errors fewer.

With the Step 11 normalizer, the splitter fix (D30) changes no datapoint of the seven projects (runs with and without
it compared): after the normalizer fix, the shape reaches the splitter only once (an `if`/`else if` within a lambda
block of `ElementTest.testForEach` in jsoup), before the only split point of the test case, whose index is then the
same.

## Known remaining issues

The remaining normalization defects (D4–D8, D12, D13, D17, D18) are covered by the next steps (see
`docs/design-review-2026-10-26.md`).

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
