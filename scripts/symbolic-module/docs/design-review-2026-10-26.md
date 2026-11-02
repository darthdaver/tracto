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
| D4 | normalize | **Inlined helpers are not normalized.** The helper body is appended as is (`TestUtils.java:2135`): a `try/catch/fail` inside a helper stays a `try`, so the splitter drops the whole test case; a helper called by a helper is not inlined. | Inlining a helper that contains a `try` *loses* the test case (it was kept before inlining worked). | Steps 3–5 *Fixed in Step 12.* |
| D5 | normalize | **Parameter aliasing.** A parameter passed a variable is renamed to that variable (`TestUtils.java:2099`). If the helper assigns the parameter (`v = v + 1;`), the inlined code modifies the caller's variable. | Changed semantics: a later assertion of the test case now reads the modified value. | refactoring *Fixed in Step 12.* |
| D6 | normalize | **Field shadowing.** A helper reading a field `base` is inlined into a test case that declares a local `base`; the inlined code reads the local. | Changed semantics. | refactoring *Fixed in Step 12.* |
| D7 | normalize | **Name clash with nested test variables.** Only top-level locals of the test case are in the set of existing variables (`TestUtils.java:314`), so a helper's `for (int i …)` inlined inside the test's `for (int i …)` is not renamed. | Output does not compile (`variable i is already defined`). | refactoring (known since Step 3) *Fixed in Step 12.* |
| D8 | normalize | **`try` inside a loop.** The final copies are made only for variables in the set of existing variables, so a loop variable used in the failing call is captured directly: `() -> Integer.parseInt("x" + i)`. | Output does not compile (`must be final or effectively final`). | Step 6 *Fixed in Step 13.* |
| D9 | split | **`flat` `assertThrows` on the generated declaration.** With the committed config (`assertThrows` missing → `flat`), `X e = assertThrows(...)` crashes the splitter (`TestUtils.java:957`). | Every test case whose try/catch/fail was converted is dropped. | known since Step 6 *Fixed in Step 10.* |
| D10 | normalize | **`fail()` not last in the try block.** `findFailMethodCall` accepts `fail()` anywhere; the conversion then assumes it is last. `try { parse(s); fail(); assertEquals(..); } catch (E e) {..}` becomes `parse(s); E e = assertThrows(E.class, () -> fail("..."), "never");` with no error logged. | Silently corrupted test case (it would throw at runtime). | refactoring *Fixed in Step 9.* |
| D11 | normalize | **Try with ≥2 catch clauses and no `fail()`.** No branch of `flatTryStmt` applies, and the try block is silently dropped. | Missing statements in prefixes. | known since Step 7 *Fixed in Step 9.* |
| D12 | normalize | **Varargs with one non-array variable.** `checkAll(x)` for `checkAll(int... vs)` declares `int vs = x;` and keeps `vs.length`. | Output does not compile. | baseline code, reached since Step 3 *Fixed in Step 12.* |
| D13 | normalize | **Renaming is not unique.** `normalizeVariableNames` keys its rename map with a structural `HashMap` (`JavaParserUtils.java:2172`): three sibling `for (int i = 0 …)` loops become `i`, `i2`, `i`. | Compiles, but breaks the "unique local names" assumption of the later steps. | refactoring *Fixed in Step 12.* |

From reading the code (not reproduced, but each one is a single, unambiguous code path):

| ID | Where | Defect |
|----|-------|--------|
| D14 | `processSplitTestClass` (`TestUtils.java:3125`) | The `try` encloses the loop over the test cases: an `IllegalStateException` in one split test case skips **all following** split test cases of the class. *Fixed in Step 15.* |
| D15 | `splitTest` (`TestUtils.java:741`) | An inner `catch (Exception e)` swallows every error, so the outer error-report branch (writing `error/split/...`) is unreachable. That branch also adds the original node instead of a copy (the Step 2 bug). *Fixed in Step 10.* |
| D16 | `splitTest` (`TestUtils.java:664`) | The `@Override` handling looks for a call named `super`, which never exists (`super.setUp()` is named `setUp`), so `foundSuper` is always false. *Fixed in Step 15.* |
| D17 | `getJunitVersion` (`TestUtils.java:134`) | The first import containing `junit` decides the version: `import org.junit.Assert;` before `import org.junit.jupiter.api.Test;` gives JUnit 4. *Fixed in Step 15.* |
| D18 | `getAssertionsDistribution` (`TestUtils.java:2566`) | `put("assert", n)` overwrites the count for each statement instead of adding to it. Statistics only. *Fixed in Step 15.* |
| D19 | `FixtureRepo.CONFIG` | The tests use `assertThrows = STANDARD`, while the committed config gives `flat`: no test covers the production path, which is why D9 was not caught by the suite. *(Step 8: `FixtureRepo.committedConfig()` added; used by the D9 test and the end-to-end test.)* |

