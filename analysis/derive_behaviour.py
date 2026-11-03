#!/usr/bin/env python3
"""Derive the per-attempt behaviour fields of a RepairGent run tree, one CSV row per attempt.

Columns
-------
project             basename of the run folder given on the command line (the project label)
case_index          case id from the metrics file: the case's 0-based index in its dataset file
attempt             attempt number, 1 to 5
test_preserving     True if the attempt succeeded and no test file it changed loses @Test methods
final_state         failed attempts: the failure category; empty for a successful attempt
final_state_detail  failed attempts: the classifier label behind final_state; empty otherwise
run_tests_calls     number of run_tests calls, counted from the attempt's testRuns records
last_edit_untested  True if the attempt ends with a file edit that no later test run tested
never_ran_tests     True if the attempt has no testRuns record, so no test command ever ran
context_limit_stop  True if a harness failure line ends the attempt with the context limit
feedback_traceable  True for a successful attempt whose run_tests calls align with its testRuns
feedback_revising   True for a traceable successful attempt in which a test run after an edit failed

Inputs
------
RUN_FOLDER is one repository's run folder: evaluation/results/*/*/*/metrics_N.json, with each
attempt's files beside its metrics file, and evaluation/logs/*.log. DIR holds one dataset file
per run folder, named <basename of RUN_FOLDER>.json: a JSON list of case entries in dataset
order. Each case's test_patch is read from it.

Attempt logs contain code the model wrote, so they are never searched for error words. Only
the timestamped logger lines are read, and a test command's output is read only between its
marker and the next logger line.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

COLUMNS = [
    "project", "case_index", "attempt", "test_preserving", "final_state", "final_state_detail",
    "run_tests_calls", "last_edit_untested", "never_ran_tests", "context_limit_stop",
    "feedback_traceable", "feedback_revising",
]

# Failure category of a failed attempt, by the label of its last test run. Any other label
# (timeouts, network and checkstyle failures, and so on) is "other".
FINAL_STATES = {
    "test does not compile": {"test_compile_error", "main_compile_error", "werror_lint"},
    "assertion still fails": {"assertion_failure"},
    "test throws an exception": {"test_error_exception"},
    "tests never run": {"never_ran_tests"},
    "no test selected": {"no_tests_selected"},
    "license check fails": {"license"},
}
LABEL_TO_STATE = {label: state for state, labels in FINAL_STATES.items() for label in labels}
OTHER_STATE = "other"

# Build output is classified by the plugin or task that aborted the build.
MAVEN_GOAL = re.compile(
    r"Failed to execute goal (?:[\w.\-]+:)?([\w.\-]+?)(?:-plugin)?:[\w.\-]+:(\w+)[^\n]*?on project",
    re.M)
GRADLE_TASK = re.compile(r"Execution failed for task '(:[\w:\-]+)'")
GRADLE_FAILED_TASK = re.compile(r"^> Task (:[\w:\-]+) FAILED", re.M)
# Maven plugin artifact -> label. These plugins abort the build before any test executes.
MAVEN_PLUGIN_LABEL = {
    "maven-checkstyle": "checkstyle", "checkstyle": "checkstyle",
    "spotbugs": "static_analysis", "findbugs": "static_analysis",
    "maven-pmd": "static_analysis", "pmd": "static_analysis",
    "forbiddenapis": "static_analysis", "animal-sniffer-maven": "static_analysis",
    "maven-enforcer": "enforcer", "enforcer": "enforcer",
    "license-maven": "license", "apache-rat": "license",
    "maven-dependency": "dep_resolution",
    "revapi-maven": "api_check", "japicmp-maven": "api_check", "clirr-maven": "api_check",
}
# Gradle task-name fragment -> label, tried in this order.
GRADLE_TASK_LABEL = [
    ("ktlint", "checkstyle"), ("detekt", "static_analysis"), ("checkstyle", "checkstyle"),
    ("spotless", "checkstyle"), ("spotbugs", "static_analysis"), ("licens", "license"),
    ("apiCheck", "api_check"), ("apiDump", "api_check"),
]

# Attempt-log records. A timestamped line starts a record; a command marker is a logger line.
TIMESTAMP_START = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} ")
LOGGER_RECORD = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) +([A-Z]+) +\[([^\]]*)\] (\S+) - (.*)$")
COMMAND_MARK = re.compile(r"Logger - [^\[]*\[(F2P|P2P) (\d+)/(\d+)\] exit=(-?\d+) (.*)$")
TOOL_CALL = re.compile(r"Logger - Tool #(\d+) called: tool (\w+)")
HARNESS_LOGGER = "org.example.utils.Logger"
HARNESS_FAILURE = re.compile(r"^case=(\d+) attempt=(\d+) failed: ?(.*)$")
EXECUTED_TOOL = re.compile(r"Executing tool \(name: (\w+)")

# Test files: @Test methods are counted in whole files, and in a test patch's added and removed lines.
TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.M)
ADDED_TEST = re.compile(r"^\+\s*@Test\b")
REMOVED_TEST = re.compile(r"^-\s*@Test\b")


class InputError(Exception):
    """A run folder or dataset that cannot be read as the derivation expects."""


# --------------------------------------------------------------------- build-output labels
def verdict_of_test_run(text: str) -> str:
    """What a failed test run did: a failed assertion, an error, or no test was selected."""
    failures = errors = 0
    for found in re.finditer(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)", text):
        failures += int(found.group(2))
        errors += int(found.group(3))
    if failures:
        return "assertion_failure"
    if errors:
        return "test_error_exception"
    if re.search(r"expected:.*but was:|AssertionError|AssertionFailedError|ComparisonFailure|"
                 r"org\.opentest4j\.AssertionFailedError", text):
        return "assertion_failure"
    if re.search(r"No tests were executed|No tests found for given includes|"
                 r"No tests matching|no tests to run", text, re.I):
        return "no_tests_selected"
    return "test_error_exception"


def classify_output(text: str) -> str:
    """Label of one failing test command's output."""
    # Infrastructure first: these fail before any of the project's own gates.
    if re.search(r"Could not resolve all (files|dependencies) for configuration|"
                 r"Could not (transfer|resolve) artifact|Non-resolvable parent POM", text):
        return "dep_resolution"
    if re.search(r"Connection refused|UnknownHostException|Read timed out|"
                 r"Temporary failure in name resolution", text):
        return "network"
    if "No space left on device" in text:
        return "disk_full"
    if re.search(r"TIMEOUT|timed out after|Timeout waiting", text):
        return "timeout"

    goals = MAVEN_GOAL.findall(text)
    if goals:
        artifact, goal = goals[-1]
        if artifact in MAVEN_PLUGIN_LABEL:
            return MAVEN_PLUGIN_LABEL[artifact]
        if "surefire" in artifact or "failsafe" in artifact:
            return verdict_of_test_run(text)
        if "compiler" in artifact:
            if "-Werror specified" in text:  # -Werror turns a style warning into a failed build
                return "werror_lint"
            if goal.lower().startswith("testcompile"):
                return "test_compile_error"
            return "main_compile_error"

    task = None
    found = GRADLE_TASK.search(text) or GRADLE_FAILED_TASK.search(text)
    if found:
        task = found.group(1)
    for failed_task in GRADLE_FAILED_TASK.findall(text):
        task = failed_task  # the FAILED marker is more specific than the summary line
    if task:
        low = task.lower()
        for fragment, label in GRADLE_TASK_LABEL:
            if fragment.lower() in low:
                return label
        if "compiletest" in low:
            return "test_compile_error"
        if "compile" in low:
            return "main_compile_error"
        if low.endswith(":test") or "test" in low:
            return verdict_of_test_run(text)

    # Build-tool-agnostic markers.
    if "-Werror specified" in text:
        return "werror_lint"
    if re.search(r"reported by Checkstyle|checkstyle:check", text):
        return "checkstyle"
    if ("COMPILATION ERROR" in text or re.search(r"^e: file:", text, re.M)
            or "Compilation error. See log for more details" in text):
        return "test_compile_error"
    if re.search(r"Tests run:|There were failing tests|tests? completed.*failed", text):
        return verdict_of_test_run(text)
    if re.search(r"No tests were executed|No tests found for given includes|No tests matching", text):
        return "no_tests_selected"
    return "other"


