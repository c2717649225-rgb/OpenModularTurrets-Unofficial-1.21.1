#!/usr/bin/env python3
"""Standard-library tests for the provisional external evidence spine."""
from __future__ import annotations

import concurrent.futures
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


STUDIO_DIR = Path(__file__).resolve().parents[1] / "studio"
sys.path.insert(0, str(STUDIO_DIR))

import evidence
import execution_policy
import runner


ZERO_DIGEST = "0" * 64


def valid_started_payload(workspace: Path) -> dict:
    return {
        "argv": [sys.executable, "-c", "pass"],
        "cwd": str(workspace),
        "host_workspace": str(workspace),
        "digests": {
            "host_tree_digest": ZERO_DIGEST,
            "control_digest": ZERO_DIGEST,
            "input_digest": ZERO_DIGEST,
            "policy_digest": ZERO_DIGEST,
        },
        "report_staging": "non_authoritative",
        "declared_reports": [],
        "declared_artifacts": [],
    }


def valid_summary(
    outcome: str = "PASS",
    *,
    exit_code: int | None = 0,
    timed_out: bool = False,
    interrupted: bool = False,
) -> dict:
    return {
        "outcome": outcome,
        "duration_seconds": 0.125,
        "exit_code": exit_code,
        "timed_out": timed_out,
        "interrupted": interrupted,
        "reports": [],
        "artifacts": [],
        "details": {},
    }


class EvidenceFixture(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="studio_evidence_"))
        self.workspace = self.temp_dir / "workspace"
        self.workspace.mkdir()
        (self.workspace / ".agents").mkdir()
        (self.workspace / ".agents" / "control.txt").write_text(
            "control\n",
            encoding="utf-8",
        )
        (self.workspace / "input.json").write_text(
            '{"input":true}\n',
            encoding="utf-8",
        )
        (self.workspace / "policy.json").write_text(
            '{"policy":1}\n',
            encoding="utf-8",
        )
        self.evidence_root = self.temp_dir / "external-evidence"

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def ledger(self, run_id: str) -> evidence.EvidenceLedger:
        return evidence.EvidenceLedger(
            self.evidence_root,
            self.workspace,
            run_id,
        )

    def start(
        self, ledger: evidence.EvidenceLedger
    ) -> dict[str, object]:
        return ledger.append_event(
            transition_id="runner:started",
            event_type="RUN_STARTED",
            payload=valid_started_payload(self.workspace),
            expected_sequence=0,
        )

    def sealed_ledger(
        self, run_id: str
    ) -> evidence.EvidenceLedger:
        ledger = self.ledger(run_id)
        self.start(ledger)
        ledger.append_event(
            transition_id="gate:one",
            event_type="GATE_RECORDED",
            payload={"gate": "L1", "status": "PASS"},
            expected_sequence=1,
        )
        ledger.seal(
            transition_id="runner:sealed",
            outcome="PASS",
            summary=valid_summary(),
            expected_sequence=2,
        )
        return ledger

    def bindings(self) -> runner.DigestBindings:
        return runner.DigestBindings.capture(
            self.workspace,
            control_paths=(".agents",),
            input_paths=("input.json",),
            policy="policy.json",
        )


