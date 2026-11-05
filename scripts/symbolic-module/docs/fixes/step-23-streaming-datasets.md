# Step 23 – Datasets written one test class at a time

**Date:** 2026-11-05
**Branch:** `22-test-processing-refactoring` (developed in a worktree on top of Step 21; committed after Step 22)
**Files changed:**
- `src/main/java/star/tracto/utils/ChunkedJsonArrayWriter.java` (new): the oracles dataset, in chunks of at most 50 MB
- `src/main/java/star/tracto/utils/JsonArrayWriter.java` (new): the tokens dataset, one output file per input file
- `src/main/java/star/tracto/utils/FilesUtils.java`: `readJSONArray` (new, reads an array one element at a time);
  `writeChunkToFile` removed (its only caller was `OraclesDataset`)
- `src/main/java/star/tracto/preprocessing/OraclesDataset.java`, `src/main/java/star/tracto/preprocessing/TokensDataset.java`
- tests: `JsonArrayWritersTest` (new, 7 tests)

## Problem

Both stages kept all the datapoints of a project in memory and wrote them at the end:

- `OraclesDataset.generate` collected the `TestClazzDatapoints` of every test class in a list, then split the list into
  chunks of 50 MB and wrote them;
- `TokensDataset.generate` read a whole input file (in practice the whole project: the oracles of each reference
  project fit in one chunk), processed every test class and kept the results in a list, written at the end.

The tokens datasets are three orders of magnitude larger than their input, and the peak memory of the tokens stage
follows the size of its output (Step 20 B side, `-Xmx24g`):

| Project | Oracles (input) | Tokens (output) | Peak RSS, tokens stage |
|---------|-----------------|-----------------|------------------------|
| jsoup   | 25 MB           | 6.6 GB          | 20.6 GB                |
| eec     | 28 MB           | 4.1 GB          | 15.0 GB                |
| plexus  | 7.2 MB          | 1.4 GB          | 9.2 GB                 |
| crawler | 8.1 MB          | 2.2 GB          | 8.7 GB                 |
| uuid    | 4.5 MB          | 2.0 GB          | 8.4 GB                 |

One run of the nine projects in parallel peaks around 80 GB; on 2026-11-05 three runs started together (27 JVMs)
exhausted the memory of the machine (147 GiB, the limit of the container) and all were lost.

## Fix

Each test class is written as soon as it is processed, and dropped.

- **Oracles.** `ChunkedJsonArrayWriter` takes the elements one at a time and keeps the former split: an element goes
  into the current chunk unless the chunk is not empty and the element would take it over 50 MB (the sum of the sizes
  of the serialized elements, as before); the file of a chunk is created with its first element. Each chunk is written
  as before (`objectMapper.writeValue` on a generator over a UTF-8 `BufferedWriter` wrote `[e1,e2,...]` with each
  element serialized as `writeValueAsString` does): the element's text, already computed for its size, is written
  between brackets and commas. The write is outside the `try` that catches the errors of the processing of a test
  class: an error writing the dataset still stops the generation.
- **Tokens.** `FilesUtils.readJSONArray` reads the input array one element at a time (`MappingIterator`, the same
  deserializer as an element of the list); `JsonArrayWriter` writes each processed test class into the output array
  (`objectMapper.createGenerator(file, UTF8)`, the generator `objectMapper.writeValue(file, list)` used, and the
  serializer of each element by its runtime type, as the list serializer did). The errors statistics, small, are still
  kept for the whole file.

The output files are byte for byte those written before. Two behaviours change, both on failures only: a stage that
dies (an exception other than the per-class ones, a killed JVM) leaves the files written so far (a closed, partial
array for the tokens file being written), where before it left none; the A/B runner already rejects such a run (exit
status, missing end of the log).

Performance: the work is the same (each element was already serialized to text once for its size in the oracles
stage, and the files are written once); less memory means less garbage collection.

## Tests

`JsonArrayWritersTest` (7 tests, they did not compile before the fix):

- `JsonArrayWriter`: an array written one element at a time is byte for byte `objectMapper.writeValue(file, list)`,
  with names holding quotes, backslashes, control characters, non-ASCII characters, a surrogate pair and U+2028, and
  `Optional` fields; an empty array is `[]` as before.
- `ChunkedJsonArrayWriter`: for every maximum size from 1 byte to the total size + 1, the chunks are those of the
  former split of the whole list (reimplemented in the test) and each is byte for byte the former writer's; a chunk is
  closed only when the next element does not fit; no element, no file.
- `FilesUtils.readJSONArray`: the elements read one at a time equal the list read at once.

The end-to-end tests of both stages (`OraclesDatasetTest`, `TokensDatasetTest`, `GenericChainTokensTest`) pass
unchanged. Full suite: 373 tests, 0 failures.

## Verification on real projects (A/B)

The expected result is no difference at all: the same files, byte for byte, on the nine projects, with a lower peak
memory in the tokens stage.

- Development check (2026-11-05): the jar of Step 21 + Step 23 (`tmp/ab/jar-step23-stream.jar`) on steve, cryptofs and
  ffmpeg, compared with the B side of the Step 21 A/B (`jar-step21-order.jar`, run at the same time on the same
  machine). Results, after normalizing what changes at every run (the random names of the anonymous classes, the label of
  the run in the absolute paths):
  - oracles stage: all the files identical (datasets, statistics, test stats) on the three projects;
  - tokens datasets: identical up to the order of `nextPossibleProjectTokens` (steve 36 of 1,588 token steps, cryptofs
    661 of 7,703, ffmpeg 148 of 3,770): the same candidates, the fields of a type in another order;
  - tokens statistics: the same targets and the same `Unexpected token` messages (the lost oracles); other error
    messages differ only in identity hashes (`ImplicitGetterMethod@1ebd...`) and in which unresolved symbol is reported
    first (`Unsolved symbol : Cryptor` / `: Masterkey`);
  - peak RSS of the tokens stage: steve 2,241 → 2,407 MB, cryptofs 4,451 → 3,756 MB, ffmpeg 4,204 → 4,071 MB (small
    outputs: the gain is expected on jsoup and eec).

  The order differences are not caused by this step: `TokensUtils.updateHashMapOfFieldTypeIdentifiers` (both overloads)
  collects the fields and variables in `Collections.newSetFromMap(new IdentityHashMap<>())`, iterated in the order of
  the identity hash codes, then puts them in a `HashMap` by name: names in the same bucket keep the insertion order,
  which changes with any change of the allocations (as the order of the methods did before Step 21). Writing the
  datapoints earlier changes the allocations. The error messages depend on the same orders. Writing cannot reorder a
  list nor change the candidates, and a change of the results of a test class by the processing of a later one (the
  risk of writing earlier) would change the content, not only the order: the check up to order is sound for this step.
- No complete A/B of its own (loop decided on 2026-11-05, `docs/resume.md`): the regression corpus is checked with
  Steps 22–24 together, and the full nine-project test runs before the training (`docs/resume.md`, section 7), where
  the peak memory of jsoup and eec with streaming is measured.

## How to run the tests

```bash
cd scripts/symbolic-module
mvn test -Dtest='JsonArrayWritersTest,OraclesDatasetTest,TokensDatasetTest'
```
