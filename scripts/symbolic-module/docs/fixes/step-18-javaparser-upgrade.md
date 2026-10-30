# Step 18 – JavaParser 3.28.2 and a single parser configuration

**Date:** 2026-10-29
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `9263e3f4`, which commits Step 17)
**Files changed:**
- `pom.xml` (JavaParser 3.26.1 → 3.28.2)
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (`setRepoJavaParser`, `resolveMethodCall`)
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (`processInvokedMethods`)
- tests: `JavaParserUtilsTest`, `TestUtilsDatapointsTest`
- docs: `docs/design-review-2026-10-26.md`

## Problem

The repositories under analysis are written in Java 8 to Java 25 (the versions declared by the 135 input repositories,
Step 17), and `lib` holds the sources of JDK 8 to 27. Two limits kept the parser behind:

- **Two configurations.** `setRepoJavaParser` parsed the code under analysis (test classes, focal classes) at
  `LanguageLevel.JAVA_17`, and the type solvers (the classes of the projects and the JDK sources) at the default level
  of `JavaParserTypeSolver`, `BLEEDING_EDGE`.
- **JavaParser 3.26.1** knows the language levels up to `JAVA_21` (its `BLEEDING_EDGE`), and does not parse all of
  Java 21 either.

The pipeline never looks at the problems of a parse (`getResult().orElseThrow()`): a construct that the parser rejects
gives a compilation unit without errors reported, where the code around the construct is lost or broken. With 3.26.1:

| Syntax | Main parser (`JAVA_17`) | Type solvers (`JAVA_21`) |
|--------|-------------------------|--------------------------|
| Java 21 type patterns in `switch` (`case String s when ...`) | rejected | accepted |
| Java 21 record patterns (`case P(int x, int y)`, `o instanceof P(int x, int y)`) | parse error | parse error |
| Java 22 unnamed variables (`catch (IOException _)`, `_ -> ...`) | rejected | rejected |
| Java 22 unnamed patterns (`P(int x, _)`) | parse error | parse error |
| Java 25 statements before `super()`, `import module` | parse error | parse error |

The JDK sources themselves use this syntax: 17 to 33 files of `java.base` of JDK 24 to 27 have problems with 3.26.1.

In the input repositories the newer syntax is still rare: `catch (IOException _)` in a test of cryptofs, record
patterns in the main code of cryptofs and paintera, unnamed variables in mediathekview, type patterns in `switch` in
steve, pixelitor, cbioportal and a few others.

## Fix

- JavaParser 3.28.2 (the latest release; `javaparser-symbol-solver-core`, which brings `javaparser-core` 3.28.2). The
  module compiles without changes.
- `setRepoJavaParser` builds one `ParserConfiguration`, at `LanguageLevel.JAVA_26` (the highest level of 3.28.2,
  named instead of `BLEEDING_EDGE` so that it does not change silently with a later upgrade of JavaParser), and uses it
  for the parser of the code under analysis, the type solvers and `StaticJavaParser`; the symbol solver is set on it
  once created (the mechanism of Step 16).

A higher language level accepts the code of the older versions: the Java 8 code that names a variable, a field or a
method `var`, `record`, `sealed` or `yield` is accepted at `JAVA_26`. The use of `_` as an identifier, valid in Java 8,
is rejected, as it already was at the previous `JAVA_17`.

## Defects of JavaParser 3.28.x and workaround

The first A/B run (below) found two defects of method resolution in 3.28.x. Both are fixed on the master branch of
JavaParser, after 3.28.2, the latest release (2026-05-31).

