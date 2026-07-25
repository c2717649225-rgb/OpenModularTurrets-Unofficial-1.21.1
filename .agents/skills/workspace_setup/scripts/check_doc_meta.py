#!/usr/bin/env python3
"""
Validate documentation freshness metadata for NeoForge skill references.

Rules (low maintenance):
  - Core set (docs_core_set.txt): each file must include status: verified and pin_neo
  - Core set size must be 5–10
  - Non-core files must NOT claim status: verified (use draft or omit)
  - Missing metadata on non-core == implicit draft (OK)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
NEOFORGE = SCRIPT_DIR.parent.parent / "neoforge"
REFS = NEOFORGE / "references"
CORE_LIST = NEOFORGE / "docs_core_set.txt"
MIN_CORE, MAX_CORE = 5, 10

STATUS_RE = re.compile(r"(?im)^\s*status\s*:\s*(\w+)\s*$")
PIN_RE = re.compile(r"(?im)^\s*pin_neo\s*:\s*(\S+)\s*$")


def load_core() -> list[str]:
    if not CORE_LIST.is_file():
        print(f"ERROR: missing {CORE_LIST}")
        return []
    names = []
    for line in CORE_LIST.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        names.append(line.replace("\\", "/"))
    return names


def head_meta(path: Path, max_lines: int = 40) -> str:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()[:max_lines]
    return "\n".join(lines)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    core = load_core()
    print("=== check_doc_meta ===")
    print(f"Core set file: {CORE_LIST}")
    print(f"Core count: {len(core)} (allowed {MIN_CORE}-{MAX_CORE})")

    failed = False
    if not (MIN_CORE <= len(core) <= MAX_CORE):
        print(f"ERROR: core set size {len(core)} outside [{MIN_CORE}, {MAX_CORE}]")
        failed = True

    for name in core:
        path = REFS / name
        if not path.is_file():
            print(f"ERROR: core file missing: {name}")
            failed = True
            continue
        head = head_meta(path)
        st = STATUS_RE.search(head)
        pin = PIN_RE.search(head)
        if not st or st.group(1).lower() != "verified":
            print(f"ERROR: {name} must have 'status: verified' near top")
            failed = True
        if not pin:
            print(f"ERROR: {name} must have 'pin_neo: <version>' near top")
            failed = True
        else:
            print(f"OK core: {name} pin_neo={pin.group(1)}")

    # Non-core must not claim verified
    core_set = set(core)
    if REFS.is_dir():
        for path in sorted(REFS.glob("*.md")):
            if path.name in core_set:
                continue
            head = head_meta(path)
            st = STATUS_RE.search(head)
            if st and st.group(1).lower() == "verified":
                print(
                    f"ERROR: {path.name} is verified but not in docs_core_set.txt "
                    "(cap 5-10; demote to draft or add via explicit plan change)"
                )
                failed = True

    if failed:
        print("\nRESULT: FAIL")
        return 1
    print("\nRESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
