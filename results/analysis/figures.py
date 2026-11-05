from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path

import matplotlib
import numpy as np

from common import (
    PACKAGE,
    PUBLIC_SUBJECTS,
    as_bool,
    as_float,
    read_commit_attempts,
    read_commit_cases,
    read_file_attempts,
    read_file_scenarios,
)

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams.update({
    "font.size": 9, "axes.labelsize": 9, "xtick.labelsize": 8.5, "ytick.labelsize": 8.5,
    "legend.fontsize": 8.5, "pdf.fonttype": 42, "ps.fonttype": 42,

    "font.family": "sans-serif", "font.sans-serif": ["Arial", "Helvetica", "DejaVu Sans"],
    "axes.spines.top": True, "axes.spines.right": True,
})
WIDTH = 6.3

MODELS = ["GPT-4o", "Gemini Flash-2.5", "Claude Sonnet-4"]
LEGEND = {"GPT-4o": "OpenAI GPT-4o", "Gemini Flash-2.5": "Google Gemini Flash-2.5",
          "Claude Sonnet-4": "Anthropic Claude Sonnet-4"}
BAR_COLOUR = {"GPT-4o": "tab:green", "Gemini Flash-2.5": "tab:orange", "Claude Sonnet-4": "tab:blue"}
SET3 = plt.cm.Set3(np.linspace(0, 1, 3))
VIOLIN_COLOUR = {"GPT-4o": SET3[2], "Gemini Flash-2.5": SET3[1], "Claude Sonnet-4": SET3[0]}
SHORT_SUBJECT = {"neo4j-java-driver": "neo4j", "xwiki-commons": "xwiki"}

def save(fig, out: Path, name: str) -> None:
    fig.savefig(out / f"{name}.pdf", bbox_inches="tight", pad_inches=0.02,
                metadata={"CreationDate": None})
    plt.close(fig)
    print(f"wrote {name}.pdf")

def percent_true(rows: list[dict[str, str]], column: str) -> float:
    return 100 * sum(as_bool(r[column]) for r in rows) / len(rows)

def flag(column: str):
    return lambda attempt: as_bool(attempt[column])

def grouped_bars(groups: list[str], series: list[tuple[str, str, list[float]]], ylabel: str,
                 out: Path, name: str, height: float) -> None:
    fig, ax = plt.subplots(figsize=(WIDTH, height))
    x = np.arange(len(groups))
    n = len(series)
    width = 0.8 / n
    for i, (label, colour, values) in enumerate(series):
        bars = ax.bar(x + (i - (n - 1) / 2) * width, values, width, label=label, color=colour,
                      zorder=3)
        for b, v in zip(bars, values):
            ax.annotate(f"{v:.1f}%", (b.get_x() + b.get_width() / 2, v), xytext=(0, 2),
                        textcoords="offset points", ha="center", va="bottom", fontsize=6.5,
                        rotation=90)
    ax.set_xticks(x, groups)
    ax.set_ylim(0, 122)
    ax.set_yticks(range(0, 101, 20))
    ax.set_ylabel(ylabel)
    ax.grid(axis="y", linestyle="--", alpha=0.5, zorder=0)
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.11), ncol=n, frameon=False,
              handlelength=1.0, columnspacing=1.6)
    save(fig, out, name)

def style_violin(parts, colours) -> None:
    for pc, colour in zip(parts["bodies"], colours):
        pc.set_facecolor(colour)
        pc.set_edgecolor("dimgray")
        pc.set_alpha(0.75)
    for key in ("cmeans", "cmedians"):
        parts[key].set_color("black")
        parts[key].set_linewidth(1.0)
    for key in ("cbars", "cmins", "cmaxes"):
        parts[key].set_color("tab:blue")
        parts[key].set_linewidth(0.9)

def violin_panels(panels: list[tuple[str, list[list[float]]]], labels: list[str], colours: list,
                  ylabel: str, out: Path, name: str) -> None:
    fig, axes = plt.subplots(1, len(panels), figsize=(WIDTH, 2.7), sharey=True)
    for ax, (title, data) in zip(axes, panels):
        parts = ax.violinplot(dataset=data, showmeans=True, showmedians=True, showextrema=True)
        style_violin(parts, colours)
        ax.set_xticks(range(1, len(labels) + 1), labels, rotation=25, ha="right",
                      rotation_mode="anchor")
        ax.set_title(title, fontsize=9)
        ax.set_ylim(-4, 104)
        ax.grid(axis="y", linestyle="--", alpha=0.5)
    axes[0].set_ylabel(ylabel)
    fig.tight_layout(w_pad=0.6)
    save(fig, out, name)

