#!/usr/bin/env python3
"""
Validate documentation trust metadata for NeoForge skill references.

Two deliberately separate concepts are enforced:
  - Core candidate set (docs_core_set.txt): the small 5–10 document shortlist
    available for task routing. It does not auto-load documents and is not a
    trust ceiling.
  - Verified set (docs_verified_set.txt): every document whose claims were
    checked against the pinned Minecraft/NeoForge version. This set may grow.

Rules:
  - Every core document must also be present in the verified set.
  - Every verified document must declare status/pins/verification date.
  - Verified pins must equal VERSION's Minecraft and docs_pin_neo values.
  - A document may not claim ``status: verified`` unless it is in the verified
    manifest.
  - Missing metadata on other documents means implicit draft and is allowed.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
AGENTS_DIR = SCRIPT_DIR.parent
NEOFORGE = AGENTS_DIR / "skills" / "neoforge"
REFS = NEOFORGE / "references"
CORE_LIST = NEOFORGE / "docs_core_set.txt"
VERIFIED_LIST = NEOFORGE / "docs_verified_set.txt"
VERSION_FILE = AGENTS_DIR / "VERSION"
MIN_CORE, MAX_CORE = 5, 10

STATUS_RE = re.compile(r"(?im)^\s*status\s*:\s*(\w+)\s*$")
PIN_RE = re.compile(r"(?im)^\s*pin_neo\s*:\s*(\S+)\s*$")
PIN_MC_RE = re.compile(r"(?im)^\s*pin_minecraft\s*:\s*(\S+)\s*$")
VERIFIED_DATE_RE = re.compile(r"(?im)^\s*last_verified\s*:\s*(\S+)\s*$")


def load_manifest(path: Path) -> list[str]:
    if not path.is_file():
        raise FileNotFoundError(path)
    names = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        names.append(line.replace("\\", "/"))
    return names


def load_version_pins(path: Path) -> tuple[str, str]:
    """Return (Minecraft, docs_pin_neo) from the toolkit VERSION file."""
    if not path.is_file():
        raise FileNotFoundError(path)
    values: dict[str, str] = {}
    for raw_line in path.read_text(
        encoding="utf-8", errors="strict"
    ).splitlines():
        if ":" not in raw_line:
            continue
        key, value = raw_line.split(":", 1)
        values[key.strip()] = value.strip()
    missing = [
        key for key in ("Minecraft", "docs_pin_neo") if not values.get(key)
    ]
    if missing:
        raise ValueError(
            f"{path} missing required field(s): {', '.join(missing)}"
        )
    return values["Minecraft"], values["docs_pin_neo"]


def head_meta(path: Path, max_lines: int = 40) -> str:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()[:max_lines]
    return "\n".join(lines)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    print("=== check_doc_meta ===")
    try:
        core = load_manifest(CORE_LIST)
        verified = load_manifest(VERIFIED_LIST)
        expected_minecraft, expected_neo = load_version_pins(VERSION_FILE)
    except (FileNotFoundError, UnicodeError, ValueError) as exc:
        print(f"ERROR: documentation metadata configuration: {exc}")
        print("\nRESULT: FAIL")
        return 1

    print(f"Core set file: {CORE_LIST}")
    print(f"Core candidate count: {len(core)} (allowed {MIN_CORE}-{MAX_CORE})")
    print(f"Verified set file: {VERIFIED_LIST}")
    print(f"Verified count: {len(verified)} (no hard cap)")
    print(f"VERSION pins: minecraft={expected_minecraft} neo={expected_neo}")

    failed = False
    if not (MIN_CORE <= len(core) <= MAX_CORE):
        print(f"ERROR: core set size {len(core)} outside [{MIN_CORE}, {MAX_CORE}]")
        failed = True

    for label, names in (("core", core), ("verified", verified)):
        duplicates = sorted({name for name in names if names.count(name) > 1})
        for name in duplicates:
            print(f"ERROR: duplicate {label} manifest entry: {name}")
            failed = True

    verified_set = set(verified)
    for name in core:
        if name not in verified_set:
            print(
                f"ERROR: core document {name} is not in docs_verified_set.txt"
            )
            failed = True

    core_set = set(core)
    for name in verified:
        path = REFS / name
        if not path.is_file():
            print(f"ERROR: verified file missing: {name}")
            failed = True
            continue
        head = head_meta(path)
        st = STATUS_RE.search(head)
        pin = PIN_RE.search(head)
        pin_mc = PIN_MC_RE.search(head)
        verified_date = VERIFIED_DATE_RE.search(head)
        entry_ok = True
        if not st or st.group(1).lower() != "verified":
            print(f"ERROR: {name} must have 'status: verified' near top")
            failed = True
            entry_ok = False
        if not pin:
            print(f"ERROR: {name} must have 'pin_neo: <version>' near top")
            failed = True
            entry_ok = False
        if not pin_mc:
            print(f"ERROR: {name} must have 'pin_minecraft: <version>' near top")
            failed = True
            entry_ok = False
        if not verified_date:
            print(f"ERROR: {name} must have 'last_verified: <date>' near top")
            failed = True
            entry_ok = False
        if pin and pin.group(1) != expected_neo:
            print(
                f"ERROR: {name} pin_neo={pin.group(1)} does not match "
                f"VERSION docs_pin_neo={expected_neo}"
            )
            failed = True
            entry_ok = False
        if pin_mc and pin_mc.group(1) != expected_minecraft:
            print(
                f"ERROR: {name} pin_minecraft={pin_mc.group(1)} does not "
                f"match VERSION Minecraft={expected_minecraft}"
            )
            failed = True
            entry_ok = False
        if entry_ok:
            scope = "core+verified" if name in core_set else "verified"
            print(
                f"OK {scope}: {name} "
                f"minecraft={pin_mc.group(1)} neo={pin.group(1)} "
                f"checked={verified_date.group(1)}"
            )

    # Metadata claims and manifest membership must agree in both directions.
    if REFS.is_dir():
        for path in sorted(REFS.glob("*.md")):
            head = head_meta(path)
            st = STATUS_RE.search(head)
            if (
                st
                and st.group(1).lower() == "verified"
                and path.name not in verified_set
            ):
                print(
                    f"ERROR: {path.name} claims verified but is absent from "
                    "docs_verified_set.txt"
                )
                failed = True

    # Size visibility (informational only — never affects PASS/FAIL).
    # References are load-on-demand encyclopedia pages, so no hard cap;
    # this exists so silent bloat is at least visible to maintainers.
    if REFS.is_dir():
        sizes = sorted(
            ((len(p.read_text(encoding="utf-8", errors="replace").splitlines()), p.name)
             for p in REFS.glob("*.md")),
            reverse=True,
        )
        total = sum(n for n, _ in sizes)
        print(f"\n[info] reference sizes: {len(sizes)} files, {total} lines total; largest:")
        for n, name in sizes[:5]:
            print(f"[info]   {n:>5} lines  {name}")

    if failed:
        print("\nRESULT: FAIL")
        return 1
    print("\nRESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
