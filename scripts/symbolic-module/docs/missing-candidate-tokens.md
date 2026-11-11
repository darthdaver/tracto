# Missing candidate tokens: problem, measurements, and how to restart

**Date:** 2026-11-03
**Branch:** `22-test-processing-refactoring`, code at `28c7ee56` (Step 19; the measurements below were made with the jar of
this commit)
**Status:** analysis in progress. Two of the nine reference projects measured; the other seven stopped by the memory of
the tokens stage (section 5). Nothing of this analysis is implemented yet.
**Purpose of this document:** restart the analysis in another session, possibly on another machine, without the
conversation that produced it.

## 0. Instructions for a new session (on a new machine)

This document replaces the conversation and the notes of the previous machine: read it entirely first. Then, from the
root of the repository (all the commands below are run from `scripts/symbolic-module` unless stated otherwise):

1. **Repository.** Branch `22-test-processing-refactoring`, at the commit that contains this document (`git log -1 --
   docs/missing-candidate-tokens.md`). `git status` must show no change to tracked files.
2. **Prerequisites.** `git`, `mvn`, `python3 --version` (3.9 or newer), a JDK 21 (the measurements used Amazon Corretto
   21.0.6; if it is not at `scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn`, set `TRACTO_JAVA_HOME` and
   `JAVA_HOME` to it), at least 32 GB of RAM (`sysctl hw.memsize` on macOS, `free -g` on Linux), about 25 GB of free
   disk.
3. **Input projects.** `python3 tools/ab/fetch_inputs.py` clones the nine reference projects (`tools/ab/input-repos.csv`:
   URL and pinned commit) into `input/github-repos/<owner>/<repo>` with their full history, checks out the pinned commit,
   and checks each one (HEAD is the commit, no changed, untracked or ignored file). Every project must print `OK`; the
   script never modifies a project already present. If a clone fails (repository moved or deleted, commit missing),
   stop and ask the author. Tested on 2026-11-03: a fresh clone is identical to the original copy (same files, same
   history). The other 126 input repositories of the dataset are not needed for this analysis.
4. **JDK sources.** Download the archive of `LIB_JDKS_LINK` (`bash/utils/local_variables.sh`, 767 MB) and unzip it into
   `scripts/symbolic-module/lib` (it contains `java_classpaths.json` and `java8/`, `java11/`, `java16/` ... `java27/`
   at its root: 242,776 files, 2.96 GB):
   `mkdir -p lib && curl -L -o /tmp/lib.zip "<LIB_JDKS_LINK>" && unzip -q /tmp/lib.zip -d lib`. Do not commit it.
   Alternative: `bash/utils/setup-jdk-sources.sh` (Adoptium Temurin; the sources may differ slightly from the archive).
5. **Build and tests.** `mvn test` with `JAVA_HOME` set to the JDK 21: 345 tests, all passing (Mockito needs
   `net.bytebuddy.experimental`, already set in the Surefire configuration).
6. **Fingerprint.** `python3 tools/ab/ab.py fingerprint`: it lists the JDK, the JDK sources and the commit of each input
   project. Every later run records it in its `summary.json`.
7. **Then** follow sections 8 and 9, keeping the rules of section 7: propose the plan to the author before changing
   code, report progress and estimates during long runs, commit only when asked.

## 1. What the symbolic module must do

The symbolic module works in two modes (description of the author, 2026-11-02):

1. **Dataset generation**, to fine-tune models that generate a test oracle token by token:
   - the **oracles dataset**: each datapoint has a target assertion and the context of the test prefix and of the
     oracle (the code, the Javadoc and the signature of the methods invoked in the test prefix, the fields and methods
     of the focal class, the classes imported by the focal class and the test class, ...);
   - the **tokens dataset**: each oracle is decomposed into its tokens; each datapoint (a *token step*) is a target
     token to guess given the partial oracle, with the list of **valid candidate tokens** (the target must be among
     them) and the context of the focal class and of the test class.
