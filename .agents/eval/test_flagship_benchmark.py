from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

FLAGSHIP_DIR = Path(__file__).resolve().parent / "flagship"
sys.path.insert(0, str(FLAGSHIP_DIR))
import benchmark  # noqa: E402


class FlagshipBenchmarkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.suite, errors = benchmark.validate_suite(
            FLAGSHIP_DIR / "suite.json"
        )
        if errors:
            raise AssertionError(errors)

    def make_run(
        self, scenario_id: str, index: int, **updates: object
    ) -> dict[str, object]:
        run: dict[str, object] = {
            "run_id": f"{scenario_id}-{index}",
            "scenario_id": scenario_id,
            "model": "test-model",
            "model_version": "pinned",
            "agent_runtime": "test-runtime",
            "reasoning_effort": "fixed",
            "tool_profile": "filesystem+shell+mcp",
            "toolkit_version": "test",
            "commit_sha": "0123456789abcdef",
            "result": "pass",
            "p0_escapes": 0,
            "repair_loops": 1,
            "behavior_tests_total": 10,
            "behavior_tests_passed": 10,
            "prior_behaviors_checked": 10,
            "regressions": 0,
            "human_minutes": 5,
            "gate_evidence": ["L4 PASS"],
        }
        run.update(updates)
        return run

    def test_suite_manifest_resolves_every_scenario(self) -> None:
        _, errors = benchmark.validate_suite(FLAGSHIP_DIR / "suite.json")
        self.assertEqual([], errors)

    def test_complete_clean_matrix_meets_thresholds(self) -> None:
        runs = [
            self.make_run(scenario["id"], index)
            for scenario in self.suite["scenarios"]
            for index in range(5)
        ]
        validated, errors = benchmark.validate_runs(runs, self.suite)
        self.assertEqual([], errors)

        summary = benchmark.summarize(validated, self.suite)

        self.assertTrue(summary["release_ready"])
        self.assertEqual(0, summary["p0_escapes"])
        self.assertEqual(0, len(summary["threshold_failures"]))

    def test_p0_escape_always_blocks_release(self) -> None:
        runs = [
            self.make_run(scenario["id"], index)
            for scenario in self.suite["scenarios"]
            for index in range(5)
        ]
        runs[0]["p0_escapes"] = 1

        summary = benchmark.summarize(runs, self.suite)

        self.assertFalse(summary["release_ready"])
        self.assertTrue(
            any("P0 escapes" in item for item in summary["threshold_failures"])
        )

    def test_invalid_result_record_is_rejected(self) -> None:
        run = self.make_run("I01", 1)
        run["behavior_tests_passed"] = 11
        run["gate_evidence"] = []

        _, errors = benchmark.validate_runs([run], self.suite)

        self.assertTrue(
            any("cannot exceed total" in item for item in errors), errors
        )
        self.assertTrue(
            any("gate_evidence" in item for item in errors), errors
        )

    def test_mixed_model_configurations_are_rejected(self) -> None:
        first = self.make_run("I01", 1)
        second = self.make_run("I01", 2)
        second["reasoning_effort"] = "different"

        _, errors = benchmark.validate_runs([first, second], self.suite)

        self.assertTrue(
            any("one pinned model/runtime" in item for item in errors),
            errors,
        )

    def test_report_command_writes_machine_readable_summary(self) -> None:
        runs = [
            self.make_run(scenario["id"], index)
            for scenario in self.suite["scenarios"]
            for index in range(5)
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            results = root / "runs.json"
            report = root / "report.json"
            results.write_text(
                json.dumps({"runs": runs}), encoding="utf-8"
            )

            code = benchmark.main(
                [
                    "report",
                    str(results),
                    "--suite",
                    str(FLAGSHIP_DIR / "suite.json"),
                    "--json-output",
                    str(report),
                ]
            )

            self.assertEqual(0, code)
            self.assertTrue(report.is_file())
            self.assertTrue(json.loads(report.read_text())["release_ready"])


if __name__ == "__main__":
    unittest.main()
