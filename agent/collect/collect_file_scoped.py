import argparse
import os
import csv
import re
from pathlib import Path
from difflib import unified_diff
from typing import Optional
from tqdm import tqdm

from codebleu import calc_codebleu

DEFAULT_PROJECTS = ["xwiki-commons", "alluxio", "druid", "gson", "neo4j-java-driver"]
DEFAULT_LLMS = ["gpt-4o", "gemini-2.5-flash", "claude-sonnet-4-0"]
DEFAULT_MAX_ATTEMPTS = 5
DEFAULT_MAX_CASES = 100

ATTEMPT_HEADER = ["project", "id", "name", "llm", "attempt", "is success", "perfect match", "assertion match", "codebleu", "price"]
CASE_HEADER = ["project", "id", "name", "llm", "is success", "is perfect match", "is assertion match", "%success", "%perfect match", "%assertion match", "price"]
SOURCE_COLUMN = "source"

def extract_method_code(java_code: str, method_name: str) -> str:

    java_code = re.sub(r'//.*?$|/\*.*?\*/', '', java_code, flags=re.DOTALL | re.MULTILINE)

    method_pattern = rf'\b[\w<>\[\]]+\s+{re.escape(method_name)}\s*\(.*?\)[^\{{]*\{{'
    match = re.search(method_pattern, java_code)

    if not match:
        return ''

    start_index = match.start()
    brace_count = 0
    i = match.end() - 1

    while i < len(java_code):
        if java_code[i] == '{':
            brace_count += 1
        elif java_code[i] == '}':
            brace_count -= 1
            if brace_count == 0:
                return java_code[start_index:i + 1]
        i += 1

    return ''

def normalize_code(code: str) -> str:

    return re.sub(r'\s+', '', code)

def strip_java_comments(code):

    code = re.sub(r'/\*.*?\*/', '', code, flags=re.DOTALL)
    code = re.sub(r'//.*', '', code)
    return code

def normalize_assertion(assertion):

    assertion = strip_java_comments(assertion)
    assertion = re.sub(r'\s+', '', assertion)
    return assertion

def extract_assertions(java_code):

    clean_code = strip_java_comments(java_code)

    pattern = re.compile(
        r'\b(assert(?:Equals|True|False|Null|NotNull|Same|NotSame|That|ArrayEquals|Throws)?)\s*\((.*?)\)\s*;',
        re.DOTALL
    )
    matches = pattern.findall(clean_code)

    raw_asserts = [f"{fn}({args.strip()})" for fn, args in matches]
    normalized = [normalize_assertion(a) for a in raw_asserts]
    return list(zip(raw_asserts, normalized))

def compare_assertions(code1, code2):
    a1_raw, a1_norm = zip(*extract_assertions(code1)) if extract_assertions(code1) else ([], [])
    a2_raw, a2_norm = zip(*extract_assertions(code2)) if extract_assertions(code2) else ([], [])

    set1 = set(a1_norm)
    set2 = set(a2_norm)

    only_in_1 = [raw for raw, norm in zip(a1_raw, a1_norm) if norm not in set2]
    only_in_2 = [raw for raw, norm in zip(a2_raw, a2_norm) if norm not in set1]

    if not only_in_1 and not only_in_2:

        return True
    else:

        return False

def match(a, b, method_name):
    a = extract_method_code(a, method_name)
    b = extract_method_code(b, method_name)
    if not b:
        print(f"{method_name} Method not found in orig files.")
        return False, False

    if not a:
        print(f"{method_name} Method not found in one agent files.")
        return False, False

    an = normalize_code(a)
    bn = normalize_code(b)

    compare = compare_assertions(a, b)
    return compare, an == bn

def codebleu(agent, orig,  method_name):
    agent = extract_method_code(agent, method_name)
    orig = extract_method_code(orig, method_name)

    if not orig:
        return 0
    result = calc_codebleu([orig], [agent], lang="java")
    return result['codebleu']

CASE_LOG = re.compile(r"^case_(\d{4})_attempt_(\d+)_.+\.log$")
SUCCESS_STATUS = re.compile(r"INFO\s+(?:\[[^\]]*\]\s+)?org\.example\.utils\.Logger - Status: SUCCESS\r?\nExit code: 0")
RUN_MODEL = re.compile(r"\bmodel=(\S+)")

def run_model(run_dir: Path) -> Optional[str]:
    run_log = run_dir / "run.log"
    if not run_log.is_file():
        return None
    found = RUN_MODEL.search(run_log.read_text(encoding="utf-8", errors="replace"))
    return found.group(1) if found else None