2. **Inference**: at each step of the generation, the symbolic module gives the model the list of valid candidate
   tokens for the next token of the partial oracle.

## 2. The problem

When the target token of a step is not among the candidates, the model cannot learn that step (fine-tuning) nor produce
it (inference): **one missing token loses the whole oracle**. The author expected the main cause to be the tokens that
belong to the external dependencies of the project, which JavaParser cannot resolve because the dependencies are not
given to the symbol solver (`oracles-dataset.sh`: the `processed_libs` block is commented out).

An oracle is also lost when the tokens stage stops on it and produces no datapoint of the tokens dataset at all.

Goal (agreed on 2026-11-03, "run the measurement on all nine projects first"): measure how many oracles are lost and
why, then fix the causes in order of the oracles they lose; the dependencies are one of the causes.

## 3. Measurements so far

**Setup.** Jar of `28c7ee56`, committed configuration, oracles and tokens stages on fresh copies of the projects
(`tools/ab/ab.py run ... --tokens`), classification of the lost oracles with `tools/ab/missing_tokens.py` (section 8).
Machine: macOS, 11 cores, 18 GB of RAM; JDK Corretto 21.0.6 (default heap 4.5 GB).

An oracle is *lost* when it produces no tokens datapoint (*dropped*) or when one of its steps has a target missing from
the candidates (*loses a step*). The tokens stage reports the latter in `statistics-tokens-dataset-training/*.json` as
`Unexpected token: <token> not found in the collected list of candidate tokens: <partial oracle>`; literals are never
counted as missing (the model generates them).

| Project | Oracles | Dropped | Lose a step | Complete | Lost | Token steps | Missing steps |
|---------|---------|---------|-------------|----------|------|-------------|---------------|
| crawler-commons | 732 | 3 | 139 | 590 | 19.4% | 9,638 | 361 |
| ffmpeg-cli-wrapper | 586 | 317 | 84 | 185 | **68.4%** | 3,770 | 135 |
| *total (2 projects)* | 1,318 | 320 | 223 | 775 | 41.2% | 13,408 | 496 |

Causes, by lost oracles (an oracle can have several causes; *only* = the oracles that fixing that cause alone recovers):

| Lost oracles (only) | Steps | Cause | Example (partial oracle ▸ missing token) |
|---------------------|-------|-------|------------------------------------------|
| 312 (312) | – | Hamcrest `assertThat` unsupported: oracle dropped | `assertThat(listener.progesses, equalTo(...))` (ffmpeg) |
| 63 (31) | 126 | Grammar: `)` (then `;`) not offered | `assertNull(normalizer.filter(weirdUrl),"normalizing: "+weirdUrl ▸ )` |
| 45 (16) | 53 | Class name (JDK or project) not offered | `assertThrows( ▸ IllegalArgumentException` |
| 36 (25) | 114 | Variable of a nested block (loop body, for-each variable), or its members | `assertEquals(sitemaps, ▸ rules` (declared in a `for` body) |
| 35 (35) | 69 | **Dependency** class or member | `assertEquals(args, ▸ ImmutableList`, `ImmutableList. ▸ of` (Guava, ffmpeg) |
| 29 (26) | 52 | Parameter of a parameterized test | `assertEquals( ▸ isAllowed` (`testGooglePatternMatching1(boolean isAllowed, ...)`) |
| 20 (20) | 32 | Member of a chain (cast, call chain, generics) | `((SimpleRobotRules)rules). ▸ getRobotRules`, `stream.getTags(). ▸ size` |
| 20 (20) | 20 | Static import of a project class (`import static ...FFmpegUtils.*`) | `assertEquals(63000000000L, ▸ fromTimecode` |
| 14 (14) | 28 | Field or method of the test class | `..."the FFmpeg developers", ▸ ffprobe` |
| 8 | – | Other drops: not a JUnit assertion (5), empty candidates (2), enum constant (1) | |

