# Generating the datasets on the MacBook (instructions for a new session)

**Written:** 2026-11-23, on the server, at commit `635a35a` (branch `22-test-processing-refactoring`), just before the
server was switched off.
**Target machine:** MacBook Pro M5 Pro (Apple Silicon, arm64), 64 GB of RAM, about 1.5 TB of free disk.
**Purpose:** a new Claude session on the Mac reads this document first, and only then works through phases A to G in
order. Each phase ends with a **checkpoint**. Do not start a phase before the checkpoint of the previous one passes. If
a checkpoint fails, stop and report to the author with the evidence: do not work around it.

Background documents (read them when a phase points to them):

- `docs/resume.md`: the state of the symbolic module, the author's decisions, the development loop, the regression
  corpus (sections 2–5).
- `docs/missing-candidate-tokens.md`: the history of the fixes (Steps 20–50), and the observations about missing
  candidate tokens (keep adding to it).
- `docs/contextual-information.md`: what the datasets store for the prompt, and the decisions still open (phase F).
- `tools/gen/generate.py` (its docstring): the generation runner.
- `README.md`, section "Generation of the training datasets (Maven repositories)".

## 0. Rules of the author (all still valid)

- **Maven projects only.** **No pilot on unseen repositories** (calibrate on the nine reference projects only).
- **Never lose a target token at inference** (losing candidates during dataset generation is acceptable); after that,
  as much context as possible. **Never prune candidates** on heuristics (decided 2026-11-21: the expected type is at
  most a hint; see `docs/contextual-information.md`).
- **Correctness first:** tests that fail before a fix; every corpus difference explained; mutation-check each part of a
  fix. Each module step: tests → `docs/fixes/step-NN-*.md` → corpus check (explain every difference, then
  `corpus.py accept`) → rows in `docs/resume.md` and `docs/missing-candidate-tokens.md` → commit and push.
- **Reproducibility:** the candidate order is deterministic (no shuffling in the dataset); the same inputs must give
  byte-identical outputs.
- **Never load a whole dataset in memory:** stream per file (jsoup's tokens file is a single 13 GB JSON array; use
  Jackson streaming in Java, or `ijson`-style streaming in Python).
- **One large run at a time.** Do not edit module files while a `corpus.py`, `ab.py`, `dev_tokens.py` or
  `generate.py run` is running (develop in a git worktree under `tmp/` instead).
- **Git:** commit with `git -c user.name=darthdaver -c user.email=davide.molinelli@usi.ch commit ...`; messages start
  with `[SYMBOLIC MODULE]` and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. The author's
  standing authorization: commit and push when a step is done. Push with
  `GIT_ASKPASS= GIT_TERMINAL_PROMPT=0 git push`; if it fails for credentials, ask the author to run `! git push` once
  themselves. **A token must never appear in the chat or in a file.**
- **Privacy:** the author's email must not be sent to external services; HTTP requests use the generic User-Agent
  `tracto-dataset-generation`.
- **Ask the author before:** `generate.py select` (4,517 requests to GitHub), the large run, any change of the output
  format, any deletion of data.

## A. Machine setup

1. **A case-sensitive volume for everything.** The default macOS file system ignores case: a repository with
   `Foo.java` and `foo.java` would be checked out differently from Linux, and the datasets would differ. Ask the author
   to create one in Disk Utility (Add Volume → **APFS (Case-sensitive)**, for example `/Volumes/tracto`, no quota), and
   clone the repository there. Check it: `touch a A && ls | wc -l` must count 2 files.
2. **Tools:** Xcode command line tools (`git`), Python 3.9+, Maven (with SDKMAN: `sdk install maven`), `tmux`.
3. **The JDK that runs the module: Amazon Corretto 21.0.6, arm64** (`sdk install java 21.0.6-amzn`). The server used
   exactly `Corretto-21.0.6.7.1`; `java -version` must show it. Set `TRACTO_JAVA_HOME` and `JAVA_HOME` to its home (with
   SDKMAN: `~/.sdkman/candidates/java/21.0.6-amzn`), and put its `bin` and Maven's `bin` first on the `PATH`. The
   folder `scripts/utils/resources/` of the server (Linux x86 binaries, not in git) is not needed on the Mac.
   Only this one JDK has to be installed: the JDK **versions of the analysed projects** (8 to 27) are not run, their
   sources are read from `lib/` (next point).
