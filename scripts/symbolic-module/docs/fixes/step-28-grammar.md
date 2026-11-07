# Step 28 – Grammar

**Date:** 2026-11-07
**Branch:** `22-test-processing-refactoring` (on top of Step 27)
**Files changed:**
- `src/main/java/star/tracto/lsp/antlr4/grammars/JavaParser.g4` and the generated parser (`antlr4Class/`, ANTLR 4.13.2):
  the message of the assertions, the method and constructor references
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `appendToken` (new), the partial oracle joined the
  same way in the tokens dataset and at inference
- tests: `AssertionGrammarTest` (new, 3), `AppendTokenTest` (new, 3); `ClassNamesTokensTest` (the known failure after
  `instanceof` removed: the test now requires the `)` and the `;`)

## Problem

Cause `grammar` of `missing_tokens.py` in the full run after Step 21: 172 lost oracles, a Java token missing from the
candidates of the grammar. Three defects:

1. **The message of an assertion had to be a string literal** (111: `)` missing; crawler 63, uuid 57 with the other
   causes): `assertNull(normalizer.filter(url), "normalizing: " + url)`, `String.format(...)`. JUnit takes any
   `String` (or a `Supplier<String>`); the rules had `(supplierExpression | STRING_LITERAL)`.
2. **`instanceof` joined to the previous token** (59: `)` or `,` missing): the tokens stage joined a keyword with a
   space after it only (`asm` + `instanceof ` = `asminstanceof `), read by the lexer as one identifier. Inference joined
   it differently (a space before and after, except after a dot): the partial oracles of the training and of the
   inference differed for every keyword (`assertEquals(new Foo(` against `assertEquals( new Foo(`).
3. **The references after a type** (2: `toArray(String[]::new)`): the grammar of Tracto had moved
   `typeType '::' ...` and `classType '::' NEW` of the reference grammar into `methodReferenceSuffix`, the suffix of an
   expression (`expression typeType '::' ...`). `String[]::new` and `Foo::new` could not be parsed. The same suffix let
   a type start right after any expression: the primitive types, the contextual keywords (`var`, `record`, `module`, …)
   and `IDENTIFIER` were offered after a complete expression (`assertEquals(x` → `int`), and with `IDENTIFIER` the
   project identifiers.

## Fix

1. The message is `(supplierExpression | expression)` in every rule (15). The JUnit 4 message before the arguments
   (`(STRING_LITERAL ',')?`) is unchanged: a concatenation there is already read by the first alternative.
2. `appendToken`, used by the tokens stage and by inference: a keyword is followed by a space, and preceded by one when
   it follows an operand (an identifier, a literal, `)`, `]`); the other tokens are joined without spaces. The tokens
   dataset changes only where a keyword follows an operand (`x instanceof T`); inference now writes the partial oracles
   as the tokens dataset (`(new Foo(`). No model has been trained on the datasets of this branch yet.
3. `methodReferenceSuffix` is `'::' typeArguments? identifier` or `'::' typeArguments? NEW` (`Foo::new` after the
   expression `Foo`); `expression` has the alternative `typeType '::' (typeArguments? identifier | NEW)` (`String[]::new`,
   `int[]::clone`), as the reference grammar.

## Tests

- `AssertionGrammarTest`: after a message that is a concatenation, a call, a literal or a supplier, `)` is offered;
  `::` after `String[]`, `new` after `String[]::` and `Foo::`, an identifier after `Foo::`; `)` and `,` after
  `x instanceof T`.
- `AppendTokenTest`: a keyword after an identifier, a call, an array access is separated; after `(`, `,`, `.` it is not;
  the other tokens are joined without spaces.
- `ClassNamesTokensTest`: `assertTrue(clock instanceof Shape);` is complete.

Checked on 2026-11-07 against the code of Step 27: `AssertionGrammarTest` fails on the message and on the references
(the `instanceof` test passes: the grammar reads `x instanceof T` when it is spaced); `ClassNamesTokensTest` expected
the failure after `clockinstanceof`.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-07, against the expected results of Step 27:

| | complete | loses a step | dropped |
|---|---|---|---|
| Step 27 | 1,089 | 175 | 38 |
| Step 28 | 1,110 | 155 | 37 |

21 oracles recovered (plexus 9, esapi 5, jsoup 3, crawler 2, cryptofs 2): 13 messages, 7 `instanceof`, 1
`String[]::new` (dropped before). **0 worse.** The token steps of almost every oracle change, all checked (step by
step against the outputs of Step 27):

- the partial oracles: only the 45 steps after `instanceof` (`einstanceof ` → `e instanceof `);
- Java candidates added: `)`, `;`, and the operators after `x instanceof T`;
- Java candidates removed (6,254 steps): the primitive types and the contextual keywords, only after a complete
  expression (an identifier or a literal 4,878, `)` 1,291, `]` 84, `i++` 1), where no type can start;
- project candidates (644 steps): only removed, only after a complete expression (`)` 585, `]` 49, a literal or an
  identifier 9, `i++` 1): the identifiers offered through the `IDENTIFIER` of the type after an expression.

Not measured on the corpus: the inference path (`appendToken` in `generateInference`), covered by `AppendTokenTest`.
