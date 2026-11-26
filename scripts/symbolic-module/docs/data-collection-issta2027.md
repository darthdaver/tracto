# Data collection for the ISSTA 2027 experiments

Design discussed with the author on 2026-11-26/27, before the large run of `docs/mac-generation.md` (section G). It
replaces the plan of running the 4,517 repositories of `resources/seart-github-repos.csv` as they are. Status: draft;
steps 3 and 4 done; the criteria of the split (step 8) are being agreed with the author.

## Intent

Produce the two datasets of the symbolic module (oracles, tokens) for two uses, with no data leakage between them and
none from the pretraining of the models:

- **fine-tuning** the neural module (Tracto, token by token) and the whole-oracle baseline;
- **the experiments** of the ISSTA 2027 paper (deadline **2027-01-11**): vanilla model, whole-oracle fine-tuned model,
  Tracto, all on the same base model and evaluated on the same oracles.

Time is the limit, not the GPUs: the fine-tuning should start by ~2026-12-11, the experiments end by ~2027-01-03.

## Decisions of the author

1. **Models: Qwen3.5** (dense 0.8B, 2B, 4B, 9B released 2026-03-02; 27B released 2026-02-24; Apache 2.0). Main size
   **9B**; 2B and 27B added if the pilot on Clariden shows they fit the calendar. OLMo and Apertus come later, in
   other experiments (Apertus is requested by the foundation funding the project).
2. **Leakage date T = 2026-03-02**: the latest release date of the Qwen3.5 models used. Alibaba does not publish a
   training cutoff; the release date is a safe upper bound. OLMo 3 (Nov 2025) and Apertus 1.0 (Sep 2025) are older, so
   the same experiment set is leakage-free for them; Apertus 1.5 (2026-07-24) would need the tests added after its
   own release.
3. **Fair comparison**: same base model and same data for every approach. The whole-oracle fine-tuned baseline is the
   approach of TOGLL (ICSE 2025: fine-tune the model to write the assertion) applied to Qwen3.5. The models of TOGLL
   (110M–2.7B, 2021–2023) are not reused.
4. **The experiment projects are excluded entirely from the fine-tuning.** Their targets are the test cases added
   after T.
5. **Licences: permissive only**, because the datasets and the models will be published.
6. Output (section F of `docs/mac-generation.md`): zstd compression by the runner, all `contextualInfos` kept,
   default timeouts with a retry pass at doubled limits, a manifest of the run.
7. Budget: 6,152 CHF ≈ 9,150 GPU-hours on Clariden (GH200, billed per node: 4 GPU-hours per node-hour, whole nodes).
   The prompt-side fixes are done in the prompt template step (`docs/prompt-template-todo.md`).
8. (2026-11-26) **Only the new SEART export** is used: the paper describes one selection, and the old list was only a
   list. The old files stay in `resources/` and are not read.
9. (2026-11-26) **The nine reference projects are treated like any other project**: the symbolic module is
   deterministic and must work on every project; a bug it has is an error of the development, not a limit of the
   approach, and can be investigated later.
10. (2026-11-26) **The repositories created after T go to the experiment set only** (the author's search:
    `resources/repositories-after-cutoff.csv`, 46 repositories created from 2026-03-16, all already in the export).
11. (2026-11-26) The split follows **explicit criteria** that make the experiment set representative (sizes and other
    dimensions), with seeded random choice inside them.

## Goals, non-goals, anti-goals

- Goals: an experiment set of ≥ ~50 projects and ≥ ~2,000 target oracles, all added after T, in projects that compile
  and run their tests (RQ2 executes the generated oracles); a varied fine-tuning pool of ~500,000 oracles or more; a
  validation set of projects for early stopping; everything reproducible (pinned commits, seeds, manifest).
- Non-goals: generating every repository of the list (one fine-tuning run uses ~2 billion tokens, ~500,000 examples);
  the compact format of `docs/contextual-information.md`; any change of the symbolic module (the jar of Step 54 is
  used as is).
- Anti-goals (a failure even if everything runs): an experiment test, or a copy of it, in the fine-tuning data; a test
  counted as "added after T" that only moved or was renamed; a target lost without being reported; missing the
  deadline because of the generation.

## Constraints

The rules of section 0 of `docs/mac-generation.md` hold: Maven projects only; never lose a target; deterministic order
and byte-identical outputs for the same inputs; read datasets streaming; one large run at a time; HTTP requests with
the User-Agent `tracto-dataset-generation` and no personal data. The author's go-ahead is needed before `select`,
before cloning repositories for the mining, before the large run, and before deleting any data.

## Approaches considered

