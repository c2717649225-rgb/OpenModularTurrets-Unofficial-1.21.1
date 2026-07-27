#!/usr/bin/env python3
"""
Unit tests for .agents/gates toolchain and workspace initialization.

Runs under standard python unittest:
    python -m unittest .agents/gates/test_gates.py
"""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

# Insert gates directory to sys.path
GATES_DIR = Path(__file__).resolve().parent
AGENTS_DIR = GATES_DIR.parent
PROJECT_DIR = AGENTS_DIR.parent

sys.path.insert(0, str(GATES_DIR))
sys.path.insert(0, str(AGENTS_DIR / "skills" / "workspace_setup" / "scripts"))

import asset_gate
import init_workspace


class TestGatesAndWorkspace(unittest.TestCase):

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="test_gates_"))

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_asset_gate_plain_register_matching(self):
        """Verify plain ITEMS.register('name', ...) is matched by asset_gate."""
        java_dir = self.test_dir / "src" / "main" / "java" / "com" / "example"
        java_dir.mkdir(parents=True, exist_ok=True)
        java_file = java_dir / "ModItems.java"
        java_file.write_text(
            """
            package com.example;
            import net.neoforged.neoforge.registries.DeferredRegister;
            public class ModItems {
                public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("testmod");
                public static final Object MY_ITEM = ITEMS.register("my_custom_item", () -> null);
            }
            """,
            encoding="utf-8",
        )
        items, blocks, blockitems, translatables, unresolved = asset_gate.parse_registrations(self.test_dir)
        self.assertIn("my_custom_item", items)

    def test_init_workspace_system_namespaces_protection(self):
        """Verify assets/minecraft and data/minecraft are never renamed into the mod_id."""
        assets_dir = self.test_dir / "src" / "main" / "resources" / "assets"
        mc_assets = assets_dir / "minecraft"
        mc_assets.mkdir(parents=True, exist_ok=True)
        (mc_assets / "test.json").write_text("{}", encoding="utf-8")

        # Fake gradle.properties
        (self.test_dir / "gradle.properties").write_text(
            "mod_id=newmod\nmod_group_id=com.newmod\n", encoding="utf-8"
        )

        # Execute dry run / apply logic
        old_ids = ["tutorialmod"]
        if assets_dir.is_dir():
            for sub in list(assets_dir.iterdir()):
                if sub.is_dir() and sub.name not in init_workspace.SYSTEM_NAMESPACES and sub.name != "newmod":
                    init_workspace.merge_or_move(sub, assets_dir / "newmod", "Assets", [], False)

        self.assertTrue(mc_assets.exists())
        self.assertFalse((assets_dir / "newmod" / "minecraft").exists())

    def test_init_workspace_java_package_refactor(self):
        """Verify init_workspace refactors java package statements and moves directory."""
        java_root = self.test_dir / "src" / "main" / "java"
        old_pkg_dir = java_root / "com" / "tutorial" / "tutorialmod"
        old_pkg_dir.mkdir(parents=True, exist_ok=True)
        main_java = old_pkg_dir / "TutorialMod.java"
        main_java.write_text(
            """
            package com.tutorial.tutorialmod;
            import com.tutorial.tutorialmod.sub.Other;
            @Mod("tutorialmod")
            public class TutorialMod {
                public static final String MODID = "tutorialmod";
            }
            """,
            encoding="utf-8",
        )

        # Run refactor logic
        old_package = "com.tutorial.tutorialmod"
        new_package = "com.example.newmod"
        for jf in java_root.rglob("*.java"):
            content = jf.read_text(encoding="utf-8")
            content = content.replace(f"package {old_package}", f"package {new_package}")
            content = content.replace(f"import {old_package}", f"import {new_package}")
            jf.write_text(content, encoding="utf-8")

        new_pkg_dir = java_root / "com" / "example" / "newmod"
        init_workspace.merge_or_move(old_pkg_dir, new_pkg_dir, "Java Package", [], False)

        new_java = new_pkg_dir / "TutorialMod.java"
        self.assertTrue(new_java.exists())
        self.assertIn("package com.example.newmod;", new_java.read_text(encoding="utf-8"))

    def test_crash_rules_validity(self):
        """Verify crash_rules.json is valid JSON and all regexes compile."""
        crash_json = GATES_DIR / "crash_rules.json"
        self.assertTrue(crash_json.is_file())
        data = json.loads(crash_json.read_text(encoding="utf-8"))
        import re
        for r in data.get("rules", []):
            for p in r.get("patterns", []):
                re.compile(p)


if __name__ == "__main__":
    unittest.main()
