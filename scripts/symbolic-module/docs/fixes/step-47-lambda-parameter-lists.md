# Step 47 – Lambdas with a parameter list: `(a, b) ->`, `(a) ->`, `(Item a, Special b) ->`

**Date:** 2026-11-17
**Branch:** `22-test-processing-refactoring` (on top of Step 46)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: the parameter list of a lambda argument (a frame
  opened by its `(`: `lambdaParameterList`, `lambdaParameterPosition`, `declaredLambdaParameters`), the types of the
  parameter of a functional interface at any position (`lambdaParameterTypes(stack, argumentFrame, position)`), the
  placeholder and the target (`lambdaArgumentExpected`, `namesALambdaParameter`), `final` in a parameter list, two
  names in a row in the partial oracle (`appendToken`)
- tests: `LambdaParameterListsTokensTest` (new, end-to-end), `AppendTokenTest`, `TokensFixture.targetAfter`

## Problem

Step 45 left the lambdas whose parameters are in parentheses: several parameters (`(a, b) ->`), a parenthesized one
(`(a) ->`), declared types (`(Item a, Special b) ->`, `(final Item a, ...)`, `(var a, ...)`). Measured before Step 46:
none in the 9,148 oracles of dataset v3 nor in the 117,824 one-line assertions of the nine repositories, so they were
first left as a known limit. The author asked for them (2026-11-17): the model must be able to write such a lambda in
an oracle, a new name for each parameter, then the members of its type, on condition that it can be tested.

With Step 46: after `removeIf((` no placeholder (the `(` opened a frame that is not the argument), so the name was a
missing target token; the names were not known in the body (`node.` ▸ nothing); a declared type and its name were
printed without a space (`(Itema,`), which the grammar reads as one name: the next tokens were wrong and the oracle
could be dropped (the jsoup probe below); after `final`, no class name.

## Fix

- **The parameter list.** A `(` that starts an argument of a call (`name((`, `name(x, (`), where a method of that name
  takes a functional interface with a parameter (Step 45), opens a frame marked as a possible parameter list; a comma
  moves to the next parameter (position + 1, the names declared so far are kept); any other operator makes it a
  parenthesized expression (the new frame is not marked: `(a, b)` cannot be an expression, `(a)` can, so both stay
  possible until a comma or an operator).
- **The names.** A name of the list that matches nothing is a parameter: its type is the parameter of the functional
  interface at its position (the same resolution as Step 45 at any position: `BiPredicate<Item, Special>` gives `Item`
  then `Special`; `Map<String, Item>.forEach` gives `String` then `Item`; an overload whose interface has no parameter
  there gives nothing: `File.listFiles(FileFilter)` for the second name). A declared type gives the same type (Java
  requires the type of the interface), so it is not resolved. At the closing parenthesis the names go to the frame of
  the argument, and `->` puts them in the scope of the body (Step 45).
- **The placeholder.** `LAMBDA_PARAM` is a candidate where a name of the list can start (after `(` or a comma of the
  list) or after its type (a class name, a primitive, `var`; `final` then a type), and not after a name already
  declared (`(a` ▸ no placeholder). The target is `LAMBDA_PARAM:<name>` when the name is not a candidate, a comma or
  the closing parenthesis follows it, and `->` follows the list.
- **`final`** in a parameter list keeps the classes (a type follows); elsewhere unchanged.
- **Two names in a row** in the partial oracle are separated by a space (`(Item a`, also `x instanceof Item a`): none
  in the oracles of v3 (0 targets with two adjacent names that are not keywords), so no existing datapoint changes.

Not covered: a parameter with a generic or array type (`(List<Item> a) ->`: `<` and `[` are frames); a name that is
also the name of a field or a class (it is read as that name, as in Step 45); the constructors taking a lambda.

## Tests

