# Step 44 – The last shapes of dataset v3: parenthesized values, inherited fields, `to`, string literals, qualified class names

**Date:** 2026-11-16
**Branch:** `22-test-processing-refactoring` (on top of Step 43 and dataset v3)
**Files changed:**
- `src/main/java/star/tracto/lsp/antlr4/Antlr4Utils.java`: `getAntlrToken` reads the contextual keywords (`to`,
  `with`, `module`, `record`, `yield`, ...) as identifiers
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: the value of a parenthesized expression
  (`carryValue`), the members of `String` after a string literal (`applyValueType`, generalized from `applyCast`), the
  inherited fields after a value, the names of the packages (`packageIdentifiers`, the class created by `new` after a
  qualified name: `afterNew`)
- `src/main/java/star/tracto/utils/javaParser/PackageIndex.java` (new): the packages of the source roots of the
  symbol solver; `JavaParserUtils.sourceRoots()`
- `src/main/java/star/tracto/data/enums/ContextualInfoType.java`: `LITERAL_RETURN`
- tests: `ValueShapesTokensTest` (new, end-to-end), `PackageIndexTest` (new)

## Problem

Dataset v3 (full test of Step 43, `docs/resume.md` section 7) loses 15 of 9,043 oracles, one missing token each, in
six shapes that are not in the corpus. Five are defects of the candidates (the sixth, the name of a lambda parameter,
`ps.removeIf(` ▸ `el2`, is a new name, as a local variable declared in the target):

| Shape | v3 | Cause |
|---|---|---|
| `(new CipherSpec()).` ▸ `getKeySize` | esapi 4 | the closing parenthesis of a parenthesized expression pops its frame: the members of the value, collected in that frame, are lost (only a cast was handled, Step 32) |
| `b_needs_a.` ▸ `threadMemo` | jsoup 4 | after a value, the fields of its class only: the inherited fields were never collected (here a package-private field of the package-private superclass `StructuralEvaluator`) |
| `to.` ▸ `mkdirs` | plexus 3 | `to` is a token of the lexer (`TO`, module declarations): read as a keyword, the prefix is printed `to .` and the variable is not recognized |
| `"mysql".` ▸ `equals` | eec 1, cryptofs 1 | a string literal cleared every candidate (a TODO of the original code) |
| `new ` ▸ `java` (`new java.awt.Color(...)`) | eec 1 | no package names among the candidates |

## Fix

- **Contextual keywords.** `getAntlrToken` returns `IDENTIFIER` for the tokens of the lexer that are keywords only in
  a module declaration (`module`, `open`, `requires`, `exports`, `opens`, `to`, `uses`, `provides`, `with`,
  `transitive`), a type declaration (`record`, `sealed`, `permits`) or a switch block (`yield`): in an assertion they
  can only be names (the grammar already accepts them as `identifier`). Not `var`, the type of the parameter of a lambda.
  The partial oracle is then `to.`, and the variable is matched.
- **Parenthesized values.** When the frame of a parenthesized expression closes and it is still that frame (an
  operator, a comma or a lambda arrow resets the frame: it is a single value), the enclosing frame records it
  (`parenthesizedValue`, for the next token only); if the next token is a dot, the enclosing frame takes the
  identifiers and references of the value (`carryValue`): the candidates after `(value).` are the ones after
  `value.`. The frame's own fields (its parenthesized flag, its tokens, its cast) are kept. Not at the closing
  parenthesis: the group can be a type, `(int) cache.get("a")` (a primitive cast, not recognized as a cast by Step
  32): a first version carried it at once and lost the names after the cast (corpus: 5 eec oracles worse, below).
  Casts keep their handling (Step 32).
- **String literals.** After a string literal (or a text block), the members of `java.lang.String` (the JDK sources),
  as after a value (`applyValueType`, the method of the casts with the contextual type as a parameter; the new type
  `LITERAL_RETURN`). The other literals keep clearing the candidates (primitives, `null`).
- **Inherited fields.** After a value, the fields of the ancestors of its class too (`getAllAncestors`, the ones with
  a source), filtered by the access rule of Step 37 (a private field of a superclass is not offered; a package-private
  one only in its package).
