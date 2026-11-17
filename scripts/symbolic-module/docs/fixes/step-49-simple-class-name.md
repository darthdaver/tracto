# Step 49 – The simple name of a class with qualified type arguments

**Date:** 2026-11-17
**Branch:** `22-test-processing-refactoring` (on top of Step 48)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `simpleClassName`, used by the three places that
  took the simple name of the class of a key of the class references
- tests: `SimpleClassNameTest` (new)

## Problem

A defect of the list of `docs/resume.md` (section 6, item 10): "an error path takes a substring of a message that
contains an identity hash" (`Range [131, 126) out of bounds`, cryptofs, dataset v1; Step 24). The identity hash came
from the description of an object used as a key (the accessors of the records, fixed before v2); it does not occur in
v2 nor v3. The substring that failed is still there, in three places: the simple name of a class was the text between
the last dot and the last `<` of its key, so a key with a qualified type argument (`java.util.List<com.acme.Item>`: its
last dot is after the `<`) threw `StringIndexOutOfBoundsException` (the error of the target, an oracle lost).

## Fix

`simpleClassName`: the text before the first `<`, then after its last dot.

## Tests

`SimpleClassNameTest`: `com.acme.Item`, `Item`, `java.util.Map.Entry`, `java.util.List<T>`, `java.util.List<com.acme.Item>`
▸ `List`, `java.util.Map<java.lang.String, java.util.List<com.acme.Item>>` ▸ `Map`. With the former expression in
the helper: `StringIndexOutOfBoundsException: Range [24, 14) out of bounds for length 29` (the error of v1).

469 Java tests pass.

## Verification

Corpus (`tmp/ab/corpus-check-step49.txt`): IDENTICAL to the expected results of Step 48 (1,302 complete): no key of the
corpus has a qualified type argument.
