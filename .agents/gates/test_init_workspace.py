#!/usr/bin/env python3
"""Safety and apply tests for the workspace initialization engine."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


GATES_DIR = Path(__file__).resolve().parent
AGENTS_DIR = GATES_DIR.parent
PROJECT_DIR = AGENTS_DIR.parent
SCRIPT_DIR = (
    AGENTS_DIR / "skills" / "workspace_setup" / "scripts"
)
sys.path.insert(0, str(SCRIPT_DIR))

import init_workspace


class InitWorkspaceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_handle = tempfile.TemporaryDirectory(
            prefix="init_workspace_"
        )
        self.project = Path(self.temp_handle.name).resolve()

    def tearDown(self) -> None:
        self.temp_handle.cleanup()

    def make_fixture(
        self,
        *,
        mod_id: str = "newmod",
        package_name: str = "com.acme.newmod",
        custom_datagen: bool = True,
    ) -> None:
        properties = (PROJECT_DIR / "gradle.properties").read_text(
            encoding="utf-8"
        )
        properties = properties.replace(
            "mod_id=tutorialmod", f"mod_id={mod_id}"
        ).replace(
            "mod_group_id=com.tutorial.tutorialmod",
            f"mod_group_id={package_name}",
        )
        (self.project / "gradle.properties").write_text(
            properties, encoding="utf-8"
        )

        source_package = (
            PROJECT_DIR
            / "src"
            / "main"
            / "java"
            / "com"
            / "tutorial"
            / "tutorialmod"
        )
        target_package = (
            self.project
            / "src"
            / "main"
            / "java"
            / "com"
            / "tutorial"
            / "tutorialmod"
        )
        target_package.mkdir(parents=True, exist_ok=True)
        shutil.copy2(
            source_package / "TutorialMod.java",
            target_package / "TutorialMod.java",
        )
        shutil.copy2(
            source_package / "Config.java",
            target_package / "Config.java",
        )
        shutil.copytree(
            source_package / "datagen",
            target_package / "datagen",
        )
        reference_source = (
            PROJECT_DIR
            / "src"
            / "main"
            / "java"
            / "dev"
            / "modstudio"
            / "referencehost"
        )
        shutil.copytree(
            reference_source,
            self.project
            / "src"
            / "main"
            / "java"
            / "dev"
            / "modstudio"
            / "referencehost",
        )
        if custom_datagen:
            (target_package / "datagen" / "CustomProvider.java").write_text(
                """package com.tutorial.tutorialmod.datagen;

