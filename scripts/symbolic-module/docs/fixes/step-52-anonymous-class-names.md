# Step 52 – The anonymous classes of interfaces have the same name at each run

> **Changed by Step 53** (`docs/fixes/step-53-anonymous-class-qualified-names.md`): the name is the qualified name of
> the interface (`Anonymous-net.bramp.ffmpeg.nut.NutReaderListener()`), not its simple name: two interfaces with the
> same simple name were one entry.

**Date:** 2026-11-25
**Branch:** `22-test-processing-refactoring` (on top of Step 51 and phase C of `docs/mac-generation.md`)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`: `processObjectCreationExpr`
- tests: `AnonymousClassNamesTokensTest` (new, end-to-end), `TestUtilsDatapointsTest` (four tests)
- tools: `tools/ab/corpus.py` (the mask of the random names removed), `tools/ab/compare_tokens.py` (comment)

## Problem

The `invokedMethods` of a test prefix list the methods and constructors called by its statements; the prompt builder
shows their signatures to the model (`scripts/prompt/src/EWashQwen.py`). A constructor without a declaration in the
sources is described from its JavaParser resolution (`processObjectCreationExpr`, unchanged since 2025-08-15): the
name and the signature of the resolved constructor.

The creation of an anonymous class of an **interface** (`new NutReaderListener() { … }`) calls no constructor of the
interface (it has none): JavaParser 3.28.2 resolves it to the default constructor of the anonymous class itself
(`JavaParserAnonymousClassDeclaration.getConstructors()`: a `DefaultConstructorDeclaration` when the supertype is an
interface), whose name is the name of the class, and JavaParser names the class `"Anonymous-" + UUID.randomUUID()`
(a field of `JavaParserAnonymousClassDeclaration`, a new UUID for each instance, and the symbol solver builds a new
instance each time it resolves the creation). The source has no name for the class (the compiler calls it
`NutReaderTest$1`, which JavaParser never sees). So the datasets had entries such as
`Anonymous-45b2d3a2-c43d-4f3f-9c0b-47b1c40a57c1()`, different at each run:

1. **Not reproducible:** the same inputs gave different bytes (ffmpeg at phase B: 1 of 21 datapoints differed between two
   runs, 58 bytes).
2. **Noise in the prompt:** a random string of 36 characters among the invoked methods.
3. **Entries not merged:** the invoked methods of a prefix are listed once (`new ArrayList<>(new LinkedHashSet<>(…))`),
   but each resolution of the same creation gave another name: two anonymous classes, one in the other in a lambda,
   gave five entries (`AnonymousClassNamesTokensTest`).

An anonymous class of a **class** (`new TypeRef<List<String>>() {}`, `new ArrayList<String>() {{ … }}`,
`new Reader(field) { … }`) resolves to the constructor of the class (`protected TypeRef()`): no random name.

**Not new.** The jar of the last commit before the refactoring (`7a583a06`, 2026-03-10, `tmp/ab/jar-original-7a583a06.jar`),
run twice on the project of `AnonymousClassNamesTokensTest`: 5 such entries in each run, with other names, and the
oracles datasets of the two runs differ. Since Step 16 (symbol resolution of the classes reached through the type
solvers) more creations are resolved: the same project has them in 3 test prefixes instead of 1. Seen since Step 16
(its A/B: 18 entries that differ only by their name); `compare_tokens.py` (2026-11-04) and `corpus.py` (2026-11-05)
masked them (`Anonymous-<uuid>`).

**Size.** Phase C (jar of Step 51): ffmpeg 1 of 586 datapoints (`NutReaderTest.testNutReader_split_0`), steve 0 of
163, uuid 0 of 369. The oracles datasets of the corpus projects: 52 occurrences (eec 16, ffmpeg 2, jsoup 34); the
tokens datasets of the corpus classes: none.

## Fix

Decided by the author on 2026-11-25 (of three options: the binary name of the compiler `NutReaderTest$1`, the class and
position `Anonymous-NutReaderTest-1`, the interface): **named after the interface.** In `processObjectCreationExpr`,
when the declaring type of the resolved constructor is a `JavaParserAnonymousClassDeclaration`, the identifier is
`Anonymous-` + the simple name of the interface as written (`getType().getNameAsString()`: without scope and type
arguments, as JavaParser names the constructors of the classes: `Anonymous-Handler` for
`new Events.Handler<String>() { … }`), and the signature is that name followed by the parameters of the JavaParser
signature (`()`: the default constructor has none; `getSignature()` is `getName()` + the parameters, not overridden).

Two anonymous classes of the same interface in a prefix are now one entry (the entries are equal: no parameters, no
body); the model sees `Anonymous-NutReaderListener()`, which tells what the object is.

`ResolvedTypeDeclaration.isAnonymousClass()` cannot be used: `JavaParserAnonymousClassDeclaration` does not override it
(`false`); found when the first version of the fix left the tests failing.

Unchanged: the error recorded in the statistics for some of these creations
(`Unable to resolve constructor type Listener in test class …`: the search of a constructor declaration in the sources,
before `processObjectCreationExpr`); it has no random name.

## Tests

- `TestUtilsDatapointsTest`:
  - `constructorOfAnAnonymousClassOfAnInterfaceIsNamedAfterTheInterface`: `new Listener() { … }` ▸ invoked methods
    `Calc`, `Anonymous-Listener`, `on`; signature `Anonymous-Listener()`, no parameters;
  - `constructorOfAnAnonymousClassOfANestedGenericInterfaceIsNamedAfterItsSimpleName`:
    `new Events.Handler<String>() { … }` ▸ `Anonymous-Handler()`;
  - `constructorsOfTwoAnonymousClassesOfTheSameInterfaceAreOneInvokedMethod`: two anonymous `Listener`s ▸ one entry;
  - `constructorOfAnAnonymousSubclassIsTheConstructorOfTheClass`: `new Calc() { … }` ▸ `Calc()`, as before (a guard:
    passes before the fix).
- `AnonymousClassNamesTokensTest` (end-to-end, both stages): a project with anonymous classes as an argument, a local
  variable, a `var` with members, a generic class, a double-brace initialization, in a lambda and nested, in the focal
  method, in an assertion, a subclass with arguments, in a field and in the setup.
  - `noRandomNameOfAnAnonymousClassInTheDatasets`: no `Anonymous-<UUID>` in any file of the output;
  - `constructorOfAnAnonymousClassOfAnInterfaceIsNamedAfterTheInterface`: the invoked methods in the tokens dataset
    (`public Reader(Listener listener)`, `Anonymous-Listener()`; the five entries of the lambda case now
    `void on(String event)`, `Anonymous-Listener()`; `protected TypeRef()` and `public Reader(Listener listener)` for
    the classes, as before);
  - `twoRunsWriteTheSameDatasets`: the same project in two folders, both stages twice in the same JVM: the oracles and
    tokens datasets byte-identical.

Against the code of Step 51: the six tests of the new behavior fail, each on a random name (the two runs differ only by
them); the guard passes. With the fix all pass.

Each part of the fix checked by a mutation, each caught: every resolved constructor renamed (the guard and four
earlier tests of `TestUtilsDatapointsTest`), none renamed (6 tests), the type arguments kept
(`Anonymous-Events.Handler<String>`) and the scope kept (`Anonymous-Events.Handler`) (the nested generic test), no
`Anonymous-` prefix (4), the signature not renamed (6), the identifier not renamed (6).

## Verification

479 Java tests pass (`mvn clean package`; jar `tmp/ab/jar-step52.jar`).

**Corpus** (`tmp/ab/corpus-prepare-step52.txt`, `tmp/ab/corpus-check-step52.txt`; `prepare` 119 s at `-j 2`, `check`
321 s at `-j 4`): **identical** to the expected results (1,302 complete), now with the digests computed without the
mask (`corpus.py`): no accept. The outputs of Step 51 kept before the run (`tmp/ab/step52-before`, APFS clones) and
compared file by file:
- tokens stage: the 27 datasets and statistics of the nine projects byte-identical (only the logs differ: times). The
  corpus classes have no such entry among their invoked methods;
- oracles stage: 926 files byte-identical; the cryptofs `errors-statistics` differs by the identity hash of an error
  message (`ResolvedVoidType@7ea7ad33` → `@7feae5bf`, known: `docs/resume.md`, section 1); the oracles datasets of eec,
  ffmpeg and jsoup differ in 16 datapoints, only in their invoked methods, only in the constructors of anonymous
  classes (everything else equal and in the same order; checked by a script): 26 entries `Anonymous-<uuid>()` become
  16 named ones, the same fields otherwise (no parameters, no body):

  | Project | Test prefixes | Before | After |
  |---|---|---|---|
  | eec | `PictureTest.testAsyncRemoteImage_split_0..3` | 2 random each | `Anonymous-Callback()` |
  | ffmpeg | `NutReaderTest.testNutReader_split_0` | 1 random | `Anonymous-NutReaderListener()` |
  | jsoup | `NodeTest.traverse_split_0`, `ElementTest.testTraverse_split_1..2`, `ElementsTest.traverse_split_0` | 1 random each | `Anonymous-NodeVisitor()` |
  | jsoup | `ElementTest.testFilterCallReturnsElement_split_1` | 1 random | `Anonymous-NodeFilter()` |
  | jsoup | `HttpConnectionTest.setAuth_split_1..6` | 2 random each | `Anonymous-RequestAuthenticator()` |

  The ten pairs, now one entry each: in eec, one anonymous `Callback` (inside an anonymous `XMLWorksheetWriter`, a
  class: its creation resolves to the constructor of `XMLWorksheetWriter`, unchanged) resolved twice, so two names; in
  jsoup, two anonymous `RequestAuthenticator`s (`auth1`, `auth2`), equal entries once named after the interface.

**ffmpeg twice** (the generation runner, `tools/gen/generate.py run`, `-j 1 --xmx 12g`, the same pinned commit as phase
C; `tmp/gen/step52-a`, `tmp/gen/step52-b`): 586 datapoints in 532 and 538 s, both `done`. The 70 files of the outputs
compared byte by byte: the oracles and tokens datasets and every statistics file **identical**, except
`test-stats-ffmpeg.json`, which records the absolute path of each test class (`filePath`, the folder of the run):
identical once `step52-a`/`step52-b` is replaced. Compared with the output of phase C (jar of Step 51,
`tmp/gen/e2e`): 67 files identical, the oracles and tokens datasets identical once their 2 random names (the
`NutReaderListener` of `testNutReader_split_0`) are replaced by `Anonymous-NutReaderListener`, `test-stats` once the
folder of the run is replaced.