- **Qualified class names.** `PackageIndex` lists the packages of the source roots the symbol solver reads (the
  projects, the sources and stubs of the dependencies, the JDK; recorded by `setRepoJavaParser`, without the
  delomboked copies), built once per set of roots. A folder is a package only if its files declare the package its
  name gives (the first file read): the roots found by `findPotentialProjectsRoots` are a superset of the true
  ones (any folder containing a folder `java`, `src`, `test`, ...: `java.base/java` next to `java.base`), which
  the solver tolerates but which gave `io`, `lang`, `util`, `awt`, ... as first names in a first version (seen
  in the corpus candidates, below). The first names of the packages (`java`, `org`, `com`, ...) are
  candidates where the classes are (`packageIdentifiers`, reset with the classes, root and after `instanceof`); after
  the name of a package, its subpackages and its public top-level classes (read when first asked). A variable or a
  class of the same name obscures a package (JLS 6.4.2: the package branch only when nothing else matches). The class
  created by `new` is followed until the first token that is not a name or a dot (`afterNew`), so that
  `new java.lang.StringBuilder(` (and `new Outer.Inner(`) gives the constructors and the members of the value created,
  not the static members of a class name.

Left, seen while writing the test: a generic class created with type arguments, `new ArrayList<String>().` (the `<`
resets the frame, as an operator): its members are not offered. Not in the lost oracles of v3.

## Tests

`ValueShapesTokensTest` (end-to-end): `(new Spec()).` and `(new Spec(size)).` ▸ `size`, `needsIv`; `not.` ▸ `memo`
(package-private, inherited from a package-private class), `matches`; `to.` ▸ `mkdirs`, `exists`; `"mysql".` ▸
`equals`, `length`, `formatted`; `new ` ▸ `java`, `com`, `Spec`; `new java.` ▸ `lang`, `util`; `new java.lang.` ▸
`StringBuilder`, `String`, `reflect`, not `AbstractStringBuilder` (package-private); `new java.lang.StringBuilder("ab").`
▸ `length`, `append`, not `java`; after a primitive cast `(long) ` the names of the root (`spec`). Against the code of Step 43: 11 target tokens missing, in the five shapes. Each part
of the fix checked by a mutation (removed: the test fails): the contextual keywords, the parenthesized value, the
string literal, the inherited fields, the first package names, the subpackages, the `new` flag (`length` missing
after `new java.lang.StringBuilder("ab").`).

`PackageIndexTest`: the packages of a JDK-like root listed with its wrong sub-root (`java.base/java`): first names
`com`, `java` only (fails without the check of the declarations: `lang`, `util` too), the public classes of a
package, the first root wins.

463 Java tests pass.

## Verification

Regression corpus, 2026-11-16, against Step 43 (`tmp/ab/corpus-step43-out`, `corpus-step44-out`): **1,302 of
1,302 complete, 0 worse**, accepted.
- candidates: additions only (4,458 steps), 0 target tokens removed: the first names of the packages where the
  classes are (`java`, `javax`, `jdk`, `sun`, `com`, `org`, `netscape`, `apple` of the JDK sources; `de`,
  `net`, `crawlercommons`, `test`, `perf`, `micro` of the projects), the inherited fields (`EmptyNodes`,
  `UNSET_CRAWL_DELAY`, `runFunc`, ...), the members of `String` after a string literal;
- contextual information: additions only; 738 steps after a string literal describe `String` (`DEFINITION` →
  `LITERAL_RETURN`), as after a variable of type `String`; 2 steps of ffmpeg `ffmpeg.version` `METHOD_CALL` →
  `FIELD_CALL`: `FFmpeg` inherits the package-private field `version` of `FFcommon` (the package of the test), a
  name now both a field and a method (the field wins, as before for such names).

Two first versions were rejected by the corpus: the value of a parenthesized group carried at its closing
parenthesis (5 eec oracles worse: `(int) cache.get("a")`, the names after a primitive cast lost), and the packages
taken from every root of `findPotentialProjectsRoots` (`io`, `lang`, `util`, `awt`, ... offered as first names).
Both have a test now.

The 15 oracles lost by dataset v3, their 7 test classes run with the jar of Step 43 and of this step
(`dev_tokens.py`, `tmp/ab/dev-step44-v3lost.txt`): **14 recovered**, 0 worse on the 1,329 oracles of these classes
(jsoup 799 → 803 of 804, eec 359 → 361, plexus 127 → 130, esapi 21 → 25, cryptofs 8 → 9). Left: jsoup
`ps.removeIf(el2 -> el2.text().contains("Five"))`: the name of the lambda parameter (a new name), and then the members
of `el2`: the type of the parameter of a lambda written in the target is known only for the streams (`LAMBDA_PARAM`),
not for another method taking a functional interface (`Collection.removeIf(Predicate<? super E>)`).
