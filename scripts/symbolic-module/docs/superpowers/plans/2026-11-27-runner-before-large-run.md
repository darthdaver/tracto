# The runner before the large run Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `tools/gen/generate.py run` ready for the large run of the ISSTA 2027 datasets: Java-file fingerprints,
zstd outputs, a retry pass of the timeouts with doubled limits, a manifest, and the order of the repositories.

**Architecture:** Small functions added to `generate.py` (stdlib only, as now) and called from `Runner.generate` /
`Runner.process`; a separate `tools/gen/run_order.py` turns the split into the `--repos` file. The datasets the module
writes do not change: after decompression they are byte-identical to an uncompressed run.

**Tech Stack:** Python 3.9+ standard library, the `zstd` command (`/opt/homebrew/bin/zstd`), `unittest`.

**Spec:** `docs/data-collection-issta2027.md` (steps 2, 9 and step 8's check 3; decisions F.1 and F.4 of
`docs/mac-generation.md`).

## Global Constraints

- zstd level 3 (`zstd -3`), applied to every `.json` file of a repository's outputs once its status is `done`.
- Default limits unchanged: oracles 2 h, tokens 12 h; the retry pass doubles them (4 h, 24 h).
- Seed of the order of the fine-tuning repositories: `20261128`.
- Fingerprint of a Java file: sha256 of its text with comments removed (`//`, `/* */`, outside string, char and text
  block literals) and every whitespace character removed; taken right after the clone, before anything writes into
  it; every `*.java` file except those under `target/` or `.git/`.
- Tests: `cd scripts/symbolic-module && python3 -m unittest tools/gen/test_generate.py tools/gen/test_run_order.py`.
- The rules of section 0 of `docs/mac-generation.md`: byte-identical outputs for the same inputs, nothing lost
  silently, no module change.
- Open, to ask the author before the large run: record the coordinates of each repository's dependencies in the
  manifest (the licences of third-party content in the contextual information).

## Review Focus

- A `.json` output that zstd fails to compress or verify: kept uncompressed, the status says which file and why; the
  status is not `done` with a missing output (Task 2).
- A run stopped during the compression: the repository has no status (run again), never a half-compressed output
  with a `done` status (Task 2).
- A Java string or text block containing `//` or `/*`: not treated as a comment (Task 1).
- A file that is not valid UTF-8: fingerprinted from its bytes decoded with `errors='replace'`, not skipped (Task 1).
- `--retry-timeouts` on a repository whose previous status is not `timeout`: not run again (Task 3).

---

### Task 1: Fingerprints of the Java files

**Files:**
- Modify: `tools/gen/generate.py` (new functions; `Runner.generate` right after the `clone` check)
- Test: `tools/gen/test_generate.py` (new class `FingerprintTest`)

**Interfaces:**
- Produces:
  - `normalize_java(text: str) -> str`
  - `java_fingerprints(repo: str) -> list[tuple[str, str, int]]` — `(relative path, sha256 hex, lines)` sorted by path.
  - `Runner.generate` writes `<run>/fingerprints/<id>.tsv` (header `path\tsha256\tlines`) after a successful clone;
    the status gets `'javaFiles': <count>`.

- [ ] **Step 1: Write the failing tests**

```python
class FingerprintTest(TempDirTest):
    def test_comments_and_whitespace_do_not_count(self):
        self.assertEqual(g.normalize_java('int a = 1; // x\n/* y */ int b;'), g.normalize_java('int a=1;\n\tint b;'))
    def test_literals_keep_their_content(self):
        self.assertIn('"http://x/*y*/"', g.normalize_java('String s = "http://x/*y*/";'))
        self.assertIn("'/'", g.normalize_java("char c = '/'; // c"))
        self.assertIn('//kept', g.normalize_java('String t = """\n  //kept\n  """;'))  # text block kept as is
    def test_files_sorted_target_and_git_left_out(self):
    def test_invalid_utf8_is_fingerprinted(self):
    def test_written_after_the_clone(self):   # Runner.generate with clone mocked to create two files -> tsv with 2 rows
```

- [ ] **Step 2: Run to verify they fail** — Expected: `AttributeError: ... 'normalize_java'`.
- [ ] **Step 3: Implement** (a single left-to-right scan with states code / string / char / text block / line
  comment / block comment; whitespace removed only in code).
- [ ] **Step 4: Run the tests** — Expected: OK.
- [ ] **Step 5: Commit**, message `[SYMBOLIC MODULE] Runner: a fingerprint of every Java file of a repository`.

### Task 2: zstd outputs

**Files:**
- Modify: `tools/gen/generate.py` (`Runner.process`, after `generate` returned `done`, before the status is written)
- Test: `tools/gen/test_generate.py` (new class `CompressionTest`, skipped when `zstd` is not on the PATH)

**Interfaces:**
- Produces: `compress_outputs(output: str, repo_id: str, log_path: str) -> dict` — `{'files': n, 'bytes': before,
  'compressedBytes': after}` or raises `CompressionError(path, reason)`; each `<output>/<kind>/<id>/**/*.json`
  becomes `*.json.zst` (`zstd -3 -q`, then `zstd -t`, then the `.json` removed).
