"""Regenerate the tables and prose numbers of the RepairGent thesis from the results package.

Each table of the Results, Methodology and Discussion chapters is written to the output folder as
CSV and Markdown, named after its label with ':' replaced by '-' (tab:headline gives
tab-headline.csv and tab-headline.md). prose-numbers.csv holds the numbers the thesis text quotes
outside its tables, each with the value computed here, its definition and its population.

Definitions follow the thesis methodology. A case is repaired when at least one of its five
attempts succeeds ("best of five"); per-attempt rates use all attempts as the denominator. A failed
attempt is never a test-preserving, perfect or assertion match, and those rates keep the full
denominator unless a row says "of repaired". Percentages and means are rounded half up to the
precision the thesis prints.

Usage: python3 analysis/tables.py [--out DIR]
"""

from __future__ import annotations

import argparse
import csv
import statistics
from collections import Counter, defaultdict
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from fractions import Fraction
from pathlib import Path
from typing import Sequence

from common import (
    MODEL_NAMES,
    PRIVATE_SUBJECTS,
    PUBLIC_SUBJECTS,
    as_bool,
    as_float,
    fisher_exact_two_sided,
    read_commit_attempts,
    read_commit_cases,
    read_file_attempts,
    read_file_scenarios,
)

DEFAULT_OUT = Path(__file__).resolve().parent.parent / "tables"
MODELS = tuple(MODEL_NAMES.values())
GPT, GEMINI, SONNET = MODELS
LANGUAGE = {"public": "Java", "private": "Kotlin"}
FAILED_STATES = (
    ("test does not compile", "Test does not compile"),
    ("assertion still fails", "Assertion still fails"),
    ("test throws an exception", "Test throws an exception"),
    ("tests never run", "Tests never run"),
    ("no test selected", "No test selected"),
    ("license check fails", "Licence check fails"),
    ("other", "Other"),
)
# Failed attempts whose final test run never reaches an assertion.
NO_ASSERTION_STATES = {"test does not compile", "tests never run", "no test selected",
                       "license check fails"}
OUTCOMES = (
    ("Successful repair", "repaired"),
    ("Test-preserving repair", "test_preserving"),
    ("Perfect match", "perfect"),
    ("Assertion match", "assertion"),
)
# Repair-size bands by the number of test methods to repair: (low, high, label); high None is open.
BANDS = ((1, 1, "1"), (2, 2, "2"), (3, 5, "3–5"), (6, 10, "6–10"), (11, None, "11 or more"))
MEASURES = (
    ("repair_test_methods",
     "distinct test methods the reference patch modified in the target test files",
     lambda case: case.test_methods),
    ("repair_test_files",
     "test files of the repair targets that the reference patch edited",
     lambda case: case.test_files),
    ("repair_edit_sites", "diff hunks in those files", lambda case: case.edit_sites),
    ("repair_test_lines", "lines added and removed in those files", lambda case: case.test_lines),
)


@dataclass(frozen=True)
class FileAttempt:
    """One file-scoped attempt."""

    success: bool
    perfect: bool


@dataclass(frozen=True)
class Scenario:
    """A file-scoped scenario under one model, with its five attempts in order."""

    model: str
    subject: str
    success: bool
    perfect: bool
    assertion: bool
    best_codebleu: float
    cost: float
    attempts: tuple[FileAttempt, ...]


@dataclass(frozen=True)
class Attempt:
    """One commit-scoped attempt with the behaviour columns of the thesis."""

    success: bool
    test_preserving: bool
    perfect: bool
    assertion: bool
    codebleu: float
    cost: float
    final_state: str
    last_edit_untested: bool
    never_ran_tests: bool
    context_limit_stop: bool
    feedback_traceable: bool
    feedback_revising: bool


@dataclass(frozen=True)
class Case:
    """A commit-scoped case: its repair-size fields and its attempts in attempt order."""

    visibility: str
    subject: str
    build_tool: str
    post_cutoff: bool
    targets: int
    test_files: int
    test_methods: int
    edit_sites: int
    test_lines: int
    attempts: tuple[Attempt, ...]

    @property
    def successes(self) -> int:
        return sum(a.success for a in self.attempts)

    @property
    def repaired(self) -> bool:
        """Best of five: at least one attempt succeeds."""
        return self.successes > 0

    @property
    def test_preserving(self) -> bool:
        return any(a.test_preserving for a in self.attempts)

    @property
    def perfect(self) -> bool:
        return any(a.perfect for a in self.attempts)

    @property
    def assertion(self) -> bool:
        return any(a.assertion for a in self.attempts)

    @property
    def deletion_only(self) -> bool:
        """Repaired only by attempts that deleted test methods."""
        return self.repaired and not self.test_preserving

    @property
    def best_codebleu(self) -> float:
        return max(a.codebleu for a in self.attempts)

    @property
    def one_file_one_method(self) -> bool:
        return self.test_files == 1 and self.test_methods == 1


@dataclass(frozen=True)
class Table:
    """A thesis table: its label, a heading and its rows of text cells."""

    label: str
    title: str
    header: list[str]
    rows: list[list[str]]


ProseRow = list[str]


