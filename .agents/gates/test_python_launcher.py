#!/usr/bin/env python3
"""Tests for the old-default-Python compatible toolkit launcher."""
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parents[2]
LAUNCHER_PATH = PROJECT_DIR / ".agents" / "run.py"
SPEC = importlib.util.spec_from_file_location("toolkit_python_launcher", LAUNCHER_PATH)
assert SPEC is not None and SPEC.loader is not None
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


class PythonLauncherTests(unittest.TestCase):
    def test_current_supported_interpreter_is_reused(self) -> None:
        self.assertGreaterEqual(sys.version_info, (3, 10))
        self.assertEqual([sys.executable], launcher.find_python())

    def test_minimum_version_matches_toolkit_syntax(self) -> None:
        self.assertEqual((3, 10), launcher.MIN_VERSION)


if __name__ == "__main__":
    unittest.main()
