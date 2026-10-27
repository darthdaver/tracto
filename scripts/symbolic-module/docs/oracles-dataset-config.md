# Oracles dataset configuration

This document describes the configuration file of the oracles dataset generation: what each key means, which values
it accepts, where the code reads it, and its current status.

**File:** `src/main/resources/dataset/oracles-dataset_config.json`
**Loaded into:** the record `star.tracto.data.records.OraclesDatasetConfig` (via Jackson, `FilesUtils.readJSON`)
**Passed by:** `bash/oracles-dataset.sh`, as the 7th argument of the jar (`oracles <id> <repo> <output> <base> <java> <config>`)

Last reviewed: 2026-10-26, on branch `22-test-processing-refactoring` (the `try-catch-finally` key was redefined in Step 9).

## How the file is loaded

Verified by loading test files with the real loader:

| Situation | Result |
|-----------|--------|
| Unknown or misspelled key (e.g. `"assert-throws"`) | **Error** at startup (`UnrecognizedPropertyException`, listing the valid keys) |
| Invalid value (e.g. `"split": "assertions"`) | **Error** at startup (`IllegalArgumentException: Unknown value: assertions`) |
| Missing key | **No error.** The value becomes `null` (enums and strings) or `false` (the two booleans). What `null` means depends on the key (see the table below): usually it silently selects one of the branches. |

Values are case-sensitive and lower case (`"assertion"`, not `"ASSERTION"`).

## Current committed configuration

```json
{
  "split": "assertion",
  "try-catch-finally": "to-assert-throws",
  "statement": "keep",
  "condition": "keep",
  "assertion": "mask",
  "target": "assertion",
  "mask-placeholder": "<MASK_PLACEHOLDER>",
  "no-assertion-target-placeholder": "<NO_ASSERTION>",
  "integrate-auxiliary-methods": true,
  "keep-statements-after-last-assertion": false
}
```

The file changed on 2026-10-26 (Step 9: `try-catch-finally` from `standard` to `to-assert-throws`). Two keys are
missing: `num-assertions-match` (→ `null`, the check is disabled). The key `assertThrows` was removed in Step 10.

## Summary of the keys

| Key | Values | Default when missing | Status |
|-----|--------|----------------------|--------|
| `split` | `assertion`, `statement` | `null` → the splitter creates no split points, so no datapoints | Used |
| `target` | `assertion`, `statement` | `null` → every target is the `no-assertion-target-placeholder` | Used |
| `assertion` | `keep`, `mask`, `remove` | `null` → behaves as `remove` | Used |
| `statement` | `keep`, `mask`, `remove` | `null` → behaves as `remove` | Used only with `split = statement` and `target = statement` |
| `assertThrows` | — | — | **Removed** in Step 10 (see below): a configuration containing it is rejected |
| `integrate-auxiliary-methods` | `true`, `false` | `false` | Used |
| `keep-statements-after-last-assertion` | `true`, `false` | `false` | Used |
| `num-assertions-match` | `strict`, `loose` | `null` → no check | Used |
| `mask-placeholder` | any string | `null` | Used (with `mask`) |
| `no-assertion-target-placeholder` | any string | `null` | Used |
| `try-catch-finally` | `to-assert-throws`, `keep-try-catch` | **error** at startup (required since Step 9) | Used |
| `condition` | `keep`, `mask`, `remove` | `null` | **Never used** (not implemented) |

## How a datapoint is built

To understand the keys, it helps to know the three phases of `OraclesDataset.generate`:

1. **Normalization** (`TestUtils.normalizeTest`): rewrites each test case. Local variables get unique names,
   auxiliary methods are inlined (if `integrate-auxiliary-methods` is `true`), and the try/catch/fail pattern is
   converted into `assertThrows`.
2. **Splitting** (`TestUtils.splitTest`): each normalized test case is split into several *split test cases*, each one
   ending at a split point (an assertion, or every statement, depending on `split`).
3. **Datapoints** (`TestUtils.processSplitTestClass`): for each split test case, the last statement (or assertion)
   becomes the **target** (what the model must generate), and the rest becomes the **test prefix** (the model input),
   together with contextual information about the test class, the focal class and the invoked methods.

## Keys in detail

### `split`: where test cases are split

Read in `TestCaseSplitter.split` (since Step 10) and `TestUtils.processSplitTestClass`.

| Value | Meaning |
|-------|---------|
| `assertion` | A split test case ends at every assertion. A test case with *n* assertions produces *n* split test cases. The target is the last assertion of each split test case. |
| `statement` | A split test case ends at every statement. The target is the last statement of each split test case. |

