# Step 35 – No body for the methods without a source

**Date:** 2026-11-11
**Branch:** `22-test-processing-refactoring` (on top of Step 34)
**Files changed:**
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (`getBodyFromMethodUsage`,
  `getBodyFromResolvedConstructorDeclaration`): no body when the signature is not in the text of the declaration
- tests: `SourcelessBodyTokensTest` (new, end-to-end)

## Problem

Found by the corpus check of Step 34: one step of cryptofs (`OrphanContentDirTest`, `stepParentDir.path()`) differed
from one run to another. The contextual information of an invoked method holds its body; for a method without a source,
the body is taken from the text of the `MethodUsage` (`toString()`): the text after the signature, from its first `{`.
When the signature is not in that text, the index was `-1` and the text was taken anyway, from the first `{` after a
few characters: a description of the JavaParser object, not code.
- The accessors of the records (Step 33 offers them): `{declaration=...JavaParserRecordDeclaration$ImplicitGetterMethod@1ab268bd,
  paramTypes=[]}`, with an identity hash that changes from one run to another (the corpus output was not deterministic).
- The methods of the JDK read by reflection (`Enum.compareTo`, `Enum.valueOf`, `Object.wait`), since before dataset v1:
  `{declaration=ReflectionMethodDeclaration{method=public final int java.lang.Enum.compareTo(java.lang.Enum)}, ...}` or
  `{method=public static java.lang.Enum java.lang.Enum.valueOf(...)}, paramTypes=[...]}`, deterministic.

The same code extracts the body of a constructor without a source.

## Fix

When the signature is not in the text of the declaration, no body (empty, as when the text has no `{`), for methods and
constructors.

## Tests

`SourcelessBodyTokensTest` (end-to-end): a record accessor (`entry.name()`) and an enum method of the JDK
(`Level.LOW.compareTo(...)`) are offered, and no `body` of the tokens datapoints contains `declaration=`. Checked on
2026-11-11: against the code of Step 34 the test fails (the bodies `{declaration=...ImplicitGetterMethod@c4cceb, ...}`,
`@60f9dc7e`, and `{declaration=ReflectionMethodDeclaration{method=... Enum.compareTo ...}`). All 443 tests pass.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-11: statuses unchanged (1,209 complete, 90 lose
a step, 3 dropped; 0 better, 0 worse). Compared field by field with the outputs of Step 34 (`tmp/ab/corpus-step34-out`,
`corpus-step35-out`): 98 steps of 41 oracles differ (steve 13, ffmpeg 14, cryptofs 6, eec 5, jsoup 2, crawler 1), only in
`contextualInfos[].invokedMethods[].body`: 290 bodies emptied, every one of them a description of a `MethodUsage`
(`{declaration=...` 100, of which 5 record accessors; `{method=...` 190, all with `paramTypes=`). No other field changed.
