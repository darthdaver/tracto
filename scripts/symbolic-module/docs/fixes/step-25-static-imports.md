# Step 25 – Members imported statically

**Date:** 2026-11-06
**Branch:** `22-test-processing-refactoring` (on top of Step 24)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/StaticImports.java` (new): the members named by the
  `import static` declarations of the test class
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `updateHashMapsOfStaticImportIdentifiers` (new),
  called by `setUpRootTokenIdentifiers`; the root enum constants and their references; the enum constants matched in
  the `IDENTIFIER` step
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java`: `solveType` (new), a type resolved by its canonical
  name with the type solvers of the repository
- tests: `StaticImportsTokensTest` (new, 5 end-to-end tests)

## Problem

Cause `static import` of `missing_tokens.py`: 133 lost oracles on the nine projects (130 by this cause alone; jsoup 70,
eec 41, ffmpeg 20), 141 token steps. The members imported statically were never offered: `import static a.B.m` and
`import static a.B.*` were handled as the imports of classes (`collectPathsOfImportedClasses` looks for a class
`a.B.m`, or the classes of a package `a.B`, and finds none). Examples from the full run after Step 21:

- static methods imported with a wildcard (ffmpeg `FFmpegUtilsTest`: `import static net.bramp.ffmpeg.FFmpegUtils.*`,
  `assertEquals("00:00:00.001", ▸ millisecondsToString(1))`) or one by one, from a nested class
  (`HttpConnection.Response.fixHeaderEncoding`, `Grid.FastGrid.isPowerOfTwo`) and from another test class
  (`WorkbookTest.getOutputTestPath`, `EvaluatorDebug.sexpr`);
- enum constants imported with a wildcard (jsoup `EntitiesTest`: `import static org.jsoup.nodes.Entities.EscapeMode.*`,
  `assertEquals(60, ▸ xhtml.▸ codepointForName("lt"))`).

A second defect showed up with the enum constants: the `IDENTIFIER` step matched classes, methods, fields, parameters
and lambda parameters, never enum constants. After an enum constant, even written after the name of its enum
(`EscapeMode.xhtml.`), no member was offered.

## Fix

`StaticImports.of(cu)` lists, for each `import static` of the test class, in the order of the imports:

- the static methods of the type named (declared or inherited; the methods of an import in the fixed order of
  `MemberOrder`), with the name imported or all of them for a wildcard;
- its static fields (declared or inherited; the fields of an interface are static) and, for an enum, its constants;
- following the access rules of Java: public; package-private or protected only when declared in the package of the
  test class; never private. The static methods of an interface are not inherited by the types implementing it.

Only the types with a declaration are read (the classes of the project, main and test sources, and the JDK sources):
the static imports of the libraries (JUnit, Hamcrest, Mockito) stay out, as the other classes of the dependencies,
until the dependencies step (scope approved for this step: "methods and fields imported statically from classes of the
project or of the JDK").

`setUpRootTokenIdentifiers` adds them to the root identifiers (methods, fields, enum constants), with the shadowing of
Java (JLS 6.4.1): a method of the test class hides the imported methods of the same name; a variable in scope (field,
local variable, parameter) hides an imported field or enum constant of the same name.

The `IDENTIFIER` step now matches enum constants: the members that follow are the ones of the enum (as for a field
whose type is the enum). This also offers the members after `EnumName.CONSTANT.`.

## Tests

`StaticImportsTokensTest` (5 end-to-end tests, oracles and tokens stages on a fixture repository):

- the members imported statically are offered: wildcard methods and fields, one method by name, a method of a nested
  class, of a class of the tests, of the JDK (`Math.max`), an enum constant;
- only the visible static members: not the other members of a single import, not package-private or private members
  of another package, not instance methods, not the JUnit assertions (a library);
- the members of a statically imported enum constant (`xhtml.codepointForName`);
- the members of an enum constant after the name of its enum (`Mode.base.codepointForName`);
- a field of the test class shadows an imported enum constant of the same name (after `xhtml.`, the members of
  `String`, not of the enum).

Checked on 2026-11-06 against the code of Step 24: 4 of the 5 tests fail (the shadowing test passes there trivially:
nothing was imported). With the shadowing rule disabled, the shadowing test fails.

## Verification

Regression corpus (`tools/ab/corpus.py`, 1,302 oracles), 2026-11-06, against the expected results of Steps 22–24:
31 oracles recovered (978 → 1,009 complete; ffmpeg 20, jsoup 7, uuid 3, cryptofs 1), **0 worse**. 311 oracles keep
their status with other candidates or contextual information, all explained:

- candidates added, none removed: the members imported statically (`asList`, `UTF_8`, the constants of
  `PosixFilePermission`, `READ_ERROR`, `DISABLE_INTRUSION_DETECTION`, the helpers of the test classes of eec);
- after an enum constant, the members of its enum, and the members of their results (steve:
  `ConfigurationKeyEnum.AuthorizationKey.name().` now offers the members of `String`);
- the contextual information of the steps after an imported member or an enum constant (`METHOD_CALL` with the method,
  `FIELD_CALL` with the enum, where it was `INIT` or `CLASS_CALL`).

The corpus holds 2 of the 11 test classes with static-import losses in the full run after Step 21; the 11 were run with
both jars (`tools/ab/dev_tokens.py`, Step 24 jar against Step 25 jar, oracles of the full run):

| Project | Oracles | Recovered | Worse |
|---|---|---|---|
| jsoup (`StringUtilTest`, `SelectorTest`, `EntitiesTest`, `QueryParserTest`, `HttpConnectionTest`) | 924 | 69 | 0 |
| eec (`StylesTest`, `ExcelReaderTest`, `GridTest`, `SimpleSheetTest`) | 337 | 41 | 0 |
| ffmpeg (`FFmpegUtilsTest`) | 20 | 20 | 0 |
| cryptofs (`CryptoFileSystemPropertiesTest`) | 29 | 0 | 0 |

130 oracles recovered, the count of the full run for this cause alone. The 2 cryptofs steps counted as `static import`
were misclassified by `missing_tokens.py` (members of local variables).

An existing defect seen in the outputs (Step 24 already): the private members of the JDK classes are offered after a
value of that type (`String.isMalformed3`, `Object.wait0`); added to the plan.