Checked by hand on the sources: the variables of nested blocks (`rules` declared in the body of a `for`), the missing `)`
(after `"..." + x`, after `x + "..."`, after `x instanceof T`), the 312 `assertThat` of ffmpeg. The classifier uses
regular expressions on the partial oracle and on the split test prefix (not the symbol solver): it is a heuristic.

**Main finding.** The dependencies are one cause among about ten: none of the 141 lost oracles of crawler-commons
(19%) is due to a dependency; in ffmpeg the dependencies cause 35 lost oracles, the unsupported `assertThat` 312.

**Not measured yet:** jsoup, eec, plexus-utils, esapi, cryptofs, java-uuid-generator, steve (section 5).

## 4. Causes and where they are in the code

| Cause | Where | First idea for the fix |
|-------|-------|------------------------|
| Assertions without a rule in the token grammar: `assertThat`, `assertThrowsExactly`, `assertDoesNotThrow`, `assertInstanceOf` ("Unsupported assertion type") | `Antlr4Utils` (switch ending at line 129) and the grammar `lsp/antlr4/grammars/JavaParser.g4` (`assert*MethodCall` rules, line ~862) | Add the rules; `assertThat` comes in several flavours (JUnit 4/Hamcrest `assertThat(actual, matcher)`, AssertJ `assertThat(actual).isEqualTo(...)`). Since Step 19 the shortened `assertDoesNotThrow` targets are dropped by the tokens stage too. |
| `instanceof` glued to the previous token | `TokensUtils` lines 452 and 814: the partial oracle is built by concatenating the tokens without spaces, except after the atomic keywords, so `asm` + `instanceof` becomes the identifier `asminstanceof` | Separate two consecutive word tokens with a space |
| `)` not offered after a string concatenation (`"..." + x`, `x + "..."`) | ANTLR grammar of the assertion arguments / `Antlr4Utils.parseSnippet` | Investigate the grammar rule of the expressions within the assertion arguments |
| Variables of nested blocks, parameters of the test method, fields and methods of the test class, static imports, class names not offered | `TokensUtils.setUpRootTokenIdentifiers` (line ~912) and the collectors (`TokenIdentifiersCollector`) | Collect the variables in scope at the position of the assertion (including loop variables and nested blocks), the parameters, the members of the test class and of its superclasses, the static imports (also wildcard), the class names (`java.lang`, imports, same package) |
| Members of chains (casts, generic results such as `iterator().next()`) | Type inference in the tokens stage | Resolve the type of the receiver expression with the symbol solver; the inference of `iterator().next()` varies from run to run |
| Dependencies | The symbol solver gets no dependency of the project | Section 6 |

## 5. The tokens stage: memory and speed

- `TokensDataset.generate` keeps all the tokens datapoints of an input file (one file per project,
  `oracles-datapoints-<project>-0.json`) in memory and writes them at the end. Each step holds its own copy of the
  contextual information (the method descriptions, `Callable`): in jsoup 2.7 million `Callable` objects and 2.7 GB of
  strings after 1,257 of 3,653 oracles. With the default heap (4.5 GB, a quarter of 18 GB) jsoup and eec spent most of
  their time in full garbage collections (jsoup 369 full GCs, 816 s; eec 573, 1,033 s) and were stopped after 1 h 40
  (jsoup 1,257 of 3,653 oracles, eec 1,037 of 1,427). jsoup would need about 13 GB.
- Output size: 2.1 GB of JSON for crawler-commons (the candidate lists and the contextual information are repeated at
  every step).
- Speed (on this machine): crawler-commons 934 s for 9,638 steps, ffmpeg 781 s for 3,770 steps; jsoup and eec
  10–30 oracles per minute before the memory filled up. At inference time the candidates are computed at every step:
  the speed of this computation matters.
- Peak memory of the oracles stage: up to 4.5 GB (ffmpeg), 3.7 GB (java-uuid-generator).

**Fixed by Step 23 (2026-11-05):** both stages write each test class as soon as it is processed
(`docs/fixes/step-23-streaming-datasets.md`), with the same bytes. The oracles stage of the nine projects then fits in
`-Xmx6g` (peak RSS 6.6 GB: eec, jsoup, ffmpeg); before, with `-Xmx24g`, the tokens stage peaked at 20.6 GB (jsoup) and
15 GB (eec).

