# Step 40 – Chains: an array as the value of a type variable, the methods of Object on an interface

**Date:** 2026-11-13
**Branch:** `22-test-processing-refactoring` (on top of Step 39)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: the member types of an identifier (the values of a
  type variable before the arrays); `collectMembersOfTypes` (an interface: the public methods of `Object`);
  `publicObjectMethods` (new)
- tests: `ChainValuesTokensTest` (new, end-to-end)

## Problem

Re-count on the corpus after Step 39 (`missing_tokens.py`): 36 oracles lose a step, 32 by the causes `member of a
chain` (26) and `member of a known type` (6). Two shapes cover 29 of them:

| Shape | Oracles | Example |
|---|---|---|
| A. an array that is the value of a type variable | 15 (eec 14, esapi 1) | `List<String[]> strings`: `strings.get(0).` ▸ `length`; `ArgumentCaptor<char[]>`: `immuneCapture.getValue().` ▸ `length` |
| B. the methods of `Object` on a value whose type is an interface | 14 (jsoup 9, eec 5) | `Connection.KeyVal`: `data.get(0).` ▸ `toString`; `Cache<K, V> hot`: `hot.` ▸ `toString` |

A. The field `length` (Step 31) was added when the type of the identifier was an array, checked **before** a type
variable was replaced by its values: `E get(int)` of `List<String[]>` is the type variable `E`, its value `String[]`
was then neither recognised as an array nor given members (not a reference type).

B. An interface has the public methods of `Object` (JLS 9.2), but the symbol solver does not list them among its
methods (`getAllMethods()`: the ancestors of an interface do not include `Object`).

Left out of this step (7 oracles): a lambda parameter of a block lambda (`createEvent.ifPresent(e2 -> { ... })`,
cryptofs 3), a `var` assigned from `assertDoesNotThrow(() -> ...)` (cryptofs 1), the variables of nested blocks (3).

## Fix

A. For each type of the identifier, the values of a type variable first (`TypeParameterValues.valuesOf`), then for each
value: an array adds `length` and its component type (as in Step 31), any other type is kept. For a type that is not a
type variable the path is the one of Step 31.

B. When the declaration of a reference type is an interface, the public instance methods of `java.lang.Object`
(`toString`, `equals`, `hashCode`, `getClass`, `notify`, `notifyAll`, `wait`; not the protected `clone` and `finalize`)
are added to its methods, through the same access filter (Step 37). The void ones are offered only where a call needs
no value (Step 39).

## Tests

`ChainValuesTokensTest` (end-to-end): `rows.get(0).` ▸ `length`, `isEmpty` (`List<String[]>`); `box.get().` ▸ `length`
(`Box<char[]>`, a class of the project); `pair.` ▸ `key`, `toString`, `equals`, `hashCode`, `getClass`, not `clone`,
`finalize` (an interface of the project); `pairs.get(0).` ▸ `key`, `hashCode` (an interface as the value of a type
variable). Checked on 2026-11-13 against the code of Step 39: it fails with the symptoms of the corpus (`length` after
`rows.get(0).` and `box.get().`, `toString` after `pair.`, `hashCode` after `pairs.get(0).`). 453 Java tests pass.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-13, against Step 39 (`tmp/ab/corpus-step39-out`,
`corpus-step40-out`): complete **1,263 → 1,293** (eec +20, jsoup +9, esapi +1), **0 worse**, loses a step 36 → 6,
dropped 3. **0 target tokens removed.**
- the 30 oracles: eec `CSVUtilTest` `strings.get(i).length` 14, `CacheTest` `hot.toString()` 5 (`Cache<K, V>`) and
  `e.toString()` 1 (`Cache.Entry<K, V>`, an interface too), jsoup `FormElementTest` `data.get(i).toString()` 9, esapi
  `immuneCapture.getValue().length` 1;
- candidates (267 steps): additions only: `getClass` 266, `toString` 260, `equals`/`hashCode` 108 (after the values of
  interfaces that do not declare them), `length` and the members of `String` after the elements of `List<String[]>`
  (14 steps; the static members of `String` too, as after any `String[]` value since Step 37: Java allows them after a
  value);
- contextual information (199 steps): additions only (`invokedMethods`, `fields`, `invokedConstructors`,
  `returnClasses` of `String` after a call returning a `String[]` through a type variable, e.g. crawler
  `map.get(LinkAttributes.HREF)` of a `Map<String, String[]>`); the type of the information of 36 steps changes with
  the member now found (`METHOD_RETURN` → `FIELD_CALL` for `length`, → `METHOD_CALL` for `toString`).

## Left

The 6 oracles that lose a step on the corpus: cryptofs `e2` (a parameter of a block lambda, 3), cryptofs `attr` (`var`
from `assertDoesNotThrow`, 1), steve `protocol` (2, a nested block).
