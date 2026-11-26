# Step 51 – The order of the classes of an imported package does not depend on the folder

**Date:** 2026-11-23
**Branch:** `22-test-processing-refactoring` (on top of Step 50 and `docs/mac-generation.md`)
**Files changed:**
- `src/main/java/star/tracto/utils/javaParser/JavaParserUtils.java`: `collectPathsOfImportedClasses`
- tests: `ImportedPackageOrderTokensTest` (new, end-to-end), `JavaParserUtilsTest` (one test),
  `TokensFixture.generate(Path modulePath)` (the module in another folder)

## Problem

Found on the MacBook, at checkpoint B of `docs/mac-generation.md`: the corpus check matched the expected results of
the server on 8 projects; on uuid, 32 oracles had another digest (all of `LockedFileTest` 26, `UUIDTimerTest` 5,
`EgressInterfaceFinderTest` 1). Only the **order** of `nextPossibleProjectTokens` differed: the same candidates, the
same statuses, missing tokens and contextual information. Controlled runs of the uuid classes: two runs on the Mac
byte-identical; the module copied to another folder on the same disk: the same 32 oracles change; the same path
(a symbolic link) on APFS and on an HFS+ disk image: byte-identical. The cause is the absolute path of the module, not
macOS, the file system, the order of the directory listings or the case-insensitive volume.

`collectPathsOfImportedClasses` collected the files of an imported package (`import java.io.*`, or a package of the
repository) in a `HashSet<Path>`. Its order follows `Path.hashCode`, computed from the absolute path: the folder of the
module for the JDK sources (`<module>/lib/java21/...`), the folder of the repository for its packages (the corpus
copies them in `tmp/ab/corpus-src/<project>/repo`, the generation runner clones them in `tmp/gen/<run>/work/<id>`).
The class names were added in that order to the `HashMap` of the class candidates (`classPathIdentifiers`), whose
`keySet()` is the order of the class names among the candidates (`TokensUtils`, the steps after an `IDENTIFIER`). The
names in different buckets come out in the order of their hash code (the same everywhere); the names in the same bucket
in an order set by the order in which they were added (`computeIfAbsent` puts a new name at the head of its bucket; a
bucket of more than eight names becomes a tree). In uuid, the focal classes import `java.io.*`, `java.util.*`,
`java.net.*`: for example `PipedReader` and `CharArrayWriter` (bucket 78 of 512) swapped between the server and the Mac.

So a dataset depended on where the module was installed and on the folder of the run, and the expected results of the
corpus were tied to the folder of the server (`/home/coder/project/tracto/scripts/symbolic-module`).

Size, between two folders of the module on the Mac (the uuid corpus classes): 104 of 1,325 token steps with another
order; in each, 4 to 6 of about 250 candidates change place (two or three pairs of names swap); the target never moves.
Not a randomness: the order is always the same in a given folder.

**Effect on the model:** none to expect. The prompt builder writes the candidates verbatim (`EWash.py`); the fine-tuning
shuffles the datapoints, not the candidates of a prompt. The decision of the author of 2026-11-05 (`docs/resume.md`,
section 2) stays: the order is deterministic in the dataset; a seeded shuffle against positional shortcuts, if wanted,
goes in the prompt builder.

## Fix

A `LinkedHashSet`: the files in the order they are found, the imports in their order, the files of a package in the
order of its listing (sorted since Step 26), the source folders in the order given. Every other listing that feeds the
candidates was already sorted (Steps 26, 38); the corpus from a second folder (below) confirms that no other order
depends on the folder.

## Tests

- `JavaParserUtilsTest.collectPathsOfImportedClassesAddsTheClassesInAnOrderThatDoesNotDependOnTheFolder`: the same
  repository in two folders (`first`, `runs/another-run/work/repository`); `import com.acme.Canvas;` and
  `import com.acme.shapes.*;` with 26 classes; a `LinkedHashMap` records the order in which the classes are added:
  `Canvas`, then `ShapeA` to `ShapeZ`.
- `ImportedPackageOrderTokensTest` (end-to-end): the same repository, the module in another folder (links to its
  `lib` and `assertion-api`); a focal class with `import java.io.*`: the candidates of every token step identical.

Against the code of Step 50 (the `HashSet` back): both fail (the classes in the order of the hashes of their paths;
`Serial` and `UTFDataFormatException` swapped in the end-to-end test). With the fix both pass; 472 Java tests pass.

A third test was dropped: class names with the same hash code (`AaAa`, `AaBB`, `BBAa`, `BBBB`: they share a bucket of
the map of the candidates) have file paths with the same hash code too (same folder, same length), so the `HashSet` kept
them in the order of the files in any folder: the test could not tell the old code from the fixed one.

## Verification

**Corpus** (`tmp/ab/corpus-check-step51.txt`, jar `f697d9c4`): 1,302 complete, 0 worse; 8 projects identical to the
expected results; uuid: the same 32 oracles as at checkpoint B, other candidate order only. Compared with the Mac
before the fix (checkpoint B, `tmp/ab/corpus-mac-step50-out`): only the tokens file of uuid changes, in 104 of 1,325
steps of the same 32 oracles, the same names in another order (no other field; the target never moves):
`BufferedInputStream` and `PushbackInputStream` (80 steps, `LockedFileTest`), and 8 to 10 names of `java.util` and
`java.io` (`UUIDTimerTest` 21 steps, `EgressInterfaceFinderTest` 3). The order of these oracles is now neither the
server's nor the old Mac's, and the same in every folder.

**Second folder:** the whole corpus again, both stages, with the same jar, from a git worktree
(`tmp/wt-step51/scripts/symbolic-module`: another module folder, so other paths for the JDK sources, the copies of
the repositories and their `processed_libs`; `lib` an APFS clone of the module's). `prepare` 120 s (`-j 2`), `check`
319 s (`-j 4`, 1,846 s CPU), the same result (1,302 complete, uuid's 32 oracles). Compared file by file with the
module folder (`compare_folders.py`: each folder's path replaced by `<module>`, files streamed):
- tokens stage: the 27 files of the datasets and statistics of the nine projects **byte-identical**, without any
  replacement (they hold no path of the module); only the logs differ (times);
- oracles stage: identical once the path is replaced, except the random names of the anonymous classes
  (`Anonymous-<UUID>`: eec 16, ffmpeg 2, jsoup 34; identical once masked), the next step;
- the dependencies: `processed_libs/dependencies.json` (the report, with the absolute paths of the cache: the cache
  copied in the worktree keeps the resolutions of the module's cache) and `maven.log` (absent: all from the cache).
  The module reads the dependencies through `<repo>/processed_libs/decompiled/...` (links into the cache, never
  resolved), so their paths changed with the folder of the repository.

Accepted (`corpus.py accept`, 2026-11-24, with the author's OK): the expected results of the corpus now have the order of
the fix for uuid's 32 oracles (the environment recorded on the Mac is the same as the server's: JDK, JDK sources,
inputs).