## 6. Dependencies (what is known so far)

- What the pipeline needs from a dependency: its types, methods and fields (symbol resolution, candidate tokens); the
  Javadoc of the library methods invoked by the test prefix (it goes into the datapoints, `TestUtils.processCallable`);
  the classes as `.java` files, since `TestUtils` and `TokensUtils` look them up by name in `processed_libs/decompiled`
  (`JavaParserUtils.retrieveTypeDeclarationFromFullyQualifiedName`, `retrieveFilePathFromFullyQualifiedName`). Hence the
  `-sources.jar` of each dependency (a decompiler never recovers the Javadoc), the binary jar as a fallback
  (`JarTypeSolver` reads it directly).
- Current scripts (`bash/utils/resolve_dependencies_mvn.sh`, `utils/resolve_dependency.py`, disabled): the POMs are
  read by hand (no transitive dependencies; a POM with `<dependencyManagement>` returns those entries instead of its
  `<dependencies>`; parents and BOMs outside the repository are not read; one `${}` per value), a Maven process is
  started for each dependency and repository (up to four per dependency), every binary jar is decompiled with
  Fernflower, everything is repeated for each module and project.
- Directions discussed (not decided): let a real resolver compute the full list (Maven Resolver inside the module, or
  one Maven invocation per project; for Gradle the Tooling API); download each artifact once into a cache shared by the
  projects, sources jar first; give the solver the sources when they exist and the binary jar otherwise; record in the
  statistics the dependencies resolved and missed. Adding the dependencies changes the memory and the speed of the
  solver (more sources to search, fewer failed resolutions): measure both.
- Input repositories: 130 of 135 are Maven only, 4 have Maven and Gradle files, 1 is Gradle only; `oracles-dataset.sh`
  processes only Maven projects.
- **Open question for the author:** at inference time, on which projects will the module run: the Maven repositories
  selected for the datasets, on the author's machine, or any project of another user (Gradle, projects that do not
  build)?

## 7. Decisions of the author that apply to this work

- Correctness and robustness of the verification first, always: no reduction of the tests or of the A/B to save time;
  any speedup must be quantified and must not weaken the checks (2026-11-02).
- A/B after each step that changes the output: the previous commit and the new one, on the nine projects, projects run
  one after another (both sides side by side), the tokens stage on crawler-commons; no reuse of old baselines; both
  sides on the same machine.
- Each change is a step: tests first (they fail before the fix), a document in `docs/fixes/step-NN-*.md`, the A/B, a
  commit only when the author asks.
- The fixes follow the order of section 9, one step at a time (2026-11-03).
- Only JUnit assertions are in scope: Hamcrest `MatcherAssert.assertThat` and AssertJ are out of scope, and their
  oracles are counted apart from the lost ones (2026-11-04). **Changed on 2026-11-09:** Hamcrest `assertThat` is in
  scope (grammar rule, oracles accepted); AssertJ stays out.
- The A/B may stop early while a fix is developed (a run on the test classes concerned, then the small projects first,
  stopped at the first regression); each step still ends with the complete A/B on the nine projects (2026-11-04).
- **2026-11-05, replaces the complete A/B after each step:** the development moves to a 16 GB laptop; each step is
  checked with its tests and the regression corpus (`tools/ab/corpus.py`, `docs/resume.md` section 4), and the complete
  nine-project test runs on a large server only before the training, with the generation of the training dataset
  (`docs/resume.md` section 7).
- **2026-11-05:** the order of the candidates is deterministic in the dataset (the prompt builder writes them verbatim);
  a shuffle against positional shortcuts, if wanted, goes in the Python prompt builder, seeded and configurable.

## 8. How to restart

