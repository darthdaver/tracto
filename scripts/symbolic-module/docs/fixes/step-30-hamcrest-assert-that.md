# Step 30 – Hamcrest `assertThat`

**Date:** 2026-11-09
**Branch:** `22-test-processing-refactoring` (on top of Step 29 and the dataset v1 results)
**Files changed:**
- `src/main/java/star/tracto/lsp/antlr4/grammars/JavaParser.g4` (rule `assertThatMethodCall`) and the generated parser
  (`antlr4Class/`, ANTLR 4.13.2, as in Step 28; the lexer is unchanged, byte for byte); `Antlr4Utils.parseSnippet`: the
  case `ASSERT_THAT`
- `assertion-api/` (new): the sources of Hamcrest 2.2 (`hamcrest-2.2/`, 80 files, BSD 3-Clause license), with
  `README.md` (origin, SHA-1)
- `src/main/java/star/tracto/utils/JavaUtils.java`: `assertionLibraryFolders` (new), added to the classpath of the symbol
  solver by `initializeJavaClassPath`
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java`: the four lookups of a class or a package by name
  also search the assertion libraries (after the JDK sources)
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`: `printTarget` and `withoutAssertionClasses` (new; the
  five copies of the regular expressions removing `Assert.`/`Assertions.` replaced, `MatcherAssert.` added)
- `StaticImports` (comment), `docs/oracles-dataset-config.md`
- tests: `AssertionGrammarTest` (1 new), `TestUtilsDatapointsTest` (1 new), `HamcrestTokensTest` (new, end-to-end);
  `TokensFixture` (comment)

## Problem

Dataset v1 (full test of 2026-11-09) drops 424 oracles that use the `assertThat` of Hamcrest:
- 314 `assertThat(actual, matcher)` (ffmpeg 312, cryptofs 2): "Unsupported assertion type: ASSERT_THAT", the grammar
  has no rule for it (the lexer token and `JUnitAssertionType.ASSERT_THAT` exist);
- 110 `MatcherAssert.assertThat(...)` (cryptofs): "Assertion do not start with JUnit api", the class qualifier stays in
  the target (only `Assert.` and `Assertions.` are removed); 8 of them start with `//\n//\n`: the line comments at the
  end of their argument lines (`containsInAnyOrder( //`) are moved from node to node by the printing of the
  normalization and of the split, and the target was printed with them.

Even with a rule, the matchers (`is`, `equalTo`, `hasSize`, `containsInAnyOrder`, ...) would not be candidates: they are
imported from Hamcrest, a dependency of the project, and the symbol solver gets the dependencies of no project (step 8).

Until now Hamcrest was out of scope (decision of 2026-11-04). On 2026-11-09 the author asked to add `assertThat` to the
grammar and to accept these oracles.

## Decision (2026-11-09; design left to Claude)

- **Hamcrest is part of the assertion API known by the module**, as the grammar knows the JUnit assertions: the model
  generates its matchers. Its sources (Hamcrest 2.2, one artifact that holds the API of `hamcrest-core` and
  `hamcrest-library` 1.3 too) are part of the module, in `assertion-api/hamcrest-2.2`, and are given to the symbol
  solver for every repository, after the JDK sources. This does not decide step 8 (the dependencies of the projects).
  Sources and not the binary jar: the static imports (Step 25) and the class names (Step 26) read the declarations.
  Not in `lib/`: `bash/oracles-dataset.sh` and `bash/tokens-dataset.sh` empty `lib/` when a JDK is missing.
- **Grammar**: `assertThat(actual, matcher)`, `assertThat(reason, actual, matcher)`, `assertThat(reason, boolean)`:
  `ASSERT_THAT '(' expression ',' expression (',' expression)? ')' SEMI`. The `assertThat(x)` of AssertJ (one argument,
  then a chain) has no rule: AssertJ stays out of scope (its chains are not split points anyway).
- **The class is removed**, as `Assertions.`: `MatcherAssert.assertThat` and `org.hamcrest.MatcherAssert.assertThat`
  become `assertThat`, in the targets and in the bodies of the model input. Still a text replacement, as for
  `Assert.`/`Assertions.` (known defect, step 9); for `MatcherAssert` only before `assertThat`, with its package.
- **A target is printed without comments** (`printTarget`): a target is one statement on one line (Step 29), whatever
  the comments of the source and wherever the earlier printing moved them.

## Tests

