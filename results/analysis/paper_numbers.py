from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
import tarfile
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath

from common import (COMMIT_SCOPED_PUBLIC, FILE_SCOPED, PUBLIC_SUBJECTS, as_bool, as_float,
                    read_commit_attempts, read_commit_cases, read_file_attempts,
                    read_file_scenarios)
from tables import NO_ASSERTION_STATES, Table, fixed, share, write_csv, write_table

DEFAULT_OUT = Path(__file__).resolve().parent.parent / "tables"
RAW = COMMIT_SCOPED_PUBLIC / "raw"
MODELS = ("GPT-4o", "Gemini Flash-2.5", "Claude Sonnet-4")
NAMED_TEST = re.compile(r"\bpublic\s+void\s+test\w*\s*\(\s*\)")
TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.M)
EXTENDS_TESTCASE = re.compile(r"\bextends\s+(?:junit\.framework\.)?TestCase\b")
METRICS_MEMBER = re.compile(r"/metrics_([1-5])\.json$")
MAVEN_CLASS_RUN = re.compile(r"Tests run: (\d+), Failures: \d+, Errors: \d+, Skipped: \d+[^\n]* -+ in (\S+)")
CASE_STUDY = (("gson", 18, "gson/src/test/java/com/google/gson/stream/JsonReaderTest.java"),
              ("gson", 0, "gson/src/test/java/com/google/gson/stream/JsonReaderTest.java"))

def money(value: float, places: int = 2) -> str:
    return "$" + fixed(value, places)

def count_share(hits: int, total: int) -> str:
    return f"{hits} ({share(hits, total)}%)"

def exact_mcnemar(only_a: int, only_b: int) -> float:
    n = only_a + only_b
    if n == 0:
        return 1.0
    return min(1.0, 2 * sum(math.comb(n, i) for i in range(min(only_a, only_b) + 1)) / 2 ** n)

class Data:
    def __init__(self) -> None:
        self.fs_attempts = read_file_attempts()
        self.fs_scenarios = read_file_scenarios()
        self.cs_attempts = read_commit_attempts()
        self.cs_cases = read_commit_cases()
        self.fs = defaultdict(list)
        for row in self.fs_attempts:
            self.fs[(row["model"], row["subject"], row["scenario"])].append(row)
        self.cs = defaultdict(list)
        for row in self.cs_attempts:
            self.cs[(row["subject"], row["case_id"])].append(row)
        for rows in self.cs.values():
            rows.sort(key=lambda r: int(r["attempt"]))
        self.case = {(c["subject"], c["case_id"]): c for c in self.cs_cases}

    def case_has(self, key, metric: str) -> bool:
        return any(as_bool(r[metric]) for r in self.cs[key])

    def cases(self, visibility: str | None = None):
        return [k for k, c in self.case.items() if visibility in (None, c["visibility"])]

