# Resuming the work on the tokens dataset (on another machine)

**Written:** 2026-11-05, at the end of the work on the 314 GB / 32-core server, before moving to a 16 GB laptop.
**Branch:** `22-test-processing-refactoring`. **Read first:** this file, then section 9 of
`docs/missing-candidate-tokens.md` (the plan) and the step documents in `docs/fixes/` (20 to 26).

To resume with Claude Code on the new machine: set it up (section 3), open Claude Code at the root of the repository
and start with, for example:

> Read `scripts/symbolic-module/docs/resume.md` entirely, then `docs/missing-candidate-tokens.md` (sections 7 and 9).
> Check the setup (section 3, step 6: the corpus must be identical to the expected results), then propose how to
> implement the next step of section 6, following the loop of section 4.

Its memory of the earlier sessions stays on the server: everything it needs is in this file and the documents it
points to.

## 1. Where the work stands

The goal: fix the oracles lost by the tokens dataset (an oracle is lost when the tokens stage drops it, or when a
token of its target is missing from the candidates offered at that step). Measured on the nine reference projects
(section 8 of `missing-candidate-tokens.md`):

| | Lost oracles |
|---|---|
| Before Step 20 | 27.3% |
| After Step 20 | 20.8% |
| After Step 21 | 20.8% (no change: Step 21 is a determinism fix) |
| After Steps 22–24 | on the corpus (section 4): 879 → 978 complete of 1,302 oracles (+99, 0 worse); on the nine projects: measured by the full test before the training (section 7) |
| After Step 25 | on the corpus: 978 → 1,009 complete (+31, 0 worse) |
| After Step 26 | on the corpus: 1,009 → 1,071 complete (+62, 0 worse) |
| After Step 27 | on the corpus: 1,071 → 1,089 complete (+18, 0 worse; dropped 57 → 38) |
| After Step 28 | on the corpus: 1,089 → 1,110 complete (+21, 0 worse) |
| After Step 29 | on the corpus: 1,110 → 1,119 complete (+9, 0 worse) |
| **Dataset v1** (full test, 2026-11-09, Step 29 `1981c0a`) | **12.2%** on the nine projects (7,937 complete of 9,043; 7,170 after Step 21: +767, 0 regressions) |
| After Step 30 | on the corpus: 1,119 → 1,139 complete (+20, 0 worse; dropped 28 → 3) |
| After Step 31 | on the corpus: 1,139 → 1,172 complete (+33, 0 worse) |
| After Step 32 | on the corpus: 1,172 → 1,178 complete (+6, 0 worse); the v1 classes with a cast: +22 |
| After Step 33 | on the corpus: 1,178 → 1,199 complete (+21, 0 worse); the 52 v1 classes with chain causes, v1 → Step 32: +594, 0 worse |
| After Step 34 | on the corpus: 1,199 → 1,209 complete (+10, 0 worse); the cryptofs classes with a `var` receiver: +17 |
| After Step 35 | on the corpus: unchanged (1,209); 290 garbage bodies of methods without a source emptied, the corpus output deterministic again |

Steps committed on 2026-11-05 (one commit each, a document each in `docs/fixes/`):

| Step | What | Effect on the dataset |
|---|---|---|
| 20 | local variables and parameters in scope at the position of the target | oracles recovered (27.3% → 20.8% lost) |
| 21 | type parameters resolved to all their values; methods in a fixed order | no oracle changes status (A/B: 0 regressions); 8 token steps gain the methods of `Object` |
| 22 | fields and methods of the test class and of its superclasses offered as candidates | corpus: 99 oracles recovered (879 → 978 complete of 1,302), 0 worse (steve +64, ffmpeg +13, esapi +11, plexus +7, jsoup +4) |
| 23 | datasets written one test class at a time (streaming) | none (byte for byte up to order); far less memory |
| 24 | fields, local variables and declared methods in a fixed order | candidates in another, fixed order; the dataset is reproducible |
| 25 (2026-11-06) | members imported statically (methods, fields, enum constants of the project and the JDK); the members of an enum constant | corpus: 31 oracles recovered (978 → 1,009), 0 worse (ffmpeg +20, jsoup +7, uuid +3, cryptofs +1) |
| 26 (2026-11-06) | class names: `java.lang`, member types, imports of member types and records, the package without its subpackages and with its interfaces, no generated classes, the classes after `instanceof` | corpus: 62 oracles recovered (1,009 → 1,071), 0 worse (eec +23, plexus +11, esapi +8, uuid +8, ffmpeg +7, jsoup +3, cryptofs +2) |

