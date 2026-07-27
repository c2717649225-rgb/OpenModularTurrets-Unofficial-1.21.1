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
        self.assertEqual("aggregate_set", result.evidence_level)
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

        with mock.patch.object(
            gametest_gate, "run_game_tests", return_value=execution
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
            "aggregate_set", report["execution"]["evidence_level"]
        )
        self.assertTrue(report["execution"]["count_consistent"])
        self.assertTrue(report["result"]["passed"])
        self.assertTrue(report["result"]["evidence_satisfied"])
        self.assertEqual("passed", report["result"]["status"])

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
        self.assertIn("@GameTest(", content)
        self.assertIn("helper.fail(", content)


if __name__ == "__main__":
    unittest.main()