public final class CustomProvider {
    private CustomProvider() {
    }
}
""",
                encoding="utf-8",
            )

        source_resources = PROJECT_DIR / "src" / "main" / "resources"
        target_resources = self.project / "src" / "main" / "resources"
        shutil.copytree(source_resources, target_resources)
        source_generated = PROJECT_DIR / "src" / "generated" / "resources"
        shutil.copytree(
            source_generated,
            self.project / "src" / "generated" / "resources",
        )
        shutil.copytree(
            PROJECT_DIR / "src" / "main" / "snbt",
            self.project / "src" / "main" / "snbt",
        )

        for language_path in (
            target_resources
            / "assets"
            / "tutorialmod"
            / "lang"
            / "zh_cn.json",
            self.project
            / "src"
            / "generated"
            / "resources"
            / "assets"
            / "tutorialmod"
            / "lang"
            / "en_us.json",
        ):
            language = json.loads(language_path.read_text(encoding="utf-8"))
            language["custom.key"] = "Preserved"
            language_path.write_text(
                json.dumps(language, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )

        agents = self.project / ".agents"
        agents.mkdir()
        (agents / "AGENTS.md").write_text(
            "# Fixture project rules\n", encoding="utf-8"
        )

    def run_engine(
        self,
        argv: list[str],
        *,
        public_main: bool = False,
    ) -> tuple[int, str]:
        output = io.StringIO()
        entrypoint = (
            init_workspace.main
            if public_main
            else init_workspace._run_main
        )
        with (
            mock.patch.object(
                init_workspace,
                "project_paths",
                return_value=(self.project, self.project / ".agents"),
            ),
            mock.patch.object(
                init_workspace,
                "git_dirty",
                return_value=False,
            ),
            contextlib.redirect_stdout(output),
        ):
            code = entrypoint(argv)
        return code, output.getvalue()

    @staticmethod
    def action_lines(output: str) -> list[str]:
        return [
            line
            for line in output.splitlines()
            if line.startswith("[")
            and not line.startswith("[Properties]")
            and not line.startswith("[Java Main Class]")
            and not line.startswith("[Java Old Package]")
        ]

    def tree_digest(self) -> str:
        digest = hashlib.sha256()
        for path in sorted(
            (candidate for candidate in self.project.rglob("*")
             if candidate.is_file()),
            key=lambda candidate: candidate.as_posix(),
        ):
            digest.update(path.relative_to(self.project).as_posix().encode())
            digest.update(b"\0")
            digest.update(path.read_bytes())
            digest.update(b"\0")
        return digest.hexdigest()

    def compile_minimal_sources(self, package_path: Path) -> None:
        javac = shutil.which("javac")
        if javac is None:
            self.skipTest("javac is unavailable")
        stubs = self.project / "compile-stubs"
        stub_sources = {
            "org/slf4j/Logger.java": (
                "package org.slf4j; public interface Logger {}\n"
            ),
            "com/mojang/logging/LogUtils.java": (
                "package com.mojang.logging; "
                "public final class LogUtils { "
                "public static org.slf4j.Logger getLogger() { return null; } }\n"
            ),
            "net/neoforged/bus/api/IEventBus.java": (
                "package net.neoforged.bus.api; public interface IEventBus {}\n"
            ),
            "net/neoforged/fml/ModContainer.java": (
                "package net.neoforged.fml; public class ModContainer {}\n"
            ),
            "net/neoforged/fml/common/Mod.java": (
                "package net.neoforged.fml.common; "
                "public @interface Mod { String value(); }\n"
            ),
        }
        for relative, content in stub_sources.items():
            path = stubs / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        sources = [
            str(path)
            for path in sorted(stubs.rglob("*.java"))
        ] + [
            str(path)
            for path in sorted(package_path.rglob("*.java"))
        ]
        result = subprocess.run(
            [javac, "-d", str(self.project / "classes"), *sources],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        self.assertEqual(
            0,
            result.returncode,
            result.stdout + "\n" + result.stderr,
        )

    def test_minimal_apply_is_compile_safe_preserves_user_datagen_and_is_idempotent(
        self,
    ) -> None:
        self.make_fixture()

        dry_code, dry_output = self.run_engine(
            ["--dry-run", "--profile", "minimal"]
        )
        apply_code, apply_output = self.run_engine(
            ["--force", "--profile", "minimal"]
        )

        self.assertEqual(0, dry_code, dry_output)
        self.assertEqual(0, apply_code, apply_output)
        self.assertEqual(
            self.action_lines(dry_output),
            self.action_lines(apply_output),
        )

        package = (
            self.project
            / "src"
            / "main"
            / "java"
            / "com"
            / "acme"
            / "newmod"
        )
        main_source = (package / "TutorialMod.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("package com.acme.newmod;", main_source)
        self.assertIn('MODID = "newmod"', main_source)
        self.assertNotIn("EXAMPLE_", main_source)
        self.assertNotIn("Config.", main_source)
        self.assertFalse((package / "Config.java").exists())
        self.assertTrue((package / "datagen" / "CustomProvider.java").is_file())
        self.assertEqual(
            ["CustomProvider.java"],
            sorted(
                path.name
                for path in (package / "datagen").glob("*.java")
            ),
        )
        all_java = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (
                self.project / "src" / "main" / "java"
            ).rglob("*.java")
        )
        self.assertNotIn("EXAMPLE_", all_java)
        self.assertNotIn("Config.", all_java)
        reference_host = (
            self.project
            / "src"
            / "main"
            / "java"
            / "dev"
            / "modstudio"
            / "referencehost"
        )
        reference_test = (
            reference_host / "ReferenceHostGameTests.java"
        ).read_text(encoding="utf-8")
        reference_datagen = (
            reference_host / "ReferenceHostDataGenerators.java"
        ).read_text(encoding="utf-8")
        self.assertIn('@GameTestHolder("newmod")', reference_test)
        self.assertIn("Blocks.STONE", reference_test)
        self.assertIn(
            "import com.acme.newmod.TutorialMod;",
            reference_datagen,
        )

        generated = self.project / "src" / "generated" / "resources"
        self.assertEqual(
            [],
            [
                path
                for path in generated.rglob("*.json")
                if "example_block" in path.as_posix()
                or "example_item" in path.as_posix()
            ],
        )
        for language_path in (
            self.project
            / "src"
            / "main"
            / "resources"
            / "assets"
            / "newmod"
            / "lang"
            / "zh_cn.json",
            generated
            / "assets"
            / "newmod"
            / "lang"
            / "en_us.json",
        ):
            language = json.loads(language_path.read_text(encoding="utf-8"))
            self.assertEqual({"custom.key": "Preserved"}, language)
        cache_files = list((generated / ".cache").glob("*"))
        self.assertEqual(1, len(cache_files))
        cache_content = cache_files[0].read_text(encoding="utf-8")
        self.assertIn(
            "data/newmod/structure/referencehostgametests.smoke.nbt",
            cache_content,
        )
        self.assertNotIn("tutorialmod", cache_content)

        snbt_structure = (
            self.project
            / "src"
            / "main"
            / "snbt"
            / "data"
            / "newmod"
            / "structure"
            / "referencehostgametests.smoke.snbt"
        )
        generated_structure = (
            generated
            / "data"
            / "newmod"
            / "structure"
            / "referencehostgametests.smoke.nbt"
        )
        self.assertTrue(snbt_structure.is_file())
        self.assertTrue(generated_structure.is_file())

        self.compile_minimal_sources(package)

        second_code, second_output = self.run_engine(
            ["--force", "--profile", "minimal"]
        )
        self.assertEqual(0, second_code, second_output)
        self.assertIn("(no file changes needed; already aligned)", second_output)

    def test_modified_starter_datagen_fails_without_deleting_user_files(
        self,
    ) -> None:
        self.make_fixture()
        orchestrator = (
            self.project
            / "src"
            / "main"
            / "java"
            / "com"
            / "tutorial"
            / "tutorialmod"
            / "datagen"
            / "ModDataGenerators.java"
        )
        orchestrator.write_text(
            orchestrator.read_text(encoding="utf-8")
            + "\n// user-owned provider registration\n",
            encoding="utf-8",
        )
        before = self.tree_digest()

        code, output = self.run_engine(
            ["--force", "--profile", "minimal"],
            public_main=True,
        )

        self.assertEqual(3, code)
        self.assertIn("dangling Java references", output)
        self.assertEqual(before, self.tree_digest())
        self.assertTrue(orchestrator.is_file())

    def test_example_profile_aligns_reference_host_and_is_idempotent(
        self,
    ) -> None:
        self.make_fixture()

        dry_code, dry_output = self.run_engine(["--dry-run"])
        apply_code, apply_output = self.run_engine(["--force"])

        self.assertEqual(0, dry_code, dry_output)
        self.assertEqual(0, apply_code, apply_output)
        self.assertEqual(
            self.action_lines(dry_output),
            self.action_lines(apply_output),
        )

        reference_host = (
            self.project
            / "src"
            / "main"
            / "java"
            / "dev"
            / "modstudio"
            / "referencehost"
        )
        reference_test = (
            reference_host / "ReferenceHostGameTests.java"
        ).read_text(encoding="utf-8")
        reference_datagen = (
            reference_host / "ReferenceHostDataGenerators.java"
        ).read_text(encoding="utf-8")
        self.assertIn('@GameTestHolder("newmod")', reference_test)
        self.assertIn(
            "import com.acme.newmod.TutorialMod;",
            reference_datagen,
        )
        self.assertTrue(
            (
                self.project
                / "src"
                / "main"
                / "snbt"
                / "data"
                / "newmod"
                / "structure"
                / "referencehostgametests.smoke.snbt"
            ).is_file()
        )
        self.assertFalse(
            (
                self.project
                / "src"
                / "main"
                / "snbt"
                / "data"
                / "tutorialmod"
            ).exists()
        )
        generated = self.project / "src" / "generated" / "resources"
        self.assertTrue(
            (
                generated
                / "data"
                / "newmod"
                / "structure"
                / "referencehostgametests.smoke.nbt"
            ).is_file()
        )
        cache_text = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (generated / ".cache").glob("*")
        )
        self.assertNotIn("tutorialmod", cache_text)
        self.assertIn("data/newmod/structure", cache_text)

        second_code, second_output = self.run_engine(["--force"])
        self.assertEqual(0, second_code, second_output)
        self.assertIn(
            "(no file changes needed; already aligned)",
            second_output,
        )

    def test_holder_alignment_ignores_comments_strings_and_fake_type(
        self,
    ) -> None:
        official = """