def classify_harness_failure(message: str, block: list[str]) -> str:
    """Kind of a harness `failed:` line. Only its message, the last `Caused by:` line of its
    block and the block's first line are matched; the rest of the block (the dumped
    conversation) is not searched."""
    head = message.split(" and LLMChat(")[0][:400]
    causes = [line for line in block if line.startswith("Caused by: ")]
    probes = [head, causes[-1][:400] if causes else "", block[0][:400] if block else ""]
    for text in probes:
        if not text:
            continue
        if "stuck in node" in text and "Assistant(parts=[]" in text:
            return "empty model reply"
        if "maximum context length" in text or "context_length_exceeded" in text:
            return "context limit"
        if re.search(r"MaxNumberOfIterations|number of (?:iterations|steps)|Max iterations limit|"
                     r"maxAgentIterations", text):
            return "iteration limit"
        code = re.search(r"with code (\d{3})", text)
        if code:
            return f"HTTP {code.group(1)}"
        if "prematurely closed the connection" in text:
            return "connection closed early"
        if "Connection reset" in text:
            return "connection reset"
        if re.search(r"UnresolvedAddressException|UnknownHostException|Network is unreachable", text):
            return "network/dns"
        if re.search(r"timed out|TimeoutException|HttpRequestTimeout", text):
            return "timeout"
    return "other"


