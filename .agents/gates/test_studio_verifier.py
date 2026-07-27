#!/usr/bin/env python3
"""Standard-library tests for independent clean-worktree verification."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


STUDIO_DIR = Path(__file__).resolve().parents[1] / "studio"
sys.path.insert(0, str(STUDIO_DIR))

import verifier


def run_git(repository: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
        timeout=10,
    )
    if result.returncode != 0:
        raise AssertionError(result.stderr)
    return result.stdout.strip()


class TestStudioVerifier(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="studio_verifier_"))
        self.repository = self.temp_dir / "repository"
        self.repository.mkdir()
        run_git(self.repository, "init", "--quiet")
        run_git(self.repository, "config", "user.name", "Verifier Fixture")
        run_git(
            self.repository,
            "config",
            "user.email",
            "verifier@example.invalid",
        )
        run_git(self.repository, "config", "core.autocrlf", "false")
        (self.repository / ".agents").mkdir()
        (self.repository / ".agents" / "policy.json").write_text(
            json.dumps({"version": 1}) + "\n",
            encoding="utf-8",
        )
        (self.repository / "src").mkdir()
        (self.repository / "src" / "main.txt").write_text(
            "committed source\n",
            encoding="utf-8",
        )
        run_git(self.repository, "add", ".")
        run_git(self.repository, "commit", "--quiet", "-m", "fixture")

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_snapshot_is_stable_and_control_scoped(self):
        snapshot = verifier.snapshot_repository(self.repository)
        self.assertEqual(64, len(snapshot.tree_digest))
        self.assertEqual(64, len(snapshot.control_digest))

        (self.repository / "src" / "main.txt").write_text(
            "new source\n",
            encoding="utf-8",
        )
        self.assertEqual(
            snapshot,
            verifier.snapshot_repository(self.repository),
            "snapshot must describe the commit rather than dirty files",
        )

    def test_dirty_tracked_or_untracked_source_fails_closed(self):
        (self.repository / "src" / "main.txt").write_text(
            "dirty\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "source repository is dirty",
        ):
            verifier.require_clean_repository(self.repository)

        run_git(self.repository, "restore", "src/main.txt")
        (self.repository / "untracked.txt").write_text(
            "untracked\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "source repository is dirty",
        ):
            verifier.require_clean_repository(self.repository)

    def test_expected_commit_and_control_digest_fail_closed(self):
        snapshot = verifier.snapshot_repository(self.repository)
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "commit mismatch",
        ):
            with verifier.prepare_clean_worktree(
                self.repository,
                expected_commit="0" * len(snapshot.commit),
            ):
                self.fail("mismatched commit was accepted")
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "control digest mismatch",
        ):
            with verifier.prepare_clean_worktree(
                self.repository,
                expected_control_digest="0" * 64,
            ):
                self.fail("mismatched control digest was accepted")

    def test_detached_checkout_is_faithful_and_removed(self):
        source = verifier.snapshot_repository(self.repository)
        checkout_path = None
        with verifier.prepare_clean_worktree(
            self.repository,
            expected_commit=source.commit,
            expected_control_digest=source.control_digest,
            temp_parent=self.temp_dir / "verifier-temp",
        ) as prepared:
            checkout_path = prepared.checkout
            self.assertTrue(checkout_path.is_dir())
            self.assertEqual(source, prepared.checkout_snapshot)
            self.assertEqual("verified", prepared.attestation()["state"])
            self.assertFalse(prepared.attestation()["execution_sandbox"])
            self.assertEqual(
                source.commit,
                run_git(checkout_path, "rev-parse", "HEAD"),
            )
        self.assertIsNotNone(checkout_path)
        self.assertFalse(checkout_path.exists())

    def test_nested_directory_is_not_accepted_as_repository_root(self):
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "repository root",
        ):
            verifier.snapshot_repository(self.repository / "src")


if __name__ == "__main__":
    unittest.main()
