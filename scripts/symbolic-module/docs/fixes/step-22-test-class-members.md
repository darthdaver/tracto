# Step 22 – Fields and methods of the test class and of its superclasses

**Date:** 2026-11-04/05
**Branch:** `22-test-processing-refactoring` (on top of Step 21)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestClassScope.java` (new): the members inherited by the test class
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `updateHashMapOfTestClassFieldIdentifiers`,
  `updateHashMapOfTestClassMethodIdentifiers` (new), used where the members of the test class are collected (root
  identifiers of a datapoint, and the identifiers restored after a `this.`-less step)
- tests: `TestClassScopeTest` (new, 4 tests), `TestClassMembersTokensTest` (new, 3 end-to-end tests)

## Problem

The measurement after Step 20 ranked the members of the test class among the first causes of lost oracles (cause
`test class member` of `missing_tokens.py`): fields and helper methods of the test class, or of a base test class,
used in the target (`assertEquals(EXPECTED, ▸ helper(...))`, `assertTrue(▸ server.isRunning())` with `server` a field
of an abstract base test class).

Three causes in `TokensUtils`:

- the fields of the test class were collected with modifier rules (public, protected, private): the package-private
  fields (no modifier, the most common in tests) were never offered;
- the inherited fields were never collected (only `tc.getFields()`, the fields declared in the test class);
- the methods were collected with `tc.resolve().getAllMethods()`: when one signature of the test class or of an
  ancestor cannot be resolved (a type of a dependency missing from the classpath, frequent in test classes), the whole
  call failed and no method of the test class was offered.

## Fix

`TestClassScope` collects what Java makes visible within the test class:

- the fields declared in the test class, whatever their access modifier, then the fields inherited from its ancestors
  (superclasses and interfaces, transitively, the closest first, with the constants of the interfaces), following the
  access rules: the private members of an ancestor are not inherited, its package-private members only within the same
  package;
- the methods: `getAllMethods()` when the test class resolves as a whole (as before); otherwise the methods are
  collected one by one from the declarations of the test class and of its ancestors (`methodsOneByOne`): a method whose
  signature cannot be resolved is still offered by its name;
- an ancestor that cannot be resolved (for example a class of a dependency) is skipped, with the ones above it.

The test methods (`@Test`) are still excluded.

## Tests

- `TestClassScopeTest` (4 tests; they did not compile before the fix): package-private members of an ancestor inherited only within the same package; the
  fields of the ancestors collected transitively, closest first, with the constants of the interfaces; an ancestor
  that cannot be resolved is skipped; a method whose signature cannot be resolved is offered by its name.
- `TestClassMembersTokensTest` (3 end-to-end tests, oracles and tokens stages on a fixture repository): the members of
  the test class and of its superclasses are offered; the fields are offered whatever their access modifier; the
  methods are offered when a signature of the test class cannot be resolved.

Checked on 2026-11-05 against the code of Step 21: the 3 end-to-end tests fail (`Unexpected token: base / calc /
shared / total / twice not found in the collected list of candidate tokens`).

## Verification

Under the loop decided on 2026-11-05 (`docs/resume.md`, section 4): unit tests, then the regression corpus
(`tools/ab/corpus.py`). Result: _CORPUS_STEP22_.