# --------------------------------------------------------------------- loading
def load_scenarios() -> list[Scenario]:
    """Scenarios of data/file-scoped/public/scenarios.csv.

    Each scenario takes its attempts, in attempt order, from data/file-scoped/public/attempts.csv.
    """
    attempts: dict[tuple[str, str, str], list[FileAttempt]] = defaultdict(list)
    for row in sorted(read_file_attempts(), key=lambda r: int(r["attempt"])):
        key = (row["model"], row["subject"], row["scenario"])
        attempts[key].append(FileAttempt(as_bool(row["success"]), as_bool(row["perfect_match"])))
    return [
        Scenario(
            model=row["model"],
            subject=row["subject"],
            success=as_bool(row["success"]),
            perfect=as_bool(row["perfect_match"]),
            assertion=as_bool(row["assertion_match"]),
            best_codebleu=as_float(row["best_codebleu"]),
            cost=as_float(row["cost_per_attempt_usd"]),
            attempts=tuple(attempts[(row["model"], row["subject"], row["scenario"])]),
        )
        for row in read_file_scenarios()
    ]


def load_cases() -> list[Case]:
    """Cases of data/commit-scoped/public/cases.csv, then of data/commit-scoped/private/cases.csv.

    Each case takes its attempts, in attempt order, from data/commit-scoped/public/attempts.csv
    and data/commit-scoped/private/attempts.csv.
    """
    attempts: dict[tuple[str, str], list[Attempt]] = defaultdict(list)
    rows = sorted(read_commit_attempts(),
                  key=lambda r: (r["subject"], r["case_id"], int(r["attempt"])))
    for row in rows:
        attempts[(row["subject"], row["case_id"])].append(Attempt(
            success=as_bool(row["success"]),
            test_preserving=as_bool(row["test_preserving"]),
            perfect=as_bool(row["perfect_match"]),
            assertion=as_bool(row["assertion_match"]),
            codebleu=as_float(row["codebleu"]),
            cost=as_float(row["cost_usd"]),
            final_state=row["final_state"],
            last_edit_untested=as_bool(row["last_edit_untested"]),
            never_ran_tests=as_bool(row["never_ran_tests"]),
            context_limit_stop=as_bool(row["context_limit_stop"]),
            feedback_traceable=as_bool(row["feedback_traceable"]),
            feedback_revising=as_bool(row["feedback_revising"]),
        ))
    return [
        Case(
            visibility=row["visibility"],
            subject=row["subject"],
            build_tool=row["build_tool"],
            post_cutoff=as_bool(row["post_cutoff"]),
            targets=int(row["repair_targets"]),
            test_files=int(row["repair_test_files"]),
            test_methods=int(row["repair_test_methods"]),
            edit_sites=int(row["repair_edit_sites"]),
            test_lines=int(row["repair_test_lines"]),
            attempts=tuple(attempts[(row["subject"], row["case_id"])]),
        )
        for row in read_commit_cases()
    ]


# --------------------------------------------------------------------- numbers
def pct(hits: int, total: int) -> Decimal:
    """A share of a set in percent, exact until it is rounded for printing."""
    return Decimal(100 * hits) / Decimal(total)


def fixed(value: float | Decimal, places: int) -> str:
    """A number rounded half up to the given decimals, as text."""
    return str(Decimal(str(value)).quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP))


def share(hits: int, total: int) -> str:
    """A share in percent to one decimal."""
    return fixed(pct(hits, total), 1)


def whole(value: float) -> str:
    """An integer-valued number as an integer, otherwise to one decimal."""
    return str(int(value)) if float(value).is_integer() else fixed(value, 1)


def count(items: Sequence[object], name: str) -> int:
    """How many items have the flag or property `name` set."""
    return sum(bool(getattr(item, name)) for item in items)


def fisher_counts(a: int, n_a: int, b: int, n_b: int) -> float:
    """Two-sided Fisher p-value for a hits out of n_a against b hits out of n_b."""
    return fisher_exact_two_sided(a, n_a - a, b, n_b - b)


def fisher_p(group_a: Sequence[object], group_b: Sequence[object], name: str) -> float:
    """Two-sided Fisher p-value of the flag `name` between two groups of cases."""
    return fisher_counts(count(group_a, name), len(group_a), count(group_b, name), len(group_b))


def p_text(p: float) -> str:
    """A p-value as the scope tables print it: below 0.001, or to three decimals."""
    return "<0.001" if p < 0.001 else fixed(p, 3)


def verdict(p: float) -> str:
    """Whether a two-sided Fisher p-value is significant at the 0.05 level."""
    return "significant at 0.05" if p < 0.05 else "not significant at 0.05"


def retained(kept: int, kept_total: int, base: int, base_total: int) -> str:
    """The share of a base rate that another rate keeps, as a whole percentage."""
    return fixed(pct(kept, kept_total) / pct(base, base_total) * 100, 0)


def mean_cost(items: Sequence[object]) -> float:
    return statistics.fmean(item.cost for item in items)


def attempts_of(cases: Sequence[Case]) -> list[Attempt]:
    return [a for case in cases for a in case.attempts]


def band(cases: Sequence[Case], low: int, high: int | None) -> list[Case]:
    return [c for c in cases if c.test_methods >= low and (high is None or c.test_methods <= high)]


# --------------------------------------------------------------------- tables
def table_fs_models(scenarios: list[Scenario]) -> Table:
    rows = []
    for model in MODELS:
        group = [s for s in scenarios if s.model == model]
        repaired = [s for s in group if s.success]
        rows.append([
            model,
            share(count(group, "success"), len(group)),
            share(count(group, "perfect"), len(group)),
            share(count(repaired, "perfect"), len(repaired)),
            share(count(group, "assertion"), len(group)),
            share(count(repaired, "assertion"), len(repaired)),
            fixed(statistics.fmean(s.best_codebleu for s in group), 3),
            fixed(mean_cost(group), 3),
        ])
    header = ["Model", "Successful repair (%)", "Perfect match, of all (%)",
              "Perfect match, of repaired (%)", "Assertion match, of all (%)",
              "Assertion match, of repaired (%)", "CodeBLEU", "Cost per attempt (USD)"]
    return Table("tab:fs-models", "File-scoped results per model, best of five (RQ1)",
                 header, rows)


