#!/usr/bin/env python3
"""Tests for the provisional Execution Policy and bubblewrap adapter."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


AGENTS_DIR = Path(__file__).resolve().parent.parent
STUDIO_DIR = AGENTS_DIR / "studio"
sys.path.insert(0, str(STUDIO_DIR))

import execution_policy


def valid_policy() -> dict:
    return {
        "$schema": "../.agents/studio/schemas/execution-policy.schema.json",
        "schema_version": 1,
        "stability": "provisional",
        "policy_id": "core.major.verify",
        "backend": "bubblewrap",
        "writable_paths": [
            ".gradle",
            "build",
            "run",
            "src/generated",
        ],
        "timeout_seconds": 1800,
        "required_capabilities": sorted(
            execution_policy.CORE_CAPABILITIES
        ),
    }


class ExecutionPolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.handle = tempfile.TemporaryDirectory(prefix="afs_policy_")
        self.root = Path(self.handle.name)
        self.project = self.root / "host"
        self.evidence = self.root / "external-evidence"
        self.project.mkdir()
        self.evidence.mkdir()
        self.policy_path = self.root / "policy.json"

    def tearDown(self) -> None:
        self.handle.cleanup()

    def write_policy(self, document: dict | None = None) -> Path:
        self.policy_path.write_text(
            json.dumps(document or valid_policy(), indent=2) + "\n",
            encoding="utf-8",
        )
        return self.policy_path

    def test_valid_policy_loads_with_stable_digest(self) -> None:
        first = execution_policy.load_policy(self.write_policy())
        second = execution_policy.load_policy(self.policy_path)

        self.assertEqual(first.digest, second.digest)
        self.assertEqual("bubblewrap", first.backend)
        self.assertEqual(
            execution_policy.CORE_CAPABILITIES,
            first.required_capabilities,
        )

    def test_bundled_major_strict_policy_is_valid(self) -> None:
        policy_path = (
            AGENTS_DIR
            / "studio"
            / "policies"
            / "major-strict.json"
        )
        policy = execution_policy.load_policy(policy_path)
        self.assertEqual("major.strict", policy.policy_id)
        self.assertEqual(
            {
                ".gradle",
                "build",
                "run",
                "src/generated/resources",
            },
            set(policy.writable_paths),
        )
        self.assertEqual(
            execution_policy.CORE_CAPABILITIES,
            policy.required_capabilities,
        )

    def test_duplicate_key_unknown_field_and_missing_capability_fail(self) -> None:
        self.policy_path.write_text(
            '{"schema_version":1,"schema_version":1}',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            execution_policy.PolicyError, "duplicate JSON key"
        ):
            execution_policy.load_policy(self.policy_path)

        unknown = valid_policy()
        unknown["future_network_policy"] = {}
        with self.assertRaisesRegex(
            execution_policy.PolicyError, "unknown provisional"
        ):
            execution_policy.load_policy(self.write_policy(unknown))

        incomplete = valid_policy()
        incomplete["required_capabilities"].remove("evidence_unmounted")
        with self.assertRaisesRegex(
            execution_policy.PolicyError, "missing capability"
        ):
            execution_policy.load_policy(self.write_policy(incomplete))

    def test_absolute_traversal_nested_and_symlink_writes_fail(self) -> None:
        for unsafe in (
            "../outside",
            "/absolute",
            "C:/windows",
            ".",
            "./build",
            "build//reports",
        ):
            document = valid_policy()
            document["writable_paths"] = [unsafe]
            with self.subTest(path=unsafe), self.assertRaises(
                execution_policy.PolicyError
            ):
                execution_policy.load_policy(self.write_policy(document))

        nested = valid_policy()
        nested["writable_paths"] = ["build", "build/reports"]
        with self.assertRaisesRegex(
            execution_policy.PolicyError, "nested writable"
        ):
            execution_policy.load_policy(self.write_policy(nested))

        if hasattr(Path, "symlink_to"):
            outside = self.root / "outside"
            outside.mkdir()
            link = self.project / "build"
            try:
                link.symlink_to(outside, target_is_directory=True)
            except OSError:
                self.skipTest("directory symlinks are unavailable")
            policy = execution_policy.load_policy(self.write_policy())
            probe = execution_policy.BackendProbe(
                "bubblewrap",
                True,
                "test",
                execution_policy.CORE_CAPABILITIES,
                "",
            )
            with self.assertRaisesRegex(
                execution_policy.PolicyError, "escapes"
            ):
                execution_policy.prepare_bubblewrap(
                    policy,
                    project_dir=self.project,
                    evidence_root=self.evidence,
                    command=["python", "-V"],
                    probe=probe,
                    executable="/usr/bin/bwrap",
                )

    def test_non_linux_probe_is_fail_closed(self) -> None:
        with mock.patch.object(
            execution_policy.sys, "platform", "win32"
        ):
            probe = execution_policy.probe_bubblewrap()
        self.assertFalse(probe.available)
        self.assertFalse(probe.capabilities)

    def test_prepare_wraps_read_only_root_write_allowlist_and_hidden_evidence(
        self,
    ) -> None:
        policy = execution_policy.load_policy(self.write_policy())
        probe = execution_policy.BackendProbe(
            "bubblewrap",
            True,
            "bubblewrap 1.0-test",
            execution_policy.CORE_CAPABILITIES,
            "",
        )
        prepared = execution_policy.prepare_bubblewrap(
            policy,
            project_dir=self.project,
            evidence_root=self.evidence,
            command=["python", "-V"],
            probe=probe,
            executable="/usr/bin/bwrap",
        )

        argv = list(prepared.argv)
        self.assertIn("--ro-bind", argv)
        self.assertIn("--unshare-net", argv)
        self.assertIn("--unshare-pid", argv)
        self.assertIn("--tmpfs", argv)
        self.assertIn(str(self.evidence.resolve()), argv)
        for relative in policy.writable_paths:
            self.assertIn(str((self.project / relative).resolve()), argv)
        self.assertEqual("prepared", prepared.attestation["state"])
        self.assertEqual("empty_tmpfs_overlay", prepared.attestation["evidence_mount"])

        final = execution_policy.finalized_attestation(
            prepared,
            launched=True,
            returncode=0,
            timed_out=False,
            process_tree_cleaned=True,
        )
        self.assertEqual("enforced", final["state"])

    def test_workspace_and_evidence_must_be_disjoint(self) -> None:
        policy = execution_policy.load_policy(self.write_policy())
        inside = self.project / "evidence"
        inside.mkdir()
        probe = execution_policy.BackendProbe(
            "bubblewrap",
            True,
            "test",
            execution_policy.CORE_CAPABILITIES,
            "",
        )
        with self.assertRaisesRegex(
            execution_policy.PolicyError, "disjoint"
        ):
            execution_policy.prepare_bubblewrap(
                policy,
                project_dir=self.project,
                evidence_root=inside,
                command=["python", "-V"],
                probe=probe,
                executable="/usr/bin/bwrap",
            )

    @unittest.skipUnless(
        sys.platform.startswith("linux") and shutil.which("bwrap"),
        "real bubblewrap backend is unavailable",
    )
    def test_real_backend_denies_source_and_underlying_evidence_writes(self) -> None:
        policy = execution_policy.load_policy(self.write_policy())
        source = self.project / "source.txt"
        source.write_text("immutable", encoding="utf-8")
        secret = self.evidence / "journal.jsonl"
        secret.write_text("authority", encoding="utf-8")
        script = (
            "from pathlib import Path\n"
            f"source=Path({str(source)!r})\n"
            f"evidence=Path({str(secret)!r})\n"
            "source_failed=False\n"
            "try: source.write_text('tampered')\n"
            "except OSError: source_failed=True\n"
            "assert source_failed\n"
            "assert not evidence.exists()\n"
            f"Path({str(self.project / 'build' / 'ok.txt')!r}).write_text('ok')\n"
        )
        prepared = execution_policy.prepare_bubblewrap(
            policy,
            project_dir=self.project,
            evidence_root=self.evidence,
            command=[sys.executable, "-c", script],
        )
        result = subprocess.run(
            list(prepared.argv),
            cwd=self.project,
            check=False,
            timeout=30,
        )
        self.assertEqual(0, result.returncode)
        self.assertEqual("immutable", source.read_text(encoding="utf-8"))
        self.assertEqual("authority", secret.read_text(encoding="utf-8"))
        self.assertEqual(
            "ok",
            (self.project / "build" / "ok.txt").read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
