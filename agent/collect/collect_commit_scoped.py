import argparse
import csv
import json
import logging
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

class _DropCodebleuDataflowNoise(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        return "data-flow match score degenerates" not in record.getMessage()

logging.getLogger().addFilter(_DropCodebleuDataflowNoise())

ATTEMPT_HEADER = [
    "project",
    "id",
    "name",
    "llm",
    "attempt",
    "is success",
    "perfect match",
    "assertion match",
    "codebleu",
    "price",
]

CASE_HEADER = [
    "project",
    "id",
    "name",
    "llm",
    "is success",
    "is perfect match",
    "is assertion match",
    "%success",
    "%perfect match",
    "%assertion match",
    "price",
]

SOURCE_COLUMN = "source"

VISIBILITY_COLUMN = "visibility"

@dataclass
class AttemptRow:
    project: str
    case_id: int
    name: str
    llm: str
    attempt: int
    is_success: bool
    perfect_match: bool
    assertion_match: bool
    codebleu: float
    price: float

def strip_java_comments(code: str) -> str:
    code = re.sub(r"/\*.*?\*/", "", code, flags=re.DOTALL)
    return re.sub(r"//.*?$", "", code, flags=re.MULTILINE)

def normalize_code(code: str) -> str:
    return re.sub(r"\s+", "", code)

def normalize_assertion(assertion: str) -> str:
    return re.sub(r"\s+", "", strip_java_comments(assertion))

_ASSERTION_CALL_NAMES = (
    "assertEquals", "assertNotEquals", "assertArrayEquals", "assertIterableEquals",
    "assertLinesMatch", "assertTrue", "assertFalse", "assertNull", "assertNotNull",
    "assertSame", "assertNotSame", "assertThat", "assertThrows", "assertThrowsExactly",
    "assertDoesNotThrow", "assertAll", "assertTimeout", "assertTimeoutPreemptively",
    "assertContains", "assertContentEquals", "assertFails", "assertFailsWith",
    "assertIs", "assertIsNot", "assertNotEquals", "assert", "fail",
    "shouldThrow", "shouldThrowExactly", "shouldThrowAny", "shouldThrowUnit",
    "shouldNotThrow", "shouldNotThrowAny",
    "verify", "verifyOrder", "verifySequence", "verifyAll",
    "coVerify", "coVerifyOrder", "coVerifySequence", "coVerifyAll", "confirmVerified",
)

_ASSERTION_MATCHER_NAMES = (
    "shouldBe", "shouldNotBe", "shouldBeEqualTo", "shouldNotBeEqualTo", "shouldEqual",
    "shouldNotEqual", "shouldContain", "shouldNotContain", "shouldContainAll",
    "shouldContainExactly", "shouldContainExactlyInAnyOrder", "shouldContainInOrder",
    "shouldStartWith", "shouldEndWith", "shouldHaveSize", "shouldBeInstanceOf",
    "shouldBeTypeOf", "shouldBeSameInstanceAs", "shouldBeNull", "shouldNotBeNull",
    "shouldBeTrue", "shouldBeFalse", "shouldBeEmpty", "shouldNotBeEmpty",
    "shouldBeGreaterThan", "shouldBeLessThan", "shouldBeGreaterThanOrEqual",
    "shouldBeLessThanOrEqual", "shouldMatch", "shouldHaveMessage",
)

_CALL_NAME_RE = re.compile(
    r"\b(" + "|".join(
        sorted(set(_ASSERTION_CALL_NAMES + _ASSERTION_MATCHER_NAMES), key=len, reverse=True)
    ) + r")\b"
)

_INFIX_RE = re.compile(
    r"(?<![.\w])(" + "|".join(sorted(set(_ASSERTION_MATCHER_NAMES), key=len, reverse=True))
    + r")\b(?![\w(])"
)

_CLOSERS = {"(": ")", "{": "}", "<": ">"}

def _scan_balanced(code: str, start: int) -> Optional[int]:
    open_ch = code[start]
    close_ch = _CLOSERS[open_ch]
    depth = 0
    i, n = start, len(code)
    while i < n:
        ch = code[i]
        if ch == '"' and code.startswith('"""', i):
            end = code.find('"""', i + 3)
            i = n if end == -1 else end + 3
            continue
        if ch in ('"', "'"):
            quote, i = ch, i + 1
            while i < n and code[i] != quote:
                i += 2 if code[i] == "\\" else 1
            i += 1
            continue
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i + 1
        elif open_ch == "<" and ch in ";\n":
            return None
        i += 1
    return None

def _skip_ws(code: str, i: int) -> int:
    n = len(code)
    while i < n and code[i] in " \t\r\n":
        i += 1
    return i

def extract_assertions(code: str) -> list[str]:
    clean = strip_java_comments(code)
    n = len(clean)
    found: list[str] = []

    for m in _CALL_NAME_RE.finditer(clean):
        name = m.group(1)
        i = _skip_ws(clean, m.end())
        generic = ""
        if i < n and clean[i] == "<":
            end = _scan_balanced(clean, i)
            if end is not None:
                generic, i = clean[i:end], _skip_ws(clean, end)
        parts = ""
        if i < n and clean[i] == "(":
            end = _scan_balanced(clean, i)
            if end is None:
                continue
            parts, i = clean[i:end], _skip_ws(clean, end)
        if i < n and clean[i] == "{":
            end = _scan_balanced(clean, i)
            if end is not None:
                parts, i = parts + clean[i:end], end
        if not parts:
            continue

        while True:
            j = _skip_ws(clean, i)
            if j >= n or clean[j] != ".":
                break
            ident = re.match(r"\.[A-Za-z_]\w*", clean[j:])
            if not ident:
                break
            seg, j = ident.group(0), j + ident.end()
            if j < n and clean[j] == "(":
                end = _scan_balanced(clean, j)
                if end is None:
                    break
                seg, j = seg + clean[j:end], end
            k = _skip_ws(clean, j)
            if k < n and clean[k] == "{":
                end = _scan_balanced(clean, k)
                if end is not None:
                    seg, j = seg + clean[k:end], end
            parts, i = parts + seg, j
        found.append(f"{name}{generic}{parts}")

    for line in clean.splitlines():
        if _INFIX_RE.search(line):
            found.append(line.strip())

    return found

def compare_assertions(code1: str, code2: str) -> bool:
    first = {normalize_assertion(assertion) for assertion in extract_assertions(code1)}
    second = {normalize_assertion(assertion) for assertion in extract_assertions(code2)}
    return first == second

_IMPORT_RE = re.compile(
    r"^[ \t]*import\s+(?:static\s+)?[\w.*]+(?:\s+as\s+\w+)?[ \t]*;?[ \t]*$",
    re.MULTILINE,
)

SOURCE_EXTENSIONS = (".java", ".kt")

def is_source_file(path: str) -> bool:
    return path.endswith(SOURCE_EXTENSIONS)

def normalize_source(code: str) -> tuple[tuple[str, ...], str]:
    code = strip_java_comments(code)
    imports = tuple(sorted(
        re.sub(r"\s+", "", m.group(0)).rstrip(";") for m in _IMPORT_RE.finditer(code)
    ))
    body = re.sub(r"\s+", "", _IMPORT_RE.sub("", code))
    return imports, body

def class_key(path: str) -> str:
    name = Path(path).name

    name = re.sub(r"\.(java|kt|kts|groovy|scala)$", "", name)
    return name.split(".")[-1].split("_")[-1]

def load_saved_files(result_dir: Path, dir_name: str, entries: list[dict]) -> dict[str, str]:
    base = result_dir / dir_name
    files: dict[str, str] = {}
    for entry in entries:
        path = entry.get("path")
        saved_as = entry.get("savedAs")
        if not path or not saved_as:
            continue
        content = read_text_if_exists(base / saved_as)
        if content is not None:
            files[path] = content
    return files

def compare_test_files(
    gold_files: dict[str, str], agent_files: dict[str, str]
) -> tuple[bool, bool, str, str]:
    gold = {path: content for path, content in gold_files.items() if is_source_file(path)}
    if not gold:
        return False, False, "", ""

    agent_by_key: dict[str, str] = {}
    for path, content in agent_files.items():
        if is_source_file(path):
            agent_by_key.setdefault(class_key(path), content)

    matched: dict[str, str] = {}
    all_matched = True
    for path in gold:
        content = agent_files.get(path)
        if content is None:
            content = agent_by_key.get(class_key(path))
        if content is None:
            all_matched = False
        else:
            matched[path] = content

    perfect_match = all_matched and all(
        normalize_source(matched[path]) == normalize_source(gold[path]) for path in gold
    )

    order = sorted(gold)
    gold_concat = "\n\n".join(gold[path] for path in order)
    agent_concat = "\n\n".join(matched.get(path, "") for path in order)
    assertion_match = compare_assertions(agent_concat, gold_concat)

    if not extract_assertions(gold_concat):
        assertion_match = False
    return perfect_match, assertion_match, gold_concat, agent_concat

def read_properties(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}

    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties

def resolve_path(raw_path: str, base_dir: Path) -> Path:
    path = Path(raw_path)
    if not path.is_absolute():
        path = base_dir / path
    return path.resolve()

def read_text_if_exists(path: Path) -> Optional[str]:
    if path.is_file():
        return path.read_text(encoding="utf-8", errors="replace")
    return None

def parse_success(log_text: Optional[str], metrics: dict) -> bool:
    if log_text is not None:
        marker = re.search(r"ATTEMPT_END_MARKER\b.*\bsuccess=(true|false)\b", log_text)
        if marker is not None:
            return marker.group(1) == "true"
    return bool(metrics.get("success", False))

def parse_price(log_text: Optional[str], metrics: dict) -> float:
    if log_text is not None:
        price_match = re.search(r"PRICE:\s*(-?\d+(?:\.\d+)?)", log_text)
        if price_match is not None:
            return float(price_match.group(1))
    return float(metrics.get("priceDelta", metrics.get("price", 0.0)) or 0.0)

def resolve_attempt_log(agent_parent: Path, attempt_log: str) -> Path:
    if not attempt_log:
        return agent_parent / "__missing_attempt_log__"
    path = Path(attempt_log)
    if not path.is_absolute():
        path = agent_parent / path
    return path.resolve()

def load_codebleu_calculator(skip_codebleu: bool, required: bool = True):
    if skip_codebleu:
        return None
    try:
        from codebleu import calc_codebleu
    except ModuleNotFoundError:
        message = (
            "Missing Python dependency: codebleu\n"
            "Install it with: python3 -m pip install -r collect/requirements.txt\n"
            "Or run with --skip-codebleu for a quick CSV with CodeBLEU set to 0.0."
        )
        if required:
            print(message, file=sys.stderr)
            sys.exit(1)
        print("WARNING: " + message + "\nProceeding with CodeBLEU=0.0.", file=sys.stderr)
        return None
    return calc_codebleu

_CODEBLEU_LANG_ALIASES = {"kotlin": "java", "kt": "java"}

_codebleu_error_warned = False

def _codebleu_lang(language: str) -> str:
    return _CODEBLEU_LANG_ALIASES.get(language.lower(), language)

def calculate_codebleu(calc_codebleu, original_code: str, agent_code: str, language: str) -> float:
    global _codebleu_error_warned
    if calc_codebleu is None or not original_code.strip():
        return 0.0
    try:
        result = calc_codebleu([original_code], [agent_code], lang=_codebleu_lang(language))
        return float(result["codebleu"])
    except Exception as exc:
        if not _codebleu_error_warned:
            print(
                f"WARNING: CodeBLEU computation failed ({type(exc).__name__}: {exc}); "
                "reporting 0.0 for affected files. If every file fails, the tree-sitter "
                "versions are likely mismatched (see collect/requirements.txt).",
                file=sys.stderr,
            )
            _codebleu_error_warned = True
        return 0.0

def read_attempt_row(metrics_path: Path, agent_parent: Path, calc_codebleu, codebleu_language: str) -> AttemptRow:
    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    result_dir = metrics_path.parent
    attempt = int(metrics.get("attempt", metrics_path.stem.split("_")[-1]))
    log_path = resolve_attempt_log(agent_parent, str(metrics.get("attemptLog", "")))
    log_text = read_text_if_exists(log_path)
    is_success = parse_success(log_text, metrics)
    price = parse_price(log_text, metrics)

    gold_entries = metrics.get("originalFiles", [])
    if not gold_entries:
        fallback = result_dir / "original_files.json"
        if fallback.is_file():
            try:
                gold_entries = json.loads(fallback.read_text(encoding="utf-8"))
            except (ValueError, OSError):
                gold_entries = []
    gold_files = load_saved_files(
        result_dir, metrics.get("originalFilesDir", "original"), gold_entries
    )
    agent_files = load_saved_files(
        result_dir, metrics.get("attemptFilesDir", f"attempt_{attempt}"), metrics.get("attemptFiles", [])
    )
    perfect_match, assertion_match, gold_concat, agent_concat = compare_test_files(gold_files, agent_files)

    if not is_success:
        perfect_match = False
        assertion_match = False

    assertion_match = assertion_match or perfect_match

    codebleu = calculate_codebleu(calc_codebleu, gold_concat, agent_concat, codebleu_language)

    return AttemptRow(
        project=str(metrics.get("datasetName", "")),
        case_id=int(metrics.get("id", 0)),
        name=str(metrics.get("taskName", "")),
        llm=str(metrics.get("llm", "")),
        attempt=attempt,
        is_success=is_success,
        perfect_match=perfect_match,
        assertion_match=assertion_match,
        codebleu=codebleu,
        price=price,
    )

def collect_attempt_rows(evaluation_dir: Path, calc_codebleu, codebleu_language: str) -> list[AttemptRow]:
    results_dir = evaluation_dir / "results"
    if not results_dir.exists():
        return []

    agent_parent = evaluation_dir.parent
    rows = [
        read_attempt_row(metrics_path, agent_parent, calc_codebleu, codebleu_language)
        for metrics_path in results_dir.rglob("metrics_*.json")
    ]
    return sorted(rows, key=lambda row: (row.project, row.case_id, row.name, row.llm, row.attempt))

def attempt_row_values(row: AttemptRow) -> list:
    return [
        row.project,
        row.case_id,
        row.name,
        row.llm,
        row.attempt,
        row.is_success,
        row.perfect_match,
        row.assertion_match,
        row.codebleu,
        row.price,
    ]

def write_attempt_csv(path: Path, rows: list[AttemptRow], source: Optional[str] = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        header = ([SOURCE_COLUMN] + ATTEMPT_HEADER) if source is not None else ATTEMPT_HEADER
        writer.writerow(header)
        for row in rows:
            values = attempt_row_values(row)
            writer.writerow(([source] + values) if source is not None else values)

def write_combined_attempt_csv(path: Path, tagged_rows: list[tuple[str, str, AttemptRow]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow([VISIBILITY_COLUMN, SOURCE_COLUMN] + ATTEMPT_HEADER)
        for visibility, source, row in tagged_rows:
            writer.writerow([visibility, source] + attempt_row_values(row))

def aggregate_case_rows(rows: list[AttemptRow], max_attempts: int) -> list[list]:
    grouped: dict[tuple[str, int, str, str], list[AttemptRow]] = {}
    for row in rows:
        grouped.setdefault((row.project, row.case_id, row.name, row.llm), []).append(row)

    denominator = max(max_attempts, 1)
    output: list[list] = []
    for key in sorted(grouped):
        group = grouped[key]
        success_count = sum(row.is_success for row in group)
        perfect_count = sum(row.perfect_match for row in group)
        assertion_count = sum(row.assertion_match for row in group)
        avg_price = sum(row.price for row in group) / max(len(group), 1)
        project, case_id, name, llm = key
        output.append(
            [
                project,
                case_id,
                name,
                llm,
                any(row.is_success for row in group),
                any(row.perfect_match for row in group),
                any(row.assertion_match for row in group),
                success_count / denominator * 100,
                perfect_count / denominator * 100,
                assertion_count / denominator * 100,
                avg_price,
            ]
        )
    return output

def write_case_csv(path: Path, rows: list[AttemptRow], max_attempts: int, source: Optional[str] = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    aggregated = aggregate_case_rows(rows, max_attempts)
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        header = ([SOURCE_COLUMN] + CASE_HEADER) if source is not None else CASE_HEADER
        writer.writerow(header)
        for row in aggregated:
            writer.writerow(([source] + row) if source is not None else row)

def write_combined_case_csv(
    path: Path, tagged_rows: list[tuple[str, str, AttemptRow]], max_attempts: int
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    by_source: dict[tuple[str, str], list[AttemptRow]] = {}
    for visibility, source, row in tagged_rows:
        by_source.setdefault((visibility, source), []).append(row)

    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow([VISIBILITY_COLUMN, SOURCE_COLUMN] + CASE_HEADER)
        for visibility, source in sorted(by_source):
            for row in aggregate_case_rows(by_source[(visibility, source)], max_attempts):
                writer.writerow([visibility, source] + row)

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Collect SWE-like repair metrics from agent_dir/evaluation.")
    parser.add_argument("--properties", default="application.properties")
    parser.add_argument(
        "--evaluation-dir",
        help="Single evaluation directory to process (default: <agent_dir>/evaluation). Mutually exclusive with --results-root.",
    )
    parser.add_argument(
        "--results-root",
        help=(
            "Directory scanned for project dirs containing an evaluation/. Supports a flat "
            "<root>/<project>/evaluation layout and a grouped <root>/<group>/<project>/evaluation "
            "layout (e.g. the public/private split). Mutually exclusive with --evaluation-dir."
        ),
    )
    parser.add_argument("--results-csv")
    parser.add_argument("--cases-csv")
    parser.add_argument(
        "--combined-results-csv",
        help="Combined attempt CSV when using --results-root (default: <results-root>/results.csv).",
    )
    parser.add_argument(
        "--combined-cases-csv",
        help="Combined case CSV when using --results-root (default: <results-root>/cases_results.csv).",
    )
    parser.add_argument("--max-attempts", type=int)
    parser.add_argument("--codebleu-language", default="java")
    parser.add_argument("--skip-codebleu", action="store_true")
    return parser.parse_args()

RESERVED_ROOT_DIRS = {"combined", "validation", "plots", "archive"}

def discover_project_dirs(results_root: Path) -> list[tuple[Path, str]]:
    found: list[tuple[Path, str]] = []

    def visit(directory: Path, visibility: str) -> None:
        if (directory / "evaluation").is_dir():
            found.append((directory, visibility))
            return
        for child in sorted(p for p in directory.iterdir() if p.is_dir()):
            visit(child, visibility)

    for top in sorted(p for p in results_root.iterdir() if p.is_dir()):
        if top.name in RESERVED_ROOT_DIRS:
            continue
        if (top / "evaluation").is_dir():
            found.append((top, ""))
        else:
            visit(top, top.name)
    return found

def process_evaluation_dir(
    evaluation_dir: Path,
    calc_codebleu,
    codebleu_language: str,
    max_attempts: int,
    results_csv: Path,
    cases_csv: Path,
    source: Optional[str] = None,
) -> list[AttemptRow]:
    rows = collect_attempt_rows(evaluation_dir, calc_codebleu, codebleu_language)
    write_attempt_csv(results_csv, rows, source=source)
    write_case_csv(cases_csv, rows, max_attempts, source=source)
    label = source or str(evaluation_dir)
    print(f"[{label}] wrote {len(rows)} SWE attempt rows to {results_csv}")
    print(f"[{label}] wrote SWE case summary to {cases_csv}")
    return rows

def main() -> None:
    args = parse_args()
    if args.evaluation_dir and args.results_root:
        raise SystemExit("--evaluation-dir and --results-root are mutually exclusive")

    properties_path = Path(args.properties).resolve()
    properties = read_properties(properties_path)
    properties_base = properties_path.parent

    max_attempts = args.max_attempts
    if max_attempts is None:
        max_attempts = int(properties.get("app.max.attempts", 5))

    calc_codebleu = load_codebleu_calculator(args.skip_codebleu)

    if args.results_root:
        results_root = resolve_path(args.results_root, Path.cwd())
        if not results_root.is_dir():
            raise SystemExit(f"--results-root not a directory: {results_root}")

        tagged_rows: list[tuple[str, str, AttemptRow]] = []
        discovered = discover_project_dirs(results_root)
        if not discovered:
            print(f"[warn] no evaluation/ directories found under {results_root}")
        for project_dir, visibility in discovered:
            evaluation_dir = project_dir / "evaluation"
            rows = process_evaluation_dir(
                evaluation_dir,
                calc_codebleu,
                args.codebleu_language,
                max_attempts,
                evaluation_dir / "results.csv",
                evaluation_dir / "cases_results.csv",
            )
            tagged_rows.extend((visibility, project_dir.name, row) for row in rows)

        combined_dir = results_root / "combined"
        combined_results = (
            resolve_path(args.combined_results_csv, Path.cwd())
            if args.combined_results_csv
            else combined_dir / "results.csv"
        )
        combined_cases = (
            resolve_path(args.combined_cases_csv, Path.cwd())
            if args.combined_cases_csv
            else combined_dir / "cases_results.csv"
        )
        write_combined_attempt_csv(combined_results, tagged_rows)
        write_combined_case_csv(combined_cases, tagged_rows, max_attempts)
        print(f"[combined] wrote {len(tagged_rows)} SWE attempt rows to {combined_results}")
        print(f"[combined] wrote SWE case summary to {combined_cases}")

        by_visibility: dict[str, list[tuple[str, str, AttemptRow]]] = {}
        for triple in tagged_rows:
            by_visibility.setdefault(triple[0], []).append(triple)
        for visibility, triples in sorted(by_visibility.items()):
            if not visibility:
                continue
            vis_dir = results_root / visibility
            write_combined_attempt_csv(vis_dir / "results.csv", triples)
            write_combined_case_csv(vis_dir / "cases_results.csv", triples, max_attempts)
            print(f"[{visibility}] wrote {len(triples)} SWE attempt rows to {vis_dir / 'results.csv'}")
        return

    if args.evaluation_dir:
        evaluation_dir = resolve_path(args.evaluation_dir, Path.cwd())
    else:
        agent_dir = resolve_path(properties.get("project.agent.dir", "agent_dir"), properties_base)
        evaluation_dir = agent_dir / "evaluation"

    results_csv = resolve_path(args.results_csv, Path.cwd()) if args.results_csv else evaluation_dir / "results.csv"
    cases_csv = resolve_path(args.cases_csv, Path.cwd()) if args.cases_csv else evaluation_dir / "cases_results.csv"

    process_evaluation_dir(
        evaluation_dir,
        calc_codebleu,
        args.codebleu_language,
        max_attempts,
        results_csv,
        cases_csv,
    )

if __name__ == "__main__":

    if os.environ.get("PYTHONHASHSEED") != "0":
        os.execve(sys.executable, [sys.executable, *sys.argv], {**os.environ, "PYTHONHASHSEED": "0"})
    main()
