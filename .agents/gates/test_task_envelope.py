#!/usr/bin/env python3
"""Tests for the deliberately graph-free provisional Task Envelope."""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


STUDIO_DIR = Path(__file__).resolve().parents[1] / "studio"
sys.path.insert(0, str(STUDIO_DIR))

import execution_policy
import task_envelope


class TestTaskEnvelope(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="task_envelope_"))
        (self.root / "docs" / "design").mkdir(parents=True)
        self.design = self.root / "docs" / "design" / "feature.md"
        self.design.write_text("approved design\n", encoding="utf-8")
        design_digest = hashlib.sha256(self.design.read_bytes()).hexdigest()

        self.manifest_path = self.root / "docs" / "studio.json"
        self.manifest_path.parent.mkdir(parents=True, exist_ok=True)
        self.manifest = {
            "schema_version": 1,
            "project_id": "fixture",
            "versions": {
                "minecraft": "1.21.1",
                "neoforge": "21.1.234",
                "java": "21",
                "gradle": "8.10",
            },
            "design_sources": [
                {
                    "path": "docs/design/feature.md",
                    "sha256": design_digest,
                }
            ],
            "approved_assets": [],
            "enabled_packs": [],
        }
        self._write_json(self.manifest_path, self.manifest)

        self.contract_path = self.root / "docs" / "feature.v2.json"
        self.contract = {
            "schema_version": 2,
            "design_source": {
                "path": "docs/design/feature.md",
                "revision": "approved-1",
                "sha256": design_digest,
            },
            "review_required": [],
            "id": "fixture.behavior",
            "version": 1,
            "status": "approved",
            "dependencies": {"features": [], "mods": []},
            "acceptance": {
                "criteria": [
                    {
                        "id": "behavior.works",
                        "risk": "P0",
                        "required": True,
                        "test_ids": ["behavior_test"],
                    }
                ],
                "tests": [
                    {
                        "id": "behavior_test",
                        "kind": "gametest",
                        "test_ref": "example.FeatureTests#works",
                        "command": ["python", ".agents/gates/gametest_gate.py"],
                        "required": True,
                        "timeout_seconds": 120,
                    }
                ],
            },
        }
        self._write_json(self.contract_path, self.contract)

        self.policy_document = {
            "schema_version": 1,
            "stability": "provisional",
            "policy_id": "major.strict",
            "backend": "bubblewrap",
            "writable_paths": ["build", "run"],
            "timeout_seconds": 900,
            "required_capabilities": sorted(
                execution_policy.CORE_CAPABILITIES
            ),
        }
        policy_path = self.root / "policy.json"
        self._write_json(policy_path, self.policy_document)
        self.policy = execution_policy.load_policy(policy_path)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    @staticmethod
    def _write_json(path: Path, value: object) -> None:
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    def _build(self):
        return task_envelope.build_task_envelope(
            workspace=self.root,
            contract_path=self.contract_path,
            contract=self.contract,
            manifest_path=self.manifest_path,
            manifest=self.manifest,
            policy=self.policy,
            expected_policy_digest=self.policy.digest,
        )

    def test_build_is_deterministic_and_has_no_graph_surface(self):
        first = self._build()
        second = self._build()
        self.assertEqual(first, second)
        self.assertEqual("single_task_envelope", first["kind"])
        self.assertNotIn("nodes", first)
        self.assertNotIn("edges", first)
        self.assertNotIn("work_graph", first)
        self.assertEqual(["build", "run"], first["execution_policy"]["writable_paths"])
        self.assertEqual("P0", first["risk"])
        self.assertEqual(64, len(first["envelope_digest"]))
        self.assertEqual(
            64,
            len(first["frozen_inputs"]["digest"]),
        )
        self.assertEqual(
            first["frozen_inputs"]["digest"],
            task_envelope.verify_frozen_input_snapshot(
                first["frozen_inputs"],
                workspace=self.root,
            ),
        )

    def test_supplied_mappings_must_match_their_strict_json_files(self):
        other_contract = dict(self.contract)
        other_contract["status"] = "implementing"
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "contract mapping does not match",
        ):
            task_envelope.build_task_envelope(
                workspace=self.root,
                contract_path=self.contract_path,
                contract=other_contract,
                manifest_path=self.manifest_path,
                manifest=self.manifest,
                policy=self.policy,
                expected_policy_digest=self.policy.digest,
            )

        other_manifest = dict(self.manifest)
        other_manifest["project_id"] = "different-project"
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "manifest mapping does not match",
        ):
            task_envelope.build_task_envelope(
                workspace=self.root,
                contract_path=self.contract_path,
                contract=self.contract,
                manifest_path=self.manifest_path,
                manifest=other_manifest,
                policy=self.policy,
                expected_policy_digest=self.policy.digest,
            )

    def test_frozen_inputs_detect_post_run_drift(self):
        envelope = self._build()
        self.design.write_text("post-run mutation\n", encoding="utf-8")
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "digest drift",
        ):
            task_envelope.verify_frozen_input_snapshot(
                envelope["frozen_inputs"],
                workspace=self.root,
            )

    def test_writable_paths_cannot_cover_any_frozen_input(self):
        docs_policy_document = dict(self.policy_document)
        docs_policy_document["writable_paths"] = ["docs"]
        docs_policy_path = self.root / "docs-policy.json"
        self._write_json(docs_policy_path, docs_policy_document)
        docs_policy = execution_policy.load_policy(docs_policy_path)
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "overlaps frozen input",
        ):
            task_envelope.build_task_envelope(
                workspace=self.root,
                contract_path=self.contract_path,
                contract=self.contract,
                manifest_path=self.manifest_path,
                manifest=self.manifest,
                policy=docs_policy,
                expected_policy_digest=docs_policy.digest,
            )

        asset = self.root / "assets" / "approved.bin"
        asset.parent.mkdir()
        asset.write_bytes(b"approved asset")
        self.manifest["approved_assets"].append(
            {
                "path": "assets/approved.bin",
                "sha256": hashlib.sha256(asset.read_bytes()).hexdigest(),
            }
        )
        self._write_json(self.manifest_path, self.manifest)
        asset_policy_document = dict(self.policy_document)
        asset_policy_document["writable_paths"] = ["assets"]
        asset_policy_path = self.root / "asset-policy.json"
        self._write_json(asset_policy_path, asset_policy_document)
        asset_policy = execution_policy.load_policy(asset_policy_path)
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "overlaps frozen input.*assets/approved.bin",
        ):
            task_envelope.build_task_envelope(
                workspace=self.root,
                contract_path=self.contract_path,
                contract=self.contract,
                manifest_path=self.manifest_path,
                manifest=self.manifest,
                policy=asset_policy,
                expected_policy_digest=asset_policy.digest,
            )

    def test_draft_or_unresolved_review_is_blocked(self):
        self.contract["status"] = "draft"
        self._write_json(self.contract_path, self.contract)
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "not eligible",
        ):
            self._build()

        self.contract["status"] = "approved"
        self.contract["review_required"] = [
            {"id": "decision", "path": "$.x", "reason": "missing"}
        ]
        self._write_json(self.contract_path, self.contract)
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "unresolved",
        ):
            self._build()

    def test_design_drift_and_manifest_mismatch_are_blocked(self):
        self.design.write_text("changed design\n", encoding="utf-8")
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "digest drift",
        ):
            self._build()

        self.design.write_text("approved design\n", encoding="utf-8")
        self.manifest["design_sources"][0]["sha256"] = "0" * 64
        self._write_json(self.manifest_path, self.manifest)
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "digest drift",
        ):
            self._build()

    def test_missing_test_reference_is_blocked(self):
        self.contract["acceptance"]["criteria"][0]["test_ids"] = ["missing"]
        self._write_json(self.contract_path, self.contract)
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "references missing tests",
        ):
            self._build()

    def test_envelope_cannot_expand_frozen_policy(self):
        envelope = self._build()
        envelope["execution_policy"]["writable_paths"].append("src")
        rebuilt = self._build()
        self.assertEqual(
            ["build", "run"],
            rebuilt["execution_policy"]["writable_paths"],
        )
        self.assertNotEqual(
            envelope["envelope_digest"],
            task_envelope.sha256_json(
                {
                    key: value
                    for key, value in envelope.items()
                    if key != "envelope_digest"
                }
            ),
        )

    def test_envelope_rejects_policy_digest_not_frozen_by_control_plane(self):
        with self.assertRaisesRegex(
            task_envelope.TaskEnvelopeError,
            "externally frozen digest",
        ):
            task_envelope.build_task_envelope(
                workspace=self.root,
                contract_path=self.contract_path,
                contract=self.contract,
                manifest_path=self.manifest_path,
                manifest=self.manifest,
                policy=self.policy,
                expected_policy_digest="f" * 64,
            )


if __name__ == "__main__":
    unittest.main()
