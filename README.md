# RepairGent: results of the file-scoped and commit-scoped settings
The package holds the final results of both settings: the outcome of every repair attempt,
the tasks each attempt ran on, the scripts that produce the thesis tables and figures, and
the raw records of the public commit-scoped runs. The private subjects are anonymised; see
[Private subjects](#private-subjects).

## Contents

| Path | Contents |
|---|---|
| `data/file-scoped/public/` | File-scoped setting, public subjects: attempts, scenario summaries and definitions |
| `data/commit-scoped/public/` | Commit-scoped setting, public subjects: attempts, case summaries, definitions and raw records |
| `data/commit-scoped/private/` | Commit-scoped setting, private subjects: attempts and case summaries, anonymised |
| `analysis/` | Scripts that produce the tables and figures, and that derive the log-based attempt fields |
| `tables/` | The thesis tables as CSV and Markdown, plus `prose-numbers.csv` for numbers the text quotes |
| `figures/` | The nine data figures of the thesis, as PDF |
| `requirements.txt` | Python packages needed by the figure script |

How to read the names. *File-scoped* and *commit-scoped* are the two experimental settings.
*Public* and *private* say whether the subjects are open-source projects or private
repositories. The private repositories appear only in the commit-scoped setting, and each is
labelled P1 to P5.

Each setting folder has a `definitions/` folder. It holds the source definition of every scenario or case in that folder.

## The two settings

**File-scoped.** A scenario is one broken test file of a public project, taken from
the TaRBench benchmark. The file is rolled back in full, and a repair is checked against the
method under repair. Three large language models (GPT-4o, Gemini Flash-2.5 and Claude
Sonnet-4) make five attempts on each scenario. The 395 scenarios come from five public Java
projects: gson, neo4j-java-driver, druid, alluxio and xwiki-commons. The file-scoped setting keeps no raw
run records, so its folder holds the per-attempt results and the definitions of its scenarios.

**Commit-scoped.** A case is one software change. The source part of the change is
applied, every test file the change modified is rolled back, and the agent must make all
affected test targets pass. The model is GPT-4o, with five attempts per case. The 175 cases
are 134 public cases from the same five projects and 41 private cases from five further
projects, labelled P1 to P5.

A case is built only from test files the developer modified in the commit. A test file the
commit created or renamed has no earlier version to repair, so it is not a repair task. The
neo4j-java-driver subject takes its qualifying commits in commit-hash order, up to the
compute budget.

### Metrics

- **Successful repair.** The repaired test passes its test commands. Computed for every
  attempt.
- **Test-preserving repair** (commit-scoped). A successful attempt counts only if no test
  file it changed ends with fewer `@Test` methods than both the broken version and the
  developer's repair.
- **Perfect match.** The repaired test equals the developer's after comments are removed and
  spaces normalised. The commit-scoped comparison also ignores the order of imports, and it
  compares every changed test file in full. The file-scoped comparison covers the method
  under repair. Only successful attempts can match.
- **Assertion match.** The assertion statements equal the developer's. A perfect match is
  always an assertion match too. Only successful attempts can match.
- **CodeBLEU.** Similarity of the repair to the developer's version. Computed for every
  attempt. A case's value is its best attempt.
- **pass@k.** The share of cases repaired by at least one of their first k attempts.

Every rate uses the full denominator. Best-of-five rates are over all scenarios or cases,
and per-attempt rates are over all attempts. A rate among repaired cases only is labelled
"of repaired". Test-preserving, perfect and assertion match are evaluated only on successful
attempts, but they still count against all cases.

## Data

All files are UTF-8 CSV with a header row, unless stated otherwise. Booleans are written
`True` or `False`. Costs are US dollars.

No partial results are included. Every scenario has all five attempts for each of its three
models, and every case has all five of its attempts.

### `data/file-scoped/public/attempts.csv` (5,925 rows)

| Column | Meaning |
|---|---|
| `subject` | Public project: `gson`, `neo4j-java-driver`, `druid`, `alluxio` or `xwiki-commons` |
| `scenario` | Scenario number, the same as `file_scoped.scenario` in the definitions |
| `scenario_name` | The broken test that defines the scenario |
| `model` | `GPT-4o`, `Gemini Flash-2.5` or `Claude Sonnet-4` |
| `attempt` | Attempt number, 1 to 5 |
| `success` | The repair passed |
| `perfect_match` | The method matches the developer's (successful attempts only) |
| `assertion_match` | The assertions match the developer's (successful attempts only) |
| `codebleu` | CodeBLEU against the developer's repair |

### `data/file-scoped/public/scenarios.csv` (1,185 rows)

One row per scenario and model, summarising its five attempts.

| Column | Meaning |
|---|---|
| `subject`, `scenario`, `scenario_name`, `model` | As in `attempts.csv` |
| `success`, `perfect_match`, `assertion_match` | At least one of the five attempts had the outcome |
| `best_codebleu` | Highest CodeBLEU over the five attempts |
| `attempts_succeeded` | Number of successful attempts, 0 to 5 |
| `cost_per_attempt_usd` | Average cost of an attempt. The archived results record spend per scenario, so this is that total divided by five |

The archived spend is exactly zero for 31 of the 1,185 pairs, and 16 of those pairs contain
a successful attempt. The cost figures of the thesis include these zeros.

### `data/file-scoped/public/definitions/<project>.json`

One JSON list per public project, with the 395 scenarios of the final results: 88 for
alluxio, 97 for druid, 34 for gson, 82 for neo4j-java-driver and 94 for xwiki-commons. Each
entry is the source entry from the project's TaRBench-format dataset, unchanged, with one key
added at the end:

- `file_scoped.scenario`: the scenario number used in `scenarios.csv` and `attempts.csv`. It
  is the entry's position in the source dataset.
- `file_scoped.scenario_name`: the broken test, as `Class#method`.

The file-scoped setting read the first 101 entries of each dataset, and all 41 entries of gson, which has
only 41. Where a test appears more than once in that prefix, its last entry defines the
scenario. The scenario numbers are therefore not consecutive. The file holds only the entries
that define a scenario. The other fields of each entry are the TaRBench fields, kept as they
were in the source.

### `data/commit-scoped/public/attempts.csv` (670 rows) and `data/commit-scoped/private/attempts.csv` (205 rows)

The two files have the same columns. `visibility` says which file a row belongs to.

| Column | Meaning |
|---|---|
| `visibility` | `public` or `private` |
| `subject` | Public project name, or `P1` to `P5` |
| `case_id` | Subject and the case's position in the subject's dataset, for example `gson-01` |
| `attempt` | Attempt number, 1 to 5 |
| `model` | Always `GPT-4o` in this setting |
| `success` | The repair passed |
| `test_preserving` | Test-preserving repair (see Metrics) |
| `perfect_match`, `assertion_match`, `codebleu` | As in the file-scoped data |
| `cost_usd` | Spend on the attempt, as reported for that attempt |
| `final_state` | Failed attempts only: the category of the final test run. Empty for successful attempts |
| `final_state_detail` | Failed attempts only: the finer label behind `final_state` |
| `run_tests_calls` | Number of test runs the agent requested |
| `last_edit_untested` | The attempt ended after an edit that no later test run tested |
| `never_ran_tests` | No test command ran during the attempt |
| `context_limit_stop` | The model's context limit ended the attempt |
| `feedback_traceable` | A successful attempt whose test runs match its recorded test results one to one |
| `feedback_revising` | In a traceable successful attempt, some test run after an edit failed |

The log-based columns (`final_state` through `feedback_revising`) are produced by
`analysis/derive_behaviour.py`; see [Re-deriving from the raw records](#re-deriving-from-the-raw-records).

### `data/commit-scoped/public/cases.csv` (134 rows) and `data/commit-scoped/private/cases.csv` (41 rows)

| Column | Meaning |
|---|---|
| `visibility`, `subject`, `case_id` | As in `attempts.csv` |
| `instance_id` | Public cases only: the case identifier of its dataset. Empty for private cases |
| `created` | Public cases only: date of the commit. Empty for private cases |
| `post_cutoff` | The commit is on or after 1 November 2023, the boundary the thesis uses for the training cutoff |
| `build_tool` | `Gradle` or `Maven`, from the case's test commands |
| `repair_targets` | Number of test classes that must pass |
| `repair_test_files` | Test files of the repair targets that the developer's change edits |
| `repair_test_methods` | Test methods the developer's change modifies in those files. This count is partly heuristic, as the thesis's methodology explains |
| `repair_edit_sites` | Diff hunks in those files |
| `repair_test_lines` | Lines added and removed in those files |

### `data/commit-scoped/public/definitions/<project>.json`

The definitions of the public cases, one file per subject (`gson.json`, `neo4j-java-driver.json`, and
so on), with the cases of this package only. Each entry has the usual fields: the repository
and base commit, `patch` (the source change applied before the agent starts), `test_patch`
(the developer's test change, used only for scoring), `problem_statement` and `hints_text`
(never shown to the agent), `FAIL_TO_PASS` (the repair targets), the test commands, the setup
scripts, and the repair-size fields.

## Raw records (public, commit-scoped only)

The raw records of the public commit-scoped attempts are in
`data/commit-scoped/public/raw/<subject>/`. For each subject there are two archives:

- `evaluation-results.tar.gz`: one metrics file per attempt, together with the attempt's
  test files and the developer's originals. The metrics files record paths relative to the
  `evaluation` folder.
- `evaluation-logs.tar.gz`: the agent log of each attempt.

Both archives unpack into an `evaluation` folder. The file-scoped setting has no raw records,
and the raw records of the private subjects are not included.

## Re-deriving from the raw records

`analysis/derive_behaviour.py` derives the log-based columns and `test_preserving` for one
run folder. Name each run folder after its subject, so the script finds its definitions:

```bash
mkdir -p runs/gson
tar -xzf data/commit-scoped/public/raw/gson/evaluation-results.tar.gz -C runs/gson
tar -xzf data/commit-scoped/public/raw/gson/evaluation-logs.tar.gz -C runs/gson
python3 analysis/derive_behaviour.py --datasets data/commit-scoped/public/definitions \
    --out gson-derived.csv runs/gson
```

Repeat for every public subject. For the 670 public attempts, the output reproduces the
derived columns of `data/commit-scoped/public/attempts.csv` exactly.

The outcome columns (`success`, `perfect_match`, `assertion_match`, `codebleu`) and
`cost_usd` come from the collector of the RepairGent repository, which is not part of this
package. For each public attempt, `success` and `cost_usd` agree with the `success` and
`priceDelta` fields of its metrics file. The metrics field `price` is not the cost of the
attempt, and the package does not use it.

## Reproducing the tables and figures

```bash
python3 -m pip install -r requirements.txt
python3 analysis/tables.py      # writes tables/
python3 analysis/figures.py     # writes figures/
```

The scripts were tested with Python 3.10. `analysis/tables.py` uses only the standard library.
`analysis/figures.py` needs matplotlib and numpy, and the figures are set in Arial. Where
Arial is missing, matplotlib falls back to Helvetica or DejaVu Sans, which changes the
glyphs. The figures were produced with matplotlib 3.10.3 and numpy 2.2.6.

The PDFs match the thesis's figures in every drawn element: page content, fonts and text
are identical. The only difference is the PDF creation-date entry, which `figures.py` leaves
out so that running the script again produces the same bytes.

## Thesis tables and figures

| Thesis | Where | Package file |
|---|---|---|
| `tab:fs-models` | RQ1, file-scoped results by model | `tables/tab-fs-models.csv` |
| `fig:fs-projects` | RQ1, success by subject | `figures/fs_success_by_subject.pdf` |
| `fig:fs-similarity` | RQ1, perfect and assertion match by subject | `figures/fs_perfect_by_subject.pdf`, `figures/fs_assertion_by_subject.pdf` |
| `fig:fs-stability` | RQ1, outcome over the five attempts | `figures/fs_violins.pdf` |
| `fig:fs-cost` | RQ1, cost per attempt | `figures/fs_violin_cost.pdf` |
| `tab:repos` | Methodology, subjects | `tables/tab-repos.csv` |
| `tab:repair-size` | Methodology, repair size | `tables/tab-repair-size.csv` |
| `tab:headline` | Results, commit-scoped headline | `tables/tab-headline.csv` |
| `tab:settings` | RQ2, file-scoped against commit-scoped | `tables/tab-settings.csv` |
| `tab:scope`, `tab:scope-groups` | RQ2, repair size | `tables/tab-scope.csv`, `tables/tab-scope-groups.csv` |
| `fig:cs-subjects` | Results, commit-scoped by subject | `figures/cs_by_subject.pdf` |
| `tab:perproject` | Results, commit-scoped by subject | `tables/tab-perproject.csv` |
| `fig:cs-stability` | Results, outcome over the five attempts | `figures/cs_violin_outcomes.pdf` |
| `fig:cs-passk` | Results, pass@k | `figures/cs_pass_at_k.pdf` |
| `fig:cs-cost` | Results, cost per attempt | `figures/cs_violin_cost.pdf` |
| `tab:visibility`, `tab:visibility-cutoff` | RQ3, public against private | `tables/tab-visibility.csv`, `tables/tab-visibility-cutoff.csv` |
| `tab:failmodes` | RQ4, failed attempts by final state | `tables/tab-failmodes.csv` |
| `tab:agent-behavior` | RQ4, behaviour of the agent | `tables/tab-agent-behavior.csv` |

The numbers the text quotes outside these tables are in `tables/prose-numbers.csv`, with the
population each one uses. The schematic figures, the case-field table and the tools table
describe the method and carry no results, so they are not in the package.

One statement of the Results text cannot be reproduced from this package. It says how many
of the 57 one-file, one-method cases come from a commit that also yields a file-scoped
scenario. The file-scoped data has no commit identifiers, so that count needs them.

## Private subjects

The five private subjects are labelled P1 to P5, and they appear only in the commit-scoped
setting. The folder `data/commit-scoped/private/` holds two files, the attempts and the case
summaries. They contain:

- the outcomes of every attempt: success, test-preserving repair, perfect and assertion
  match, CodeBLEU, cost and the log-based columns;
- the case sizes: repair targets, test files, test methods, diff hunks and changed lines,
  the build tool, and whether the case postdates the cutoff.

They do not contain: the names of the projects, owners or repositories; commit hashes,
instance identifiers or dates; test class or method names, test commands or file paths;
source code, patches, problem statements or hints; agent conversations, logs or generated
code; the definitions or raw records of the private cases; or any mapping from P1 to P5
to a project.

The private subjects are Kotlin projects, built with Gradle, except for three cases in P4,
which use Maven.