After Steps 21, 23 and 24 the tokens stage is **deterministic**: the same code on the same inputs gives byte for byte
the same dataset (checked with `-XX:hashCode=2`, which changes every identity hash code), except the random names of
the anonymous classes (`Anonymous-<uuid>`) and a few error messages of the statistics that print identity hashes.
This is what makes the fast checks of section 4 exact.

## 2. Decisions of the author (all still valid)

- Correctness first: no fix without tests that fail before it, no change accepted without its differences explained.
- Each fix is a step: tests first, a document `docs/fixes/step-NN-*.md`, a commit only when the author asks (one per
  step). Git identity of the commits: `darthdaver <davide.molinelli@uusi.ch>` (as the earlier commits).
- Only JUnit assertions are in scope (Hamcrest `MatcherAssert.assertThat` and AssertJ are out, counted apart).
  **Changed on 2026-11-09:** Hamcrest `assertThat` is in scope (see below; done in Step 30); AssertJ stays out.
- The fixes follow the order of section 9 of `missing-candidate-tokens.md` (summarized in section 6 below).
- **2026-11-05, new loop:** development on the laptop (16 GB). No full A/B per step: each step is checked with the
  unit tests and the regression corpus (section 4); the full nine-project test runs on a large server only before the
  training (section 7), together with the generation of the training dataset.
- **2026-11-05, candidate order:** the order of the candidates must be deterministic in the dataset (the prompt
  builder writes them verbatim, `scripts/prompt/src/EWash.py`). If the order is to be randomized against positional
  shortcuts, it is shuffled in the Python prompt builder, seeded and configurable (not done yet, low priority).
- Measuring the position of the target in the candidate list: low priority, not now.
- **2026-11-06, assigned assertions:** moved to step 3. The model always generates a plain assertion; the symbolic
  module adds the declaration (`X e = `) statically with JavaParser at the end of the generation (section 6, step 3).
- **2026-11-06:** the work continues on the server for now (the laptop move is postponed; this document stays valid).
- **2026-11-07, dataset v1 (decided by the author):** after Step 27, do steps 4-6 (qualified assertions in the
  prefixes, grammar, block lambdas), then cut the first training dataset with the full test (section 7); steps 7-9 go
  to the second version.
- **2026-11-09, a target is always a single statement (asked by the author; design chosen by Claude, Step 29):** the
  model never generates a block of statements. A block lambda of one statement in `assertThrows`/`assertThrowsExactly`/
  `assertDoesNotThrow` is normalized as the longer blocks (Step 19; nothing moves: `() -> { a(); }` becomes `() -> a()`);
  any other assertion containing a block of statements (`assertTimeoutPreemptively(d, () -> {...})`, `assertAll(() ->
  {...})`, an anonymous class) produces no datapoint for now (a timeout block cannot be split without changing what is
  timed). Fixed at normalization, not at token generation, so that the target text (`tgt`) is one line too.
- **2026-11-09, dataset v1 cut; Hamcrest `assertThat` in scope (asked by the author):** the full test of the final jar
  (section 7) is dataset v1. Next, add `assertThat` (Hamcrest, `MatcherAssert.assertThat` included) to the grammar and
  accept its oracles (424 dropped: ffmpeg 312, cryptofs 112), then step 7.

## 3. Setting up the laptop