- `AssertionGrammarTest.assertThatOfHamcrestHasARule`: the candidates after `assertThat(`, after the actual value,
  after two arguments (`)` or `,`), after three (`)` only), after the `)` (`;`).
- `TestUtilsDatapointsTest.hamcrestAssertThatIsATargetWithoutItsClassOnOneLine`: `MatcherAssert.assertThat`,
  `org.hamcrest.MatcherAssert.assertThat` with a reason, an `assertThat` whose arguments end with `//` comments: three
  unqualified one-line targets; the test prefix has no `MatcherAssert` either.
- `HamcrestTokensTest` (end-to-end): three `assertThat` targets of the tokens stage, complete: a matcher imported
  statically one by one (`is`) or with a wildcard (`hasSize`), a class of Hamcrest imported (`CoreMatchers`) and its
  matcher (`hasItem`); the target without `MatcherAssert.`.

Checked on 2026-11-09: against the code of Step 29, the three tests fail ("Unsupported assertion type: ASSERT_THAT";
the targets `MatcherAssert.assertThat(...)` and `//\n...`). All 439 tests pass.

## Verification

Oracles stage of the nine corpus projects (`corpus.py prepare`), compared with the oracles of Step 29 (same 9,148
datapoints, same keys):
- targets: 110 change, all in cryptofs: the 102 `MatcherAssert.assertThat` lose their class, the 8 with comments are
  on one line;
- test prefixes: the bodies of 68 cryptofs prefixes lose `MatcherAssert.`; the invoked methods of 239 ffmpeg and 78
  cryptofs prefixes gain the matchers called in the prefix, now resolved (ffmpeg: `is` 222, the constructor
  `new IsNull<>()` 40, `hasSize` 12, `endsWith` 11; cryptofs: `is`, `empty`, `containsString`, `contains`, and
  `hasAtLeastRemaining`, a matcher of the project whose type is a Hamcrest `Matcher`), as the methods of the JDK already
  are (`List.get`, `size`); 11 jsoup prefixes differ only by the random names of anonymous classes;
- test classes (cryptofs): the bodies of 125 test cases lose `MatcherAssert.`; the Hamcrest types of 4 auxiliary
  methods are now resolved and written as the other resolved types (`Lorg/hamcrest/Matcher;` instead of the fallback
  `org.hamcrest.Matcher`, which for `anEntry` also listed a stray `java.util.HashMap`). The dataset already mixes the two
  forms (resolved: `java/nio/file/Path;`, unresolved: `org.mockito.stubbing.Answer`), a pre-existing inconsistency.

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-09:

| | complete | loses a step | dropped |
|---|---|---|---|
| Step 29 | 1,119 | 155 | 28 |
| Step 30 | 1,139 | 160 | 3 |

**0 worse.** The 25 Hamcrest oracles of the corpus are no longer dropped: 20 complete (ffmpeg 13, cryptofs 7, of which
5 under a new key, the target without `MatcherAssert.`), 5 lose a step (ffmpeg `assertThat(command,
is(ImmutableList.of(...)))`: `ImmutableList` is Guava, a dependency, step 8). The 3 still dropped are the
`assertInstanceOf` (jsoup 2, cryptofs 1), without a grammar rule.

114 oracles keep their status with other token steps (cryptofs 53, ffmpeg 34, esapi 18, steve 9), compared step by
step with a rerun of the Step 29 jar on the Step 29 oracles (which gave back its expected results exactly): only
additions, nothing removed. Candidates added: the classes of Hamcrest imported (`MatcherAssert`, `Matchers`,
`CoreMatchers`, esapi's `CustomMatcher`), the matchers imported statically (`is`, `hasSize`, `hasItem`, `contains`,
`greaterThanOrEqualTo`, ...), `assertThat` imported statically, `hasAtLeastRemaining`. Contextual information added
(the declarations of the members used in the partial oracle, now resolved): `assertThat`, `is`, `hasItem`, `hasSize`,
`contains`, ... (ffmpeg 133 steps, cryptofs 36, steve 27).

Expected on the nine projects (not measured: the full test takes about 5 hours): most of the 424 dropped oracles
recovered, those with a Guava argument (ffmpeg `ImmutableList`) losing a step instead.

## Known defect left

The comments of a target's arguments, moved before the statement by the earlier printing, stay in the test prefix
where the target was (`// // /*<MASK_PLACEHOLDER>*/`). Not specific to Hamcrest (any assertion with line comments in its
arguments); added to step 9 of `docs/resume.md`.
