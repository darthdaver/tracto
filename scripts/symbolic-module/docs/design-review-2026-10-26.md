# Design review of the normalization and splitting pipeline

**Date:** 2026-10-26
**Branch:** `22-test-processing-refactoring` (uncommitted Steps 2–7 on top of `b87e674c`)
**Scope:** `TestUtils` (normalization, splitting, datapoint generation), the parts of `JavaParserUtils` they use,
`OraclesDataset.generate`, and the test suite.

## Starting point

- `mvn test`: 28 tests, 0 failures, 0 errors (JDK 21).
- Line coverage (JaCoCo): `TestUtils` 41%, `JavaParserUtils` 21%, `OraclesDataset` 0%, `TokensUtils` 0%.
  `processSplitTestClass`, the step that builds the datapoints, has no tests.
- Steps 1–7 (`docs/fixes/`) made normalization run and fixed its main crashes. Still open from Step 7: the
  classification of try shapes, with the bad patterns discarded.

## Confirmed defects

Each defect below was reproduced with a probe test (normalization or splitting of a small fixture class,
and compiling the output with `javac` where relevant). The probe was deleted afterwards; the cases will become
regression tests.

| ID | Phase | Defect | Effect on the dataset | Since |
|----|-------|--------|------------------------|-------|
| D1 | split | **Nested loops.** A statement after an inner loop is placed *inside* the inner loop (`addStatement` appends to the last `for` found in the whole body, `TestUtils.java:2428`). | Wrong prefixes for every assertion that follows a nested loop. | baseline *Fixed in Step 10.* |
| D2 | normalize + split | **`if/else` with a nested `if`.** The `else` branch is attached to the nested `if` (`getLastStatementTypeOccurrence(..., IfStmt.class)`, `TestUtils.java:1808` and `:1293`). `if (a) { if (b) {..} } else {X}` becomes `if (a) { if (b) {..} else {X} }`. | Changed test semantics, wrong prefixes. | baseline (split); normalization since it runs *Splitter part fixed in Step 10; normalizer part fixed in Step 11.* |
| D3 | normalize | **Error flag lost.** `thrownedException = parseNormalizeTestStatementsBlock(...)` overwrites the flag, so an error followed by a block statement (loop, `if`, …) is not reported. | Missing entries in the error report; the kept `try` is dropped later with a generic message. | refactoring *Fixed in Step 9.* |
| D4 | normalize | **Inlined helpers are not normalized.** The helper body is appended as is (`TestUtils.java:2135`): a `try/catch/fail` inside a helper stays a `try`, so the splitter drops the whole test case; a helper called by a helper is not inlined. | Inlining a helper that contains a `try` *loses* the test case (it was kept before inlining worked). | Steps 3–5 |
| D5 | normalize | **Parameter aliasing.** A parameter passed a variable is renamed to that variable (`TestUtils.java:2099`). If the helper assigns the parameter (`v = v + 1;`), the inlined code modifies the caller's variable. | Changed semantics: a later assertion of the test case now reads the modified value. | refactoring |
| D6 | normalize | **Field shadowing.** A helper reading a field `base` is inlined into a test case that declares a local `base`; the inlined code reads the local. | Changed semantics. | refactoring |
| D7 | normalize | **Name clash with nested test variables.** Only top-level locals of the test case are in the set of existing variables (`TestUtils.java:314`), so a helper's `for (int i …)` inlined inside the test's `for (int i …)` is not renamed. | Output does not compile (`variable i is already defined`). | refactoring (known since Step 3) |
| D8 | normalize | **`try` inside a loop.** The final copies are made only for variables in the set of existing variables, so a loop variable used in the failing call is captured directly: `() -> Integer.parseInt("x" + i)`. | Output does not compile (`must be final or effectively final`). | Step 6 |
| D9 | split | **`flat` `assertThrows` on the generated declaration.** With the committed config (`assertThrows` missing → `flat`), `X e = assertThrows(...)` crashes the splitter (`TestUtils.java:957`). | Every test case whose try/catch/fail was converted is dropped. | known since Step 6 *Fixed in Step 10.* |
| D10 | normalize | **`fail()` not last in the try block.** `findFailMethodCall` accepts `fail()` anywhere; the conversion then assumes it is last. `try { parse(s); fail(); assertEquals(..); } catch (E e) {..}` becomes `parse(s); E e = assertThrows(E.class, () -> fail("..."), "never");` with no error logged. | Silently corrupted test case (it would throw at runtime). | refactoring *Fixed in Step 9.* |
| D11 | normalize | **Try with ≥2 catch clauses and no `fail()`.** No branch of `flatTryStmt` applies, and the try block is silently dropped. | Missing statements in prefixes. | known since Step 7 *Fixed in Step 9.* |
| D12 | normalize | **Varargs with one non-array variable.** `checkAll(x)` for `checkAll(int... vs)` declares `int vs = x;` and keeps `vs.length`. | Output does not compile. | baseline code, reached since Step 3 |
| D13 | normalize | **Renaming is not unique.** `normalizeVariableNames` keys its rename map with a structural `HashMap` (`JavaParserUtils.java:2172`): three sibling `for (int i = 0 …)` loops become `i`, `i2`, `i`. | Compiles, but breaks the "unique local names" assumption of the later steps. | refactoring |

