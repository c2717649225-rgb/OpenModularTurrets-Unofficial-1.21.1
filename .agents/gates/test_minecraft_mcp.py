#!/usr/bin/env python3
"""Regression tests for project-scoped Minecraft MCP source selection."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parents[2]
MCP_PATH = PROJECT_DIR / ".agents" / "mcp" / "minecraft_mcp.py"
SPEC = importlib.util.spec_from_file_location("minecraft_mcp_under_test", MCP_PATH)
assert SPEC is not None and SPEC.loader is not None
minecraft_mcp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(minecraft_mcp)


class MinecraftMcpTests(unittest.TestCase):
    def test_metadata_uses_active_dependencies_not_commented_examples(self) -> None:
        with tempfile.TemporaryDirectory(prefix="minecraft_mcp_metadata_") as temp:
            project = Path(temp)
            (project / "gradle.properties").write_text(
                "minecraft_version=1.21.1\nneo_version=21.1.234\n",
                encoding="utf-8",
            )
            (project / "build.gradle").write_text(
                """
dependencies {
    // implementation "mezz.jei:jei-1.21.1:19.0.0"
    implementation "software.bernie.geckolib:geckolib-neoforge-1.21.1:4.8"
}
""",
                encoding="utf-8",
            )

            metadata = minecraft_mcp.load_project_metadata(str(project))

        self.assertEqual("1.21.1", metadata["minecraft_version"])
        self.assertEqual("21.1.234", metadata["neo_version"])
        self.assertEqual(["geckolib"], metadata["dependency_keywords"])

    def test_filter_rejects_forge_and_wrong_minecraft_versions(self) -> None:
        metadata = {
            "minecraft_version": "1.21.1",
            "neo_version": "21.1.234",
            "dependency_keywords": ["geckolib"],
        }
        jars = [
            "/cache/net/neoforged/neoforge/21.1.234/neoforge-21.1.234-sources.jar",
            "/cache/net/neoforged/neoforge/21.1.200/neoforge-21.1.200-sources.jar",
            "/cache/net/minecraft/client/1.21.1/minecraft-1.21.1-sources.jar",
            "/cache/net/minecraftforge/forge/1.20.1/forge-1.20.1-sources.jar",
            "/cache/forge_gradle/forge-1.20.1-sources.jar",
            "/cache/geckolib-forge-1.20.1-sources.jar",
            "/cache/geckolib-neoforge-1.21.1-sources.jar",
        ]

        filtered = minecraft_mcp.filter_jars(jars, metadata=metadata)
        scan_all = minecraft_mcp.filter_jars(
            jars,
            scan_all_deps=True,
            metadata=metadata,
        )

        expected = {
            jars[0],
            jars[2],
            jars[6],
        }
        self.assertEqual(expected, set(filtered))
        self.assertNotIn(jars[3], scan_all)
        self.assertNotIn(jars[4], scan_all)
        self.assertNotIn(jars[5], scan_all)

    def test_onboarding_includes_codex_registration_command(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            minecraft_mcp.print_mcp_onboarding()

        text = output.getvalue()
        self.assertIn("codex mcp add minecraft-mcp --", text)
        self.assertIn("codex mcp get minecraft-mcp", text)
        self.assertIn("restart Codex", text)


if __name__ == "__main__":
    unittest.main()