4. **The JDK sources in `scripts/symbolic-module/lib/`:** `lib/java8` … `lib/java27` from the archive of
   `LIB_JDKS_LINK` (`bash/utils/local_variables.sh`), not `setup-jdk-sources.sh`. `lib/java_classpaths.json` is in git.
   The corpus compares them with the server's (file counts and bytes per folder, in
   `tools/ab/corpus/expected.json` → `environment.jdk_sources`). Do not open these folders in Finder (it writes
   `.DS_Store` files, which change the counts); `find lib -name .DS_Store -delete` if it happened.
5. **Limits for long runs:** `ulimit -n 65536` in the shell that runs the jobs (macOS defaults to 256); run in `tmux`;
   `caffeinate -dims` in another pane for the whole run; power adapter connected; Spotlight and Time Machine excluded
   from the volume (System Settings), so they don't read terabytes of JSON.
6. **The nine reference projects:** `python3 tools/ab/fetch_inputs.py` (into `input/github-repos/`, at their pinned
   commits; every project must print `OK`).
7. **Build and tests** (from `scripts/symbolic-module`): `mvn package -DskipTests`, then `mvn test`.

**Checkpoint A:** the volume is case-sensitive; `java -version` shows Corretto 21.0.6 for aarch64; `mvn test` passes
with no failures (report the count; `grep -r @Test src/test/java | wc -l` gave 559 on the server at `635a35a`);
`python3 -m unittest tools/gen/test_generate.py` passes (13 tests; on macOS `test_admission` and the memory tests may
fail until phase C, note which).

## B. Same results as the server (the regression corpus)

The datasets are the product: the Mac must produce **exactly** what the server produces, before anything else.

1. `python3 tools/ab/corpus.py prepare --jar target/tracto-jar-with-dependencies.jar -j 2` (the oracles stage of the
   corpus projects, `-Xmx6g` per JVM).
2. `python3 tools/ab/corpus.py check --jar target/tracto-jar-with-dependencies.jar -j 4` (about 12 minutes on the
   server with `-j 9`).

**Checkpoint B:** it prints `IDENTICAL to the expected results` and no `WARNING: the environment differs`. Record the
duration (it is the first speed calibration).

If it is not identical: do **not** accept. Possible platform causes to investigate, in this order: the environment
warning (JDK build, JDK sources, inputs); directory listing order (APFS and ext4 list directories in different orders:
look for `File.listFiles`, `Files.list`, `Files.walk`, `DirectoryStream` without sorting in the module); line
separators or file encoding (`file.encoding`, `Charset.defaultCharset()`); case sensitivity (point A.1). A difference
caused by the platform is a **reproducibility defect of the module**: fix it as a normal step (tests, doc, corpus) so
that Linux and macOS give the same results, and ask the author before accepting anything.

Done on 2026-11-23: 8 projects identical, uuid 32 oracles with another candidate order. The cause was the folder of
the module, not macOS: **Step 51** (`docs/fixes/step-51-imported-package-order.md`). The durations: `prepare` 224 s
(`-j 2`), `check` 316 s (`-j 4`, 1,853 s CPU). Step 51 accepted with the author's OK on 2026-11-24:
the expected results are those of its check on the Mac.

## C. Make the runner work on macOS

`tools/gen/generate.py` reads Linux-only files for its memory control: `memory_limit()` and `memory_used()` use the
cgroup files and `/proc/meminfo`. On macOS:

- limit: `sysctl -n hw.memsize`;
- memory in use that cannot be reclaimed: from `vm_stat` (pages *wired* + *active* + *occupied by compressor*, times
  the page size it prints); `memory_pressure` or `sysctl kern.memorystatus_level` (percentage of free memory) is an
  acceptable second signal.

