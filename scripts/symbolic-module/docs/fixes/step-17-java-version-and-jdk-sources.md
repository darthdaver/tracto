# Step 17 – Java version of the repository and JDK sources

**Date:** 2026-10-29
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `92e1e7dd`, which commits Step 16)
**Files changed:**
- `src/main/java/star/tracto/utils/JavaVersionDetector.java` (new)
- `src/main/java/star/tracto/utils/JavaUtils.java` (selection of the JDKs)
- `src/main/java/star/tracto/SymbolicModule.java`, `src/main/java/star/tracto/Client.java` (Java version argument)
- `src/main/java/star/tracto/preprocessing/OraclesDataset.java` (statistics)
- `bash/utils/setup-jdk-sources.sh` (new), `bash/oracles-dataset.sh`, `bash/tokens-dataset.sh`,
  `bash/utils/local_variables.sh` (`LIB_JDKS_LINK`: the archive of the fourteen JDKs),
  `rqs/bash/rq1_tracto_inference.sh` (Java version argument `auto`)
- `lib/java_classpaths.json` (14 JDKs: 8, 11, 16–27)
- tests: `JavaVersionDetectorTest`, `JavaUtilsTest` (new), `OraclesDatasetTest`
- docs: `README.md`, `docs/design-review-2026-10-26.md`

## Defect

**D35.** The symbol solver resolves the classes of the Java standard library from the sources of the JDKs in `lib`
(`lib/java<version>/src`, listed in `lib/java_classpaths.json`). `JavaUtils.initializeJavaClassPath` gave it the
sources of all the JDKs of the file, in the order of the file (8, 11, 17, 21, 24), whatever the Java version passed to
the module (`24` by the scripts). The first type solver finding a class wins, so every JDK class was the one of Java 8,
and every method added after Java 8 failed to resolve. With the setup of the pipeline:

| Call | Resolved from |
|------|---------------|
| `list.add("a")` | `lib/java8/src/java/util/List.java` |
| `List.of("a")`, `Map.of("a", 1)`, `s.isBlank()`, `s.strip()`, `"x".formatted(1)`, `o.isEmpty()` (`Optional`), `st.toList()`, `Thread.ofVirtual()` | unresolved |

The Java version argument was only used by the lookup of the JDK classes by name (`getPathToJavaLibClassFromFqn`,
`getPathsToJavaLibClassesFromPackage`), mapped to the five JDKs of `lib` with fixed ranges.

## Fix

**Java version of the repository.** `JavaVersionDetector` reads the version declared by the build files of the
repository:
- Maven: the properties `maven.compiler.release`, `maven.compiler.source`, `maven.compiler.target` (and their `test`
  variants), the properties named like `java.version` or `jdk.version`, and the `release`, `source` and `target`
  settings of the `maven-compiler-plugin`, in the POMs and in their profiles; a reference to a property (`${name}`) is
  resolved with the properties of the POMs of the repository, one value per definition when it is defined several times
  (for example by the profiles of two JDKs);
- Gradle: `sourceCompatibility`, `targetCompatibility`, the toolchain (`JavaLanguageVersion.of(...)`,
  `jvmToolchain(...)`) and `options.release`.

The build files within the source folders (projects used as test resources), the build output and the processed
libraries are ignored. When several versions are declared (13 of the 135 input repositories: modules, profiles, a
higher version for the tests), the highest one is taken: the tests must be resolved against an API containing
everything they use.

**Selection of the JDKs.** The Java version argument of the module (`args[5]` of `oracles` and `tokens`, `args[8]` of
the inference client) accepts `auto`, which detects the version; a number still forces it. `JavaUtils` gives the
symbol solver:
- for a known version, only the JDK of `lib` with the smallest version not older than it (the newest JDK if all are
  older): Java 9 uses JDK 11, Java 22 uses JDK 22, Java 6 uses JDK 8;