**Machine.** At least 32 GB of RAM (64 GB: the default heap of 16 GB fits jsoup without the fix of section 5), several
cores, about 20 GB of free disk for the outputs. Compare only runs made on the same machine: the order of the files and
the run-to-run variations of JavaParser can differ between machines, so remeasure crawler-commons and ffmpeg there too.

**Setup.**
1. The repository at the commit that contains this document; JDK 21 (the runs used Corretto 21.0.6, the default path of
   `TRACTO_JAVA_HOME` is `scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn`), Maven (online the first time),
   Python 3.9+.
2. The JDK sources in `scripts/symbolic-module/lib/`: the archive of `LIB_JDKS_LINK` (`bash/utils/local_variables.sh`),
   or `bash/utils/setup-jdk-sources.sh` (Adoptium Temurin 8, 11, 16–27). The fingerprint of `ab.py` records their file
   counts and sizes.
3. The input repositories in `input/github-repos/<owner>/<repo>` (or `TRACTO_INPUT`) at these commits, with no local
   change: `python3 tools/ab/fetch_inputs.py` (section 0, list in `tools/ab/input-repos.csv`):

| Id | Repository | Commit |
|----|------------|--------|
| crawler | crawler-commons/crawler-commons | `9740d790a85efd74918e8b8c81ac412590d8b64d` |
| ffmpeg | bramp/ffmpeg-cli-wrapper | `a6655138d7cc1b2c62a57a5e95798695e060de6e` |
| jsoup | jhy/jsoup | `f727535bcae771a26daf9dff585a8bfd9f015668` |
| eec | wangguanquan/eec | `43cbda4ee4e09bf9988e033f4e4ac8cc3b108af4` |
| plexus | codehaus-plexus/plexus-utils | `86c5f2f77e0c43a93beb0443b6829da284483e32` |
| esapi | esapi/esapi-java-legacy | `58be1699d92336f3eff87435ee22cceced9a466d` |
| cryptofs | cryptomator/cryptofs | `2b2f33795278ffd31389c2211cbcfc0d37c06e37` |
| uuid | cowtowncoder/java-uuid-generator | `3f2109438765a4e196b69bff6d14234e5bf33075` |
| steve | steve-community/steve | `ea62fe17daa2d3bafe236e131d9e68b828a17b1a` |

**Commands** (from `scripts/symbolic-module`; outputs in `tmp/ab/<label>`, git-ignored):

```bash
# the jar of a commit (built in a temporary git worktree) and its configuration
python3 tools/ab/ab.py build 28c7ee56 tmp/ab/jar.jar
git show 28c7ee56:scripts/symbolic-module/src/main/resources/dataset/oracles-dataset_config.json > tmp/ab/config.json
# oracles and tokens stages on the nine projects (-j: projects in parallel; each JVM needs up to the heap given)
python3 tools/ab/ab.py run measure tmp/ab/jar.jar tmp/ab/config.json --tokens \
    --tokens-projects crawler,ffmpeg,jsoup,eec,plexus,esapi,cryptofs,uuid,steve -j 2 --jvm-options "-Xmx16g"
# lost oracles and their causes
python3 tools/ab/missing_tokens.py tmp/ab/measure:crawler,ffmpeg,jsoup,eec,plexus,esapi,cryptofs,uuid,steve
```

`ab.py run` checks that every run is complete (exit status, statistics, end of the generation in the log, no
`OutOfMemoryError`), that nothing is written outside the output folders, and records CPU time, peak memory and the
fingerprint of the environment (`tmp/ab/<label>/summary.json`). `compare19.py <A> <B> <C> <ids>` compares the oracles
datasets of A/B runs (it imports `compare17.py`).

## 9. Plan of the fixes (approved by the author)

Measured on the nine projects (`missing_tokens.py`): 27.3% of the oracles lost before Step 20, 20.8% after Step 20 and
after Step 21. Status on 2026-11-05; the next steps follow the loop of `docs/resume.md` (section 4).

**Done** (one commit and one document in `docs/fixes/` each):