import net.neoforged.neoforge.gametest.GameTestHolder;
// @GameTestHolder("tutorialmod")
class Notes {
    String text = "@GameTestHolder(\\"tutorialmod\\")";
}
@GameTestHolder("tutorialmod")
class RealTests {}
"""
        aligned = init_workspace.align_gametest_holder_literals(
            official,
            ["tutorialmod"],
            "newmod",
        )
        self.assertIn('// @GameTestHolder("tutorialmod")', aligned)
        self.assertIn(
            '"@GameTestHolder(\\"tutorialmod\\")"',
            aligned,
        )
        self.assertIn('@GameTestHolder("newmod")', aligned)

        fake = """
import example.fake.GameTestHolder;
@GameTestHolder("tutorialmod")
class FakeTests {}
"""
        self.assertEqual(
            fake,
            init_workspace.align_gametest_holder_literals(
                fake,
                ["tutorialmod"],
                "newmod",
            ),
        )

    def test_invalid_java_package_paths_fail_closed(self) -> None:
        invalid_values = (
            "../../escape",
            r"com\escape",
            str((self.project.parent / "absolute_escape").resolve()),
            "com..escape",
            "com.class.escape",
        )
        for package_name in invalid_values:
            with self.subTest(package_name=package_name):
                self.temp_handle.cleanup()
                self.temp_handle = tempfile.TemporaryDirectory(
                    prefix="init_workspace_invalid_"
                )
                self.project = Path(self.temp_handle.name).resolve()
                self.make_fixture(package_name=package_name)
                before = self.tree_digest()

                code, output = self.run_engine(
                    ["--force", "--profile", "minimal"],
                    public_main=True,
                )

                self.assertEqual(3, code)
                self.assertIn("unsafe workspace mutation refused", output)
                self.assertEqual(before, self.tree_digest())

    def test_symlinked_destination_escape_fails_closed(self) -> None:
        self.make_fixture(package_name="linked.escape")
        outside = self.project.parent / (
            self.project.name + "_outside"
        )
        outside.mkdir()
        link = self.project / "src" / "main" / "java" / "linked"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError) as error:
            shutil.rmtree(outside, ignore_errors=True)
            self.skipTest(f"directory symlinks unavailable: {error}")
        before = self.tree_digest()
        outside_before = list(outside.iterdir())

        code, output = self.run_engine(
            ["--force", "--profile", "minimal"],
            public_main=True,
        )

        self.assertEqual(3, code)
        self.assertIn("symlink", output)
        self.assertEqual(before, self.tree_digest())
        self.assertEqual(outside_before, list(outside.iterdir()))
        link.unlink()
        shutil.rmtree(outside, ignore_errors=True)

    def test_merge_rejects_traversal_and_different_destination_content(
        self,
    ) -> None:
        root = self.project / "root"
        source = root / "source"
        destination = root / "destination"
        source.mkdir(parents=True)
        destination.mkdir()
        (source / "same.txt").write_text("source", encoding="utf-8")
        (destination / "same.txt").write_text(
            "destination", encoding="utf-8"
        )

        with self.assertRaises(init_workspace.WorkspaceSafetyError):
            init_workspace.merge_or_move(
                source,
                destination,
                "test",
                [],
                False,
                allowed_root=root,
            )
        self.assertTrue((source / "same.txt").is_file())
        with self.assertRaises(init_workspace.WorkspaceSafetyError):
            init_workspace.merge_or_move(
                root / ".." / "outside",
                destination,
                "test",
                [],
                True,
                allowed_root=root,
            )


if __name__ == "__main__":
    unittest.main()