def collect_attempt_rows(eval_dir: Path, projects: list[str], llms: list[str], max_attempts: int, max_cases: int) -> list[list]:
    rows: list[list] = []

    logs_dir = eval_dir / "logs"
    if not logs_dir.is_dir():
        print(f"No logs directory at {logs_dir}")
        return rows

    latest: dict[tuple[str, int, str, int], Path] = {}
    for run_dir in sorted(p for p in logs_dir.iterdir() if p.is_dir()):
        project = next((p for p in projects if run_dir.name.endswith("_" + p)), None)
        llm = run_model(run_dir)
        if project is None or llm is None or (llms and llm not in llms):
            continue
        for log_file in sorted(run_dir.glob("case_*_attempt_*_*.log")):
            found = CASE_LOG.match(log_file.name)
            if not found:
                continue
            case_id, attempt = int(found.group(1)) - 1, int(found.group(2))
            if case_id <= max_cases and attempt <= max_attempts:
                latest[(project, case_id, llm, attempt)] = log_file

    for (project, case_id, llm, attempt), log_file in tqdm(sorted(latest.items()), desc="attempts"):
        case_dirs = sorted((eval_dir / "results" / project).glob(f"{case_id}_*"))
        if not case_dirs:
            print(f"no results folder for {project} case {case_id}")
            continue
        case_dir = case_dirs[0]
        name = case_dir.name.split("_", 1)[1]
        log_content = log_file.read_text(encoding="utf-8", errors="replace")

        isSuccess = False
        perfectMatch = False
        assertionMatch = False
        agent_file = case_dir / llm / f"agent_{attempt}.txt"
        orig_file = case_dir / llm / "original.txt"
        price = 0

        method_name = name.split('#')[1] if '#' in name else None

        if SUCCESS_STATUS.search(log_content):
            isSuccess = True
            if not agent_file.exists() or not orig_file.exists():
                print(f"no agent or orig file for {project}_{case_id}_{attempt}")
            elif method_name is not None:
                assertionMatch, perfectMatch = match(agent_file.read_text(), orig_file.read_text(), method_name)
        dop = re.search(r'PRICE: (-?\d+(?:\.\d+)?)', log_content)
        if dop is not None:
            price = float(dop.group(1))

        agent_text = agent_file.read_text() if agent_file.exists() else ""
        orig_text = orig_file.read_text() if orig_file.exists() else ""
        cb = codebleu(agent_text, orig_text, method_name) if (orig_text and method_name is not None) else 0
        assert (not perfectMatch or assertionMatch)
        rows.append([project, case_id, name, llm, attempt, isSuccess, perfectMatch, assertionMatch, cb, price])

    return rows

def aggregate_case_rows(attempt_rows: list[list], max_attempts: int) -> list[list]:
    denominator = max(max_attempts, 1)
    grouped: dict[tuple, list[list]] = {}
    for row in attempt_rows:
        grouped.setdefault((row[0], row[1], row[2], row[3]), []).append(row)

    cases: list[list] = []
    for key in sorted(grouped, key=lambda k: (k[0], k[1], k[2], k[3])):
        group = grouped[key]
        success_count = sum(1 for row in group if row[5])
        perfect_count = sum(1 for row in group if row[6])
        assertion_count = sum(1 for row in group if row[7])
        price_sum = sum(float(row[9]) for row in group)
        cases.append([
            key[0], key[1], key[2], key[3],
            success_count > 0,
            perfect_count > 0,
            assertion_count > 0,
            success_count / denominator * 100,
            perfect_count / denominator * 100,
            assertion_count / denominator * 100,
            price_sum / max(len(group), 1),
        ])
    return cases

