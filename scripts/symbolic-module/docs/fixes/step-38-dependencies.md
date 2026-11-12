# Step 38 – The dependencies of the Maven projects

**Date:** 2026-11-12
**Branch:** `22-test-processing-refactoring` (on top of Step 37, dataset v2)
**Files changed:**
- `tools/deps/resolve_maven.py` (new): resolves the dependencies of a Maven repository into its folder
  `processed_libs` (tests: `tools/deps/test_resolve_maven.py`)
- `src/main/java/star/tracto/deps/StubGenerator.java` (new): the stubs of the classes of a jar without sources
  (`java -jar tracto.jar stubs <jar> <folder>`); `pom.xml`: Javassist declared (the version the symbol solver brings)
- `src/main/java/star/tracto/SymbolicModule.java`: the command `stubs`; the folder `processed_libs/jars` collected with
  `processed_libs/decompiled`; the library folders in a fixed order
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java`: the jars of `processed_libs/jars` given to the
  symbol solver
- `src/main/java/star/tracto/utils/FilesUtils.java`: `listDirectories` in a fixed order
- `tools/ab/ab.py` (`run --deps`), `tools/ab/corpus.py` (`prepare` resolves the dependencies, `--no-deps` as before),
  `bash/oracles-dataset.sh` and `bash/dataset-parallel.sh` (`resolve_deps` true by default)
- tests: `DependencyLibrariesTokensTest` (new, end-to-end), `StubGeneratorTest` (new); `tools/deps/test_resolve_maven.py`

Decided by the author on 2026-11-12: Maven projects only (for the datasets and the inference); no pilot on unseen
repositories, the remaining causes first, then the generation. Design discussed with the author the same day
(`docs/missing-candidate-tokens.md`, section 6.2): no target token may be lost at inference time; the candidates come
from deterministic sources only (sources, else stubs of the bytecode, else the jar); then as much context as possible.

## The cause

Dependency: 151 of the 259 oracles lost in dataset v2 (ffmpeg 97, esapi 39, cryptofs 10, steve 5). The module already
gives the symbol solver the folders `<repo>/processed_libs/decompiled/<library>/` and searches them for the classes by
name, but nothing filled them: the script that did (`bash/utils/resolve_dependencies_mvn.sh`) was disabled in
`oracles-dataset.sh`. It read the POMs by hand (no transitive dependencies, no parents or BOMs outside the repository)
and decompiled every jar.

Probe (no code change, jar of dataset v2): the sources jars of the dependencies of ffmpeg and steve unpacked by hand
into that folder. ffmpeg, the 508 oracles of its classes with missing tokens: 98 lose a step without them, 0 with them;
steve, 12 oracles: 7 → 2 (the 2 left: a variable of a nested block). No oracle worse.

## Fix

**The resolver** (`tools/deps/resolve_maven.py <repo>`):
1. the effective POM of every module (`help:effective-pom`, no dependency resolution needed). At the root of a
   multi-module repository, `dependency:copy-dependencies` fails as soon as a module depends on a sibling module that
   is not built (slf4j: 2 jars; awaitility: none);
2. a synthetic POM with the external dependencies of all the modules (the modules of the repository left out: their
   sources are in the repository; one version per artifact, the highest; the repositories the modules declare),
   resolved by Maven with the transitive dependencies, test scope: the binary jars, the `-sources` jars, the `-javadoc`
   jars of the artifacts without sources. When the whole set cannot be resolved, each dependency is resolved on its
   own (a reactor of one module per dependency, `--fail-at-end`) and the missing ones are reported;
3. in the repository, `processed_libs/`: `decompiled/<groupId>__<artifactId>-<version>/` the `.java` files of the
   sources jar, or the stubs of a jar without sources; `jars/` the binary jars whose stubs could not be generated;
   `dependencies.json` the report (status, mode, artifacts and what was provided for each, the dependencies missing);
   `maven.log`. Links to a shared cache (`--cache`, `$TRACTO_DEPS_CACHE`, `~/.cache/tracto-deps`), where each artifact
   is unpacked once and the stubs generated once; the resolution itself is cached by the hash of the POM files.

**The stubs** (`StubGenerator`, Javassist): one `.java` file per top-level class, its member classes nested, the
generic signatures, the fields, the methods and constructors with the parameter names of the debug information, empty
bodies, the types fully qualified; not the private, synthetic, bridge members nor the anonymous classes. First line:
`// tracto:stub <jar>` (the provenance). The binary jar alone is not enough for the guarantee: the symbol solver
resolves its types, but the names of its classes are not offered (the class names come from the `.java` files found by
name): in `DependencyLibrariesTokensTest`, with the jar alone `Gadget` is not offered after `assertNotNull(`, with its
stubs `Tool` is.

