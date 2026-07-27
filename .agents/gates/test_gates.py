#!/usr/bin/env python3
"""
Unit tests for .agents/gates toolchain and workspace initialization.

Runs under standard python unittest:
    python -m unittest .agents/gates/test_gates.py
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

# Insert gates directory to sys.path
GATES_DIR = Path(__file__).resolve().parent
AGENTS_DIR = GATES_DIR.parent
PROJECT_DIR = AGENTS_DIR.parent

sys.path.insert(0, str(GATES_DIR))
sys.path.insert(0, str(AGENTS_DIR / "skills" / "workspace_setup" / "scripts"))

import asset_gate
import compile_and_repair
import init_workspace
import static_gate


class TestGatesAndWorkspace(unittest.TestCase):

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="test_gates_"))

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_compile_cli_rejects_unknown_and_malformed_options(self):
        with self.assertRaisesRegex(ValueError, "unknown argument"):
            compile_and_repair.validate_cli_arguments(
                ["--with-game-test"]
            )
        with self.assertRaisesRegex(ValueError, "requires a value"):
            compile_and_repair.validate_cli_arguments(
                ["--gametest-timeout"]
            )
        compile_and_repair.validate_cli_arguments(
            [
                "--with-static",
                "--with-gametest",
                "--gametest-timeout=30",
            ]
        )

    def test_compile_cli_rejects_nonfinite_timeout(self):
        for value in ("nan", "inf", "-inf", "0", "-1"):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "finite"):
                    compile_and_repair.positive_finite_float(
                        value, "--gametest-timeout"
                    )

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

    def test_asset_gate_strict_datagen_layout_is_narrow(self):
        """Strict layout rejects provider outputs but permits manual resources."""
        main = self.test_dir / "src" / "main" / "resources"
        forbidden = [
            "assets/testmod/blockstates/example.json",
            "assets/testmod/models/item/example.json",
            "assets/testmod/lang/en_us.json",
            "data/testmod/advancement/example.json",
            "data/testmod/loot_table/blocks/example.json",
            "data/testmod/recipe/example.json",
            "data/minecraft/tags/block/mineable/pickaxe.json",
            "data/c/tags/item/ingots/example.json",
        ]
        allowed = [
            "assets/testmod/lang/zh_cn.json",
            "assets/testmod/sounds.json",
            "data/testmod/custom_manual_data/example.json",
        ]
        for rel in forbidden + allowed:
            path = main / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("{}", encoding="utf-8")
        texture = main / "assets" / "testmod" / "textures" / "item" / "example.png"
        texture.parent.mkdir(parents=True, exist_ok=True)
        texture.write_bytes(b"not-a-real-png-but-layout-allows-it")

        findings = asset_gate.check_datagen_layout(self.test_dir, "testmod")
        subjects = {finding.subject for finding in findings}

        self.assertEqual(
            {
                f"src/main/resources/{rel}"
                for rel in forbidden
            },
            subjects,
        )
        self.assertTrue(all(
            finding.rule_id == "datagen_resource_in_main"
            and finding.severity == "error"
            for finding in findings
        ))

    def test_asset_gate_reports_bilingual_key_drift(self):
        """en_us and zh_cn must expose the same translation-key set."""
        lang_dir = (
            self.test_dir / "src" / "generated" / "resources"
            / "assets" / "testmod" / "lang"
        )
        lang_dir.mkdir(parents=True, exist_ok=True)
        (lang_dir / "en_us.json").write_text(
            json.dumps({"shared": "Shared", "english.only": "English"}),
            encoding="utf-8",
        )
        (lang_dir / "zh_cn.json").write_text(
            json.dumps({"shared": "共享", "chinese.only": "中文"}),
            encoding="utf-8",
        )

        findings = asset_gate.check_lang_quality(
            asset_gate.ResourceView(self.test_dir), "testmod", set()
        )
        by_rule = {finding.rule_id: finding for finding in findings}

        self.assertIn("lang_key_missing_zh_cn", by_rule)
        self.assertIn("`english.only`", by_rule["lang_key_missing_zh_cn"].message)
        self.assertIn("lang_key_missing_en_us", by_rule)

    def test_static_gate_detects_performance_antipatterns(self):
        """Hot path stream usage and missing isClientSide check emit warnings."""
        src_dir = self.test_dir / "src" / "main" / "java" / "com" / "example"
        src_dir.mkdir(parents=True, exist_ok=True)
        bad_java = src_dir / "BadBlockEntity.java"
        bad_java.write_text(
            """package com.example;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.Level;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.state.BlockState;
            import java.util.List;
            import java.util.stream.Collectors;

            public class BadBlockEntity extends BlockEntity {
                public static void tick(Level level, BlockPos pos, BlockState state, BadBlockEntity be) {
                    List<String> list = be.items.stream().collect(Collectors.toList());
                }
            }
            """,
            encoding="utf-8",
        )
        findings = static_gate.scan_file(
            bad_java,
            bad_java.read_text(encoding="utf-8"),
            java_root=src_dir,
            mod_id="example",
        )
        rule_ids = {finding.rule_id for finding in findings}
        self.assertIn("perf_tick_stream_usage", rule_ids)
        self.assertIn("perf_blockentity_tick_clientside", rule_ids)

    def test_generated_resource_validation(self):
        """DataGen output must exist, be non-empty, and contain valid JSON."""
        ok, message = compile_and_repair.validate_generated_resources(
            str(self.test_dir)
        )
        self.assertFalse(ok)
        self.assertIn("does not exist", message)

        generated = self.test_dir / "src" / "generated" / "resources"
        generated.mkdir(parents=True)
        ok, message = compile_and_repair.validate_generated_resources(
            str(self.test_dir)
        )
        self.assertFalse(ok)
        self.assertIn("contains no JSON", message)

        malformed = generated / "data" / "testmod" / "recipe" / "broken.json"
        malformed.parent.mkdir(parents=True)
        malformed.write_text("{", encoding="utf-8")
        ok, message = compile_and_repair.validate_generated_resources(
            str(self.test_dir)
        )
        self.assertFalse(ok)
        self.assertIn("malformed generated JSON", message)

        malformed.write_text('{"type": "minecraft:crafting_shapeless"}', encoding="utf-8")
        ok, message = compile_and_repair.validate_generated_resources(
            str(self.test_dir)
        )
        self.assertTrue(ok)
        self.assertIn("validated 1 JSON file", message)

    def test_datagen_git_changes_are_scoped(self):
        """Reproducibility status ignores unrelated developer changes."""
        subprocess.run(
            ["git", "init", "-q", str(self.test_dir)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        generated = (
            self.test_dir / "src" / "generated" / "resources"
            / "data" / "testmod" / "recipe"
        )
        generated.mkdir(parents=True)
        tracked = generated / "example.json"
        tracked.write_text("{}", encoding="utf-8")
        unrelated = self.test_dir / "notes.txt"
        unrelated.write_text("tracked", encoding="utf-8")
        subprocess.run(
            ["git", "-C", str(self.test_dir), "add", "."],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        subprocess.run(
            [
                "git", "-C", str(self.test_dir),
                "-c", "user.name=Gate Tests",
                "-c", "user.email=gates@example.invalid",
                "commit", "-qm", "fixture",
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

        self.assertEqual([], compile_and_repair.datagen_git_changes(str(self.test_dir)))

        unrelated.write_text("developer edit", encoding="utf-8")
        self.assertEqual([], compile_and_repair.datagen_git_changes(str(self.test_dir)))
        self.assertEqual(
            1, len(compile_and_repair.git_worktree_changes(str(self.test_dir)))
        )

        tracked.write_text('{"changed": true}', encoding="utf-8")
        changes = compile_and_repair.datagen_git_changes(str(self.test_dir))
        self.assertIsNotNone(changes)
        self.assertEqual(1, len(changes))
        self.assertIn("src/generated/resources", changes[0].replace("\\", "/"))

    def test_server_smoke_terminates_the_process_tree(self):
        """L3 cleanup must target the wrapper tree, not only its parent PID."""
        class FakeProcess:
            pid = 4242

            @staticmethod
            def poll():
                return None

        process = FakeProcess()
        if os.name == "nt":
            with mock.patch.object(compile_and_repair.subprocess, "run") as run:
                run.return_value.returncode = 0
                compile_and_repair.terminate_process_tree(process)
            command = run.call_args.args[0]
            self.assertEqual(
                ["taskkill", "/PID", "4242", "/T", "/F"],
                command,
            )
        else:
            with (
                mock.patch.object(compile_and_repair.os, "getpgid", return_value=4242),
                mock.patch.object(compile_and_repair.os, "killpg") as killpg,
            ):
                compile_and_repair.terminate_process_tree(process)
            killpg.assert_called_once()

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

    def test_init_workspace_aligns_generated_resource_contents(self):
        """Generated JSON references must follow a Mod ID rename."""
        generated = self.test_dir / "src" / "generated" / "resources"
        model = generated / "assets" / "newmod" / "models" / "item" / "example.json"
        tag = generated / "data" / "minecraft" / "tags" / "item" / "example.json"
        model.parent.mkdir(parents=True)
        tag.parent.mkdir(parents=True)
        model.write_text(
            '{"parent":"tutorialmod:item/example"}',
            encoding="utf-8",
        )
        tag.write_text(
            '{"values":["tutorialmod:example_item"]}',
            encoding="utf-8",
        )

        changed = init_workspace.align_generated_resource_contents(
            generated,
            ["tutorialmod"],
            "newmod",
            [],
            False,
        )

        self.assertEqual(2, changed)
        self.assertNotIn("tutorialmod", model.read_text(encoding="utf-8"))
        self.assertNotIn("tutorialmod", tag.read_text(encoding="utf-8"))
        self.assertIn("newmod:item/example", model.read_text(encoding="utf-8"))
        self.assertIn("newmod:example_item", tag.read_text(encoding="utf-8"))

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
