#!/usr/bin/env python3
"""Standard-library tests for the L4 GameTest gate.

These tests use fake processes and never launch Gradle or Minecraft.
"""
from __future__ import annotations

import json
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock


GATES_DIR = Path(__file__).resolve().parent
PROJECT_DIR = GATES_DIR.parents[1]
sys.path.insert(0, str(GATES_DIR))

import gametest_gate


PASS_OUTPUT = """
[Server thread/INFO] 2 tests are now running at position 1, 2, 3!
[Server thread/INFO] ========= 2 GAME TESTS COMPLETE IN 0.5 s ======================
[Server thread/INFO] All 2 required tests passed :)
[Server thread/INFO] ====================================================
BUILD SUCCESSFUL in 4s
"""

ONE_TEST_PASS_OUTPUT = """
[Server thread/INFO] 1 test are now running at position 1, 2, 3!
[Server thread/INFO] ========= 1 GAME TESTS COMPLETE IN 0.5 s ======================
[Server thread/INFO] All 1 required tests passed :)
BUILD SUCCESSFUL in 4s
"""


class FakeProcess:
    def __init__(
        self,
        command,
        *,
        stdout,
        output: str,
        timeout_once: bool = False,
        returncode: int = 0,
        **kwargs,
    ):
        self.command = command
        self.kwargs = kwargs
        self.pid = 4242
        self.returncode = None
        self._final_returncode = returncode
        self._timeout_once = timeout_once
        self._wait_count = 0
        self.killed = False
        stdout.write(output)
        stdout.flush()

    def wait(self, timeout=None):
        self._wait_count += 1
        if self._timeout_once and self._wait_count == 1:
            raise subprocess.TimeoutExpired(self.command, timeout)
        self.returncode = self._final_returncode
        return self.returncode

    def poll(self):
        return self.returncode

    def kill(self):
        self.killed = True
        self.returncode = -9


