#!/usr/bin/env python3
"""
Crash-log triage: match a Minecraft/NeoForge crash report (or latest.log)
against crash_rules.json and print the diagnosis, red line, and the ONE doc
to read — turning crash analysis from open-ended reasoning into a table
lookup. Runtime-domain sibling of repair_rules.json (compile errors).

    python .agents/run.py .agents/gates/crash_triage.py                 # newest run/crash-reports/*.txt,
                                                         # falls back to run/logs/latest.log
    python .agents/run.py .agents/gates/crash_triage.py path/to/crash-2026-07-27_xx.txt

Zero dependencies. Also usable when the MCP probe (read_latest_crash_report)
is unavailable. Multiple rules can hit (a crash often has several signatures);
all hits are printed, first hit is usually the root cause. No hit -> prints
the systematic-debugging four-phase guidance instead of guessing.

Exit codes: 0 = rules hit, 2 = no rule hit (guidance printed), 1 = no input found.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent
RULES_FILE = SCRIPT_DIR / "crash_rules.json"

# Where a NeoForge dev workspace drops crash evidence, in preference order.
CANDIDATE_DIRS = [
    PROJECT_ROOT / "run" / "crash-reports",
    PROJECT_ROOT / "runs" / "client" / "crash-reports",
    PROJECT_ROOT / "runs" / "server" / "crash-reports",
]
CANDIDATE_LOGS = [
    PROJECT_ROOT / "run" / "logs" / "latest.log",
    PROJECT_ROOT / "runs" / "client" / "logs" / "latest.log",
    PROJECT_ROOT / "runs" / "server" / "logs" / "latest.log",
]


def find_input(argv: list) -> Path | None:
    if argv:
        p = Path(argv[0])
        if not p.is_absolute():
            p = PROJECT_ROOT / p
        return p if p.is_file() else None
    reports = []
    for d in CANDIDATE_DIRS:
        if d.is_dir():
            reports.extend(d.glob("crash-*.txt"))
    if reports:
        return max(reports, key=lambda p: p.stat().st_mtime)
    for log in CANDIDATE_LOGS:
        if log.is_file():
            return log
    return None


def main(argv: list) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    src = find_input(argv)
    if src is None:
        print("ERROR: no crash report found (run/crash-reports, runs/*/crash-reports, latest.log)")
        print("Usage: python .agents/run.py .agents/gates/crash_triage.py [path/to/crash-report.txt]")
        return 1

    text = src.read_text(encoding="utf-8", errors="replace")
    rules_data = json.loads(RULES_FILE.read_text(encoding="utf-8"))

    print("==================================================")
    print("Crash Triage (rule table: gates/crash_rules.json)")
    print("==================================================")
    print(f"Input: {src}  ({len(text.splitlines())} lines)")

    hits = []
    for rule in rules_data.get("rules", []):
        if all(re.search(p, text) for p in rule.get("patterns", [])):
            hits.append(rule)

    if not hits:
        print("\nNo known signature matched.")
        print(rules_data.get("no_match_guidance", ""))
        return 2

    print(f"\nMatched rule(s): {len(hits)}  (first hit is usually the root cause)")
    for i, r in enumerate(hits, 1):
        print("--------------------------------------------------")
        print(f"[{i}] {r['id']}" + (f"   红线: {r['redline']}" if r.get("redline") else ""))
        print(f"    诊断: {r['diagnosis']}")
        if r.get("read"):
            print(f"    阅读: .agents/{r['read']}")
        print(f"    修复: {r['fix_hint']}")
    print("--------------------------------------------------")
    print("修复后按完成证据协议重跑门禁；若本次崩溃是门禁未拦的新签名，"
          "回流一条规则到 crash_rules.json 并登记 anti_patterns 尾表（AGENTS.md P1-3）。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