From reading the code (not reproduced, but each one is a single, unambiguous code path):

| ID | Where | Defect |
|----|-------|--------|
| D14 | `processSplitTestClass` (`TestUtils.java:3125`) | The `try` encloses the loop over the test cases: an `IllegalStateException` in one split test case skips **all following** split test cases of the class. |
| D15 | `splitTest` (`TestUtils.java:741`) | An inner `catch (Exception e)` swallows every error, so the outer error-report branch (writing `error/split/...`) is unreachable. That branch also adds the original node instead of a copy (the Step 2 bug). *Fixed in Step 10.* |
| D16 | `splitTest` (`TestUtils.java:664`) | The `@Override` handling looks for a call named `super`, which never exists (`super.setUp()` is named `setUp`), so `foundSuper` is always false. |
| D17 | `getJunitVersion` (`TestUtils.java:134`) | The first import containing `junit` decides the version: `import org.junit.Assert;` before `import org.junit.jupiter.api.Test;` gives JUnit 4. |
| D18 | `getAssertionsDistribution` (`TestUtils.java:2566`) | `put("assert", n)` overwrites the count for each statement instead of adding to it. Statistics only. |
| D19 | `FixtureRepo.CONFIG` | The tests use `assertThrows = STANDARD`, while the committed config gives `flat`: no test covers the production path, which is why D9 was not caught by the suite. *(Step 8: `FixtureRepo.committedConfig()` added; used by the D9 test and the end-to-end test.)* |

Found while writing the Step 8 tests (details in `docs/fixes/step-08-test-infrastructure-and-characterization.md`), in the Step 9 A/B run and in Step 10:

| ID | Where | Defect |
|----|-------|--------|
| D20 | `JavaFileCollector.cleanRepository` | Generated `*STARSplitTestPrefix.java` files are not deleted and are collected as test classes on the next run. |
| D21 | `OraclesDataset.testClassPathToSourceClassNormPath` | Every `test` is removed from the test file name, so focal classes whose name contains `test` (`Latest`, `Contest`, …) are never matched. |
| D22 | `JavaParserUtils.checkModifiersRules` | Always returns `true`: the modifier rules used by `TokensUtils` are never applied (from reading). |
| D23 | `JavaParserUtils.getLastAssertionInMethodDeclaration` | For `assertAll(() -> ..., () -> assertTrue(..))` the target is the assertion inside the last lambda, not the `assertAll`. *Fixed in Step 10.* |
| D24 | splitter, `synchronized` branch | The body of a `synchronized` statement is added after the statement instead of within it. *Fixed in Step 10.* |
| D25 | `splitTest`, `processSplitTestClass`, `distributeMethods` | Fully qualified annotations (`@org.junit.Test`, `@org.junit.jupiter.api.Disabled`) are not recognized. |
| D26 | splitter, expression statements | A statement containing an anonymous class with methods (`new Clock() { public long now() { ... } }`) throws "Unexpected block statement parent node ...", and the test case is dropped (3 test cases in the two A/B projects; found in Step 9). *Fixed in Step 10.* |
| D27 | splitter, labeled statements | A labeled loop is rewritten as a labeled block (`outer: { for (...) { ... continue outer; } }`), which does not compile. Found and fixed in Step 10. |
| D28 | splitter, lambda block assigned to a variable | `Runnable r = () -> { ... };` is rewritten as a lambda without its declaration, and the split test class does not parse: all the datapoints of the class are lost. Found and fixed in Step 10. |
| D29 | normalizer, `if` branch | The comment of an `if` statement is dropped, while the comments of the other statements (loops included) are kept. Found and fixed in Step 11. |
| D30 | splitter (`TestCaseSplitter`, Step 10) | When the `then` branch of an `if` is a single statement and the `else` branch is a block or an `else if`, wrapping the `then` branch in a block moves it after the `else` branch among the children of the `if`. The split points, located in the copies of the test case by their index in pre-order, then point to other statements: split test cases swapped or cut at the wrong statement. Since Step 11 the normalizer writes every branch as a block, so this happens only in code the normalizer does not rewrite (inlined auxiliary methods, D4; lambda blocks passed as arguments). Found and fixed in Step 11. |

