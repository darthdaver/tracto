# Numbers and choices of the ISSTA 2027 datasets (for the paper)

The record of how the fine-tuning and experiment datasets of the ISSTA 2027 paper are built: every choice with its
reason, every number with the file or script it comes from. The plan of the work (steps, calendar, risks) is
`docs/data-collection-issta2027.md`; this file keeps only what the paper needs. It is updated at each step; a number
still to be measured is marked **(to fill)**.

Started on 2026-11-27. The leakage cutoff is written **T = 2026-03-02** throughout.

## 1. Models and leakage cutoff

- Base models: **Qwen3.5**, dense, Apache 2.0 (0.8B, 2B, 4B and 9B released on 2026-03-02; 27B released on
  2026-02-24). Main size 9B; 2B and 27B if the calendar allows. Chosen as the newest family with a range of dense
  sizes and a cutoff that leaves about nine months of new tests. Not chosen: newer models (Qwen3.8-27B, August 2026,
  would leave about three months of tests; GLM-5.x and DeepSeek V4 are mixtures of hundreds of billions of
  parameters, too large to fine-tune with our budget) and the models of TOGLL (ICSE 2025; 110M–2.7B parameters,
  2021–2023), too old for a meaningful comparison today.
- **Cutoff T = 2026-03-02**, the latest release date of the Qwen3.5 models we use. Alibaba does not publish a training
  cutoff, and the release date is a safe upper bound: nothing written after it can be in the pretraining data.
- The same experiment set is also free of leakage for OLMo 3 (November 2025) and Apertus 1.0 (September 2025), planned
  for later experiments.
- Approaches compared, all on the same base model and the same data: the vanilla model; the model fine-tuned to write
  the whole oracle (the approach of TOGLL applied to Qwen3.5); Tracto (fine-tuned to write the oracle token by token,
  guided by the symbolic module).
- Compute: CSCS Clariden (GH200), 6,152 CHF of credits, about 9,150 GPU-hours.

## 2. Source of the repositories