`LambdaParameterListsTokensTest` (end-to-end): `Checks.both(item, special, (a, b) -> ...)` with
`BiPredicate<Item, Special>`: `(` ▸ `LAMBDA_PARAM` and the target `LAMBDA_PARAM:a`, `(a` ▸ no placeholder, `(a,` ▸
`LAMBDA_PARAM:b`, `(a,b)->` ▸ `a`, `b`, `a.` ▸ `count` not `special`, `b.` ▸ `special`; not after `Checks.both(` (an
`Item`); declared types: `(` ▸ `IDENTIFIER:Item`, `(Item` ▸ `LAMBDA_PARAM:a`, `(Item a,Special` ▸ `LAMBDA_PARAM:b`,
`b.` ▸ `special`; `final`: `(final Item a,final Special` ▸ `LAMBDA_PARAM:b`, `a.` ▸ `count`; `var`: `(var` ▸
`LAMBDA_PARAM:a`, `b.` ▸ `special`; `items.removeIf((` ▸ `LAMBDA_PARAM:it`, `it.` ▸ `count`, `name`; the JDK:
`byName.forEach((k,` ▸ `LAMBDA_PARAM:v`, `v.` ▸ `count` (`Map<String, Item>`); `list.sort((x,y)->x.count()-y.` ▸
`count` (`Comparator<? super E>`); a stream: `reduce((a,` ▸ `LAMBDA_PARAM:b`, the body ▸ `a`, `b`; a parenthesized
expression, `assertEquals(3,(` ▸ no placeholder, `IDENTIFIER:size`. `AppendTokenTest`: two names in a row.

Against the code of Step 46: both tests fail. Each part checked by a mutation (14; 13 fail the tests): the parameter
list flag, the comma (the position, the flag), the names given at the closing parenthesis, the name recorded, the
placeholder in a list, the target, the spacing, `final`, the position of the parameter of the interface, the name
after a type, not after a name, `final` then a type. The 14th, the explicit check of the arity of the functional
method, does not fail: without it, `getParamType` past the last parameter throws and the exception is caught (the same
empty result); kept to avoid a warning per position.

467 Java tests pass.

## Verification

**Corpus** (`tmp/ab/corpus-check-step47.txt`): IDENTICAL to the expected results of Step 46 (1,302 complete): no
oracle of the corpus has these shapes, and nothing else changed.

**Real code** (`tmp/ab/dev-step47-probe.txt`): the shapes are not in the assertions of the nine repositories, but they
are in their other statements (jsoup `div.traverse((node, depth) -> ...)`, eec
`listFiles((dir, name) -> name.endsWith(".xlsx"))`, cryptofs `Map.compute`). A probe run (`tmp/ab/s47probe`, built
by `tools/ab/probe_s47.py`): real test prefixes of v3 (their split test classes, the repositories with their
dependencies) with the target replaced by an assertion holding such a lambda, run with `dev_tokens.py` and the jars of
Steps 46 and 47:

| Probe | Step 46 | Step 47 |
|---|---|---|
| jsoup `testTraverseLambda_split_2`: `assertSame(div, div.traverse((node, depth) -> node.attr("id")));` (`NodeVisitor`, a functional interface of the project, `head(Node, int)`) | loses steps (`node.` ▸ nothing) | complete: `LAMBDA_PARAM:node`, `LAMBDA_PARAM:depth`, the body ▸ `node`, `depth`, `node.` ▸ `attr` (77 members) |
| jsoup `testTraverseLambda_split_1`: the same with `(Node node, int depth)` | dropped (`(Nodenode,`) | complete: `IDENTIFIER:Node`, `LAMBDA_PARAM:node` (the only candidate after `(Node`), `INT:int`, `LAMBDA_PARAM:depth`, `node.` ▸ `attr` |
| eec `testReader_split_0`: `assertNotNull(testResourceRoot().toFile().listFiles((dir, name) -> name.endsWith(".xlsx")));` (`File.listFiles(FilenameFilter)` and `listFiles(FileFilter)`) | loses steps (`dir`, `name`) | complete: `LAMBDA_PARAM:dir`, `LAMBDA_PARAM:name`, `name.` ▸ `endsWith` (`String`, from `FilenameFilter.accept(File, String)`) |