def table_repos(scenarios: list[Scenario], cases: list[Case]) -> Table:
    scenario_counts = Counter(s.subject for s in scenarios if s.model == GPT)
    rows = []
    for subject in (*PUBLIC_SUBJECTS, *PRIVATE_SUBJECTS):
        own = [c for c in cases if c.subject == subject]
        visibility = own[0].visibility
        rows.append([
            subject,
            visibility,
            LANGUAGE[visibility],
            ", ".join(sorted({c.build_tool for c in own})),
            str(scenario_counts[subject]) if visibility == "public" else "",
            str(len(own)),
        ])
    rows.append(["Total", "", "", "", str(sum(scenario_counts.values())), str(len(cases))])
    header = ["Project", "Visibility", "Language", "Build", "File-scoped scenarios",
              "Commit-scoped cases"]
    return Table("tab:repos", "Subjects with their file-scoped scenarios and commit-scoped cases",
                 header, rows)


def table_headline(cases: list[Case]) -> Table:
    attempts = attempts_of(cases)
    rows = [
        _count_row("Successful repair, best of five attempts", count(cases, "repaired"), len(cases),
                   "cases"),
        _count_row("Successful repair, per attempt", count(attempts, "success"), len(attempts),
                   "attempts"),
        _count_row("Test-preserving repair, best of five attempts",
                   count(cases, "test_preserving"), len(cases), "cases"),
        _count_row("Test-preserving repair, per attempt", count(attempts, "test_preserving"),
                   len(attempts), "attempts"),
        _count_row("Assertion match", count(cases, "assertion"), len(cases), "cases"),
        _count_row("Perfect match", count(cases, "perfect"), len(cases), "cases"),
        ["Best CodeBLEU, mean of per-case maximum",
         fixed(statistics.fmean(c.best_codebleu for c in cases), 3), "", "", ""],
        ["Total model spend", fixed(sum(a.cost for a in attempts), 2), "", "", "USD"],
    ]
    header = ["Metric", "Rate (%) or value", "Count", "Of", "Unit"]
    return Table("tab:headline", "Commit-scoped headline results", header, rows)


def _count_row(label: str, hits: int, total: int, unit: str) -> list[str]:
    return [label, share(hits, total), str(hits), str(total), unit]


def file_column(group: list[Scenario]) -> dict[str, str]:
    attempts = [a for s in group for a in s.attempts]
    repaired = [s for s in group if s.success]
    return {
        "Scenarios or cases": str(len(group)),
        "Successful repair, best of five": share(count(group, "success"), len(group)),
        "Successful repair, per attempt": share(count(attempts, "success"), len(attempts)),
        "Test-preserving repair, best of five": "",
        "Assertion match": share(count(group, "assertion"), len(group)),
        "Perfect match": share(count(group, "perfect"), len(group)),
        "Assertion match, of repaired": share(count(repaired, "assertion"), len(repaired)),
        "Perfect match, of repaired": share(count(repaired, "perfect"), len(repaired)),
        "Best CodeBLEU, mean over cases": fixed(
            statistics.fmean(s.best_codebleu for s in group), 3),
        "Mean cost per attempt (USD)": fixed(mean_cost(group), 3),
    }


def commit_column(group: list[Case]) -> dict[str, str]:
    attempts = attempts_of(group)
    repaired = [c for c in group if c.repaired]
    return {
        "Scenarios or cases": str(len(group)),
        "Successful repair, best of five": share(count(group, "repaired"), len(group)),
        "Successful repair, per attempt": share(count(attempts, "success"), len(attempts)),
        "Test-preserving repair, best of five": share(count(group, "test_preserving"),
                                                      len(group)),
        "Assertion match": share(count(group, "assertion"), len(group)),
        "Perfect match": share(count(group, "perfect"), len(group)),
        "Assertion match, of repaired": share(count(repaired, "assertion"), len(repaired)),
        "Perfect match, of repaired": share(count(repaired, "perfect"), len(repaired)),
        "Best CodeBLEU, mean over cases": fixed(statistics.fmean(c.best_codebleu for c in group),
                                                3),
        "Mean cost per attempt (USD)": fixed(mean_cost(attempts), 3),
    }


def table_settings(scenarios: list[Scenario], cases: list[Case]) -> Table:
    one = [c for c in cases if c.one_file_one_method]
    columns = [
        file_column([s for s in scenarios if s.model == GPT]),
        commit_column([c for c in one if c.visibility == "public"]),
        commit_column([c for c in one if c.visibility == "private"]),
        commit_column(one),
        commit_column(cases),
    ]
    rows = [[label, *(column[label] for column in columns)] for label in columns[0]]
    header = ["Metric", "File-scoped, all scenarios",
              "Commit-scoped, one file and one method, public",
              "Commit-scoped, one file and one method, private",
              "Commit-scoped, one file and one method, all", "Commit-scoped, all cases"]
    return Table("tab:settings",
                 "GPT-4o: file-scoped scenarios against commit-scoped cases, by repair size",
                 header, rows)


def _band_row(label: str, group: list[Case]) -> list[str]:
    attempts = attempts_of(group)
    return [label, str(len(group)),
            share(count(group, "repaired"), len(group)),
            share(count(attempts, "success"), len(attempts)),
            share(count(group, "test_preserving"), len(group)),
            share(count(attempts, "test_preserving"), len(attempts))]