Found while writing the Step 8 tests (details in `docs/fixes/step-08-test-infrastructure-and-characterization.md`), in the Step 9 A/B run and in Step 10:

| ID | Where | Defect |
|----|-------|--------|
| D20 | `JavaFileCollector.cleanRepository` | Generated `*STARSplitTestPrefix.java` files are not deleted and are collected as test classes on the next run. *Fixed in Step 15.* |
| D21 | `OraclesDataset.testClassPathToSourceClassNormPath` | Every `test` is removed from the test file name, so focal classes whose name contains `test` (`Latest`, `Contest`, …) are never matched. *Fixed in Step 15.* |
| D22 | `JavaParserUtils.checkModifiersRules` | Always returns `true`: the modifier rules used by `TokensUtils` are never applied (from reading). *Fixed in Step 15.* |
| D23 | `JavaParserUtils.getLastAssertionInMethodDeclaration` | For `assertAll(() -> ..., () -> assertTrue(..))` the target is the assertion inside the last lambda, not the `assertAll`. *Fixed in Step 10.* |
| D24 | splitter, `synchronized` branch | The body of a `synchronized` statement is added after the statement instead of within it. *Fixed in Step 10.* |
| D25 | `splitTest`, `processSplitTestClass`, `distributeMethods` | Fully qualified annotations (`@org.junit.Test`, `@org.junit.jupiter.api.Disabled`) are not recognized. *Fixed in Step 15.* |
| D26 | splitter, expression statements | A statement containing an anonymous class with methods (`new Clock() { public long now() { ... } }`) throws "Unexpected block statement parent node ...", and the test case is dropped (3 test cases in the two A/B projects; found in Step 9). *Fixed in Step 10.* |
| D27 | splitter, labeled statements | A labeled loop is rewritten as a labeled block (`outer: { for (...) { ... continue outer; } }`), which does not compile. Found and fixed in Step 10. |
| D28 | splitter, lambda block assigned to a variable | `Runnable r = () -> { ... };` is rewritten as a lambda without its declaration, and the split test class does not parse: all the datapoints of the class are lost. Found and fixed in Step 10. |
| D29 | normalizer, `if` branch | The comment of an `if` statement is dropped, while the comments of the other statements (loops included) are kept. Found and fixed in Step 11. |
| D30 | splitter (`TestCaseSplitter`, Step 10) | When the `then` branch of an `if` is a single statement and the `else` branch is a block or an `else if`, wrapping the `then` branch in a block moves it after the `else` branch among the children of the `if`. The split points, located in the copies of the test case by their index in pre-order, then point to other statements: split test cases swapped or cut at the wrong statement. Since Step 11 the normalizer writes every branch as a block, so this happens only in code the normalizer does not rewrite (inlined auxiliary methods, D4; lambda blocks passed as arguments). Found and fixed in Step 11. |
| D31 | normalizer, labeled statements | A labeled statement (`outer: for (...) { ... }`) is added to the normalized test case as it is: the statements within it are not normalized (a try/catch/fail is not converted into `assertThrows`, an auxiliary method is not inlined). Found and fixed in Step 12. |
| D32 | datapoints, `keep-try-catch` | The target of a split test case is searched from its last statement in pre-order: for a split point within a try block, the empty catch (or finally) block kept by the truncation (Step 10). With `keep-try-catch` the `fail(...)` and the assertions of the try blocks produced no datapoint ("No assertion found"); with the committed configuration, the same for the try statements the normalizer does not rewrite (within a lambda block, 2 datapoints in jsoup); with `split = statement` an empty compound statement got its empty body `{}` as target. Found and fixed in Step 14. |
| D33 | splitter, `@Override` test cases | The split test cases of a test case overriding a test case of the superclass keep `@Override`, although with their new names they override nothing: the split test class does not compile, and the annotation is listed in the test prefixes. Found and fixed in Step 15 (kept by the split test cases of a test case without a test annotation, recognized as a test case by `@Override`). |
| D34 | `JavaParserUtils.setRepoJavaParser` | The classes found by the type solvers (the projects of the repository, the JDK sources) are parsed without the symbol resolver: resolving a node of a declaration reached through the symbol solver (`toAst`, `getWrappedNode`) throws "Symbol resolution not configured". `processInvokedMethods` drops the methods whose description needs such a resolution, those with a type parameter of their class in the signature (`SuffixTrie<V>.put`, `List<E>.get`): 4,738 statements of the prefixes in the Step 15 A/B run. Found in the error statistics of the Step 15 A/B run and fixed in Step 16. |
| D35 | `JavaUtils.initializeJavaClassPath` | All the JDKs of `lib/java_classpaths.json` are given to the symbol solver in the order of the file (Java 8 first), whatever the Java version argument (`24` in the scripts): the first JDK containing a class wins, so the JDK classes are always those of Java 8, and the methods added after Java 8 (`List.of`, `String.isBlank`, `Stream.toList`) never resolve. Found after Step 16 and fixed in Step 17, with the detection of the Java version of the repository. |
| D36 | `JavaParserUtils.setRepoJavaParser` | The code under analysis is parsed at `JAVA_17` and the type solvers at the `BLEEDING_EDGE` of JavaParser 3.26.1 (`JAVA_21`): the syntax of Java 21 to 25 (record patterns, unnamed variables, statements before `super()`, module imports) is rejected and, as the parse problems are not checked, lost silently (a test case asserting within an `if` with a record pattern gives no datapoint); the focal classes declared as records make the processing of their whole test class fail ("Cannot get a reference type declaration from RecordDeclaration": 9 test classes of cryptofs). Fixed in Step 18 (JavaParser 3.28.2, one configuration at `JAVA_26`). |
| D37 | JavaParser 3.28.x, method resolution | Defects of the library, fixed on its master branch after 3.28.2: arrays of primitive types are widened or boxed component by component (JavaParser #4991, #5065, #5146: `Arrays.sort(byte[])` ambiguous), the return types of the candidate methods must be resolvable (a test helper returning a class of Mockito or Hamcrest is not resolved), an `UnsupportedOperationException` on some lambdas; any of them stopped the matching of the invoked methods of the whole test prefix. Worked around in Step 18 (`JavaParserUtils.resolveMethodCall`, errors caught per statement). |
| D38 | normalizer, `to-assert-throws` (`flatTryStmt`) | A variable of the try block (or a resource) hiding a field is renamed when the try statement is flattened, but the copy of the failing call within the `assertThrows` kept the old name: `String s2 = "x"; assertThrows(E.class, () -> Integer.parseInt(s));` reads the field. Found and fixed in Step 19 (the variables are renamed before the failing call is copied). |

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
  secondary types is correct, because they stay in the original file of the same package), `FAKE_ELEMENT_LABEL` (*removed in Step 14*),
  the lambda-statement branch of the normalizer (a lambda cannot be an expression statement), `replaceParams`,
  `renameLocalVar`, `collectVariableMapping(Expression, …)`, `addCatchClause`, and the `TRY`/`CATCH`/`FINALLY`/`SWITCH`
  cases of `addStatement` (the splitter throws on these statements first). *Step 10 removed `addCatchClause` and
  `addStatement`.* Since Step 11: the statement visitors of `utils/javaParser/visitors/statements` other than
  `AssertStmtVisitor` and `ExpressionStmtVisitor` (they were only used by the removed
  `getLastStatementTypeOccurrence`), and `StmtVisitorHelper.getLastExprInStmt`.