def violins(data: list[list[float]], labels: list[str], colours: list, ylabel: str, out: Path,
            name: str, ylim=(-4, 104)) -> None:
    fig, ax = plt.subplots(figsize=(WIDTH * 0.62, 2.9))
    parts = ax.violinplot(dataset=data, showmeans=True, showmedians=True, showextrema=True)
    style_violin(parts, colours)
    ax.set_xticks(range(1, len(labels) + 1), labels)
    if ylim:
        ax.set_ylim(*ylim)
    ax.set_ylabel(ylabel)
    ax.grid(axis="y", linestyle="--", alpha=0.5)
    save(fig, out, name)

def fs_bars(out: Path, column: str, ylabel: str, name: str, height: float) -> None:
    scenarios = read_file_scenarios()
    series = []
    for model in MODELS:
        rows = [r for r in scenarios if r["model"] == model and r["subject"] in PUBLIC_SUBJECTS]
        values = [percent_true([r for r in rows if r["subject"] == s], column)
                  for s in PUBLIC_SUBJECTS]
        series.append((LEGEND[model], BAR_COLOUR[model], values + [percent_true(rows, column)]))
    grouped_bars([*PUBLIC_SUBJECTS, "All"], series, ylabel, out, name, height)

def fs_success_by_subject(out: Path) -> None:
    fs_bars(out, "success", "Successful repair (%)", "fs_success_by_subject", height=3.1)

def fs_perfect_by_subject(out: Path) -> None:
    fs_bars(out, "perfect_match", "Perfect match (%)", "fs_perfect_by_subject", height=2.6)

def fs_assertion_by_subject(out: Path) -> None:
    fs_bars(out, "assertion_match", "Assertion match (%)", "fs_assertion_by_subject", height=2.6)

def fs_violins(out: Path) -> None:
    attempts = defaultdict(list)
    for row in read_file_attempts():
        attempts[(row["subject"], row["scenario"], row["model"])].append(row)
    panels = []
    for column, title in [("success", "Successful repair"), ("assertion_match", "Assertion match"),
                          ("perfect_match", "Perfect match")]:
        per_model = [[percent_true(rows, column) for (subject, _, model), rows in attempts.items()
                      if model == name and subject in PUBLIC_SUBJECTS] for name in MODELS]
        panels.append((title, per_model))
    violin_panels(panels, MODELS, [VIOLIN_COLOUR[m] for m in MODELS],
                  "Attempts per scenario (%)", out, "fs_violins")

def fs_violin_cost(out: Path) -> None:
    scenarios = read_file_scenarios()
    cost = [[as_float(r["cost_per_attempt_usd"]) for s in PUBLIC_SUBJECTS for r in scenarios
             if r["subject"] == s and r["model"] == model] for model in MODELS]
    violins(cost, MODELS, [VIOLIN_COLOUR[m] for m in MODELS], "Mean cost per attempt (USD)",
            out, "fs_violin_cost", ylim=(0, None))

def commit_scoped() -> tuple[list[tuple[str, str]], dict[tuple[str, str], list[dict]]]:
    cases = read_commit_cases()
    attempts = defaultdict(list)
    for row in read_commit_attempts():
        attempts[(row["subject"], row["case_id"])].append(row)
    for rows in attempts.values():
        rows.sort(key=lambda r: int(r["attempt"]))
    return [(c["subject"], c["case_id"]) for c in cases], attempts

def cases_with(keys, attempts, predicate) -> float:
    hit = [any(predicate(a) for a in attempts[k]) for k in keys]
    return 100 * sum(hit) / len(hit)

def cases_passing_by(keys, attempts, k: int, column: str) -> float:
    hit = [any(as_bool(a[column]) for a in attempts[key] if int(a["attempt"]) <= k)
           for key in keys]
    return 100 * sum(hit) / len(hit)

def per_case_percent(keys, attempts, column: str) -> list[float]:
    return [100 * sum(as_bool(a[column]) for a in attempts[k]) / len(attempts[k]) for k in keys]