def table_scope(cases: list[Case]) -> Table:
    rows = [_band_row(label, band(cases, low, high)) for low, high, label in BANDS]
    header = ["Test methods to repair", "Cases", "Successful repair, best of five (%)",
              "Successful repair, per attempt (%)", "Test-preserving repair, best of five (%)",
              "Test-preserving repair, per attempt (%)"]
    return Table("tab:scope", "Commit-scoped repair rate by the number of test methods to repair",
                 header, rows)


def table_scope_groups(cases: list[Case]) -> Table:
    one = band(cases, 1, 1)
    six = band(cases, 6, None)
    one_or_two = band(cases, 1, 2)
    three = band(cases, 3, None)
    rows = [
        _split_row("1", one, None, None),
        _split_row("6 or more", six, one, six),
        _split_row("1 or 2", one_or_two, None, None),
        _split_row("3 or more", three, one_or_two, three),
    ]
    header = ["Test methods to repair", "Cases", "Successful repair, best of five (%)",
              "p (successful repair)", "Test-preserving repair, best of five (%)",
              "p (test-preserving repair)"]
    return Table("tab:scope-groups",
                 "Commit-scoped repair rate for two splits by the number of test methods to repair",
                 header, rows)


def _split_row(label: str, group: list[Case], reference: list[Case] | None,
               compared: list[Case] | None) -> list[str]:
    p_success = p_test_preserving = ""
    if reference is not None and compared is not None:
        p_success = p_text(fisher_p(reference, compared, "repaired"))
        p_test_preserving = p_text(fisher_p(reference, compared, "test_preserving"))
    return [label, str(len(group)),
            share(count(group, "repaired"), len(group)), p_success,
            share(count(group, "test_preserving"), len(group)), p_test_preserving]


def table_visibility(cases: list[Case]) -> Table:
    public = [c for c in cases if c.visibility == "public"]
    private = [c for c in cases if c.visibility == "private"]
    rows = [[label, share(count(public, name), len(public)),
             share(count(private, name), len(private)),
             f"{fisher_p(public, private, name):.2g}"] for label, name in OUTCOMES]
    header = ["Metric", f"Public (n = {len(public)}) (%)", f"Private (n = {len(private)}) (%)",
              "p"]
    return Table("tab:visibility", "Commit-scoped cases: all public against private, best of five",
                 header, rows)


def table_visibility_cutoff(cases: list[Case]) -> Table:
    pre = [c for c in cases if c.visibility == "public" and not c.post_cutoff]
    private = [c for c in cases if c.visibility == "private"]
    rows = [[label, share(count(pre, name), len(pre)), share(count(private, name), len(private)),
             fixed(fisher_p(pre, private, name), 2)] for label, name in OUTCOMES]
    header = ["Metric", f"Public before cutoff (n = {len(pre)}) (%)",
              f"Private (n = {len(private)}) (%)", "p"]
    return Table("tab:visibility-cutoff",
                 "Commit-scoped cases: public before the training cutoff against private",
                 header, rows)


def table_perproject(cases: list[Case]) -> Table:
    groups = {subject: [c for c in cases if c.subject == subject]
              for subject in {c.subject for c in cases}}
    order = sorted(groups, key=lambda s: (-Fraction(count(groups[s], "repaired"),
                                                    len(groups[s])), s))
    rows = []
    for subject in order:
        group = groups[subject]
        attempts = attempts_of(group)
        rows.append([
            subject,
            str(len(group)),
            share(count(group, "repaired"), len(group)),
            share(count(attempts, "success"), len(attempts)),
            share(count(group, "test_preserving"), len(group)),
            str(count(group, "deletion_only")),
            share(count(group, "perfect"), len(group)),
            share(count(group, "assertion"), len(group)),
            fixed(statistics.fmean(c.best_codebleu for c in group), 3),
        ])
    header = ["Subject", "Cases", "Successful repair, best of five (%)",
              "Successful repair, per attempt (%)", "Test-preserving repair, best of five (%)",
              "Deletion-only cases", "Perfect match (%)", "Assertion match (%)", "CodeBLEU"]
    return Table("tab:perproject", "Commit-scoped results per subject", header, rows)


def table_failmodes(cases: list[Case]) -> Table:
    failed = [a for a in attempts_of(cases) if not a.success]
    counts = [(label, sum(a.final_state == state for a in failed))
              for state, label in FAILED_STATES]
    if sum(n for _, n in counts) != len(failed):
        raise ValueError("a failed attempt has a final state that no row counts")
    rows = [[label, str(n), share(n, len(failed))] for label, n in counts]
    rows.append(["Total", str(len(failed)), share(len(failed), len(failed))])
    header = ["Final state", "Attempts", "Share (%)"]
    return Table("tab:failmodes", "Failed commit-scoped attempts by final state", header, rows)


def table_agent_behavior(cases: list[Case]) -> Table:
    attempts = attempts_of(cases)
    rows = [
        ["Ended with an edit it never tested", str(count(attempts, "last_edit_untested"))],
        ["Never ran the tests", str(count(attempts, "never_ran_tests"))],
        ["Ended by the 128000-token context limit", str(count(attempts, "context_limit_stop"))],
        ["Passed only after a test run that failed after an edit",
         str(count(attempts, "feedback_revising"))],
    ]
    return Table("tab:agent-behavior", "Agent behaviours in the commit-scoped attempts",
                 ["Behaviour", "Attempts"], rows)


def table_repair_size(cases: list[Case]) -> Table:
    rows = []
    for field, meaning, measure in MEASURES:
        values = [measure(c) for c in cases]
        rows.append([field, meaning, whole(statistics.median(values)), str(max(values)),
                     str(sum(values))])
    header = ["Field", "What it counts", "Median", "Maximum", "Total"]
    return Table("tab:repair-size", "Repair-size measures of the commit-scoped cases", header, rows)