Keep the Linux path unchanged; add tests with recorded `sysctl`/`vm_stat` outputs (tests first, failing before the
change). `max_rss_mb` already handles macOS (`ru_maxrss` is in bytes there). Check also that nothing else in
`generate.py` and `tools/deps/resolve_maven.py` assumes Linux (`/proc`, GNU-only options of `find`, `sed`, `date`, `du`).

**Checkpoint C:** all tests of `tools/gen/test_generate.py` pass on macOS (and still on Linux paths, with the recorded
files); a short end-to-end run on three reference projects works:

```shell
python3 tools/gen/generate.py run tmp/gen/e2e --jar target/tracto-jar-with-dependencies.jar --repos tmp/gen/e2e-repos.csv -j 3 --xmx 12g
python3 tools/gen/generate.py report tmp/gen/e2e
```

with `tmp/gen/e2e-repos.csv` (not in git, write it):

```csv
id,name,commit,codeLines
steve,steve-community/steve,ea62fe17daa2d3bafe236e131d9e68b828a17b1a,1
uuid,cowtowncoder/java-uuid-generator,3f2109438765a4e196b69bff6d14234e5bf33075,2
ffmpeg,bramp/ffmpeg-cli-wrapper,a6655138d7cc1b2c62a57a5e95798695e060de6e,3
```

Server results for this run (commit `ca4020b`, jar of dataset v4): steve 163 datapoints in 277 s, ffmpeg 586 in
2,272 s, uuid 369 in 2,771 s, all `done`.

Done on 2026-11-24. On macOS the limit is `sysctl -n hw.memsize` and the memory in use is Activity Monitor's "Memory
Used" from `vm_stat`: anonymous pages − purgeable + wired + occupied by compressor. Not `Pages active` as suggested
above: it counts the page cache too, which the Linux measure leaves out. Reading 5.4 GB twice moved 5.4 GiB from free
to file-backed pages and left the measure unchanged. A measurement that fails (`vm_stat` is a process) is logged and
skipped: no repository starts or is stopped, and the watcher goes on. 25 tests (12 new: recorded `vm_stat`/`sysctl`
outputs, the Linux files in their formats, the machine itself, the watcher); the scheduling tests no longer depend on
the memory in use of the machine (they waited forever above 80%). Each part was mutation-checked. `resolve_maven.py`
runs only `mvn` and `java`, nothing Linux-only; its Lombok test needs `JAVA_HOME` on JDK 21 (the Mac's default JDK 11
fails it). End to end (jar of `35ac938`, 12:16–12:28): steve 163 datapoints in 105 s, ffmpeg 586 in 607 s, uuid 369
in 718 s, all `done` (2.6–3.9× faster than the server), tokens peak RSS as on the server (uuid 7,521 MB), 4.0 GB of
tokens output; the memory in use peaked at 41.7 GiB of 64 (the desktop applications used ~33 GiB before the run), no
swap.

## D. Remaining module step before the run: Step 52 (deterministic anonymous classes)

JavaParser names anonymous classes `Anonymous-<UUID.randomUUID()>` (`JavaParserAnonymousClassDeclaration`); the name
reaches the `identifier`/`signature` of declarations in the oracles and tokens datasets (seen in ffmpeg: 1 of 21
datapoints differed between two runs, 58 bytes). `tools/ab/corpus.py` masks it (`ANONYMOUS` pattern), so the corpus does
not see it, but the datasets are not byte-reproducible. Fix it as **Step 52** (Step 51 went to checkpoint B) with the
full step procedure (section 0):
a deterministic name (for example from the enclosing type and the position of the anonymous class in the file), tests
that fail before the fix, two runs of ffmpeg byte-identical after it.

**Checkpoint D:** Step 52 committed and pushed; the corpus check identical (after `accept` of the explained
differences, if the name is visible in the digests).

