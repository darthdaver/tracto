# Step 32 – Chains: the members of the type of a cast

**Date:** 2026-11-10
**Branch:** `22-test-processing-refactoring` (on top of Step 31)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java` (`updateTokenIdentifiersAndReferences`): the frames of
  the parentheses record whether they open a parenthesized expression and their tokens; `isCastType`, `applyCast` (new);
  `collectMembersOfTypes` (new: the loop collecting the members of the types of an identifier, moved out unchanged so
  that a cast uses it too); the type of the field `length` of the arrays
- `src/main/java/star/tracto/data/enums/ContextualInfoType.java`: `CAST_RETURN` (new, last)
- tests: `ChainMembersTokensTest` (cases added)

## Problem

Second part of step 7 (type inference of the chains). In dataset v1, 44 missing steps follow a cast (crawler 24,
jsoup 10, cryptofs 4, eec 3, plexus 3): `((SimpleRobotRules) rules).` ▸ `getRobotRules`, then often the next member of
the chain (`.getRobotRules().` ▸ `size`).

The state machine of the tokens stage has a frame per parenthesis: `(` pushes a frame in the root state, `)` pops it.
Nothing recorded a cast: after `((T) x)` the frame below the parentheses was left as it was, usually in the root state,
and after the `.` the model was offered the class names of the root (125 to 193 classes), not the members of T.

A Step 31 defect found by the new test: the field `length` added to the arrays (a declarator with no parent) could not
be resolved when `length` was the token (`Unable to process field length`, the token recorded as unsolvable: at
inference, a `.` or `(` generated after it would stop the inference).

## Fix

- A frame opened by a `(` that does not follow a name, `this`, `super` or a statement keyword (`if`, `while`, `for`,
  `switch`, `catch`, `synchronized`, `try`) is a parenthesized expression; each frame records its tokens.
- When such a frame is closed and its tokens are names separated by dots, the last one a class (`CLASS_CALL`), it is the
  type of a cast, `(T)` or `(Outer.T)`: the enclosing frame keeps the class of T.
- When the parenthesized expression that holds it is closed, `((T) expression)`, the frame below gets the members of T
  (as after a variable of type T: `collectMembersOfTypes`) and the contextual information of a value of type T, as after
  the result of a call, with the new type `CAST_RETURN`.
- Not recognized: generic and array types (`(List<String>) x`, `(byte[]) x`), qualified names of packages
  (`(java.util.List) x`). A cast as an argument (`foo((T) x)`) changes nothing: the call's result follows.
- The type of the field `length` is `int` (`ResolvedPrimitiveType.INT`), not resolved from its declarator.

## Tests

`ChainMembersTokensTest` (end-to-end), cases added: `((TextNode) node).` ▸ `wholeText`, then `.wholeText().` ▸ `trim`;
`((TextNode) nodes.get(0)).` ▸ `wholeText`; `Node.describe((TextNode) node).` ▸ `trim` and not `wholeText`; no
`Unable to process field` message. Checked on 2026-11-10: against the code of Step 31 the test fails (`wholeText` and
`trim` missing after the casts; its log has the message `Unable to process field length`). All 440 tests pass.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-10:

| | complete | loses a step | dropped |
|---|---|---|---|
| Step 31 | 1,172 | 127 | 3 |
| Step 32 | 1,178 | 121 | 3 |

**0 worse.** 6 oracles complete: cryptofs 2 (`((PatternPathMatcher) pathMatcher).getPattern().pattern()`), jsoup 1
(`((TextNode) hello.nextSibling()).getWholeText()`), plexus 3 (`((Artifact) ReflectionValueExtractor.evaluate(...))`).
Compared step by step with the outputs of Step 31: 38 steps differ, all in these 6 oracles. After `((T) x).` the class
names of the root are replaced by the members of T (in plexus the private fields of `Artifact`, a class nested in the
test class: legal there); from the closing `)` the contextual information is `CAST_RETURN` and T.

The classes of dataset v1 with a cast outside the corpus (`dev_tokens.py`, Step 31 jar against Step 32 jar, oracles of
the v1 run; crawler `SiteMapParserTest`, `SimpleRobotRulesParserTest`; eec `SimpleSheetTest`, `TemplateSheetTest`; jsoup
`TokeniserTest`, `NodeTest`, `XmlTreeBuilderTest`, `PrinterTest`; cryptofs `RealUseTokenTest`; 931 oracles):
- 22 oracles complete, 0 worse: crawler 12 (all its oracles now complete), jsoup 8, eec 2 (`(Timestamp)`, a class of
  the JDK);
- candidates: removed only the class names of the root after the `.` of a cast (one step per cast), added the members
  of T and those of the next member of the chain (`getRobotRules().`: the methods of `List`). For `Timestamp` the
  private members of the JDK class are offered too (`writeObject`, `getCalendarSystem`), the known defect of step 9;
- still missing near a cast in cryptofs: `(Path) e2.context()`, the member `context` of `e2`, the parameter of the
  lambda that holds the assertion (`createEvent.ifPresent(e2 -> {...})`), whose type is not known (not the cast).

jsoup `ElementTest` (857 oracles, 2 of the cast steps) was left out: its run alone takes about 1.5 hours.
