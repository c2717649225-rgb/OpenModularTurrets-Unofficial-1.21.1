#!/usr/bin/env python3
"""Standard-library tests for the environment self-check."""
from __future__ import annotations

import os
import shutil
import sys
import unittest
from pathlib import Path
from unittest import mock

import environment_check


class TestEnvironmentCheck(unittest.TestCase):
    def test_default_python_info_shape(self):
        executable, version, ok = environment_check.default_python_info()
        self.assertTrue(executable)
        self.assertRegex(version, r"^\d+\.\d+\.\d+")
        self.assertIsInstance(ok, bool)
        self.assertEqual(ok, sys.version_info >= (3, 10))

    def test_python_entries_on_path_order(self):
        path_value = os.pathsep.join(["/a", "/b"])
        with mock.patch.dict("os.environ", {"PATH": path_value}):
            with mock.patch(
                "environment_check.Path.is_file",
                side_effect=[True, False, True],
            ):
                entries = environment_check.python_entries_on_path()
        self.assertEqual(
            [str(Path("/a") / "python.exe"), str(Path("/b") / "python")],
            entries,
        )

    def test_python_entries_skips_missing(self):
        path_value = os.pathsep.join(["/a", "/b"])
        with mock.patch.dict("os.environ", {"PATH": path_value}):
            with mock.patch(
                "environment_check.Path.is_file",
                return_value=False,
            ):
                self.assertEqual([], environment_check.python_entries_on_path())

    def test_py_launcher_absent(self):
        with mock.patch.object(shutil, "which", return_value=None):
            self.assertEqual([], environment_check.py_launcher_versions())

    def test_py_launcher_parses_output(self):
        with mock.patch.object(shutil, "which", return_value="py"):
            with mock.patch.object(
                environment_check.subprocess,
                "run",
                return_value=mock.Mock(
                    stdout="Installed Pythons found by py.exe *\n\n"
                           " -3.10-64 C:\\py310\\python.exe\n"
                           " -3.6-64 C:\\py36\\python.exe\n"
                ),
            ):
                versions = environment_check.py_launcher_versions()
        self.assertEqual(
            ["-3.10-64 C:\\py310\\python.exe", "-3.6-64 C:\\py36\\python.exe"],
            versions,
        )

    def test_main_ok_when_modern(self):
        with mock.patch(
            "environment_check.default_python_info",
            return_value=("/py310/python.exe", "3.10.6", True),
        ):
            self.assertEqual(0, environment_check.main([]))

    def test_main_problems_when_old(self):
        with mock.patch(
            "environment_check.default_python_info",
            return_value=("/py36/python.exe", "3.6.4", False),
        ):
            with mock.patch(
                "environment_check.python_entries_on_path", return_value=[]
            ):
                with mock.patch(
                    "environment_check.py_launcher_versions", return_value=[]
                ):
                    self.assertEqual(1, environment_check.main([]))


if __name__ == "__main__":
    unittest.main()