Done on 2026-11-25 (`docs/fixes/step-52-anonymous-class-names.md`). Only the constructor of an anonymous class of an
**interface** had the random name (it resolves to the default constructor of the anonymous class; an anonymous class of
a class resolves to the constructor of that class), in the invoked methods of the test prefixes, since the original
code (the jar of `7a583a06` writes them too). Named after the interface, as decided by the author:
`Anonymous-NutReaderListener()`; equal entries merged; since Step 53 (asked by the author) the qualified name,
`Anonymous-net.bramp.ffmpeg.nut.NutReaderListener()`. Step 54 (asked by the author before phase E): the members of an
anonymous class after a `var` of it (a lost target before). Found in Step 53: the oracles stage depends on identity
hash codes through JavaParser (`docs/resume.md`, section 6, item 10); measured before phase E: 1 of the 9,148
datapoints of the oracles stage, the tokens stage independent of them, no workaround. Found while measuring:
`tools/ab/ab.py` ran Maven and delombok on the `JAVA_HOME` of the shell (`setdefault`; on the Mac's JDK 11 delombok
cannot parse text blocks: the members generated by Lombok lost in steve, 49 datapoints); now on the JDK of the
pipeline, as `tools/gen/generate.py` (phase E) always did (steve run from a JDK 11 shell: identical to the corpus). The corpus check identical without the mask (no accept); two
runs of ffmpeg byte-identical (datasets and statistics, except the folder of the run in `test-stats`), and identical to
phase C once the random name is replaced.

## E. Calibration on the nine reference projects

Run the nine projects with the runner, as the large run will:

```shell
# tmp/gen/ref9-repos.csv: the nine rows of tools/ab/input-repos.csv as id,name,commit,codeLines (codeLines: 1..9)
python3 tools/gen/generate.py run tmp/gen/ref9 --jar target/tracto-jar-with-dependencies.jar --repos tmp/gen/ref9-repos.csv -j 4 --xmx 12g
python3 tools/gen/generate.py report tmp/gen/ref9
```

Watch the memory (Activity Monitor or `memory_pressure`) during the first hour; if it stays low, `-j 5` or `-j 6` can
be tried later. Compare with the server (dataset v4, Broadwell VM, 9 parallel jobs; the jar of Step 49, so the
datapoints may differ very slightly after Step 50):

| Project | Datapoints | Oracles wall s | Tokens wall s | Tokens CPU s | Tokens peak RSS MB | Tokens output |
|---|---|---|---|---|---|---|
| crawler | 732 | 99 | 2,662 | 4,103 | 6,432 | 3.2 GB |
| cryptofs | 903 | 78 | 2,640 | 3,061 | 5,246 | 2.4 GB |
| eec | 1,427 | 198 | 9,074 | 11,447 | 9,829 | 5.7 GB |
| esapi | 528 | 67 | 1,355 | 1,662 | 10,544 | 5.1 GB |
| ffmpeg | 586 | 138 | 2,241 | 3,104 | 5,802 | 1.3 GB |
| jsoup | 3,653 | 224 | 16,875 | 22,583 | 12,818 | 13 GB |
| plexus | 787 | 84 | 2,228 | 3,244 | 5,019 | 2.5 GB |
| steve | 163 | 31 | 274 | 297 | 3,166 | 0.2 GB |
| uuid | 369 | 68 | 2,738 | 4,415 | 7,512 | 2.7 GB |
| **total** | **9,148** | | | **53,917** | | **36 GB** |

Per datapoint on the server: oracles ~0.2 CPU s, tokens ~5.9 CPU s, tokens output ~3.9 MB (99.5% of it
`contextualInfos`). A job uses ~1.35 cores, so the number of parallel jobs is limited by memory, not by cores.

**Checkpoint E:** all nine `done`; the datapoint counts match the table (or the differences are explained by Step 50);
the speed ratio Mac/server per project; the estimate of the large run recomputed (section H) and reported to the
author.