# --------------------------------------------------------------------- prose numbers
def _prose(section: str, printed: str, computed: str, definition: str,
           population: str) -> ProseRow:
    return [section, printed, computed, definition, population]


def prose_numbers(scenarios: list[Scenario], cases: list[Case]) -> list[ProseRow]:
    return (_prose_intro_method(cases)
            + _prose_rq1_rq2(scenarios, cases)
            + _prose_rq3_stability_cost(scenarios, cases)
            + _prose_rq4_discussion(cases))


def _prose_intro_method(cases: list[Case]) -> list[ProseRow]:
    repaired = count(cases, "repaired")
    return [
        _prose("Introduction", "20.9 %", share(count(cases, "deletion_only"), repaired),
               "Repaired commit-scoped cases that passed only through attempts that deleted "
               "test methods, as a share of repaired cases", f"{repaired} repaired cases"),
        _prose("Results: overall", "23 of these 110 cases",
               str(count(cases, "deletion_only")),
               "Repaired commit-scoped cases that passed only through attempts that deleted "
               "test methods", f"{repaired} repaired cases"),
        _prose("Introduction", "393 test classes", str(sum(c.targets for c in cases)),
               "Repair targets (test classes) summed over all commit-scoped cases",
               f"{len(cases)} commit-scoped cases"),
        _prose("Introduction", "65 of the cases span more than one class",
               str(sum(c.targets > 1 for c in cases)),
               "Commit-scoped cases with more than one repair target",
               f"{len(cases)} commit-scoped cases"),
        _prose("Methodology", "75 originate from commits before November 2023",
               str(sum(not c.post_cutoff for c in cases)),
               "Commit-scoped cases whose commit predates November 2023 (post_cutoff false)",
               f"{len(cases)} commit-scoped cases"),
        _prose("Methodology", "100 originate from November 2023 onwards",
               str(sum(c.post_cutoff for c in cases)),
               "Commit-scoped cases whose commit is from November 2023 onwards (post_cutoff true)",
               f"{len(cases)} commit-scoped cases"),
        _prose("Methodology", "three P4 cases whose tests are built with Maven",
               str(sum(c.subject == "P4" and c.build_tool == "Maven" for c in cases)),
               "P4 cases whose build tool is Maven", "7 P4 cases"),
        _prose("Methodology", "only 13 have six or more",
               str(sum(c.targets >= 6 for c in cases)),
               "Commit-scoped cases with six or more repair targets",
               f"{len(cases)} commit-scoped cases"),
        _prose("Methodology", "holds at least 21 cases",
               str(min(len(band(cases, low, high)) for low, high, _ in BANDS)),
               "Smallest band of the repair-size method axis, in commit-scoped cases",
               f"{len(cases)} commit-scoped cases"),
    ]


