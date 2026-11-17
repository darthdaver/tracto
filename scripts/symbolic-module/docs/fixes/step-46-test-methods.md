# Step 46 – The test methods of a superclass are not candidates

**Date:** 2026-11-17
**Branch:** `22-test-processing-refactoring` (on top of Step 45)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestClassScope.java`: `isTestMethod` (the annotations of a test
  method of JUnit, by simple name), used by `addMethods`
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `hasAnnotation`, the annotation filter of
  `collectMethodUsages` and `collectResolvedMethodDeclaration` (`"Test"` stands for `isTestMethod`)
- tests: `TestMethodCandidatesTokensTest` (new, end-to-end)

## Why this step, and not the lambdas with several parameters

Step 45 left the lambdas with several parameters (`(a, b) ->`) or a parenthesized parameter (`(a) ->`) uncovered,
and it was proposed as Step 46. Measured before writing it: the 9,148 oracles of dataset v3 have none (214 `() ->`,
already handled, and the one `el2 ->` of Step 45), and neither have the 117,824 one-line assertions of the test
sources of the nine repositories (`grep` of `assert…(` lines with a `( … ) ->` lambda, typed parameters included).
A fix with no case in the data could not be checked on it: it stays a known limit, recorded in
`docs/missing-candidate-tokens.md`, to take up if the generation over the 4,517 repositories shows it.

## Problem

A pre-existing defect of `docs/resume.md` (section 6, item 10): the test methods annotated `@ParameterizedTest` (or
`@RepeatedTest`, `@TestFactory`, `@TestTemplate`) are offered as candidates: only `@Test` was excluded, by its exact
name in `TokensUtils` (so `@org.junit.Test` was not either there). Measured while writing the test:

- the test methods of the test class: the split class keeps the prefix of each test with an assertion
  (`escapeCssIdentifier_split_0`), with its annotations: the ones with `@Test` were excluded, the parameterized and
  repeated ones were not (they are also listed among the invoked methods of the contextual information);
- the test methods of a superclass of the test class, through both paths (the test class resolved as a whole:
  `collectMethodUsages`; method by method when a signature cannot be resolved: `TestClassScope.addMethods`);
- since Step 39 a void method is offered only where a void call can be written (the body of a lambda,
  `assertDoesNotThrow(() -> ` ▸ `inheritedParameterizedTest`), and a test factory returns a value, so it is offered
  everywhere (`assertEquals(two, ` ▸ `inheritedTestFactory`).

A test method is not called by an assertion (JUnit calls it): it is noise among the candidates.

## Fix

One predicate, `TestClassScope.isTestMethod`: a method annotated with `Test`, `ParameterizedTest`, `RepeatedTest`,
`TestFactory`, `TestTemplate` (JUnit 5) or `Theory` (JUnit 4 theories), by the simple name of the annotation (a
qualified annotation, `@org.junit.jupiter.params.ParameterizedTest`, too). `TestClassScope.addMethods` uses it; the
annotation filter of `TokensUtils` (every caller passes `("Test", false)`) uses it for `"Test"` and compares the
simple name for any other annotation.

Not covered: a custom annotation meta-annotated with `@Test` (it would need to resolve the annotation).

## Tests

`TestMethodCandidatesTokensTest` (end-to-end): a superclass `BaseTest` with a test method of each annotation (simple
and qualified `@ParameterizedTest`, `@Test`, qualified `@org.junit.Test`, `@RepeatedTest`, `@TestFactory`,
`@TestTemplate`, `@Theory`) and a helper `base()`; in the test class, a test of each annotation and a parameterized
test with an assertion (its prefix `parameterizedCheck_split_0` stays in the split class); a test class resolved as a whole (`CalcTest`) and one that is not
(`CounterTest`, a signature with a type of JUnit); after `assertEquals(two,` and after `assertDoesNotThrow(()->`:
`twice` and `base` are candidates, no test method is. Against the code of Step 45: 17 test methods offered (the test
factory at the root of both classes; in the lambda, 8 in the resolved class, the split prefix and every inherited one
but `@Test`, and 7 in the other, where JavaParser's `getAnnotationByName` already matched the qualified
`@org.junit.Test`). A first version of the test had no test with an assertion in the test class (its prefix is the
case of the corpus, below). Each part checked by a mutation (each fails the test, only on its
path): `TestClassScope.addMethods` back to `@Test` (the unresolved class), the filter of `TokensUtils` back to the
exact name (the resolved class), the qualified name (`getNameAsString`: the two qualified ones), the set reduced to
`Test`.

465 Java tests pass.

## Verification

Corpus (`tmp/ab/corpus-check-step46.txt`): 1,302 of 1,302 complete, 0 worse; other candidates or contextual
information in 160 oracles (cryptofs 69, jsoup 46, crawler 35, steve 10), only removals, all test methods:

- the invoked methods of the contextual information: 1,346 entries removed (51 test methods of 19 split classes:
  `@ParameterizedTest` 1,148, `@RepeatedTest` 198; jsoup `TokenQueueTest` `escapeCssIdentifier_split_0`, steve
  `OcppVersionTest`, cryptofs `DirStructureTest`, ...), no entry added, no other field changed;
- the candidates: 10 steps lose a test method (the prefixes of parameterized tests, `testAssertWritable_split_0`,
  `consumeCssIdentifier_WebPlatformTests_split_0`, ...), no candidate added, no target among them.

Compared with the corpus output of Step 44 (the one of Step 45 was not kept): the other difference is the
`LAMBDA_PARAM` of Step 45 (23 steps), already reviewed there. Accepted.