class TestEvidenceLedger(EvidenceFixture):
    def test_external_root_must_be_physically_disjoint(self):
        with self.assertRaises(evidence.EvidencePathError):
            evidence.EvidenceLedger(
                self.workspace / "evidence",
                self.workspace,
                "inside",
            )

        outer = self.temp_dir / "outer"
        nested_workspace = outer / "workspace"
        nested_workspace.mkdir(parents=True)
        with self.assertRaises(evidence.EvidencePathError):
            evidence.EvidenceLedger(
                outer,
                nested_workspace,
                "ancestor",
            )

    def test_run_id_directory_and_cas_append(self):
        ledger = self.ledger("run-001")
        started = self.start(ledger)
        self.assertEqual(1, started["sequence"])
        self.assertEqual(
            self.evidence_root.resolve() / "runs" / "run-001",
            ledger.run_dir,
        )
        with self.assertRaises(evidence.EvidenceSequenceConflict):
            ledger.append_event(
                transition_id="gate:stale",
                event_type="GATE_RECORDED",
                payload={"status": "PASS"},
                expected_sequence=0,
            )

    def test_transition_id_is_idempotent_only_for_same_content(self):
        ledger = self.ledger("idempotency")
        payload = valid_started_payload(self.workspace)
        first = ledger.append_event(
            transition_id="runner:started",
            event_type="RUN_STARTED",
            payload=payload,
            expected_sequence=0,
        )
        retried = ledger.append_event(
            transition_id="runner:started",
            event_type="RUN_STARTED",
            payload=payload,
            expected_sequence=999,
        )
        self.assertEqual(first, retried)
        with self.assertRaises(evidence.EvidenceTransitionConflict):
            ledger.append_event(
                transition_id="runner:started",
                event_type="RUN_STARTED",
                payload={**payload, "argv": ["different"]},
                expected_sequence=1,
            )

    def test_started_and_sealed_payloads_fail_closed(self):
        ledger = self.ledger("typed-payload")
        with self.assertRaisesRegex(
            evidence.EvidenceIntegrityError,
            "invalid fields",
        ):
            ledger.append_event(
                transition_id="runner:started",
                event_type="RUN_STARTED",
                payload={"not": "the schema"},
                expected_sequence=0,
            )
        self.start(ledger)
        broken = valid_summary()
        broken.pop("reports")
        with self.assertRaisesRegex(
            evidence.EvidenceIntegrityError,
            "invalid fields",
        ):
            ledger.seal(
                transition_id="runner:sealed",
                outcome="PASS",
                summary=broken,
                expected_sequence=1,
            )

    def test_concurrent_writers_are_serialized_and_replayable(self):
        ledger = self.ledger("concurrent")
        self.start(ledger)

        def append(index: int) -> None:
            for _attempt in range(100):
                sequence = ledger.replay(require_sealed=False).last_sequence
                try:
                    ledger.append_event(
                        transition_id=f"worker:{index}",
                        event_type="WORK_RECORDED",
                        payload={"worker": index},
                        expected_sequence=sequence,
                    )
                    return
                except evidence.EvidenceSequenceConflict:
                    continue
            raise AssertionError("CAS writer did not converge")

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(append, range(8)))

        replay = ledger.replay(require_sealed=False)
        self.assertEqual(9, replay.last_sequence)
        self.assertEqual(
            {f"worker:{index}" for index in range(8)},
            {
                event["transition_id"]
                for event in replay.events
                if event["event_type"] == "WORK_RECORDED"
            },
        )

    def test_seal_creates_replayable_hash_bound_bundle(self):
        ledger = self.sealed_ledger("sealed")
        replay = ledger.replay()
        self.assertTrue(replay.sealed)
        self.assertEqual("PASS", replay.outcome)
        self.assertEqual(3, replay.last_sequence)
        self.assertEqual(
            replay.head_digest,
            replay.bundle["journal_head_digest"],
        )
        self.assertEqual(
            evidence.sha256_file(ledger.journal_path),
            replay.bundle["journal_sha256"],
        )

    def test_unsealed_journal_fails_completion_replay(self):
        ledger = self.ledger("unsealed")
        self.start(ledger)
        with self.assertRaises(evidence.EvidenceUnsealedError):
            ledger.replay()
        self.assertFalse(ledger.replay(require_sealed=False).sealed)

    def test_deleted_event_is_detected(self):
        ledger = self.sealed_ledger("deleted-event")
        lines = ledger.journal_path.read_bytes().splitlines(keepends=True)
        ledger.journal_path.write_bytes(lines[0] + lines[2])
        with self.assertRaises(evidence.EvidenceIntegrityError):
            ledger.replay()

    def test_reordered_events_are_detected(self):
        ledger = self.sealed_ledger("reordered")
        lines = ledger.journal_path.read_bytes().splitlines(keepends=True)
        ledger.journal_path.write_bytes(lines[1] + lines[0] + lines[2])
        with self.assertRaises(evidence.EvidenceIntegrityError):
            ledger.replay()

    def test_tampered_event_is_detected(self):
        ledger = self.sealed_ledger("tampered")
        lines = ledger.journal_path.read_bytes().splitlines(keepends=True)
        event = json.loads(lines[1])
        event["payload"]["status"] = "FAIL"
        lines[1] = evidence.canonical_json_bytes(event) + b"\n"
        ledger.journal_path.write_bytes(b"".join(lines))
        with self.assertRaisesRegex(
            evidence.EvidenceIntegrityError,
            "event_digest mismatch",
        ):
            ledger.replay()

    def test_truncated_journal_is_detected(self):
        ledger = self.sealed_ledger("truncated")
        raw = ledger.journal_path.read_bytes()
        ledger.journal_path.write_bytes(raw[:-1])
        with self.assertRaisesRegex(
            evidence.EvidenceIntegrityError,
            "truncated",
        ):
            ledger.replay()

    def test_deleted_or_tampered_bundle_is_detected(self):
        deleted = self.sealed_ledger("deleted-bundle")
        deleted.bundle_path.unlink()
        with self.assertRaisesRegex(
            evidence.EvidenceIntegrityError,
            "bundle.json is missing",
        ):
            deleted.replay()

        tampered = self.sealed_ledger("tampered-bundle")
        bundle = json.loads(tampered.bundle_path.read_bytes())
        bundle["outcome"] = "FAIL"
        tampered.bundle_path.write_bytes(
            evidence.canonical_json_bytes(bundle) + b"\n"
        )
        with self.assertRaisesRegex(
            evidence.EvidenceIntegrityError,
            "bundle_digest mismatch",
        ):
            tampered.replay()

    def test_authoritative_evidence_budget_is_enforced_before_append(self):
        ledger = self.ledger("oversize-event")
        self.start(ledger)
        with self.assertRaisesRegex(
            evidence.EvidenceBudgetError,
            "frozen .* budget",
        ):
            ledger.append_event(
                transition_id="builder:oversize",
                event_type="WORK_RECORDED",
                payload={"embedded_log": "x" * evidence.MAX_EVIDENCE_BYTES},
                expected_sequence=1,
            )
        replay = ledger.replay(require_sealed=False)
        self.assertEqual(1, replay.last_sequence)
        self.assertLessEqual(
            ledger.journal_path.stat().st_size,
            evidence.MAX_EVIDENCE_BYTES,
        )