- **Where to generate.** Chosen: the Mac, with a time limit (about 7 days; at the phase E rate ~290,000 datapoints a
  day, fewer with clones and dependency downloads). Rejected: the whole list on the Mac (1–3 weeks, does not fit the
  calendar); Clariden CPUs (faster, but a new platform needing its own corpus check on Linux ARM, bulk downloads from
  Alps are discouraged by CSCS, and CPU work there is billed at the GPU price).
- **Which list.** Chosen by the author: the fresh SEART export only (one selection to describe in the paper).
  Rejected: the old list only (its activity window ends on 2025-05-04, so it cannot give tests after T); the old list ∪
  the fresh export (624 more inactive repositories for the fine-tuning, but two selections to justify).
- **Which models.** Chosen: the newest family with a range of dense sizes and a leakage date that leaves ~9 months of
  tests (Qwen3.5). Rejected: the newest models (Qwen3.8-27B, mid-August 2026, would leave ~3 months of tests; GLM-5.x
  and DeepSeek V4 are mixtures of hundreds of billions of parameters, too large to fine-tune here); the models of
  TOGLL (too old: every approach would be weak).

## Steps

### Prepare (no external requests)

1. **Fix the date of the miner** (`scripts/miner`), test first. `mine.sh` passes `--since "01/08/2024"` and `mine.py`
   parses `%m/%d/%Y`: it means 8 January 2024 (the mollie output in the repository contains a commit of 2024-03-26).
   Use an unambiguous ISO date (`2026-03-02`) and a test that fails on the old parsing.
2. **The runner** (`tools/gen/generate.py`), tests first: zstd compression of every JSON output of a repository once it
   is `done` (decision F.1; round-trip checked); the manifest of the run (per repository: commit, status, jar sha256,
   configuration, JDK, module commit; for the run: SEART export date and query, T, seeds); the retry pass of the
   timeouts with doubled limits (F.4). The runner already processes `--repos` in the order of the CSV and can be
   stopped and resumed.
