# Oracles dataset configuration

This document describes the configuration file of the oracles dataset generation: what each key means, which values
it accepts, where the code reads it, and its current status.

**File:** `src/main/resources/dataset/oracles-dataset_config.json`
**Loaded into:** the record `star.tracto.data.records.OraclesDatasetConfig` (via Jackson, `FilesUtils.readJSON`)
**Passed by:** `bash/oracles-dataset.sh`, as the 7th argument of the jar (`oracles <id> <repo> <output> <base> <java> <config>`)

Last reviewed: 2026-10-23, on branch `22-test-processing-refactoring`.

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
  "try-catch-finally": "standard",
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

The file has not changed since 2025-08-15. Two keys are missing: `assertThrows` (→ `null`, behaves as `flat`) and
`num-assertions-match` (→ `null`, the check is disabled).

## Summary of the keys

| Key | Values | Default when missing | Status |
|-----|--------|----------------------|--------|
| `split` | `assertion`, `statement` | `null` → the splitter creates no split points, so no datapoints | Used |
| `target` | `assertion`, `statement` | `null` → every target is the `no-assertion-target-placeholder` | Used |
| `assertion` | `keep`, `mask`, `remove` | `null` → behaves as `remove` | Used |
| `statement` | `keep`, `mask`, `remove` | `null` → behaves as `remove` | Used only with `split = statement` and `target = statement` |
| `assertThrows` | `standard`, `flat` | `null` → behaves as `flat` | Used |
| `integrate-auxiliary-methods` | `true`, `false` | `false` | Used |
| `keep-statements-after-last-assertion` | `true`, `false` | `false` | Used |
| `num-assertions-match` | `strict`, `loose` | `null` → no check | Used |
| `mask-placeholder` | any string | `null` | Used (with `mask`) |
| `no-assertion-target-placeholder` | any string | `null` | Used |
| `try-catch-finally` | `standard`, `flat`, `assert_throws` | `null` | **Not used** since 2026-03-09 (see below) |
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

Read in `TestUtils.parseSplitTestStatementsBlock` and `TestUtils.processSplitTestClass`.

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

Special target: whatever the strategies, a target starting with `fail` becomes the label **`THROW EXCEPTION`**. The
same label is produced by `assertThrows = flat` (see below). The tokens stage treats `THROW EXCEPTION` as a special
first token that the model can choose instead of an assertion name.

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

### `assertThrows`: how an `assertThrows` becomes a datapoint

Read in `TestUtils.parseSplitTestStatementsBlock` (the only read, ~line 947). Note that this key is the only one in
camelCase.

Verified on a test case `Calc calc = new Calc(); int x = -1; assertThrows(IllegalArgumentException.class, () -> calc.sqrt(x)); assertEquals(1, calc.sqrt(1));`:

| Value | Datapoint for the `assertThrows` |
|-------|----------------------------------|
| `standard` | The `assertThrows` is an assertion like the others. Prefix `Calc calc = new Calc(); int x = -1; <mask>`, target `assertThrows(IllegalArgumentException.class, () -> calc.sqrt(x));` |
| `flat` (and `null`) | The call inside the `assertThrows` is written as a plain statement at the end of the prefix, and the target is `THROW EXCEPTION`. Prefix `Calc calc = new Calc(); int x = -1; calc.sqrt(x); <mask>`, target `THROW EXCEPTION`. |

In both cases the following assertion (`assertEquals(1, calc.sqrt(1))`) is a datapoint whose prefix contains the
`assertThrows` statement.

Details of `flat`:
- the expected exception is added to the `throws` clause of the split test case
  (`public void testBare_split_0() throws IllegalArgumentException`), probably so that a checked exception does not make
  the prefix uncompilable. The signature is part of the model input, so it reveals the expected exception, and the
  clause is carried over to the following split test cases of the same test case;
- known bug (2026-10-23): the `flat` branch fails on the form generated by the try/catch normalization,
  `IllegalArgumentException e = assertThrows(...)` (`... is not MethodCallExpr, it is VariableDeclarationExpr`), and
  reads the exception class at the wrong position for JUnit 5 with a message.

Since the committed config does not contain the key, every dataset generated so far used `flat`.

### `try-catch-finally`: not used since 2026-03-09