**The module:** the jars of `processed_libs/jars` were never given to the solver: the branch of `setRepoJavaParser` for
a folder of jars is unreachable (`findPotentialProjectsRoots` always returns at least the folder itself), and
`findLibrariesPaths` collected `decompiled` only. The library folders are given in a fixed order (sorted by name): two
libraries can define the same class, the first one wins.

## Tests

- `DependencyLibrariesTokensTest` (end-to-end): a library with sources (`widget.` ▸ `colour`); a library without
  sources with its stubs (`tool.` ▸ `sharpness`, `cut`; `assertNotNull(` ▸ `Tool`; `Tool.` ▸ `make`); a library with its
  jar only (`gadget.` ▸ `spin`). Checked on 2026-11-12: against the code of Step 37 it fails (`spin` not found: the jar
  is not given to the solver); the library with sources passes on Step 37 (the path already existed).
- `StubGeneratorTest`: a generic class with bounded type parameters, fields of generic and nested types, a constructor,
  a generic method with varargs and `throws`, static and inner member classes (the constructor of an inner class
  without its outer instance), an enum (constants, no `values`/`valueOf`), an interface (abstract, default, static
  methods), an annotation; the private members and classes, the anonymous class, the names with `$` left out; the
  result parses (JavaParser, Java 21); the same classes give the same stubs.
- `tools/deps/test_resolve_maven.py`: the effective POM (siblings, managed and plugin dependencies left out, the highest
  version, the unresolvable versions reported), the synthetic POM, the repository layout; with `TRACTO_DEPS_IT=1`, a
  multi-module repository with a module depending on its sibling and a missing artifact, resolved twice (the second
  time from the cache).

## Verification

**Resolution of the corpus projects** (`ab.py run --deps`, 2026-11-12): all nine resolved (`whole`, no artifact
missing), 10 to 36 s each the first time, 0 s from the cache. Artifacts: steve 224 (208 with sources, 16 without
classes), cryptofs 38, esapi 48 (46 sources, 1 stub, 1 without classes), ffmpeg 29, jsoup 22, crawler 11, uuid 11,
eec 9, plexus 5. The oracles stage: same datapoints (9,148), same time and peak memory as Step 37 (eec 6.5 GB instead
of 8.5, jsoup 6.5 instead of 7.5).

**The oracles datasets** (`tmp/ab/corpus-src-step37` vs `tmp/ab/corpus-src`): only the invoked methods of the test
prefixes change (and the type of a parameter, 3 steve prefixes):
- 703 prefixes gain 2,734 methods of the libraries, 2,476 with their Javadoc (Mockito `when`, `thenReturn`, `mock`,
  `any`; Commons Lang `Fraction.getFraction`; Spring, Joda-Time, ...);
- 40 methods replaced by the method with the same name the call really invokes: before, an unresolved call was matched
  by name to a method of the project (steve `get(...)` of a controller → Spring `MockMvcRequestBuilders.get`;
  cryptofs `UnverifiedVaultConfig.decode` → java-jwt `JWT.decode`; ffmpeg `FFmpegBuilder.setFormat` and
  `addExtraArgs` → the builder methods returning `T`; ffmpeg `run` → the interface `ProcessFunction` through Mockito's
  `verify`; esapi `log` → the overload with the slf4j `Logger` and `Marker`); the `Anonymous-<uuid>` lines are not
  differences;
- 2 eec prefixes (`SimpleSheetTest.testSimpleSheetPaging`) lose `ListSheet.setData(BiFunction)`: JavaParser throws
  `Return-Type-Substituable must be implemented on reference type` in the resolution path of the oracles stage (the
  same call resolved with `resolve()` succeeds with and without the dependencies);