Done on 2026-11-26 (00:22–01:39, `tmp/gen/ref9`, log `tmp/gen/ref9.log`; jar `tmp/gen/ref9.jar`, the code of `0190a87a`
= Step 54, sha256 `8939c667…`, same classes as `tmp/ab/jar-step54.jar`; `-j 4 --xmx 12g`, in `tmux` with
`caffeinate -dims`, the desktop applications closed: 15.7 GiB in use before the run). All nine `done`, **9,148
datapoints, every count as on the server**; 76 min of wall time (jsoup alone the last 34 min); 4.9 CPU hours.

| Project | Oracles wall s | Tokens wall s | Tokens CPU s | Server/Mac tokens CPU | Tokens peak RSS MB | Tokens output GiB |
|---|---|---|---|---|---|---|
| crawler | 22 | 732 | 1,302 | 3.2× | 6,633 | 3.15 |
| cryptofs | 15 | 726 | 1,208 | 2.5× | 5,097 | 2.35 |
| eec | 51 | 2,464 | 3,658 | 3.1× | 10,233 | 5.66 |
| esapi | 14 | 393 | 777 | 2.1× | 11,214 | 4.99 |
| ffmpeg | 34 | 631 | 952 | 3.3× | 5,841 | 1.21 |
| jsoup | 62 | 4,515 | 6,479 | 3.5× | 12,646 | 12.39 |
| plexus | 17 | 593 | 1,071 | 3.0× | 5,093 | 2.46 |
| steve | 8 | 78 | 156 | 1.9× | 1,994 | 0.17 |
| uuid | 13 | 754 | 1,420 | 3.1× | 7,510 | 2.64 |
| **total** | | | **17,023** | **3.2×** | | **35.0** |

- Per datapoint: tokens 1.86 CPU s (server 5.9), oracles 0.075 CPU s; 1.22 s of a job's wall time; output 3.9 MiB (as
  on the server; the total 35.0 GiB against 36, not analysed per project). A tokens job uses 1.4–2.0 cores: the 4 jobs
  use ~6 of the 18 cores of the M5 Pro (6 performance, 12 efficiency); the parallelism is limited by memory (14 GiB
  reserved per job: a fifth job does not fit in 64 GiB), not by the cores.
- Memory (sampled every 30 s, `tmp/gen/ref9-memory.tsv`): peak 46.0 GiB in use of 64 (4 jobs, at 00:41), the lowest
  free level 87%, no swap. Peak RSS per project as on the server (steve lower).
- Reproducibility: the oracles datasets of the nine projects are identical to the corpus run of Step 54
  (`tmp/ab/corpus-src`, made by `tools/ab/ab.py` from `input/github-repos`; the runner clones from GitHub). The
  tokens datasets of steve and uuid are byte-identical to phase C (jar of Step 51), and ffmpeg to the run of Step 52
  (`tmp/gen/step52-a`) once `Anonymous-NutReaderListener` is renamed as Step 53 did.
- Compression of the tokens outputs (for F.1; each file read once, nothing written): **zstd -3: 52.8×** (38× eec to
  143× esapi; 37.6 GB → 0.71 GB; under 1 s per GB on one thread), gzip -6: 8.0× (its 32 KB window cannot see the contextual information
  repeated from one token step to the next), zstd -19 --long=27: ffmpeg 1,295 MB → 0.67 MB, jsoup 13.3 GB → 4.2 MB
  in 118 s (4 threads; the readers then need `--long=27`), xz -6 on ffmpeg 311×. Round trips byte-identical (ffmpeg,
  sha256).

## F. Decisions of the author needed before the large run

Present them with the numbers of phase E; do not decide alone:

1. **Output size (blocking).** With the current format, ~3.9 MB per datapoint: 1–3 million datapoints need ~4–12 TB;
   the Mac has ~1.5 TB. Options:
   (a) the compact format of `docs/contextual-information.md` (a symbol table per file, the dynamic information as
   ids; ~20× smaller; a converter keeps the prompt scripts working) — a module change, done as steps with corpus checks;
   (b) compression of the finished outputs by the runner (gzip or zstd of each JSON file once the repository is `done`;
   no module change; >10× on this repetitive JSON; the readers must stream from the compressed files);
   (c) both. Measure the ratio on the phase E outputs before proposing.