class TestStudioRunner(EvidenceFixture):
    def make_runner(
        self,
        run_id: str,
        *,
        reports: dict[str, str] | None = None,
        artifacts: dict[str, str] | None = None,
    ) -> runner.StudioRunner:
        bindings = self.bindings()
        return runner.StudioRunner(
            self.evidence_root,
            self.workspace,
            run_id,
            digests=bindings,
            frozen_policy_digest=bindings.policy_digest,
            reports=reports,
            artifacts=artifacts,
        )

    def test_runner_records_bindings_argv_cwd_reports_and_artifacts(self):
        reports_dir = self.workspace / "build" / "reports"
        reports_dir.mkdir(parents=True)
        report = reports_dir / "gate.json"
        report.write_text('{"status":"PASS"}\n', encoding="utf-8")
        artifact = self.workspace / "build" / "artifact.bin"
        artifact.write_bytes(b"artifact")

        studio_runner = self.make_runner(
            "runner-pass",
            reports={"L1": "build/reports/gate.json"},
            artifacts={"jar": "build/artifact.bin"},
        )

        def operation(context: runner.RunContext) -> int:
            context.record_event(
                transition_id="gate:L1",
                event_type="GATE_RECORDED",
                payload={"gate": "L1", "status": "PASS"},
            )
            return 0

        record = studio_runner.run(
            [sys.executable, "-c", "pass"],
            operation,
        )
        self.assertEqual("PASS", record.outcome)
        self.assertTrue(record.bundle_path.is_file())
        started = record.replay.events[0]["payload"]
        self.assertEqual(
            [sys.executable, "-c", "pass"],
            started["argv"],
        )
        self.assertEqual(str(self.workspace.resolve()), started["cwd"])
        self.assertEqual(
            studio_runner.digests.as_dict(),
            started["digests"],
        )
        terminal = record.replay.events[-1]["payload"]
        self.assertEqual(
            evidence.sha256_file(report),
            terminal["reports"][0]["sha256"],
        )
        self.assertEqual(
            evidence.sha256_file(artifact),
            terminal["artifacts"][0]["sha256"],
        )
        self.assertEqual("non_authoritative", started["report_staging"])

    def test_nonzero_return_is_sealed_as_fail(self):
        record = self.make_runner("runner-fail").run(
            ["fixture", "fail"],
            lambda _context: 7,
        )
        self.assertEqual("FAIL", record.outcome)
        self.assertEqual(7, record.exit_code)
        self.assertEqual("FAIL", record.replay.bundle["outcome"])

    def test_typed_terminal_conditions_are_all_sealed(self):
        cases = {
            "blocked": (
                lambda _context: (_ for _ in ()).throw(
                    runner.RunnerBlockedError("no sandbox")
                ),
                "BLOCKED",
            ),
            "infra": (
                lambda _context: (_ for _ in ()).throw(
                    runner.VerifiedInfrastructureError("runner unavailable")
                ),
                "VERIFIED_INFRA_ERROR",
            ),
            "timeout": (
                lambda _context: (_ for _ in ()).throw(
                    subprocess.TimeoutExpired(["fixture"], 0.01)
                ),
                "TIMEOUT",
            ),
        }
        for name, (operation, outcome) in cases.items():
            with self.subTest(name=name):
                record = self.make_runner(f"runner-{name}").run(
                    ["fixture", name],
                    operation,
                )
                self.assertEqual(outcome, record.outcome)
                self.assertTrue(record.replay.sealed)
                self.assertEqual(
                    outcome == "TIMEOUT",
                    record.replay.events[-1]["payload"]["timed_out"],
                )

    def test_prelaunch_block_still_starts_records_probe_and_seals(self):
        studio_runner = self.make_runner("prelaunch-blocked")
        record = studio_runner.seal_without_launch(
            ["bwrap", "--version"],
            runner.RunDecision.blocked(
                details={"reason": "sandbox backend unavailable"}
            ),
            event={
                "transition_id": "policy:probe",
                "event_type": "CAPABILITY_PROBED",
                "payload": {
                    "capability": "sandbox",
                    "available": False,
                },
            },
        )
        self.assertEqual(
            ["RUN_STARTED", "CAPABILITY_PROBED", "RUN_SEALED"],
            [event["event_type"] for event in record.replay.events],
        )
        self.assertEqual("BLOCKED", record.outcome)

    def test_interruption_is_rethrown_after_fail_seal(self):
        studio_runner = self.make_runner("runner-interrupted")
        with self.assertRaises(KeyboardInterrupt):
            studio_runner.run(
                ["fixture", "interrupt"],
                lambda _context: (_ for _ in ()).throw(
                    KeyboardInterrupt()
                ),
            )
        replay = studio_runner.ledger.replay()
        self.assertEqual("FAIL", replay.outcome)
        self.assertTrue(replay.events[-1]["payload"]["interrupted"])

    def test_unexpected_exception_is_rethrown_after_fail_seal(self):
        studio_runner = self.make_runner("runner-exception")
        with self.assertRaisesRegex(RuntimeError, "fixture exploded"):
            studio_runner.run(
                ["fixture", "explode"],
                lambda _context: (_ for _ in ()).throw(
                    RuntimeError("fixture exploded")
                ),
            )
        replay = studio_runner.ledger.replay()
        self.assertEqual("FAIL", replay.outcome)
        self.assertFalse(replay.events[-1]["payload"]["interrupted"])

    def test_run_command_rejects_bare_builder_argv_before_journal_start(self):
        studio_runner = self.make_runner("bare-command")
        tamper = (
            "from pathlib import Path; "
            "Path(__import__('sys').argv[1]).write_text('forged')"
        )
        with self.assertRaisesRegex(
            execution_policy.CapabilityUnavailable,
            "PreparedSandbox",
        ):
            studio_runner.run_command(  # type: ignore[arg-type]
                [
                    sys.executable,
                    "-c",
                    tamper,
                    str(studio_runner.ledger.journal_path),
                ],
            )
        self.assertFalse(studio_runner.ledger.journal_path.exists())

    def test_run_command_never_passes_caller_or_host_environment_to_bwrap(
        self,
    ):
        policy_document = {
            "schema_version": 1,
            "stability": "provisional",
            "policy_id": "test.environment",
            "backend": "bubblewrap",
            "writable_paths": [".gradle", "build"],
            "timeout_seconds": 10,
            "required_capabilities": sorted(
                execution_policy.CORE_CAPABILITIES
            ),
        }
        policy_path = self.workspace / "strict-environment.json"
        policy_path.write_text(
            json.dumps(policy_document),
            encoding="utf-8",
        )
        policy = execution_policy.load_policy(policy_path)
        self.evidence_root.mkdir()
        executable = Path(sys.executable).resolve()
        executable_digest = execution_policy.sha256_file(executable)
        probe_document = {
            "backend": "bubblewrap",
            "version": "test",
            "executable": str(executable),
            "executable_sha256": executable_digest,
            "verification": execution_policy.LIVE_PROBE_VERIFICATION,
            "capabilities": sorted(execution_policy.CORE_CAPABILITIES),
        }
        probe = execution_policy.BackendProbe(
            "bubblewrap",
            True,
            "test",
            execution_policy.CORE_CAPABILITIES,
            "",
            str(executable),
            executable_digest,
            execution_policy.LIVE_PROBE_VERIFICATION,
            execution_policy.sha256_json(probe_document),
        )
        with mock.patch.object(
            execution_policy,
            "probe_bubblewrap",
            return_value=probe,
        ):
            prepared = execution_policy.prepare_bubblewrap(
                policy,
                project_dir=self.workspace,
                evidence_root=self.evidence_root,
                command=[sys.executable, "-c", "pass"],
                environment={"CI": "true"},
            )
        bindings = runner.DigestBindings.capture(
            self.workspace,
            control_paths=(".agents",),
            policy=policy.document,
        )
        rejected = runner.StudioRunner(
            self.evidence_root,
            self.workspace,
            "runtime-env-rejected",
            digests=bindings,
            frozen_policy_digest=policy.digest,
        )
        with self.assertRaisesRegex(
            runner.RunnerError,
            "runtime environment overrides are forbidden",
        ):
            rejected.run_command(
                prepared,
                environment={"LD_PRELOAD": "/attacker.so"},
            )
        self.assertFalse(rejected.ledger.journal_path.exists())

        class CompletedProcess:
            pid = 123
            returncode = 0

            def wait(self, timeout=None):
                return 0

            def poll(self):
                return 0

        clean = runner.StudioRunner(
            self.evidence_root,
            self.workspace,
            "host-env-empty",
            digests=bindings,
            frozen_policy_digest=policy.digest,
        )
        with mock.patch.object(
            runner.subprocess,
            "Popen",
            return_value=CompletedProcess(),
        ) as popen, mock.patch.object(
            runner,
            "_linux_process_identity",
            return_value={
                "pid": 123,
                "start_token": "456",
                "platform": "linux-procfs",
            },
        ):
            record = clean.run_command(prepared)
        self.assertEqual("PASS", record.outcome)
        self.assertEqual({}, popen.call_args.kwargs["env"])
        self.assertIn("--clearenv", popen.call_args.args[0])

    def test_real_sandbox_hides_authoritative_journal_from_builder(self):
        if not sys.platform.startswith("linux") or not shutil.which("bwrap"):
            self.skipTest("real bubblewrap backend is unavailable")
        probe = execution_policy.probe_bubblewrap()
        if not probe.available:
            self.skipTest(probe.reason)
        policy_path = self.workspace / "strict-policy.json"
        policy_path.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "stability": "provisional",
                    "policy_id": "test.strict",
                    "backend": "bubblewrap",
                    "writable_paths": ["build"],
                    "timeout_seconds": 10,
                    "required_capabilities": sorted(
                        execution_policy.CORE_CAPABILITIES
                    ),
                }
            ),
            encoding="utf-8",
        )
        policy = execution_policy.load_policy(policy_path)
        bindings = runner.DigestBindings(
            "a" * 64,
            "b" * 64,
            "c" * 64,
            policy.digest,
        )
        studio_runner = runner.StudioRunner(
            self.evidence_root,
            self.workspace,
            "sandbox-journal",
            digests=bindings,
            frozen_policy_digest=policy.digest,
            reports={"major": "build/major.json"},
        )
        report = self.workspace / "build" / "major.json"
        script = (
            "from pathlib import Path\n"
            f"journal=Path({str(studio_runner.ledger.journal_path)!r})\n"
            "assert not journal.exists()\n"
            f"report=Path({str(report)!r})\n"
            "report.write_text('{\"status\":\"PASS\"}\\n', encoding='utf-8')\n"
        )
        prepared = execution_policy.prepare_bubblewrap(
            policy,
            project_dir=self.workspace,
            evidence_root=self.evidence_root,
            command=[sys.executable, "-c", script],
        )
        record = studio_runner.run_command(prepared)
        self.assertEqual("PASS", record.outcome)
        self.assertEqual(policy.digest, record.replay.events[0]["payload"][
            "digests"
        ]["policy_digest"])

    def test_unsafe_output_declarations_fail_before_execution(self):
        with self.assertRaises(runner.RunnerError):
            self.make_runner(
                "unsafe-parent",
                reports={"escape": "../outside.json"},
            )
        with self.assertRaises(runner.RunnerError):
            self.make_runner(
                "unsafe-absolute",
                reports={"escape": str((self.temp_dir / "x").resolve())},
            )

    def test_runner_requires_external_frozen_policy_digest(self):
        bindings = self.bindings()
        with self.assertRaisesRegex(
            runner.RunnerError,
            "externally frozen policy",
        ):
            runner.StudioRunner(
                self.evidence_root,
                self.workspace,
                "policy-drift",
                digests=bindings,
                frozen_policy_digest="f" * 64,
            )

    def test_missing_required_output_fails_closed_without_fabricated_digest(self):
        record = self.make_runner(
            "missing-output",
            reports={"major": "build/missing.json"},
        ).run(["fixture"], lambda _context: 0)
        missing = record.replay.events[-1]["payload"]["reports"][0]
        self.assertEqual("VERIFIED_INFRA_ERROR", record.outcome)
        self.assertEqual("missing", missing["kind"])
        self.assertFalse(missing["exists"])
        self.assertIsNone(missing["sha256"])
        self.assertEqual(
            "output_digest_capture_failed",
            record.replay.events[-1]["payload"]["details"]["classification"],
        )

    def test_oversize_terminal_details_are_replaced_and_sealed_fail_closed(self):
        studio_runner = self.make_runner("oversize-terminal")
        record = studio_runner.run(
            ["fixture"],
            lambda _context: runner.RunDecision.passed(
                details={
                    "embedded_log": "x" * evidence.MAX_EVIDENCE_BYTES,
                }
            ),
        )
        self.assertEqual("VERIFIED_INFRA_ERROR", record.outcome)
        self.assertTrue(record.replay.sealed)
        self.assertEqual(
            "evidence_budget_exceeded",
            record.replay.events[-1]["payload"]["details"]["classification"],
        )
        total = (
            record.bundle_path.stat().st_size
            + studio_runner.ledger.journal_path.stat().st_size
        )
        self.assertLessEqual(total, evidence.MAX_EVIDENCE_BYTES)

    def test_oversize_runtime_event_is_rejected_then_fail_sealed(self):
        studio_runner = self.make_runner("oversize-runtime-event")

        def operation(context: runner.RunContext) -> int:
            context.record_event(
                transition_id="builder:oversize",
                event_type="WORK_RECORDED",
                payload={
                    "embedded_log": "x" * evidence.MAX_EVIDENCE_BYTES,
                },
            )
            return 0

        with self.assertRaises(evidence.EvidenceBudgetError):
            studio_runner.run(["fixture"], operation)
        replay = studio_runner.ledger.replay()
        self.assertEqual("FAIL", replay.outcome)
        self.assertTrue(replay.sealed)
        self.assertLessEqual(
            studio_runner.ledger.journal_path.stat().st_size
            + studio_runner.ledger.bundle_path.stat().st_size,
            evidence.MAX_EVIDENCE_BYTES,
        )


if __name__ == "__main__":
    unittest.main()