1. Clone the repository, branch `22-test-processing-refactoring`.
2. **The same JDK as the server: Amazon Corretto 21.0.6** (`sdk install java 21.0.6-amzn` with SDKMAN, or the Corretto
   archive), Maven, Python 3.9+. Set `TRACTO_JAVA_HOME` and `JAVA_HOME` to it if it is not at
   `scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn`.
3. **The same JDK sources as the server**, in `scripts/symbolic-module/lib/`: the archive of `LIB_JDKS_LINK`
   (`bash/utils/local_variables.sh`; section 0 of `missing-candidate-tokens.md`), not `setup-jdk-sources.sh` (its
   sources may differ). The candidates of the JDK types come from them.
   The corpus records the environment of its expected results (JDK version, file counts of the JDK sources, commits of
   the inputs): `corpus.py check` prints a `WARNING: the environment differs` when they do not match.
4. The nine input repositories at their commits: `python3 tools/ab/fetch_inputs.py` (list in
   `tools/ab/input-repos.csv`; 172 MB).
5. Build: `cd scripts/symbolic-module && mvn package -DskipTests` (online the first time), then
   `mvn test` (387 tests, all passing on 2026-11-05).
6. Prepare the corpus once: `python3 tools/ab/corpus.py prepare --jar target/tracto-jar-with-dependencies.jar`
   (the oracles stage of the nine projects, a few minutes each), then
   `python3 tools/ab/corpus.py check --jar target/tracto-jar-with-dependencies.jar`: it must print
   `IDENTICAL to the expected results`. If it does not, the setup differs from the server (JDK, JDK sources, inputs;
   see the warning): fix the setup before any change.