2. **`contextualInfos`:** keep everything (compactly) or drop parts (open in `docs/contextual-information.md`).
3. **Prompt-side problems** (`scripts/prompt`, not the module): the `setupTeardownMethods` / `setupTearDownMethods`
   key mismatch in `EWashQwen.normalize_section`; the focal class constructors never read; `prompt_qwen.py` loads whole
   files with `json.load`.
4. **Timeouts:** oracles 2 h, tokens 12 h by default. They are wall-clock times, so the set of repositories that finish
   depends on the machine; propose a retry pass of the timeouts with doubled limits, and a **manifest** of the run
   (per repository: commit, status, jar sha256, configuration, JDK, the module commit) for reproducibility.
5. The go-ahead for `generate.py select` and for the large run.

## G. The large run

1. `python3 tools/gen/generate.py select` (with the author's go-ahead): writes `tmp/gen/maven-repos.csv` (Maven
   repositories with a `pom.xml` at the root of their pinned commit, smallest first). Resumable. Report the counts
   (Maven, not Maven, unknown).
2. The run, in `tmux`, with `caffeinate`:

   ```shell
   ulimit -n 65536
   python3 tools/gen/generate.py run tmp/gen/full --jar <jar of the final commit> -j <from phase E> --xmx 12g --min-free-disk-gb 100
   ```

   Keep a copy of the jar used (`tmp/gen/full/tracto.jar`) and note its sha256 and the commit in the manifest. The run
   is resumable: after an interruption (reboot, crash), the same command continues; the repositories without a status
   run again from the start.
3. Check every day: `python3 tools/gen/generate.py report tmp/gen/full` (by status and reason, datapoints, CPU hours,
   disk). Look at the new `failed` reasons and the timeouts; a systematic failure (same reason in many repositories)
   is reported to the author before it spreads.
4. At the end: `--retry-failed -j 1` for the `memory pressure` failures; the timeouts according to decision F.4.

## H. Estimate (to be redone with the numbers of phase E)

Made on the server, very rough: ~2,000 Maven repositories with JUnit tests (unknown until `select`), 1–3 million
datapoints, ~6–10 CPU s per datapoint for the tokens stage on the server (more for large projects: steve 1.8, jsoup 6.2,
eec 8.0) → ~2,000–6,000 CPU hours on the server; the M5 cores are expected to be ~2× faster per core (not measured),
with 4–5 parallel jobs in 64 GB → **about 2 to 5 weeks** of continuous running, dominated by the large repositories
and the 12-hour timeout. Disk: see F.1.

Redone on 2026-11-26 with phase E: 1.22 s of a job's wall time per datapoint and 4 jobs → **~3.5 days per million
datapoints** (1–3 million: ~4–11 days), if the repositories of the large run cost what the reference projects cost
per datapoint. Not measured by phase E, all adding to it: the clones and the first resolution of the dependencies of
each repository (here 0.5–7 s, Maven's local repository already filled), the repositories much larger than jsoup,
and the timeouts (each holds a job for up to 2 + 12 hours). So **about 1 to 3 weeks**. Disk: 3.9 MiB per datapoint
raw → 4–12 TB for 1–3 million datapoints (the Mac has 1.4 TiB free); with zstd -3, 53× smaller → ~80–240 GB.

## Notes from the server (things learned the hard way)

- Tokens-stage memory tracks the size of its output; the largest reference project (jsoup) peaks at ~13 GB with
  `-Xmx12g`. The runner reserves heap + 2 GiB per job.
- `tmp/` is not in git. The reference outputs of dataset v4 (`tmp/ab/v4`, 36 GB) stay on the server unless the author
  copied them; they are not needed (the corpus expected results in git are the reference).
- `tools/measure/` holds the expected-type measurement (2026-11-21); `MEASURE_SHOW_MISSED=1` lists the datapoints it
  cannot locate (a limitation of the measurement only, not of the dataset).
- macOS has `/usr/bin/time -l` (peak memory in bytes) and `unzip`, which the server lacked.