Example with `split = assertion`, for a test case `a(); assertX(); b(); assertY();`:
split 0 = `a(); assertX();`, split 1 = `a(); assertX(); b(); assertY();`.

### `target`: what the model must generate

Read in `TestUtils.processSplitTestClass` and `processInvokedMethods`.

| Value | Meaning |
|-------|---------|
| `assertion` | The target is the assertion (with the `Assert.` / `Assertions.` qualifiers removed). With `split = statement`, a split test case whose last statement is not an assertion gets the target `no-assertion-target-placeholder`: the model must learn *whether* an assertion goes here. |
| `statement` | The target is the last statement, whatever it is (qualifiers are not removed). |

Special target: whatever the strategies, a target starting with `fail` becomes the label **`THROW EXCEPTION`**. Until
Step 10 the same label was produced by `assertThrows = flat` (see below). The tokens stage treats `THROW EXCEPTION` as a
special first token that the model can choose instead of an assertion name. The label is to be removed (decision of
2026-10-26, Step 14): a `fail(...)` will be a target like any other statement.

### `assertion` and `statement`: what happens to the target in the test prefix

Read in `TestUtils.processSplitTestClass`. `assertion` applies when the target is an assertion
(`target = assertion`); `statement` applies only to the combination `split = statement`, `target = statement`.

| Value | Test prefix |
|-------|-------------|
| `keep` | The target stays in the prefix (the input contains the answer). |
| `mask` | The target is removed and replaced by a block comment with the `mask-placeholder` text, e.g. `/*<MASK_PLACEHOLDER>*/`, at the end of the block where the target was. |
| `remove` | The target is removed, without a placeholder. |

Verified example (`split = assertion`, `target = assertion`, `assertion = mask`):

```
prefix : Calc calc = new Calc(); int x = -1; /*<MASK_PLACEHOLDER>*/
target : assertThrows(IllegalArgumentException.class, () -> calc.sqrt(x));
```

### `assertThrows`: removed in Step 10

Since Step 10 (`docs/fixes/step-10-splitter-by-truncation.md`) an `assertThrows` is an assertion like the others: it is a
split point and the target of its datapoint, whether it is written in the test case or generated by the normalization
(`E e = assertThrows(...)`). For `Calc calc = new Calc(); int x = -1; assertThrows(IllegalArgumentException.class, () -> calc.sqrt(x));`
the datapoint has prefix `Calc calc = new Calc(); int x = -1; <mask>` and target
`assertThrows(IllegalArgumentException.class, () -> calc.sqrt(x));`.

Until Step 10 the key selected between this behaviour (`standard`) and `flat`, the default when the key was missing (so
every dataset generated before Step 10 used it): the call inside the `assertThrows` was written as a plain statement at
the end of the prefix, the target was `THROW EXCEPTION`, and the expected exception was added to the `throws` clause of
the split test case (revealing it in the model input). `flat` failed on the `E e = assertThrows(...)` generated by the
normalization of the try statements, so those test cases produced no datapoints (D9).

### `try-catch-finally`: how try statements are normalized

Read in `TestUtils.parseNormalizeTestStatementsBlock` (since Step 9, `docs/fixes/step-09-try-statement-classification.md`).
The key is required: a configuration without it, or with one of the old values (`standard`, `flat`, `assert_throws`),
is rejected when it is loaded.

Every try statement reached by the normalization is first classified (`TryStatementClassifier`). Three shapes are
supported; a test case containing a try statement of any other shape is **discarded**: it is removed from the
normalized test class (so it produces no datapoints), saved in `discarded/normalized/...`, and the reason is recorded in
its statistics (`discardReason` in `test-stats-<project>.json`, total `discardedTestCases` in `statistics-<project>.json`).

| Shape | Example | Supported |
|-------|---------|-----------|
| `RESOURCES_WITHOUT_CATCH` | `try (R r = open()) { ... }` | yes |
| `FINALLY_WITHOUT_CATCH` | `try { ... } finally { ... }` | yes |
| `EXPECTED_EXCEPTION` | `try { ...; call(); fail(); } catch (E e) { ... }` (one catch clause, resources and finally allowed; the statement before `fail()` must be a method call or an object creation, possibly assigned to a variable) | yes |
| `MULTI_CATCH` | more than one catch clause | no |
| `UNION_CATCH` | `catch (A \| B e)` | no |
| `FAIL_NOT_LAST` | `fail()` in the try block, but not as its last statement | no |
| `UNSUPPORTED_FAILING_STATEMENT` | the statement before `fail()` is not a call (`if`, loop, `a / b`, …) or is missing | no |
| `MUST_NOT_THROW` | `fail()` in the catch block | no |
| `CATCH_WITHOUT_FAIL` | no `fail()`, non-empty catch block | no |
| `EMPTY_CATCH` | no `fail()`, empty catch block | no |