Memory: the corpus runs two JVMs with `-Xmx6g` by default (`-j`, `--jvm-options`); measured on the server: the tokens stage peaks at 4.9 GB per JVM on the corpus (jsoup), the
oracles stage of `prepare` at 6.6 GB (eec, jsoup, ffmpeg with `-Xmx6g`). On 16 GB: `-j 2 --jvm-options=-Xmx5g` for
`check`, `-j 1` for `prepare` (or `-j 2` with the watchdog running), with no other heavy program open. A `check` takes
12 minutes on the server with `-j 9` (jsoup's corpus classes alone take 11.5 minutes), about 25 minutes with `-j 2`.
Optional safety net: `python3 tools/ab/ram_watchdog.py &` kills the runs when the memory reaches 85% and grows.

## 4. The development loop (per step)

1. Write the tests of the fix first (a fixture in `TokensFixture` / `TokensDatasetTest`-style end-to-end tests, or
   unit tests); check they fail.
2. Implement; `mvn test` (all green); `mvn package -DskipTests`.
3. If the oracles stage changed: `corpus.py prepare --jar ...` again.
4. `python3 tools/ab/corpus.py check --jar target/tracto-jar-with-dependencies.jar` (about 25 min).
   It compares every oracle of the corpus with the expected results: status (complete / loses a step / dropped), the
   tokens missing from the candidates, the digest of its token steps. Report: `better`, `WORSE`, `same status but
   other candidates`, `new`, `gone`.
5. Review every difference. `WORSE` must be zero or explained (a target that is now correctly not offered, for
   example). `better` should be the oracles the step targets. `candidates changed` must be what the step intends.
   For the details of one oracle: `tmp/ab/dev/corpus/check/<project>/out` (its tokens dataset and statistics).
6. `python3 tools/ab/corpus.py accept --reason "Step NN: <what changed>"`: the new expected results, committed with the
   step (`tools/ab/corpus/expected.json`).
7. The step document (`docs/fixes/step-NN-*.md`): problem, fix, tests, corpus result (counts of the report).
8. Commit when the author asks.

Other tools (`tools/ab/`): `dev_tokens.py` runs two jars on chosen test classes and lists the oracles fixed, the
regressions and the causes of the tokens still missing (`missing_tokens.py`): useful while developing a fix, for the
classes the step targets (on the laptop its source run is the corpus source:
`dev_tokens.py <jar-A> <jar-B> corpus-src <label> jsoup:ElementsTest`). `ab.py` runs the full pipeline (section 7). `missing_tokens.py` and `compare_tokens.py`
load whole tokens datasets (jsoup 6.6 GB): on the laptop only on the corpus outputs.

What the corpus contains (`tools/ab/corpus/corpus.json`, chosen on the server on 2026-11-05 from the full runs of
Steps 20 and 21): 95 test classes, 1,302 oracles: 67 target classes (lost
oracles, up to 2 per cause and project), 34 guard classes (oracles recovered by Steps 20 and 21), 18 sampled classes.
The 99 oracles recovered by Step 22 are part of the expected results. Not covered: the oracles recovered by Step 20 in
slow classes (eec 362, uuid 78, crawler 48, jsoup 17, plexus 10, esapi 8). Checked on 2026-11-05: each of the 1,302
corpus oracles has the same status as in the full nine-project run of the same jar (the corpus is faithful despite the
history dependence of section 6). Classes slower than 2 minutes are left out (jsoup `ElementTest` alone takes 80
minutes): the full test of section 7 covers them. A class can depend on the classes processed before it in the same
JVM (pre-existing defect, section 6), so corpus results are compared only with corpus results.

## 5. Things learned the hard way

- On the server the memory limit is the container's (`/sys/fs/cgroup/memory.max`, 147 GiB), not what `free` shows
  (314 GB). Before streaming, one nine-project run with `-j 9 -Xmx24g` peaked at ~80 GB; three at once crashed it.
- `ab.py run` refuses (`NOT COMPLETE`) a run during which files of the module changed outside `tmp/` and `target/`:
  do not edit the module while it runs (develop in a git worktree under `tmp/ab/` instead).
- zsh does not split `$VAR` into words: `G="git -c ..."; $G commit` fails; use a function.
- Before Steps 21–24, two runs of the same code differed (identity hash order): every old A/B had noise. Old runs
  are not comparable byte for byte with new ones.
- Order-only differences need a deep comparison (the lists of `contextualInfos` are nested).

## 6. Next steps (in this order)

Each is its own step with the loop of section 4. The causes and their counts come from `missing_tokens.py` on the
full runs of the server (`docs/missing-candidate-tokens.md`, section 9); the corpus `target:<cause>` classes are the
examples to start from.

1. **Done: Step 25** (`docs/fixes/step-25-static-imports.md`). **Static imports** (130 lost oracles), wildcard included: methods and fields imported statically from classes of
   the project or of the JDK, offered as candidates (cause `static import`; jsoup 70, eec 41, ffmpeg 20).
2. **Done: Step 26** (`docs/fixes/step-26-class-names.md`). **Class names** (247): `java.lang`, the imports, the same package, the classes nested in the test class (cause
   `class name`; for example the exception class of an `assertThrows`). Do not offer the classes generated by the
   pipeline (`…STARSplitTest`, `…STARNormalizedTest`, `…STARSplitTestPrefix`): today they are offered (defect).
3. **Done: Step 27** (`docs/fixes/step-27-assigned-assertions.md`). **Assigned assertions** (91 dropped; decided by the author on 2026-11-06): `X e = assertThrows(...)` (84) and
   `X v = assertDoesNotThrow(...)` (7); cryptofs 32, esapi 28, jsoup 20; message "Assertion do not start with JUnit
   api". Design (author's choice: the model always generates a plain assertion, the declaration is added statically):
   - Oracles stage: when the target statement declares one variable whose initializer is a JUnit assertion call, the
     `target` is the assertion only, and a new optional datapoint field `assignment` (modifiers, type, name) keeps the
     declaration as written in the test. Copy the type, never infer it: it can differ from the class literal
     (`Exception ex = assertThrows(Selector.SelectorParseException.class, ...)`) and for `assertDoesNotThrow` it is
     the type of the lambda result.
   - Tokens stage and Python prompts: unchanged (the target starts with the assertion).
   - Inference (`TokensUtils.generateInference`, where `partialSnippet` ends with `;`): when the datapoint has an
     `assignment`, write `<modifiers> <type> <name> = <generated assertion>` as the generated oracle, joining strings
     (do not reprint the generated text with JavaParser: it would reformat it), then check that JavaParser parses
     the statement (status `ERROR` with a clear message if not). Build the `tgt` column the same way, so both
     columns are full statements and the RQ scripts (`rqs/RQ1_*.py`, text comparison) need no change. The model
     server (`neural-module/server.py`) only returns the next token: nothing to change there.
   - The later oracles of the same test already see the variable: the split prefixes keep the earlier assigned
     assertion (`CycleDetectedException e2 = assertThrows(...); ... e2.getCycle()`).
   - Same step (same flow): `Client.java` reads the oracles files with the streaming reader (`readJSONArray`).
   - Tests: extraction with `final`, a declared type different from the class literal, `assertDoesNotThrow`, a
     multi-line lambda; rebuilding gives back the original statement; an unparsable generated assertion gives
     `ERROR`; corpus: the 91 move from dropped to complete or "loses a step", 0 worse.
4. **Checked 2026-11-07, nothing to change: Qualified JUnit assertions in the prefixes.** The model input is already
   unqualified: the bodies of the test prefixes and of the invoked methods, and the targets, go through a text
   replacement of `Assert.`/`Assertions.` (`TestUtils`); none of the 9,154 datapoints of the corpus projects has a
   qualified assertion. Only the split prefix files keep them (cryptofs 110 files, steve 18, esapi 14), read by the
   symbolic module for the declarations in scope, not for the text of the calls. The text replacement has a latent
   defect, added to step 9.
5. **Done: Step 28** (`docs/fixes/step-28-grammar.md`). **Grammar** (101; 172 in the full run with the other causes): the spacing of `instanceof`, the `)` after a string concatenation. The grammar has no rule for `assertThrowsExactly`
   and `assertInstanceOf` (their lexer tokens exist; Step 27 added `assertDoesNotThrow`): check how many targets use them.
6. **Done: Step 29** (`docs/fixes/step-29-block-lambdas.md`). **Block lambdas** (52 dropped; `No enum constant for symbol: EOF`, cryptofs 49). The other 110 targets with the
   message "Assertion do not start with JUnit api" are Hamcrest `MatcherAssert.assertThat` (out of scope).
7. **Done: Step 30** (`docs/fixes/step-30-hamcrest-assert-that.md`; asked by the author on 2026-11-09). **Hamcrest
   `assertThat`** (424 dropped in dataset v1: ffmpeg 312, cryptofs 112): a grammar rule; `MatcherAssert.` removed as
   `Assertions.`; targets printed without comments; the sources of Hamcrest 2.2 (`assertion-api/`) given to the symbol
   solver as part of the assertion API known by the module, so that the matchers are candidates.
8. **Type inference of the chains** (causes `member of a chain`, 451 lost in v1, jsoup 287; `member of a known type`, 55).
   **Step 31 done** (`docs/fixes/step-31-chain-ancestors-array-length.md`): the type parameters of the ancestors (the
   result of `getAllAncestors()` was discarded: `els.get(0).` after `Elements extends ArrayList<Element>`), the
   `length` of the arrays. **Step 32 done** (`docs/fixes/step-32-casts.md`): the members of the type of a cast
   (`((SimpleRobotRules) rules).`, 44 steps in v1). **Step 33 done** (`docs/fixes/step-33-unresolvable-signatures.md`):
   re-measured on the 52 v1 classes with these causes (v1 jar → Step 32 jar: +594 oracles, 0 worse; 257 missing
   steps left); then the methods of a type whose lookup failed as a whole (a type of a dependency in one signature:
   eec `Styles.toColor`, esapi `ESAPI.securityConfiguration`, steve, ffmpeg, cryptofs; the accessors of the records).
   **Step 34 done** (`docs/fixes/step-34-var-initializers.md`): the lambda bodies `()->x.` (cryptofs) were not the
   cause, the variables declared with `var` were: their type from the parts of the initializer when the symbol solver
   cannot type it as a whole (an argument of a type of a dependency), when not ambiguous.
   Remaining shapes, causes to be examined: parenthesized expressions `(new X()).` (esapi), arrays
   returned through a type variable (`strings.get(0).length`, eec), the members of an interface's `Object`
   (`data.get(0).toString`), fields of other classes (`structEval.threadMemo`, jsoup).
9. **Dependencies** (cause `dependency`, 91 in v1): needs a design decision of the author on the inference setting
   (section 6 of `missing-candidate-tokens.md`).
10. **Pre-existing defects**, each its own step:
   - the candidates depend on the processing history: state kept across datapoints and classes (the static
     `unsolvableTokens`/`rootUnsolvableTokens` of `TokensUtils`; `cloneRootTokenReferences` copies maps shallowly and
     the stream path appends into the root's lists). Fixing it also makes the full test splittable per test class
     (section 7);
   - the protected-member filter compares package names with `String.contains`;
   - an error path takes a substring of a message that contains an identity hash (`Range [131, 126) out of bounds`,
     cryptofs `ChunkSaverTest`);
   - the test methods annotated `@ParameterizedTest` (or `@RepeatedTest`, `@TestFactory`, `@TestTemplate`) are offered
     as candidates: only `@Test` is excluded (`TokensUtils.collectMethodUsages` / `collectResolvedMethodDeclaration`,
     `TestClassScope.addMethods`); seen in jsoup `TokenQueueTest`. Cheap;
   - the private members of the JDK classes are offered after a value of that type (`String`: `isMalformed3`,
     `COMPACT_STRINGS`; `Object`: `wait0`), in the outputs of Step 24 already (steve, cryptofs, jsoup): the modifier
     rules of `collectMethodUsages` let them through for the classes read from the JDK sources (not verified further);
   - `java.lang` and the JDK classes come from the newest and the oldest JDK of `lib` when the Java version of the
     repository is not pinned to one JDK (`JavaUtils`): classes of both are offered (`Compiler`, `IO`);
   - after the name of a class (`String.`), its instance and protected methods are offered too (`clone`, `finalize`):
     the modifier rules are combined with OR and the rule "not private" alone accepts them
     (`JavaParserUtils.checkModifiersRules`, found in Step 33);
   - ~~the body of a method without a source is garbage taken from the text of its `MethodUsage`~~: **Step 35 done**
     (`docs/fixes/step-35-sourceless-bodies.md`);
   - the imports and the package of the focal class are added to the classes of the test class (not visible there
     unless imported in the test class too);
   - the qualifiers `Assert.`/`Assertions.` are removed with a text replacement (`TestUtils`, targets and bodies):
     `org.junit.Assert.assertFalse(...)` would become `org.junit.assertFalse(...)`, and a class of the project or of a
     library named `Assert` loses its name (Spring `Assert.notNull(...)` in steve's main code). No datapoint of the
     nine projects is affected today (esapi `AccessControllerTest`, the only fully qualified calls, has no datapoint;
     steve's calls are not in an invoked method). Fix: an AST rewrite of the calls to the JUnit classes only;
   - the comments of the arguments of a target, moved before the statement by the printing of the normalization and of
     the split, stay in the test prefix where the target was (`// // /*<MASK_PLACEHOLDER>*/`; Step 30, cryptofs
     `CryptoFileSystemPropertiesTest`);
   - the `LAMBDA_PARAM` placeholder (added when the token after `(` is missing in a stream context,
     `TokensUtils` ~line 412) is offered outside lambdas (eec `CacheTest`: `assertEquals(hot.toString(`); probably the
     stream flag is kept across datapoints (not verified).

**When to stop and train** (decided by the author on 2026-11-07: after steps 4-6, see section 2; the first proposal was): cut the first training dataset after steps 1-3
(static imports, class names, assigned assertions; about 470 oracles at most, the causes overlap), with the full test
of section 7; keep fixing for a second version.

Parked (low priority): seeded shuffle of the candidates in the prompt builder; measure the position of the target in
the candidate lists.

## 7. Before the training: the full test (on a large server)

Run on a machine like the 2026-11 server (32 cores, ≥ 64 GB; the container limit is what counts). Commands from
`scripts/symbolic-module`:

```bash
python3 tools/ab/ram_watchdog.py tmp/ab/ram_watchdog.log &          # memory safety net (85% and growing -> kill)
python3 tools/ab/fetch_inputs.py                                     # inputs at their commits
python3 tools/ab/ab.py build c164602 tmp/ab/jar-baseline.jar # the baseline: Step 21 (last full A/B)
python3 tools/ab/ab.py build HEAD tmp/ab/jar-final.jar
P=crawler,ffmpeg,jsoup,eec,plexus,esapi,cryptofs,uuid,steve
python3 tools/ab/ab.py run baseline tmp/ab/jar-baseline.jar src/main/resources/dataset/oracles-dataset_config.json --tokens --tokens-projects $P -j 4 --jvm-options=-Xmx24g &
python3 tools/ab/ab.py run final    tmp/ab/jar-final.jar    src/main/resources/dataset/oracles-dataset_config.json --tokens --tokens-projects $P -j 9 --jvm-options=-Xmx12g &
wait
python3 tools/ab/compare_tokens.py tmp/ab/baseline tmp/ab/final $(echo $P | tr , ' ')   # transitions, regressions
python3 tools/ab/missing_tokens.py tmp/ab/final:$P                                      # lost oracles by cause
```

The baseline (Step 21, before streaming) needs up to ~20 GB per JVM (jsoup); the final jar streams, but the peak of
its tokens stage on the slowest classes (jsoup `ElementTest`, eec) is not measured yet (they are not in the corpus):
`ab.py` records it (`max_rss_mb` in `tmp/ab/final/summary.json`); if a project reports `OutOfMemoryError`, rerun it
alone with a larger heap (`--projects jsoup`). The longest
project is jsoup (~4.3 h, one JVM: `ElementTest` 80 min, `SelectorTest` 36 min); the rest finishes earlier. Expect
the oracles datasets identical (up to `Anonymous-<uuid>`), no unexplained regression, the lost oracles lower.
`compare_tokens.py` compares candidates as sets, so the order changes of Step 24 do not show as differences.
Then generate the training dataset with the final jar (same `ab.py run`, or the production scripts).

**Done on 2026-11-09 (dataset v1):** the final jar of Step 29 (`1981c0a`) against the Step 21 jar (`tmp/ab/step21-B`,
run on 2026-11-05 with the same configuration; not rerun). `compare_tokens.py`: 0 regressions; 767 more oracles
complete (lost 1,892 → 1,116 of 9,154 datapoints); the transitions from or to `absent` are oracles whose target text
changed in steps 27–29 (a new key). The oracles datasets are identical, file by file, to those of `corpus.py prepare`
for Step 29. `missing_tokens.py`: 12.2% lost (crawler 1.6%, plexus 1.0%, jsoup 8.1%, eec 9.1%, steve 11.0%, esapi
13.5%, uuid 14.9%, cryptofs 19.2%, ffmpeg 60.4%). Every stage exited 0; peak memory 10.3 GB (jsoup, `-Xmx12g`);
jsoup took 4.6 h (`-j 9`), eec 2.6 h, the others under 45 min. The datasets (25 GB) are in
`tmp/ab/final/<project>/out/raw-tokens-dataset-training/`. Two traps: in zsh `$P` is not split on spaces (write the
project names out for `compare_tokens.py`); `ab.py` reported a false `OutOfMemoryError` on every project, because the
log prints the candidates and `OutOfMemoryError` is one of them since Step 26 (fixed: it now looks for
`java.lang.OutOfMemoryError`).

Step 21's own A/B (2026-11-05, server): A = Step 20, B = Step 21, nine projects, `tmp/ab/step21-A|B` on
the server: 20.8% lost on both sides (7,170 complete of 9,049 by `missing_tokens.py`), 0 regressions, no status change
(`compare_tokens.py`). The full test before the training compares the final jar with this baseline.
