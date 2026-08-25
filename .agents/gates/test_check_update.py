#!/usr/bin/env python3
"""Standard-library tests for the toolkit upgrade comparator."""
from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path


GATES_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(GATES_DIR))

import check_update


class TestCheckUpdate(unittest.TestCase):
    def setUp(self):
        self.old_dir = Path(tempfile.mkdtemp(prefix="check_update_old_"))
        self.new_dir = Path(tempfile.mkdtemp(prefix="check_update_new_"))

    def tearDown(self):
        shutil.rmtree(self.old_dir, ignore_errors=True)
        shutil.rmtree(self.new_dir, ignore_errors=True)

    def _write(self, root: Path, rel: str, content: str) -> Path:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        # write_bytes: avoid platform newline translation corrupting the
        # exact CRLF/LF fixtures under test
        path.write_bytes(content.encode("utf-8"))
        return path

    def test_identical_trees_are_clean(self):
        self._write(self.old_dir, "gates/a.py", "x = 1\n")
        self._write(self.new_dir, "gates/a.py", "x = 1\n")
        result = check_update.compare(self.old_dir, self.new_dir)
        self.assertEqual([], result["conflicts"])
        self.assertEqual([], result["upstream_additions"])
        self.assertEqual([], result["local_additions"])

    def test_crlf_and_lf_count_as_identical(self):
        self._write(self.old_dir, "gates/a.py", "x = 1\r\n")
        self._write(self.new_dir, "gates/a.py", "x = 1\n")
        result = check_update.compare(self.old_dir, self.new_dir)
        self.assertEqual([], result["conflicts"])

    def test_missing_trailing_newline_is_a_conflict(self):
        self._write(self.old_dir, "gates/a.py", "x = 1\n")
        self._write(self.new_dir, "gates/a.py", "x = 1")
        result = check_update.compare(self.old_dir, self.new_dir)
        self.assertEqual([Path("gates/a.py")], result["conflicts"])

    def test_content_difference_is_a_conflict(self):
        self._write(self.old_dir, "gates/a.py", "x = 1\n")
        self._write(self.new_dir, "gates/a.py", "x = 2\n")
        result = check_update.compare(self.old_dir, self.new_dir)
        self.assertEqual([Path("gates/a.py")], result["conflicts"])

    def test_upstream_and_local_additions(self):
        self._write(self.old_dir, "local_only.txt", "mine\n")
        self._write(self.new_dir, "upstream_only.txt", "theirs\n")
        result = check_update.compare(self.old_dir, self.new_dir)
        self.assertEqual([Path("upstream_only.txt")],
                         result["upstream_additions"])
        self.assertEqual([Path("local_only.txt")], result["local_additions"])
        self.assertEqual([], result["conflicts"])

    def test_pycache_and_pyc_are_ignored(self):
        self._write(self.old_dir, "gates/__pycache__/a.pyc", "junk")
        self._write(self.old_dir, "gates/a.pyc", "junk")
        self._write(self.new_dir, "gates/__pycache__/a.pyc", "junk")
        self._write(self.new_dir, "gates/a.pyc", "junk")
        result = check_update.compare(self.old_dir, self.new_dir)
        self.assertEqual([], result["upstream_additions"])
        self.assertEqual([], result["local_additions"])
        self.assertEqual([], result["conflicts"])

    def test_symlinks_are_skipped(self):
        outside = self.old_dir.parent / "outside_target.txt"
        outside.write_text("secret\n", encoding="utf-8")
        try:
            (self.old_dir / "link.txt").symlink_to(outside)
        except (OSError, NotImplementedError):
            self.skipTest("symlink creation not permitted on this platform")
        result = check_update.compare(self.old_dir, self.new_dir)
        self.assertEqual([], result["conflicts"])
        self.assertEqual([], result["local_additions"])

    def test_symlinked_directories_are_not_followed(self):
        outside = self.old_dir.parent / "outside_tree"
        outside.mkdir(exist_ok=True)
        (outside / "secret.txt").write_text("secret\n", encoding="utf-8")
        try:
            (self.old_dir / "linkdir").symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("symlink creation not permitted on this platform")
        result = check_update.compare(self.old_dir, self.new_dir)
        self.assertEqual([], result["local_additions"])
        self.assertEqual([], result["conflicts"])

    def test_main_exit_codes(self):
        self._write(self.old_dir, "a.txt", "old\n")
        self._write(self.new_dir, "a.txt", "new\n")
        self.assertEqual(1, check_update.main(
            [str(self.old_dir), str(self.new_dir)]))
        self._write(self.new_dir, "a.txt", "old\n")
        self.assertEqual(0, check_update.main(
            [str(self.old_dir), str(self.new_dir)]))


if __name__ == "__main__":
    unittest.main()