class RawAudit:
    def __init__(self, data: Data) -> None:
        self.flags = {(r["subject"], r["case_id"], int(r["attempt"])): r
                      for r in data.cs_attempts if r["visibility"] == "public"}
        self.attempts = 0
        self.any_pass = set()
        self.final_pass = set()
        self.final_differs = []
        self.pairs = 0
        self.junit3_pairs = 0
        self.junit3_loss = set()
        self.case_study = {}
        for subject in PUBLIC_SUBJECTS:
            self._read_subject(subject)

    def _read_subject(self, subject: str) -> None:
        definitions = {d["instance_id"]: d for d in json.loads(
            (COMMIT_SCOPED_PUBLIC / "definitions" / f"{subject}.json").read_text(encoding="utf-8"))}
        wanted = {(case, path) for s, case, path in CASE_STUDY if s == subject}
        with tarfile.open(RAW / subject / "evaluation-results.tar.gz") as results:
            names = set(results.getnames())
            for member in results.getmembers():
                found = METRICS_MEMBER.search(member.name)
                if not found:
                    continue
                metric = json.load(results.extractfile(member))
                attempt = int(found.group(1))
                case_id = f"{subject}-{metric['id'] + 1:02d}"
                key = (subject, case_id, attempt)
                self.attempts += 1
                runs = metric.get("testRuns") or []
                if any(run["allPassed"] for run in runs):
                    self.any_pass.add(key)
                if runs and runs[-1]["allPassed"]:
                    self.final_pass.add(key)
                elif metric["success"]:
                    self.final_differs.append(key)
                if not as_bool(self.flags[key]["success"]):
                    continue
                base = PurePosixPath(member.name).parent
                instance = base.parent.name.split("_", 1)[1]
                patch = definitions[instance]["test_patch"]
                self._junit3(results, names, metric, base, patch, key)
                for case, path in wanted:
                    if metric["id"] == case and attempt == 1:
                        self.case_study[(subject, case)] = (metric, base, path)
        if wanted:
            with tarfile.open(RAW / subject / "evaluation-results.tar.gz") as results, \
                    tarfile.open(RAW / subject / "evaluation-logs.tar.gz") as logs:
                for (s, case), (metric, base, path) in list(self.case_study.items()):
                    if s == subject:
                        self.case_study[(s, case)] = self._case_row(results, logs, metric, base, path)

    def _junit3(self, results, names, metric, base, patch, key) -> None:
        patched = {line.split(" b/")[-1].strip() for line in patch.splitlines()
                   if line.startswith("diff --git")}
        references = {e["path"]: e for e in metric.get("originalFiles") or []}
        for entry in metric.get("attemptFiles") or []:
            path = entry["path"]
            if path not in references or path not in patched:
                continue
            own = str(base / metric["attemptFilesDir"] / entry["savedAs"])
            ref = str(base / metric["originalFilesDir"] / references[path]["savedAs"])
            if own not in names or ref not in names:
                continue
            self.pairs += 1
            agent = results.extractfile(own).read().decode("utf-8")
            reference = results.extractfile(ref).read().decode("utf-8")
            if not EXTENDS_TESTCASE.search(reference) or TEST_ANNOTATION.search(reference):
                continue
            self.junit3_pairs += 1
            added = removed = 0
            current = None
            for line in patch.splitlines():
                if line.startswith("diff --git"):
                    current = line.split(" b/")[-1].strip()
                elif current == path and not line.startswith(("+++", "---")):
                    if line.startswith("+"):
                        added += len(NAMED_TEST.findall(line[1:]))
                    elif line.startswith("-"):
                        removed += len(NAMED_TEST.findall(line[1:]))
            kept, ref_count = len(NAMED_TEST.findall(agent)), len(NAMED_TEST.findall(reference))
            if kept < min(ref_count - added + removed, ref_count):
                self.junit3_loss.add(key)

    @staticmethod
    def _case_row(results, logs, metric, base, path) -> dict:
        def saved(entries_key: str, folder_key: str) -> str:
            entry = next(e for e in metric[entries_key] if e["path"] == path)
            return results.extractfile(str(base / metric[folder_key] / entry["savedAs"])).read().decode("utf-8")
        reference, agent = saved("originalFiles", "originalFilesDir"), saved("attemptFiles", "attemptFilesDir")
        log = logs.extractfile(metric["attemptLog"]).read().decode("utf-8", errors="replace")
        test_class = PurePosixPath(path).stem
        runs = [int(n) for n, name in MAVEN_CLASS_RUN.findall(log) if name.endswith("." + test_class)]
        return {"reference_lines": len(reference.splitlines()), "agent_lines": len(agent.splitlines()),
                "reference_tests": len(TEST_ANNOTATION.findall(reference)),
                "agent_tests": len(TEST_ANNOTATION.findall(agent)),
                "runs_before": runs[0] if runs else None, "runs_after": runs[-1] if runs else None}

def table_repeatability(data: Data) -> Table:
    rows = []
    for model in MODELS:
        hist = Counter(sum(as_bool(r["success"]) for r in rows_)
                       for (m, _, _), rows_ in data.fs.items() if m == model)
        rows.append([f"File-scoped, {model}, successful repair"] + [str(hist[j]) for j in range(6)])
    for metric, label in (("success", "successful repair"), ("test_preserving", "test-preserving repair")):
        hist = Counter(sum(as_bool(r[metric]) for r in rows_) for rows_ in data.cs.values())
        rows.append([f"Commit-scoped, GPT-4o, {label}"] + [str(hist[j]) for j in range(6)])
    return Table("tab:repeatability", "Tasks by the number of their five attempts with the outcome",
                 ["Setting, model, outcome"] + [f"{j} of 5" for j in range(6)], rows)

