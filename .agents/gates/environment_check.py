"""Environment self-check for the toolkit's Python 3.10+ requirement.

Diagnoses why ``python`` may resolve to an old interpreter (e.g. 3.6) while
a modern one is installed, and prints concrete, safe fix steps.  Pure
standard library; run via ``python .agents/run.py .agents/gates/environment_check.py``.

Exit codes: 0 = environment OK, 1 = problems found.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

MIN_VERSION = (3, 10)


def default_python_info() -> tuple[str, str, bool]:
    """(executable, version, meets_requirement) for the default python."""
    version = f"{sys.version_info.major}.{sys.version_info.minor}."
    version += f"{sys.version_info.micro} ({sys.version_info.releaselevel})"
    ok = sys.version_info >= MIN_VERSION
    return sys.executable, version, ok


def python_entries_on_path() -> list[str]:
    """Every python.exe (or python) found while scanning PATH, in order."""
    entries = []
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        if not directory:
            continue
        for name in ("python.exe", "python"):
            candidate = Path(directory) / name
            if candidate.is_file():
                entries.append(str(candidate))
                break
    return entries


def py_launcher_versions() -> list[str]:
    """Installed versions reported by the py launcher (empty if absent)."""
    launcher = shutil.which("py")
    if not launcher:
        return []
    try:
        output = subprocess.run(
            [launcher, "-0p"],
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    lines = []
    for line in (output.stdout or "").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("Installed"):
            lines.append(stripped)
    return lines


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)

    executable, version, ok = default_python_info()
    problems = []

    print("=== Toolkit environment self-check (requires Python 3.10+) ===")
    print(f"Default python : {executable}")
    print(f"Version        : {version}")
    print(f"Requirement    : {MIN_VERSION[0]}.{MIN_VERSION[1]}+ "
          f"{'OK' if ok else 'NOT MET'}")

    entries = python_entries_on_path()
    if entries:
        print("\npython entries found on PATH (first wins):")
        for entry in entries:
            print(f"  {entry}")
    else:
        print("\nNo python executable found on PATH.")

    launcher = py_launcher_versions()
    if launcher:
        print("\npy launcher installed versions:")
        for line in launcher:
            print(f"  {line}")

    if ok:
        print("\nRESULT: OK — the default python already meets the 3.10+ "
              "requirement.")
        return 0

    problems.append("default python is older than 3.10")
    print("\nRESULT: PROBLEMS FOUND")
    print(f"  - {problems[0]}")
    print("\nSafe fixes (pick one):")
    print("  1. Make a 3.10+ interpreter the default:")
    print("     - Windows: run the toolkit's admin script")
    print("       .reasonix/fix-python-path.ps1 (reorders system PATH so")
    print("       Python310 comes before older installs), or adjust the")
    print("       system PATH manually; then restart your terminal.")
    print("     - Or set the user environment variable PY_PYTHON=3.10 so")
    print("       the 'py' launcher defaults to a modern interpreter.")
    print("  2. Keep using the bootstrap: every toolkit command already")
    print("     works via  python .agents/run.py <script> , which finds a")
    print("     3.10+ interpreter automatically.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