def cs_subject_bars(groups, successful, preserving, assertion, perfect, out: Path, name: str) -> None:
    fig, ax = plt.subplots(figsize=(WIDTH, 3.3))
    x = np.arange(len(groups))
    w = 0.27
    deleted = [s - t for s, t in zip(successful, preserving)]
    ax.bar(x - w, preserving, w, color="tab:green", label="Successful repair, test-preserving",
           zorder=3)
    ax.bar(x - w, deleted, w, bottom=preserving, color="white", edgecolor="tab:green",
           hatch="////", linewidth=0.8, label="Successful only with tests deleted", zorder=3)
    ax.bar(x, assertion, w, color="tab:purple", label="Assertion match", zorder=3)
    ax.bar(x + w, perfect, w, color="tab:brown", label="Perfect match", zorder=3)
    for xs, vals in [(x - w, successful), (x, assertion), (x + w, perfect)]:
        for xi, v in zip(xs, vals):
            ax.annotate(f"{v:.1f}%", (xi, v), xytext=(0, 2), textcoords="offset points",
                        ha="center", va="bottom", fontsize=6.0, rotation=90)
    ax.set_xticks(x, groups)
    ax.set_ylim(0, 122)
    ax.set_yticks(range(0, 101, 20))
    ax.set_ylabel("Cases, best of five (%)")
    ax.grid(axis="y", linestyle="--", alpha=0.5, zorder=0)
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.11), ncol=2, frameon=False,
              handlelength=1.4, columnspacing=1.6)
    save(fig, out, name)

def cs_by_subject(out: Path) -> None:
    keys, attempts = commit_scoped()
    subjects = defaultdict(list)
    for key in keys:
        subjects[key[0]].append(key)
    order = sorted(subjects, key=lambda s: (-cases_with(subjects[s], attempts, flag("success")), s))
    sets = [subjects[s] for s in order] + [keys]
    groups = [SHORT_SUBJECT.get(s, s) for s in order] + ["All"]
    cs_subject_bars(
        groups,
        [cases_with(ks, attempts, flag("success")) for ks in sets],
        [cases_with(ks, attempts, flag("test_preserving")) for ks in sets],
        [cases_with(ks, attempts, flag("assertion_match")) for ks in sets],
        [cases_with(ks, attempts, flag("perfect_match")) for ks in sets],
        out, "cs_by_subject")

def cs_pass_at_k(out: Path) -> None:
    keys, attempts = commit_scoped()
    ks = range(1, 6)
    fig, ax = plt.subplots(figsize=(WIDTH * 0.55, 2.5))
    for test_preserving, label, colour, marker in [
            (False, "Successful repair", "tab:green", "o"),
            (True, "Test-preserving repair", "#5aa05a", "s")]:
        column = "test_preserving" if test_preserving else "success"
        ys = [cases_passing_by(keys, attempts, k, column) for k in ks]
        ax.plot(list(ks), ys, marker=marker, color=colour, label=label,
                linestyle="--" if test_preserving else "-")
        for k, y in zip(ks, ys):
            ax.annotate(f"{y:.1f}", (k, y), xytext=(0, -11 if test_preserving else 5),
                        textcoords="offset points", ha="center", fontsize=6.5)
    ax.set_xticks(list(ks))
    ax.set_xlabel("Attempts considered, $k$")
    ax.set_ylabel("Cases repaired (%)")
    ax.set_ylim(0, 100)
    ax.grid(axis="y", linestyle="--", alpha=0.5)
    ax.legend(loc="lower right", frameon=False)
    save(fig, out, "cs_pass_at_k")

def cs_violin_outcomes(out: Path) -> None:
    keys, attempts = commit_scoped()
    data = [per_case_percent(keys, attempts, column)
            for column in ("success", "test_preserving", "assertion_match", "perfect_match")]
    labels = ["Successful\nrepair", "Test-preserving\nrepair", "Assertion\nmatch", "Perfect\nmatch"]
    violins(data, labels, ["#8dd3c7", "#b3de69", "#ffed6f", "#fdb462"],
            "Attempts per case (%)", out, "cs_violin_outcomes")

def cs_violin_cost(out: Path) -> None:
    keys, attempts = commit_scoped()
    atts = [a for key in keys for a in attempts[key]]
    cost = [[as_float(a["cost_usd"]) for a in atts if as_bool(a["success"])],
            [as_float(a["cost_usd"]) for a in atts if not as_bool(a["success"])]]
    violins(cost, ["Successful attempts", "Failed attempts"], [SET3[2], SET3[0]],
            "Cost per attempt (USD)", out, "cs_violin_cost", ylim=(0, None))

FIGURES = [fs_success_by_subject, fs_perfect_by_subject, fs_assertion_by_subject, fs_violins,
           fs_violin_cost, cs_by_subject, cs_violin_outcomes, cs_pass_at_k, cs_violin_cost]

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=PACKAGE / "figures",
                        help="folder for the PDF files (default: figures/ next to analysis/)")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for figure in FIGURES:
        figure(args.out)

if __name__ == "__main__":
    main()
