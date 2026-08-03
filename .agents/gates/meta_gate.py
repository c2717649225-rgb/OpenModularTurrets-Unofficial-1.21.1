"""Meta gate: keep toolkit Python files from bloating unboundedly.

Scans every ``.py`` file under the toolkit tree and flags single-file line
counts above a warning threshold (default 1500) or a hard failure threshold
(default 2500). Standard library only — the toolkit runs on plain Python
with zero third-party dependencies.

Exit codes: 0 = pass (warnings allowed), 1 = hard threshold exceeded.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

WARN_THRESHOLD = 1500
FAIL_THRESHOLD = 2500
EXCLUDED_DIRS = {"__pycache__", "_archive"}


def iter_py_files(root: Path):
    # os.walk(followlinks=False) never descends into symlinked directories;
    # leaf symlinks are skipped explicitly (rglob would follow them).
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDED_DIRS]
        base = Path(dirpath)
        for name in sorted(filenames):
            if not name.endswith(".py"):
                continue
            path = base / name
            if path.is_symlink():
                continue
            yield path


def count_lines(path: Path) -> int:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return sum(1 for _ in fh)
    except OSError:
        return -1


def _escape_annotation(value: str) -> str:
    """Escape a value for a GitHub Actions workflow command annotation."""
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="toolkit root (default: parent of the gates/ directory)",
    )
    parser.add_argument("--warn", type=int, default=WARN_THRESHOLD,
                        help="line count above which a warning is emitted")
    parser.add_argument("--fail", type=int, default=FAIL_THRESHOLD,
                        help="line count above which the gate fails")
    parser.add_argument(
        "--warnings-as-errors",
        action="store_true",
        help="treat line-count warnings as failures (CI strict mode)",
    )
    args = parser.parse_args(argv)
    if args.fail <= args.warn:
        parser.error(f"--fail ({args.fail}) must be greater than --warn "
                     f"({args.warn})")
        return 2

    root = (args.root or Path(__file__).resolve().parents[1]).resolve()
    errors, warnings = [], []
    for path in iter_py_files(root):
        lines = count_lines(path)
        rel = path.relative_to(root)
        if lines > args.fail:
            errors.append((rel, lines))
        elif lines > args.warn:
            warnings.append((rel, lines))

    for rel, lines in warnings:
        rel_raw = rel.as_posix()
        rel_escaped = _escape_annotation(rel_raw)
        print(f"::warning file={rel_escaped}::meta_gate: {lines} lines "
              f"exceeds warning threshold {args.warn}")
        print(f"WARNING: {rel_raw}: {lines} lines (> {args.warn})")
    for rel, lines in errors:
        rel_raw = rel.as_posix()
        rel_escaped = _escape_annotation(rel_raw)
        print(f"::error file={rel_escaped}::meta_gate: {lines} lines "
              f"exceeds hard limit {args.fail}")
        print(f"ERROR: {rel_raw}: {lines} lines (> {args.fail})")

    if errors or (args.warnings_as_errors and warnings):
        detail = f"{len(errors)} file(s) over hard limit"
        if args.warnings_as_errors:
            detail += f", {len(warnings)} warning(s) escalated"
        print(f"RESULT: FAIL (meta gate) — {detail}")
        return 1
    print(f"RESULT: PASS (meta gate) — {len(errors)} error(s), "
          f"{len(warnings)} warning(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
