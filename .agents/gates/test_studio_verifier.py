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

import evidence
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

    def _sealed_ledger(
        self,
        run_id: str,
        *,
        outcome: str = "PASS",
        host_tree_digest: str = "a" * 64,
        control_digest: str = "b" * 64,
        input_digest: str = "c" * 64,
        policy_digest: str = "d" * 64,
        gate_argv: list[str] | None = None,
    ) -> evidence.EvidenceLedger:
        ledger = evidence.EvidenceLedger(
            self.temp_dir / "evidence",
            self.repository,
            run_id,
        )
        ledger.append_event(
            transition_id="runner:started",
            event_type="RUN_STARTED",
            payload={
                "argv": ["fixture"],
                "cwd": str(self.repository),
                "host_workspace": str(self.repository),
                "digests": {
                    "host_tree_digest": host_tree_digest,
                    "control_digest": control_digest,
                    "input_digest": input_digest,
                    "policy_digest": policy_digest,
                },
                "report_staging": "non_authoritative",
                "declared_reports": [],
                "declared_artifacts": [],
            },
            expected_sequence=0,
        )
        sequence = 1
        if gate_argv is not None:
            ledger.append_event(
                transition_id="sandbox:attested",
                event_type="SANDBOX_ATTESTED",
                payload={
                    "state": "enforced",
                    "policy_digest": policy_digest,
                    "command_digest": evidence.digest_json(gate_argv),
                },
                expected_sequence=sequence,
            )
            sequence += 1
        ledger.seal(
            transition_id="runner:sealed",
            outcome=outcome,
            summary={
                "outcome": outcome,
                "duration_seconds": 0.1,
                "exit_code": 0 if outcome == "PASS" else 1,
                "timed_out": False,
                "interrupted": False,
                "reports": [],
                "artifacts": [],
                "details": {},
            },
            expected_sequence=sequence,
        )
        return ledger

    def test_sealed_bundle_consumption_requires_frozen_digest_and_pass(self):
        passed = self._sealed_ledger("source-pass")
        expected = passed.replay().bundle["bundle_digest"]
        replay = verifier.consume_sealed_bundle(
            passed,
            expected_bundle_digest=expected,
        )
        self.assertEqual("PASS", replay.outcome)
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "externally frozen digest",
        ):
            verifier.consume_sealed_bundle(
                passed,
                expected_bundle_digest="f" * 64,
            )

        failed = self._sealed_ledger("source-fail", outcome="FAIL")
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "not independently eligible",
        ):
            verifier.consume_sealed_bundle(
                failed,
                expected_bundle_digest=failed.replay().bundle[
                    "bundle_digest"
                ],
            )

    def test_verification_decision_binds_both_bundles_and_detects_tamper(self):
        snapshot = verifier.snapshot_repository(self.repository)
        source = self._sealed_ledger(
            "decision-source",
            host_tree_digest=snapshot.tree_digest,
            control_digest=snapshot.control_digest,
        ).replay()
        gate_argv = ["python", "gate.py"]
        verifier_input_digest = verifier.verification_input_digest(
            source,
            snapshot,
            expected_policy_digest="d" * 64,
        )
        checked = self._sealed_ledger(
            "decision-verifier",
            host_tree_digest=snapshot.tree_digest,
            control_digest=snapshot.control_digest,
            input_digest=verifier_input_digest,
            policy_digest="d" * 64,
            gate_argv=gate_argv,
        ).replay()
        decision = verifier.VerificationDecision(
            verifier._decision_document(
                source_replay=source,
                verifier_replay=checked,
                source_snapshot=snapshot,
                gate_argv=gate_argv,
                policy_digest="d" * 64,
            )
        )
        verifier.validate_verification_decision(
            decision,
            source_replay=source,
            verifier_replay=checked,
            expected_source_snapshot=snapshot,
            expected_policy_digest="d" * 64,
        )
        self.assertTrue(decision.passed)

        tampered = decision.as_dict()
        tampered["passed"] = False
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "decision digest mismatch",
        ):
            verifier.validate_verification_decision(
                tampered,
                source_replay=source,
                verifier_replay=checked,
                expected_source_snapshot=snapshot,
                expected_policy_digest="d" * 64,
            )

        rebound = decision.as_dict()
        rebound["source_tree_digest"] = "f" * 64
        rebound.pop("decision_digest")
        rebound["decision_digest"] = evidence.digest_json(rebound)
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "does not match the expected clean snapshot",
        ):
            verifier.validate_verification_decision(
                rebound,
                source_replay=source,
                verifier_replay=checked,
                expected_source_snapshot=snapshot,
                expected_policy_digest="d" * 64,
            )

        forged_snapshot = verifier.SourceSnapshot(
            commit="0" * len(snapshot.commit),
            tree_digest=snapshot.tree_digest,
            control_digest=snapshot.control_digest,
        )
        rebound_commit = decision.as_dict()
        rebound_commit["commit"] = forged_snapshot.commit
        rebound_commit["verification_input_digest"] = (
            verifier.verification_input_digest(
                source,
                forged_snapshot,
                expected_policy_digest="d" * 64,
            )
        )
        rebound_commit.pop("decision_digest")
        rebound_commit["decision_digest"] = evidence.digest_json(
            rebound_commit
        )
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "verification_input_digest is not bound to the verifier journal",
        ):
            verifier.validate_verification_decision(
                rebound_commit,
                source_replay=source,
                verifier_replay=checked,
                expected_source_snapshot=forged_snapshot,
                expected_policy_digest="d" * 64,
            )

    def test_source_bundle_cannot_be_rebound_to_another_commit_or_policy(self):
        snapshot_a = verifier.snapshot_repository(self.repository)
        source_a = self._sealed_ledger(
            "source-a",
            host_tree_digest=snapshot_a.tree_digest,
            control_digest=snapshot_a.control_digest,
            policy_digest="d" * 64,
        ).replay()
        provenance = verifier.validate_source_replay_provenance(
            source_a,
            snapshot_a,
            expected_policy_digest="d" * 64,
        )
        self.assertEqual(snapshot_a.commit, provenance["commit"])

        (self.repository / "src" / "main.txt").write_text(
            "different committed source\n",
            encoding="utf-8",
        )
        run_git(self.repository, "add", "src/main.txt")
        run_git(self.repository, "commit", "--quiet", "-m", "second fixture")
        snapshot_b = verifier.snapshot_repository(self.repository)
        with self.assertRaisesRegex(
            verifier.VerificationError,
            "host_tree_digest does not match",
        ):
            verifier.validate_source_replay_provenance(
                source_a,
                snapshot_b,
                expected_policy_digest="d" * 64,
            )

        with self.assertRaisesRegex(
            verifier.VerificationError,
            "policy_digest does not match",
        ):
            verifier.validate_source_replay_provenance(
                source_a,
                snapshot_a,
                expected_policy_digest="e" * 64,
            )


if __name__ == "__main__":
    unittest.main()
