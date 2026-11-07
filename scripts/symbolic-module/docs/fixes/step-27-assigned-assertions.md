# Step 27 – Assigned assertions

**Date:** 2026-11-06
**Branch:** `22-test-processing-refactoring` (on top of Step 26)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/components/Assignment.java` (new): the declaration of the variable assigned
  with a target assertion (modifiers, declared type, name), joined back with an assertion by `declare`
- `src/main/java/star/tracto/preprocessing/components/OracleDatapoint.java`, `builders/OracleDatapointBuilder.java`: the
  optional field `assignment` (written only when present), `statement` (the target with its declaration)
- `src/main/java/star/tracto/preprocessing/utils/AssignedAssertions.java` (new): the split of an assigned assertion
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (`processSplitTestClass`): the target of an assigned
  assertion is the assertion only, the declaration goes to `assignment`
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java` (`generateInference`, `completedInference` new): the
  declaration added back to the generated assertion and to the `tgt` column, the statement checked with JavaParser
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java`: `statementParseProblem` (new)
- `src/main/java/star/tracto/lsp/antlr4/grammars/JavaParser.g4`, `Antlr4Utils.java` and the generated parser
  (`antlr4Class/`): the rule `assertDoesNotThrowMethodCall` (the grammar had no rule for `assertDoesNotThrow`)
- `src/main/java/star/tracto/Client.java`: the oracles files read one test class at a time (`readJSONArray`)
- tests: `AssignedAssertionsTest` (new, 5), `AssignedAssertionsTokensTest` (new, 2 end-to-end),
  `TestUtilsDatapointsTest` (1 new, 1 updated); `TokensFixture.messages` (new)

## Problem

91 oracles of the nine projects were dropped by the tokens stage with the message "Assertion do not start with JUnit
api": the target was the whole statement `X e = assertThrows(...)` (84) or `X v = assertDoesNotThrow(...)` (7)
(cryptofs 32, esapi 28, jsoup 20, ffmpeg 4, plexus 3, uuid 3, crawler 1). Some come from the normalization itself,
which turns a `try { ...; fail(...); } catch (E e) { ... }` into `E e = assertThrows(E.class, ...)`.

## Fix

Design decided by the author on 2026-11-06: the model always generates a plain assertion; the symbolic module adds
the declaration back, with JavaParser, at the end of the generation.

- **Oracles stage:** when the target statement declares one variable initialized with a JUnit assertion, the `target`
  is the assertion only, printed as a statement by itself, and the new optional field `assignment` keeps the
  declaration as written in the test: annotations and modifiers, the declared type (copied, never inferred: it can
  differ from the class literal, `RuntimeException ex = assertThrows(NumberFormatException.class, ...)`, and for
  `assertDoesNotThrow` it is the type of the lambda result), the name. The split is kept only if the declaration
  joined with the assertion gives back exactly the statement as printed before; otherwise the statement stays whole (a
  warning; none in the corpus). The test prefixes do not change: the later oracles of the test case still see the
  variable.
- **Tokens stage and Python prompts:** unchanged; the target starts with the assertion.
- **Grammar:** the grammar had no rule for `assertDoesNotThrow` (the tokens stage failed with "Unsupported assertion
  type: ASSERT_DOES_NOT_THROW"). New rule, as `assertTimeout` without the duration (the signatures of JUnit 5: an
  `Executable` or a `ThrowingSupplier`, then an optional message, a `String` or a `Supplier<String>`):
  `ASSERT_DOES_NOT_THROW '(' (executable | throwingSupplierExpression) (',' (supplierExpression | STRING_LITERAL))? ')' SEMI`.
  The parser is regenerated with ANTLR 4.13.2, the version of the committed parser (the complete jar of Maven Central,
  SHA-1 `7df86c34…` checked); regenerating the grammar before the change gave the committed files byte for byte (besides
  the header with the path of the grammar, kept).
- **Inference** (`generateInference`): when the generation ends with `;` and the datapoint has an `assignment`, the
  generated oracle is `<modifiers> <type> <name> = <generated assertion>`, joined as strings (the generated text is not
  reprinted), and JavaParser must parse it as a statement (status `ERROR` with a message otherwise). The `tgt` column is
  built the same way, so both columns are full statements and the RQ scripts (`rqs/RQ1_*.py`, text comparison) need no
  change. The model server only returns the next token: nothing changes there.
- `Client.java` reads the oracles files with the streaming reader, one test class at a time (as `TokensDataset` since
  Step 23).

## Tests

- `TestUtilsDatapointsTest.assignedAssertionIsTheTargetWithItsDeclarationApart`: `final` with a declared type different
  from the class literal (and the qualifier `Assertions.` removed), `assertDoesNotThrow`, a block lambda printed on
  several lines; joined with its declaration, each target gives back the statement of the test.
- `TestUtilsDatapointsTest.assertThrowsGeneratedFromATryStatementIsTheTargetWithTheCommittedConfiguration` (updated):
  the `assertThrows` made by the normalization from a `try`/`fail`/`catch`, and the prefix of the next oracle keeps the
  whole statement.
- `AssignedAssertionsTest`: only a variable initialized with a JUnit assertion is split (not two variables, not another
  call, not a declaration without initializer); the datapoint written and read back with its assignment, no field
  without one (and the files without the field are read); the declaration added back to the generated text as it is;
  an assigned generated assertion that does not parse gives `ERROR`; a plain one is kept as it is.
- `AssignedAssertionsTokensTest` (end-to-end): the assigned `assertThrows` and `assertDoesNotThrow` are targets of the
  tokens stage with all their tokens among the candidates; the later oracles see the variable.

Checked on 2026-11-06: against the code of Step 26, the end-to-end test fails (both assigned assertions dropped; the
other new tests use the new classes). Mutations: without the grammar case of `assertDoesNotThrow`, the end-to-end test
fails; without the modifiers in the declaration, the oracles test fails (the statement stays whole).

## Verification

Oracles stage of the nine corpus projects (`corpus.py prepare`, 9,154 datapoints), compared with the oracles of
Step 26: exactly 91 datapoints change, the assigned assertions (84 `assertThrows`, 7 `assertDoesNotThrow`; cryptofs
32, esapi 28, jsoup 20, ffmpeg 4, plexus 3, uuid 3, crawler 1), each giving back its statement when joined with its
declaration; no split refused. The other 9,063 are identical (12 test prefixes differ only by the random names of the
anonymous classes, `Anonymous-<uuid>`).

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-06, against the expected results of Step 26:

| | complete | loses a step | dropped |
|---|---|---|---|
| Step 26 | 1,071 | 174 | 57 |
| Step 27 | 1,089 | 175 | 38 |

**0 worse**, no other candidate changed. 17 oracles of the corpus are assigned assertions: their key changes (the
target text), paired by test class and prefix: 15 dropped → complete (jsoup 10, uuid 3, cryptofs 1, esapi 1); 2 still
dropped, `assertThrows` with a block lambda ("No enum constant for symbol: EOF", the block lambdas step). 4 plain
`assertDoesNotThrow` targets of cryptofs, dropped before for the missing grammar rule, are recovered too: 3 complete, 1
loses a step (`decoratedConsumer.accept(event)`: `accept`, a member of a generic `Consumer`, the chains step).