def table_case_audit(audit: RawAudit, data: Data) -> Table:
    rows = []
    for subject, case, _ in CASE_STUDY:
        row = audit.case_study[(subject, case)]
        case_id = f"{subject}-{case + 1:02d}"
        accepted = as_bool(audit.flags[(subject, case_id, 1)]["test_preserving"])
        rows.append([case_id, f"{row['reference_lines']}/{row['agent_lines']}",
                     f"{row['reference_tests']}/{row['agent_tests']}",
                     f"{row['runs_before']}/{row['runs_after']}", "Yes" if accepted else "No"])
    return Table("tab:case-audit", "The two Gson attempts of the case study (attempt 1)",
                 ["Case", "Reference/agent file lines", "Reference/agent @Test lines",
                  "Tests run before/after the repair", "Test-preserving repair accepts"], rows)

def table_public_screen(audit: RawAudit, data: Data) -> Table:
    public = data.cases("public")
    total_attempts, total_cases = len(audit.flags), len(public)
    archived = {k for k, r in audit.flags.items() if as_bool(r["success"])}
    preserving = {k for k, r in audit.flags.items() if as_bool(r["test_preserving"])}
    def cases_of(keys):
        return {(s, c) for s, c, _ in keys}
    rows = []
    for label, keys in (("Successful repair", archived),
                        ("Successful repair, last test run passes", archived & audit.final_pass),
                        ("Test-preserving repair", preserving),
                        ("Test-preserving repair, also counting JUnit 3 test methods", preserving - audit.junit3_loss)):
        rows.append([label, count_share(len(keys), total_attempts), count_share(len(cases_of(keys)), total_cases)])
    return Table("tab:public-screen", "Public commit-scoped attempts and cases under the raw-record screens",
                 ["Outcome", f"Attempts (of {total_attempts})", f"Cases (of {total_cases})"], rows)

def shared_changes(data: Data) -> tuple[Table, dict]:
    gpt = {(r["subject"], int(r["scenario"])): r for r in data.fs_scenarios if r["model"] == "GPT-4o"}
    rows, totals = [], Counter()
    for subject in PUBLIC_SUBJECTS:
        scenarios = json.loads((FILE_SCOPED / "definitions" / f"{subject}.json").read_text(encoding="utf-8"))
        cases = {d["instance_id"]: d for d in json.loads(
            (COMMIT_SCOPED_PUBLIC / "definitions" / f"{subject}.json").read_text(encoding="utf-8"))}
        for key in sorted(data.cases("public")):
            info = data.case[key]
            if info["subject"] != subject:
                continue
            repaired = info["instance_id"].rsplit("-", 1)[-1]
            matches = [d for d in scenarios if repaired.startswith(d["aCommit"])]
            if not matches:
                continue
            base = cases[info["instance_id"]]["base_commit"]
            passing = sum(as_bool(gpt[(subject, int(d["file_scoped"]["scenario"]))]["success"]) for d in matches)
            same_parent = sum(base.startswith(d["bCommit"]) for d in matches)
            cs_pass, cs_preserving = data.case_has(key, "success"), data.case_has(key, "test_preserving")
            rows.append([info["case_id"], str(len(matches)), str(same_parent), str(passing),
                         "Yes" if cs_pass else "No", "Yes" if cs_preserving else "No"])
            totals.update(changes=1, scenarios=len(matches), same_parent=same_parent, fs_pass=passing,
                          cs_pass=cs_pass, cs_preserving=cs_preserving, **{f"changes_{subject}": 1,
                                                                           f"scenarios_{subject}": len(matches)})
    table = Table("tab:shared-changes", "Public commits that are cases of both settings",
                  ["Commit-scoped case", "File-scoped scenarios", "Scenarios with the same parent revision",
                   "File-scoped scenarios repaired (GPT-4o)", "Commit-scoped successful repair",
                   "Commit-scoped test-preserving repair"], rows)
    return table, totals

