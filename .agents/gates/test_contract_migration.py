#!/usr/bin/env python3
"""Lossless and fail-closed tests for the v1 to v2 contract migrator."""
from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict


GATES_DIR = Path(__file__).resolve().parent
AGENTS_DIR = GATES_DIR.parent
CONTRACTS_DIR = AGENTS_DIR / "contracts"
sys.path.insert(0, str(GATES_DIR))
sys.path.insert(0, str(CONTRACTS_DIR))

import contract_gate
import migrate_v1_to_v2
from test_contract_gate import valid_contract


class ContractMigrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_handle = tempfile.TemporaryDirectory(
            prefix="contract_migration_"
        )
        self.temp_dir = Path(self.temp_handle.name)
        self.source = self.temp_dir / "legacy.contract.json"
        self.source.write_text(
            json.dumps(valid_contract(), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp_handle.cleanup()

    def migrate(self, name: str = "modern.contract.json") -> tuple[Path, Dict[str, Any]]:
        target = self.temp_dir / name
        report = migrate_v1_to_v2.migrate_file(self.source, target)
        return target, report

    def test_migration_is_deterministic_and_does_not_touch_input(self) -> None:
        before = self.source.read_bytes()
        first, first_report = self.migrate("first.json")
        second, second_report = self.migrate("second.json")

        self.assertEqual(before, self.source.read_bytes())
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(
            first_report["target_sha256"],
            second_report["target_sha256"],
        )
        self.assertEqual(
            hashlib.sha256(before).hexdigest(),
            first_report["source_sha256"],
        )
        self.assertEqual(
            first_report["changed_paths"],
            second_report["changed_paths"],
        )

    def test_all_v1_behavior_declarations_and_covers_are_preserved(self) -> None:
        source = contract_gate.load_json_strict(self.source)
        target_path, report = self.migrate()
        target = contract_gate.load_json_strict(target_path)

        for key, value in source.items():
            if key in {"$schema", "status", "acceptance"}:
                continue
            self.assertEqual(value, target[key], key)
        for key, value in source["acceptance"].items():
            self.assertEqual(value, target["acceptance"][key], key)

        expected_covers = {
            cover
            for test in source["acceptance"]["tests"]
            for cover in test["covers"]
        }
        criteria = target["acceptance"]["criteria"]
        self.assertEqual(
            expected_covers,
            {criterion["statement"] for criterion in criteria},
        )
        self.assertEqual(
            len(criteria),
            len({criterion["id"] for criterion in criteria}),
        )
        self.assertTrue(all(
            criterion["id"] == migrate_v1_to_v2._criterion_id(
                criterion["statement"]
            )
            for criterion in criteria
        ))
        self.assertTrue(report["review_required"])
        observation_reviews = [
            item
            for item in report["review_required"]
            if item["id"].startswith("review.observation.")
        ]
        self.assertEqual(len(criteria), len(observation_reviews))
        self.assertIn("$.acceptance.criteria", report["changed_paths"])
        self.assertTrue(report["diff"].startswith("--- v1-contract"))

    def test_migrated_contract_is_a_reviewable_draft(self) -> None:
        target_path, _ = self.migrate()
        target = contract_gate.load_json_strict(target_path)

        self.assertEqual(2, target["schema_version"])
        self.assertEqual("draft", target["status"])
        self.assertTrue(target["review_required"])
        self.assertTrue(all(
            criterion["risk"] == "unclassified"
            for criterion in target["acceptance"]["criteria"]
        ))
        draft_report = contract_gate.run_gate([target_path], require=True)
        self.assertTrue(draft_report.passed, draft_report.findings)

        target["status"] = "approved"
        target_path.unlink()
        target_path.write_text(
            json.dumps(target, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        incomplete = contract_gate.run_gate([target_path], require=True)
        incomplete_codes = {
            finding.code for finding in incomplete.findings
        }
        self.assertIn(
            "unclassified_criterion_after_draft",
            incomplete_codes,
        )
        self.assertIn("migration_review_incomplete", incomplete_codes)

        for criterion in target["acceptance"]["criteria"]:
            criterion["risk"] = "P1"
        target["review_required"] = []
        target_path.write_text(
            json.dumps(target, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        reviewed = contract_gate.run_gate([target_path], require=True)
        self.assertTrue(reviewed.passed, reviewed.findings)

    def test_refuses_overwrite_and_in_place_migration(self) -> None:
        existing = self.temp_dir / "existing.json"
        existing.write_text("do not replace", encoding="utf-8")

        with self.assertRaises(migrate_v1_to_v2.MigrationError):
            migrate_v1_to_v2.migrate_file(self.source, existing)
        self.assertEqual("do not replace", existing.read_text(encoding="utf-8"))

        with self.assertRaises(migrate_v1_to_v2.MigrationError):
            migrate_v1_to_v2.migrate_file(self.source, self.source)

    def test_rejects_duplicate_keys_unknown_version_and_v2_input(self) -> None:
        duplicate = self.temp_dir / "duplicate.json"
        duplicate.write_text(
            '{"id":"first","id":"second"}\n',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            migrate_v1_to_v2.MigrationError,
            "duplicate JSON object key",
        ):
            migrate_v1_to_v2.migrate_file(
                duplicate,
                self.temp_dir / "duplicate-out.json",
            )

        unknown_data = valid_contract()
        unknown_data["schema_version"] = 99
        unknown = self.temp_dir / "unknown.json"
        unknown.write_text(
            json.dumps(unknown_data, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            migrate_v1_to_v2.MigrationError,
            "unsupported source schema_version",
        ):
            migrate_v1_to_v2.migrate_file(
                unknown,
                self.temp_dir / "unknown-out.json",
            )

        v2_path, _ = self.migrate("already-v2.json")
        with self.assertRaisesRegex(
            migrate_v1_to_v2.MigrationError,
            "already a v2 contract",
        ):
            migrate_v1_to_v2.migrate_file(
                v2_path,
                self.temp_dir / "v3.json",
            )

    def test_optional_diff_and_json_report_are_created_exclusively(self) -> None:
        target = self.temp_dir / "modern.json"
        diff_path = self.temp_dir / "modern.diff"
        report_path = self.temp_dir / "modern.report.json"

        report = migrate_v1_to_v2.migrate_file(
            self.source,
            target,
            diff_path=diff_path,
            report_path=report_path,
        )

        self.assertEqual(report["diff"], diff_path.read_text(encoding="utf-8"))
        payload = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(1, payload["source_schema_version"])
        self.assertEqual(2, payload["target_schema_version"])
        self.assertNotIn("diff", payload)


if __name__ == "__main__":
    unittest.main()