class TestGameTestGate(unittest.TestCase):
    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="gametest_gate_"))
        (self.test_dir / "gradle.properties").write_text(
            "mod_id=example\n", encoding="utf-8"
        )

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def write(self, relative: str, content: str) -> Path:
        path = self.test_dir / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def make_wrapper(self) -> Path:
        name = "gradlew.bat" if os.name == "nt" else "gradlew"
        return self.write(name, "unused by unit tests\n")

    def test_discovery_ignores_comments_literals_and_excluded_trees(self):
        source_file = self.write(
            "src/main/java/com/example/FeatureGameTests.java",
            '''
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("example")
            public class FeatureGameTests {
                // @GameTest
                String text = "@GameTest";
                String block = """
                    @GameTest
                """;
                /* @GameTest(template = "ignored") */
                @GameTest(template = "first")
                public static void first(GameTestHelper helper) {}

                @net.minecraft.gametest.framework.GameTest
                public static void second(GameTestHelper helper) {}
            }
            ''',
        )
        self.write(
            "src/generated/java/com/example/GeneratedTests.java",
            "@GameTest public static void generated() {}",
        )
        self.write(
            "src/test/java/com/example/UnitTests.java",
            "@GameTest public static void unitOnly() {}",
        )
        self.write(
            "src/main/java/scaffolds/ScaffoldTests.java",
            "@GameTest public static void placeholder() {}",
        )
        self.write(
            "build/generated/sources/BuildTests.java",
            "@GameTest public static void buildOutput() {}",
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertEqual(1, result.scanned_files)
        self.assertEqual(2, result.count)
        self.assertEqual([], result.errors)
        self.assertTrue(
            all(
                occurrence.path
                == "src/main/java/com/example/FeatureGameTests.java"
                for occurrence in result.tests
            )
        )
        self.assertEqual(
            [
                "com.example.FeatureGameTests#first",
                "com.example.FeatureGameTests#second",
            ],
            [occurrence.symbol for occurrence in result.tests],
        )
        self.assertTrue(
            all(
                occurrence.fqcn == "com.example.FeatureGameTests"
                for occurrence in result.tests
            )
        )
        self.assertEqual(
            ["first", "second"],
            [occurrence.method for occurrence in result.tests],
        )
        self.assertTrue(all(test.signature_valid for test in result.tests))
        self.assertTrue(
            all(test.holder_namespace == "example" for test in result.tests)
        )
        expected_sha256 = hashlib.sha256(source_file.read_bytes()).hexdigest()
        self.assertTrue(
            all(test.source_sha256 == expected_sha256 for test in result.tests)
        )

    def test_discovery_reports_invalid_signature_and_missing_holder(self):
        self.write(
            "src/main/java/com/example/BrokenGameTests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            public class BrokenGameTests {
                @GameTest
                public void notStatic(String helper) {}
            }
            """,
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertEqual(1, result.count)
        occurrence = result.tests[0]
        self.assertEqual(
            "com.example.BrokenGameTests#notStatic", occurrence.symbol
        )
        self.assertFalse(occurrence.signature_valid)
        self.assertIsNone(occurrence.holder_namespace)
        self.assertTrue(result.errors)
        self.assertTrue(
            any("must be static" in error for error in occurrence.signature_errors)
        )
        self.assertTrue(
            any("GameTestHolder" in error for error in occurrence.signature_errors)
        )

    def test_class_level_prefix_false_is_rejected_fail_closed(self):
        self.write(
            "src/main/java/com/example/UnprefixedGameTests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
            @GameTestHolder("example")
            @PrefixGameTestTemplate(false)
            public class UnprefixedGameTests {
                @GameTest
                public static void behavior(GameTestHelper helper) {}
            }
            """,
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertEqual(1, result.count)
        self.assertFalse(result.tests[0].signature_valid)
        self.assertTrue(
            any(
                "PrefixGameTestTemplate(false)" in error
                for error in result.errors
            )
        )

    @unittest.skipUnless(shutil.which("javac"), "javac is required")
    def test_compiled_bytecode_must_contain_official_runtime_annotations(self):
        game_test_annotation = self.write(
            "src/main/java/net/minecraft/gametest/framework/GameTest.java",
            """
            package net.minecraft.gametest.framework;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface GameTest {}
            """,
        )
        self.write(
            "src/main/java/net/minecraft/gametest/framework/GameTestHelper.java",
            """
            package net.minecraft.gametest.framework;
            public final class GameTestHelper {}
            """,
        )
        self.write(
            (
                "src/main/java/net/neoforged/neoforge/gametest/"
                "GameTestHolder.java"
            ),
            """
            package net.neoforged.neoforge.gametest;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface GameTestHolder { String value(); }
            """,
        )
        self.write(
            "src/main/java/com/example/BytecodeGameTests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("example")
            public final class BytecodeGameTests {
                @GameTest
                public static void behavior(GameTestHelper helper) {}
            }
            """,
        )
        sources = sorted(
            str(path)
            for path in (self.test_dir / "src/main/java").rglob("*.java")
        )
        classes = self.test_dir / "build/classes/java/main"
        classes.mkdir(parents=True)

        compiled = subprocess.run(
            [shutil.which("javac"), "-d", str(classes), *sources],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        self.assertEqual(0, compiled.returncode, compiled.stderr)
        discovery = gametest_gate.discover_gametests(self.test_dir)
        self.assertEqual([], discovery.errors)

        verified = gametest_gate.verify_compiled_gametests(
            self.test_dir, discovery
        )

        self.assertEqual([], verified.errors)
        self.assertTrue(verified.tests[0].bytecode_verified)
        self.assertRegex(
            verified.tests[0].bytecode_sha256 or "", r"^[0-9a-f]{64}$"
        )

        game_test_annotation.write_text(
            game_test_annotation.read_text(encoding="utf-8").replace(
                "RetentionPolicy.RUNTIME", "RetentionPolicy.SOURCE"
            ),
            encoding="utf-8",
        )
        recompiled = subprocess.run(
            [shutil.which("javac"), "-d", str(classes), *sources],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        self.assertEqual(0, recompiled.returncode, recompiled.stderr)

        rejected = gametest_gate.verify_compiled_gametests(
            self.test_dir, discovery
        )

        self.assertFalse(rejected.tests[0].bytecode_verified)
        self.assertTrue(
            any("official @GameTest descriptor" in error
                for error in rejected.errors)
        )

    def test_discovery_rejects_lookalike_annotations_without_official_imports(self):
        self.write(
            "src/main/java/com/example/LookalikeTests.java",
            """
            package com.example;
            @GameTestHolder("example")
            public class LookalikeTests {
                @GameTest
                public static void lookalike(GameTestHelper helper) {}
            }
            """,
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertEqual(1, result.count)
        occurrence = result.tests[0]
        self.assertFalse(occurrence.signature_valid)
        self.assertTrue(
            any(
                gametest_gate.OFFICIAL_GAME_TEST in error
                for error in occurrence.signature_errors
            )
        )
        self.assertTrue(
            any(
                gametest_gate.OFFICIAL_GAME_TEST_HELPER in error
                for error in occurrence.signature_errors
            )
        )
        self.assertIsNone(occurrence.holder_namespace)

    def test_discovery_rejects_wildcard_import_type_shadowing(self):
        self.write(
            "src/main/java/com/example/ShadowedTests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.*;
            import net.neoforged.neoforge.gametest.*;
            @interface GameTest {}
            @interface GameTestHolder { String value(); }
            class GameTestHelper {}
            @GameTestHolder("example")
            public class ShadowedTests {
                @GameTest
                public static void behavior(GameTestHelper helper) {}
            }
            """,
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertEqual(1, result.count)
        self.assertFalse(result.tests[0].signature_valid)
        self.assertTrue(
            any(
                "must resolve" in error
                for error in result.tests[0].signature_errors
            )
        )

    def test_discovery_accepts_fully_qualified_official_types(self):
        self.write(
            "src/main/java/com/example/FullyQualifiedTests.java",
            """
            package com.example;
            @net.neoforged.neoforge.gametest.GameTestHolder("example")
            public class FullyQualifiedTests {
                @net.minecraft.gametest.framework.GameTest
                public static void behavior(
                    net.minecraft.gametest.framework.GameTestHelper helper
                ) {}
            }
            """,
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertEqual([], result.errors)
        self.assertEqual(1, result.count)
        self.assertTrue(result.tests[0].signature_valid)
        self.assertEqual("example", result.tests[0].holder_namespace)

    def test_holder_annotation_cannot_leak_from_another_top_level_type(self):
        self.write(
            "src/main/java/com/example/HolderLeakTests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("wrong_owner")
            interface OtherType {}

            public class HolderLeakTests {
                @GameTest
                public static void behavior(GameTestHelper helper) {}
            }
            """,
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertEqual(1, result.count)
        self.assertFalse(result.tests[0].signature_valid)
        self.assertIsNone(result.tests[0].holder_namespace)
        self.assertTrue(
            any(
                "GameTestHolder" in error
                for error in result.tests[0].signature_errors
            )
        )

    def test_named_holder_namespace_is_recorded(self):
        self.write(
            "src/main/java/com/example/NamedHolderTests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder(value = "example")
            public class NamedHolderTests {
                @GameTest
                public static void behavior(GameTestHelper helper) {}
            }
            """,
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertEqual([], result.errors)
        self.assertEqual("example", result.tests[0].holder_namespace)

    def test_holder_namespace_must_match_host_mod_id(self):
        self.write(
            "src/main/java/com/example/WrongNamespaceTests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("other")
            public class WrongNamespaceTests {
                @GameTest
                public static void behavior(GameTestHelper helper) {}
            }
            """,
        )

        result = gametest_gate.discover_gametests(self.test_dir)

        self.assertFalse(result.tests[0].signature_valid)
        self.assertTrue(
            any("does not match" in error for error in result.errors)
        )

    def test_require_tests_fails_before_run_when_discovery_is_empty(self):
        with mock.patch.object(gametest_gate, "run_game_tests") as run:
            code = gametest_gate.main(
                [
                    "--project-dir",
                    str(self.test_dir),
                    "--require-tests",
                    "--run",
                ]
            )

        self.assertEqual(1, code)
        run.assert_not_called()

    def test_empty_discovery_is_advisory_without_require_tests(self):
        report_path = self.test_dir / "advisory.json"
        code = gametest_gate.main(
            [
                "--project-dir",
                str(self.test_dir),
                "--json-report",
                str(report_path),
            ]
        )
        self.assertEqual(0, code)
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual("advisory", report["result"]["status"])
        self.assertFalse(report["result"]["passed"])
        self.assertFalse(report["result"]["evidence_satisfied"])
        self.assertTrue(report["result"]["command_ok"])

    def test_run_if_present_skips_gradle_for_empty_host(self):
        with mock.patch.object(gametest_gate, "run_game_tests") as run:
            code = gametest_gate.main(
                [
                    "--project-dir",
                    str(self.test_dir),
                    "--run-if-present",
                ]
            )

        self.assertEqual(0, code)
        run.assert_not_called()

    def test_contract_presence_turns_conditional_ci_mode_strict(self):
        self.write(
            "docs/features/example.contract.json",
            '{"id": "example.feature"}',
        )
        with mock.patch.object(gametest_gate, "run_game_tests") as run:
            code = gametest_gate.main(
                [
                    "--project-dir",
                    str(self.test_dir),
                    "--require-tests-if-contracts",
                    "--run-if-present",
                ]
            )

        self.assertEqual(1, code)
        run.assert_not_called()

    def test_nonfinite_timeout_is_rejected_before_launch(self):
        self.make_wrapper()
        with mock.patch.object(gametest_gate.subprocess, "Popen") as launch:
            with self.assertRaises(ValueError):
                gametest_gate.run_game_tests(
                    self.test_dir, timeout_seconds=float("nan")
                )
        launch.assert_not_called()

    def test_parser_accepts_only_complete_all_green_run(self):
        result = gametest_gate.parse_gametest_output(
            PASS_OUTPUT,
            command=["gradlew", "runGameTestServer"],
            returncode=0,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1.25,
            tail_lines=4,
        )

        self.assertTrue(result.passed)
        self.assertEqual("passed", result.status)
        self.assertEqual(2, result.total_tests)
        self.assertEqual(2, result.running_tests)
        self.assertEqual(2, result.complete_tests)
        self.assertEqual(2, result.required_passed_tests)
        self.assertTrue(result.count_consistent)
        self.assertEqual("console_aggregate", result.evidence_level)
        self.assertTrue(result.completion_marker)
        self.assertTrue(result.required_passed_marker)
        self.assertLessEqual(len(result.output_tail), 4)

    def test_parser_rejects_required_failure(self):
        output = """
        3 tests are now running at position 0, 0, 0!
        ========= 3 GAME TESTS COMPLETE IN 1 s ======================
        1 required tests failed :(
        """
        result = gametest_gate.parse_gametest_output(
            output,
            command=["gradlew"],
            returncode=1,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1,
        )
        self.assertFalse(result.passed)
        self.assertEqual(1, result.required_failures)
        self.assertIn("required", result.reason)

    def test_parser_rejects_optional_failure_despite_zero_exit(self):
        output = """
        ========= 2 GAME TESTS COMPLETE IN 1 s ======================
        All 2 required tests passed :)
        1 optional tests failed
        """
        result = gametest_gate.parse_gametest_output(
            output,
            command=["gradlew"],
            returncode=0,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1,
        )
        self.assertFalse(result.passed)
        self.assertEqual(1, result.optional_failures)
        self.assertIn("optional", result.reason)

    def test_parser_fails_closed_on_unrecognized_success_output(self):
        result = gametest_gate.parse_gametest_output(
            "BUILD SUCCESSFUL",
            command=["gradlew"],
            returncode=0,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1,
        )
        self.assertFalse(result.passed)
        self.assertEqual("unparsed", result.status)

    def test_parser_rejects_discovery_runtime_count_mismatch(self):
        result = gametest_gate.parse_gametest_output(
            PASS_OUTPUT,
            command=["gradlew"],
            returncode=0,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1,
            discovered_tests=3,
        )

        self.assertFalse(result.passed)
        self.assertEqual("failed", result.status)
        self.assertFalse(result.count_consistent)
        self.assertEqual(3, result.discovered_tests)
        self.assertIn("counts disagree", result.reason)

    def test_runtime_event_stream_binds_exact_passed_symbol_set(self):
        self.write(
            "src/main/java/com/example/RuntimeTests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("example")
            public class RuntimeTests {
                @GameTest
                public static void behavior(GameTestHelper helper) {}
            }
            """,
        )
        discovery = gametest_gate.discover_gametests(self.test_dir)
        occurrence = replace(
            discovery.tests[0],
            bytecode_verified=True,
            bytecode_path=(
                "build/classes/java/main/com/example/RuntimeTests.class"
            ),
            bytecode_sha256="a" * 64,
        )
        discovery = gametest_gate.DiscoveryResult(
            project_dir=discovery.project_dir,
            scanned_files=discovery.scanned_files,
            tests=[occurrence],
            errors=[],
        )
        nonce = "nonce"
        events = [
            {
                "protocol": gametest_gate.RUNTIME_EVENT_PROTOCOL,
                "nonce": nonce,
                "sequence": 1,
                "event": "run_started",
            },
            {
                "protocol": gametest_gate.RUNTIME_EVENT_PROTOCOL,
                "nonce": nonce,
                "sequence": 2,
                "event": "test_passed",
                "test_name": "runtimetests.behavior",
                "required": True,
                "detail": "",
            },
            {
                "protocol": gametest_gate.RUNTIME_EVENT_PROTOCOL,
                "nonce": nonce,
                "sequence": 3,
                "event": "run_finished",
                "passed": 1,
                "failed": 0,
            },
        ]
        event_path = self.write(
            "events.jsonl",
            "".join(json.dumps(event) + "\n" for event in events),
        )

        evidence = gametest_gate.validate_runtime_events(
            event_path, nonce=nonce, discovery=discovery
        )

        self.assertTrue(evidence.passed)
        self.assertEqual(
            ("com.example.RuntimeTests#behavior",),
            evidence.passed_symbols,
        )
        self.assertEqual((), evidence.failed_symbols)
        self.assertEqual(nonce, evidence.nonce)
        self.assertEqual(
            event_path.read_bytes().decode("utf-8"),
            evidence.raw_jsonl,
        )
        self.assertEqual(events, list(evidence.canonical_events))
        self.assertRegex(
            evidence.canonical_stream_sha256 or "",
            r"^[0-9a-f]{64}$",
        )

    def test_reporter_control_digest_binds_all_three_control_files(self):
        destination = (
            self.test_dir
            / ".agents"
            / "gates"
            / "runtime"
            / "gametest-reporter"
        )
        shutil.copytree(
            GATES_DIR / "runtime" / "gametest-reporter",
            destination,
        )
        first_digest, first_files = (
            gametest_gate.reporter_control_attestation(self.test_dir)
        )
        self.assertEqual(
            [
                (
                    "src/dev/codex/gametest/"
                    "TrustedGameTestReporterMod.java"
                ),
                "META-INF/neoforge.mods.toml",
                "inject.init.gradle",
            ],
            [item["path"] for item in first_files],
        )

        metadata = destination / "META-INF" / "neoforge.mods.toml"
        metadata.write_text(
            metadata.read_text(encoding="utf-8")
            + "\n# control drift\n",
            encoding="utf-8",
        )
        second_digest, second_files = (
            gametest_gate.reporter_control_attestation(self.test_dir)
        )

        self.assertNotEqual(first_digest, second_digest)
        self.assertNotEqual(first_files, second_files)

    def test_console_markers_cannot_replace_runtime_symbol_events(self):
        execution = gametest_gate.parse_gametest_output(
            ONE_TEST_PASS_OUTPUT,
            command=["gradlew", "runGameTestServer"],
            returncode=0,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1,
            discovered_tests=1,
        )
        evidence = gametest_gate.RuntimeEventEvidence(
            False,
            "runtime event stream is missing",
            None,
            None,
        )

        bound = gametest_gate.bind_runtime_evidence(
            execution, evidence, reporter_jar_sha256="a" * 64
        )

        self.assertFalse(bound.passed)
        self.assertEqual("failed", bound.status)
        self.assertEqual("untrusted", bound.evidence_level)

    def test_run_uses_exact_gradle_task_and_flags(self):
        self.make_wrapper()
        created: list[FakeProcess] = []

        def factory(command, **kwargs):
            process = FakeProcess(
                command, output=PASS_OUTPUT, returncode=0, **kwargs
            )
            created.append(process)
            return process

        result = gametest_gate.run_game_tests(
            self.test_dir, timeout_seconds=5, popen_factory=factory
        )

        self.assertTrue(result.passed)
        self.assertEqual(1, len(created))
        self.assertEqual(
            [
                str(self.test_dir.resolve() / self.make_wrapper().name),
                "runGameTestServer",
                "--no-daemon",
                "--console=plain",
            ],
            created[0].command,
        )
        if os.name == "nt":
            self.assertIn("creationflags", created[0].kwargs)
        else:
            self.assertTrue(created[0].kwargs["start_new_session"])

    def test_timeout_terminates_process_tree(self):
        self.make_wrapper()
        created: list[FakeProcess] = []

        def factory(command, **kwargs):
            process = FakeProcess(
                command,
                output="Started game test server\n",
                timeout_once=True,
                returncode=-9,
                **kwargs,
            )
            created.append(process)
            return process

        with mock.patch.object(
            gametest_gate, "terminate_process_tree"
        ) as terminate:
            result = gametest_gate.run_game_tests(
                self.test_dir, timeout_seconds=0.01, popen_factory=factory
            )

        self.assertFalse(result.passed)
        self.assertTrue(result.timed_out)
        self.assertTrue(result.termination_attempted)
        terminate.assert_called_once_with(created[0])

    def test_interrupt_terminates_process_tree_before_propagating(self):
        self.make_wrapper()

        class InterruptProcess:
            pid = 4242
            returncode = None

            def __init__(self):
                self.wait_count = 0

            def wait(self, timeout=None):
                self.wait_count += 1
                if self.wait_count == 1:
                    raise KeyboardInterrupt
                self.returncode = -9
                return self.returncode

            def poll(self):
                return self.returncode

            def kill(self):
                self.returncode = -9

        process = InterruptProcess()

        def factory(command, **kwargs):
            kwargs["stdout"].write("GameTest starting\n")
            kwargs["stdout"].flush()
            return process

        with mock.patch.object(
            gametest_gate, "terminate_process_tree"
        ) as terminate:
            with self.assertRaises(KeyboardInterrupt):
                gametest_gate.run_game_tests(
                    self.test_dir,
                    timeout_seconds=5,
                    popen_factory=factory,
                )

        terminate.assert_called_once_with(process)
        self.assertEqual(2, process.wait_count)

    def test_process_tree_cleanup_is_platform_specific(self):
        process = mock.Mock()
        process.pid = 4242
        process.poll.return_value = None

        if os.name == "nt":
            with mock.patch.object(gametest_gate.subprocess, "run") as run:
                run.return_value.returncode = 0
                gametest_gate.terminate_process_tree(process)
            self.assertEqual(
                ["taskkill", "/PID", "4242", "/T", "/F"],
                run.call_args.args[0],
            )
        else:
            with mock.patch.object(gametest_gate.os, "killpg") as killpg:
                gametest_gate.terminate_process_tree(process)
            killpg.assert_called_once_with(4242, gametest_gate.signal.SIGKILL)

    def test_main_writes_json_report_without_real_gradle(self):
        self.write(
            "src/main/java/com/example/Tests.java",
            """
            package com.example;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("example")
            public class Tests {
                @GameTest
                public static void behavior(GameTestHelper helper) {}
            }
            """,
        )
        report_path = self.test_dir / "reports" / "gametest.json"
        execution = gametest_gate.parse_gametest_output(
            ONE_TEST_PASS_OUTPUT,
            command=["gradlew", "runGameTestServer"],
            returncode=0,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=1,
        )
        execution = replace(
            execution,
            evidence_level="runtime_symbol_set",
            runtime_events_verified=True,
            reporter_protocol=gametest_gate.RUNTIME_EVENT_PROTOCOL,
            reporter_jar_sha256="a" * 64,
            event_stream_sha256="b" * 64,
            executed_symbols=("com.example.Tests#behavior",),
            passed_symbols=("com.example.Tests#behavior",),
        )

        with mock.patch.object(
            gametest_gate,
            "run_trusted_game_tests",
            side_effect=lambda _project, discovery, **_kwargs: (
                discovery,
                execution,
            ),
        ):
            code = gametest_gate.main(
                [
                    "--project-dir",
                    str(self.test_dir),
                    "--require-tests",
                    "--run",
                    "--json-report",
                    str(report_path),
                ]
            )

        self.assertEqual(0, code)
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(2, report["schema_version"])
        self.assertEqual(1, report["discovery"]["count"])
        self.assertEqual(
            "com.example.Tests#behavior",
            report["discovery"]["tests"][0]["symbol"],
        )
        self.assertTrue(
            report["discovery"]["tests"][0]["signature_valid"]
        )
        self.assertTrue(report["execution"]["passed"])
        self.assertEqual(
            "runtime_symbol_set", report["execution"]["evidence_level"]
        )
        self.assertTrue(report["execution"]["count_consistent"])
        self.assertTrue(report["result"]["passed"])
        self.assertTrue(report["result"]["evidence_satisfied"])
        self.assertEqual("passed", report["result"]["status"])
        self.assertEqual(
            gametest_gate.ATTESTATION_SCOPE,
            report["attestation_scope"],
        )
        self.assertEqual(
            gametest_gate.TAMPER_RESISTANCE,
            report["tamper_resistance"],
        )

    def test_reference_host_probe_needs_explicit_require_tests_opt_in(self):
        self.write(
            (
                "src/main/java/dev/modstudio/referencehost/"
                "ReferenceHostGameTests.java"
            ),
            """
            package dev.modstudio.referencehost;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.neoforged.neoforge.gametest.GameTestHolder;
            @GameTestHolder("example")
            public class ReferenceHostGameTests {
                @GameTest
                public static void infrastructure(GameTestHelper helper) {}
            }
            """,
        )
        discovery = gametest_gate.discover_gametests(self.test_dir)
        self.assertEqual([], discovery.errors)
        self.assertEqual(1, discovery.count)
        self.assertEqual(0, discovery.feature_count)
        self.assertEqual(1, discovery.infrastructure_probe_count)
        self.assertEqual(
            gametest_gate.TEST_CLASS_INFRASTRUCTURE_PROBE,
            discovery.tests[0].classification,
        )

        default_report = self.test_dir / "reports" / "default.json"
        default_code = gametest_gate.main(
            [
                "--project-dir",
                str(self.test_dir),
                "--require-tests",
                "--json-report",
                str(default_report),
            ]
        )
        default_payload = json.loads(
            default_report.read_text(encoding="utf-8")
        )
        self.assertEqual(1, default_code)
        self.assertFalse(default_payload["result"]["command_ok"])
        self.assertTrue(default_payload["policy"]["reference_host_only"])
        self.assertFalse(
            default_payload["policy"]["allow_reference_host_only"]
        )

        opted_report = self.test_dir / "reports" / "opted-in.json"
        opted_code = gametest_gate.main(
            [
                "--project-dir",
                str(self.test_dir),
                "--require-tests",
                "--allow-reference-host-only",
                "--json-report",
                str(opted_report),
            ]
        )
        opted_payload = json.loads(
            opted_report.read_text(encoding="utf-8")
        )
        self.assertEqual(0, opted_code)
        self.assertTrue(opted_payload["result"]["command_ok"])
        self.assertTrue(
            opted_payload["policy"]["allow_reference_host_only"]
        )

    def test_scaffold_is_generic_and_fails_until_implemented(self):
        scaffold = (
            PROJECT_DIR
            / ".agents"
            / "scaffolds"
            / "gametest"
            / "FeatureGameTests.java.template"
        )
        content = scaffold.read_text(encoding="utf-8")
        self.assertNotIn("tutorialmod", content.lower())
        self.assertIn("{{MOD_GROUP}}", content)
        self.assertIn("{{MODID}}", content)
        self.assertIn("@GameTestHolder", content)
        self.assertNotIn("@PrefixGameTestTemplate(false)", content)
        self.assertIn("@GameTest(", content)
        self.assertIn("helper.fail(", content)

        instantiated = (
            content.replace("{{MOD_GROUP}}", "com.example")
            .replace("{{MODID}}", "example")
            .replace("{{TEMPLATE_NAME}}", "feature")
        )
        self.write(
            "src/main/java/com/example/gametest/FeatureGameTests.java",
            instantiated,
        )
        discovery = gametest_gate.discover_gametests(self.test_dir)
        self.assertEqual([], discovery.errors)
        self.assertEqual(1, discovery.count)
        self.assertEqual(
            "featuregametests.featurecontract",
            discovery.tests[0].runtime_name,
        )


if __name__ == "__main__":
    unittest.main()
