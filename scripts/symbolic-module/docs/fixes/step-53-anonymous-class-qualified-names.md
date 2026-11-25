# Step 53 – The anonymous classes of interfaces are named after the qualified name of the interface

**Date:** 2026-11-25
**Branch:** `22-test-processing-refactoring` (on top of Step 52)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`: `processObjectCreationExpr`, `interfaceName` (new)
- tests: `TestUtilsDatapointsTest` (three tests changed, two new), `AnonymousClassNamesTokensTest` (an interface of the
  JDK), `DependencyLibrariesTokensTest` (an interface of a binary jar)
- docs: `docs/contextual-information.md` (the rule for the ids of the symbol table)

## Problem

Step 52 named the constructor of an anonymous class of an interface after the **simple** name of the interface
(`Anonymous-NutReaderListener()`). Two interfaces with the same simple name (`new Events.Handler() { … }` and
`new Jobs.Handler() { … }` in one test prefix) gave two equal entries, merged into one (`Anonymous-Handler()`): the
model could not tell them apart, and the compact format of the datasets considered for the large run
(`docs/contextual-information.md`: a symbol table per file, the datapoints referring to its symbols by id) could match
two different symbols to one entry if its ids were built from the names. Raised by the author on 2026-11-25, who chose
the qualified name, on the condition that Java accepts it.

## Fix

`interfaceName`: the name of the interface as Java accepts it in the source:
- its **canonical name**, `TypeDeclaration.getFullyQualifiedName()` when the declaration has a source (the project, the
  JDK sources of `lib`, the sources and stubs of the dependencies), else JavaParser's qualified name (a binary jar of
  `processed_libs/jars`, Step 38): dotted (`com.acme.Outer.Mid.Deep`, `java.lang.Thread.UncaughtExceptionHandler`, never
  the `$` of the binary name), without type arguments (`new Comparator<String>() { … }` ▸
  `Anonymous-java.util.Comparator()`);
- for a **local** interface (declared in a method, Java 16+) or one nested in a local class, which have no canonical name
  in Java, the name written in the creation (`Anonymous-Local()`, `Anonymous-Holder.Nested()`): JavaParser's qualified
  name of a local type (`com.acme.CalcTest.Local`) is not valid Java; `getFullyQualifiedName()` is empty for exactly
  these types.

Checked on JavaParser 3.28.2 before the tests (probes, scratch): nested, doubly nested and generic interfaces of the
project, `Comparator` and `Thread.UncaughtExceptionHandler` of the JDK, a local interface, an interface nested in a local
class, a nested interface read from a jar (`JavassistInterfaceDeclaration`: `org.gadgets.Gadgets.Listener`).

The `Anonymous-` prefix is not Java (it was not with the random names either); Java has no name for the constructor of an
anonymous class, and the model never writes it (the invoked methods are context, not candidates).

## Tests

- `TestUtilsDatapointsTest`: the three tests of Step 52 now expect the qualified name
  (`Anonymous-com.acme.Listener()`; `constructorOfAnAnonymousClassOfANestedGenericInterfaceIsNamedAfterItsQualifiedName`:
  `new Events.Handler<String>() { … }` ▸ `Anonymous-com.acme.Events.Handler()`); new:
  - `constructorsOfAnonymousClassesOfTwoInterfacesWithTheSameSimpleNameAreTwoInvokedMethods`: `Events.Handler` and
    `Jobs.Handler` ▸ two entries (with the code of Step 52: one, `Anonymous-Handler`);
  - `constructorOfAnAnonymousClassOfALocalInterfaceIsNamedAsWritten`: a local interface and an interface nested in a
    local class ▸ `Anonymous-Local`, `Anonymous-Holder.Nested`.
- `AnonymousClassNamesTokensTest`: `Anonymous-com.acme.Listener()`; a new test method of the project with an anonymous
  `Comparator<String>` (the JDK sources of `lib`) ▸ `Anonymous-java.util.Comparator()`.
- `DependencyLibrariesTokensTest.theConstructorOfAnAnonymousClassOfAnInterfaceOfAJarIsNamedAfterItsQualifiedName`: a
  nested interface of a binary jar (`processed_libs/jars`, no source) ▸ `Anonymous-org.gadgets.Gadget.Listener()`.

Against the code of Step 52: the six tests of `TestUtilsDatapointsTest` and `AnonymousClassNamesTokensTest` that name
an interface fail (the simple name; the two `Handler`s one entry). The jar test was written after the code; the mutation
of its branch (the simple name) makes it fail. Each part checked by a mutation, each caught: the simple name back (7
tests), no branch for the declarations with a source (the local test: `com.acme.CalcTest.Local`), JavaParser's qualified
name for the local types, their simple name (`Anonymous-Nested`), the simple name for a jar (the jar test), the name as
written everywhere (5 tests).

## Verification

482 Java tests pass (`mvn clean package`; jar `tmp/ab/jar-step53.jar`).

**Corpus** (`tmp/ab/corpus-prepare-step53.txt`, `tmp/ab/corpus-check-step53.txt`; 118 s + 317 s): **identical** to the
expected results (1,302 complete); no accept. The outputs of Step 52 kept before the run (`tmp/ab/step53-before`) and
compared file by file:
- tokens stage: the datasets and statistics of the nine projects byte-identical (only the logs differ);
- oracles stage: 926 files byte-identical; the 16 entries of Step 52 renamed, nothing else in their datapoints:

  | Project | Step 52 | Step 53 |
  |---|---|---|
  | eec (4 prefixes) | `Anonymous-Callback()` | `Anonymous-okhttp3.Callback()` (a dependency) |
  | ffmpeg (1) | `Anonymous-NutReaderListener()` | `Anonymous-net.bramp.ffmpeg.nut.NutReaderListener()` |
  | jsoup (4) | `Anonymous-NodeVisitor()` | `Anonymous-org.jsoup.select.NodeVisitor()` |
  | jsoup (1) | `Anonymous-NodeFilter()` | `Anonymous-org.jsoup.select.NodeFilter()` |
  | jsoup (6) | `Anonymous-RequestAuthenticator()` | `Anonymous-org.jsoup.helper.RequestAuthenticator()` |

- one more change in eec, **not caused by the fix**: `SimpleSheetTest.testSimpleSheetPaging_split_1` loses one invoked
  method (`setData(BiFunction<Integer, T, List<T>>)`, 20 → 19 entries) and gains an error
  (`Return-Type-Substituable must be implemented on reference type.`), and the error messages of the same statement in
  `SimpleSheetTest` and `ListMapSheetTest` list JavaParser's "equivalent types" of the lambda `(i, a) -> { … }` in
  another order (`[InferenceVariableType{id=1}, Integer, Object]` instead of `[…, Object, Integer]`). Each jar always
  gives the same result (eec's oracles stage twice with each jar: Step 52 20 entries both times, Step 53 19 both
  times); with `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` (every identity hash code equal) both jars give 20,
  and the whole outputs of eec of the two jars differ only by the 4 names (and the folder of the run in
  `test-stats-eec.json`). So the type inference of JavaParser iterates a set ordered by identity hash codes; HotSpot
  generates them as a fixed sequence per thread, so a run is reproducible, but any change of the code that allocates
  or hashes differently can move an unrelated result (as Step 21 found in the tokens stage). A defect of the oracles
  stage before this step (`docs/resume.md`, section 6, item 10); the invoked methods are context of the prompt, not
  candidates (the tokens stage does not read them).
