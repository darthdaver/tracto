# Step 15 – Robustness and clean-up

**Date:** 2026-10-27
**Branch:** `22-test-processing-refactoring` (uncommitted, on top of `2d062ab7`, which commits Step 14)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/TestUtils.java` (D14, D16, D17, D18, D25, dead code)
- `src/main/java/star/tracto/preprocessing/utils/TestCaseSplitter.java` (D33)
- `src/main/java/star/tracto/preprocessing/utils/JavaFileCollector.java` (D20)
- `src/main/java/star/tracto/preprocessing/OraclesDataset.java` (D21)
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java` (D22, dead code)
- deleted: `M2TMatchingType`, 20 statement visitors of `utils/javaParser/visitors/statements`
- `pom.xml` (system property of the tests, for Mockito on Java 21)
- tests: `TestUtilsDatapointsTest`, `TestUtilsSplitTest`, `TestUtilsNormalizeTest`, `JavaParserUtilsTest`,
  `OraclesDatasetTest`
- docs: `docs/design-review-2026-10-26.md`

## Defects fixed

| Defect | Problem | Fix |
|--------|---------|-----|
| D14 | `processSplitTestClass`: the `try` enclosed the loop over the split test cases. An `IllegalStateException` in one split test case dropped the datapoints of all the following ones; any other runtime exception dropped the whole test class (caught by `OraclesDataset`). | Each split test case is processed within its own `try`: a failure is logged, recorded in the processing errors of the test class, and drops only its datapoint. |
| D16 | `splitTest`: for a test case annotated with `@Override`, the search of the call to the superclass looked for a call named `super`, which never exists (`super.test()` is a call named `test`), so the branch handling it never ran. Fixing the search would have run a broken branch: the stub of the test case would have started with a call to itself. | The branch is removed. The behaviour is the one of every dataset generated so far: the split test cases keep the call to the superclass, and a stub with the name of the test case (`System.out.println("Method Overriding!");`) overrides the test case of the superclass in the split test class. |
| D17 | `getJunitVersion`: the first import containing `junit` decided the version: `import org.junit.Assert;` before `import org.junit.jupiter.api.Test;` gave JUnit 4. | The import of the test annotation decides (`org.junit.jupiter.api.Test` or `org.junit.jupiter.api.*`: JUnit 5; `org.junit.Test` or `org.junit.*`: JUnit 4); without it, the first JUnit import, as before. |
| D18 | `getAssertionsDistribution`: the count of the `assert` statements was overwritten by each statement. Statistics only. | The counts are summed. |
| D20 | `JavaFileCollector.cleanRepository` did not delete the generated `*STARSplitTestPrefix.java` files, collected as test classes by the next run. | They are deleted with the other generated files. |
| D21 | `OraclesDataset.testClassPathToSourceClassNormPath` removed every occurrence of `test` from the name of the test class: the focal class of `LatestTest` was looked for as `La`. | Only the `test` suffix and prefix are removed (`CalcTest`, `TestCalc`). |
| D22 | `JavaParserUtils.checkModifiersRules` returned `true` even when no rule was satisfied: the modifier rules of `TokensUtils` were never applied. | `false` when no rule is satisfied (`true` without rules). In `TokensUtils` the candidate tokens no longer include the private members of other classes (rules "public and static, or not private") and the private or protected nested types. |
| D25 | Annotations written with their fully qualified name (`@org.junit.jupiter.api.Test`, `@org.junit.jupiter.api.Disabled`) were not recognized by `normalizeTest`, `splitTest`, `processSplitTestClass` and `distributeMethods`. | The simple names of the annotations are compared. |

Found while writing the test of D16:

- **D33**: the split test cases of a test case annotated with `@Override` kept the annotation, although with their new
  names they override nothing: the split test class does not compile, and `Override` was listed among the annotations
  of the test prefixes. `TestCaseSplitter` removes the annotation from the renamed copies that have a test
  annotation. A test case without a test annotation that overrides a test case of the superclass is recognized as a
  test case only by `@Override` (and a name containing `test`, `distributeMethods`): its split test cases keep the
  annotation, otherwise the datapoint phase would take them for auxiliary methods (the first A/B run lost the
  datapoint of `FFmpegHlsOutputBuilderTest.testSetFormat` in this way); their split test class still does not
  compile.

## Dead code removed

Listed in the housekeeping section of the review: `matchMethodsToTests` (empty) and the enum `M2TMatchingType` it
used; in `normalizeTest`, the loop over the integrated auxiliary methods (its body was commented out) with the list of
integrated auxiliary methods (no longer read), the search of `super` calls in the `@Override` test cases (its result
was never read) and the filter of the private top-level types (a top-level type cannot be private);
`JavaParserUtils.replaceParams` and `renameLocalVar`; `StmtVisitorHelper.getLastExprInStmt` and the 20 statement
visitors only used by the method removed in Step 11; the unused imports of `TestUtils`.

