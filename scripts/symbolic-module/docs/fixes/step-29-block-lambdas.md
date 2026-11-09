# Step 29 – Block lambdas: a target is always a single statement

**Date:** 2026-11-09
**Branch:** `22-test-processing-refactoring` (on top of Step 28)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/AssertionLambdaBlockClassifier.java`: the lambda blocks of one
  statement are in scope (`find`); `producesNoDatapoint` also holds for any assertion containing a block of statements
  (`containsAStatementBlock`, new)
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (`normalizeAssertionLambdaBlock`): a block of one call
  is written as an expression (statistics key `expression-lambda`; no `throws Exception` added; the comment of the
  assertion stays on it)
- `src/main/java/star/tracto/lsp/antlr4/Antlr4Utils.java` (`tokenizeSnippet`): only the tokens of the default channel of
  the lexer (no whitespace, no comment); `TokensUtils`: the removal of the `" "` tokens, now dead, is gone
- `docs/oracles-dataset-config.md` (`assertion-lambda-blocks`)
- tests: `TestUtilsAssertionLambdaBlocksTest` (new cases: blocks of one statement, assertions with a statement block,
  a one-line target), `AssertionLambdaBlockClassifierTest` (blocks of one statement, `producesNoDatapoint`),
  `TestUtilsDatapointsTest` (updated), `BlockLambdaTokensTest` (new, end-to-end), `TokenizeSnippetTest` (new)

## Problem

52 oracles of the nine projects (cryptofs 49) were dropped by the tokens stage with "No enum constant for symbol: EOF".
They are assertions with a block lambda printed on several lines: `assertThrows(E.class, () -> {\n    a();\n});`. The
target was split with the lexer and only the `" "` tokens were removed, so a line break with indentation stayed a
target token; `getAntlrToken` lexes it as hidden, sees EOF, and the target was dropped.

Step 19 normalizes the lambda blocks of `assertThrows`, `assertThrowsExactly` and `assertDoesNotThrow` (the statements
before the last call move before the assertion, only the call stays in the lambda), but only the blocks of **at least
two** statements: a block of one statement stayed a block, and the target was a block of statements on several lines.
The `assertTimeoutPreemptively` with a block (5, cryptofs) were not in scope at all.

## Decision (2026-11-09)

The author asked that the model always generate a single statement, never a block, and left the design to Claude:

- **At normalization, not at token generation**, so that the target text, the `tgt` column compared by the RQ scripts
  and the model input are one statement on one line.
- A block of one statement is normalized as a longer one, with nothing to move: `() -> { a(); }` becomes `() -> a()`
  (a declaration or a return keeps its call: `() -> { int v = f(); }` and `() -> { return f(); }` become `() -> f()`);
  an `assertDoesNotThrow` of one assertion is unwrapped; the other shapes (a `throw`, a compound statement, ...)
  produce no datapoint, as for the longer blocks.
- Any other assertion that still contains a block of statements (a lambda block, as in
  `assertTimeoutPreemptively(d, () -> {...})` or `assertAll(() -> {...})`, or the body of an anonymous class) produces
  no datapoint. A timeout block cannot be split like an `assertThrows` (the moved statements would no longer be
  timed); skipped for now.
- The tokenizer skips the whitespace and the comments: the formatting of a target is never a token. At inference the
  partial oracle (built by `appendToken`) holds single spaces only, removed before as well: inference does not change.

## Tests

- `TestUtilsAssertionLambdaBlocksTest`: a block of one call, of one declaration, of one return becomes an expression; an
  `assertDoesNotThrow` of one assertion is unwrapped; a single `throw` is left as it is (no datapoint); the comment of
  the assertion stays on it; no `throws Exception` when nothing moves; the statistics count `expression-lambda`; an
  `assertThrows` with a block of one call printed on several lines is a one-line target; an assertion with a lambda block
  of another assertion (of two statements or one), a nested lambda block, an anonymous class, a block left by the
  normalization is not a target, the other assertions of the test case are (`assertTimeout` with an expression lambda).
- `AssertionLambdaBlockClassifierTest`: the blocks of one statement are classified; `producesNoDatapoint`.
- `BlockLambdaTokensTest` (end-to-end): the `assertThrows` and `assertDoesNotThrow` with a block of one call are one-line
  targets of the tokens stage, with all their tokens among the candidates.
- `TokenizeSnippetTest`: the whitespace and the comments are skipped, a string literal keeps its spaces.

Checked on 2026-11-09: against the code of Step 28, 23 of the new or updated tests fail. All 436 tests pass.

## Verification

Oracles stage of the nine corpus projects (`corpus.py prepare`), compared with the oracles of Step 28 (9,154 →
9,148 datapoints):
- 73 targets change, all from a block of one statement to a one-line expression lambda (cryptofs 69, jsoup 2,
  ffmpeg 1, plexus 1);
- 6 datapoints are gone: the 5 `assertTimeoutPreemptively` with a block (cryptofs) and 1 `assertThrows` whose single
  statement contains an anonymous class (`new OpenOption() {...}`, cryptofs `testUnsupportedOption`);
- no multi-line target is left (besides the 8 Hamcrest `MatcherAssert.assertThat` with leading `//` comments, out of
  scope and dropped by the tokens stage).

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-09:

| | complete | loses a step | dropped |
|---|---|---|---|
| Step 28 | 1,110 | 155 | 37 |
| Step 29 | 1,119 | 155 | 28 |

**0 worse.** The 9 block lambdas of the corpus (cryptofs: 7 `assertThrows`, 1 `assertDoesNotThrow`, and the assigned
`IOException thrown = assertThrows(...)` of `MigratorsTest`, still dropped after Step 27) are now one-line targets
(new keys) and complete. Compared step by step with the outputs of Step 28, no candidate and no partial oracle changed;
only the `contextualInfos` of 28 steps (9 oracles of `FilePathMigrationTest` and `ReadonlyFlagTest`), which hold the
bodies of the other methods of the test class, now showing the one-line lambdas.

The 28 dropped left in the corpus are out of the scope of this step: Hamcrest `assertThat` (ffmpeg 18, cryptofs 2) and
`MatcherAssert.assertThat` (cryptofs 5), and `assertInstanceOf` (jsoup 2, cryptofs 1), which has no grammar rule.