# --------------------------------------------------------------------- attempt logs
def read_test_runs(lines: list[str]) -> tuple[list[list[dict]], list[str]]:
    """The test-command runs of an attempt log, and the order of its edits and test runs.

    A command's output is the text between its "[F2P i/n] exit=" marker and the next
    timestamped line. A marker with i == 1 starts a new run. The order holds "edit" for each
    modify_file tool call and "run" for each run start.
    """
    runs: list[list[dict]] = []
    current: list[dict] = []
    order: list[str] = []
    command: dict | None = None
    output: list[str] = []
    for line in lines:
        if TIMESTAMP_START.match(line):
            if command is not None:
                command["output"] = "\n".join(output)
                current.append(command)
                command, output = None, []
            mark = COMMAND_MARK.search(line)
            if mark:
                kind, index, _total, code, _text = mark.groups()
                if int(index) == 1:
                    if current:
                        runs.append(current)
                        current = []
                    order.append("run")
                command = {"kind": kind, "exit": int(code)}
                continue
            call = TOOL_CALL.search(line)
            if call and call.group(2) == "modify_file":
                order.append("edit")
            continue
        if command is not None:
            output.append(line)
    if command is not None:
        command["output"] = "\n".join(output)
        current.append(command)
    if current:
        runs.append(current)
    return runs, order


def final_label(runs: list[list[dict]]) -> str:
    """Label of a failed attempt's final test run: why its last run did not pass."""
    if not runs:
        return "never_ran_tests"
    failing = [c for c in runs[-1] if c["kind"] == "F2P" and c["exit"] != 0]
    if not failing:
        return "passed_but_not_recorded"
    return Counter(classify_output(c["output"]) for c in failing).most_common(1)[0][0]


def read_log_records(lines: list[str], attempt: int) -> tuple[list[str], list[str]]:
    """Tool names of the executed-tool records, and the kinds of the harness failure lines that
    name this attempt, in log order. A record runs from its timestamped line to the next one."""
    starts = [(i, m) for i, line in enumerate(lines)
              if line[:2] == "20" and (m := LOGGER_RECORD.match(line))]
    starts.append((len(lines), None))
    tools: list[str] = []
    failures: list[str] = []
    for (i, m), (end, _) in zip(starts, starts[1:]):
        level, logger, message = m.group(2), m.group(4), m.group(5)
        failure = HARNESS_FAILURE.match(message) if logger == HARNESS_LOGGER else None
        if failure:
            if int(failure.group(2)) == attempt:
                failures.append(classify_harness_failure(failure.group(3), lines[i + 1:end]))
            continue
        if level == "ERROR":
            continue
        executed = EXECUTED_TOOL.search(message)
        if executed and message.startswith("(agent id:"):
            tools.append(executed.group(1))
    return tools, failures


def ends_on_untested_edit(order: list[str]) -> bool:
    """True if the last edit has no test run after it."""
    if "edit" not in order:
        return False
    last_edit = max(i for i, step in enumerate(order) if step == "edit")
    return "run" not in order[last_edit + 1:]