def _prose_rq1_rq2(scenarios: list[Scenario], cases: list[Case]) -> list[ProseRow]:
    gpt = [s for s in scenarios if s.model == GPT]
    gemini = [s for s in scenarios if s.model == GEMINI]
    sonnet = [s for s in scenarios if s.model == SONNET]
    sonnet_alluxio = [s for s in sonnet if s.subject == "alluxio"]
    one = [c for c in cases if c.one_file_one_method]
    one_public = [c for c in one if c.visibility == "public"]
    one_private = [c for c in one if c.visibility == "private"]
    attempts = attempts_of(cases)

    stronger = [s for s in scenarios if s.model in (GPT, GEMINI)]
    subjects = sorted({s.subject for s in stronger})
    subject_pairs = [[s for s in stronger if s.model == model and s.subject == subject]
                     for model in (GPT, GEMINI) for subject in subjects]
    subject_rates = [pct(count(pair, "success"), len(pair)) for pair in subject_pairs]

    def vs_scenarios(group: list[Case]) -> float:
        """Fisher p-value of successful repair: cases against all GPT-4o scenarios."""
        return fisher_counts(count(group, "repaired"), len(group), count(gpt, "success"), len(gpt))

    p_public = vs_scenarios(one_public)
    p_private = vs_scenarios(one_private)
    p_visibility = fisher_p(one_public, one_private, "repaired")

    return [
        _prose("Results: RQ1", "repaired on every attempt",
               whole(statistics.median(count(s.attempts, "success") for s in gpt)),
               "Median over GPT-4o scenarios of successful attempts out of five",
               f"{len(gpt)} GPT-4o file-scoped scenarios"),
        _prose("Results: RQ1", "repaired on every attempt",
               whole(statistics.median(count(s.attempts, "success") for s in gemini)),
               "Median over Gemini Flash-2.5 scenarios of successful attempts out of five",
               f"{len(gemini)} Gemini Flash-2.5 file-scoped scenarios"),
        _prose("Results: RQ1", "two of five attempts under perfect match",
               whole(statistics.median(count(s.attempts, "perfect") for s in gpt)),
               "Median over GPT-4o scenarios of perfect-match attempts out of five",
               f"{len(gpt)} GPT-4o file-scoped scenarios"),
        _prose("Results: RQ1", "about 2.6 times as much per attempt",
               fixed(mean_cost(gpt) / mean_cost(gemini), 1),
               "Mean cost per attempt, GPT-4o over Gemini Flash-2.5",
               f"{len(gpt)} scenarios per model (scenario means)"),
        _prose("Results: RQ1", "roughly a factor of six",
               fixed(mean_cost(sonnet) / mean_cost(gemini), 1),
               "Mean cost per attempt, Claude Sonnet-4 over Gemini Flash-2.5",
               f"{len(sonnet)} scenarios per model (scenario means)"),
        _prose("Results: RQ1", "23.9 % of the 88 file-scoped alluxio scenarios",
               share(count(sonnet_alluxio, "success"), len(sonnet_alluxio)),
               "Claude Sonnet-4 successful repair on alluxio scenarios",
               f"{len(sonnet_alluxio)} alluxio scenarios"),
        _prose("Results: RQ2", "p < 0.001", f"{p_public:.4g}",
               "Two-sided Fisher test, successful repair: one-file-one-method public cases "
               f"against all GPT-4o file-scoped scenarios ({verdict(p_public)})",
               f"{len(one_public)} public cases; {len(gpt)} scenarios"),
        _prose("Results: RQ2", "p = 0.30", f"{p_private:.4g}",
               "Two-sided Fisher test, successful repair: one-file-one-method private cases "
               f"against all GPT-4o file-scoped scenarios ({verdict(p_private)})",
               f"{len(one_private)} private cases; {len(gpt)} scenarios"),
        _prose("Results: RQ2", "p = 0.15", f"{p_visibility:.4g}",
               "Two-sided Fisher test, successful repair: one-file-one-method public cases "
               f"against private cases ({verdict(p_visibility)})",
               f"{len(one_public)} public; {len(one_private)} private cases"),
        _prose("Results: RQ2", "keeps 64 % of its file-scoped value",
               retained(count(cases, "repaired"), len(cases), count(gpt, "success"), len(gpt)),
               "Successful repair of all commit-scoped cases as a share of GPT-4o's file-scoped "
               "successful repair", f"{len(cases)} cases; {len(gpt)} scenarios"),
        _prose("Results: RQ2", "keeps 25 %",
               retained(count(cases, "assertion"), len(cases), count(gpt, "assertion"), len(gpt)),
               "Assertion match, commit-scoped as a share of GPT-4o's file-scoped assertion match",
               f"{len(cases)} cases; {len(gpt)} scenarios"),
        _prose("Results: RQ2", "keeps only 12 %",
               retained(count(cases, "perfect"), len(cases), count(gpt, "perfect"), len(gpt)),
               "Perfect match, commit-scoped as a share of GPT-4o's file-scoped perfect match",
               f"{len(cases)} cases; {len(gpt)} scenarios"),
        _prose("Results: RQ2", "about 7.5 times as much as a file-scoped one",
               fixed(statistics.fmean(a.cost for a in attempts) / mean_cost(gpt), 1),
               "Mean cost per commit-scoped attempt over the mean cost per GPT-4o file-scoped "
               "attempt", f"{len(attempts)} commit-scoped attempts; {len(gpt)} scenarios"),
        _prose("Results: RQ1", "above 91 % on every subject", fixed(min(subject_rates), 1),
               "Lowest per-subject successful repair of GPT-4o and Gemini Flash-2.5 on "
               "file-scoped scenarios", f"{len(subject_rates)} model-subject pairs"),
        _prose("Results: RQ2",
               "seven and six cases fall into the bands of six to ten and of more than ten targets",
               str(sum(6 <= c.targets <= 10 for c in cases)),
               "Commit-scoped cases with six to ten repair targets",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: RQ2", "six cases fall into the bands of more than ten targets",
               str(sum(c.targets > 10 for c in cases)),
               "Commit-scoped cases with more than ten repair targets",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: RQ2", "110 of the 175 cases have a single target",
               str(sum(c.targets == 1 for c in cases)),
               "Commit-scoped cases with one repair target", f"{len(cases)} commit-scoped cases"),
    ]


