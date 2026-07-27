#!/usr/bin/env python3
"""Standard-library tests for acceptance-to-GameTest traceability."""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


GATES_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(GATES_DIR))

import gametest_gate
import traceability_gate


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
        return {
            "schema_version": 2,
            "id": "example.feature",
            "acceptance": {
                "tests": [
                    {
                        "id": "gametest.feature-works",
                        "kind": "gametest",
                        "test_ref": SYMBOL,
                        "required": True,
                    }
                ],
                "criteria": [
                    {
                        "id": "criterion.feature-works",
                        "risk": "P0",
                        "required": True,
                        "statement": "The feature works.",
                        "observation": "The GameTest passes.",
                        "test_ids": ["gametest.feature-works"],
                    }
                ],
            },
        }

    def write_contract(self, value=None) -> None:
        self.write_json(
            self.contract_path, self.contract() if value is None else value
        )

    def write_gametest_report(self) -> None:
        discovery = gametest_gate.discover_gametests(self.project)
        self.assertEqual([], discovery.errors)
        execution = gametest_gate.parse_gametest_output(
            ONE_TEST_PASS_OUTPUT,
            command=["gradlew", "runGameTestServer"],
            returncode=0,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1,
            discovered_tests=discovery.count,
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

    def test_maps_criterion_to_source_backed_aggregate_l4_pass(self):
        report = self.run_gate()

        self.assertTrue(report.passed)
        self.assertEqual([], report.findings)
        self.assertEqual(1, len(report.mappings))
        mapping = report.mappings[0]
        self.assertEqual("criterion.feature-works", mapping.criterion_id)
        self.assertEqual("gametest.feature-works", mapping.acceptance_test_id)
        self.assertEqual(SYMBOL, mapping.symbol)
        self.assertEqual("aggregate_set", mapping.evidence_level)
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
        contract["acceptance"]["tests"][0]["test_ref"] = (
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

    def test_non_gametest_required_criterion_is_explicitly_uncovered(self):
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

        self.assertTrue(report.passed)
        self.assertFalse(
            payload["coverage"]["full_required_criteria_coverage"]
        )
        self.assertEqual(2, payload["coverage"]["required_criteria_total"])
        self.assertEqual(1, payload["coverage"]["required_criteria_uncovered"])
        self.assertEqual(
            "criterion.client-boundary",
            payload["coverage"]["uncovered"][0]["criterion_id"],
        )

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