- Unused imports in `TestUtils`: `javax.swing.plaf.nimbus.State`, `javax.swing.text.html.Option`,
  `javassist.expr.NewArray`, `java.lang.reflect.Method`. *Removed in Step 15, with the dead code above.*
- `TokensUtils` changed in `b87e674c` from a name-keyed map to an identity set of declarators; the tokens dataset was
  not regenerated since. It needs a before/after comparison. *Since Step 12 the variables of a normalized test case have unique names, so a
  map keyed by name and a set of declarators hold the same variables of the test prefixes (see Step 15).*

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
5. **Step 12 – inlining semantics** (D4–D7, D12) and the name allocator (D13). *Done on 2026-10-27, together with D31.*
6. **Step 13 – lambda captures in nested try statements** (D8) and the `_final` policy. *Done on 2026-10-27.*
7. **Step 14 – remove the `THROW EXCEPTION` label** (the `assertThrows` key was already removed in Step 10), including
   `TokensUtils` and the two Python utilities; plain `assertThrows(...)` when the catch block does not use the exception. *Done on 2026-10-27, together with D32.*
8. **Step 15 – robustness and clean-up**: D14, D16–D18, D20–D22, D25, dead code (the `temp` drafts were deleted on 2026-10-26). *Done on 2026-10-27, together with D33.*
9. **Step 16 – invoked methods of the test prefixes** (D34), found after the plan in the error statistics of the Step 15 A/B run. *Done on 2026-10-28.*
10. **Step 17 – Java version of the repository and JDK sources** (D35): the version declared by the build files selects the JDK whose sources are given to the symbol solver; the sources of the JDKs are built by `bash/utils/setup-jdk-sources.sh`. *Done on 2026-10-29.*
11. **Step 18 – JavaParser 3.28.2 and a single parser configuration** (D36), with the workaround of the defects of the library (D37). *Done on 2026-10-30.*
12. **Step 19 – lambda blocks of `assertThrows`, `assertThrowsExactly` and `assertDoesNotThrow`**: the statements before the last one move before the assertion (`assertion-lambda-blocks = move-to-prefix`), an `assertDoesNotThrow` ending with an assertion is unwrapped, the shapes whose throwing statement is unclear produce no datapoint (`skip` makes all of them produce none); tests of the lambda paths of the splitter. *Done on 2026-11-02, together with D38.*