The two values decide what happens to the supported shapes:

| Value | Meaning |
|-------|---------|
| `to-assert-throws` (committed config) | The try statement is flattened: the resources, then the statements of the try block, then those of the finally block. An expected exception becomes `E e = assertThrows(E.class, () -> call(), ...)` followed by the statements of the catch block. |
| `keep-try-catch` | The structure is kept (resources, catch parameters), and the statements of the try, catch and finally blocks are normalized. **The splitter does not support try statements yet** (Step 10): with this value the test cases containing a try statement produce no datapoints for now. |

History: until commit `6db6ac15` (2026-03-09) the key had the values `standard`, `flat` and `assert_throws`, read by
the splitter; that commit moved the handling of try statements into the normalization and left the key unused until
Step 9. The old `flat` behaviour (the origin of the `THROW EXCEPTION` target and of the `throws` clause of the split test
cases) is dropped.

### `integrate-auxiliary-methods`: inline helper methods

Read in `TestUtils.normalizeTest` (passed to `parseNormalizeTestStatementsBlock`).

| Value | Meaning |
|-------|---------|
| `true` | A call to an auxiliary method of the test class (a method that is not a test, setup or teardown method) used as a statement, `helper(x);`, is replaced by the body of the method, with the parameters replaced by the arguments and the local variables renamed when they clash. A helper with early `return` statements is inlined as a labeled block (`helper: { ... break helper; ... }`). Calls whose returned value is used (`T v = helper(x);`) are not inlined. |
| `false` | Auxiliary methods are not inlined; the rest of the normalization (variable renaming, try/catch conversion) still runs. |

History: in the March baseline (`7a583a0`) the check was inverted (`if (!config.integrateAuxiliaryMethods())`), so with
the committed value `true` the **whole** normalization was skipped. The refactoring fixed it; Steps 1–5 made the inlining
work (`docs/fixes/`).

### `keep-statements-after-last-assertion`

Read in `TestCaseSplitter.split` (since Step 10).

| Value | Meaning |
|-------|---------|
| `true` | The statements after the last assertion of a test case (including an `else` branch or the rest of a loop after it) form one more split test case: the whole test case. |
| `false` | They are dropped. |

From reading the code (not verified): with `split = assertion` the extra split test case has no assertion as last
statement, so `processSplitTestClass` logs "No assertion found" and produces no datapoint for it.

### `num-assertions-match`

Read in `TestCaseSplitter.split` (since Step 10), only with `split = assertion`: the number of split points of the test
case is compared with the number of assertions it contains (until Step 10 the comparison was made for each block).

| Value | When the numbers differ |
|-------|-------------------------|
| `strict` | The test case is dropped (an exception is thrown and logged). |
| `loose` | A warning is logged and the split test cases are kept. |
| missing (`null`, current config) | Nothing happens. |

The numbers differ, for example, with AssertJ/Truth chains (`assertThat(x).isEqualTo(y)`), which are counted as
assertions but are not split points.

### `mask-placeholder`

The text of the comment that replaces the target in the prefix when `assertion` (or `statement`) is `mask`. Currently
`<MASK_PLACEHOLDER>`, printed as `/*<MASK_PLACEHOLDER>*/`.

### `no-assertion-target-placeholder`

The target used when there is no assertion to predict: with `split = statement` and `target = assertion`, for a split
test case whose last statement is not an assertion. It is also the initial value of every target, so a missing
`target` key makes every target equal to this placeholder. Currently `<NO_ASSERTION>`.

### `condition`: never implemented

Declared in `OraclesDatasetConfig` but never read (there is a `// TODO: MANAGE CONDITIONS` in `processSplitTestClass`).
The record declares it with type `AssertionStrategyType`, although an enum `ConditionStrategyType` with the same values
exists. Presumably meant to keep, mask or remove the conditions of `if`/loops in the prefix.

## Open points

1. `condition` has no effect. Options: remove it from the config and the record, or implement it.
2. Missing keys are silently `null` (except `try-catch-finally`, validated by a compact constructor since Step 9).
   Validating the other keys the same way (or giving them explicit defaults) would make the choice visible; until then,
   it is safer to write every key explicitly in the config file.
3. ~~`assertThrows` is the only camelCase key.~~ The key was removed in Step 10.
4. Known bugs in combinations not used by the committed config (from the code review, not yet fixed):
   - `split = assertion`, `target = statement`, `assertion = mask`: the target stays in the prefix and the mask is
     added as well;
   - a target that is the body of a lambda or of an `if`/`for` without braces is not removed from the prefix and not
     masked.