def feedback_alignment(tools: list[str], test_runs: list[dict]) -> tuple[bool, bool]:
    """(aligned, revising) for an attempt's executed-tool sequence.

    The sequence aligns with the testRuns records when both hold the same number of run_tests
    calls; the k-th run_tests call is then the k-th record. Revising means that some run_tests
    call after an edit failed.
    """
    if tools.count("run_tests") != len(test_runs):
        return False, False
    after_edit: list[bool] = []
    edited = False
    k = 0
    for tool in tools:
        if tool == "modify_file":
            edited = True
        elif tool == "run_tests":
            if edited:
                after_edit.append(bool(test_runs[k].get("allPassed")))
            k += 1
    return True, not all(after_edit)


def resolve_log(run_dir: Path, recorded: str | None) -> Path | None:
    """The attempt log a metrics file names: at its recorded path, else under evaluation/logs."""
    if not recorded:
        return None
    for candidate in (run_dir / recorded, run_dir / "evaluation" / "logs" / Path(recorded).name):
        if candidate.is_file():
            return candidate
    return None


# --------------------------------------------------------------------- test preservation
def count_test_methods(text: str) -> int:
    return len(TEST_ANNOTATION.findall(text))


def test_patch_method_delta(test_patch: str, path: str) -> tuple[int, int]:
    """@Test lines that the developer's test patch adds to, and removes from, one file."""
    added = removed = 0
    current = None
    for line in test_patch.splitlines():
        if line.startswith("diff --git"):
            current = line.split(" b/")[-1].strip()
        elif current == path and not line.startswith(("+++", "---")):
            if ADDED_TEST.match(line):
                added += 1
            elif REMOVED_TEST.match(line):
                removed += 1
    return added, removed


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


@dataclass
class ChangedTestFile:
    """A test file the attempt changed and the developer's test patch also changes."""
    path: str
    attempt_tests: int      # @Test methods in the attempt's version
    reference_tests: int    # @Test methods in the developer's repair
    broken_tests: int       # reference - added + removed by the test patch (the broken version)

    @property
    def fails_rule(self) -> bool:
        """Fewer @Test methods than both the broken and the developer's version."""
        return self.attempt_tests < self.reference_tests and self.attempt_tests < self.broken_tests



def changed_test_files(record: dict, metrics_dir: Path, test_patch: str) -> list[ChangedTestFile]:
    """The test files the attempt changed that the developer's test patch also changes."""
    patched = {line.split(" b/")[-1].strip() for line in test_patch.splitlines()
               if line.startswith("diff --git")}
    files = []
    for entry in record.get("attemptFiles") or []:
        path, saved = entry.get("path"), entry.get("savedAs")
        if path not in patched or not saved:
            continue
        mine = metrics_dir / record["attemptFilesDir"] / saved
        reference = metrics_dir / (record.get("originalFilesDir") or "original") / saved
        if not (mine.is_file() and reference.is_file()):
            continue
        added, removed = test_patch_method_delta(test_patch, path)
        reference_tests = count_test_methods(read_text(reference))
        files.append(ChangedTestFile(
            path=path,
            attempt_tests=count_test_methods(read_text(mine)),
            reference_tests=reference_tests,
            broken_tests=reference_tests - added + removed))
    return files


# --------------------------------------------------------------------- attempts
@dataclass
class Attempt:
    """One attempt with the facts behind its columns."""
    project: str
    case_index: int
    attempt: int
    success: bool
    changed_tests: list[ChangedTestFile]
    failure_label: str          # label of the last test run; "" for a successful attempt
    test_runs: int              # number of testRuns records
    untested_final_edit: bool
    context_limit: bool
    aligned: bool
    revising: bool

    @property
    def test_preserving(self) -> bool:
        return self.success and not any(f.fails_rule for f in self.changed_tests)

    def row(self) -> dict:
        failed = not self.success
        return {
            "project": self.project,
            "case_index": self.case_index,
            "attempt": self.attempt,
            "test_preserving": self.test_preserving,
            "final_state": LABEL_TO_STATE.get(self.failure_label, OTHER_STATE) if failed else "",
            "final_state_detail": self.failure_label if failed else "",
            "run_tests_calls": self.test_runs,
            "last_edit_untested": self.untested_final_edit,
            "never_ran_tests": self.test_runs == 0,
            "context_limit_stop": self.context_limit,
            "feedback_traceable": self.success and self.aligned,
            "feedback_revising": self.success and self.aligned and self.revising,
        }


