# Step 39 – The void methods only where a call needs no value

**Date:** 2026-11-12
**Branch:** `22-test-processing-refactoring` (on top of Step 38)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/VoidCalls.java` (new): where a call of a void method is valid in an
  assertion; whether all the methods of a name are void
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `methodCandidates` (new), the names of the methods
  offered as candidates, used by the training and the inference loops
- tests: `VoidCallsTest` (new, unit), `VoidMethodsTokensTest` (new, end-to-end)

## Problem

Candidate noise, larger since Step 38 resolves the dependencies: the assertions of JUnit imported statically
(`assertEquals`, `assertNull`, ... in about 4,277 corpus steps) and the methods of `Object` (`wait`, `notify`,
`notifyAll`, `finalize`, the private natives `registerNatives`/`wait0` of the JDK sources) were offered in every
position, as were the void methods of the test class (`setUp`, the helpers, the split test methods) and of the types
of the values (`List.clear`, `Node.remove`). A call of a void method has no value: inside an assertion it is valid only
as the body of an expression lambda (`assertThrows(E.class, () -> clock.stop())`) or as a method reference
(`clock::stop`). No target is lost by this cause (the tests compile); the lists are longer than they need to be.

Not changed, by decision: the annotation types (`Test`, `Override`, `Nullable`) stay among the class names: they are
valid in an expression as `Anno.class` (`isAnnotationPresent(MyAnno.class)`) and through their nested types and
constants (`TestInstance.Lifecycle.PER_CLASS`). None of the 9,148 targets of dataset v2 uses one, but removing them
could lose a target at inference time in the projects that define annotations; a filter by simple name would be wrong
anyway (`Tag` of jsoup is a class).

## Fix

`VoidCalls.allowed(previousTokens)` walks the tokens of the partial oracle back from the position: the groups between
parentheses, brackets and angle brackets are skipped; identifiers, literals, dots, `this`, `super`, `new`, `class`
continue the receiver chain; `->` ends it (the body of a lambda: void allowed); `::` right before the position (a
method reference: allowed); any other token (an open parenthesis or bracket, a comma, an operator) makes the position a
value (not allowed). When in doubt the void methods are kept (a `>` is read as the end of type arguments).

`methodCandidates` leaves out a method name only where void calls are not allowed and when **all** its methods are
resolved as void: a name with an overload that is not void (`Clock.log()` and `log(String)`, Jupiter's generic `fail`)
or an unresolved return type stays. Only the list of candidates changes: the collectors, the contextual information
and the order of the remaining candidates are the same.

## Tests

- `VoidCallsTest`: the bodies of lambdas (a chain, `new X().`, a cast, an array element, a string literal, `this.`,
  type arguments, a nested lambda, two parameters), a method reference; the values (an argument, an operand, after `!`,
  an argument or an operand inside the body of a lambda, after the body of a lambda, an array index).
- `VoidMethodsTokensTest` (end-to-end): in an argument, not the void helper of the test class nor `wait`/`notify`; after
  `clock.`, not `stop`/`reset`, but `log` (one overload returns a value); in the body of a lambda, `stop`, `prepare`,
  `reset` after `new Clock().` offered, and the targets found; an argument inside the body of a lambda is a value.

Checked on 2026-11-12 against the behaviour of Step 38 (`allowed` always true): both tests fail. With `->` not
ending the chain, the end-to-end test fails (the targets `stop`, `prepare`, `reset` missing). 452 Java tests pass.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-12, against Step 38
(`tmp/ab/corpus-step38-out`, `corpus-step39-out`): statuses unchanged (1,263 complete, 36 lose a step, 3 dropped),
**0 worse**, **0 target tokens removed**. 5,284 steps lose candidates, none gains one, contextual information
unchanged. The steps with candidates offer 127.6 names on average instead of 141.8 (−10%). Removed: `wait`, `notify`,
`notifyAll` (4,981 steps), the JUnit assertions (4,233; `assertThat` 1,488, `assertAll` 2,776), `finalize`,
`registerNatives`, `wait0`, `setUp`/`tearDown`/`before`, the split test methods, `forEach`, `clear`, `sort`,
`println`, ... `fail` is removed in the JUnit 4 projects (`Assert.fail` is void: eec, esapi, ffmpeg) and kept in the
JUnit 5 ones (`<V> V fail(...)`). The 108 changed steps with a lambda in their prefix are all inside the arguments of a
call of the body (`() -> QueryParser.parse(`).

## Left

- The private members of `Object` inherited at the root (`wait0`, `registerNatives` of the JDK sources) are offered by
  the root identifiers (the access filter of Step 37 applies after a value): void, so gone here, but the private
  methods returning a value would still be offered.
- The annotation types (see above), kept on purpose.
