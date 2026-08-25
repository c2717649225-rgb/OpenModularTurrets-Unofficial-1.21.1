#!/usr/bin/env python
"""Launch a toolkit script with an available Python 3.10+ interpreter.

This bootstrap intentionally uses Python 3.6-compatible syntax so machines
whose ``python`` command is old can still select a modern installed runtime.
"""
import os
import shutil
import subprocess
import sys


MIN_VERSION = (3, 10)


def _works(command):
    probe = list(command) + [
        "-c",
        "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)",
    ]
    try:
        return subprocess.call(
            probe,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        ) == 0
    except (OSError, subprocess.SubprocessError):
        return False


def find_python():
    if sys.version_info >= MIN_VERSION:
        return [sys.executable]

    candidates = []
    if os.name == "nt":
        launcher = shutil.which("py")
        if launcher:
            candidates.extend(
                [launcher, selector]
                for selector in ("-3.13", "-3.12", "-3.11", "-3.10")
            )
    for name in ("python3.13", "python3.12", "python3.11", "python3.10"):
        executable = shutil.which(name)
        if executable:
            candidates.append([executable])

    for candidate in candidates:
        if _works(candidate):
            return candidate
    return None


def main():
    if len(sys.argv) < 2:
        sys.stderr.write(
            "Usage: python .agents/run.py <script-or--m> [arguments...]\n"
        )
        return 2

    command = find_python()
    if command is None:
        sys.stderr.write(
            "Error: the toolkit requires Python 3.10+; no compatible "
            "interpreter was found.\n"
        )
        return 1
    if command[0] != sys.executable:
        # The default `python` is too old; a fallback interpreter was
        # selected.  One-time hint so the environment can be fixed at the
        # source instead of relying on the bootstrap forever.
        sys.stderr.write(
            "[hint] The default `python` is older than 3.10, so a fallback "
            "interpreter was used for this command.\n"
            "       Fix the environment once: set PY_PYTHON=3.10 (py "
            "launcher) and reorder PATH so `python` resolves to 3.10+.\n"
            "       Run  python .agents/run.py .agents/gates/"
            "environment_check.py  for a diagnosis and exact steps.\n"
        )
    return subprocess.call(command + sys.argv[1:])


if __name__ == "__main__":
    raise SystemExit(main())
