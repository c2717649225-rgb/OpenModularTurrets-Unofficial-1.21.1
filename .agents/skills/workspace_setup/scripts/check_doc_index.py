#!/usr/bin/env python3
"""
Verify that every NeoForge skill reference / example / playbook markdown file
is linked from skills/neoforge/SKILL.md, and that every such link target exists.

Also enforces playbook count cap (<= 5) when playbooks/ exists.

Exit 0 on success, 1 on failure.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

# scripts/ -> workspace_setup/ -> skills/ -> .agents/ -> project root
SCRIPT_DIR = Path(__file__).resolve().parent
NEOFORGE_DIR = SCRIPT_DIR.parent.parent / "neoforge"
SKILL_MD = NEOFORGE_DIR / "SKILL.md"
MAX_PLAYBOOKS = 5

LINK_RE = re.compile(
    r"\[[^\]]*\]\((references/[^)]+\.md|examples/[^)]+\.md|playbooks/[^)]+\.md)\)"
)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    if not SKILL_MD.is_file():
        print(f"ERROR: SKILL.md not found: {SKILL_MD}")
        return 1

    skill_text = SKILL_MD.read_text(encoding="utf-8", errors="replace")

    # Collect on-disk docs
    groups = {
        "references": NEOFORGE_DIR / "references",
        "examples": NEOFORGE_DIR / "examples",
        "playbooks": NEOFORGE_DIR / "playbooks",
    }
    on_disk: list[str] = []
    for name, folder in groups.items():
        if not folder.is_dir():
            continue
        for p in sorted(folder.glob("*.md")):
            on_disk.append(f"{name}/{p.name}")

    linked = set(LINK_RE.findall(skill_text))
    # Also accept bare filename mentions for resilience (basename match)
    skill_lower = skill_text

    orphans: list[str] = []
    for rel in on_disk:
        basename = rel.split("/", 1)[1]
        if rel in linked or basename in skill_lower:
            continue
        orphans.append(rel)

    broken: list[str] = []
    for rel in sorted(linked):
        target = NEOFORGE_DIR / rel.replace("/", os.sep)
        if not target.is_file():
            broken.append(rel)

    playbook_dir = groups["playbooks"]
    playbook_count = 0
    if playbook_dir.is_dir():
        playbook_count = len(list(playbook_dir.glob("*.md")))

    print("=== check_doc_index ===")
    print(f"SKILL.md: {SKILL_MD}")
    print(f"On-disk docs: {len(on_disk)}")
    print(f"Linked paths in SKILL.md: {len(linked)}")
    print(f"Playbooks count: {playbook_count} (max {MAX_PLAYBOOKS})")

    failed = False
    if orphans:
        failed = True
        print("\nORPHAN docs (on disk but not referenced in SKILL.md):")
        for o in orphans:
            print(f"  - {o}")
    else:
        print("\nNo orphan docs.")

    if broken:
        failed = True
        print("\nBROKEN links in SKILL.md (target missing):")
        for b in broken:
            print(f"  - {b}")
    else:
        print("No broken reference/example/playbook links.")

    if playbook_count > MAX_PLAYBOOKS:
        failed = True
        print(
            f"\nPLAYBOOK CAP exceeded: {playbook_count} > {MAX_PLAYBOOKS}. "
            "Do not expand playbooks; use references/ instead."
        )

    if failed:
        print("\nRESULT: FAIL")
        return 1

    print("\nRESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
