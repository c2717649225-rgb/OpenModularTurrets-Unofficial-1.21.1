#!/usr/bin/env python3
"""Version dispatch and semantic tests for Major Feature Contract v2."""
from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict


GATES_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(GATES_DIR))

import contract_gate
from test_contract_gate import valid_contract


def valid_v2_contract(contract_id: str = "test.feature.v2") -> Dict[str, Any]:
    contract = valid_contract(contract_id)
    contract["$schema"] = (
        "../../.agents/contracts/major-feature-v2.schema.json"
    )
    contract["schema_version"] = 2
    contract["design_source"] = {
        "path": "docs/design/feature-v2.md",
        "revision": "2",
        "sha256": "c" * 64,
    }
    contract["review_required"] = []
    contract["acceptance"]["criteria"] = [
        {
            "id": "persistence.migration",
            "risk": "P0",
            "required": True,
            "statement": "Version zero migrates without data loss.",
            "observation": "The migrated fixture preserves every declared field.",
            "test_ids": ["migration_v0_to_v1"],
        },
        {
            "id": "network.authority",
            "risk": "P0",
            "required": True,
            "statement": "Only the logical server changes authoritative state.",
            "observation": "Invalid client intents are rejected.",
            "test_ids": ["network_intent"],
        },
        {
            "id": "runtime.dedicated_server",
            "risk": "P0",
            "required": True,
            "statement": "Common code loads on a dedicated server.",
            "observation": "The server reaches Done without client classes.",
            "test_ids": ["dedicated_server_boot"],
        },
        {
            "id": "assets.reconcile",
            "risk": "P1",
            "required": True,
            "statement": "Declared resources reconcile.",
            "observation": "The asset gate reports no missing resources.",
            "test_ids": ["asset_validation"],
        },
        {
            "id": "performance.steady_state",
            "risk": "P1",
            "required": True,
            "statement": "Steady-state work stays inside its budget.",
            "observation": "The measured p99 is no greater than the limit.",
            "test_ids": ["steady_state_performance"],
        },
    ]
    return contract