def _prose_rq3_stability_cost(scenarios: list[Scenario], cases: list[Case]) -> list[ProseRow]:
    gpt = [s for s in scenarios if s.model == GPT]
    public = [c for c in cases if c.visibility == "public"]
    private = [c for c in cases if c.visibility == "private"]
    repaired = [c for c in cases if c.repaired]
    successful = [a for a in attempts_of(cases) if a.success]
    failed = [a for a in attempts_of(cases) if not a.success]
    first_attempt_repairs = sum(c.attempts[0].success for c in cases)
    p_gap = fisher_p(private, public, "test_preserving")

    def pass_at(k: int, name: str) -> int:
        """Cases with the flag `name` on one of their first k attempts."""
        return sum(any(getattr(a, name) for a in c.attempts[:k]) for c in cases)

    def failed_of(subject: str) -> list[Attempt]:
        return [a for c in cases if c.subject == subject for a in c.attempts if not a.success]

    gson, neo4j = failed_of("gson"), failed_of("neo4j-java-driver")
    return [
        _prose("Results: RQ3", "lowers the public rate by 14.9 points",
               fixed(pct(count(public, "repaired"), len(public))
                     - pct(count(public, "test_preserving"), len(public)), 1),
               "Public successful repair minus public test-preserving repair, best of five",
               f"{len(public)} public cases"),
        _prose("Results: RQ3", "the private one by only 7.3 points",
               fixed(pct(count(private, "repaired"), len(private))
                     - pct(count(private, "test_preserving"), len(private)), 1),
               "Private successful repair minus private test-preserving repair, best of five",
               f"{len(private)} private cases"),
        _prose("Results: RQ3", "17.9 points",
               fixed(pct(count(private, "test_preserving"), len(private))
                     - pct(count(public, "test_preserving"), len(public)), 1),
               "Private minus public test-preserving repair, best of five; Fisher p = "
               f"{p_gap:.2g}, {verdict(p_gap)}",
               f"{len(private)} private; {len(public)} public cases"),
        _prose("Results: subjects", "29.8 % of its 57",
               share(sum(a.final_state == "test does not compile" for a in gson), len(gson)),
               "Failed gson attempts whose final state is test does not compile",
               f"{len(gson)} failed gson attempts"),
        _prose("Results: subjects", "76.7 % of its 60",
               share(sum(a.final_state == "test does not compile" for a in neo4j), len(neo4j)),
               "Failed neo4j-java-driver attempts whose final state is test does not compile",
               f"{len(neo4j)} failed neo4j-java-driver attempts"),
        _prose("Results: subjects", "hold 19 of the 23 commit-scoped cases",
               str(sum(c.deletion_only for c in cases
                       if c.subject in {"gson", "xwiki-commons", "alluxio", "druid"})),
               "Deletion-only cases of gson, xwiki-commons, alluxio and druid",
               f"{count(cases, 'deletion_only')} deletion-only cases"),
        _prose("Results: stability", "25.1 % pass on every attempt",
               share(sum(c.successes == len(c.attempts) for c in cases), len(cases)),
               "Commit-scoped cases with a successful attempt on every attempt",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: stability", "37.1 % pass on none",
               share(sum(c.successes == 0 for c in cases), len(cases)),
               "Commit-scoped cases with no successful attempt",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: stability", "71.4 % of GPT-4o's 395 file-scoped scenarios",
               share(sum(all(a.success for a in s.attempts) for s in gpt), len(gpt)),
               "GPT-4o file-scoped scenarios with a successful attempt on every attempt",
               f"{len(gpt)} GPT-4o file-scoped scenarios"),
        _prose("Results: stability", "78.3 % never keep the developer's assertions",
               share(sum(not c.assertion for c in cases), len(cases)),
               "Commit-scoped cases with no assertion match on any attempt",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: stability", "91.4 % never reproduce the developer's repair",
               share(sum(not c.perfect for c in cases), len(cases)),
               "Commit-scoped cases with no perfect match on any attempt",
               f"{len(cases)} commit-scoped cases"),
        _prose("Related work", "66 of the 175 cases were repaired on some of their five attempts",
               str(sum(0 < c.successes < len(c.attempts) for c in cases)),
               "Commit-scoped cases with some but not all attempts successful",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: stability", "44.0 % of the 175 commit-scoped cases after one attempt",
               share(pass_at(1, "success"), len(cases)),
               "Cases with a successful first attempt (pass@1), as a share of all cases",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: stability", "59.4 % after three",
               share(pass_at(3, "success"), len(cases)),
               "Cases with a successful attempt among the first three (pass@3)",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: stability", "34.9 % after one attempt",
               share(pass_at(1, "test_preserving"), len(cases)),
               "Cases with a test-preserving first attempt (pass@1), as a share of all cases",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: stability", "48.6 % after three",
               share(pass_at(3, "test_preserving"), len(cases)),
               "Cases with a test-preserving attempt among the first three (pass@3)",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: stability", "30.0 % of the 110 eventual repairs undone",
               share(len(repaired) - first_attempt_repairs, len(repaired)),
               "Repaired cases not repaired by attempt 1, as a share of repaired cases",
               f"{len(repaired)} repaired cases"),
        _prose("Results: cost", "They cost $0.170 on average",
               fixed(statistics.fmean(a.cost for a in successful), 3),
               "Mean cost of a successful commit-scoped attempt",
               f"{len(successful)} successful attempts"),
        _prose("Results: cost", "with a median of $0.099",
               fixed(statistics.median(a.cost for a in successful), 3),
               "Median cost of a successful commit-scoped attempt",
               f"{len(successful)} successful attempts"),
        _prose("Results: cost", "the most expensive of them costs $2.53",
               fixed(max(a.cost for a in successful), 2),
               "Maximum cost of a successful commit-scoped attempt",
               f"{len(successful)} successful attempts"),
        _prose("Results: cost", "They cost $0.457 on average",
               fixed(statistics.fmean(a.cost for a in failed), 3),
               "Mean cost of a failed commit-scoped attempt", f"{len(failed)} failed attempts"),
        _prose("Results: cost", "about 2.7 times as much as a successful attempt",
               fixed(statistics.fmean(a.cost for a in failed)
                     / statistics.fmean(a.cost for a in successful), 1),
               "Mean cost of a failed attempt over that of a successful attempt",
               f"{len(failed)} failed; {len(successful)} successful attempts"),
        _prose("Results: cost", "with a median of $0.323",
               fixed(statistics.median(a.cost for a in failed), 3),
               "Median cost of a failed commit-scoped attempt", f"{len(failed)} failed attempts"),
        _prose("Results: cost", "reaches $4.43 for a single attempt",
               fixed(max(a.cost for a in failed), 2),
               "Maximum cost of a failed commit-scoped attempt", f"{len(failed)} failed attempts"),
        _prose("Results: cost", "GPT-4o's 1975 file-scoped attempts",
               str(sum(len(s.attempts) for s in gpt)),
               "GPT-4o file-scoped attempts",
               "395 GPT-4o file-scoped scenarios, five attempts each"),
    ]