- for an unknown version, the newest JDK of `lib`, then the oldest one: the classes and methods added by every version
  are found, and the oldest JDK (Java 8) provides the classes removed since (for example JAXB, removed in Java 11). The
  first version of the step gave all the JDKs, newest first: with the fourteen JDKs of `lib` the symbol solver became
  unusable (see the verification below).

The lookup of the JDK classes by name (the candidate classes of the tokens dataset, the declarations of the invoked
methods) uses the same JDKs, in the same order. A version listed in `lib/java_classpaths.json` whose folder is missing
from `lib` (for example, after downloading an archive older than the list) is left out of the selection, with a
warning.

**JDK of the running JVM.** JavaParser describes some classes of the standard library by reflection on the JVM running
the module, not from the sources: `JavaParserEnumDeclaration.getAncestors` takes `java.lang.Enum`, the superclass of
every enum, with `ReflectionFactory.typeUsageFor(Enum.class, ...)`. On Java 21, `Enum` implements `Constable`, whose
types (`ConstantDesc`, `Enum.EnumDesc`) exist only since Java 12: with the sources of JDK 8 or 11 alone they cannot be
resolved. The first A/B run of the tokens stage found it (two datapoints of crawler-commons lost, "Unsolved symbol :
java.lang.constant.ConstantDesc" at the step after `ChronoField.`); before Step 17 the newer JDKs among the five given to
the symbol solver resolved them by chance. So, when the JDK of the repository is older than the JVM running the module,
the symbol solver also gets the sources of the JDK of the running JVM (the smallest JDK of `lib` not older than it),
after the ones of the repository: a class of the JDK of the repository still comes from it (`List.of` stays unresolved
for Java 8), only the classes missing from it come from the newer one. The lookup of the JDK classes by name uses only
the JDK of the repository.

The statistics of the oracles dataset record the version, how it was obtained and the JDKs used (`javaVersion`,
`javaVersionSource`: `detected`, `not detected` or `argument`, `jdkVersions`, `runtimeJdkVersion`). The scripts of the
datasets pass `auto`.

**JDK sources.** `bash/utils/setup-jdk-sources.sh` builds the `lib` folder: for each requested version (all the ones
available from Adoptium by default: 8, 11, 16–27), it downloads the Eclipse Temurin JDK for Linux x64 (the sources are
the same on every platform, apart from internal platform-specific classes, and Linux x64 has a build for every
version), extracts its `src.zip` into `lib/java<version>/src` and its `release` file (the exact build) into
`lib/java<version>/release`, and regenerates `lib/java_classpaths.json` when it added a version; `--archive` builds the
archive downloaded by the init phase of the dataset scripts. SDKMAN, used to build the first five folders by hand,
lists JDKs it can no longer download (its download service answers 404 for most of them, among which all the builds of
Java 22 to 27). The init phase of `oracles-dataset.sh` and `tokens-dataset.sh` downloads the archive when the folder of
any version listed in `lib/java_classpaths.json` is missing (before: a fixed list of five folders).

## Tests

Written before the fix (with stubs keeping the old behaviour, so that they compile): 33 of the 35 failing.

| Test | Checks |
|------|--------|
| `JavaVersionDetectorTest` (20 cases) | Maven properties (`maven.compiler.release`, `source`/`target` `1.8`, `java.version`), the compiler plugin with a property reference, a property of the root POM used in a module, the highest version of the modules and profiles, a property defined by two profiles (written after the first run on the input repositories, where it gave the first definition), the build files within `src` and `processed_libs` ignored, a property defined outside the repository, no declaration, a malformed POM, nine Gradle forms |
| `JavaUtilsTest.theJdkOfTheDeclaredVersionIsTheSmallestNotOlderOrTheNewestOne` (8 cases) | 6 → 8, 9 → 11, 18 → 21, 30 → 25, unknown → the newest, then the oldest |
| `JavaUtilsTest.classpathHas...` (2), `lookupOfAJdkClassFollowsTheSelectedJdks` | the classpath and the lookup by name use only the selected JDKs, in order |
| `JavaUtilsTest.classesRemovedAfterJava8AreResolvedFromTheOldestJdkWhenTheVersionIsUnknown` | for an unknown version, `javax.xml.bind.JAXB`, only in the fake JDK 8, resolves |
| `JavaUtilsTest.jdksListedWithoutTheirFolderAreIgnored` | a JDK listed in the JSON without its folder is not selected (before: Java 22 selected the missing JDK 25) |
| `JavaUtilsTest.autoArgument...` (2), `numericArgumentForcesTheVersion` | the `auto` and numeric arguments, and the source recorded |
| `JavaUtilsTest.methodsAddedAfterJava8AreResolvedUnlessTheRepositoryDeclaresJava8` | with a fake JDK 8 whose `List` has no `of` and a fake JDK 11 whose `List` has it, `List.of()` resolves from the JDK 11 for an unknown version and for Java 11, and stays unresolved for Java 8 (before: unresolved in every case, the defect) |
| `JavaUtilsTest.classpathEndsWithTheJdkOfTheRunningJvmWhenTheSelectedOneIsOlder`, `classesMissingFromTheJdkOfTheRepositoryAreResolvedFromTheJdkOfTheRunningJvm` (written after the first A/B run of the tokens stage, failing before their fix) | for Java 8 on a Java 21 JVM the classpath is JDK 8 then JDK 21, and `java.lang.constant.ConstantDesc`, absent from JDK 8, resolves (before: "Unsolved symbol : java.lang.constant.ConstantDesc", as in the tokens stage); for Java 25, JDK 25 alone |
| `OraclesDatasetTest.statisticsRecordTheJavaVersionOfTheRepositoryAndTheJdksOfTheSymbolSolver` | `javaVersion`, `javaVersionSource`, `jdkVersions` in the statistics |

Without a `lib` folder (the fixture repositories of the tests), the lookup of the JDK classes by name now finds nothing
instead of throwing a `NullPointerException`; no other test changed.

Full suite: 230 tests, 0 failures, 0 errors, 0 skipped.

**Detection on the input repositories.** The 135 repositories of `input/github-repos`: 114 with a declared version
(6: 1, 7: 1, 8: 25, 9: 1, 11: 26, 14: 1, 17: 29, 18: 1, 21: 19, 22: 1, 24: 2, 25: 7), 21 without: all of them inherit
from a parent POM outside the repository (11 from the Jenkins plugin POM), which would declare the version.

## Verification on real projects

**Oracles dataset.** The seven projects of the previous steps, fresh copies, committed configuration. Before: the jar of
Step 16 (`92e1e7dd`) with the Java version `24` and the five JDKs of the committed `lib/java_classpaths.json`, as the
scripts ran it. After: the jar of Step 17 with `auto` and the fourteen JDKs of `lib`.

| Project | Detected version → JDK | Invoked methods | Errors of the invoked methods | Run time |
|---------|------------------------|-----------------|-------------------------------|----------|
| crawler-commons | 11 → 11 | 4,862 → 4,933 (+71) | 193 → 22 | 81 s → 35 s |
| java-uuid-generator | 8 → 8 | 2,115 (16 described from JDK 8) | 13 → 13 | 52 s → 19 s |
| ffmpeg-cli-wrapper | 8 → 8 | 5,507 (same) | 7,007 → 7,007 | 137 s → 66 s |
| plexus-utils | 11 → 11 | 3,872 (82 described from JDK 11) | 85 → 85 | 54 s → 21 s |
| esapi | 8 → 8 | 1,855 (2 described from JDK 8) | 2,965 → 2,965 | 60 s → 24 s |
| eec | 8 → 8 | 20,421 (same) | 526 → 526 | 1,164 s → 99 s |
| jsoup | 11 → 11 | 17,578 (7 described from JDK 11) | 144 → 147 | 248 s → 98 s |

- The datapoints, their targets and test prefixes, and the descriptions of the test and focal classes are the same in
  the two runs.
- crawler-commons: the calls of the methods added after Java 8 (`List.of`, `Set.of`, `Map.of`) are resolved, and so
  are the calls taking them as arguments (`createRobotRules(Set.of("anybot"), ...)`, `validateSiteMapURLs`,
  `parseContent`): 171 errors fewer (the 78 unsolved symbols and the 93 calls that could not be resolved).
- The other changes of the invoked methods are the same methods described from the sources of the JDK of the project
  instead of those of Java 8 (before, for the symbol solver) or Java 24 (before, for the lookup of the JDK classes by
  name, which used the version argument): `abstract public void flush()` of `Writer` in Java 8 is `public abstract` in
  Java 11, `Class.getResource` returns `java.net.URL` in the Java 8 source and `URL` in the Java 11 one,
  `Path.resolve(String)` is a `default` method since Java 11; `Object` declares no constructor in Java 8
  (`public Object()` from Java 24 becomes `Object()`), and `ProxySelector` declares none in Java 8 and 11.
- jsoup, 3 errors more: `ProxySelector` declares no constructor in Java 11, so the constructor of the anonymous class
  `new ProxySelector() { ... }` (4 split test cases of `HttpClientExecutorTest`) is not matched with a declaration (the
  matching of the implicit constructors handles only `new X()`); before, the lookup by name found the explicit
  constructor of Java 24. The invoked methods still list `ProxySelector()`. One error of another test case disappears.
- The run times fall by 2 to 12 times: the symbol solver searches the source folders of one JDK instead of five (the
  two runs were made at the same time, so the times are approximate).
- The anonymous classes are named with a random UUID (`Anonymous-…`), which differs at each run: those differences are
  not counted above.
- The runs above were made before the JDK of the running JVM was added after an older JDK of the repository; the run
  with it gives the same oracles datasets (apart from the names of the anonymous classes).

**Tokens dataset.** The tokens stage on crawler-commons (Java 11, the project whose JDK changes the most), after the
oracles stage of each run:

| | Before | After |
|---|---|---|
| Datapoints | 727 | 727 (725 in the first version of the step: "Unsolved symbol : java.lang.constant.ConstantDesc", fixed by the JDK of the running JVM) |
| Errors logged | 1,310 | 1,093 |
| Steps whose target is not among the candidates ("Unexpected token") | 367 | 366 |
| Run time | 6,518 s | 563 s |

The candidate project tokens change in 413 of the 9,596 steps: the members of `String` of JDK 11 instead of those of
JDK 8 (`isBlank`, `lines`, `repeat`, and package-private internals such as `isLatin1`, `LATIN1`, `checkIndex`, which
replace JDK 8 internals such as `indexOfSupplementary` and `checkBounds`: the candidate tokens include non-public
members of the JDK classes, before and after this step). No step gains or loses its target among the candidates. The
run before was made at the same time as the first run of the step, so the times are approximate.

**Version not detected.** crawler-commons with its POM removed, so that no version is detected:
- with all the fourteen JDKs, newest first (the first version of the step), the normalization and the splitting took
  3 minutes, then the generation of the datapoints stopped on one test class for more than 1 hour 40 minutes (stopped
  by hand); the thread dump showed the symbol solver parsing JDK sources: when `JavaParserTypeSolver` does not find a
  type, it parses the directories of its source folder looking for it, and `CombinedTypeSolver` asks each of the about
  1,000 source folders of the fourteen JDKs;
- with the newest JDK, then the oldest one (JDK 27, then JDK 8): 23 s; the same datapoints, targets, test prefixes,
  number of invoked methods and errors as the run where Java 11 is detected; 19 invoked methods are described from
  JDK 27 instead of JDK 11 (`TimeZone.getTimeZone` is `synchronized` in the sources of JDK 11, not in those of JDK 27).

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