- Step 20: the local declarations in scope at the position of the target.
- Step 21: type variables resolved to all their values, methods in a fixed order (A/B: 0 regressions, no status
  change; the result of an oracle no longer depends on what was processed before it).
- Step 22: the fields and methods of the test class and of its superclasses (verified on the regression corpus).
- Step 23: the datasets written one test class at a time (same bytes; the memory no longer follows the output).
- Step 24: fields, local variables and declared methods in a fixed order (the dataset is reproducible byte for byte).
- Step 25: the members imported statically (methods, fields, enum constants of the project and of the JDK), and the
  members of an enum constant (corpus: +31 complete, 0 worse).
- Step 26: the class names visible in the test class (`java.lang`, member types, imports, the package itself), not
  the generated classes; the classes after `instanceof` (corpus: +62 complete, 0 worse).
- Step 27: the assigned assertions (`X e = assertThrows(...)`): the target is the assertion, the declaration is kept
  in `assignment` and added back at inference; the grammar rule of `assertDoesNotThrow` (corpus: +18 complete, 0 worse).
- Step 28: the grammar (the message of an assertion is any expression; `Type::new`, `String[]::new`) and the partial
  oracle joined the same way in the dataset and at inference (`x instanceof T`) (corpus: +21 complete, 0 worse).
- Step 29: a target is always a single statement: a block lambda of one statement is written as an expression by the
  normalization (`() -> { a(); }` becomes `() -> a()`), an assertion still containing a block of statements
  (`assertTimeoutPreemptively(d, () -> {...})`, an anonymous class) produces no datapoint; whitespace and comments are
  never target tokens (corpus: +9 complete, 0 worse).
- Step 30: the `assertThat` of Hamcrest: a grammar rule, `MatcherAssert.` removed from the targets and the model input,
  the targets printed without comments, the sources of Hamcrest 2.2 given to the symbol solver as part of the assertion
  API known by the module (`assertion-api/`), so that the matchers are candidates (corpus: +20 complete, dropped 28 → 3,
  0 worse).
- Step 31: chains, first part: the values of the type parameters of the ancestors of a type (the result of
  `getAllAncestors()` was discarded), the field `length` of the arrays (corpus: +33 complete, 0 worse).
- Step 32: chains, second part: the members of the type of a cast, `((T) expression).` (corpus: +6 complete, 0 worse;
  the v1 classes with a cast: +22).
- Step 33: the methods of a type whose lookup of all the methods failed as a whole (a type of a dependency in one
  signature; the accessors of the records): the methods that can be resolved, inherited ones included (corpus: +21
  complete, 0 worse).
- Step 34: the type of a variable declared with `var` whose initializer the symbol solver cannot type as a whole (an
  argument of a type of a dependency), from the parts of the initializer when not ambiguous (corpus: +10 complete, 0
  worse; the cryptofs classes with a `var` receiver: +17).
- Step 35: no body in the contextual information of a method without a source (the JDK read by reflection, the
  accessors of the records: a description of the object was taken, with an identity hash for the records); candidates
  and statuses unchanged.
