"""Shared helpers for the results package: loading the tables, rates and tests.

Only the standard library is used here. Figures need matplotlib (see figures.py).
"""

from __future__ import annotations

import csv
import math
from pathlib import Path

PACKAGE = Path(__file__).resolve().parent.parent
DATA = PACKAGE / "data"
FILE_SCOPED = DATA / "file-scoped" / "public"
COMMIT_SCOPED_PUBLIC = DATA / "commit-scoped" / "public"
COMMIT_SCOPED_PRIVATE = DATA / "commit-scoped" / "private"

PRIVATE_SUBJECTS = ("P1", "P2", "P3", "P4", "P5")
PUBLIC_SUBJECTS = ("gson", "neo4j-java-driver", "druid", "alluxio", "xwiki-commons")
MODEL_NAMES = {
    "openai-gpt-4o": "GPT-4o",
    "google-chat-gemini-flash-2.5": "Gemini Flash-2.5",
    "anthropic-claude-4-sonnet": "Claude Sonnet-4",
}


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def read_file_attempts() -> list[dict[str, str]]:
    """One row per file-scoped attempt, from data/file-scoped/public/attempts.csv."""
    return read_rows(FILE_SCOPED / "attempts.csv")


def read_file_scenarios() -> list[dict[str, str]]:
    """One row per file-scoped scenario and model, from data/file-scoped/public/scenarios.csv."""
    return read_rows(FILE_SCOPED / "scenarios.csv")


def read_commit_attempts() -> list[dict[str, str]]:
    """One row per commit-scoped attempt: the public rows, then the private rows.

    Reads data/commit-scoped/public/attempts.csv and data/commit-scoped/private/attempts.csv.
    """
    return (read_rows(COMMIT_SCOPED_PUBLIC / "attempts.csv")
            + read_rows(COMMIT_SCOPED_PRIVATE / "attempts.csv"))


def read_commit_cases() -> list[dict[str, str]]:
    """One row per commit-scoped case: the public rows, then the private rows.

    Reads data/commit-scoped/public/cases.csv and data/commit-scoped/private/cases.csv.
    """
    return (read_rows(COMMIT_SCOPED_PUBLIC / "cases.csv")
            + read_rows(COMMIT_SCOPED_PRIVATE / "cases.csv"))


def as_bool(value: str) -> bool:
    return str(value).strip().lower() in {"true", "1", "yes"}


def as_float(value: str) -> float:
    text = str(value).strip()
    return float(text) if text not in {"", "None", "nan"} else 0.0


def fisher_exact_two_sided(a: int, b: int, c: int, d: int) -> float:
    """Two-sided Fisher's exact test p-value for the 2x2 table [[a, b], [c, d]].

    Sums the probabilities of every table with the same margins that is no more
    likely than the observed one (the usual two-sided definition).
    """
    row1, row2 = a + b, c + d
    col1 = a + c
    n = row1 + row2

    def prob(x: int) -> float:
        return math.comb(col1, x) * math.comb(n - col1, row1 - x) / math.comb(n, row1)

    observed = prob(a)
    low, high = max(0, row1 - (n - col1)), min(row1, col1)
    p = sum(prob(x) for x in range(low, high + 1) if prob(x) <= observed * (1 + 1e-7))
    return min(1.0, p)