- `Runner.process`: on `CompressionError` the status is `failed`, reason `compression <path>: <reason>`, outputs
  removed as for any failure; otherwise the status gets `compression`.

- [ ] **Step 1: Write the failing tests**

```python
class CompressionTest(TempDirTest):
    def test_every_json_compressed_and_round_trips(self):   # two kinds, nested folder; zstd -d gives the same bytes
    def test_other_repositories_untouched(self):
    def test_a_failure_keeps_the_file_and_raises(self):    # run_process mocked to exit 1 on `zstd -t`
    def test_status_failed_on_compression_error(self):     # Runner.process -> 'failed', 'compression ...', outputs removed
```

- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement**; `report` sums `compression.compressedBytes`.
- [ ] **Step 4: Run the tests** — Expected: OK.
- [ ] **Step 5: Commit**, message `[SYMBOLIC MODULE] Runner: the outputs of a repository compressed with zstd once done`.

### Task 3: Retry pass of the timeouts with doubled limits

**Files:**
- Modify: `tools/gen/generate.py` (`main`: option `--retry-timeouts`; `Runner.to_run`; the limits passed to `stage`)
- Test: `tools/gen/test_generate.py`

**Interfaces:**
- Produces: `run --retry-timeouts`: runs only the repositories whose status is `timeout`, with
  `--oracles-timeout` and `--tokens-timeout` doubled; the new status has `'retry': {'previous': <old status>,
  'limits': [o, t]}`.

- [ ] **Step 1: Write the failing tests** — `test_retry_timeouts_selects_timeouts_only` (statuses done, failed,
  timeout, none → only the timeout), `test_retry_timeouts_doubles_the_limits` (`stage` receives 14400 and 86400 with
  the default options), `test_retry_keeps_the_previous_status`.
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests** — Expected: OK.
- [ ] **Step 5: Commit**, message `[SYMBOLIC MODULE] Runner: a retry pass of the timeouts with doubled limits`.

### Task 4: Manifest of a run

**Files:**
- Modify: `tools/gen/generate.py` (`main` of `run`)
- Test: `tools/gen/test_generate.py`

**Interfaces:**
- Produces: `write_manifest(run_dir: str, args, argv: list[str]) -> str` — writes
  `<run>/manifests/<UTC timestamp>.json` at each start of `run` (a run restarted keeps every manifest):
  `argv`, sha256 of the jar, of the configuration and of the `--repos` file, `git rev-parse HEAD` of the repository
  and whether the working tree has changes under `scripts/symbolic-module/src`, the first line of `java -version` of the
  pipeline JDK, of `mvn -version` and of `zstd --version`, `platform.platform()`, `CUTOFF`, the seeds (walk
  `20261127`, order `20261128`), the sha256 of `resources/seart-github-repos-updated.csv` and
  `resources/issta2027-split.csv` when present.

- [ ] **Step 1: Write the failing tests** — `test_manifest_fields` (every key above present, hashes of the given
  files), `test_each_start_writes_a_new_manifest`.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5: Commit**, message `[SYMBOLIC MODULE] Runner: a manifest at each start of a run`.

### Task 5: Order of the repositories of the run

**Files:**
- Create: `tools/gen/run_order.py`
- Test: `tools/gen/test_run_order.py`

**Interfaces:**
- Consumes: `resources/issta2027-split.csv` (columns of the walk plan, Task 4).
- Produces: `order(rows: list[dict], seed: int = 20261128) -> list[dict]`; CLI `run_order.py --split ... --out
  tmp/gen/issta2027/run-repos.csv` with the columns `id,name,commit,codeLines` that `read_repos` reads.

- [ ] **Step 1: Write the failing tests** — `test_experiment_then_validation_then_fine_tuning`;
  `test_excluded_never_listed`; `test_fine_tuning_round_robin_over_sizes` (the first three fine-tuning rows are one
  small, one medium, one large, each group in its own seeded order); `test_same_input_same_bytes`.
- [ ] **Step 2–4:** fail, implement (experiment and validation sorted by numeric id), pass.
- [ ] **Step 5: Commit**, message `[SYMBOLIC MODULE] The order of the repositories of the large run`.

### Task 6: End-to-end check against phase C

- [ ] **Step 1:** `python3 tools/gen/generate.py run tmp/gen/runner-check --jar tmp/gen/ref9.jar --repos
  tmp/gen/e2e-repos.csv -j 3 --xmx 12g` (steve, uuid, ffmpeg).
- [ ] **Step 2:** decompress the outputs into a scratch folder and compare them with `tmp/gen/e2e/output` using the
  scratchpad `compare_runs.py` (the folder of the clone replaced by `<repo>`): every file identical. Check the
  fingerprints files (one row per Java file of the clone) and the manifest by hand.
- [ ] **Step 3:** row in `docs/resume.md`; numbers (compression ratio) in `docs/issta2027-dataset-numbers.md`
  section 8; commit, message `[SYMBOLIC MODULE] Runner checked end to end: outputs identical to phase C once
  decompressed`.
