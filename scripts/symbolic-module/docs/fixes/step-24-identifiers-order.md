# Step 24 – Fields and local variables in the order of their declarations

**Date:** 2026-11-05
**Branch:** `22-test-processing-refactoring` (developed in a worktree on top of Steps 21 and 23; committed after Step 23)
**Files changed:**
- `src/main/java/star/tracto/utils/LinkedIdentitySet.java` (new)
- `src/main/java/star/tracto/preprocessing/utils/MemberOrder.java` (new; `TypeParameterValues.inFixedOrder` of Step 21
  delegates to it)
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java` (both `updateHashMapOfFieldTypeIdentifiers`,
  `collectResolvedMethodDeclaration`)
- tests: `IdentifiersOrderTest` (new, 5 tests), `MemberOrderTest` (new, 2 tests)

## Problem

The development check of Step 23 found the tokens datasets of the same code different from one run to another in the
order of `nextPossibleProjectTokens` (steve 36 of 1,588 token steps, cryptofs 661 of 7,703, ffmpeg 148 of 3,770): the
same candidates, the fields of a type in another order. Example, after `client.` (a `java.net.Socket`) the methods in
the same order, then `connected, shutOut, impl, factory, ...` in one run and `connected, factory, impl, shutOut, ...`
in the other.

Both `TokensUtils.updateHashMapOfFieldTypeIdentifiers` (fields of a type; local variables of a method) collected the
declarators in `Collections.newSetFromMap(new IdentityHashMap<>())` (by identity: equal declarators of different
classes are distinct) and iterated it to fill a `HashMap` by name. A set made from an `IdentityHashMap` is iterated in
the order of the identity hash codes, which change with any change of the allocations (in the same way, the order of
the methods depended on them before Step 21); names in the same bucket of the `HashMap` keep the order of insertion,
so their order in the candidates changed.

The candidates are written in the prompt of the model in their order (`scripts/prompt/src/EWash.py`): the dataset must
be reproducible. Decision of the author (2026-11-05): a deterministic order in the dataset; if the order is to be
randomized against positional shortcuts, it is shuffled when the prompt is built (Python scripts), not here.

A second source, found by the first development check of this step (methods in another order in 78 token steps of
ffmpeg): when `getAllMethods()` of a type fails (often: a signature that cannot be parsed, a symbol not solved), the
declared methods are collected instead (`collectResolvedMethodDeclaration`), iterated in the order of
`getDeclaredMethods()`, a hash set ordered by identity hash codes; Step 21 had ordered only the methods of
`getAllMethods()`.

## Fix

`MemberOrder`: one fixed order for the methods, by declaring type, then signature (`getAllMethods()`, as in Step 21) or
name (declared methods), then position in the source (overloads whose parameter types are not solved), then signature.
`LinkedIdentitySet`: distinct by identity like before, iterated in the order of insertion, that is the order of the
declarations (`JavaParserUtils.collectExistingVariables` visits them in source order). The candidates are the same; only
their order becomes the order of the declarations, in every run.

## Tests

`IdentifiersOrderTest` (5 tests; the three order tests failed before the fix, each with a different order): eight names
with the same `String.hashCode` (made of `Aa` and `BB`) declared as fields, as fields of one declaration and as local
variables come out in the order of the declarations (an order of the identity hash codes matches it once in 40,320);
two equal declarations of different classes are still both collected. `MemberOrderTest` (2 tests, did not compile
before): the declared methods (with overloads whose parameter types are unknown) and the method usages come out in the
same order for 20 shuffled orders of the set. Full suite: 380 tests, 0 failures.

## Verification on real projects

- Development check (2026-11-05): the jar of Steps 21 + 23 + 24 (`tmp/ab/jar-step24-fields.jar`) on steve, cryptofs
  and ffmpeg, run twice: as usual and with `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` (all the identity hash
  codes equal: any order of identity hash codes changes). Expected: the two runs identical (after normalizing the names
  of the anonymous classes and the label of the run); against Step 23, the same candidates (identical up to order).
  Results (2026-11-05, after the fix of the declared methods):
  - the datasets (oracles and tokens) of the two runs are byte for byte identical on the three projects (0 of 13,061
    token steps differ), and so are the statistics of the oracles stage;
  - the statistics of the tokens stage of steve and ffmpeg are identical; those of cryptofs differ in 13 error messages
    only, all printing an identity hash (`JavaParserRecordDeclaration$ImplicitGetterMethod@2e8c8eab` / `@1`) or a
    message derived from one (`Range [131, 126) out of bounds for length 126` / `length 120`: a substring of such a
    message, a defect to schedule with the pre-existing ones); same targets, same `Unexpected token` messages.
  The output no longer depends on the identity hash codes on these projects.
  - against Step 23 (`step23-dev-B`): the same content, in another order (steve 30 token steps, ffmpeg 169, cryptofs
    623: `nextPossibleProjectTokens`, and in cryptofs also the nested lists of `contextualInfos`); 0 differences once
    every list is sorted.
- Regression corpus and determinism of Steps 21–24 together: see `docs/fixes/step-22-test-class-members.md`
  (verification) and `docs/resume.md`; the full nine-project test runs before the training.
