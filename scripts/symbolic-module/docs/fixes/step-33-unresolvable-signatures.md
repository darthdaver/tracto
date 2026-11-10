# Step 33 – The methods of a type whose signatures cannot all be resolved

**Date:** 2026-11-10
**Branch:** `22-test-processing-refactoring` (on top of Step 32)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: `resolvableMethods` (new); the methods after the
  name of a class (`updateHashMapOfMethodUsageIdentifiers`) and after a value (`collectMembersOfTypes`) when the lookup
  of all the methods fails
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (`getMethodModifiers`): the modifiers of a method
  neither in a source nor in a class file (the accessors of a record)
- `tools/ab/dev_tokens.py`: no report for a project whose JVM failed (its outputs are empty or cut off)
- tests: `UnresolvableSignatureTokensTest` (new, end-to-end)

## Measurement first: what Steps 30–32 left

Before choosing the fix, the 52 test classes of dataset v1 with a step lost to `member of a chain` or `member of a known
type` (4,742 oracles) were run with the v1 jar and with the Step 32 jar, on the oracles of the v1 run
(`dev_tokens.py`; outputs `tmp/ab/dev/step33-chains`, `step33-chains-small`; reports `tmp/ab/dev-step33-*.txt`):

| project | oracles | v1 → Step 32 | worse |
|---|---|---|---|
| jsoup | 2,128 | +274 (288 lose a step → 14) | 0 |
| ffmpeg | 391 | +214 (the Hamcrest oracles dropped in v1, Step 30) | 0 |
| uuid | 276 | +55 (all complete) | 0 |
| eec | 944 | +26 | 0 |
| crawler | 404 | +12 (all complete) | 0 |
| cryptofs | 187 | +5 | 0 |
| plexus | 219 | +5 | 0 |
| esapi | 193 | +3 | 0 |

257 missing steps left in these classes: member of a chain 88, member of a known type 65, dependency 53, class name 23,
test class member 14, other 8, nested-block variable 4, static import 2. Among them, one cause shared by several shapes
and projects: the lookup of the methods of a class failing as a whole.

(The first run of the measurement lost 9 JVMs to the RAM watchdog, at 85% of the 147 GiB of the container with 9 JVMs
and the page cache of the outputs; those five projects were run again with `-j 3`. The report of `dev_tokens.py` then
read the empty outputs of the killed JVMs and stopped: it now reports the failed exit status instead.)

## Problem

The members of a type are collected with `getAllMethods()` of the symbol solver, which builds every method of the type
and of its ancestors. It fails as a whole when one signature cannot be resolved, usually a type of a dependency (the
symbol solver gets the dependencies of no project, step 8). In the v1 logs, 338 such failures (`Unable to resolve type
declaration ... and collect methods from it`): eec `Styles`, `Fill`, `Font` (`org.dom4j.Element`), esapi `ESAPI`
(`HttpServletRequest`), cryptofs `CryptoFileSystemProvider`, `CryptoFileSystems`, `VaultConfig`, ffmpeg `FFmpegStream`
(`Fraction`), steve `ChargeBoxIdValidator`, `IdTagValidator`, `TransactionStopServiceHelper`.

- After the name of a class (`Styles.`), no method at all was offered (`Styles.` ▸ `toColor`: 36 steps in v1), and the
  class name was recorded as unsolvable (at inference, a `.` generated after it stopped the inference).
- After a value (`styles.`), the names of the declared methods only: no return type (the chain stopped after them), no
  inherited method.

Found by the corpus check of the first version of the fix: the accessors of a record (`dirId()` of `record
MissingDirIdBackup(String dirId, ...)`) are neither in a source nor in a class file, and `getMethodModifiers` (which
parses the printed form of a class-file method) threw for them. That exception stopped the collection of all the
methods of a record even when `getAllMethods()` succeeded (v1 log: `UseInfo: Could not parse method signature:
JavaParserRecordDeclaration`), and dropped a datapoint offering one of them (its contextual information prints the
signature).

## Fix

