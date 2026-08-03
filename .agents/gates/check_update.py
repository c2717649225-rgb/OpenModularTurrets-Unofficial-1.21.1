"""Compare two .agents/ toolkit copies before overwriting a local one.

Usage:
    python check_update.py <OLD_DIR> <NEW_DIR>

* OLD_DIR = the locally customized copy you have installed
* NEW_DIR = the upstream copy you are about to overwrite with

Output sections:
  * "upstream additions"  — files that exist only in NEW (safe to adopt);
  * "local additions"     — files that exist only in OLD (would be lost on
                            overwrite unless kept separately);
  * "conflicts"           — shared files whose content differs.  Without a
                            baseline manifest we cannot tell which side
                            changed, so treat every differing shared file as
                            a conflict and inspect it before overwriting.

Comparison is content-based with newline normalization (CRLF and LF are
treated as identical), so a Windows checkout does not report every line of
a Linux-authored file as changed.  Binary files are compared as bytes.

Exit codes: 0 = no conflicts, 1 = conflicts found, 2 = usage error.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

IGNORED_DIRS = {"__pycache__", ".git"}
IGNORED_SUFFIXES = {".pyc", ".pyo"}


def iter_files(root: Path):
    # os.walk(followlinks=False) never descends into symlinked directories,
    # and leaf symlinks are skipped explicitly: nothing outside the compared
    # trees can be read, even from hostile link farms.
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames[:] = sorted(d for d in dirnames if d not in IGNORED_DIRS)
        base = Path(dirpath)
        for name in sorted(filenames):
            path = base / name
            if path.is_symlink():
                continue
            if path.suffix in IGNORED_SUFFIXES:
                continue
            yield path


def read_normalized(path: Path):
    """Return text (newline-normalized) or bytes for binary files.

    Only CRLF and CR are normalized to LF, so a real content difference
    such as a missing trailing newline is still reported.  Binary files
    (NUL byte in the first 8 KiB) are compared as raw bytes.
    """
    try:
        raw = path.read_bytes()
    except OSError:
        return None
    if b"\x00" in raw[:8192]:
        return raw
    return raw.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def compare(old_root: Path, new_root: Path) -> dict:
    old_files = {p.relative_to(old_root): p for p in iter_files(old_root)}
    new_files = {p.relative_to(new_root): p for p in iter_files(new_root)}

    upstream_additions = sorted(set(new_files) - set(old_files))
    local_additions = sorted(set(old_files) - set(new_files))
    shared = sorted(set(old_files) & set(new_files))

    conflicts = []
    for rel in shared:
        old_content = read_normalized(old_files[rel])
        new_content = read_normalized(new_files[rel])
        if old_content is None or new_content is None:
            continue  # unreadable on one side; do not guess
        if old_content != new_content:
            conflicts.append(rel)
    return {
        "upstream_additions": upstream_additions,
        "local_additions": local_additions,
        "conflicts": conflicts,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("old_dir", type=Path, help="locally installed copy")
    parser.add_argument("new_dir", type=Path, help="upstream copy to adopt")
    args = parser.parse_args(argv)

    if not args.old_dir.is_dir() or not args.new_dir.is_dir():
        parser.error("both OLD_DIR and NEW_DIR must be existing directories")
        return 2

    result = compare(args.old_dir.resolve(), args.new_dir.resolve())

    print(f"Upstream additions ({len(result['upstream_additions'])}):")
    for rel in result["upstream_additions"]:
        print(f"  + {rel}")
    print(f"Local additions ({len(result['local_additions'])}):")
    for rel in result["local_additions"]:
        print(f"  - {rel}")
    print(f"Conflicts ({len(result['conflicts'])}):")
    for rel in result["conflicts"]:
        print(f"  ! {rel}")

    if result["conflicts"]:
        print(
            "RESULT: CONFLICTS FOUND — inspect each listed file before "
            "overwriting; locally added files are not part of the upstream "
            "toolkit and must be kept separately."
        )
        return 1
    print(
        "RESULT: CLEAN — no shared file differs (newline differences are "
        "normalized); overwrite is safe."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
