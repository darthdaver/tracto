# Step 36 – The token steps of a target do not depend on the targets processed before it

**Date:** 2026-11-11
**Branch:** `22-test-processing-refactoring` (on top of Step 35)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `resetDatapointState` (new), called at the start of
  each datapoint, in the generation of the dataset (`generateTokenSteps`) and in the inference (`generateInference`)
- tests: `ProcessingHistoryTokensTest` (new, end-to-end)

Decided by the author on 2026-11-11: before the dataset v2, fix the defects that make the candidates wrong or depend on
the run (defect 1 of step 9, "the candidates depend on the processing history"), then the full test.

## The cause

The tokens stage keeps the state of the processing of a target in static fields of `TokensUtils`. Checked one by one:

- `streamFlag`, "the current value is a `Stream`": set when a value of type `java.util.stream.Stream` is reached
  (`list.stream()`, `Arrays.stream(...)`), cleared only by a `->`. It was never cleared at the start of a datapoint: after
  a target with a stream and no lambda after it (`list.stream().count()`, `Arrays.stream(a).map(X::toString)`), every
  later target of the run (the same test class and the next ones, in the same JVM) was processed as in a stream:
  - after a `(` whose argument is not a candidate (a literal), the placeholder `LAMBDA_PARAM` was offered, and the step
    had the target `LAMBDA_PARAM:"rel"` instead of `STRING_LITERAL:"rel"`;
  - a call was recorded as `LAMBDA_CALL` instead of `METHOD_CALL`, so its `)` did not turn it into `METHOD_RETURN`: the
    contextual information after `getHref()` lost the class returned (`URL`) and its members, and kept the ones of the
    lambdas;
  - the lambda identifiers and the type parameters of the frame were carried into the new frames.
  This is also the defect "the `LAMBDA_PARAM` placeholder is offered outside lambdas" (eec `CacheTest`,
  `assertEquals(hot.toString(`): one cause.
- `unsolvableTokens` (the tokens that cannot be processed further) and `unsolvableWarn`: never cleared at the start of a
  datapoint either. The generation of the dataset only writes them; the inference reads them (a generated token that
  was unsolvable in an earlier target could stop a later generation). `unsolvableWarn` was cleared in the inference
  only.
- `rootTokenIdentifiersCollector`, `rootTokenReferencesCollector`: new for each datapoint. Their shallow copies
  (`cloneRootTokenReferences`) share lists between the frames of one target (intended for the lambdas of a stream),
  never between targets.
- The paths of the test class and of the repository, the errors of the class: set for each test class. The cache of the
  classes of `java.lang` (keyed by the files read) and the symbol solver of the repository: functions of their inputs.
- The tokens stage does not change the compilation units it shares between the datapoints of a class.

## Fix

`resetDatapointState` clears `streamFlag`, `unsolvableTokens` and `unsolvableWarn` at the start of each datapoint, where
the root collectors are created, in both paths.

## Tests

`ProcessingHistoryTokensTest` (end-to-end): a test class with a lambda on a stream (`list.stream().anyMatch(s ->
s.` ▸ `isEmpty`, still offered), then `assertEquals(1, list.stream().count())`, then `assertEquals(1, Math.abs(1))`: in
the last one, no `LAMBDA_PARAM` after `Math.abs(`, no target `LAMBDA_PARAM:…`, no `LAMBDA_CALL`. Checked on 2026-11-11:
against the code of Step 35 the test fails (`LAMBDA_PARAM` offered after `Math.abs(`). All 444 tests pass.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-11: statuses unchanged (complete 1,209, loses a
step 90, dropped 3), 0 worse. 11 oracles have other token steps, all crawler, all after the target
`NewsAttributesTest.testNewsAttributesAsMap_split_6` (`assertArrayEquals(Arrays.stream(attributes.getGenres()).map(…`):
the rest of `NewsAttributesTest` (`testNullNewsAttributesAsMap` 7) and `LinkAttributesTest` (4), processed after it.
Compared step by step with the outputs of Step 35 (`tmp/ab/corpus-step35-out`, `corpus-step36-out`):
- candidates (8 steps): only `LAMBDA_PARAM` removed;
- targets (4 steps): `LAMBDA_PARAM:"rel"` → `STRING_LITERAL:"rel"` (and `"params.rel"`, `"hreflang"`,
  `"params.hreflang"`);
- contextual information (25 steps): `LAMBDA_CALL` → `METHOD_RETURN` with the returned class and its members (`URL`,
  `String`, `Map`).

Proof that the new output is the output without history: `LinkAttributesTest` alone (`dev_tokens.py`, no earlier
class in the JVM) gives byte for byte the same dataset with the jars of Step 35 and of Step 36, and its four datapoints
are equal to those of the corpus check of Step 36 (and differ from those of Step 35).

No `LAMBDA_PARAM` is left in the corpus outside a stream (8 steps with the Step 35 jar, all of this cause). The corpus
runs few classes per JVM; the full runs process the whole project in one JVM, so more datapoints change there (the
full test before the dataset v2 measures them).

## Left

Step 9 defects decided for v2: the private members of the JDK classes offered after a value of that type; the instance
and protected methods offered after the name of a class (the modifier rules combined with OR).
