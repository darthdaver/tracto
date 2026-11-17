# Step 48 – The private methods inherited by the test class are not candidates

**Date:** 2026-11-17
**Branch:** `22-test-processing-refactoring` (on top of Step 47)
**Files changed:**
- `src/main/java/star/tracto/preprocessing/utils/MemberAccess.java`: `ofTestClass` (the component `inherited`: a
  protected member of a superclass is accessible)
- `src/main/java/star/tracto/preprocessing/utils/TokensUtils.java`: the methods of the test class resolved as a whole
  are filtered by `MemberAccess.ofTestClass` (before, `MemberAccess.ANY`)
- tests: `InheritedMemberAccessTokensTest` (new, end-to-end)

## Problem

A defect of the list of `docs/resume.md` (section 6, item 10), measured on the corpus of Step 47: the private
methods of `Object` are offered without a receiver, `registerNatives` (the JDKs 8 and 11: esapi, ffmpeg, jsoup,
plexus, uuid; 24 steps) and `wait0` (Java 19 and later: cryptofs; 20 steps, in the body of a lambda where the void
methods are candidates, Step 39), and they are listed among the invoked methods of the contextual information of every
oracle. The methods of the test class and of its superclasses were collected with `MemberAccess.ANY` (the private
methods of the test class itself must be offered), so the private methods of the superclasses came too: of `Object`,
and of a superclass of the project (the test: `superSecret`), with the package-private ones of a superclass of another
package (`otherPackageHelper`). Only when the test class is resolved as a whole: method by method
(`TestClassScope`, Step 22) the inherited ones were already filtered.

## Fix

`MemberAccess.ofTestClass`: any member of the test class and of the classes nested in it; of the superclasses, the
public, protected and package-private (same package) ones, as Java gives them to a subclass; not the private ones.

## Tests

`InheritedMemberAccessTokensTest` (end-to-end): a test class resolved as a whole and one that is not, extending a class
of another package (`inheritedProtected`, `inheritedPublic`, a package-private and a private method); after
`assertEquals(two,` and `assertDoesNotThrow(()->`: `twice` (private, of the test class), `inheritedProtected`,
`inheritedPublic`, `hashCode`, `toString` offered; `superSecret`, `otherPackageHelper`, `wait0`, `registerNatives` not.
Against the code of Step 47: `superSecret`, `otherPackageHelper`, `wait0` offered (the resolved class). Mutations
(each fails the test): the protected rule (`inheritedProtected` missing), the members of the test class itself
(`twice` missing).

468 Java tests pass.

## Verification

Corpus (`tmp/ab/corpus-check-step48.txt`): 1,302 complete, 0 worse; every oracle changed, only removals:
`registerNatives` (24 steps) and `wait0` (20) from the candidates, no target among them; from the invoked methods of
the contextual information, 4,332 entries (`registerNatives` 3,618, `wait0` 714); nothing added, no other field
changed. Accepted.

## Left (the other defects of the list)

- The JDKs of a repository whose Java version is not detected (the newest and the oldest of `lib`: classes of both are
  offered, `Compiler`, `IO`): the version is detected for the nine projects (v3 logs), so it does not occur there. It
  only adds candidates (it never loses one), and narrowing it could lose a class, so it is not changed blindly: the
  generation logs the source of the version per repository (`Java version: ... (not detected)`), to measure first; the
  likely fix then is to read the version from the effective POM computed by the resolver (Step 38: a version set by a
  parent POM outside the repository is not detected).
