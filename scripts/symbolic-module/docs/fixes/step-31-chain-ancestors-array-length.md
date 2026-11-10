# Step 31 – Chains: the type parameters of the ancestors, the length of the arrays

**Date:** 2026-11-09
**Branch:** `22-test-processing-refactoring` (on top of Step 30)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java` (`updateTokenIdentifiersAndReferences`, identifier
  of a variable, a field, a parameter or a method): the ancestors of the type are kept; `addArrayLength` (new)
- tests: `ChainMembersTokensTest` (new, end-to-end)

## Problem

First part of step 7 (type inference of the chains; section 6 of `docs/resume.md`). In dataset v1 the cause
`member of a chain` of `missing_tokens.py` covers 485 missing steps (451 oracles, jsoup 294 steps). Three shapes:

| Shape | Steps | Example |
|---|---|---|
| a member of the result of a call | 307 (jsoup) | `els.get(0).` ▸ `attr` |
| the `length` of an array | 123 (eec, uuid) | `expectBytes.` ▸ `length` |
| a member after a cast | 44 (crawler, cryptofs) | `((SimpleRobotRules) rules).` ▸ `getRobotRules` |

Two defects:

1. **The type parameters of the ancestors were lost.** For an identifier whose type is resolved, the values of the type
   parameters of the type and of its ancestors are recorded, so that a type variable returned later (`E get(int)`)
   stands for its value. The call `resolvedType.asReferenceType().getAllAncestors()` was made but its result discarded:
   the list of ancestors stayed empty. A variable of a class extending a generic class (jsoup `Elements extends
   ArrayList<Element>`) left `E` unknown, and nothing was offered after `els.get(0).`.
2. **No `length` for the arrays.** The type of an array is replaced by its component type (so that the members of an
   element are offered after `values[0].`: the access to an element is not tracked), and the field `length` of the
   arrays, declared by no class, was never offered. It was offered by chance after an array of `String`
   (`String.length()`).

Casts are not part of this step (Step 32): the state machine of the tokens stage has no notion of a cast.

## Fix

1. Keep the ancestors: `ancestors = resolvedType.asReferenceType().getAllAncestors();`.
2. When the type of the identifier is an array, add the field `length` (`int length`, a declarator with no declaring
   class, JLS 10.7) to the members offered, with those of the component type.

## Tests

`ChainMembersTokensTest` (end-to-end): `nodes.get(0).` ▸ `text` (`Nodes extends ArrayList<Node>`), `nodes.first().`
▸ `text`, `values.` ▸ `length` (`int[]`), `nodes.names().` ▸ `length` (`String[]`). Checked on 2026-11-09: against the
code of Step 30 the test fails (`text` after `nodes.get(0).`, `length` after `values.`). All 440 tests pass.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles; the oracles stage is unchanged), 2026-11-09:

| | complete | loses a step | dropped |
|---|---|---|---|
| Step 30 | 1,139 | 160 | 3 |
| Step 31 | 1,172 | 127 | 3 |

**0 worse.** 33 oracles complete: 25 by `length` (eec 17, `ReflectUtilTest` among them; jsoup `SoftPoolTest` 4; esapi 2;
uuid 2), 8 by the type parameters of the ancestors (jsoup: `els.get(0).nextElementSibling()` 2,
`doc.select(...).get(0).childNode(0)` 3, `errorList.get(0).getPosition()` 3).

41 oracles keep their status with other token steps, compared step by step with the outputs of Step 30:
- candidates: only additions: `length` after an array (eec 23 steps, uuid 3, esapi 2); in jsoup, the members of the
  element type now resolved (`html`, `reparentChild`, ..., and the methods of `Object`, private ones included:
  `registerNatives`, a known defect of step 9);
- contextual information: the classes returned by a call are now known (cryptofs `useTokens.get(` on a
  `ConcurrentMap<Path, RealUseToken>`: `RealUseToken`; jsoup `els.get(0)`: `Element`, `Tag`), and `length` after an
  array is a field (`FIELD_CALL`) instead of the method `length()` of `String` (plexus `expected.length`) or nothing
  (eec `fields.length`).