The three values were read by the **splitter** until commit `6db6ac15` (2026-03-09, *"Try/catch/finally flattening
within the normalization phase"*), which moved the handling of try statements into the normalization phase and removed
every read of this key. Their meaning, reconstructed from the code before that commit (`git show 6db6ac15^:...TestUtils.java`):

| Value | Meaning in the old splitter |
|-------|-----------------------------|
| `standard` | **Keep the try/catch/finally structure.** The split test cases contain the `try` statement; the statements of the try block, then of each catch block, then of the finally block are split points, inside that structure. |
| `flat` | **Inline the try block.** The statements of the try block are copied into the test case without the `try`, the `fail(...)` call is replaced by `fail("THROW EXCEPTION")` (target `THROW EXCEPTION`), and the exceptions of the catch clauses are added to the `throws` clause of the split test case. The following split test cases continue with the original `try` statement. This is the origin of the `THROW EXCEPTION` label and of the `throws` clause. |
| `assert_throws` | **Convert into `assertThrows`.** A try statement without resources was checked (`checkTryStmt`) and converted by `flatTryStmt` into the statements of the try block plus `assertThrows(<exception>, () -> <call before fail>)` and the statements of the catch/finally blocks. The result was then split, and the `assertThrows` was handled according to the `assertThrows` key. A try statement with resources fell back to `standard`. |

How try statements are handled today (whatever the value of `try-catch-finally`):
- normalization always converts the standard pattern `try { ...; call(); fail(...); } catch (E e) { ... }` into
  `E e = assertThrows(E.class, () -> call(), ...)` followed by the catch statements, which is the old `assert_throws`
  behaviour (fixed in Step 6, see `docs/fixes/step-06-flatten-try-catch-fail.md`);
- a try statement **without catch clauses** (try-with-resources, try/finally) is flattened: the resource declarations,
  then the statements of the try block, then the statements of the finally block (Step 7,
  `docs/fixes/step-07-flatten-try-without-catch.md`). Before Step 7 the resources and the try block were silently
  dropped;
- any other try statement (several catch clauses, `fail` in the catch block, …) is kept as it is, and the splitter then
  **drops the whole test case** ("Try statement should not appear in a test after the normalization");
- the representation of the generated `assertThrows` in the dataset is decided by the `assertThrows` key.

**Planned change (decided on 2026-10-23, in progress).** The key will keep two values, both handled in the
normalization phase:

| New value | Meaning |
|-----------|---------|
| `to-assert-throws` (committed config) | Convert the expected-exception pattern into `assertThrows`; flatten try-with-resources and try/finally without catch clauses. |
| `keep-try-catch` | Keep the try/catch/finally structure (normalized inside its blocks); the splitter splits inside the try, catch and finally blocks. |

In both modes, the test cases containing a try statement with a bad or unsupported pattern are discarded, with the
reason logged: more than one catch clause, a union type in the catch clause, `fail()` in the catch block ("must not
throw"), a catch clause without any `fail()` (the test passes even if nothing is thrown), an empty catch block, `fail()`
not as the last statement of the try block. The old `flat` behaviour is dropped.

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

Read at the end of `TestUtils.parseSplitTestStatementsBlock` (top level only).

| Value | Meaning |
|-------|---------|
| `true` | The statements after the last assertion of a test case form one more split test case. |
| `false` | They are dropped. |

From reading the code (not verified): with `split = assertion` the extra split test case has no assertion as last
statement, so `processSplitTestClass` logs "No assertion found" and produces no datapoint for it.

### `num-assertions-match`

Read at the end of `TestUtils.parseSplitTestStatementsBlock`, only with `split = assertion`: after splitting a block,
the number of split test cases is compared with the number of assertions found in the block.

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

1. `try-catch-finally` and `condition` have no effect. Options: remove them from the config and the record, or
   re-implement them (for example, `standard` = keep the try structure for the try shapes that cannot be converted,
   instead of dropping the test case).
2. Missing keys are silently `null`. Validating the record when it is loaded (a compact constructor that rejects
   `null` values, or explicit defaults) would make the choice visible; until then, it is safer to write every key
   explicitly in the config file.
3. `assertThrows` is the only camelCase key.
4. Known bugs in combinations not used by the committed config (from the code review, not yet fixed):
   - `split = assertion`, `target = statement`, `assertion = mask`: the target stays in the prefix and the mask is
     added as well;
   - a target that is the body of a lambda or of an `if`/`for` without braces is not removed from the prefix and not
     masked.