**Arrays of primitive types (JavaParser issues #4991, #5065, #5146).** Since 3.28.0 (commit `d82e6d91b`, made for the
grouped values of a variable arity call, #4890), `MethodResolutionLogic.isBoxingCompatibleWithTypeSolver` compares two
array types component by component with boxing and widening, and it is also used for the ordinary parameters. Arrays
of primitive types are invariant (JLS 4.10.3), but a `byte[]` is accepted for `int[]`, `long[]`, `float[]` or
`double[]`, and an `int[]` for `Integer[]` or `Object[]`. A standalone reproduction (16 calls of overloads with array
parameters, compared with the overload javac selects) gives 1 wrong call with 3.27.1 (the variable arity case #4890
fixed), 7 with 3.28.2:

- `byte[]`, `short[]`, `char[]`, `int[]` and `long[]` arguments of overloads taking other primitive arrays or
  `Integer[]`/`Object[]`: `MethodAmbiguityException` (`Arrays.sort(byte[])`, `Arrays.sort(int[])`, `Arrays.equals`);
- `m(int[])` with the overloads `m(Integer[])` and `m(Object)`: `m(Integer[])` instead of `m(Object)`, without error;
- `m(long...)` with an `int[]` argument: accepted, javac rejects it (#5146).

`float[]`, `double[]` and `boolean[]` arguments, arrays of references and arrays of arrays are resolved correctly. The
fix of #4991 (#5147, commit `228a954`) leaves the variable arity case #5146 open (assigned to a maintainer); a fix for it
was prepared (see below).

**Return type of the candidate methods.** In 3.28.x, `JavaParserTypeDeclarationAdapter.solveMethod` resolves the return
type of the candidate methods while choosing one, and the resolution fails when that type is missing from the classpath:
`new ValidatorTestContainer(mockValidator).throwException()` in esapi, a helper returning the `Answer` of Mockito, which
the pipeline does not give to the symbol solver ("Unsolved symbol : Answer"). Fixed on master by the refactoring of the
member lookup (`a9eed99`, `15bf161`).

**Other errors of 3.28.2.** On a lambda of eec, the resolution throws an `UnsupportedOperationException`
("Return-Type-Substituable must be implemented on reference type"), which the matching of the invoked methods did not
catch per statement: it stopped the matching of the whole test prefix.

**Workaround.** `JavaParserUtils.resolveMethodCall` resolves a method call and, when JavaParser reports it ambiguous or
cannot resolve it, returns the method of the type of the scope with the name and the number of arguments of the call
when it is the only one (the helper methods of the tests are rarely overloaded: the matchers of cryptofs, whose return
type `Matcher` of Hamcrest is missing from the classpath), and, among several overloads, the only one whose parameter
types are exactly the types of the arguments (the overload javac selects in the cases of the arrays). It is used by the
matching of the invoked methods (`resolveCallExpression` and the methods of the libraries in `processInvokedMethods`).
Any runtime exception left now drops the invoked methods of its statement only (the matching caught a list of
exceptions per statement, and any other one stopped the matching of the whole test prefix, "Unexpected error while
matching focal method").

Depending on the order in which it meets the candidates (its caches are filled by the previous resolutions), JavaParser
3.28.x also chooses a wrong overload without any error: in the A/B run, `Arrays.copyOf(array, array.length)` of jsoup,
with a `char[]`, was resolved to `copyOf(double[], int)`, while the same call resolved alone throws the ambiguity. So,
after a resolution, when a parameter of an array type receives an argument of another array type, one of the two holding
primitive values (never selected by javac), the overload whose parameter types are exactly the argument types replaces
it, when there is one. The other silent wrong choice of the reproduction (`m(Integer[])` instead of `m(Object)` for an
`int[]`) is not corrected, as no overload matches exactly; it needs an overload taking an array of the boxed type next to
one taking a supertype of the array, and disappears with the next release of JavaParser.

**Upstream.** The material prepared for JavaParser is in `tmp/javaparser-issue/` (not committed): the reproduction
(`ArrayOverloads.java`, `run.sh`) and its output for 3.27.1, 3.28.2 and master with the fix of #5146, the fix of #5146
(`fix-5146.patch`: `MethodResolutionLogic` and `Issue5146Test`, 18 cases; the JavaParser symbol solver suite passes,
2,190 tests), the description of the pull request and a comment offering it on #5146.

## Tests

Written before the upgrade, against 3.26.1:

| Test | Checks | With 3.26.1 |
|------|--------|-------------|
| `JavaParserUtilsTest.parserAcceptsTheSyntaxOfJava8To26` (11 cases) | the parser of `setRepoJavaParser` parses without problems: Java 8 names (`var`, `record`, `sealed`, `yield`), switch expressions, records, sealed classes, type and record patterns in `switch` (Java 21), unnamed variables and patterns (Java 22), markdown documentation comments (Java 23), statements before `super()` and module imports (Java 25) | 6 failing (Java 21, 22 and 25) |
| `JavaParserUtilsTest.projectClassWithNewSyntaxIsResolvedThroughTheTypeSolver` | a project class written with Java 25 syntax (`import module java.base;`, statements before `super()`) is found by the type solver, and a call of its method resolves | failing ("Unsolved symbol: Box") |
| `TestUtilsDatapointsTest.testCaseWithARecordPatternProducesItsDatapoint` | a test case asserting within `if (pair instanceof Pair(int a, int b))` gives its datapoint, with the record pattern in the test prefix | failing (no datapoint) |

Written after the first A/B run, for the workaround:

| Test | Checks | Before the workaround |
|------|--------|-----------------------|
| `JavaParserUtilsTest.resolveMethodCallPicksTheOverloadWhoseParametersAreExactlyTheArgumentTypes` | `Bytes.count(bytes)` with `count(byte[])`, `count(int[])`, `count(long[])` of a project class gives `count(byte[])`, also with a qualified scope | `MethodAmbiguityException` |
| `JavaParserUtilsTest.resolveMethodCallResolvesAMethodWhoseReturnTypeIsUnknown` | the method of an inner class returning a type missing from the classpath (`Answer`) | "Unsolved symbol : Answer" |
| `JavaParserUtilsTest.resolveMethodCallResolvesTheOnlyMethodWithTheNameAndArityOfTheCall` | `Matchers.hasCapacity(size)` (`size` an `Integer`, the parameter an `int`, the return type `Matcher` missing from the classpath) | "Unsolved symbol" |
| `JavaParserUtilsTest.resolveMethodCallCorrectsAPrimitiveArrayArgumentResolvedToAnotherArrayType` | the resolution of `Bytes.copyOf(chars, chars.length)` replaced (Mockito spy) by `copyOf(double[], int)`, the choice of JavaParser in jsoup: `copyOf(char[], int)` | `copyOf(double[], int)` |
| `JavaParserUtilsTest.resolveMethodCallStillFailsWhenNoOverloadMatchesExactly` | `count(ints)` with `count(long[])` and `count(double[])` (rejected by javac) is still not resolved | passing |
| `TestUtilsDatapointsTest.callWithAPrimitiveArrayArgumentToAnOverloadedMethodIsAnInvokedMethod` | the invoked methods of a prefix with `Bytes.count(data)` are `Calc`, `count(byte[])`, `accumulate` | `Calc` only |
| `TestUtilsDatapointsTest.unexpectedErrorWhileMatchingAStatementDropsOnlyItsInvokedMethods` | an `UnsupportedOperationException` injected (static mock) for the call of `add` drops `add` only: the invoked methods are `Calc`, `accumulate` | `Calc` only |

Full suite: 250 tests, 0 failures, 0 errors, 0 skipped (no existing test changes with the upgrade).

**JDK sources.** `java.base` of JDK 8, 21, 24, 25, 26 and 27 parsed at `JAVA_26` with 3.28.2: 0 files with problems
(with 3.26.1 at `JAVA_21`: JDK 24 17 files, JDK 25 21, JDK 26 26, JDK 27 33).

## Verification on real projects

**Oracles dataset.** The seven projects of the previous steps and two projects with newer syntax: cryptofs (Java 25,
`catch (IOException _)` in a test, records and record patterns in its main code) and steve (Java 21, type patterns in
`switch` in its main code). Fresh copies, committed configuration, `auto`; the jar of Step 17 (`9263e3f4`) against the
jar of Step 18 with the workaround. The first runs of Step 18, without the workaround or with part of it, found the
defects of 3.28.x described above.

| Project | Datapoints | Invoked methods | Errors of the invoked methods |
|---------|------------|-----------------|-------------------------------|
| crawler-commons | 732 → 732 | 4,933 → 4,933 | 22 → 22 |
| java-uuid-generator | 369 → 369 | 2,115 → 2,115 | 13 → 13 |
| ffmpeg-cli-wrapper | 586 → 586 | 5,507 → 5,512 | 7,007 → 7,002 |
| plexus-utils | 785 → 785 | 3,872 → 3,872 | 85 → 85 |
| esapi | 528 → 528 | 1,855 → 1,855 | 2,965 → 2,965 |
| eec | 1,427 → 1,427 | 20,401 → 20,695 | 527 → 515 |
| jsoup | 3,653 → 3,653 | 17,578 → 17,591 | 147 → 134 |
| cryptofs | **825 → 909** | 3,509 → 3,610 | 2,279 → 3,444 |
| steve | 163 → 163 | 260 → 265 | 606 → 608 |

- The datapoints of Step 17, their targets and test prefixes, are the same.
- cryptofs: 10 test classes more (68 → 78, +84 datapoints). With 3.26.1, 9 split test classes failed as a whole
  ("Cannot get a reference type declaration from RecordDeclaration": the focal classes are records), and the
  normalization of `RealUseTokenTest` failed ("Symbol resolution not configured", the two parser configurations). The
  new datapoints checked by hand are correct (for example an `assertThrows` target of `ChunkCacheTest`, and the test
  prefixes of `RealUseTokenTest` with `var` declarations and the invoked methods of JDK 25). Its errors grow with the
  new test classes.
- Invoked methods added: the methods with a type parameter in their signature or reached through a generic type
  (`E next()`, `Stream.map`, `List.sort`), and project methods 3.26.1 could not resolve (`toFile`, `asFullSheet` of
  eec, `urlTo` of jsoup); the descriptions of the test and focal classes gain the fully qualified names of types 3.26.1
  could not resolve (type parameters bound to `Object`, generic return types), and in eec a Javadoc.
- Invoked methods lost: 16 in cryptofs, matchers with a return type of Hamcrest (missing from the classpath) called
  through a static import (`argThat(hasAtLeastRemaining(...))`): the workaround looks for the method in the type of the
  scope, and an unqualified call has none (it is looked for in the test class). They come back with the next release of
  JavaParser. In steve, `add` and `remove` of a store are now the methods of the class of the variable
  (`public boolean add(...)`) instead of those of its interface: the same 12 methods.
- The run times are similar (between 0.6 and 1.7 times those of Step 17: the runs of Step 17 were made at the same time
  as other runs).

**Tokens dataset.** The tokens stage on crawler-commons, Step 17 and Step 18 (the workaround is not used by the tokens
stage): the same 727 datapoints; 1,093 → 1,094 errors; the steps whose target is not among the candidates 366 → 367
(`isValid` in `assertFalse(sm.getSiteMapUrls().iterator().next().isValid())`: the element type of the iterator of a
`Collection<SiteMapURL>` is not inferred at that step); the candidate project tokens change in 8 of 9,596 steps (the
methods of `Object`).

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
