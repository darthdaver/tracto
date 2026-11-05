# Step 21 – Type parameters resolved to all their values, methods in a fixed order

**Date:** 2026-11-04/05 (the A/B ended on 2026-11-05)
**Branch:** `22-test-processing-refactoring` (on top of `2632474`, Step 20)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TypeParameterValues.java` (new)
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: the four places that resolved a type variable with
  `typeParametersReferences.get(key).get(0)`; `collectMethodUsages` iterates the methods in a fixed order
- tests: `TypeParameterValuesTest` (new, 5 tests), `GenericChainTokensTest` (new, end-to-end), `TokensFixture` (new:
  the fixture repository of the end-to-end tests, shared with `TokensDatasetTest`; it gives the JDK 21 sources to the
  symbol solver)

## Problem

Found during the smoke A/B of the test class step (2026-11-04): an oracle of crawler-commons
(`SiteMapParserTest`, `...iterator().next().` ▸ a member of the element type) was complete with one jar and lost with
another, although the change between them had nothing to do with it.

Each step of a chain records the values of the type parameters it meets. A type parameter can get several values:
`iterator()` is declared in `Collection<E>` and, inherited, in `Iterable<T>`, so after `items().iterator()` the type
parameter `E` of `Iterator` gets the element type and the unresolved type variable `T`. A type variable was then
resolved to the **first** recorded value, and the order of the values came from `getAllMethods()`, a hash set ordered
by the identity hash codes of the method declarations: the order changed with anything allocated before (another
oracle, another class, a change of the code). The type of `next()` was then the element type in some runs and `T` in
others, and its members were offered or not.

Proof: with `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` on both sides (all identity hash codes equal), the two
jars gave the same result on that class (and both did better: 111 complete oracles instead of 107).

## Fix

- `TypeParameterValues.valuesOf`: a type variable stands for all the **resolved** values recorded for its type
  parameter (the type variables among them skipped; if none is resolved, the values unchanged), at the four places that
  took `get(0)` (the values of the type parameters of a step, the types whose members follow).
- `TypeParameterValues.inFixedOrder`: `collectMethodUsages` iterates the methods by declaring type, then signature,
  instead of the order of the set. (Step 24 extends the fixed order to the other collections of members.)

## Tests

- `TypeParameterValuesTest` (5 tests): resolved values only, in their order; the values unchanged when none is
  resolved; a type that is not a type variable stands for itself; the fixed order of the methods does not depend on the
  order of the set.
- `GenericChainTokensTest` (end-to-end): the members of the element type are offered after
  `items().iterator().next().`.

## Verification on real projects (A/B)

A = `2632474` (Step 20), B = Step 21; nine projects, tokens stage on all of them, side by side on the same machine
(2026-11-05, `-j 4` each, `-Xmx24g`; `tmp/ab/step21-A`, `tmp/ab/step21-B`).

- Lost oracles (`missing_tokens.py`): 20.8% on both sides (7,170 complete of 9,049; dropped 589, losing a step
  1,290); missing tokens 2,008 → 2,007 (crawler 196 → 195).
- `compare_tokens.py`: oracles datasets identical on the nine projects; no oracle changes status (0 regressions,
  0 fixed); the candidates of 8 token steps gain names and none loses any (crawler 3, cryptofs 5: the methods of
  `Object`, offered where a type variable was resolved to the unresolved `T` before).

The step does not recover oracles on the nine projects as a whole: its value is that the result of an oracle no
longer depends on what was processed before it. Before this step every A/B had spurious differences; the remaining
order dependences (fields, local variables, declared methods) are fixed by Step 24.