def derive_attempt(record: dict, metrics_dir: Path, run_dir: Path, project: str,
                   case_index: int, test_patch: str) -> Attempt:
    attempt_no = int(record["attempt"])
    success = bool(record.get("success"))
    test_runs = record.get("testRuns") or []
    log = resolve_log(run_dir, record.get("attemptLog"))
    if log is None:
        raise InputError(f"{project} case {case_index} attempt {attempt_no}: attempt log not found")
    lines = read_text(log).split("\n")
    runs, order = read_test_runs(lines)
    tools, failure_kinds = read_log_records(lines, attempt_no)
    aligned, revising = feedback_alignment(tools, test_runs)
    return Attempt(
        project=project,
        case_index=case_index,
        attempt=attempt_no,
        success=success,
        changed_tests=changed_test_files(record, metrics_dir, test_patch) if success else [],
        failure_label="" if success else final_label(runs),
        test_runs=len(test_runs),
        untested_final_edit=ends_on_untested_edit(order),
        context_limit=bool(failure_kinds) and failure_kinds[0] == "context limit",
        aligned=aligned,
        revising=revising,
    )


def derive_run(run_dir: Path, dataset_file: Path) -> list[Attempt]:
    """All attempts of one run folder, read against its dataset file."""
    if not dataset_file.is_file():
        raise InputError(f"dataset file not found: {dataset_file}")
    cases = json.loads(dataset_file.read_text(encoding="utf-8"))
    position: dict[str, int] = {}
    for index, case in enumerate(cases):
        if case["instance_id"] in position:
            raise InputError(f"{dataset_file.name}: duplicate instance_id at entry {index}")
        position[case["instance_id"]] = index

    results = run_dir / "evaluation" / "results"
    metrics_files = sorted(results.glob("*/*/*/metrics_*.json"))
    if not metrics_files:
        raise InputError(f"no metrics files under {results}")
    attempts: list[Attempt] = []
    seen: set[tuple[str, int]] = set()
    for metrics_path in metrics_files:
        record = json.loads(metrics_path.read_text(encoding="utf-8"))
        instance_id, attempt_no = record["taskName"], int(record["attempt"])
        case_index = int(record["id"])
        if instance_id not in position:
            raise InputError(f"{metrics_path}: case is not in {dataset_file.name}")
        if position[instance_id] != case_index:
            raise InputError(f"{metrics_path}: id {case_index} is not the dataset index of its case")
        if (instance_id, attempt_no) in seen:
            raise InputError(f"{metrics_path}: attempt {attempt_no} of this case appears twice")
        seen.add((instance_id, attempt_no))
        attempts.append(derive_attempt(
            record, metrics_path.parent, run_dir, run_dir.name, case_index,
            cases[case_index].get("test_patch") or ""))
    return attempts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--datasets", required=True, metavar="DIR",
                        help="folder with one <project>.json dataset file per run folder")
    parser.add_argument("--out", required=True, metavar="FILE", help="CSV file to write")
    parser.add_argument("run_folders", nargs="+", metavar="RUN_FOLDER",
                        help="one repository's run folder")
    args = parser.parse_args(argv)

    attempts: list[Attempt] = []
    labels: set[str] = set()
    try:
        for folder in args.run_folders:
            run_dir = Path(os.path.abspath(folder))
            if run_dir.name in labels:
                raise InputError(f"two run folders share the basename {run_dir.name!r}")
            labels.add(run_dir.name)
            attempts.extend(derive_run(run_dir, Path(args.datasets) / f"{run_dir.name}.json"))
    except InputError as exc:
        print(f"derive_behaviour: {exc}", file=sys.stderr)
        return 1

    attempts.sort(key=lambda a: (a.project, a.case_index, a.attempt))
    with open(args.out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(a.row() for a in attempts)
    print(f"wrote {len(attempts)} attempts from {len(labels)} run folders to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
