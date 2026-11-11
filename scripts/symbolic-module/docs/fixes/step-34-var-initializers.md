# Step 34 – The type of a variable declared with `var` whose initializer cannot be typed as a whole

**Date:** 2026-11-11
**Branch:** `22-test-processing-refactoring` (on top of Step 33)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/VarTypes.java` (new): the type of a `var` from the parts of its
  initializer
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `resolveVariableType` (new), used where the type of
  a variable token is resolved
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java`: `getRepoTypeSolver` (new), to resolve a class name
  in the context of a node
- tests: `VarInitializerTokensTest` (new, end-to-end)

## The cause: not the lambdas

The next shape left by Step 33 was the body of a lambda, `assertThrows(X.class, () -> spy.` (cryptofs, about 26 missing
steps in v1). A probe with a field, a class name, a typed local and a `var` local in the body of a lambda showed the
members offered in all four cases: the lambdas are not the cause. Of these steps, the class names (`VaultConfig.load`,
`CryptoFileSystemProperties.wrap`) were already fixed by Step 33 (the lists were measured with the Step 32 jar); every
other one has a variable declared with `var`.

For `var x = initializer`, the symbol solver types the whole initializer, and fails when one part needs a type it cannot
resolve: an argument of a type of a dependency (`VaultConfig.createNew().cipherCombo(CryptorProvider.Scheme.X).build()`,
`VaultConfig.load("...", masterkeyLoader, 42)`), an unqualified call it also looks for in the static imports of the
dependencies (`import static org.mockito.Mockito.*`). The variable was recorded as unsolvable (`Unable to process field
config`), and the class names of the root were offered after `config.`. In dataset v1, 32 missing steps have a `var`
receiver, all in cryptofs (the only project using `var` in its tests).

## Fix

When the symbol solver cannot type the initializer of a `var`, its type is determined from the parts of the
initializer, only when it is not ambiguous (`VarTypes`):
- each part is first typed by the symbol solver; when it fails:
- `new T(...)`, `(T) e`, `(e)`;
- a variable declared with `var` used in the initializer: its own initializer, in turn (`var first =
  names.iterator().next()`; the declaration of a local variable is a `VariableDeclarationExpr`, its declarator is
  looked up by name);
- the name of a class (the scope of a static call), resolved in the context of the expression;
- a call `e.m(...)` or `m(...)`: the methods named `m` with as many parameters as arguments (or variadic), declared in
  the type of `e` and its ancestors; for an unqualified call, in the enclosing types from the innermost, then in the
  static imports (single imports first, then on demand; a type that cannot be resolved is left out). The declared
  methods are read without their parameter types, so that a method whose parameters cannot be resolved is not taken
  for absent. Their return types, with the type parameters of the type of `e` replaced by their values, must be the
  same; `void`, a type variable (a type parameter of the method, inferred from the arguments: `spy(x)`) or a return
  type that cannot be resolved give nothing.

Otherwise nothing: the variable stays unsolvable, as before. This does not decide step 8: `var x = spy(y)` (Mockito)
and `var r = Assertions.assertDoesNotThrow(() -> ...)` (JUnit) need the signatures of the dependencies.

A first version took the candidates from the resolvable methods of Step 33: the methods whose parameters cannot be
resolved were not seen, so the overloads `pick(Element)` (returns `Config`) and `pick(String)` (returns `Builder`) gave
`Builder` for `pick(element)`, a wrong type; and `load(String, Element)` was not found at all. The test has a case for
the ambiguity.

## Tests

`VarInitializerTokensTest` (end-to-end; `org.dom4j.Element` and Mockito not on the classpath): `var config =
Config.load("a", element)` ▸ `getId`; `Config.createNew().root(element).build()` ▸ `getId`; the same in the body of a
lambda, `() -> config.` ▸ `check`; `var config = newConfig("a")` (a method of the test class, with a static import of
Mockito) ▸ `getId`; `var builder = createNew().root(element)` (a static import) ▸ `build`; `var first =
names.iterator().next()` (a `var` in the initializer, `List<String>`) ▸ `trim`; `var picked = Config.pick(element)`
(overloads with different return types) ▸ not `getId`. Checked on 2026-11-11: against the code of Step 33 the test
fails (`getId`, `check`, `build`, `trim` missing; the case of the test class method passed already). All 442 tests pass.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-11:

| | complete | loses a step | dropped |
|---|---|---|---|
| Step 33 | 1,199 | 100 | 3 |
| Step 34 | 1,209 | 90 | 3 |

**0 worse.** 10 oracles complete, all cryptofs: `ChunkLoaderTest` 6 (`var data = inTest.load(...)`: `data.remaining()`,
`data.capacity()`, `ByteBuffer`), `CryptoFileSystemModuleTest` 4 (`result.isEmpty()`, `result.isPresent()`,
`result.get().length()`: `Optional<String>`, then `String`; `decoratedConsumer.accept(event)` in a lambda).

Compared step by step with the outputs of Step 33 (`tmp/ab/corpus-step33-out`, `corpus-step34-out`): only the candidates
and the contextual information differ, in these oracles and in 2 assertions `assertThat(data, ...)` of the same tests
(the contextual information of `data`).
- Candidates (11 steps): only additions, the members of the type after the variable (`ByteBuffer`, `Optional`,
  `Consumer`, `String` after `result.get().`), with the private and static members of the JDK classes as after any other
  value of these types (the known defects of step 9).
- Contextual information (47 steps): the declaration of the variable's type, the invoked methods.
- One step whose output differs without a cause in this step: the contextual information of a record accessor
  (`stepParentDir.path()`, cryptofs `OrphanContentDirTest`) holds a body printed from the object
  (`{declaration=...ImplicitGetterMethod@1ab268bd, ...}`), whose identity hash changes from one run to another. The body
  of a method without a source is taken from the text of the `MethodUsage` (`JavaParserUtils.getBodyFromMethodUsage`),
  even when its signature is not in it; the same garbage, deterministic, for the JDK methods read by reflection
  (`Enum.compareTo`, about 100 steps). Step 35.

The cryptofs classes with a `var` receiver (`dev_tokens.py`, Step 33 jar against Step 34 jar, oracles of the v1 run;
`CryptoFileSystemPropertiesTest`, `OpenCryptoFileTest`, `VaultConfigTest`, `RealInUseManagerTest`,
`C9rConflictResolverTest`, `OrphanContentDirTest`, `CryptoFileSystemModuleTest`, `ChunkLoaderTest`; 121 oracles): 69 →
86 complete (+17), 0 worse (`VaultConfigTest` `config.` 5, `CryptoFileSystemPropertiesTest` `entryPropertySet.`,
`properties.`, `randomProperty.`, the ones above). Still missing near a `var`: `spy(...)` (`inUseSpy`, `openCryptoFile`,
`interruptedSpy`, `continuedSpy`, `conflictResolverSpy`) and `assertDoesNotThrow(...)` (`useInfo`): step 8.

The first corpus check of this step lost its six JVMs to the RAM watchdog: the page cache of days of runs (110 GiB of
`active_file`) was counted as used. The watchdog (outside the repository) now leaves out all the clean page cache.

## Left

From the Step 33 list: arrays returned through a type variable (`strings.get(0).length`, eec 14), a field of another
class (`structEval.threadMemo`, jsoup 12), the methods of `Object` after a value of an interface type
(`data.get(0).toString()`), parenthesized expressions (`(new CipherSpec()).`, esapi 7); the body of the methods without a
source (Step 35); dependencies: step 8.
