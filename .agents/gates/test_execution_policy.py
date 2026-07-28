#!/usr/bin/env python3
"""Tests for the provisional Execution Policy and bubblewrap adapter."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock


AGENTS_DIR = Path(__file__).resolve().parent.parent
STUDIO_DIR = AGENTS_DIR / "studio"
sys.path.insert(0, str(STUDIO_DIR))

import execution_policy


def verified_probe(executable: Path) -> execution_policy.BackendProbe:
    executable = executable.resolve()
    executable_digest = execution_policy.sha256_file(executable)
    document = {
        "backend": "bubblewrap",
        "version": "bubblewrap 1.0-test",
        "executable": str(executable),
        "executable_sha256": executable_digest,
        "verification": execution_policy.LIVE_PROBE_VERIFICATION,
        "capabilities": sorted(execution_policy.CORE_CAPABILITIES),
    }
    return execution_policy.BackendProbe(
        "bubblewrap",
        True,
        document["version"],
        execution_policy.CORE_CAPABILITIES,
        "",
        str(executable),
        executable_digest,
        document["verification"],
        execution_policy.sha256_json(document),
    )


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
            probe = verified_probe(Path(sys.executable))
            with mock.patch.object(
                execution_policy,
                "probe_bubblewrap",
                return_value=probe,
            ), self.assertRaisesRegex(execution_policy.PolicyError, "escapes"):
                execution_policy.prepare_bubblewrap(
                    policy,
                    project_dir=self.project,
                    evidence_root=self.evidence,
                    command=["python", "-V"],
                )

    def test_protected_control_roots_cannot_be_made_writable(self) -> None:
        for protected in (".agents", ".agents/studio", ".git", "control", "evidence"):
            document = valid_policy()
            document["writable_paths"] = [protected]
            with self.subTest(path=protected), self.assertRaisesRegex(
                execution_policy.PolicyError,
                "protected control root",
            ):
                execution_policy.load_policy(self.write_policy(document))

        forged_document = valid_policy()
        forged_document["writable_paths"] = [".agents"]
        forged = execution_policy.ExecutionPolicy(
            policy_id=forged_document["policy_id"],
            backend="bubblewrap",
            writable_paths=(".agents",),
            timeout_seconds=1800,
            required_capabilities=execution_policy.CORE_CAPABILITIES,
            document=forged_document,
            digest=execution_policy.sha256_json(forged_document),
        )
        with self.assertRaisesRegex(
            execution_policy.PolicyError,
            "protected control root",
        ):
            execution_policy.prepare_bubblewrap(
                forged,
                project_dir=self.project,
                evidence_root=self.evidence,
                command=["python", "-V"],
            )

    def test_writable_symlink_alias_into_protected_root_fails_before_create(
        self,
    ) -> None:
        protected = self.project / ".agents"
        protected.mkdir()
        alias = self.project / "build"
        try:
            alias.symlink_to(protected, target_is_directory=True)
        except OSError:
            self.skipTest("directory symlinks are unavailable")
        document = valid_policy()
        document["writable_paths"] = ["build/cache"]
        policy = execution_policy.load_policy(self.write_policy(document))
        with mock.patch.object(
            execution_policy,
            "probe_bubblewrap",
            return_value=verified_probe(Path(sys.executable)),
        ), self.assertRaisesRegex(
            execution_policy.PolicyError,
            "resolves into a protected control root",
        ):
            execution_policy.prepare_bubblewrap(
                policy,
                project_dir=self.project,
                evidence_root=self.evidence,
                command=["python", "-V"],
            )
        self.assertFalse(
            (protected / "cache").exists(),
            "validation must not create files through a protected alias",
        )

    def test_non_linux_probe_is_fail_closed(self) -> None:
        with mock.patch.object(
            execution_policy.sys, "platform", "win32"
        ):
            probe = execution_policy.probe_bubblewrap()
        self.assertFalse(probe.available)
        self.assertFalse(probe.capabilities)
        policy = execution_policy.load_policy(self.write_policy())
        with mock.patch.object(
            execution_policy.sys,
            "platform",
            "win32",
        ), self.assertRaisesRegex(
            execution_policy.CapabilityUnavailable,
            "only supported.*Linux",
        ):
            execution_policy.prepare_bubblewrap(
                policy,
                project_dir=self.project,
                evidence_root=self.evidence,
                command=["python", "-V"],
            )

    def test_version_only_probe_cannot_claim_live_capabilities(self) -> None:
        fake_result = subprocess.CompletedProcess(
            ["fake-bwrap"],
            0,
            "bubblewrap fake\n",
        )
        launch_environments: list[dict[str, str] | None] = []

        def fake_run(*_args, **kwargs):
            launch_environments.append(kwargs.get("env"))
            return fake_result

        with mock.patch.object(
            execution_policy.sys,
            "platform",
            "linux",
        ):
            probe = execution_policy.probe_bubblewrap(
                which=lambda _name: sys.executable,
                run=fake_run,
            )
        self.assertFalse(probe.available)
        self.assertFalse(probe.capabilities)
        if launch_environments:
            self.assertTrue(
                all(environment == {} for environment in launch_environments)
            )

    def test_prepare_wraps_read_only_root_write_allowlist_and_hidden_evidence(
        self,
    ) -> None:
        policy = execution_policy.load_policy(self.write_policy())
        probe = verified_probe(Path(sys.executable))
        with mock.patch.object(
            execution_policy,
            "probe_bubblewrap",
            return_value=probe,
        ):
            prepared = execution_policy.prepare_bubblewrap(
                policy,
                project_dir=self.project,
                evidence_root=self.evidence,
                command=["python", "-V"],
            )

        argv = list(prepared.argv)
        self.assertIn("--ro-bind", argv)
        self.assertIn("--unshare-net", argv)
        self.assertIn("--unshare-pid", argv)
        self.assertIn("--clearenv", argv)
        self.assertIn("--tmpfs", argv)
        self.assertIn(str(self.evidence.resolve()), argv)
        self.assertNotIn(
            ("--ro-bind", "/", "/"),
            {
                tuple(argv[index : index + 3])
                for index in range(max(0, len(argv) - 2))
            },
        )
        self.assertIn(
            (
                "--ro-bind",
                str(self.project.resolve()),
                str(self.project.resolve()),
            ),
            {
                tuple(argv[index : index + 3])
                for index in range(max(0, len(argv) - 2))
            },
        )
        for ephemeral in execution_policy.EPHEMERAL_SANDBOX_ROOTS:
            self.assertIn(
                ("--tmpfs", ephemeral),
                {
                    tuple(argv[index : index + 2])
                    for index in range(max(0, len(argv) - 1))
                },
            )
        for relative in policy.writable_paths:
            self.assertIn(str((self.project / relative).resolve()), argv)
        self.assertEqual("prepared", prepared.attestation["state"])
        self.assertEqual("not_mounted", prepared.attestation["host_root"])
        self.assertEqual(
            "empty_tmpfs_without_host_bind",
            prepared.attestation["evidence_mount"],
        )

        final = execution_policy.finalized_attestation(
            prepared,
            launched=True,
            returncode=0,
            timed_out=False,
            process_tree_cleaned=True,
            process_identity={
                "pid": 123,
                "start_token": "456",
                "platform": "linux-procfs",
            },
            cleanup_verification="bubblewrap_pid_namespace_reaped",
        )
        self.assertEqual("enforced", final["state"])
        self.assertRegex(final["attestation_digest"], r"^[0-9a-f]{64}$")
        with self.assertRaisesRegex(
            execution_policy.CapabilityUnavailable,
            "process identity",
        ):
            execution_policy.finalized_attestation(
                prepared,
                launched=True,
                returncode=0,
                timed_out=False,
                process_tree_cleaned=True,
                cleanup_verification="bubblewrap_pid_namespace_reaped",
            )
        prepared.attestation["network"] = "allowed"
        with self.assertRaisesRegex(
            execution_policy.CapabilityUnavailable,
            "attestation digest mismatch",
        ):
            execution_policy.validate_prepared_sandbox(prepared)

    def test_environment_is_cleared_allowlisted_and_bound_to_prepared_argv(
        self,
    ) -> None:
        policy = execution_policy.load_policy(self.write_policy())
        probe = verified_probe(Path(sys.executable))
        with mock.patch.object(
            execution_policy,
            "probe_bubblewrap",
            return_value=probe,
        ):
            prepared = execution_policy.prepare_bubblewrap(
                policy,
                project_dir=self.project,
                evidence_root=self.evidence,
                command=["python", "-V"],
                environment={
                    "CI": "true",
                    "JAVA_HOME": "/usr/lib/jvm/default-java",
                },
            )

        argv = list(prepared.argv)
        clear_index = argv.index("--clearenv")
        command_index = argv.index("--")
        self.assertLess(clear_index, command_index)
        self.assertIn(
            ("--setenv", "CI", "true"),
            {
                tuple(argv[index : index + 3])
                for index in range(max(0, len(argv) - 2))
            },
        )
        self.assertEqual(
            "clear_then_set_allowlist",
            prepared.attestation["environment_mode"],
        )
        self.assertEqual(
            "true",
            prepared.attestation["environment"]["CI"],
        )
        execution_policy.validate_prepared_sandbox(prepared)

        for injected in (
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
            "PYTHONPATH",
            "PYTHONHOME",
            "JAVA_TOOL_OPTIONS",
        ):
            with self.subTest(injected=injected), self.assertRaisesRegex(
                execution_policy.PolicyError,
                "not permitted",
            ):
                with mock.patch.object(
                    execution_policy,
                    "probe_bubblewrap",
                    return_value=probe,
                ):
                    execution_policy.prepare_bubblewrap(
                        policy,
                        project_dir=self.project,
                        evidence_root=self.evidence,
                        command=["python", "-V"],
                        environment={injected: "/attacker/payload"},
                    )

    def test_timeout_and_minimal_mount_plan_mutations_fail_closed(self) -> None:
        policy = execution_policy.load_policy(self.write_policy())
        with mock.patch.object(
            execution_policy,
            "probe_bubblewrap",
            return_value=verified_probe(Path(sys.executable)),
        ):
            prepared = execution_policy.prepare_bubblewrap(
                policy,
                project_dir=self.project,
                evidence_root=self.evidence,
                command=["python", "-V"],
            )

        with self.assertRaisesRegex(
            execution_policy.CapabilityUnavailable,
            "timeout differs",
        ):
            execution_policy.validate_prepared_sandbox(
                replace(
                    prepared,
                    timeout_seconds=prepared.timeout_seconds * 2,
                )
            )

        argv = list(prepared.argv)
        project_bind = next(
            index
            for index in range(len(argv) - 2)
            if argv[index : index + 3]
            == [
                "--ro-bind",
                str(self.project.resolve()),
                str(self.project.resolve()),
            ]
        )
        argv[project_bind : project_bind] = ["--ro-bind", "/", "/"]
        with self.assertRaisesRegex(
            execution_policy.CapabilityUnavailable,
            "exact minimal sandbox plan",
        ):
            execution_policy.validate_prepared_sandbox(
                replace(prepared, argv=tuple(argv))
            )

    def test_workspace_and_evidence_must_be_disjoint(self) -> None:
        policy = execution_policy.load_policy(self.write_policy())
        inside = self.project / "evidence"
        inside.mkdir()
        with self.assertRaisesRegex(
            execution_policy.PolicyError, "disjoint"
        ):
            execution_policy.prepare_bubblewrap(
                policy,
                project_dir=self.project,
                evidence_root=inside,
                command=["python", "-V"],
            )

    def test_real_backend_denies_source_and_underlying_evidence_writes(self) -> None:
        if not sys.platform.startswith("linux") or not shutil.which("bwrap"):
            self.skipTest("real bubblewrap backend is unavailable")
        live_probe = execution_policy.probe_bubblewrap()
        if not live_probe.available:
            self.skipTest(live_probe.reason)
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