def numbers(data: Data, audit: RawAudit, shared: dict) -> list[list[str]]:
    out: list[list[str]] = []

    def add(section: str, quantity: str, value: str, definition: str) -> None:
        out.append([section, quantity, value, definition])

    fs_by_model = {m: [r for r in data.fs_scenarios if r["model"] == m] for m in MODELS}
    n_scenarios = len(fs_by_model["GPT-4o"])
    for m in MODELS:
        add("RQ1", f"{m}: scenarios repaired", f"{sum(as_bool(r['success']) for r in fs_by_model[m])} of {n_scenarios}",
            "File-scoped scenarios with a successful attempt among five")
    gpt = {(r["subject"], r["scenario"]): r for r in fs_by_model["GPT-4o"]}
    gem = {(r["subject"], r["scenario"]): r for r in fs_by_model["Gemini Flash-2.5"]}
    only_gpt = sum(as_bool(gpt[k]["success"]) and not as_bool(gem[k]["success"]) for k in gpt)
    only_gem = sum(as_bool(gem[k]["success"]) and not as_bool(gpt[k]["success"]) for k in gpt)
    add("RQ1", "scenarios repaired only by GPT-4o / only by Gemini", f"{only_gpt} / {only_gem}",
        "File-scoped scenarios, best of five, paired by scenario")
    add("RQ1", "exact McNemar test, GPT-4o against Gemini", f"p = {fixed(exact_mcnemar(only_gpt, only_gem), 3)}",
        "Two-sided exact test on the discordant scenarios")
    for m in ("GPT-4o", "Gemini Flash-2.5"):
        rates = []
        for s in PUBLIC_SUBJECTS:
            rows_ = [r for r in fs_by_model[m] if r["subject"] == s]
            rates.append((sum(as_bool(r["success"]) for r in rows_) / len(rows_),
                          share(sum(as_bool(r["success"]) for r in rows_), len(rows_)), s))
        lowest = min(rates)
        add("RQ1", f"{m}: lowest successful repair of a subject", f"{lowest[1]}% ({lowest[2]})",
            "File-scoped, best of five, per subject")
    for m in ("GPT-4o", "Gemini Flash-2.5"):
        add("RQ1", f"{m}: assertion match / perfect match",
            f"{sum(as_bool(r['assertion_match']) for r in fs_by_model[m])} / "
            f"{sum(as_bool(r['perfect_match']) for r in fs_by_model[m])}", "File-scoped scenarios, best of five")
    claude_alluxio = [r for r in fs_by_model["Claude Sonnet-4"] if r["subject"] == "alluxio"]
    add("RQ1", "Claude Sonnet-4: alluxio scenarios repaired",
        f"{sum(as_bool(r['success']) for r in claude_alluxio)} of {len(claude_alluxio)}", "File-scoped, best of five")
    repaired_gpt = [r for r in fs_by_model["GPT-4o"] if as_bool(r["success"])]
    add("RQ1", "GPT-4o: repaired scenarios without a perfect-match attempt",
        f"{sum(not as_bool(r['perfect_match']) for r in repaired_gpt)} of {len(repaired_gpt)}", "File-scoped")
    zero = [r for r in data.fs_scenarios if as_float(r["cost_per_attempt_usd"]) == 0]
    add("RQ1", "scenario-model pairs with zero recorded spend, and of those with a success",
        f"{len(zero)} of {len(data.fs_scenarios)}, {sum(as_bool(r['success']) for r in zero)}", "File-scoped")

    all_cases = data.cases()
    passing = [k for k in all_cases if data.case_has(k, "success")]
    preserving = [k for k in all_cases if data.case_has(k, "test_preserving")]
    only_deleting = len(passing) - len(preserving)
    add("RQ2", "repaired cases without a test-preserving attempt",
        f"{only_deleting} ({share(only_deleting, len(passing))}% of {len(passing)})",
        "Commit-scoped cases with a successful attempt but none that keeps the test methods")
    passing_attempts = [r for r in data.cs_attempts if as_bool(r["success"])]
    failing_check = [r for r in passing_attempts if not as_bool(r["test_preserving"])]
    add("RQ2", "successful attempts that fail the test-preserving check",
        f"{len(failing_check)} of {len(passing_attempts)} ({share(len(failing_check), len(passing_attempts))}%)",
        "Commit-scoped attempts")
    drop = fixed(100 * len(passing) / len(all_cases) - 100 * len(preserving) / len(all_cases), 1)
    add("RQ2", "drop from successful to test-preserving repair", f"{drop} percentage points", "Commit-scoped, best of five")
    for subject in ("gson", "neo4j-java-driver"):
        keys = [k for k in all_cases if k[0] == subject]
        add("RQ2", f"{subject}: successful / test-preserving repair",
            f"{sum(data.case_has(k, 'success') for k in keys)} / "
            f"{sum(data.case_has(k, 'test_preserving') for k in keys)} of {len(keys)}", "Commit-scoped cases")
    gpt_cost = statistics.mean(as_float(r["cost_per_attempt_usd"]) for r in fs_by_model["GPT-4o"])
    cs_cost = statistics.mean(as_float(r["cost_usd"]) for r in data.cs_attempts)
    add("RQ2", "mean cost per attempt, file-scoped GPT-4o / commit-scoped",
        f"{money(gpt_cost, 3)} / {money(cs_cost, 3)}", "Recorded model spend")
    small = [k for k in all_cases if data.case[k]["repair_test_files"] == "1" and data.case[k]["repair_test_methods"] == "1"]
    for label, keys in (("all", small), ("public", [k for k in small if data.case[k]["visibility"] == "public"]),
                        ("private", [k for k in small if data.case[k]["visibility"] == "private"])):
        s = sum(data.case_has(k, "success") for k in keys)
        t = sum(data.case_has(k, "test_preserving") for k in keys)
        add("RQ2", f"one modified test file and one modified method, {label}",
            f"{len(keys)} cases, {s} repaired ({share(s, len(keys))}%), {t} test-preserving ({share(t, len(keys))}%)",
            "Commit-scoped cases whose developer repair changes one test method in one test file")
    add("RQ2", "shared changes and their file-scoped scenarios",
        f"{shared['changes']} changes, {shared['scenarios']} scenarios, {shared['same_parent']} with the same parent",
        "Public commits that define a commit-scoped case and file-scoped scenarios (matched by commit hash)")
    add("RQ2", "shared changes by subject",
        ", ".join(f"{s} {shared[f'changes_{s}']} ({shared[f'scenarios_{s}']})" for s in PUBLIC_SUBJECTS
                  if shared[f"changes_{s}"]), "Changes (scenarios)")
    add("RQ2", "shared changes: file-scoped repaired / commit-scoped repaired / test-preserving",
        f"{shared['fs_pass']} of {shared['scenarios']} / {shared['cs_pass']} of {shared['changes']} / "
        f"{shared['cs_preserving']} of {shared['changes']}", "GPT-4o, best of five")
    six = [k for k in all_cases if int(data.case[k]["repair_test_methods"]) >= 6]
    add("RQ2", "cases needing six or more test methods: successful / test-preserving",
        f"{len(six)} cases, {share(sum(data.case_has(k, 'success') for k in six), len(six))}% / "
        f"{share(sum(data.case_has(k, 'test_preserving') for k in six), len(six))}%", "Commit-scoped, best of five")
    for k in (1, 3, 5):
        firsts = {key: rows[:k] for key, rows in data.cs.items()}
        s = sum(any(as_bool(r["success"]) for r in rows) for rows in firsts.values())
        t = sum(any(as_bool(r["test_preserving"]) for r in rows) for rows in firsts.values())
        spend = sum(as_float(r["cost_usd"]) for rows in firsts.values() for r in rows)
        add("RQ2", f"first {k} attempts: successful / test-preserving, spend, spend per test-preserving case",
            f"{share(s, len(firsts))}% / {share(t, len(firsts))}%, {money(spend)}, {money(spend / t)}",
            "Every case charged for its first k recorded attempts")
    spend_3 = sum(as_float(r["cost_usd"]) for rows in data.cs.values() for r in rows[:3])
    spend_5 = sum(as_float(r["cost_usd"]) for rows in data.cs.values() for r in rows)
    add("RQ2", "cost of attempts four and five", money(spend_5 - spend_3), "Commit-scoped, all cases")
    for label, rows_ in (("successful", passing_attempts),
                         ("failed", [r for r in data.cs_attempts if not as_bool(r["success"])])):
        costs = [as_float(r["cost_usd"]) for r in rows_]
        add("RQ2", f"{label} attempts: mean / median cost",
            f"{money(statistics.mean(costs), 3)} / {money(statistics.median(costs), 3)}", "Commit-scoped attempts")
    for model in ("GPT-4o", "Gemini Flash-2.5", "Claude Sonnet-4"):
        hist = Counter(sum(as_bool(r["success"]) for r in rows_) for (m, _, _), rows_ in data.fs.items() if m == model)
        add("RQ2", f"{model}: scenarios repaired on all five / no / some attempts",
            f"{hist[5]} / {hist[0]} / {n_scenarios - hist[5] - hist[0]}", "File-scoped")
    for metric, label in (("success", "successful"), ("test_preserving", "test-preserving")):
        hist = Counter(sum(as_bool(r[metric]) for r in rows_) for rows_ in data.cs.values())
        n = len(data.cs)
        add("RQ2", f"commit-scoped cases {label} on all five / some / no attempts",
            f"{count_share(hist[5], n)} / {count_share(n - hist[5] - hist[0], n)} / {count_share(hist[0], n)}",
            "Commit-scoped cases")
    attempt_both = sum(as_bool(r["assertion_match"]) and as_bool(r["test_preserving"]) for r in data.cs_attempts)
    add("RQ2", "attempts with assertion match and test-preserving repair, and perfect matches among them",
        f"{attempt_both} of {len(data.cs_attempts)}, "
        f"{sum(as_bool(r['perfect_match']) and as_bool(r['test_preserving']) for r in data.cs_attempts)} perfect "
        f"of {sum(as_bool(r['perfect_match']) for r in data.cs_attempts)}", "Commit-scoped attempts")
    add("RQ2", "attempts with assertion match but no test-preserving repair",
        str(sum(as_bool(r["assertion_match"]) and not as_bool(r["test_preserving"]) for r in data.cs_attempts)),
        "Commit-scoped attempts")
    add("RQ2", "test-preserving attempts without assertion match",
        str(sum(as_bool(r["test_preserving"]) and not as_bool(r["assertion_match"]) for r in data.cs_attempts)),
        "Commit-scoped attempts")
    assertion_cases = [k for k in all_cases if data.case_has(k, "assertion_match")]
    add("RQ2", "assertion-matching cases outside the test-preserving cases / test-preserving cases without assertion match",
        f"{sum(not data.case_has(k, 'test_preserving') for k in assertion_cases)} / "
        f"{sum(not data.case_has(k, 'assertion_match') for k in preserving)}", "Commit-scoped cases, best of five")

    private = data.cases("private")
    for subject in ("P1", "P2", "P3"):
        keys = [k for k in private if k[0] == subject]
        add("RQ3", f"{subject}: cases repaired", f"{sum(data.case_has(k, 'success') for k in keys)} of {len(keys)}",
            "Commit-scoped, best of five")
    for vis in ("public", "private"):
        keys = data.cases(vis)
        missing = sum(data.case_has(k, "success") and not data.case_has(k, "test_preserving") for k in keys)
        add("RQ3", f"{vis}: repaired cases without a test-preserving attempt", str(missing), "Commit-scoped")
        for metric, label in (("success", "successful"), ("test_preserving", "test-preserving")):
            per_subject = defaultdict(list)
            for k in keys:
                per_subject[k[0]].append(data.case_has(k, metric))
            mean = statistics.mean(sum(v) / len(v) for v in per_subject.values())
            add("RQ3", f"{vis}: {label} repair with every subject weighted equally", f"{fixed(100 * mean, 1)}%",
                "Mean of the subjects' rates, best of five")

    failed = [r for r in data.cs_attempts if not as_bool(r["success"])]
    states = Counter(r["final_state"] for r in failed)
    no_assertion = sum(states[s] for s in states if s in NO_ASSERTION_STATES)
    add("RQ4", "failed attempts whose final state reached no assertion",
        f"{no_assertion} of {len(failed)} ({share(no_assertion, len(failed))}%)", "Commit-scoped failed attempts")
    compile_detail = Counter(r["final_state_detail"] for r in failed if r["final_state"] == "test does not compile")
    add("RQ4", "compilation-related final states by detail",
        ", ".join(f"{k} {v}" for k, v in compile_detail.most_common()), "Commit-scoped failed attempts")
    untested = [r for r in data.cs_attempts if as_bool(r["last_edit_untested"])]
    add("RQ4", "attempts ending after an untested edit, and of those successful",
        f"{len(untested)} of {len(data.cs_attempts)}, {sum(as_bool(r['success']) for r in untested)}",
        "Commit-scoped attempts")
    add("RQ4", "attempts that never ran a test", str(sum(as_bool(r["never_ran_tests"]) for r in data.cs_attempts)),
        "Commit-scoped attempts")
    context = [r for r in data.cs_attempts if as_bool(r["context_limit_stop"])]
    add("RQ4", "attempts stopped by the context limit, and of those on druid",
        f"{len(context)}, {sum(r['subject'] == 'druid' for r in context)}", "Commit-scoped attempts")
    traceable = [r for r in passing_attempts if as_bool(r["feedback_traceable"])]
    revising = sum(as_bool(r["feedback_revising"]) for r in traceable)
    add("RQ4", "successful attempts with traceable test runs, and of those with a failing run after an edit",
        f"{len(traceable)} of {len(passing_attempts)}, {revising} ({share(revising, len(traceable))}%)",
        "Commit-scoped successful attempts")
    for key, rows_ in sorted(data.cs.items()):
        if all(as_bool(r["context_limit_stop"]) and as_bool(r["never_ran_tests"]) for r in rows_):
            scores = [as_float(r["codebleu"]) for r in rows_]
            add("RQ4", f"{key[1]}: CodeBLEU of five attempts that all stop at the context limit untested",
                f"{fixed(min(scores), 3)} to {fixed(max(scores), 3)}", "Commit-scoped case")
    add("RQ4", "public attempts whose last recorded test run fails although an earlier one passed",
        ", ".join(f"{c} attempt {a}" for _, c, a in sorted(audit.final_differs)) or "none",
        "Public commit-scoped raw records")
    add("RQ4", "public successful attempts, saved target/reference file pairs, JUnit 3 pairs",
        f"{sum(as_bool(r['success']) for r in audit.flags.values())}, {audit.pairs}, {audit.junit3_pairs}",
        "JUnit 3 pairs: the reference class extends TestCase directly and has no @Test line")
    add("RQ4", "test-preserving attempts that lose JUnit 3 test methods",
        ", ".join(f"{c} attempt {a}" for _, c, a in sorted(audit.junit3_loss)) or "none",
        "No-argument public void test* methods below both the broken and the developer's count")
    add("Overall", "attempts in both settings", str(len(data.fs_attempts) + len(data.cs_attempts)),
        "File-scoped and commit-scoped attempts")
    return out

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Compute the paper's further analyses.")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="folder to write the tables to")
    args = parser.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    data = Data()
    audit = RawAudit(data)
    shared_table, shared = shared_changes(data)
    for table in (table_repeatability(data), table_case_audit(audit, data),
                  table_public_screen(audit, data), shared_table):
        write_table(table, args.out)
    write_csv(args.out / "paper-numbers.csv", ["section", "quantity", "value", "definition"],
              numbers(data, audit, shared))
    print(f"wrote tab-repeatability, tab-case-audit, tab-public-screen, tab-shared-changes and "
          f"paper-numbers.csv to {args.out}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
