# Step 50 – The fields of the classes nested in a type are not candidates after a value

**Date:** 2026-11-18
**Branch:** `22-test-processing-refactoring` (on top of Step 49 and dataset v4)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `declaredFields(Node)`, used for the fields of the
  type of a value and for those of its supertypes
- tests: `NestedClassFieldsTokensTest` (new, end-to-end)

## Problem

After a value (`x.`), the fields of its type were taken with `findAll(FieldDeclaration.class)` on the declaration of
the type: every field declaration below it, so also those of the classes nested in it (static, inner, local,
anonymous). The scope check (`checkNodeScope(field, TypeDeclaration.class, 0)`) accepts them: the nearest type
declaration of such a field is its own nested class. Java does not let a value reach them (`doc.validChildren`, where
`validChildren` is a field of `Element.NodeList`, does not compile). A known defect since Step 37 (`docs/resume.md`,
section 6, item 10), extended by Step 44 to the supertypes when it added the inherited fields. Found again in dataset
v4: `Throwable.SentinelHolder.STACK_TRACE_SENTINEL` (a private nested class of the JDK) after every exception value
(61 steps), jsoup `Element.NodeList.validChildren` after `doc.` (289), the fields of the classes nested in `Evaluator`
(`a`, `b`, `index`, `key`, `pattern`, `value`), the `i` of the anonymous iterator of `Path.iterator()` in cryptofs. Noise
only: never a lost target.

## Fix

`declaredFields(node)`: the fields of the type declaration itself (`TypeDeclaration.getFields()`, its direct members),
an empty list when the node is not a type declaration. Used for the type of the value and for each supertype (Step 44),
as `TestClassScope.inheritedFields` already does for the test class. A value whose type is the nested class still has
its fields (`list.` ▸ `validChildren` for a `Node.NodeList`).

## Tests

`NestedClassFieldsTokensTest` (end-to-end): `Element extends Node`; `Node` has a field, a static field, a public
static nested class (`NodeList.validChildren`), a private nested class (`Holder.SENTINEL`), an anonymous class
(`cursor`, package-private) and a local class (`counted`); `Element` a field and a nested class (`Cache.hits`).
`element.` ▸ `tag`, `depth`, `EmptyString`, not `hits`, `validChildren`, `SENTINEL`, `cursor`, `counted`; `node.` ▸
`depth`, `EmptyString`, not the nested ones; `list.` (a `Node.NodeList`) ▸ `validChildren`; the JDK:
`exception.` (an `IllegalStateException`) ▸ `getMessage`, not `STACK_TRACE_SENTINEL`, `STACK_TRACE_ELEMENT_SENTINEL`.

Against the code of Step 49: fails (9 wrong candidates, `cursor` too once package-private). Each part checked by a
mutation, both fail the test: the type of the value (`hits`, then `validChildren`, `SENTINEL`, `cursor`, `counted` after
`node.`), the supertypes (the fields nested in `Node` after `element.`, the JDK sentinels).

470 Java tests pass.

## Verification

**Corpus** (`tmp/ab/corpus-check-step50.txt`, output `tmp/ab/corpus-step50-out`): 1,302 complete, 0 worse; 102
oracles with other candidates (crawler 39, jsoup 43, cryptofs 11, uuid 5, eec 3, esapi 1). Compared step by step with
Step 49 (`tmp/ab/corpus-step49-out`): 113 steps lose candidates, **none gains one, no target token removed**. The 35
names removed, all fields of a nested or anonymous class:

| After | Removed | Declared in |
|---|---|---|
| an exception (`exception.`, `thrown.`, `ioException.`, `wrapper.`; jsoup, cryptofs, esapi, uuid) | `STACK_TRACE_SENTINEL`, `STACK_TRACE_ELEMENT_SENTINEL` | `Throwable.SentinelHolder` (private) |
| jsoup `doc.body().` | `validChildren` | `Element.NodeList` |
| jsoup `parse.`, `se2.` (`Evaluator`, `StructuralEvaluator`) | `a`, `b`, `index`, `key`, `pattern`, `value`, `NodeIterPool`, `evaluators` | the classes nested in `Evaluator` and `StructuralEvaluator` (`StructuralEvaluator.evaluator` and `threadMemo` stay) |
| crawler `rules.` | `_allow`, `_prefix` | `SimpleRobotRules.RobotRule` |
| crawler `trie.` | `chars`, `children`, `offset`, `value` | `SuffixTrie.Node` |
| crawler `normalizer.` | `NAME_COMPARATOR`, `idnNormalization`, `name`, `queryParamsToRemove`, `value` | `BasicURLNormalizer.Builder`, `BasicURLNormalizer.NameValuePair` and another nested class |
| crawler `Arrays.stream(...).` | `prev`, `started`, `finished` | the anonymous spliterator of `Stream.iterate` |
| cryptofs `prov.` | `c9sPath`, `longName` | `LongFileNameProvider.DeflatedFileName` |
| cryptofs `inTest.` (a `FileChannel`) | `PRIVATE`, `READ_ONLY`, `READ_WRITE` | `FileChannel.MapMode` |
| cryptofs `old.` (a `Path`) | `i` | the anonymous iterator of `Path.iterator()` |
| eec `styles.getFont(style).` | `BOLD`, `ITALIC`, `PLAIN`, `STRIKE`, `UNDERLINE`, `DOUBLE_UNDERLINE` | `Font.Style` |

Accepted (`corpus.py accept`).
