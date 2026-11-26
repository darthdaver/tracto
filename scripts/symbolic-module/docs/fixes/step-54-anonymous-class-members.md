# Step 54 – The members of an anonymous class are offered after a `var` of that class

**Date:** 2026-11-25
**Branch:** `22-test-processing-refactoring` (on top of Step 53)
**Files changed:**
- `src/main/java/star/tracto/utils/javaParser/NamedAnonymousClassDeclaration.java` (new): the declaration of an anonymous
  class named after its supertype, with the naming rule of Step 53 (`nameOf`, moved from `TestUtils`)
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `resolveVariableType`, `anonymousClassType` (new),
  `declaredFields`, `collectMembersOfTypes`
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java`: `processObjectCreationExpr` uses `nameOf`
- tests: `AnonymousClassMembersTokensTest` (new, end-to-end), `NamedAnonymousClassDeclarationTest` (new)

## Problem

Found by the test of Step 52 (`AnonymousClassNamesTokensTest`):

```java
var counter = new Object() {
    int count = 1;
    int twice() { return 2 * count; }
};
assertEquals(2, counter.twice());   // counter. ▸ getClass, hashCode, equals, toString: no `twice`
```

A lost target ("Unexpected token: twice"), and `count` likewise. The cause is JavaParser (3.28.2), not Step 50 as first
guessed: it types `new Object() { … }` as `Object`, the type written after `new`, so a `var` initialized with it is an
`Object` (JavaParser cannot resolve `counter.twice()` either: "unable to find the method declaration"). Only `var`
gives a variable the type of an anonymous class: with a declared type (`Listener listener = new Listener() { … }`) Java
allows the members of the declared type only, which were already offered. 0 of the 117 anonymous classes of the test
sources of the nine projects are assigned to a `var`, and no lost oracle of dataset v4 or of the corpus has the shape;
fixed before the large run, as decided by the author on 2026-11-25 (a lost target token at inference).

## Fix

- `resolveVariableType`: a variable declared with `var` whose initializer creates an anonymous class (an
  `ObjectCreationExpr` with a body) has the type of the anonymous class, a `NamedAnonymousClassDeclaration`: JavaParser's
  `JavaParserAnonymousClassDeclaration` (its methods are the ones of the body and the inherited ones), with the name of
  Step 53 instead of `Anonymous-<UUID.randomUUID()>` (`Anonymous-java.lang.Object`; the qualified name
  `com.acme.CounterTest.Anonymous-java.lang.Object`), so that no random name can reach a dataset or an order. When the
  supertype cannot be resolved (a missing dependency), the type of JavaParser, as before (the name is computed inside the
  check, so a declaration whose supertype fails is never returned).
- `declaredFields`: for an anonymous class (its node is the creation), the fields of its body, its direct members only
  (Step 50: not those of a class nested in the body).
- `collectMembersOfTypes`: an anonymous class has no file, so the class of the context (`returnClasses`, which gives the
  methods and constructors of the context of the next step) is its supertype, as when JavaParser typed the variable with
  it: the context after `counter.` stays the methods and the constructor of `Object`.
- `TestUtils.processObjectCreationExpr` (Steps 52–53) takes the name from `NamedAnonymousClassDeclaration.nameOf`: one
  rule for both stages (the oracles datasets identical).

Effect on a datapoint (`AnonymousClassNamesTokensTest`'s project, Step 53 against Step 54, every step compared): the
candidates after `counter.` gain `twice` and `count`; the context of the step after `counter.twice` becomes
`METHOD_CALL` with `int twice()` (before: `FIELD_RETURN`, empty), after `counter.twice()` `METHOD_RETURN`, after
`counter.count` `FIELD_CALL`; nothing else.

Not covered (no variable, or nothing to resolve): a member reached directly on the creation
(`new Object() { int x; }.x`); an anonymous class of a type that cannot be resolved (its body members could be offered
without the supertype; not done).

JavaParser still builds its own anonymous declarations for the methods of the body (the declaring type of `twice`), with
random names: they appear in a log line only ("Declaring type path not found for while processing: …Anonymous-<uuid>");
the end-to-end test of Step 52 checks that no file of the output contains one, and that two runs write the same bytes.

## Tests

- `AnonymousClassMembersTokensTest` (end-to-end): a test class with `var`s of anonymous classes of `Object` (a method, a
  field, a class nested in the body) and of an interface (a field, a default method of the interface), a variable of
  an anonymous class declared with the interface, a plain `var` of a class and of a generic class, and a `var` of an
  anonymous class of a type that cannot be resolved:
  - `theMembersOfTheAnonymousClassAreOfferedAfterAVarOfIt`: `counter.` ▸ `twice`, `count`, `toString`, `hashCode`, not
    `hidden` (the field of the nested class); `listener.` ▸ `calls`, `name` (not the void `on`, not offered as an
    argument since Step 39); `plain.` ▸ `total`, `next`; `names.get(0).` ▸ `length`; the only lost target is the one
    of the unresolvable type, as before;
  - `theContextAfterTheVariableIsTheOneOfTheSupertype`: the context after `counter.` has the methods
    (`public String toString()`, `public native int hashCode()`) and the constructor (`public Object()`) of `Object`;
  - `aVariableDeclaredWithTheSupertypeHasTheMembersOfTheSupertypeOnly`: `Listener listener = new Listener() { int calls; … }`
    ▸ `name`, not `calls` (Java does not compile `listener.calls`).
- `NamedAnonymousClassDeclarationTest`: `Anonymous-java.lang.Object` (qualified `com.acme.T.Anonymous-java.lang.Object`),
  the same name for two instances, `Anonymous-java.lang.Runnable`, `Anonymous-Local` (a local interface).

Against the code of Step 53: the first test fails (`twice`, `count`, `calls` lost: "Unexpected token"); the second and
third pass (the context and the declared type were right before: they guard the fix). With the fix all pass, and
the tests of Step 52 (no random name in the output, two runs byte-identical) with the `var` of its project.

The context test was written after a first version of the fix, which emptied that context (no file for the anonymous
class): it failed on it, and passes with the supertype as the class of the context.

Each part checked by a mutation: the branch of `var` disabled (the first test), applied to declared types too (the third),
without the check of a body (the generic `var`: `length` lost), the fields of the body dropped and all the fields below
the creation (`hidden` offered) (the first), the context of the anonymous type (the second), the random name of
JavaParser (`NamedAnonymousClassDeclarationTest`): each caught. Not caught: the fallback to the type of JavaParser
removed. On the unresolvable type both paths fail with the same messages (the constructor of JavaParser resolves the
supertype and throws; the caller records "Unable to process field thing"). It is kept for a supertype that the
anonymous declaration (JavaParser's `ObjectCreationContext`) cannot resolve while JavaParser's typing of the variable can;
no such case was found.

## Verification

486 Java tests pass (`mvn clean package`; jar `tmp/ab/jar-step54.jar`).

**Corpus** (`tmp/ab/corpus-prepare-step54.txt`, `tmp/ab/corpus-check-step54.txt`; 122 s + 323 s): **identical** to the
expected results (1,302 complete); no accept. Compared file by file with the outputs of Step 53 (`tmp/ab/step54-before`):
both stages byte-identical (only the logs of the tokens stage differ: times). No corpus class has a `var` of an
anonymous class, and the move of the naming rule left the oracles stage unchanged (no identity-hash effect this time,
see Step 53).