3. **The list** (done 2026-11-26: `tools/gen/repo_list.py`, tests `tools/gen/test_repo_list.py`): reads the SEART
   export, drops the forks and the licences that are not permissive; a licence string in neither list stops the
   script. Permissive: Apache 2.0, MIT, MIT No Attribution, BSD 0/2/3/4-Clause, BSD 3-Clause Clear, ISC, The Unlicense,
   CC0 1.0, Universal Permissive License, zlib, Boost Software License 1.0, University of Illinois/NCSA, PostgreSQL,
   Academic Free License 3.0, Educational Community License 2.0, WTFPL, Artistic 2.0, Mulan Permissive 2. Not
   permissive: no licence, "Other", GPL/LGPL/AGPL, EPL, MPL, EUPL, OSL, CeCILL, CC BY(-SA). Writes
   **`resources/issta2027-repositories.csv`** (the official list: id, name, lastCommitSHA, lastCommit, codeLines,
   javaCodeLines (the Java entry of SEART's `metrics`), license, createdAt, isArchived, commits, contributors,
   stargazers, totalIssues), sorted by id, byte-identical when run again. Counts: 5,095 rows, 212 forks and 2,060 not
   permissive dropped, **2,823 kept**.

### Collect (each step waits for the author's go-ahead)

4. **SEART export** (the author, done 2026-11-26): `resources/seart-github-repos-updated.csv`, 5,095 repositories,
   main language Java. Observed in the file: at least 103 commits, 10 contributors, 10 stars, 50 issues; last commit
   from 2026-03-15 to 2026-11-05; 212 forks (the old list had none: dropped by step 3). The exact query (the dates of
   the last-commit filter) is to be written in the manifest. Created after T: 50 repositories that are not forks, 28
   of them permissive, **19 Maven** (checked on 2026-11-26 with the request of `select`; 18 of the author's list and
   `reshaprio/reshapr`, created on 2026-03-04, before the start of the author's search). `matevip/mateclaw` and
   `mateaix/mateclaw` look like the same project under two owners.
5. **`generate.py select`** on the official list (go-ahead 2026-11-26): one request per repository to
   raw.githubusercontent.com; keeps the repositories with a `pom.xml` at the root of their pinned commit. Output in
   `tmp/gen/issta2027/maven-repos.csv`. Done 2026-11-26 in 2 minutes: **1,572 Maven**, 1,251 not, 0 unknown; the 19
   repositories created after T are among them. Size terciles of the Maven list: 13,207 and 50,647 Java lines.
6. **The walk** (choosing the experiment and validation projects; go-ahead for the clones 2026-11-26, ~300–400
   repositories): the Maven repositories are split into three size groups by `javaCodeLines` (terciles of the Maven
   list), each in a seeded random order. Going down each order, a candidate is cloned with its history since T at the
   pinned commit (`git clone --shallow-since`, then one more commit, so the state at T is known) and checked:
   - test methods (`@Test`, `@ParameterizedTest`) added by commits whose author and committer dates are after T, still
     present at the pinned commit, whose normalized body did not exist in the repository at T (moved, renamed or
     copied tests are dropped); at least 10 oracles in them;
   - it builds and those tests pass at the pinned commit with one of the JDKs 8, 11, 17, 21, 25, 26, 27 (Corretto,
     SDKMAN), the one matching the Java version the project declares first (JDK 24 is not needed: 25 builds it);
   - its JUnit version: the one of most of its target test classes (JUnit 4, or Jupiter = JUnit 5/6); each oracle is
     labelled too.
   An eligible candidate fills its cell (size × JUnit version) until the cell has **20 projects**; then 5 more per
   cell for validation. Every candidate and the reason it was refused are written to a CSV. A cell that is not full
   by the selection deadline is reported, never filled from another cell.
7. **The repositories created after T** (decision 10): all go to the experiment set when they meet the same checks;
   `matevip/mateclaw` and `mateaix/mateclaw` count once.
8. **Split** (decided 2026-11-26; seeded, written to CSV files and to the manifest):
   - experiment projects: 3 size groups × 2 JUnit versions × 20 = **120**, plus the eligible repositories created
     after T (at most 19), reported also as their own group; **at most 50 oracles per project** (seeded sample);
     results per oracle and per project, the overall figure weighted by the real share of each group in the list;
     a table compares the experiment set with the whole list (JUnit version, assertion style, Java version, age,
     stars). AssertJ chains are out of the module's scope (Step 30): a scope limit to state in the paper;
   - validation projects: 5 per cell (30), for early stopping;
   - fine-tuning projects: all the other Maven repositories;
   - similar projects on both sides: proposed on 2026-11-26, waiting for the author (see the conversation of that
     day): experiment and validation projects only from owners with a single repository in the list; a fine-tuning
     repository with the same repository name as an experiment project is dropped; the large run records a
     fingerprint of every Java file, and a fine-tuning repository sharing files with an experiment project is
     dropped.
9. **The large run**, in this order: experiment projects, validation projects, then the fine-tuning projects in a
   seeded random order balanced by size (so that what is done when the time limit stops the run is a fair sample, not
   only the small repositories). Time limit about 7 days; the timeouts are retried at the end if time allows.

### Post-process (streaming, on the Mac)

10. **Targets of the experiment projects**: keep the datapoints of the test methods added after T. The module splits
    each test method into one prefix per assertion: the test class `XSTARSplitTest` comes from the class `X`, the
    prefix `m_split_N` from the method `m`. Every mined test must map to its datapoints, or be reported with the
    reason (e.g. the module found no oracle in it); none is dropped silently.
11. **Duplicates**: drop from the fine-tuning data every datapoint whose normalized test prefix and target also appear
    in an experiment project, and report how many and from which repositories.
12. **Upload** the compressed datasets and the CSV files of the split to the CSCS store (default quota 1 TB; expected
    ~100–250 GB).

## Calendar

| Dates | Data (Mac) | Models (Clariden) |
|---|---|---|
| 27 Nov – 1 Dec | steps 1–5 | account, container, Qwen3.5 weights downloaded once |
| 1 – 4 Dec | steps 6–8; start step 9 | pilot on the nine reference projects (9,148 oracles): speed of 2B, 9B, 27B; training stack on GH200 (ARM) |
| 4 – 11 Dec | step 9 (time limit); steps 10–11; prompt templates and encoder | test of the Tracto inference loop (Java module and model server in the same job) |
| 11 – 20 Dec | step 12; encoding of the training examples | fine-tuning: whole-oracle and Tracto, 9B (+2B, 27B) |
| 18 Dec – 3 Jan | correctness runs (build the projects, run the oracles) | inference: vanilla, whole-oracle, Tracto |
| 4 – 11 Jan | writing | — |

## Risks

- New repositories bring failures not seen on the nine reference projects: the time limit contains them; a systematic
  failure is reported to the author before it spreads (section G of `docs/mac-generation.md`).
- Too few tests after T in projects that build: lower the per-project threshold, or keep the experiment projects
  growing (re-mine just before the experiments; the window keeps growing until then).
- The Tracto inference loop on Clariden: the compute nodes cannot be reached from outside, so the Java module and the
  model server run in the same job; the experiment projects are copied with their dependencies resolved on the Mac.
- Christmas: the queue and the CSCS support may be slower.

## Left to the builder

The normalization of test bodies (whitespace, comments), the exact number of validation projects, the per-project
threshold of new tests (set from the counts of step 6), how the steps are split into commits (each with tests, a
`docs/fixes` note when it changes the module tooling, and a row in `docs/resume.md`).
