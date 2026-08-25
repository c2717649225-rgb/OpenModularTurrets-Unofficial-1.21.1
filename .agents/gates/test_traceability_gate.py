#!/usr/bin/env python3
"""Standard-library tests for acceptance-to-GameTest traceability."""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path


GATES_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(GATES_DIR))

import gametest_gate
import traceability_gate
from test_contract_v2 import DESIGN_CONTENT, valid_v2_contract


ONE_TEST_PASS_OUTPUT = """
[Server thread/INFO] 1 test are now running at position 1, 2, 3!
[Server thread/INFO] ========= 1 GAME TESTS COMPLETE IN 0.5 s ======================
[Server thread/INFO] All 1 required tests passed :)
BUILD SUCCESSFUL in 4s
"""
SYMBOL = "com.example.FeatureGameTests#featureWorks"


class TestTraceabilityGate(unittest.TestCase):
    def setUp(self):
        self.project = Path(
            tempfile.mkdtemp(prefix="traceability_gate_")
        ).resolve()
        self.contract_path = (
            self.project / "docs" / "features" / "feature.json"
        )
        self.gametest_report_path = (
            self.project / "build" / "reports" / "gametest.json"
        )
        self.source_path = (
            self.project
            / "src"
            / "main"
            / "java"
            / "com"
            / "example"
            / "FeatureGameTests.java"
        )
        (self.project / "gradle.properties").write_text(
            "mod_id=example\n",
            encoding="utf-8",
        )
        shutil.copytree(
            GATES_DIR / "runtime" / "gametest-reporter",
            self.project
            / ".agents"
            / "gates"
            / "runtime"
            / "gametest-reporter",
        )
        design_path = (
            self.project / "docs" / "design" / "feature-v2.md"
        )
        design_path.parent.mkdir(parents=True, exist_ok=True)
        design_path.write_bytes(DESIGN_CONTENT.encode("utf-8"))
        self.write_source()
        self.write_contract()
        self.write_gametest_report()

    def tearDown(self):
        shutil.rmtree(self.project, ignore_errors=True)

    def write_json(self, path: Path, value) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    def read_json(self, path: Path):
        return json.loads(path.read_text(encoding="utf-8"))

    def write_source(self, *, static: bool = True) -> None:
        modifier = "public static" if static else "public"
        self.source_path.parent.mkdir(parents=True, exist_ok=True)
        self.source_path.write_text(
            f"""
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("example")
            public class FeatureGameTests {{
                @GameTest
                {modifier} void featureWorks(GameTestHelper helper) {{}}
            }}
            """,
            encoding="utf-8",
        )

    def contract(self):
        contract = valid_v2_contract("example.feature")
        contract["acceptance"]["tests"].append(
            {
                "id": "gametest.feature-works",
                "kind": "gametest",
                "command": [sys.executable, "-c", "raise SystemExit(0)"],
                "covers": ["The exact runtime GameTest symbol passes."],
                "required": True,
                "expected": "The feature GameTest passes.",
                "timeout_seconds": 60,
                "test_ref": SYMBOL,
            }
        )
        contract["acceptance"]["criteria"] = [
            {
                "id": "criterion.feature-works",
                "risk": "P0",
                "required": True,
                "statement": "The feature works.",
                "observation": "The exact GameTest symbol passes.",
                "test_ids": ["gametest.feature-works"],
            }
        ]
        return contract

    def write_contract(self, value=None) -> None:
        self.write_json(
            self.contract_path, self.contract() if value is None else value
        )

    def write_gametest_report(self) -> None:
        discovery = gametest_gate.discover_gametests(self.project)
        self.assertEqual([], discovery.errors)
        bytecode_path = (
            self.project
            / "build"
            / "classes"
            / "java"
            / "main"
            / "com"
            / "example"
            / "FeatureGameTests.class"
        )
        bytecode = b"verified-test-bytecode"
        bytecode_path.parent.mkdir(parents=True, exist_ok=True)
        bytecode_path.write_bytes(bytecode)
        discovery.tests[0] = replace(
            discovery.tests[0],
            bytecode_path=bytecode_path.relative_to(self.project).as_posix(),
            bytecode_sha256=hashlib.sha256(bytecode).hexdigest(),
            bytecode_verified=True,
        )
        execution = gametest_gate.parse_gametest_output(
            ONE_TEST_PASS_OUTPUT,
            command=["gradlew", "runGameTestServer"],
            returncode=0,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1,
            discovered_tests=discovery.count,
        )
        nonce = "traceability-fixture-nonce"
        runtime_name = discovery.tests[0].runtime_name
        events = [
            {
                "protocol": gametest_gate.RUNTIME_EVENT_PROTOCOL,
                "nonce": nonce,
                "sequence": 1,
                "event": "run_started",
                "test_name": "",
                "required": True,
                "detail": "",
            },
            {
                "protocol": gametest_gate.RUNTIME_EVENT_PROTOCOL,
                "nonce": nonce,
                "sequence": 2,
                "event": "test_passed",
                "test_name": runtime_name,
                "required": True,
                "detail": "",
            },
            {
                "protocol": gametest_gate.RUNTIME_EVENT_PROTOCOL,
                "nonce": nonce,
                "sequence": 3,
                "event": "run_finished",
                "passed": 1,
                "failed": 0,
            },
        ]
        raw_events = "".join(
            json.dumps(event, separators=(",", ":")) + "\n"
            for event in events
        ).encode("utf-8")
        event_evidence = gametest_gate.validate_runtime_event_bytes(
            raw_events,
            nonce=nonce,
            expected_runtime_symbols={runtime_name: SYMBOL},
        )
        control_digest, control_files = (
            gametest_gate.reporter_control_attestation(self.project)
        )
        execution = gametest_gate.bind_runtime_evidence(
            execution,
            event_evidence,
            reporter_jar_sha256="a" * 64,
            reporter_control_sha256=control_digest,
            reporter_control_files=control_files,
        )
        report = gametest_gate._build_report(
            discovery,
            execution,
            command_ok=True,
            evidence_satisfied=True,
            reason=execution.reason,
            contracts_present=True,
            tests_required=True,
        )
        self.write_json(self.gametest_report_path, report)

    def run_gate(self):
        return traceability_gate.run_gate(
            [self.contract_path],
            self.gametest_report_path,
            project_dir=self.project,
        )

    def finding_codes(self, report):
        return {finding.code for finding in report.findings}

    def test_maps_criterion_to_exact_runtime_symbol_l4_pass(self):
        report = self.run_gate()

        self.assertTrue(report.passed)
        self.assertEqual([], report.findings)
        self.assertEqual(1, len(report.mappings))
        mapping = report.mappings[0]
        self.assertEqual("criterion.feature-works", mapping.criterion_id)
        self.assertEqual("gametest.feature-works", mapping.acceptance_test_id)
        self.assertEqual(SYMBOL, mapping.symbol)
        self.assertEqual("runtime_symbol_set", mapping.evidence_level)
        self.assertEqual("passed", mapping.l4_status)
        self.assertEqual(
            "src/main/java/com/example/FeatureGameTests.java",
            mapping.source_path,
        )
        self.assertRegex(mapping.source_sha256, r"^[0-9a-f]{64}$")
        payload = report.to_dict()
        self.assertRegex(
            payload["inputs"]["contracts"][0]["sha256"],
            r"^[0-9a-f]{64}$",
        )
        self.assertRegex(
            payload["inputs"]["gametest_report"]["sha256"],
            r"^[0-9a-f]{64}$",
        )
        self.assertTrue(
            payload["coverage"]["full_required_criteria_coverage"]
        )
        self.assertEqual(1, payload["coverage"]["required_criteria_covered"])
        self.assertEqual([], payload["coverage"]["uncovered"])

    def test_unknown_gametest_symbol_fails_closed(self):
        contract = self.contract()
        contract["acceptance"]["tests"][-1]["test_ref"] = (
            "com.example.FeatureGameTests#missing"
        )
        self.write_contract(contract)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "gametest_symbol_not_discovered", self.finding_codes(report)
        )

    def test_duplicate_gametest_ref_fails_closed(self):
        contract = self.contract()
        contract["acceptance"]["tests"].append(
            {
                "id": "gametest.duplicate",
                "kind": "gametest",
                "test_ref": SYMBOL,
                "required": True,
            }
        )
        contract["acceptance"]["criteria"][0]["test_ids"].append(
            "gametest.duplicate"
        )
        self.write_contract(contract)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn("duplicate_gametest_ref", self.finding_codes(report))

    def test_invalid_discovered_signature_fails_closed(self):
        report_json = self.read_json(self.gametest_report_path)
        report_json["discovery"]["tests"][0]["signature_valid"] = False
        report_json["discovery"]["tests"][0]["signature_errors"] = [
            "GameTest method must be static"
        ]
        self.write_json(self.gametest_report_path, report_json)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "invalid_gametest_signature", self.finding_codes(report)
        )

    def test_missing_symbol_components_fail_closed(self):
        report_json = self.read_json(self.gametest_report_path)
        report_json["discovery"]["tests"][0].pop("method")
        self.write_json(self.gametest_report_path, report_json)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn("invalid_discovered_method", self.finding_codes(report))
        self.assertIn("discovery_metadata_drift", self.finding_codes(report))

    def test_source_path_outside_main_fails_closed(self):
        report_json = self.read_json(self.gametest_report_path)
        report_json["discovery"]["tests"][0]["path"] = "docs/Fake.java"
        self.write_json(self.gametest_report_path, report_json)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn("source_path_outside_main", self.finding_codes(report))

    def test_duplicate_json_object_key_fails_closed(self):
        self.contract_path.write_text(
            '{"schema_version":2,"schema_version":2,"id":"example.feature"}',
            encoding="utf-8",
        )

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn("contract_invalid_json", self.finding_codes(report))

    def test_aggregate_count_mismatch_fails_closed(self):
        report_json = self.read_json(self.gametest_report_path)
        report_json["execution"]["running_tests"] = 2
        report_json["execution"]["count_consistent"] = False
        self.write_json(self.gametest_report_path, report_json)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn("aggregate_count_mismatch", self.finding_codes(report))

    def test_incoherent_l4_success_fields_fail_closed(self):
        report_json = self.read_json(self.gametest_report_path)
        report_json["execution"]["required_failures"] = 1
        self.write_json(self.gametest_report_path, report_json)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn("aggregate_l4_not_passed", self.finding_codes(report))

    def test_source_digest_drift_fails_closed(self):
        self.source_path.write_text(
            self.source_path.read_text(encoding="utf-8")
            + "\n// changed after the L4 run\n",
            encoding="utf-8",
        )

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn("source_digest_drift", self.finding_codes(report))

    def test_retained_runtime_event_tamper_fails_digest_replay(self):
        report_json = self.read_json(self.gametest_report_path)
        execution = report_json["execution"]
        execution["runtime_event_raw_jsonl"] = (
            execution["runtime_event_raw_jsonl"].replace(
                '"event":"test_passed"',
                '"event":"test_failed"',
            )
        )
        self.write_json(self.gametest_report_path, report_json)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "event_stream_digest_mismatch",
            self.finding_codes(report),
        )

    def test_self_consistent_event_digest_cannot_hide_bad_sequence(self):
        report_json = self.read_json(self.gametest_report_path)
        execution = report_json["execution"]
        lines = execution["runtime_event_raw_jsonl"].splitlines()
        events = [json.loads(line) for line in lines]
        events[1]["sequence"] = 9
        raw = "".join(
            json.dumps(event, separators=(",", ":")) + "\n"
            for event in events
        ).encode("utf-8")
        replay = gametest_gate.validate_runtime_event_bytes(
            raw,
            nonce=execution["runtime_event_nonce"],
            expected_runtime_symbols={
                report_json["discovery"]["tests"][0]["runtime_name"]: SYMBOL
            },
        )
        execution["runtime_event_raw_jsonl"] = raw.decode("utf-8")
        execution["event_stream_sha256"] = replay.stream_sha256
        execution["canonical_runtime_events"] = list(
            replay.canonical_events
        )
        execution["canonical_event_stream_sha256"] = (
            replay.canonical_stream_sha256
        )
        self.write_json(self.gametest_report_path, report_json)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "runtime_event_replay_invalid",
            self.finding_codes(report),
        )

    def test_reporter_control_tamper_after_report_fails_closed(self):
        init_script = (
            self.project
            / ".agents"
            / "gates"
            / "runtime"
            / "gametest-reporter"
            / "inject.init.gradle"
        )
        init_script.write_text(
            init_script.read_text(encoding="utf-8")
            + "\n// changed after evidence capture\n",
            encoding="utf-8",
        )

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "reporter_control_drift",
            self.finding_codes(report),
        )

    def test_unlinked_gametest_acceptance_test_fails_closed(self):
        contract = self.contract()
        contract["acceptance"]["criteria"][0]["test_ids"] = []
        self.write_contract(contract)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "gametest_not_linked_to_criterion", self.finding_codes(report)
        )
        self.assertIn("no_traceability_mappings", self.finding_codes(report))

    def test_non_gametest_required_criterion_fails_closed(self):
        contract = self.contract()
        contract["acceptance"]["tests"].append(
            {
                "id": "static.client-boundary",
                "kind": "static",
                "required": True,
            }
        )
        contract["acceptance"]["criteria"].append(
            {
                "id": "criterion.client-boundary",
                "risk": "P0",
                "required": True,
                "statement": "Common code has no client imports.",
                "observation": "The static gate passes.",
                "test_ids": ["static.client-boundary"],
            }
        )
        self.write_contract(contract)

        report = self.run_gate()
        payload = report.to_dict()

        self.assertFalse(report.passed)
        self.assertIn(
            "required_criterion_uncovered", self.finding_codes(report)
        )
        self.assertFalse(
            payload["coverage"]["full_required_criteria_coverage"]
        )
        self.assertEqual(2, payload["coverage"]["required_criteria_total"])
        self.assertEqual(1, payload["coverage"]["required_criteria_uncovered"])
        self.assertEqual(
            "criterion.client-boundary",
            payload["coverage"]["uncovered"][0]["criterion_id"],
        )

    def test_mixed_required_provider_cannot_borrow_gametest_evidence(self):
        for kind in (
            "dedicated_server",
            "datagen",
            "performance",
            "static",
        ):
            with self.subTest(kind=kind):
                contract = self.contract()
                test_id = f"{kind}.required-proof"
                contract["acceptance"]["tests"].append(
                    {
                        "id": test_id,
                        "kind": kind,
                        "command": [
                            sys.executable,
                            "-c",
                            "raise SystemExit(0)",
                        ],
                        "covers": [
                            f"The required {kind} provider produces evidence."
                        ],
                        "required": True,
                        "expected": f"The {kind} provider passes.",
                        "timeout_seconds": 60,
                    }
                )
                contract["acceptance"]["criteria"][0]["test_ids"].append(
                    test_id
                )
                self.write_contract(contract)

                report = self.run_gate()
                coverage = report.criterion_coverage[0]

                self.assertFalse(report.passed)
                self.assertFalse(coverage["l4_covered"])
                self.assertEqual(
                    [test_id],
                    coverage["required_non_gametest_test_ids"],
                )
                self.assertIn(
                    "required_criterion_uncovered",
                    self.finding_codes(report),
                )

    def test_mixed_optional_non_gametest_does_not_block_l4_coverage(self):
        contract = self.contract()
        test_id = "static.optional-diagnostic"
        contract["acceptance"]["tests"].append(
            {
                "id": test_id,
                "kind": "static",
                "command": [
                    sys.executable,
                    "-c",
                    "raise SystemExit(0)",
                ],
                "covers": ["An optional diagnostic provides extra context."],
                "required": False,
                "expected": "The optional diagnostic reports its result.",
                "timeout_seconds": 60,
            }
        )
        contract["acceptance"]["criteria"][0]["test_ids"].append(test_id)
        self.write_contract(contract)

        report = self.run_gate()
        coverage = report.criterion_coverage[0]

        self.assertTrue(report.passed, report.findings)
        self.assertTrue(coverage["l4_covered"])
        self.assertEqual([], coverage["required_non_gametest_test_ids"])
        self.assertEqual(
            [test_id],
            coverage["ignored_optional_non_gametest_test_ids"],
        )

    def test_console_aggregate_cannot_satisfy_traceability(self):
        report_json = self.read_json(self.gametest_report_path)
        report_json["execution"]["evidence_level"] = "console_aggregate"
        report_json["execution"]["runtime_events_verified"] = False
        report_json["execution"]["executed_symbols"] = []
        report_json["execution"]["passed_symbols"] = []
        self.write_json(self.gametest_report_path, report_json)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "runtime_symbol_evidence_invalid", self.finding_codes(report)
        )

    def test_contract_schema_failure_is_not_ignored(self):
        contract = self.contract()
        contract.pop("design_source")
        self.write_contract(contract)

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "contract_gate_schema_required", self.finding_codes(report)
        )

    def test_bytecode_digest_drift_fails_closed(self):
        class_path = (
            self.project
            / "build"
            / "classes"
            / "java"
            / "main"
            / "com"
            / "example"
            / "FeatureGameTests.class"
        )
        class_path.write_bytes(b"changed-after-runtime-evidence")

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn("bytecode_digest_drift", self.finding_codes(report))

    def test_current_source_set_growth_invalidates_stale_report(self):
        second_source = (
            self.project
            / "src"
            / "main"
            / "java"
            / "com"
            / "example"
            / "SecondGameTests.java"
        )
        second_source.write_text(
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("example")
            public class SecondGameTests {
                @GameTest
                public static void second(GameTestHelper helper) {}
            }
            """,
            encoding="utf-8",
        )

        report = self.run_gate()

        self.assertFalse(report.passed)
        self.assertIn(
            "discovered_source_set_drift", self.finding_codes(report)
        )

    def test_cli_writes_machine_readable_report(self):
        output_path = self.project / "reports" / "traceability.json"

        code = traceability_gate.main(
            [
                str(self.contract_path),
                "--gametest-report",
                str(self.gametest_report_path),
                "--project-dir",
                str(self.project),
                "--json-report",
                str(output_path),
            ]
        )

        self.assertEqual(0, code)
        output = self.read_json(output_path)
        self.assertTrue(output["result"]["passed"])
        self.assertEqual(1, output["result"]["mapping_count"])
        self.assertEqual(SYMBOL, output["mappings"][0]["symbol"])


if __name__ == "__main__":
    unittest.main()
