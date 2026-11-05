# RepairGent: replication package

This package accompanies the paper *Agentic Unit Test Repair*. It contains RepairGent, the
agent the paper evaluates, the task definitions of both experimental settings, the recorded
outcome of every repair attempt, and the scripts that compute the paper's tables and
figures. It is anonymised for double-anonymous review.

## Purpose

The package supports three uses, from cheapest to most expensive:

1. **Recompute the results from the recorded runs.** No API key and no Docker needed; about
   one minute. See [Usage 1](#1-recompute-the-tables-and-figures).
2. **Exercise the agent on one case.** One commit-scoped case, one attempt, with GPT-4o. Most
   of the time goes into building the case's Docker image (about five minutes on a 12-core
   machine); the attempt itself took about two minutes and USD 0.03 in the recorded runs. See
   [Usage 2](#2-smoke-test-one-case-one-attempt).
3. **Re-run whole settings.** Every public commit-scoped case or every file-scoped scenario,
   five attempts each. See [Usage 3](#3-re-run-the-commit-scoped-setting) and
   [Usage 4](#4-re-run-the-file-scoped-setting).

## Provenance

The package is distributed with the paper. After acceptance it will be archived with a DOI.

## Contents

| Path | Contents |
|---|---|
| `agent/` | RepairGent: Kotlin sources, Gradle build, tests, configuration and the public task definitions |
| `agent/dataset_swe/public/` | Commit-scoped cases of the five public subjects (134 cases) |
| `agent/dataset_test_repair/` | File-scoped source datasets of the five public subjects |
| `agent/scripts/docker/` | Scripts the agent uses to build and start a case's container |
| `agent/collect/` | Collectors that score the attempts of a run: success, perfect and assertion match, CodeBLEU |
| `results/` | Recorded outcomes of all 6,800 attempts, raw records of the 670 public commit-scoped attempts, analysis scripts, tables and figures; see `results/README.md` |
| `scripts/` | `reproduce_results.sh` (Usage 1) and `smoke_test.sh` (Usage 2) |
| `Dockerfile` | An image with everything the agent needs, as an alternative to a local JDK |
| `LICENSE` | Licence of the recorded results |

## Data

**Subjects.** Five open-source Java projects: Gson, Neo4j Java Driver, Druid, Alluxio and XWiki
Commons. A task names its repository and commits; the case's Docker build clones the public
repository at that commit. The subjects' code stays under the projects' own licences.

**File-scoped setting.** 395 scenarios, each one broken test method, collected from the five
projects with the collection scripts of the TaRBench benchmark. `agent/dataset_test_repair/`
holds the source datasets the agent reads, and `results/data/file-scoped/public/definitions/`
the entries that define the 395 scenarios.

**Commit-scoped setting.** 175 cases, each one commit whose existing test files the agent must
repair together: 134 from the public projects and 41 from five private projects (P1 to P5).
The public cases are in `agent/dataset_swe/public/`. The private projects' code, task
definitions and raw records cannot be shared; the package holds only their anonymised
per-attempt outcomes, so every table can still be recomputed.

**Size.** About 110 MB, most of it the compressed raw records of the public commit-scoped runs.

## Setup

**Hardware.** Usage 1 runs on any machine with Python. For the agent: a 64-bit Linux or macOS
machine with Docker, 16 GB of memory, and disk space for the case images. A case image
holds four JDKs, Maven, Gradle and the subject's checkout (2.3 GB for the Gson case of
Usage 2), and the images are kept after a case finishes: remove them with
`docker image prune` between subjects when disk space is short. The package was tested on
macOS with Docker Desktop, 12 cores and 48 GB of memory.

**Software.**

- Python 3.10 or newer; `results/requirements.txt` for the figures
- Docker 24 or newer, with the `buildx` plugin
- JDK 21 to build and run the agent (or the `Dockerfile` of this package instead)
- for the file-scoped setting only: Git, Maven 3.9 and a JDK that builds the subject

**Model access.** The agent calls the providers' APIs directly. Set the key for the model you run:

| Model | `LLM_MODEL` | Key |
|---|---|---|
| GPT-4o (both settings) | `gpt-4o` | `OPENAI_API_KEY` |
| Gemini 2.5 Flash (file-scoped) | `gemini-2.5-flash` | `GOOGLE_API_KEY` |
| Claude Sonnet 4 (file-scoped) | `claude-sonnet-4` | `ANTHROPIC_API_KEY` |

Copy `agent/.env.example` to `agent/.env` and fill in the key and `JAVA_HOME`, or export the
same variables in the shell; shell variables take precedence.

**Check the installation** (compiles the agent and runs its unit tests; no API key needed):

```bash
cd agent && ./gradlew test
```

## Usage

### 1. Recompute the tables and figures

```bash
python3 -m pip install -r results/requirements.txt
scripts/reproduce_results.sh
```

The script regenerates every table and figure into `results/reproduced/` and compares them
with the shipped ones in `results/tables/` and `results/figures/`. It reports each file as
identical or different. The tables need only the standard library; the figures need
matplotlib and numpy, and their fonts depend on the fonts installed (see `results/README.md`).

### 2. Smoke test: one case, one attempt

```bash
export OPENAI_API_KEY=...        # or put it in agent/.env
scripts/smoke_test.sh
```

This runs one attempt with GPT-4o on Gson case `gson-20` (dataset index 19). In the recorded
runs all five attempts on this case repaired it, at USD 0.03 per attempt on average. The
script builds the case's image, starts its container, runs the agent and writes the attempt to
`agent/agent_dir/evaluation/`:

- `evaluation/results/gson/<case>/gpt-4o/metrics_1.json`: the outcome, with `"success"`, the
  test runs and the token-based cost (`priceDelta`);
- `evaluation/results/gson/<case>/gpt-4o/attempt_1/`: the test files as the agent left them,
  next to the developer's versions in `original/`;
- `evaluation/logs/gson_<case>_1_gpt-4o_<instance>.log`: the attempt's full agent log, and
  `evaluation/logs/<run>/run.log`: the log of the run.

A different case or more attempts: `CASE_IDS=0,5-7 APP_MAX_ATTEMPTS=5 scripts/smoke_test.sh`.

### 3. Re-run the commit-scoped setting

```bash
cd agent
for pair in gson:gson neo4j:neo4j-java-driver druid:druid alluxio:alluxio xwiki:xwiki-commons; do
  dataset=${pair%%:*}; subject=${pair#*:}
  mkdir -p runs/$subject
  PROJECT_AGENT_DIR=runs/$subject SWE_DATASET=$dataset APP_EXECUTION_MODE=commit-scoped \
    ./gradlew run --console=plain
done
```

Each subject gets its own run folder, named as `results/analysis/derive_behaviour.py` expects.

Five attempts per case, 670 attempts in all. In the recorded runs these attempts took 29 hours
of agent time (median 1.6 minutes, longest 28 minutes), plus one image build per case, and all
875 commit-scoped attempts, public and private, cost USD 289.60. The outcome of an attempt
varies, so a re-run will not reproduce the recorded attempts one for one; the paper reports how
much they vary. `CASE_IDS` restricts a run to some cases, so a run can be split or continued.

To score a run, install the collector's dependencies and point it at the run's results:

```bash
python3 -m pip install -r agent/collect/requirements.txt
cd agent
python3 collect/collect_commit_scoped.py --results-root runs \
    --combined-results-csv attempts.csv --combined-cases-csv cases.csv
python3 ../results/analysis/derive_behaviour.py \
    --datasets ../results/data/commit-scoped/public/definitions --out derived.csv runs/*
```

`attempts.csv` has one row per attempt with its success, perfect match, assertion match,
CodeBLEU and cost, and `derived.csv` adds test-preserving repair and the log-based columns of
`results/README.md`.

### 4. Re-run the file-scoped setting

```bash
cd agent
APP_EXECUTION_MODE=file-scoped PROJECT_NAME=gson \
  PROJECT_LINK=https://github.com/google/gson.git LLM_MODEL=gpt-4o ./gradlew run --console=plain
```

`PROJECT_NAME` is one of `alluxio`, `druid`, `gson`, `neo4j-java-driver` and `xwiki-commons`,
with `PROJECT_LINK` its GitHub repository. This setting clones the subject into
`agent/agent_dir/` and runs its tests with the local Maven and `JAVA_HOME`, not in Docker. It
runs the first 101 entries of the subject's dataset, as the recorded runs did. To score the
attempts:

```bash
python3 collect/collect_file_scoped.py --evaluation-dir agent_dir/evaluation --projects gson \
    --llms gpt-4o --results-csv fs-attempts.csv --cases-csv fs-scenarios.csv
```

### Using the Docker image instead of a local JDK

```bash
docker build -t repairgent .
docker run --rm -it \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD/agent/agent_dir:/repairgent/agent/agent_dir" \
  -e OPENAI_API_KEY -e SWE_DATASET=gson -e CASE_IDS=19 -e APP_MAX_ATTEMPTS=1 \
  repairgent
```

The container starts the case containers through the host's Docker socket, and the results
appear in `agent/agent_dir/` on the host.

## Where the paper's results come from

| Paper | Package |
|---|---|
| Table II, subjects | `results/tables/tab-repos.csv` |
| Table III, file-scoped results by model | `results/tables/tab-fs-models.csv` |
| Table IV, commit-scoped results | `results/tables/tab-headline.csv` |
| Table V, repair size | `results/tables/tab-scope.csv` |
| Table VI, outcomes over the five attempts | `results/tables/tab-repeatability.csv` |
| Table VII, public and private subjects | `results/tables/tab-visibility.csv` |
| Table VIII, final states of failed attempts | `results/tables/tab-failmodes.csv` |
| Table IX, the two Gson attempts of the case study | `results/tables/tab-case-audit.csv` |
| Table X, public attempts under the raw-record screens | `results/tables/tab-public-screen.csv` |
| Figure 2, commit-scoped results by subject | `results/figures/cs_by_subject.pdf` |
| Figure 3, first-k outcomes | `results/figures/cs_pass_at_k.pdf` |
| Commits that are cases of both settings | `results/tables/tab-shared-changes.csv` |
| Every further number in the text | `results/tables/paper-numbers.csv` |

`paper-numbers.csv` lists each number the text reports, by section, with its definition:
the paired model test, the shared changes, the cost of a fixed number of attempts, the overlap
of the quality checks, the equal-weight rates by subject, the raw-record screens and the rest.
`results/analysis/tables.py` writes the tables in the first part of the list and
`results/analysis/paper_numbers.py` the others, from the data in `results/data/`; the
raw-record tables read the public archives. The per-attempt data is described in
`results/README.md`.

## Differences from the recorded runs

- **Model access.** The recorded runs reached the models through an API gateway. This package
  calls the providers' APIs with the same models. A provider may serve a newer snapshot under
  the same name, so outcomes can differ from the recorded ones.
- **Output limit.** GPT-4o accepts at most 16,384 output tokens per request, so the agent asks
  for that much; the recorded runs requested up to 64,000, which the gateway accepted.
- **Cost.** The agent computes cost from the token counts the provider reports and the list
  prices in `agent/src/main/kotlin/utils/AgentUtils.kt`. Override them with
  `LLM_INPUT_USD_PER_MILLION` and `LLM_OUTPUT_USD_PER_MILLION`. The recorded costs come from
  the gateway's account of each attempt.
- **Raw logs.** The shipped logs are anonymised. The gateway client's name, its request and
  error types, its stack frames, its tool-schema dumps, and the account and quota details it
  attached to responses are removed or renamed. Timestamps are in UTC, model and image names
  follow this package (`gpt-4o`, `repairgent-case-…`), and the case numbers in the harness's
  own log lines follow the case numbering of this package. The conversations, tool calls and
  test output are unchanged, and the derived columns reproduce exactly from the shipped logs.
- **Code comments** are removed from the shipped sources, and one mistyped character in the
  file-scoped prompt is corrected.