Every confirmed defect with a simple trigger has a `@Disabled` test named after it (map in the Step 8 document).

## Design issues behind the defects

### 1. The normalizer mixes "build a new tree" with "mutate the original"
The normalized body is a new, detached `BlockStmt`, filled by moving or cloning nodes of the original method. At the
same time the original compilation unit is mutated (variable renaming in place, `_final` declarations inserted into
the original try block, catch blocks renamed), and symbol resolution relies on the original staying attached.
Steps 1, 2, 3 and 6 were all instances of this: an operation that resolves symbols ran on a detached node, or a
mutation reached the wrong tree.

**Recommendation:** normalize a copy of the compilation unit **in place**, as a sequence of passes (rename locals →
inline helpers → flatten try statements), each replacing nodes with `Node.replace`. Every node stays attached, so
resolution always works, and the original file is never touched.

### 2. Two hand-written tree walkers with copy-pasted branches
`parseNormalizeTestStatementsBlock` and `parseSplitTestStatementsBlock` each have about ten near-identical branches
(`do`, `for`, `foreach`, `while`, `synchronized`, labeled, `if` chains, lambdas). The splitter does not keep a
reference to the block it is filling: each split test case is a fresh clone, so it finds the insertion point by
searching for the *last statement of a given type* in the whole body. That search is the root cause of D1 and D2.

**Recommendation for the splitter:** build each split test case by **truncation**. For a split point *N* (an
assertion, or any statement), copy the normalized method and, at every block enclosing *N*, remove the statements
that follow the child containing *N* (an `else` branch after *N* is removed the same way). This gives the same
prefixes as the current design for the shapes it handles correctly, but needs no insertion-point search and no
per-statement-type branch. It is much shorter than the ~700 lines it would replace.

### 3. Inlining copies text instead of preserving semantics
The inliner substitutes parameter names with argument names and renames clashing locals. It does not handle
parameters that are assigned (D5), fields shadowed at the call site (D6), names in nested scopes (D7), varargs (D12),
or statements that need normalizing themselves (D4).

**Recommendation:**
- bind each parameter to a local declaration (`int v = x;`), unless the parameter is never assigned in the helper and
  the argument is a plain variable; this fixes D5 and D12 and keeps the argument evaluation order;
- qualify field reads in the inlined copy with `this.` when a local of the test case has the same name (D6);
- run the normalizer again on the inlined body, with a depth limit and a guard against recursive helpers (D4).

### 4. Name generation is scattered
Six naming schemes coexist (`i1`, `x_0`, `v_arg` → `v_arg1` → `v_arg12`, `x_final1` → `x_final12`,
`p_executable`, `helperResult`). Some generated names are never registered (the varargs variable). The set of
existing names covers only top-level locals.

**Recommendation:** one name allocator per test case, seeded with every identifier in the method (all depths), its
parameters and the fields of the class. A single scheme, `base`, `base_1`, `base_2`, … fixes D7, D13 and the growing
suffixes.

### 5. Errors are swallowed and reported late
Exceptions are caught at several levels, logged with `e.getMessage()` only, and turned into a boolean flag (lost in
D3). The failing statement is then appended unchanged, so the problem resurfaces in the splitter as the generic
"Try statement should not appear in a test after the normalization".

**Recommendation:** a per-test-case result, `NORMALIZED` or `DISCARDED(reason)`, with a fixed set of reason codes
(for example `MULTI_CATCH`, `UNION_CATCH`, `FAIL_NOT_LAST`, `FAIL_IN_CATCH`, `CATCH_WITHOUT_FAIL`, `SWITCH`), counted
in the statistics. This is also the mechanism the planned discarding of bad try patterns needs (Step 7). The same
applies to `processSplitTestClass`: catch per split test case (D14).

