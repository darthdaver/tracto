# Step 45 – Lambdas as arguments of any call, values created with type arguments

**Date:** 2026-11-16
**Branch:** `22-test-processing-refactoring` (on top of Step 44)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: the frame of an argument knows its call
  (`callName`, `argumentIndex`); the parameter of a lambda written as an argument (`lambdaParameterTypes`, the
  `LAMBDA_PARAM` candidate), the parameters of the lambdas in scope in their whole body (`lambdaScope`); the type
  arguments of the class created by `new` (`typeArguments`, a frame opened by `<` and closed by its `>`)
- tests: `LambdaArgumentsTokensTest` (new, end-to-end)

## Problem

Left by Step 44 (`docs/fixes/step-44-value-shapes.md`):

- **A lambda written in the target as the argument of a method that is not a method of a stream**, jsoup
  `assertFalse(ps.removeIf(el2 -> el2.text().contains("Five")))` (dataset v3: the last lost oracle): the parameter
  name was not expected (the placeholder `LAMBDA_PARAM` was offered only after the `(` of a stream method) and its
  type was unknown, so its members were missing (`el2.` ▸ `text`). The type of a lambda parameter came only from the
  type parameter `T` of `Stream`; the parameters of the lambdas were also forgotten after an operator of the body
  (`it -> it.a() > 0 && it.` ▸ ?) and in the arguments of a call of the body.
- **A value created with type arguments**, `new ArrayList<String>().` ▸ `size`, `new Box<>(x).` ▸ `get`: the `<` is an
  operator for the frames (it resets the frame), so the class created was lost; after `new X<T>(`, the parenthesis
  opened a "parenthesized expression" (the token before is not a name), whose value (the argument) Step 44 would then
  carry.

## Fix

- **The argument of a call.** A frame opened by the `(` of a call (`name(`) records the name of the method
  (`callName`); a comma moves to the next argument (`argumentIndex`), an operator or a lambda arrow keeps the argument.
- **The parameter of a lambda argument.** For each method of that name in the frame of the call (its method
  references), the parameter at the position of the argument (the last one if variable arity): if it is a functional
  interface (`FunctionalInterfaceLogic.getFunctionalMethod`) whose method has parameters, the type of the first one,
  with the type arguments of the parameter applied to the method of the interface (`test(T)` of `Predicate<Item>` →
  `Item`; JavaParser gives the method as declared; a type parameter of the interface is replaced by the type
  argument at its position, `typeArgumentApplied`: JavaParser's `useThisTypeParametersOnTheGivenType` gives the
  type parameter back when the type argument is a type variable of the same name, `Predicate<? super T>` in jsoup's
  `Nodes<T>`, seen on the last lost oracle of v3), the bound of a wildcard (`? super E` → `E`), and the values of a
  type variable recorded for the receiver of the method (`E` of `ArrayList<Item>` for `items.removeIf(`: the values
  known when the method name was read, `receiverTypeParameters`, since the method replaces them). Where an argument
  starts and such a type exists, `LAMBDA_PARAM` is a candidate (at generation and at inference, as for the streams);
  the target is `LAMBDA_PARAM:<name>` when the name is not a candidate and `->` follows. The name then gets these
  types (the lambda references, as for the streams), which become names of the body after `->`.
- **The scope of the lambda parameters.** The parameters of a lambda are names of its whole body: they are kept by
  the frames that follow an operator of the body and by the frames of the calls of the body (`lambdaScope`), until
  the comma that ends the argument (the scope of the enclosing frame). Before, only the first operand of the body
  knew them.
- **Type arguments after `new`.** After `new X` (Step 44, `afterNew`), a `<` opens a frame of type arguments (the
  classes and the first package names are offered), with its depth for nested ones (`HashMap<String, List<Item>>`;
  the lexer gives `>>` as two `>`); a comma, `?`, `extends`, `super`, `&` start another type; its `>` closes it: the
  frame of the class created is back, with its constructors and the members of the value. The `(` that follows is the
  one of the arguments of the constructor, not a parenthesized expression.

Not covered: lambdas with several parameters (`(a, b) ->`) and with a parenthesized parameter (`(a) ->`), the
constructors taking a lambda (`new Thread(() -> ...)` has no parameter), generic method calls with explicit type
arguments (`Collections.<String>emptyList()`).

## Tests

`LambdaArgumentsTokensTest` (end-to-end): `items.removeIf(` ▸ `LAMBDA_PARAM` and `it->it.` ▸ `count`, `name`
(`Items extends ArrayList<Item>`); `specials.removeIf(s->s.` ▸ `special`, `count` (`Specials extends Group<Special>`,
`Group<T extends Item>` overrides `removeIf(Predicate<? super T>)`, as jsoup's `Elements` and `Nodes<T>`); `list.removeIf(x->x.` ▸ `count`, `name` (`List<Item>`); `Checks.matches(item,`
▸ `LAMBDA_PARAM` (the second parameter, `Predicate<Item>`), not after `Checks.matches(` (an `Item`), `i->i.` ▸
`count`; the scope: `it->it.count()>5&&` ▸ `it`, `&&Checks.matches(` ▸ `it`, `j->j.` ▸ `count`, `j->j.count()<` ▸
`it`, `j`; no `LAMBDA_PARAM` after `assertTrue(` nor in a comparison (`size<3&&size>`); `new ArrayList<String>().` ▸
`size`, `isEmpty`; `new Box<>(item).` ▸ `get`; `new HashMap<String,List<Item>>().` ▸ `isEmpty`, `keySet`; the type
arguments `new HashMap<String,` ▸ `List`, `Item`. Against the code of Step 44: 13 target tokens missing. Each part
checked by a mutation (11, each fails the test): the substitution by position, the call name, the argument index, the type arguments of the
interface, the type variables of the receiver, the scope after an operator, the scope in a call of the body, the
frame of type arguments, its depth, the constructor arguments not parenthesized, the type of the parameter.

464 Java tests pass.

## Verification

Regression corpus, 2026-11-16, against Step 44 (`tmp/ab/corpus-step44-out`, `corpus-step45-out`): **1,302 of 1,302
complete, 0 worse**, accepted. Candidates: `LAMBDA_PARAM` added in 23 steps of 8 oracles, all where the method has a
functional parameter at that position (`toArray(IntFunction)` of a collection and of a stream, Mockito 5
`mock(Answer, T...)`, `newDirectoryStream(path, DirectoryStream.Filter)`, `Stream.map(` where the target was a
method reference, not a parameter: the stream rule offers `LAMBDA_PARAM` only when the target is not a candidate);
nothing else changed (no target label, no contextual information). The substitution by position, added after this
check, leaves the corpus identical.

The last oracle lost by dataset v3, jsoup `ElementsTest` run with the jar of Step 44 and of this step
(`dev_tokens.py`, `tmp/ab/dev-step45-elements.txt`): **198 of 198 complete** (Step 44: 197), 0 worse. With Step 44,
**the 15 oracles lost by dataset v3 are recovered**.
