from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_doc_meta  # noqa: E402


VERIFIED_META = """---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-27
---
# Test reference
"""

VERSION_META = """1.2.0
Minecraft: 1.21.1
NeoForge: 21.1.x
docs_pin_neo: 21.1.x
"""


class DocMetadataManifestTests(unittest.TestCase):
    def run_gate(
        self,
        core: list[str],
        verified: list[str],
        documents: dict[str, str],
        version_text: str = VERSION_META,
    ) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            refs = root / "references"
            refs.mkdir()
            core_list = root / "docs_core_set.txt"
            verified_list = root / "docs_verified_set.txt"
            version_file = root / "VERSION"
            core_list.write_text("\n".join(core) + "\n", encoding="utf-8")
            verified_list.write_text(
                "\n".join(verified) + "\n", encoding="utf-8"
            )
            version_file.write_text(version_text, encoding="utf-8")
            for name, body in documents.items():
                (refs / name).write_text(body, encoding="utf-8")

            output = io.StringIO()
            with (
                mock.patch.object(check_doc_meta, "REFS", refs),
                mock.patch.object(check_doc_meta, "CORE_LIST", core_list),
                mock.patch.object(
                    check_doc_meta, "VERIFIED_LIST", verified_list
                ),
                mock.patch.object(
                    check_doc_meta, "VERSION_FILE", version_file
                ),
                contextlib.redirect_stdout(output),
            ):
                result = check_doc_meta.main()
            return result, output.getvalue()

    def test_verified_documents_may_exist_outside_small_core_set(self) -> None:
        core = [f"core_{index}.md" for index in range(5)]
        extra = "advanced_worldgen.md"
        documents = {name: VERIFIED_META for name in [*core, extra]}

        result, output = self.run_gate(
            core, [*core, extra], documents
        )

        self.assertEqual(0, result, output)
        self.assertIn("Verified count: 6 (no hard cap)", output)
        self.assertIn("OK verified: advanced_worldgen.md", output)

    def test_verified_metadata_claim_requires_manifest_membership(self) -> None:
        core = [f"core_{index}.md" for index in range(5)]
        documents = {name: VERIFIED_META for name in core}
        documents["unlisted.md"] = VERIFIED_META

        result, output = self.run_gate(core, core, documents)

        self.assertEqual(1, result)
        self.assertIn(
            "unlisted.md claims verified but is absent", output
        )

    def test_core_document_must_be_in_verified_manifest(self) -> None:
        core = [f"core_{index}.md" for index in range(5)]
        documents = {name: VERIFIED_META for name in core}

        result, output = self.run_gate(core, core[:-1], documents)

        self.assertEqual(1, result)
        self.assertIn(
            "core document core_4.md is not in docs_verified_set.txt", output
        )

    def test_verified_minecraft_pin_must_equal_version(self) -> None:
        core = [f"core_{index}.md" for index in range(5)]
        documents = {name: VERIFIED_META for name in core}
        documents["core_0.md"] = VERIFIED_META.replace(
            "pin_minecraft: 1.21.1",
            "pin_minecraft: 1.21.2",
        )

        result, output = self.run_gate(core, core, documents)

        self.assertEqual(1, result)
        self.assertIn(
            "pin_minecraft=1.21.2 does not match VERSION Minecraft=1.21.1",
            output,
        )

    def test_verified_neoforge_pin_must_equal_version(self) -> None:
        core = [f"core_{index}.md" for index in range(5)]
        documents = {name: VERIFIED_META for name in core}
        documents["core_0.md"] = VERIFIED_META.replace(
            "pin_neo: 21.1.x",
            "pin_neo: 21.2.x",
        )

        result, output = self.run_gate(core, core, documents)

        self.assertEqual(1, result)
        self.assertIn(
            "pin_neo=21.2.x does not match VERSION docs_pin_neo=21.1.x",
            output,
        )


if __name__ == "__main__":
    unittest.main()