- **SEART GitHub Search** (https://seart-ghs.si.usi.ch/), export of **2026-11-26** (made by the author):
  `resources/seart-github-repos-updated.csv`, **5,095 repositories**.
- Criteria of the query: main language Java; at least 100 commits (observed minimum 103), at least 10 contributors, at
  least 10 stars, at least 50 issues; last commit after the cutoff (observed: from 2026-03-15 to 2026-11-05; the exact
  date range of the query: **(to fill, from the author)**).
- Each repository is pinned to the last commit SEART recorded (`lastCommitSHA`): every result is reproducible at that
  commit.
- Only this export is used. An earlier list (4,517 repositories, collected in 2025) is not used: its activity window
  ends before the cutoff, so it cannot give tests written after T.

## 3. From the export to the candidate repositories

| Filter | Removed | Left | Script |
|---|---|---|---|
| SEART export | | 5,095 | |
| Forks (a fork contains the tests of the repository it comes from) | 212 | 4,883 | `tools/gen/repo_list.py` |
| Licence not permissive (the datasets and models will be published) | 2,060 | **2,823** | `tools/gen/repo_list.py` |
| No `pom.xml` at the root of the pinned commit (the symbolic module and the experiments use Maven) | 1,251 | **1,572** | `tools/gen/generate.py select` |

- Permissive licences accepted: Apache 2.0, MIT, MIT No Attribution, BSD 0/2/3/4-Clause, BSD 3-Clause Clear, ISC, The
  Unlicense, CC0 1.0, Universal Permissive License 1.0, zlib, Boost 1.0, University of Illinois/NCSA, PostgreSQL,
  Academic Free License 3.0, Educational Community License 2.0, WTFPL, Artistic 2.0, Mulan Permissive 2.
- Not accepted: no licence (all rights reserved), "Other" (a licence GitHub does not recognize: unknown terms), GPL,
  LGPL, AGPL, EPL, MPL, EUPL, OSL, CeCILL, CC BY, CC BY-SA. A licence name in neither list stops the script.
- Licences of the 1,572 Maven repositories: Apache 2.0 1,149; MIT 337; BSD 3-Clause 40; BSD 2-Clause 22; The
  Unlicense 9; UPL 5; ECL 2; CC0 2; MIT-0 2; ISC 1; Artistic 1; Mulan 1; BSD 3-Clause Clear 1.
- The `select` check: 2,823 requests on 2026-11-26, no answer unknown.

### Descriptive statistics of the 1,572 Maven repositories

Minimum, first quartile, median, third quartile, maximum (`resources/issta2027-repositories.csv`).

| | min | Q1 | median | Q3 | max |
|---|---|---|---|---|---|
| Java lines of code (SEART `metrics`) | 0 | 9,166 | 27,012 | 74,794 | 15,323,786 |
| Commits | 104 | 600 | 1,249 | 2,746 | 111,459 |
| Contributors | 10 | 19 | 32 | 64 | 457 |
| Stars | 10 | 69 | 222 | 935 | 66,739 |
| Issues | 50 | 97 | 200 | 529 | 21,491 |
| Created | 2008-05-31 | 2014-05-16 | 2017-02-01 | 2019-12-12 | 2026-08-25 |
| Last commit (pinned) | 2026-03-16 | 2026-09-26 | 2026-10-28 | 2026-11-03 | 2026-11-05 |

885 owners (711 with a single Maven repository in the list; the largest: apache 126, jenkinsci 93, quarkiverse 26,
vert-x3 22, googleapis 20); 28 archived repositories.

## 4. Repositories created after the cutoff

- The author searched SEART for the repositories created after the cutoff with the same criteria:
  `resources/repositories-after-cutoff.csv`, 46 repositories created from 2026-03-16, all already in the export.
- In the export, 50 repositories that are not forks were created on or after T; 28 have a permissive licence; **19 are
  Maven** (18 of the author's search and `reshaprio/reshapr`, created on 2026-03-04, before the start of the search).
- Decision: they go to the experiment set only, reported also as their own group (the strongest evidence against
  leakage). `matevip/mateclaw` and `mateaix/mateclaw` appear to be the same project under two owners: counted once.
- A repository created after T can still contain older code (a project moved from elsewhere keeps its history): for
  them too, only the tests added by commits after T are targets.

## 5. The split: experiment, validation, fine-tuning

### Design (decided with the author on 2026-11-26/27)

- **Experiment set: 3 size groups × 2 JUnit versions × 20 projects = 120 projects**, plus the eligible repositories
  created after T (at most 19).
  - Size groups: the terciles of Java lines of the Maven list, **13,207 and 50,647** (524 repositories each).
  - JUnit version: JUnit 4, or Jupiter (JUnit 5 and 6, the same API). A project gets the version of most of its
    target test classes; each oracle is labelled too, because projects mix both.
  - Why JUnit version: the oracles differ (the message is the first argument in JUnit 4 and the last in Jupiter;
    `assertThrows` against `@Test(expected = ...)` or try/fail/catch; `assertAll`, `assertInstanceOf`,
    `assertIterableEquals` exist only in Jupiter; Hamcrest `assertThat` is used mostly with JUnit 4).
  - **At most 50 oracles per project** (a seeded sample), so that no project dominates (in an earlier evaluation of
    ours, one project out of 34 gave 16% of the oracles).
- **Validation set: 5 projects per group (30)**, chosen in the same way, for early stopping.
- **Fine-tuning set: all the other Maven repositories**, generated in a seeded random order balanced by size, within
  a time limit.
- Results reported per oracle and per project; the overall figure weights the two JUnit groups by their real share in
  the list (the 50/50 design over-represents JUnit 4 in tests written after T).

### Why 120 projects

The unit of evidence is the project (the oracles of one project are alike). With a paired comparison of two
approaches across projects (two-sided, α = 0.05, power 0.8), the smallest standardized difference that can be
detected is about 0.66 with 20 projects, 0.45 with 40, 0.37 with 60 and **0.26 with 120**. With 120 projects each size
group has 40 projects and each JUnit group 60, enough to see medium differences between groups. More projects add
per-project costs (cloning, building with several JDKs, running the tests) for less and less precision, within the
deadline.

### Eligibility of an experiment or validation project

1. At least 10 oracles in **test methods added after T**: methods (`@Test`, `@ParameterizedTest`) added by commits
   whose author and committer dates are both after T, still present at the pinned commit, and whose normalized body
   did not exist in the repository at T (moved, renamed or copied tests are not targets). An oracle whose test
   prefix and assertion, local variable names aside, already existed in a test of the repository at T is not a target
   either (a copy-paste of an older test).
2. It **builds and these tests pass** at the pinned commit, because the experiments run the generated oracles. JDKs
   tried: 8, 11, 17, 21, 25, 26, 27 (Amazon Corretto), the one matching the Java version the project declares first.
3. Its owner has a **single Maven repository** in the list (section 6).

### Similarity of the targets to the tests written before the cutoff

Decided on 2026-11-27. A new test that resembles the older tests of its project (same class, same pattern, other
values) is not leakage: its oracle differs, and this is the usual situation in which developers write tests (Tracto
even receives the other tests of the class as context). Only a copy is leakage, and copies are removed (eligibility
1). The resemblance is measured for every target, computed during the selection, when the state of the repository at
T is at hand:

- `similarity`: the largest Jaccard similarity of token multisets (local variable names normalized) between the target
  test method and a test method of the repository at T (the measure of Allamanis, *The Adverse Effects of Code
  Duplication in Machine Learning Models of Code*, Onward! 2019, who calls near-duplicates the pairs at 0.7 or more);
- `oraclesSeen`: whether the assertion alone (normalized) already appeared in a test at T (common assertions such as
  `assertEquals(0, list.size())` are expected);
- `duplicates`: the oracles removed as copies.

For the paper: the distribution of `similarity` and `oraclesSeen` over the targets; the results of each approach by
similarity range; a sensitivity analysis without the targets at 0.7 or more. **(to fill)**

The same measures between the targets and the fine-tuning data (other repositories) are computed after the
generation (section 6, check 4, extended to near-duplicates).

### Selection procedure

Each size group is put in a seeded random order. Going down each order, a candidate is cloned with its history since
T and checked; an eligible candidate fills its group (size × JUnit version) until it has 20 projects, then 5 more go
to validation. Every candidate and the reason it was refused are written to a CSV file. A group that cannot be
filled is reported as it is, never filled from another group.

Candidates (Maven, owner with a single Maven repository): small 196, medium 249, large 266.

Results **(to fill)**: seed; candidates examined per group; refused by reason; accepted per group; oracles per group.

## 6. No similar projects on both sides

The experiment projects are excluded entirely from the fine-tuning. Similar projects are kept apart by four checks:

1. **Owner**: experiment and validation projects only from owners with a single Maven repository in the list, so no
   repository of the same owner (which shares code, test helpers and style) is in the fine-tuning data. Measured
   alternative: one project per owner and the owner's other repositories removed from the fine-tuning would remove a
   median of 540 repositories (200 simulated selections; the large owners are picked often); the chosen rule removes
   none. The owners with a single repository are not less popular (median stars 263, against 177 for the others) and
   are spread across sizes. Exception: the repositories created after T of the same owner (`floci-io`,
   `openJiuwen-ai`: two each) all go to the experiment set.
2. **Repository name**: a fine-tuning repository with the same repository name as an experiment project is removed.
   Among the 2,823 repositories, 32 names are shared by different owners, several being the same project moved or
   copied (e.g. `linkedin/cruise-control` and `cruise-control-for-kafka/cruise-control`, `consensys/teku` and
   `Consensys-Incorporated/teku`, `facebook/soloader` and `meta/SoLoader`, `axonframework/axonframework` and
   `AxonIQ/AxonFramework`).
3. **Shared files**: the generation records a fingerprint of every Java file (after removing whitespace and comments);
   a fine-tuning repository sharing files with an experiment project is removed. **(to fill: threshold, repositories
   removed)**
4. **Duplicate datapoints**: a fine-tuning datapoint whose normalized test prefix and oracle also appear in an
   experiment project is removed. **(to fill: datapoints removed, from which repositories)**

Not guaranteed (threats to validity): a similar style between unrelated projects (same style guide, same template),
which has no measurable definition; edited copies of files, which exact fingerprints do not catch.

## 7. Scope of the oracles

- An oracle is the last assertion of a test prefix: an `assert` statement or a call to `assertAll`,
  `assertArrayEquals`, `assertDoesNotThrow`, `assertEquals`, `assertFalse`, `assertInstanceOf`, `assertIterableEquals`,
  `assertLinesMatch`, `assertNotEquals`, `assertNotNull`, `assertNotSame`, `assertNull`, `assertSame`, `assertThat`
  (JUnit 4 and Hamcrest), `assertThrows`, `assertThrowsExactly`, `assertTimeout`, `assertTimeoutPreemptively`,
  `assertTrue`, `fail` (`JUnitAssertionType`).
- Out of scope: AssertJ and Truth chains (`assertThat(x).isEqualTo(y)`); a project that uses mostly AssertJ has few
  oracles and fails eligibility 1. To state as a limit of the approach.
- The symbolic module splits each test method into one test prefix per assertion (`XSTARSplitTest`, `m_split_N`).

## 8. Generation of the datasets

- Symbolic module: the jar of Step 54 (commit `48914f43`; `tmp/gen/ref9.jar`, sha256 `8939c667...dca2e`), the
  configuration of dataset v4; JavaParser 3.28.2 at language level Java 26; JDK sources 8–27 for the type resolution.
- Calibration on nine reference projects (2026-11-26): 9,148 oracles datapoints, 76 minutes with 4 repositories at a
  time and a 12 GB heap; outputs compressed with zstd (level 3: 53×).
- Runner `tools/gen/generate.py run`: every JSON output compressed with zstd; all the contextual information kept;
  time limits of 2 hours (oracles stage) and 12 hours (tokens stage), the timeouts retried once with doubled limits; a
  manifest of the run (commit, status, jar, configuration, JDK per repository).
- Order: experiment projects, validation projects, then the fine-tuning repositories (seeded random order balanced by
  size), with a time limit of about 7 days for the fine-tuning part.
- Results **(to fill)**: repositories done, skipped, failed, timeout (by reason); datapoints and oracles per set;
  size of the datasets; time.

## 9. Reproducibility

| File | sha256 |
|---|---|
| `resources/seart-github-repos-updated.csv` (SEART export) | `e0b76081e30d719c452f21ec467fe89ceb0dae5eda5f71b250b68cc76cd92bf4` |
| `resources/issta2027-repositories.csv` (2,823 repositories) | `3023f5a28131e94867dfc459f921ea2f32b0d63c7f6c7b137ff090ea7b859be3` |
| `resources/issta2027-maven-repositories.csv` (1,572 Maven repositories) | `8f2a1e564869a1a44b502742c303d2de6c438c7b19907083923ad102729dcd66` |
| `resources/repositories-after-cutoff.csv` (the author's search) | `cd2579544be8f082093df6a30cd586708e126cf5fab17ff4b71796e7195f8506` |

`tools/gen/repo_list.py` writes the same list byte for byte from the export. Seeds of the selection and of the order
of the run: **(to fill)**.
