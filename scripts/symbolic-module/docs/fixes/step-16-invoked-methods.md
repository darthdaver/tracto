# Step 16 – Invoked methods of the test prefixes

**Date:** 2026-10-28
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `dd809d08`, which commits Step 15)
**Files changed:**
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (`setRepoJavaParser`)
- tests: `TestUtilsDatapointsTest`, `JavaParserUtilsTest`
- docs: `docs/design-review-2026-10-26.md`

## Defect

The `invokedMethods` of a test prefix list the methods and constructors called by the statements of the prefix; the
prompt builder (`scripts/prompt/src/EWash.py`) adds them to the context of the model. They are collected by
`TestUtils.processInvokedMethods`, which records in the processing errors of the split test case
(`errors-statistics-*.json`) every statement whose calls it cannot describe. The errors of the Step 15 A/B run showed
that this happens often: 2,446 of the 8,080 datapoints of the seven projects have errors, and the most common message
(4,738 statements, in 1,545 datapoints) is

```
Symbol resolution not configured: to configure consider setting a SymbolResolver in the ParserConfiguration
```

Even methods of the focal class are missed: in crawler-commons, `trie.put(s, true)` is not an invoked method of the
datapoints of `SuffixTrieTest`, although `SuffixTrie` is the focal class.

**D34 – cause.** `JavaParserUtils.setRepoJavaParser` builds the symbol solver from a `JavaParserTypeSolver` per
source folder (the projects of the repository and the JDK sources of `lib/java*`). Each type solver parses the classes
it finds with its own parser configuration, the default one, which has no symbol resolver: only the code under
analysis, parsed by the parser of `setRepoJavaParser`, has one. A declaration reached by resolving the code under
analysis (`resolve().toAst()`, `getWrappedNode()`) belongs to a compilation unit of a type solver, and resolving any
node of it throws the exception above.

`processInvokedMethods` takes the declaration of the type of a variable from the symbol solver
(`JavaParserClassDeclaration.getWrappedNode()`) and matches the calls on the variable against its methods. Matching
`put` succeeds, but describing it (`processCallable` → `processType` → `processTypeParameter`) resolves the type
parameter `V` of `SuffixTrie<V>` in its signature (`TypeParameter.toDescriptor()`), which throws: the statement is
recorded as an error, and the method is not added. The same happens to the methods of the JDK classes (whose sources in
`lib/java*` are parsed by type solvers too) with a type parameter in their signature (`List<E>.get`,
`Iterator<E>.next`, `Map<K, V>.put`), and to any other resolution within a declaration of a type solver.

The defect predates this branch: the setup of the type solvers dates from `790b1b23` (August 2025, on `main`), and the
Step 8 A/B run has the same number of these errors on the classes it processed as the Step 15 run.

## Fix

`setRepoJavaParser` creates the parser configuration of the type solvers itself, the same as the default one
(`LanguageLevel.BLEEDING_EDGE`, so the classes are parsed as before), passes it to every `JavaParserTypeSolver`, and
sets the symbol solver on it once the symbol solver is created. JavaParser attaches the symbol resolver of the
configuration to each compilation unit when it is parsed, so the classes parsed by the type solvers can be resolved
like the code under analysis.

## Tests

Written before the fix, both failing:

| Test | Checks |
|------|--------|
| `JavaParserUtilsTest.declarationOfAProjectClassReachedByTheSymbolSolverCanBeResolved` | the parameter type `T` of `Box<T>.put(T)`, reached by resolving `box.put(this)`, resolves to a type variable (before: "Symbol resolution not configured") |
| `TestUtilsDatapointsTest.testPrefixListsTheMethodsOfGenericProjectClassesThatUseTheirTypeParameters` | `Box<Calc> box = new Box<>(); box.put(calc);` gives the invoked methods `Calc`, `Box`, `put`, with the parameter type `T` (before: `put` missing) |

The fixture repositories of the tests have no JDK sources (`JavaUtils.javaLibPath` is not set), so the calls on
variables of JDK types are not covered by the tests: they are checked by the A/B run below.

Full suite: 190 tests, 0 failures, 0 errors, 0 skipped.

## Verification on real projects

**Oracles dataset.** The seven projects of the previous steps, fresh copies, committed configuration, the jar of
Step 15 (`dd809d08`) against the jar of Step 16, compared datapoint by datapoint (the two runs were made in
`tmp/ab/` of the module, ignored by git):

| Project | Invoked methods | Added (of the focal class) | Duplicates merged | "Symbol resolution not configured" |
|---------|-----------------|----------------------------|-------------------|------------------------------------|
| crawler-commons | 4,741 → 4,862 | 122 (11) | 1 | 783 → 0 |
| java-uuid-generator | 2,039 → 2,115 | 76 (0) | 0 | 214 → 0 |
| ffmpeg-cli-wrapper | 5,355 → 5,507 | 153 (0) | 1 | 156 → 0 |
| plexus-utils | 3,753 → 3,872 | 123 (0) | 4 | 524 → 0 |
| esapi | 1,830 → 1,855 | 32 (3) | 7 | 67 → 0 |
| eec | 19,335 → 20,421 | 1,095 (34) | 9 | 2,534 → 0 |
| jsoup | 17,422 → 17,578 | 253 (32) | 97 | 460 → 0 |

- The datapoints, their targets and test prefixes, and the descriptions of the test and focal classes are the same in
  the two runs; the other errors of the invoked methods (unsolved types of the test libraries, which the pipeline does
  not give to the symbol solver, and unmatched calls) are the same.
- Added: 1,854 invoked methods, 80 of the focal class (`SuffixTrie.put` in crawler-commons, `put` and `get` of the
  focal classes of eec, `borrow` and `release` in jsoup) and 1,774 of other classes, mostly JDK methods with a type
  parameter in their signature (`List.get`, `Iterator.next`, `Map.put`, `List.add`). The JDK methods without one
  (`File.exists`) were already listed.
- Duplicates merged: 119 invoked methods listed twice by the same prefix (`destination.listFiles()` and
  `current.listFiles()`) are now listed once, as the list of invoked methods intends (it removes the equal entries).
  The two entries differed before, because the resolution failure left the fully qualified name of the return type of
  one of them empty.
- The anonymous classes are named with a random UUID (`Anonymous-4b34c8da-…`), which differs at each run: 18 such
  entries differ only by their name.
- No crash; the run times are similar (the two runs were made at the same time, so they are approximate).

**Tokens dataset.** The tokens stage on java-uuid-generator, Step 15 and Step 16, and a second run of Step 16 on a
copy, to separate the effect of the fix from the differences between two runs of the same jar:

| | Step 15 → Step 16 | Step 16, two runs |
|---|---|---|
| Datapoints | 366 → 366 | same |
| Invoked methods of the test prefixes | 76 datapoints with new methods, 197 with the same methods described completely | same |
| Candidate project tokens of the steps | same tokens; order differs in 1,704 of 5,183 steps | same tokens; order differs in 1,755 steps |
| Invoked methods of the contextual infos | same methods; order differs in 1,435 steps | same methods; order differs in 1,429 steps |
| Fields of the contextual infos | same fields; order differs in 359 steps (358 in `EthernetAddressTest`) | order differs in 1 step |
| Errors logged | 713 → 713 | same |

The order of the candidate tokens and of the contextual invoked methods changes at each run, by as much as between the
two jars: the tokens dataset is not reproducible in these orders. The reordered tokens are variables and methods alike
(`toString`, `finalize`, `Files.setOwner`); the collections that give this order were not traced. Apart from these
orders, the fix adds the new invoked methods and reorders the fields of the contextual infos of one test class, with
the same content.

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