def write_csv(path: Path, header: list[str], rows: list[list], source: Optional[str] = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', newline='') as file:
        writer = csv.writer(file)
        if source is not None:
            writer.writerow([SOURCE_COLUMN] + header)
            for row in rows:
                writer.writerow([source] + row)
        else:
            writer.writerow(header)
            writer.writerows(rows)

def write_combined_csv(path: Path, header: list[str], tagged_rows: list[tuple[str, list]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', newline='') as file:
        writer = csv.writer(file)
        writer.writerow([SOURCE_COLUMN] + header)
        for source, row in tagged_rows:
            writer.writerow([source] + row)

def discover_project_dirs(results_root: Path) -> list[Path]:
    project_dirs: list[Path] = []

    def visit(directory: Path) -> None:
        if (directory / "evaluation").is_dir():
            project_dirs.append(directory)
            return
        for child in sorted(p for p in directory.iterdir() if p.is_dir()):
            visit(child)

    for child in sorted(p for p in results_root.iterdir() if p.is_dir()):
        visit(child)
    return project_dirs

def process_eval_dir(
    eval_dir: Path,
    projects: list[str],
    llms: list[str],
    max_attempts: int,
    max_cases: int,
    results_csv: Path,
    cases_csv: Path,
    source: Optional[str] = None,
) -> tuple[list[list], list[list]]:
    attempt_rows = collect_attempt_rows(eval_dir, projects, llms, max_attempts, max_cases)
    case_rows = aggregate_case_rows(attempt_rows, max_attempts)
    write_csv(results_csv, ATTEMPT_HEADER, attempt_rows, source=source)
    write_csv(cases_csv, CASE_HEADER, case_rows, source=source)
    label = source or str(eval_dir)
    print(f"[{label}] wrote {len(attempt_rows)} attempt rows to {results_csv}")
    print(f"[{label}] wrote {len(case_rows)} case rows to {cases_csv}")
    return attempt_rows, case_rows

def parse_csv_list(value: Optional[str], fallback: list[str]) -> list[str]:
    if value is None:
        return fallback
    return [item.strip() for item in value.split(',') if item.strip()]

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Collect test-repair metrics from agent_dir/evaluation.")
    parser.add_argument(
        "--evaluation-dir",
        help="Single evaluation directory to process. Mutually exclusive with --results-root.",
    )
    parser.add_argument(
        "--results-root",
        help=(
            "Directory scanned for project dirs containing an evaluation/. Supports a flat "
            "<root>/<project>/evaluation layout and a grouped <root>/<group>/<project>/evaluation "
            "layout (e.g. the public/private split). Mutually exclusive with --evaluation-dir."
        ),
    )
    parser.add_argument("--results-csv", help="Override path for per-dir attempt CSV (single-dir mode).")
    parser.add_argument("--cases-csv", help="Override path for per-dir case CSV (single-dir mode).")
    parser.add_argument("--combined-results-csv", help="Combined attempt CSV when using --results-root.")
    parser.add_argument("--combined-cases-csv", help="Combined case CSV when using --results-root.")
    parser.add_argument("--projects", help=f"Comma-separated project list (default: {','.join(DEFAULT_PROJECTS)}).")
    parser.add_argument("--llms", help=f"Comma-separated LLM list (default: {','.join(DEFAULT_LLMS)}).")
    parser.add_argument("--max-attempts", type=int, default=DEFAULT_MAX_ATTEMPTS)
    parser.add_argument("--max-cases", type=int, default=DEFAULT_MAX_CASES)
    return parser.parse_args()

def main() -> None:
    args = parse_args()
    if args.evaluation_dir and args.results_root:
        raise SystemExit("--evaluation-dir and --results-root are mutually exclusive")

    projects = parse_csv_list(args.projects, DEFAULT_PROJECTS)
    llms = parse_csv_list(args.llms, DEFAULT_LLMS)

    if args.results_root:
        results_root = Path(args.results_root).resolve()
        if not results_root.is_dir():
            raise SystemExit(f"--results-root not a directory: {results_root}")

        all_attempts: list[tuple[str, list]] = []
        all_cases: list[tuple[str, list]] = []
        project_dirs = discover_project_dirs(results_root)
        if not project_dirs:
            print(f"[warn] no evaluation/ directories found under {results_root}")
        for project_dir in project_dirs:
            eval_dir = project_dir / "evaluation"
            attempt_rows, case_rows = process_eval_dir(
                eval_dir,
                projects,
                llms,
                args.max_attempts,
                args.max_cases,
                eval_dir / "results.csv",
                eval_dir / "cases_results.csv",
            )
            all_attempts.extend((project_dir.name, row) for row in attempt_rows)
            all_cases.extend((project_dir.name, row) for row in case_rows)

        combined_results = Path(args.combined_results_csv).resolve() if args.combined_results_csv else results_root / "results.csv"
        combined_cases = Path(args.combined_cases_csv).resolve() if args.combined_cases_csv else results_root / "cases_results.csv"
        write_combined_csv(combined_results, ATTEMPT_HEADER, all_attempts)
        write_combined_csv(combined_cases, CASE_HEADER, all_cases)
        print(f"[combined] wrote {len(all_attempts)} attempt rows to {combined_results}")
        print(f"[combined] wrote {len(all_cases)} case rows to {combined_cases}")
        return

    if args.evaluation_dir:
        eval_dir = Path(args.evaluation_dir).resolve()
    else:
        eval_dir = Path("agent_dir/evaluation")

    results_csv = Path(args.results_csv).resolve() if args.results_csv else Path("results.csv").resolve()
    cases_csv = Path(args.cases_csv).resolve() if args.cases_csv else Path("cases_results.csv").resolve()
    process_eval_dir(eval_dir, projects, llms, args.max_attempts, args.max_cases, results_csv, cases_csv)

if __name__ == "__main__":
    main()
