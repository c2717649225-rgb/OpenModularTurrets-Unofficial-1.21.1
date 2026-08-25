#!/usr/bin/env python3
"""Schema tests for the provisional Studio Manifest core."""
from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path
from typing import Any, Dict


GATES_DIR = Path(__file__).resolve().parent
AGENTS_DIR = GATES_DIR.parent
sys.path.insert(0, str(GATES_DIR))

import contract_gate


SCHEMA_PATH = AGENTS_DIR / "studio" / "schemas" / "mod-studio.schema.json"


def valid_manifest() -> Dict[str, Any]:
    return {
        "$schema": "../../.agents/studio/schemas/mod-studio.schema.json",
        "schema_version": 1,
        "project_id": "example.flagship",
        "versions": {
            "minecraft": "1.21.1",
            "neoforge": "21.1.207",
            "java": "21",
            "gradle": "8.10.2",
        },
        "design_sources": [
            {
                "path": "docs/design/v1.md",
                "sha256": "a" * 64,
            }
        ],
        "approved_assets": [
            {
                "path": "src/main/resources/assets/example/textures/item/core.png",
                "sha256": "b" * 64,
            }
        ],
        "enabled_packs": [
            {
                "id": "evidence-core",
                "schema_version": 1,
            }
        ],
    }


class StudioManifestSchemaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        raw_schema = contract_gate.load_json_strict(SCHEMA_PATH)
        cls.validator = contract_gate.SchemaValidator(raw_schema)

    def test_valid_provisional_core_passes(self) -> None:
        self.assertEqual([], self.validator.validate(valid_manifest()))

    def test_rejects_absolute_parent_and_backslash_paths(self) -> None:
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
                manifest = valid_manifest()
                manifest["design_sources"][0]["path"] = bad_path
                codes = {
                    issue.code for issue in self.validator.validate(manifest)
                }
                self.assertIn("schema_pattern", codes)

    def test_rejects_bad_digest_unknown_fields_and_unknown_schema(self) -> None:
        manifest = valid_manifest()
        manifest["schema_version"] = 2
        manifest["design_sources"][0]["sha256"] = "not-a-digest"
        manifest["future_workgraph"] = {}

        codes = {issue.code for issue in self.validator.validate(manifest)}

        self.assertIn("schema_enum", codes)
        self.assertIn("schema_pattern", codes)
        self.assertIn("schema_additional_property", codes)

    def test_requires_design_source_but_allows_no_assets_or_packs(self) -> None:
        manifest = valid_manifest()
        manifest["approved_assets"] = []
        manifest["enabled_packs"] = []
        self.assertEqual([], self.validator.validate(manifest))

        missing_design = copy.deepcopy(manifest)
        missing_design["design_sources"] = []
        codes = {
            issue.code for issue in self.validator.validate(missing_design)
        }
        self.assertIn("schema_min_items", codes)

    def test_scaffold_is_json_and_intentionally_unresolved(self) -> None:
        scaffold = (
            AGENTS_DIR
            / "scaffolds"
            / "studio_manifest"
            / "mod-studio.json"
        )
        parsed = contract_gate.load_json_strict(scaffold)

        self.assertIsInstance(parsed, dict)
        self.assertTrue(any(
            contract_gate.PLACEHOLDER_RE.search(text)
            for _, text in contract_gate._walk_strings(parsed)
        ))
        self.assertTrue(self.validator.validate(parsed))


if __name__ == "__main__":
    unittest.main()
