# Step 42 – The types that come from lambdas

**Date:** 2026-11-13
**Branch:** `22-test-processing-refactoring` (on top of Step 41)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/LambdaTypes.java` (new): the type of a parameter of a lambda without a
  declared type
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: the two resolutions of the types of the parameters
  in scope (`updateHashMapOfParameterReferencesFromIdentifiers`, the parameter branch of the token steps) go through
  `LambdaTypes.parameterType`; `resolveVariableType`: a `var` typed `void` is typed from its initializer
- `src/main/java/star/tracto/preprocessing/utils/VarTypes.java`: an initializer typed `void` is typed from its parts;
  the void candidates are left out; `fromLambdaArgument` (new)
- tests: `LambdaTypesTokensTest` (new, end-to-end)

## Problem

4 of the 6 corpus oracles still losing a step after Step 41, all in cryptofs:

| Shape | Oracles | Example |
|---|---|---|
| A. the parameter of a lambda without a declared type, whose body contains the target | 3 | `createEvent.ifPresent(e2 -> { assertEquals(1, e2.count()); })`: `e2.` ▸ `count`, `context` |
| B. a `var` initialized by a call of an overloaded method with a lambda | 1 | `var attr = Assertions.assertDoesNotThrow(() -> Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW_LINKS))`: `attr.` ▸ `isDirectory` |

Probed with the symbol solver alone (a scratch program, the JDK and JUnit 5.7.2):

A. The parameters in scope (Step 20) are recorded with their declared `Type`, an `UnknownType` for `e2 -> ...`, and
`UnknownType.resolve()` always throws: `e2` was offered, its members were not. `Parameter.resolve().getType()` infers
the type from the functional interface, but gives a `ResolvedLambdaConstraintType` for `Consumer<? super T>`
(described `? super java.nio.file.WatchEvent<?>`, neither a reference type nor a wildcard for the solver): no members.

B. The symbol solver resolves the call to `assertDoesNotThrow(Executable)` and types the variable `void`, without an
error, so `VarTypes` (Step 34, called on a failure only) was not tried. `VarTypes` would not have typed it either: the
candidates of one argument are the void `(Executable)` and `<T> T (ThrowingSupplier<T>)`, and a void or type-variable
return type gave nothing. Java chooses the method that returns a value (the body of the lambda is an expression of type
`BasicFileAttributes`), and a variable is never void.

## Fix

A. `LambdaTypes.parameterType`: the declared type, or, for a parameter of a lambda without one, the type inferred by
the symbol solver (`Parameter.resolve().getType()`) without its constraint or wildcard: the bound, `X` for
`? super X` and `? extends X` (JLS 15.27.3 and 9.9, the non-wildcard parameterization of the functional interface),
`Object` for `?`.

B. A value is never void:
- `resolveVariableType`: a `var` typed `void` by the symbol solver is typed from its initializer (`VarTypes`), as when
  it fails;
- `VarTypes.typeOf`: a part typed `void` is typed from its own parts;
- `VarTypes.returnType`: the void candidates are left out (the call is a value: an initializer, the scope of a call);
  a type parameter of the method returned by it (after the type parameters of the scope are replaced by their values)
  is the type of the body of an expression lambda passed as a functional interface whose method returns that type
  parameter (`FunctionalInterfaceLogic.getFunctionalMethod`). A block lambda, a method reference or any other argument:
  the type stays unknown, as before.

## Tests

`LambdaTypesTokensTest` (end-to-end; a project with `Checks.attempt(Action)` void and `<T> T attempt(Producer<T>)`, the
overloads of `assertDoesNotThrow`): `item.` ▸ `name`, `count` (`var item = Checks.attempt(() -> Store.make())`);
`it.` ▸ `name`, `count` in `found.ifPresent(it -> {...})` (`found` a `var`) and `first.ifPresent(it -> {...})`
(`Optional<Item> first`); the declared parameter `(Item it) -> {...}`, a control that passed before. Checked on
2026-11-13 against the code of Step 41: it fails with the three symptoms of the corpus. Mutations, each failing the
test: a `var` typed `void` accepted (in `resolveVariableType`, or in `VarTypes.typeOf`), the void candidates kept, no
inference from the lambda; the constraint type not unwrapped gave no members (seen in a debug run). A first version
inferred the type parameter before replacing the type parameters of the scope: `VarInitializerTokensTest`
(`names.iterator().next()`, `E` of `Iterator<E>`) failed, fixed by the order. 457 Java tests pass.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-13, against Step 41 (`tmp/ab/corpus-step41-out`,
`corpus-step42-out`): complete **1,296 → 1,300** (cryptofs +4: `e2.context` 2, `e2.count`, `attr.isDirectory`),
**0 worse**, 0 other oracle with other candidates.
- candidates: additions only, in 4 steps (after `e2.`: `context`, `count`, `kind` and the methods of `Object`; after
  `attr.`: the methods of `BasicFileAttributes`); 0 target tokens removed;
- contextual information: additions only, in the 4 oracles (16 steps: the class `WatchEvent` or `BasicFileAttributes`
  after the variable, its methods after the dot); the info type of 8 steps follows (`PARAMETER_RETURN` and
  `FIELD_RETURN` → `METHOD_CALL` and `METHOD_RETURN`, as in Step 40).

Left on the corpus: 2 oracles of steve, the getters generated by Lombok (`OcppProtocol`, `@Getter`): Step 43.