class ContractV2Tests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_handle = tempfile.TemporaryDirectory(prefix="contract_v2_")
        self.temp_dir = Path(self.temp_handle.name)

    def tearDown(self) -> None:
        self.temp_handle.cleanup()

    def write(self, name: str, data: Dict[str, Any]) -> Path:
        path = self.temp_dir / name
        path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return path

    @staticmethod
    def codes(report: contract_gate.GateReport) -> set[str]:
        return {finding.code for finding in report.findings}

    def test_auto_dispatch_accepts_v2_and_reports_schema_version(self) -> None:
        path = self.write("feature-v2.json", valid_v2_contract())

        report = contract_gate.run_gate([path], require=True)

        self.assertTrue(report.passed, report.findings)
        self.assertTrue(report.automatic_schema_dispatch)
        self.assertEqual(2, report.documents[0].schema_version)
        self.assertEqual(2, report.as_dict()["contracts"][0]["schema_version"])
        self.assertEqual("auto", report.as_dict()["schema"])

    def test_mixed_v1_v2_directory_keeps_global_checks(self) -> None:
        self.write("legacy.json", valid_contract("test.legacy"))
        self.write("modern.json", valid_v2_contract("test.modern"))

        report = contract_gate.run_gate([self.temp_dir], require=True)

        self.assertTrue(report.passed, report.findings)
        self.assertEqual({1, 2}, {
            document.schema_version for document in report.documents
        })

    def test_unknown_schema_version_fails_closed(self) -> None:
        contract = valid_v2_contract()
        contract["schema_version"] = 999
        path = self.write("unknown.json", contract)

        report = contract_gate.run_gate([path], require=True)
        override_report = contract_gate.run_gate(
            [path],
            require=True,
            schema_path=contract_gate.V2_SCHEMA,
        )

        self.assertFalse(report.passed)
        self.assertIn("unknown_schema_version", self.codes(report))
        self.assertEqual([], report.documents)
        self.assertFalse(override_report.passed)
        self.assertIn(
            "unknown_schema_version",
            self.codes(override_report),
        )
        self.assertEqual([], override_report.documents)

    def test_v2_dispatch_requires_v2_core_fields(self) -> None:
        contract = valid_contract()
        contract["schema_version"] = 2
        path = self.write("not-really-v2.json", contract)

        report = contract_gate.run_gate([path], require=True)

        self.assertFalse(report.passed)
        self.assertIn("schema_required", self.codes(report))

    def test_design_source_path_must_be_canonical_and_relative(self) -> None:
        for bad_path in (
            "/tmp/design.md",
            "C:/design.md",
            "../design.md",
            "docs/../design.md",
            "./docs/design.md",
            "docs//design.md",
            "docs/design/",
            r"docs\design.md",
        ):
            with self.subTest(path=bad_path):
                contract = valid_v2_contract()
                contract["design_source"]["path"] = bad_path
                path = self.write("bad-design-path.json", contract)
                report = contract_gate.run_gate([path], require=True)
                self.assertFalse(report.passed)
                self.assertIn("schema_pattern", self.codes(report))

    def test_criteria_ids_and_test_references_fail_closed(self) -> None:
        contract = valid_v2_contract()
        duplicate = copy.deepcopy(contract["acceptance"]["criteria"][0])
        contract["acceptance"]["criteria"].append(duplicate)
        contract["acceptance"]["criteria"][1]["test_ids"] = ["missing_test"]
        path = self.write("bad-criteria.json", contract)

        report = contract_gate.run_gate([path], require=True)
        codes = self.codes(report)

        self.assertFalse(report.passed)
        self.assertIn("duplicate_acceptance_criterion_id", codes)
        self.assertIn("unknown_criterion_test_reference", codes)
        self.assertIn(
            "required_criterion_without_required_executable_test",
            codes,
        )

    def test_required_criterion_needs_required_executable_test(self) -> None:
        contract = valid_v2_contract()
        for test in contract["acceptance"]["tests"]:
            if test["id"] == "network_intent":
                test["required"] = False
        path = self.write("optional-test.json", contract)

        report = contract_gate.run_gate([path], require=True)

        self.assertFalse(report.passed)
        self.assertIn(
            "required_criterion_without_required_executable_test",
            self.codes(report),
        )

    def test_draft_can_carry_review_but_approved_contract_cannot(self) -> None:
        contract = valid_v2_contract()
        contract["status"] = "draft"
        contract["acceptance"]["criteria"][0]["risk"] = "unclassified"
        contract["review_required"] = [
            {
                "id": "review.risk",
                "path": "$.acceptance.criteria[0].risk",
                "reason": "Classify after design review.",
            }
        ]
        draft_path = self.write("draft.json", contract)
        self.assertTrue(
            contract_gate.run_gate([draft_path], require=True).passed
        )

        contract["status"] = "approved"
        approved_path = self.write("approved.json", contract)
        codes = self.codes(contract_gate.run_gate(
            [approved_path],
            require=True,
        ))
        self.assertIn("unclassified_criterion_after_draft", codes)
        self.assertIn("migration_review_incomplete", codes)

    def test_v2_gametest_requires_stable_test_ref(self) -> None:
        contract = valid_v2_contract()
        test = contract["acceptance"]["tests"][1]
        test["kind"] = "gametest"
        path = self.write("missing-ref.json", contract)

        missing = contract_gate.run_gate([path], require=True)
        self.assertIn("gametest_ref_missing", self.codes(missing))

        test["test_ref"] = "com.acme.FeatureGameTests#serverAuthority"
        valid_path = self.write("with-ref.json", contract)
        valid = contract_gate.run_gate([valid_path], require=True)
        self.assertTrue(valid.passed, valid.findings)

    def test_explicit_schema_override_preserves_legacy_behavior(self) -> None:
        path = self.write("legacy.json", valid_contract())

        report = contract_gate.run_gate(
            [path],
            require=True,
            schema_path=contract_gate.DEFAULT_SCHEMA,
        )

        self.assertTrue(report.passed, report.findings)
        self.assertFalse(report.automatic_schema_dispatch)

    def test_explicit_v2_schema_still_runs_v2_semantics(self) -> None:
        contract = valid_v2_contract()
        contract["acceptance"]["criteria"].append(copy.deepcopy(
            contract["acceptance"]["criteria"][0]
        ))
        path = self.write("duplicate-criterion.json", contract)

        report = contract_gate.run_gate(
            [path],
            require=True,
            schema_path=contract_gate.V2_SCHEMA,
        )

        self.assertFalse(report.passed)
        self.assertFalse(report.automatic_schema_dispatch)
        self.assertEqual(2, report.documents[0].schema_version)
        self.assertIn(
            "duplicate_acceptance_criterion_id",
            self.codes(report),
        )

    def test_duplicate_json_keys_are_rejected_before_dispatch(self) -> None:
        path = self.temp_dir / "duplicate-key.json"
        path.write_text(
            '{"schema_version":2,"schema_version":1}\n',
            encoding="utf-8",
        )

        report = contract_gate.run_gate([path], require=True)

        self.assertFalse(report.passed)
        self.assertIn("invalid_json", self.codes(report))
        self.assertEqual([], report.documents)

    def test_duplicate_review_ids_and_deprecated_unresolved_review_fail(self) -> None:
        contract = valid_v2_contract()
        contract["status"] = "deprecated"
        contract["review_required"] = [
            {
                "id": "review.risk",
                "path": "$.acceptance.criteria[0].risk",
                "reason": "Classify the criterion.",
            },
            {
                "id": "review.risk",
                "path": "$.acceptance.criteria[1].risk",
                "reason": "Classify another criterion.",
            },
        ]
        path = self.write("deprecated-review.json", contract)

        report = contract_gate.run_gate([path], require=True)
        codes = self.codes(report)

        self.assertFalse(report.passed)
        self.assertIn("duplicate_review_required_id", codes)
        self.assertIn("migration_review_incomplete", codes)


if __name__ == "__main__":
    unittest.main()
