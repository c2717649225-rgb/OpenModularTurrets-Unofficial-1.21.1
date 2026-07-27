#!/usr/bin/env python3
"""Standard-library tests for the quality-profile orchestrator."""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


GATES_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(GATES_DIR))

import pipeline


class TestPipeline(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="pipeline_gate_"))

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_fast_profile_excludes_expensive_runtime_gates(self):
        plan = pipeline.build_plan(self.temp_dir, "fast")
        quality = plan[-1].command
        self.assertIn("--with-static", quality)
        self.assertNotIn("--with-contracts", quality)
        self.assertNotIn("--with-data", quality)
        self.assertNotIn("--with-gametest", quality)
        self.assertNotIn("--with-server", quality)

    def test_major_profile_requires_contract_data_assets_and_gametest(self):
        contract_root = self.temp_dir / "feature-contracts"
        plan = pipeline.build_plan(
            self.temp_dir,
            "major",
            contract_root=contract_root,
            gametest_timeout=123,
        )
        quality = plan[-1].command
        for option in (
            "--with-contracts",
            "--with-data",
            "--with-assets",
            "--strict-datagen-layout",
            "--warnings-as-errors",
            "--with-gametest",
        ):
            self.assertIn(option, quality)
        self.assertIn(str(contract_root), quality)
        self.assertIn("123", quality)
        self.assertNotIn("--verify-data-clean", quality)
        self.assertNotIn("--with-server", quality)

    def test_release_adds_clean_data_server_and_suite_integrity(self):
        plan = pipeline.build_plan(self.temp_dir, "release")
        quality = plan[-2].command
        self.assertIn("--verify-data-clean", quality)
        self.assertIn("--with-server", quality)
        self.assertEqual("flagship benchmark suite integrity", plan[-1].name)
        self.assertEqual("validate-suite", plan[-1].command[-1])

    def test_dry_run_writes_report_without_launching(self):
        report = self.temp_dir / "reports" / "pipeline.json"
        with mock.patch.object(pipeline, "run_step") as run_step:
            code = pipeline.main(
                [
                    "--project-dir",
                    str(self.temp_dir),
                    "--profile",
                    "major",
                    "--dry-run",
                    "--json-report",
                    str(report),
                ]
            )

        self.assertEqual(0, code)
        run_step.assert_not_called()
        payload = json.loads(report.read_text(encoding="utf-8"))
        self.assertFalse(payload["passed"])
        self.assertEqual("planned", payload["status"])
        self.assertTrue(payload["dry_run"])
        self.assertTrue(payload["steps"])
        self.assertTrue(
            all(step["status"] == "planned" for step in payload["steps"])
        )

    def test_execution_stops_after_first_failure(self):
        plan_results = [
            pipeline.StepResult(
                name="documentation index",
                command=["python", "check_doc_index.py"],
                returncode=1,
                duration_seconds=0.1,
                status="failed",
                output_tail=["failure"],
            )
        ]
        with mock.patch.object(
            pipeline, "run_step", side_effect=plan_results
        ) as run_step:
            code = pipeline.main(
                [
                    "--project-dir",
                    str(self.temp_dir),
                    "--profile",
                    "release",
                ]
            )

        self.assertEqual(1, code)
        self.assertEqual(1, run_step.call_count)

    def test_nonfinite_timeouts_are_rejected(self):
        with self.assertRaises(SystemExit) as gametest_error:
            pipeline.main(
                [
                    "--project-dir",
                    str(self.temp_dir),
                    "--gametest-timeout",
                    "nan",
                    "--dry-run",
                ]
            )
        self.assertEqual(2, gametest_error.exception.code)

        with self.assertRaises(SystemExit) as step_error:
            pipeline.main(
                [
                    "--project-dir",
                    str(self.temp_dir),
                    "--step-timeout",
                    "inf",
                    "--dry-run",
                ]
            )
        self.assertEqual(2, step_error.exception.code)

    def test_process_table_descendants_cross_session_boundaries(self):
        fixture = (
            "100 1 Mon Jul 27 10:00:00 2026\n"
            "101 100 Mon Jul 27 10:00:01 2026\n"
            "102 101 Mon Jul 27 10:00:02 2026\n"
            "200 1 Mon Jul 27 10:00:03 2026\n"
        )
        table, starts = pipeline.parse_process_table(fixture)
        with mock.patch.object(
            pipeline.subprocess,
            "run",
            return_value=mock.Mock(
                returncode=0,
                stdout=fixture,
            ),
        ), mock.patch.object(
            pipeline,
            "stable_process_identity",
            side_effect=lambda pid, ps_start="": "id:" + ps_start,
        ):
            descendants = pipeline.process_descendants(100)
        self.assertEqual({100, 101, 102}, set(descendants))
        self.assertEqual({101}, table[100])
        self.assertTrue(starts[102].startswith("Mon Jul 27"))

    def test_identity_mismatch_prevents_raw_pid_kill(self):
        with (
            mock.patch.object(
                pipeline,
                "read_process_table",
                return_value=({}, {4242: "new-start"}),
            ),
            mock.patch.object(
                pipeline,
                "stable_process_identity",
                return_value="ps-lstart:new-start",
            ),
            mock.patch.object(pipeline.os, "kill") as kill,
        ):
            pipeline.force_kill_identities(
                {4242: "ps-lstart:old-start"}, {}
            )
        kill.assert_not_called()

    @unittest.skipUnless(
        sys.platform.startswith("linux"),
        "Linux nested-session process cleanup integration",
    )
    def test_timeout_kills_descendant_that_created_a_new_session(self):
        child_code = (
            "import os,time;"
            "os.setsid();"
            "pid=os.getpid();"
            "raw=open('/proc/'+str(pid)+'/stat').read();"
            "start=raw[raw.rfind(')')+1:].split()[19];"
            "print('DETACHED_PID='+str(pid)+' START_TOKEN='+start,flush=True);"
            "time.sleep(60)"
        )
        parent_code = (
            "import subprocess,sys,time;"
            "subprocess.Popen([sys.executable,'-c',"
            + repr(child_code)
            + "]);"
            "time.sleep(60)"
        )
        step = pipeline.PlannedStep(
            "nested-session fixture",
            [sys.executable, "-c", parent_code],
        )

        with mock.patch.object(
            pipeline, "GRACEFUL_SHUTDOWN_SECONDS", 0.2
        ):
            result = pipeline.run_step(
                step,
                project_dir=self.temp_dir,
                tail_lines=20,
                timeout_seconds=0.3,
            )

        self.assertEqual("timed_out", result.status)
        detached_lines = [
            line
            for line in result.output_tail
            if line.startswith("DETACHED_PID=")
        ]
        self.assertEqual(1, len(detached_lines), result.output_tail)
        fields = dict(
            item.split("=", 1) for item in detached_lines[0].split()
        )
        detached_pid = int(fields["DETACHED_PID"])
        detached_identity = "proc-start:" + fields["START_TOKEN"]

        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if (
                pipeline.stable_process_identity(detached_pid)
                != detached_identity
            ):
                break
            time.sleep(0.05)
        else:
            pipeline.force_kill_identities(
                {detached_pid: detached_identity}, {}
            )
            self.fail(f"detached descendant {detached_pid} survived timeout")


if __name__ == "__main__":
    unittest.main()