### 6. Try handling needs an explicit classifier
`checkTryStmt`, `findFailMethodCall` and `flatTryStmt` each check part of the shape, which is how D10 and D11 slip
through. **Recommendation:** classify each try statement once into the shapes of the Step 7 count (expected
exception, try-with-resources, try/finally, must-not-throw, catch without fail, empty catch, multi-catch, union catch,
fail not last), transform only the supported shapes, and discard the others with the reason.

### 7. Configuration
Missing keys silently become `null` and select a branch (see `docs/oracles-dataset-config.md`), and the tests use a
config that differs from the committed one (D19). **Recommendation:** validate the record when it is loaded, write
every key in the committed JSON, and make the tests load the committed file.

### 8. Testability
`TestUtils` is a 4,300-line class of static methods. The only entry points read and write files, and they write
generated classes into the analysed repository. Every test therefore needs a temporary repository on disk.
**Recommendation (incremental):** when a unit is fixed, extract it into its own class with an AST-in/AST-out API
(`TryStatementClassifier`, `AuxiliaryMethodInliner`, `NameAllocator`, `TestSplitter`), and keep the file I/O in
`normalizeTest`/`splitTest`.

## Dataset decisions to make

These affect what the model sees, so they are choices rather than bugs:

1. **`_final` copies.** Every variable used in a converted failing call gets a `final T x_final = x;` line, even when
   `x` is already effectively final (almost always). These lines never appear in real tests. Option: copy only the
   variables that are reassigned.
2. **Parameter binding on inlining.** Always declare a local per parameter (more lines, always correct), or only when
   needed (recommendation 3).
3. **`assertThrows` mode in the committed config.** `flat` is broken on the generated declarations (D9) and leaks the
   expected exception through the `throws` clause of the split test case. `standard` treats `assertThrows` as an
   ordinary assertion and has no known bug.
4. **`fail()` outside a try block** still becomes the target `THROW EXCEPTION` (open since Step 5).

### Decisions taken (2026-10-26)

| Topic | Decision |
|-------|----------|
| Restructuring | Incremental: fix test-first, extract only the unit being fixed into its own class. |
| Splitter (D1, D2) | Rewrite by **truncation** (copy the normalized method, cut after the split point), after pinning today's correct output with characterization tests. |
| Try statements | `try-catch-finally` keeps two values. `keep-try-catch`: the structure is kept; the `fail(...)` call and the assertions within the try/catch blocks are the targets. `to-assert-throws`: the try statement is flattened into an `assertThrows`, which is the target. The `assertThrows` key (`standard`/`flat`) is removed. |
| `THROW EXCEPTION` | Removed everywhere: a `fail(...)` is a target like any other statement. `TokensUtils` and `utils/find_assertion_type.py`, `utils/assertion_distribution.py` use the label and must be updated in the same step. |
| Generated variables | Only when needed, with Java-style names: `finalCalc` (copy for a lambda, only for reassigned variables), the parameter's own name for parameter bindings (`int v = x;`), numeric suffix for clashes (`count2`), helper name for labels (`search2` on clash). When the catch block does not use the exception, a plain `assertThrows(...);` is generated. |

## Housekeeping

- `src/main/java/star/tracto/preprocessing/utils/temp` (staged) and
  `src/main/java/star/tracto/utils/javaParser/JavaParserUtilsTemp.java` (untracked, **compiled into the jar**) are
  March drafts of `TestUtils`/`JavaParserUtils`, not referenced anywhere.
- Dead code: `matchMethodsToTests` (empty), `hasSuper` and the loop over `integratedAuxiliaryMethods` in
  `normalizeTest`, the `isPrivate()` filter on top-level types (top-level types cannot be private; dropping the
  secondary types is correct, because they stay in the original file of the same package), `FAKE_ELEMENT_LABEL`,
  the lambda-statement branch of the normalizer (a lambda cannot be an expression statement), `replaceParams`,
  `renameLocalVar`, `collectVariableMapping(Expression, …)`, `addCatchClause`, and the `TRY`/`CATCH`/`FINALLY`/`SWITCH`
  cases of `addStatement` (the splitter throws on these statements first). *Step 10 removed `addCatchClause` and
  `addStatement`.* Since Step 11: the statement visitors of `utils/javaParser/visitors/statements` other than
  `AssertStmtVisitor` and `ExpressionStmtVisitor` (they were only used by the removed
  `getLastStatementTypeOccurrence`), and `StmtVisitorHelper.getLastExprInStmt`.