The review also asked for a before/after comparison of the change of `TokensUtils` in `b87e674c` (the variables of the
test prefix collected in an identity set instead of a map keyed by name). The two differ only when two variables have
the same name: since Step 12 the variables of a normalized test case have unique names, so they hold the same variables.

## Tests

Written before the fixes; all the `@Disabled` known-defect tests are now enabled.

| Test | Defect |
|------|--------|
| `TestUtilsDatapointsTest.failureOfASplitTestCaseDoesNotDropTheOtherSplitTestCasesOfTheClass`: no input is known to make the processing of a split test case fail (none in the seven projects of the A/B runs either), so the failure is injected with a static mock of `JavaParserUtils.getLastAssertionInMethodDeclaration` for the first split test case | D14 |
| `TestUtilsSplitTest.overridingTestCaseIsSplitAndKeepsAStubWithItsName` | D16 (characterization), D33 |
| `TestUtilsDatapointsTest.overridingTestCaseWithoutTestAnnotationProducesDatapoints` (written after the first A/B run, failing before its fix) | D33 |
| Enabled: `TestUtilsNormalizeTest.junitVersionIsTakenFromTheTestAnnotationImport` | D17 |
| Enabled: `TestUtilsNormalizeTest.statisticsCountTheAssertStatementsOfTheWholeTestCase` | D18 |
| Enabled: `OraclesDatasetTest.runningTheGenerationAgainGivesTheSameResult` | D20 |
| Enabled: `OraclesDatasetTest.focalClassWhoseNameContainsTestIsMatched` | D21 |
| `JavaParserUtilsTest.checkModifiersRulesRequiresAtLeastOneRuleToBeSatisfied` (4 cases) | D22 |
| Enabled: `TestUtilsSplitTest.disabledTestCaseWithFullyQualifiedAnnotationIsKeptUnchanged`; new: `TestUtilsSplitTest.testCaseWithFullyQualifiedTestAnnotationIsSplit`, `TestUtilsDatapointsTest.testCaseDisabledWithAFullyQualifiedAnnotationProducesNoDatapoints` | D25 |

Before the fixes 11 tests failed. Mutation check of D14: catching only an exception that is never thrown, the D14 test
fails.

The static mock needs Byte Buddy to support Java 21: the version bundled with Mockito 5.2 supports Java 20, so the tests
run with `net.bytebuddy.experimental=true` (`systemPropertyVariables` of Surefire, which keeps the `argLine` of JaCoCo).
Upgrading Mockito would remove the need for it.

Full suite: 188 tests, 0 failures, 0 errors, 0 skipped.

## Verification on real projects

**Oracles dataset.** The seven projects of the previous steps, fresh copies, committed configuration, compared with
Step 14 datapoint by datapoint (ffmpeg-cli-wrapper rerun after the fix of D33 described above):

| Project | Test classes processed | Datapoints | Differences |
|---------|------------------------|------------|-------------|
| crawler-commons | 13 → **18** | 655 → **732** | D21: `ImageAttributesTest`, `LinkAttributesTest`, `MobileAttributesTest`, `NewsAttributesTest`, `VideoAttributesTest` matched with their focal classes (+77 datapoints) |
| java-uuid-generator | 8 → 8 | 369 → 369 | none |
| ffmpeg-cli-wrapper | 21 → 21 | 586 → 586 | none |
| plexus-utils | 31 → 31 | 785 → 785 | none |
| jsoup | 45 → **46** | 3553 → **3653** | D21: `AttributesTest` matched with `org.jsoup.nodes.Attributes` (+100 datapoints; 2 of its test cases discarded for unsupported try statements) |
| eec | 22 → 22 | 1427 → 1427 | none |
| esapi | 45 → 45 | 528 → 528 | none |

No datapoint of Step 14 was removed or changed. The names of the 6 test classes contain `test` within
`Attributes` + `Test` (`attributestest`): removing every occurrence of `test` left `attribuest`, which matched no
class. The other defects fixed do not occur in these projects or do not change the dataset: D14, no split test case fails;
D17, the JUnit version of every test class is the same as in Step 14; D25, the only fully qualified JUnit annotations
(`@org.junit.Ignore` in esapi's `EncoderTest`, `@org.junit.Test` in `AccessControllerTest`) are in test classes without
a focal class of the same name, not processed; D16, D18 and D20 do not change the datapoints.

**Tokens dataset (D22).** The tokens stage on java-uuid-generator, Step 14 and Step 15: the same 366 datapoints, with
fewer candidate project tokens in their steps (2027 token steps change). The classes collected from the packages of the
wildcard imports must be public (the rule of `collectImportedClasses`): the package-private classes of the JDK
(`java.util.prefs.XmlSupport`, `java.net.SocksSocketImpl`, `InetAddressImpl`, …), not accessible from a test, are no
longer candidates. The tokens stage logs 70 errors fewer (743 → 673).

## How to run the tests

```bash
cd scripts/symbolic-module
export JAVA_HOME=/Users/usi/repositories/tracto/scripts/utils/resources/sdkman/candidates/java/21.0.6-amzn
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```