- Step 36: the token steps of a target do not depend on the targets processed before it (the flag "the current value
  is a `Stream`" was kept from one datapoint to the next: `LAMBDA_PARAM` and `LAMBDA_CALL` outside streams); statuses
  unchanged, 11 corpus oracles with other token steps.
- Step 37: only the members the test class can access, as in Java (`MemberAccess`): no private member after a value,
  the static members only after the name of a class, the instance methods after `X::`; statuses unchanged, 0 worse,
  1,188 steps lose candidates, none gains one.

**Dataset v1 (full test of 2026-11-09, Step 29):** 12.2% lost (1,116 of 9,154 datapoints; 7,937 complete of 9,043
counted by `missing_tokens.py`), 0 regressions against Step 21 (details in `docs/resume.md`, section 7). Lost oracles
by cause: member of a chain 451 (436 alone; jsoup 287), Hamcrest `assertThat` 314 (ffmpeg 312, cryptofs 2),
`MatcherAssert.assertThat` ("Assertion do not start with JUnit api") 110 (cryptofs), dependency 91 (87), class name 66
(57; eec 41), member of a known type 55 (49; cryptofs 29), test class member 12, nested-block variable 10,
`assertInstanceOf` 7 (jsoup 5, cryptofs 2), other 4, static import 2, `Range [N, N) out of bounds` 1.

**Lost oracles by cause after Step 21** (oracles recovered by fixing that cause alone, of 9,049; an oracle can have
several causes):

| Cause | Oracles (alone) | Where |
|---|---|---|
| member of a chain (type inference) | 456 (426) | jsoup 288, eec 57, uuid 55 |
| class name | 331 (247) | jsoup 92, eec 86, esapi 50, crawler 37 |
| dropped: Hamcrest `assertThat` (out of scope) | 314 | ffmpeg 312 |
| dropped: assigned assertions (`X e = assertThrows(...)`, "Assertion do not start with JUnit api") | 91 | cryptofs 32, esapi 28, jsoup 20 |
| dropped: Hamcrest `MatcherAssert.assertThat` (out of scope; 8 with leading `//` comments) | 110 | cryptofs 110 |
| grammar | 168 (101) | crawler 63, uuid 57, esapi 24 |
| test class member (Step 22) | 147 (133) | steve 64, plexus 26 |
| static import | 133 (130) | jsoup 70, eec 41, ffmpeg 20 |
| dependency | 91 (85) | esapi 39, ffmpeg 35 |
| dropped: block lambdas ("No enum constant for symbol") | 52 | cryptofs 49 |
| member of a known type | 46 (39) | cryptofs 20 |
| other / nested-block variable | 18 / 10 | |

**Next steps, in the approved order** (details and entry points in `docs/resume.md`, section 6):

1. **Static imports** (130), wildcard included: done (Step 25).
2. **Class names** (247), done (Step 26): `java.lang`, the imports, the same package, the classes nested in the test class; never the
   classes generated by the pipeline (`…STARSplitTest`, `…STARNormalizedTest`, `…STARSplitTestPrefix`).
3. **Assigned assertions** (91; decided 2026-11-06), done (Step 27): the model generates the assertion only; the oracles stage keeps
   the declaration (`assignment`: modifiers, type, name) and the symbolic module adds it back at the end of the
   generation (design in `docs/resume.md`, section 6).
4. **Qualified JUnit assertions in the prefixes**, checked 2026-11-07, nothing to change (the model input is already unqualified; `docs/resume.md`, section 6) (no oracle recovered: no target is qualified; a consistency fix of the
   model input): the split prefix files of cryptofs still contain `Assertions.assertX(...)`; first check whether they
   are in the test prefix or only in other methods, then normalize to the unqualified form.
5. **Grammar** (101), done (Step 28): the spacing of `instanceof`, the `)` after a string concatenation.
6. **Block lambdas** (52), done (Step 29).
7. **Hamcrest `assertThat`** (424 dropped in v1; asked by the author on 2026-11-09), done (Step 30): grammar rule,
   `MatcherAssert.` removed, the sources of Hamcrest given to the solver (`assertion-api/`).
8. **Type inference of the chains** (451 in v1, 436 alone; the largest but the hardest).
9. **Dependencies** (85): design (section 6) with the answer of the author on the inference setting.
10. **Pre-existing defects**, each its own step: ~~candidates depending on the processing history~~ (Step 36); ~~the protected-member
   filter (`String.contains` on package names)~~ (Step 37); a substring of an error message holding an identity hash; the test methods annotated `@ParameterizedTest` (or other test annotations than `@Test`)
   offered as candidates; ~~the private members of the JDK classes offered after a value of that type (`String.isMalformed3`)~~ (Step 37); ~~the `LAMBDA_PARAM` placeholder offered outside lambdas~~ (same cause, Step 36).

Proposed to the author on 2026-11-05 (not decided): cut the first training dataset after steps 1-3 (about 470 oracles
at most, the causes overlap), measured with the full test of `docs/resume.md` section 7.