- `resolvableMethods`: the methods of the type and of its ancestors (those that can be resolved, `getAncestors(true)`),
  one by one; a method whose signature cannot be resolved is left out, a method of an ancestor overridden by one
  collected is left out. The type parameters of the ancestors are not replaced by their values (a type variable stands
  for the values recorded for its type parameter, Step 21).
- When `getAllMethods()` fails, after the name of a class and after a value: the resolvable methods, with their return
  types, then the names of the declared methods left (as before after a value; nothing before after a class name). The
  class name is recorded as unsolvable only when the class itself cannot be resolved. Each fallback has its own error
  handling.
- `getMethodModifiers`: for a method neither in a source nor in a class file, the modifiers of the resolved declaration
  (access, `static`, `abstract`) instead of an exception.

This does not decide step 8: the methods whose signatures need a type of a dependency stay unresolved, as before.

## Tests

`UnresolvableSignatureTokensTest` (end-to-end; a class with methods taking an `org.dom4j.Element`, not on the classpath):
`Styles.` ▸ `toColor`, `Styles.toColor("FF0000").` ▸ `getName`; `styles.` ▸ `palette`, `styles.palette().` ▸ `getName`;
`styles.` ▸ `baseName` (inherited); a record: `entry.` ▸ `color`, `entry.color().` ▸ `getName`. Checked on 2026-11-10:
against the code of Step 32 the test fails (`toColor`, `getName` after the calls, `baseName`; for the record, `getName`
after `entry.color().`: the name `color` was offered by the old fallback, without its return type). All
441 tests pass.

Not tested here (pre-existing, step 9 of `docs/resume.md`): after a class name the non-private instance methods are
offered too; the modifier rules of `checkModifiersRules` are combined with OR, and "not private" alone accepts them.

## Verification

Regression corpus (`tools/ab/corpus.py check`, 1,302 oracles), 2026-11-10:

| | complete | loses a step | dropped |
|---|---|---|---|
| Step 32 | 1,178 | 121 | 3 |
| Step 33 | 1,199 | 100 | 3 |

**0 worse.** 21 oracles complete: steve 11 (`TransactionStopServiceHelper.isEnergyValue(value)`), eec 9
(`Styles.toColor(...)`, 3 of them also `styles.getFont(style).getColor()`), cryptofs 1 (`stepParentDir.path().resolve(...)`,
a record).

Compared step by step with the outputs of Step 32 (`tmp/ab/corpus-step32-out`, `corpus-step33-out`): only the
candidates and the contextual information differ.
- Candidates (121 steps; steve 75, eec 26, cryptofs 15, ffmpeg 4, ...): only additions. The methods of the classes whose
  lookup failed (`isEnergyValue`, `toColor`, `getFill`, `getVaultVersion`, the accessors of the records), and the
  methods of `Object` inherited by them, as the other classes already offer them, including its private ones
  (`registerNatives`, `wait0`: the known defect of step 9).
- Contextual information (284 steps): only additions (the declarations of the methods now resolved: invoked methods
  271 steps, constructors 29, return classes 28, fields 18, enum constants 5); the type changes from `CLASS_CALL` to
  `METHOD_CALL` or `METHOD_RETURN` where a call on the class is now recognized (40 steps), from `METHOD_RETURN` to
  `METHOD_CALL` (4).

## Left

The other remaining shapes of the measurement, by size, their causes to be examined:
- the members of a variable in the body of a lambda, `assertThrows(X.class, () -> spy.` (cryptofs, about 24 steps);
- arrays returned through a type variable, `strings.get(0).length` (eec `CSVUtilTest`, 14);
- a field of another class, `structEval.threadMemo.get().isEmpty()` (jsoup, 12);
- the methods of `Object` after a value of an interface type, `data.get(0).toString()` (jsoup `FormElementTest`, eec);
- a parenthesized expression, `(new CipherSpec()).getKeySize()` (esapi, 7);
- dependencies (53: Mockito, Guava, servlet, commons-lang `Fraction`): step 8.