## Open items after Step 19 (2026-11-02)

Found during Steps 15–19, not fixed yet; in the suggested order (on 2026-11-02 the user chose the dependencies, item 3,
as the next step, mainly for the tokens dataset):

1. **Compile check of the generated classes.** The split test classes have never been compiled within the real projects
   (`mvn test-compile` of a few A/B projects with the generated classes in place): the only check of the whole output.
2. **Inlining within flattened try statements.** `flatTryStmt` returns a detached block, so `AuxiliaryMethodInliner`
   cannot resolve the calls of auxiliary methods within a try-with-resources or a try/finally ("The node is not inserted
   in a CompilationUnit"): 37 calls in the A/B projects (eec 32, jsoup 3, plexus-utils 2) stay calls, their assertions
   are not datapoints.
3. **Dependencies of the projects.** `oracles-dataset.sh` gives the symbol solver no dependency of the project (the
   `processed_libs` block is commented out): the types of Mockito, Hamcrest, JUnit 4 rules, servlets are the largest
   category of errors of the invoked methods (esapi 896, ffmpeg 605 unsolved symbols), and a helper returning one of them
   needs the workaround of Step 18.
4. **JavaParser release.** Upgrade to the first release after 3.28.2 (it fixes #4991/#5065 and the resolution of the
   return types), rerun the A/B, and simplify `JavaParserUtils.resolveMethodCall`. The fix of #5146 prepared for
   JavaParser is not submitted. 16 calls of statically imported matchers of cryptofs are resolved only by the release.
5. **Tokens dataset.** The order of the candidate tokens and of the contextual invoked methods changes at each run; the
   candidates include non-public members of the JDK classes (`String.isLatin1`, `LATIN1`); they miss the variables of
   nested blocks and the `length` of arrays ("Unexpected token"); the element type of `iterator().next()` on a generic
   collection is not inferred at one step of crawler-commons.
6. **Smaller items.** Anonymous classes are named with a random UUID (`Anonymous-<uuid>`), different at each run; the
   normalizer does not support `switch` statements (kept as they are, reported); the `keep-try-catch` and `statement`
   modes are covered by tests only; 21 of the 135 input repositories declare their Java version only in a parent POM
   (the newest and the oldest JDK are used); the Mockito of the tests (5.2) needs `net.bytebuddy.experimental` on
   Java 21.
7. **Lambda blocks (after Step 19).** The lambda blocks of `assertAll`, `assertTimeout`, `assertTimeoutPreemptively` and
   AssertJ `assertThatThrownBy` are kept as they are (about 50 blocks of two or more statements in the input
   repositories, 5 `assertTimeoutPreemptively` in cryptofs); the normalizer does not visit the lambda blocks of the other
   statements (`values.forEach(v -> { ... })`): their auxiliary methods are not inlined and their try statements are
   not converted, while the splitter splits within them. The type inference of JavaParser for lambdas varies from run
   to run (`Equivalent types are: [InferenceVariableType{id=1}, ...]`: one error and one invoked method of eec differ
   between two runs on identical generated files).