- Unused imports in `TestUtils`: `javax.swing.plaf.nimbus.State`, `javax.swing.text.html.Option`,
  `javassist.expr.NewArray`, `java.lang.reflect.Method`.
- `TokensUtils` changed in `b87e674c` from a name-keyed map to an identity set of declarators; the tokens dataset was
  not regenerated since. It needs a before/after comparison.

## Test plan

**Fixtures instead of mocks.** The code transforms real JavaParser trees and resolves symbols with the real
solver; mocking AST nodes or the solver would test nothing meaningful. The "mocks" are therefore fixture
repositories, built in a JUnit `@TempDir` as the current `FixtureRepo` does, extended with:
- a writer for the focal (source) class, needed by `processSplitTestClass`;
- the committed config loaded from `src/main/resources/dataset/oracles-dataset_config.json`, next to the test config;
- the compile-and-run helper of `TestUtilsNormalizeBehaviourTest`, moved to a shared class, so that any test can check
  that the normalized code compiles and behaves like the original;
- a small multi-class repository for an end-to-end test of `OraclesDataset.generate`.

Mockito (already a test dependency) only where a collaborator is impractical to build for real.

**Units and what to test:**

| Unit | Tests |
|------|-------|
| `JavaParserUtils`: `normalizeVariableNames`, `replaceVariables`, `replaceParameters`, `getVarFromNameExpr`, `renameLocalParam`, `generateNewVariable`, `collectExistingVariables`/`checkNodeScope`, `getLastAssertionInMethodDeclaration` | Direct tests on parsed snippets attached to a compilation unit (D13 included). |
| Normalization (`normalizeTest`) | One test per statement kind (block, `do`, `for`, `foreach`, `while`, `synchronized`, `if` chains, labeled, `switch`); try shapes (D3, D8, D10, D11); inlining (D4–D7, D12); compile-and-run checks on the outputs. |
| Splitting (`splitTest`) | One test per statement kind (D1, D2); both `assertThrows` modes with the generated declaration (D9); `keep-statements-after-last-assertion`; `num-assertions-match`; `@Disabled`/`@Ignore` and `@Override` test cases (D16). |
| Datapoints (`processSplitTestClass`) | Target and prefix for each combination of `split`/`target`/`assertion` (mask, keep, remove), `THROW EXCEPTION` targets, assertion qualifiers removed, one failing split test case does not drop the others (D14). |
| Pipeline (`OraclesDataset.generate`) | End-to-end on the fixture repository: datapoint count, a few golden datapoints, statistics files. |

## Proposed order of the next steps

Each step is test-first and documented in `docs/fixes/step-NN-*.md`, with the A/B check on crawler-commons and
java-uuid-generator, as for Steps 1–7.

1. **Step 8 – test infrastructure and characterization tests** for the behaviour that is already correct (the
   `JavaParserUtils` helpers, the splitter's simple shapes, `processSplitTestClass`). *Done on 2026-10-26.*
2. **Step 9 – try classification and discarding** (planned in Step 7): D10, D11, D3 and the per-test-case result;
   the `try-catch-finally` values `to-assert-throws` and `keep-try-catch` (the splitting within try statements for
   `keep-try-catch` is completed with the new splitter in Step 10). *Done on 2026-10-26.*
3. **Step 10 – splitter rewrite by truncation** (D1, D2, D23, D24, D26, splitting within try statements). *Done on
   2026-10-26, together with the removal of the `assertThrows` key and of its `flat` mode (D9), D15, D27 and D28.*
4. **Step 11 – normalizer `if` chains** (D2). *Done on 2026-10-27, together with D29 and D30.*
5. **Step 12 – inlining semantics** (D4–D7, D12) and the name allocator (D13).
6. **Step 13 – lambda captures in nested try statements** (D8) and the `_final` policy.
7. **Step 14 – remove the `THROW EXCEPTION` label** (the `assertThrows` key was already removed in Step 10), including
   `TokensUtils` and the two Python utilities; plain `assertThrows(...)` when the catch block does not use the exception.
8. **Step 15 – robustness and clean-up**: D14, D16–D18, D20–D22, D25, dead code (the `temp` drafts were deleted on 2026-10-26).
