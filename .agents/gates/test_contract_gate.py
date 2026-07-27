#!/usr/bin/env python3
"""Valid and invalid behavior tests for the Major feature contract gate."""
from __future__ import annotations

import contextlib
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


GATES_DIR = Path(__file__).resolve().parent
AGENTS_DIR = GATES_DIR.parent
sys.path.insert(0, str(GATES_DIR))

import contract_gate


def _automatic_test(test_id: str, kind: str, covers: str) -> Dict[str, Any]:
    return {
        "id": test_id,
        "kind": kind,
        "command": [
            sys.executable,
            "-c",
            "raise SystemExit(0)"
        ],
        "covers": [covers],
        "required": True,
        "expected": "The declared invariant holds.",
        "timeout_seconds": 60
    }


def valid_contract(
    contract_id: str = "test.feature",
    feature_dependencies: Optional[Iterable[str]] = None,
) -> Dict[str, Any]:
    resource_path = contract_id.replace(".", "_").replace("-", "_")
    tests: List[Dict[str, Any]] = [
        _automatic_test(
            "migration_v0_to_v1",
            "migration",
            "Version zero fixture migrates without data loss.",
        ),
        _automatic_test(
            "network_intent",
            "integration",
            "C2S validation, abuse rejection and S2C synchronization pass.",
        ),
        _automatic_test(
            "dedicated_server_boot",
            "dedicated_server",
            "Common code loads without client classes.",
        ),
        _automatic_test(
            "asset_validation",
            "static",
            "Registry and resource declarations reconcile.",
        ),
        _automatic_test(
            "steady_state_performance",
            "performance",
            "Representative load remains within the tick budget.",
        ),
    ]
    return {
        "$schema": "../../.agents/contracts/major-feature.schema.json",
        "id": contract_id,
        "version": 1,
        "status": "approved",
        "summary": "A bounded, server-authoritative vertical feature slice.",
        "server_authority": {
            "owner": "logical_server",
            "authoritative_state": [
                "FeatureState owns the validated progression integer."
            ],
            "client_input_policy": "intent_only",
            "invalid_input_policy": "reject_and_log"
        },
        "persistence": {
            "required": True,
            "scope": "level",
            "schema": {
                "version": 1,
                "format": "codec",
                "owner": "FeatureState",
                "fields": [
                    "progression: bounded integer, default zero"
                ]
            },
            "migration": {
                "strategy": "versioned_codec",
                "supported_from": [0],
                "fallback": "reject_incompatible",
                "test_ids": [
                    "migration_v0_to_v1"
                ]
            }
        },
        "network": {
            "required": True,
            "flows": [
                {
                    "id": "advance_intent",
                    "direction": "c2s",
                    "purpose": "Ask the logical server to validate one transition.",
                    "validation": [
                        "Sender exists and has permission.",
                        "Requested transition is legal from current server state.",
                        "Payload contains no unbounded collection."
                    ],
                    "rate_limit": {
                        "strategy": "token_bucket",
                        "max_per_second": 4,
                        "burst": 8,
                        "key": "player"
                    },
                    "max_payload_bytes": 256,
                    "test_ids": [
                        "network_intent"
                    ]
                },
                {
                    "id": "state_sync",
                    "direction": "s2c",
                    "purpose": "Expose the minimal read-only client view.",
                    "validation": [
                        "Target client still tracks the owning level."
                    ],
                    "rate_limit": {
                        "strategy": "coalesced",
                        "max_per_second": 10,
                        "burst": 1,
                        "key": "player"
                    },
                    "max_payload_bytes": 512,
                    "test_ids": [
                        "network_intent"
                    ]
                }
            ]
        },
        "client_boundary": {
            "common_code_client_imports": "forbidden",
            "client_only_packages": [
                "com.acme.test.client"
            ],
            "visual_state": "synced_server_state",
            "dedicated_server_fallback": (
                "The feature remains functional without visual presentation."
            ),
            "dedicated_server_test_id": "dedicated_server_boot"
        },
        "registries": {
            "entries": [
                {
                    "registry": "item",
                    "ids": [
                        f"test:{resource_path}"
                    ],
                    "lifecycle": "static"
                }
            ],
            "tags": [
                "c:feature_contract_test_items"
            ]
        },
        "assets": {
            "generated": [
                "Item model and en_us language entry"
            ],
            "manual": [],
            "locales": [
                "en_us",
                "zh_cn"
            ],
            "license_review": "not_applicable",
            "validation_test_ids": [
                "asset_validation"
            ]
        },
        "performance": {
            "budgets": [
                {
                    "id": "steady_state_tick",
                    "metric": "server_tick_time",
                    "scope": "One hundred active feature owners",
                    "limit": 0.25,
                    "unit": "ms_per_tick",
                    "measurement": (
                        "Warm up for 200 ticks, then record p99 across 1200 ticks."
                    ),
                    "test_id": "steady_state_performance"
                }
            ],
            "hot_paths": [
                "Server transition event; no unconditional per-object tick."
            ],
            "measurement_environment": (
                "Java 21, fixed seed, 4 GiB heap and stable fixture load."
            )
        },
        "dependencies": {
            "features": list(feature_dependencies or []),
            "mods": [
                {
                    "id": "neoforge",
                    "requirement": "required",
                    "version_range": "[21.1,21.2)",
                    "fallback": "Feature is disabled on unsupported loader versions."
                }
            ]
        },
        "acceptance": {
            "tests": tests,
            "manual_checks": [
                {
                    "id": "playable_loop",
                    "steps": [
                        "Join a dedicated server with two players.",
                        "Submit valid and invalid transition intents.",
                        "Save, restart and reconnect both players."
                    ],
                    "expected": (
                        "Server state persists and both clients converge without "
                        "accepting invalid input."
                    )
                }
            ],
            "non_goals": [
                "This version does not introduce a reusable cross-mod framework."
            ]
        }
    }


class ContractGateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir_handle = tempfile.TemporaryDirectory(
            prefix="contract_gate_"
        )
        self.temp_dir = Path(self.temp_dir_handle.name)

    def tearDown(self) -> None:
        self.temp_dir_handle.cleanup()

    def write_contract(
        self,
        name: str,
        data: Dict[str, Any],
    ) -> Path:
        path = self.temp_dir / name
        path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return path

    @staticmethod
    def finding_codes(report: contract_gate.GateReport) -> set[str]:
        return {item.code for item in report.findings}

    def test_valid_single_file_and_directory_pass(self) -> None:
        path = self.write_contract("feature.contract.json", valid_contract())

        single = contract_gate.run_gate([path], require=True)
        directory = contract_gate.run_gate([self.temp_dir], require=True)

        self.assertTrue(single.passed, single.findings)
        self.assertTrue(directory.passed, directory.findings)
        self.assertEqual(1, len(single.contract_files))
        self.assertEqual(1, len(directory.contract_files))

    def test_default_contract_directory_is_host_documentation(self) -> None:
        expected = contract_gate.PROJECT_DIR / "docs" / "features"

        self.assertEqual(expected, contract_gate.DEFAULT_CONTRACT_DIRECTORY)
        self.assertNotIn(
            ".agents",
            str(
                contract_gate.DEFAULT_CONTRACT_DIRECTORY.relative_to(
                    contract_gate.PROJECT_DIR
                )
            ),
        )

    def test_required_type_enum_and_placeholder_fail(self) -> None:
        invalid = valid_contract()
        del invalid["status"]
        invalid["version"] = "one"
        invalid["summary"] = "TODO: decide the feature result"
        invalid["server_authority"]["owner"] = "the_client"
        path = self.write_contract("invalid.contract.json", invalid)

        report = contract_gate.run_gate([path], require=True)
        codes = self.finding_codes(report)

        self.assertFalse(report.passed)
        self.assertIn("schema_required", codes)
        self.assertIn("schema_type", codes)
        self.assertIn("schema_enum", codes)
        self.assertIn("unresolved_placeholder", codes)

    def test_duplicate_contract_ids_and_dependency_cycle_fail(self) -> None:
        self.write_contract(
            "a.contract.json",
            valid_contract("test.a", ["test.b"]),
        )
        self.write_contract(
            "b.contract.json",
            valid_contract("test.b", ["test.a"]),
        )
        self.write_contract(
            "duplicate_a.contract.json",
            valid_contract("test.a"),
        )

        report = contract_gate.run_gate([self.temp_dir], require=True)
        codes = self.finding_codes(report)

        self.assertFalse(report.passed)
        self.assertIn("duplicate_contract_id", codes)
        self.assertIn("dependency_cycle", codes)

    def test_require_rejects_empty_directory(self) -> None:
        optional = contract_gate.run_gate([self.temp_dir], require=False)
        required = contract_gate.run_gate([self.temp_dir], require=True)

        self.assertTrue(optional.passed)
        self.assertFalse(required.passed)
        self.assertIn("no_contracts", self.finding_codes(required))

    def test_executable_declarations_and_test_references_fail_closed(self) -> None:
        invalid = valid_contract()
        for test in invalid["acceptance"]["tests"]:
            test["required"] = False
        invalid["acceptance"]["tests"][0]["command"] = "python migration_test.py"
        invalid["acceptance"]["tests"][1]["command"].append("&&")
        invalid["persistence"]["migration"]["test_ids"] = [
            "dedicated_server_boot"
        ]
        invalid["performance"]["budgets"][0]["test_id"] = "missing_budget_test"
        path = self.write_contract("bad_tests.contract.json", invalid)

        report = contract_gate.run_gate([path], require=True)
        codes = self.finding_codes(report)

        self.assertFalse(report.passed)
        self.assertIn("schema_type", codes)
        self.assertIn("shell_syntax_in_test_command", codes)
        self.assertIn("no_required_executable_test", codes)
        self.assertIn("wrong_test_kind", codes)
        self.assertIn("unknown_test_reference", codes)

    def test_json_report_contains_machine_readable_result(self) -> None:
        contract_path = self.write_contract(
            "feature.contract.json",
            valid_contract(),
        )
        report_path = self.temp_dir / "reports" / "contracts.json"
        output = io.StringIO()

        with contextlib.redirect_stdout(output):
            exit_code = contract_gate.main([
                str(contract_path),
                "--require",
                "--json-report",
                str(report_path),
            ])

        payload = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(0, exit_code)
        self.assertTrue(payload["passed"])
        self.assertEqual(1, payload["contracts_checked"])
        self.assertEqual("test.feature", payload["contracts"][0]["id"])
        self.assertIn("JSON report:", output.getvalue())

    def test_scaffold_is_valid_json_but_cannot_ship_unresolved(self) -> None:
        scaffold = (
            AGENTS_DIR
            / "scaffolds"
            / "major_feature"
            / "major-feature.contract.json"
        )
        parsed = json.loads(scaffold.read_text(encoding="utf-8"))
        self.assertIsInstance(parsed, dict)

        report = contract_gate.run_gate([scaffold], require=True)
        self.assertFalse(report.passed)
        self.assertIn(
            "unresolved_placeholder",
            self.finding_codes(report),
        )

    def test_overlapping_inputs_are_deduplicated(self) -> None:
        path = self.write_contract("feature.contract.json", valid_contract())

        report = contract_gate.run_gate(
            [self.temp_dir, path, self.temp_dir],
            require=True,
        )

        self.assertTrue(report.passed, report.findings)
        self.assertEqual(1, len(report.contract_files))


if __name__ == "__main__":
    unittest.main()