def _prose_rq4_discussion(cases: list[Case]) -> list[ProseRow]:
    attempts = attempts_of(cases)
    successful = [a for a in attempts if a.success]
    failed = [a for a in attempts if not a.success]
    never_ran = [a for a in attempts if a.never_ran_tests]
    traceable = [a for a in attempts if a.feedback_traceable]
    public = [c for c in cases if c.visibility == "public"]
    private = [c for c in cases if c.visibility == "private"]
    pre_cutoff = [c for c in public if not c.post_cutoff]
    deletion_only = [c for c in cases if c.deletion_only]
    flat = [c for c in cases if all(fixed(a.codebleu, 3) == "0.996" for a in c.attempts)]
    population = f"{len(private)} private and {len(public)} public cases"
    alluxio = [a for c in cases if c.subject == "alluxio" for a in c.attempts]

    def gap(name: str) -> str:
        """Private minus public, in percentage points."""
        return fixed(pct(count(private, name), len(private))
                     - pct(count(public, name), len(public)), 1)

    return [
        _prose("Results: RQ4", "one of the druid cases", str(len(flat)),
               "Commit-scoped cases whose five attempts all score CodeBLEU 0.996",
               f"{len(cases)} commit-scoped cases"),
        _prose("Results: RQ4", "scores 0.996 on all five attempts",
               fixed(flat[0].attempts[0].codebleu, 3) if flat else "",
               "CodeBLEU of each of the five attempts of that case",
               f"{len(flat)} commit-scoped case(s)"),
        _prose("Results: RQ4", "four because the agent stopped",
               str(sum(not a.context_limit_stop for a in never_ran)),
               "Never-ran attempts not ended by the context limit",
               f"{len(never_ran)} never-ran attempts"),
        _prose("Results: RQ4", "five because the context limit ended them first",
               str(sum(a.context_limit_stop for a in never_ran)),
               "Never-ran attempts ended by the context limit",
               f"{len(never_ran)} never-ran attempts"),
        _prose("Results: RQ4", "53.0 % never reach an assertion",
               share(sum(a.final_state in NO_ASSERTION_STATES for a in failed), len(failed)),
               "Failed attempts whose final state is test does not compile, tests never run, "
               "no test selected or licence check fails", f"{len(failed)} failed attempts"),
        _prose("Results: RQ4", "34.5 %, passed only after a test run that had failed following "
                               "an edit",
               share(count(traceable, "feedback_revising"), len(traceable)),
               "Traceable successful attempts that passed only after a failed test run that "
               "followed an edit", f"{len(traceable)} traceable successful attempts"),
        _prose("Discussion", "successful attempts average 0.668",
               fixed(statistics.fmean(a.codebleu for a in successful), 3),
               "Mean CodeBLEU of successful attempts", f"{len(successful)} successful attempts"),
        _prose("Discussion", "against 0.493 for failures",
               fixed(statistics.fmean(a.codebleu for a in failed), 3),
               "Mean CodeBLEU of failed attempts", f"{len(failed)} failed attempts"),
        _prose("Discussion", "assertion match by 13.1 points", gap("assertion"),
               "Private minus public assertion match, best of five; Fisher p = "
               f"{fisher_p(private, public, 'assertion'):.2g}, "
               f"{verdict(fisher_p(private, public, 'assertion'))}", population),
        _prose("Discussion", "perfect match by 4.7 points", gap("perfect"),
               "Private minus public perfect match, best of five; Fisher p = "
               f"{fisher_p(private, public, 'perfect'):.2g}, "
               f"{verdict(fisher_p(private, public, 'perfect'))}", population),
        _prose("Discussion", "successful repair by 10.3 points", gap("repaired"),
               "Private minus public successful repair, best of five; Fisher p = "
               f"{fisher_p(private, public, 'repaired'):.2g}, "
               f"{verdict(fisher_p(private, public, 'repaired'))}", population),
        _prose("Discussion", "94 of GPT-4o's 384 successful attempts lost test methods",
               str(sum(not a.test_preserving for a in successful)),
               "Successful attempts that lost test methods (not test-preserving)",
               f"{len(successful)} successful attempts"),
        _prose("Discussion", "nine of alluxio's 115 commit-scoped attempts",
               str(sum(a.final_state == "license check fails" for a in alluxio)),
               "Alluxio attempts that end at the licence-header check",
               f"{len(alluxio)} alluxio attempts"),
        _prose("Discussion",
               "20 of the 23 cases repaired only by attempts that deleted test methods are public",
               str(sum(c.visibility == "public" for c in deletion_only)),
               "Deletion-only cases that are public", f"{len(deletion_only)} deletion-only cases"),
        _prose("Related work", "56.0 % of the 134 public commit-scoped cases predate",
               share(len(pre_cutoff), len(public)),
               "Public cases from before the training cutoff as a share of public cases",
               f"{len(public)} public cases"),
    ]


# --------------------------------------------------------------------- output
def write_csv(path: Path, header: list[str], rows: list[list[str]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def markdown(table: Table) -> str:
    def line(cells: list[str]) -> str:
        return "| " + " | ".join(cell or "—" for cell in cells) + " |"

    lines = [f"## {table.title}", "", line(table.header), line(["---"] * len(table.header))]
    lines += [line(row) for row in table.rows]
    return "\n".join(lines) + "\n"


def write_table(table: Table, out: Path) -> None:
    stem = table.label.replace(":", "-")
    write_csv(out / f"{stem}.csv", table.header, table.rows)
    (out / f"{stem}.md").write_text(markdown(table), encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Regenerate the RepairGent thesis tables.")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="output folder (default: tables/ next to analysis/)")
    args = parser.parse_args(argv)
    scenarios, cases = load_scenarios(), load_cases()
    tables = [
        table_fs_models(scenarios),
        table_repos(scenarios, cases),
        table_headline(cases),
        table_settings(scenarios, cases),
        table_scope(cases),
        table_scope_groups(cases),
        table_visibility(cases),
        table_visibility_cutoff(cases),
        table_perproject(cases),
        table_failmodes(cases),
        table_agent_behavior(cases),
        table_repair_size(cases),
    ]
    args.out.mkdir(parents=True, exist_ok=True)
    for table in tables:
        write_table(table, args.out)
    write_csv(args.out / "prose-numbers.csv",
              ["section", "thesis_prints", "computed", "definition", "population"],
              prose_numbers(scenarios, cases))
    print(f"wrote {len(tables)} tables and prose-numbers.csv to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