- steve: the type `DateTime` of a parameter is written `Lorg/joda/time/DateTime;` instead of
  `org.joda.time.DateTime` (resolved now; the format of the resolved types predates this step, `String` is written
  `java/lang/String;`);
- the errors of the oracles stage on the corpus projects: 8,620 → 1,207 (`Unsolved symbol: ArgumentCaptor`,
  `TemporaryFolder`, `Cryptor`, `TestName`, `Mockito.any()` gone).

**Regression corpus** (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-12: complete **1,209 → 1,263** (esapi +29,
ffmpeg +13, cryptofs +7, steve +5), **0 worse**, loses a step 90 → 36, dropped 3. Compared step by step with the
outputs of Step 37 (`tmp/ab/corpus-step37-out`, `corpus-step38-out`):
- candidates: additions only (4,399 steps), no step loses one, no target token removed: the assertion methods of
  JUnit imported statically (`import static org.junit.jupiter.api.Assertions.*`, about 4,277 steps: `assertEquals`,
  `assertNull`, `fail`, ...; Step 25 offers the static imports, JUnit was not resolved); the imported classes now
  resolved (`Test`, `BeforeEach`, `ParameterizedTest`, `Nullable`, `Mockito`, `LoggerFactory`, `Logger`); the members of
  the libraries on the chains recovered;
- contextual information (4,962 steps): additions only (the overloads of the assertion being written, `invokedMethods`
  4,772; `fields` 964, e.g. `LOG` now that `Logger` is resolved); the type of the information on the steps of the
  chains recovered (`Mockito.` `INIT` → `CLASS_CALL`, `.mock(` → `METHOD_CALL`, the getters of the esapi `Cookie` and of
  the Joda-Time `DateTime` `FIELD_RETURN`/`PARAMETER_RETURN` → `METHOD_CALL`/`METHOD_RETURN`).

**End to end with the layout of the resolver** (symbolic links to the cache): the ffmpeg classes with missing tokens
in v2, 508 oracles, all complete (98 lose a step without the dependencies), as with the sources unpacked by hand.

**Resolution of 30 repositories of the list** (resolution only, no dataset; the first 30 of
`resources/seart-github-repos.csv`): 9 without a `pom.xml` at the root (Gradle, Android, Ant: skipped by the pipeline
anyway), 18 resolved, 2 resolved with missing artifacts (shiro 56 modules: 4; logback: 3), 1 failed (an Eclipse Tycho
build: `help:effective-pom`). Of 2,242 artifacts: 2,094 sources (93.4%), 91 stubs (4.1%; caelum 18, lorsource 25,
citrus 24, scala-maven-plugin 12), none of them with a `-javadoc` jar; 10 jars with only a `module-info.class`
(Netty, Selenium: no classes, since then counted as such). Most repositories take 10 to 100 s; caelum took 19 min the
first time: since then network timeouts and a limit of 20 min per Maven call (`TRACTO_DEPS_MAVEN_SECONDS`).

## Left

- **Candidate noise from the resolved libraries** (proposed as the next step): the assertion methods of JUnit imported
  statically are offered in every argument position (most return `void`); the annotation types imported (`Test`,
  `BeforeEach`, `Nullable`) are offered as class names. Correct by the rules of Steps 25 and 26, never useful inside an
  assertion.
- **Lombok:** the methods generated at compile time (`ConnectorStatus.builder()`, steve) are in neither the sources nor
  the dependencies (the most frequent error left in the oracles stage of the corpus, 160).
- The 2 eec prefixes that lose `ListSheet.setData` (JavaParser `Return-Type-Substituable`).

- The order of the libraries is the order of their names, not the order of the Maven classpath (what the compiler
  sees when two libraries define the same class). The module's own Hamcrest 2.2 comes before all of them.
- The Javadoc of the `-javadoc` jars is downloaded for the artifacts without sources but not yet merged into their
  stubs; the bodies are not decompiled (section 6.2 of `docs/missing-candidate-tokens.md`).
- The provenance of the context of a library in the datapoints (`source`, `stub`, ...): the stubs carry it in their
  first line only.
