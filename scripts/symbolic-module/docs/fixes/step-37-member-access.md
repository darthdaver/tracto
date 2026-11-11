# Step 37 – Only the members the test class can access are offered

**Date:** 2026-11-11
**Branch:** `22-test-processing-refactoring` (on top of Step 36)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/MemberAccess.java` (new): which members of a type the test class can
  access
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: the members after a value
  (`collectMembersOfTypes`, `processReturnClass`), after the name of a class and after `new X` (the identifiers of the
  classes), after `X::` (new case `COLONCOLON`); `collectMethodUsages`, `collectResolvedMethodDeclaration`,
  `updateHashMapOfMethodUsageIdentifiers`, `updateHashMapOfFieldTypeIdentifiers` take a `MemberAccess` instead of lists
  of rules on the modifiers
- tests: `MemberAccessTokensTest` (new, end-to-end); `IdentifiersOrderTest` (`MemberAccess.ANY` for the members of the
  test class)

Decided by the author on 2026-11-11 (defects 2 and 3 of step 9, before the dataset v2): the private members of the JDK
classes offered after a value of that type; the instance and protected methods offered after the name of a class. One
cause, one step.

## The cause

The members of a type were filtered with lists of rules on their modifiers, a member passing when it satisfies one
rule (`JavaParserUtils.checkModifiersRules`, OR between the rules):
- after a value: "public" OR "not private" OR "not protected" (plus "protected" when the package of the test class
  `contains` the package of the type). A private member is not protected: every member passed. After a `String`:
  `value`, `coder`, `isLatin1`, `isMalformed3`, `COMPACT_STRINGS`; after any object: `wait0`, `registerNatives`,
  `finalize`, `clone`; after a jsoup `Element` in `org.jsoup.parser`: the package-private methods of `Node`;
- after the name of a class: "static and public" OR "not private" (plus "static and protected" in the same package).
  Every member that is not private passed: after `String.`, `length`, `isEmpty`, `equals`, `getClass`;
- the package was compared with `String.contains` on the qualified name of the type without its simple name: a
  subpackage was the same package, a nested type (`a.b.Outer.Inner`) was in package `a.b.Outer`.

## Fix

`MemberAccess`, the access rule of Java from the test class, on the class that declares each member (an inherited
member is checked on its own class):
- a public member always; the members of an interface or of an annotation are public, the fields of an interface
  static;
- a member that is not private when the class that declares it is in the package of the test class (equal names);
- any member of a class nested in the test class (private ones too: the same top-level class);
- after the name of a class (`X.`), the static members only;
- after `new X`, the members of the value created (offered after `new X().`); the result of a call, a field, a cast
  (`processReturnClass`): the members of a value;
- after `X::` (a method reference on a class, new): the static and the instance methods the test class can access (the
  receiver of an instance method is the first argument), not the fields. Before, `::` changed nothing: the members
  collected after `X` stayed, the instance methods among them only because "not private" let them through.

A protected member of a class of another package is not offered after a value: it is accessible from a subclass,
through a value of that subclass only; the members of the test class and of its superclasses are collected apart (Step
22, unchanged: `MemberAccess.ANY`).

## Tests

`MemberAccessTokensTest` (end-to-end): a class of the package of the test class (`box.` ▸ `size`, `count`, `peek`,
`label`, not `secret`, `hidden`), of another package (`remote.` ▸ `ping` only), an interface of another package
(`shape.` ▸ `area`; `Shape.` ▸ `SIDES`, not `area`), class names (`Box.` ▸ `create` only; `Remote.` ▸ `make`, not the
package-private `helper`), the JDK (`text.` ▸ `length`, `isEmpty`, `hashCode`, not `value`, `coder`, `isLatin1`,
`COMPACT_STRINGS`, `clone`, `finalize`; `String.` ▸ `valueOf`, `format`, `CASE_INSENSITIVE_ORDER`, not `length`,
`clone`, `hashCode`), a class nested in the test class (`helper.` ▸ its private field `x`), a nested class of the
package (`inner.` ▸ `depth`), the result of a constructor (`new Box().` ▸ `size`, `count`, not `secret`), the result of
a call (`Box.create().`), a method reference (`Box::` ▸ `size`, `count`, `create`, not `label`). Checked on 2026-11-11:
against the code of Step 36 the test fails (`secret` offered after `box.`); without the rule for the classes nested in
the test class it fails (`helper.x`). All 445 tests pass.

With `processReturnClass` given the access of a class name, the test still passes: the candidates after a value come
from `collectMembersOfTypes`; `processReturnClass` fills the references (the contextual information). It is given the
access of a value, which is what all its callers are.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-11: statuses unchanged (complete 1,209, loses a
step 90, dropped 3), **0 worse**.

A first version (the access of a class name after `new X` and in `processReturnClass`, no case for `::`) made 5 oracles
worse: `new LongFileNameProvider(readonlyFlag).isDeflated` (cryptofs 2), `new StringBuilder().appendCodePoint(first)`
(esapi 2), `map(NewsAttributes.NewsGenre::toString)` (crawler 1). The test has the three shapes now.

Compared step by step with the outputs of Step 36 (`tmp/ab/corpus-step36-out`, `corpus-step37-out`):
- candidates: 1,188 steps lose candidates, none gains one, and no target token is removed. 1,618 distinct names: after
  the name of a class, the instance methods (`getClass` 426 steps, `equals` 413, `hashCode` 403, `toString` 401,
  `wait`, `notify`, ...); after a value, the private and package-private members of the JDK and of the projects
  (`finalize` 1,147, `clone` 971, `registerNatives` 579, `serialVersionUID` 173, `wait0` 135, `readObject`, the internals
  of `String`, `ArrayList`, `URL`, `ByteBuffer`, `Method`, `Date`). Checked one by one for the names that could be
  public or of the same package: jsoup `xEl.indent`, `childElementsList` (package-private in `org.jsoup.nodes`, the test
  in `org.jsoup.parser`), crawler `rules.getPath` (private), eec `hot.data` and plexus `cmd.position` (private fields of
  classes nested in the type), `new StringBuilder().getValue` (package-private in `java.lang`), ffmpeg `job.state`
  (protected, another package), `ByteBuffer.compare` (private);
- contextual information (2,178 steps): the same members removed from `invokedMethods` (2,155) and `fields` (188); 8
  additions, the private field `value` of `ValueHolder`, nested in the test class (plexus
  `ReflectionValueExtractorTest`, after `new ValueHolder`); 16 steps whose type changes from `FIELD_CALL` to
  `METHOD_CALL`: a private field and a public method with the same name (`attrs.permissions()`, `ffmpeg.filters()`,
  `xEl.tag()`, `echo.connection()`), the name was taken for the field, now for the method the target calls.

## Left

- After a value, the fields of the classes nested in its type are collected too (`toAst().findAll(FieldDeclaration)`):
  the private ones are no longer offered, a public field of a nested class would be; the fields inherited from the
  superclasses are not collected.
- No member is offered after a string literal (`"a".length()`): the literals reset the state (`TokensUtils`, `TODO`).
- From step 9: `@ParameterizedTest` methods offered, the classes of two JDKs, the imports of the focal class, the text
  replacement of `Assert.`, the comments of the arguments. The defect "the protected-member filter compares package
  names with `String.contains`" was this filter: no such comparison is left.
