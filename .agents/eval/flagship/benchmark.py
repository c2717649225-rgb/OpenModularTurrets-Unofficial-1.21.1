#!/usr/bin/env python3
"""Validate and summarize repeatable flagship-mod AI benchmark runs.

This file deliberately does not invoke an AI client. The toolkit owns the
scenario contract and scoring format; the caller owns model credentials and
the agent runtime. Keeping that boundary makes results comparable across
Codex, Gemini, Claude, local models, and future clients.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_SUITE = SCRIPT_DIR / "suite.json"

RUN_FIELDS: dict[str, type | tuple[type, ...]] = {
    "run_id": str,
    "scenario_id": str,
    "model": str,
    "model_version": str,
    "agent_runtime": str,
    "reasoning_effort": str,
    "tool_profile": str,
    "toolkit_version": str,
    "commit_sha": str,
    "result": str,
    "p0_escapes": int,
    "repair_loops": int,
    "behavior_tests_total": int,
    "behavior_tests_passed": int,
    "prior_behaviors_checked": int,
    "regressions": int,
    "human_minutes": (int, float),
    "gate_evidence": list,
}
RESULT_VALUES = {"pass", "partial", "fail"}


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8", errors="strict"))
    except FileNotFoundError as exc:
        raise ValueError(f"file not found: {path}") from exc
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid JSON in {path}: {exc}") from exc


def validate_suite(path: Path) -> tuple[dict[str, Any], list[str]]:
    raw = load_json(path)
    errors: list[str] = []
    if not isinstance(raw, dict):
        return {}, ["suite root must be an object"]

    for field in (
        "schema_version",
        "suite_id",
        "minimum_independent_runs",
        "release_thresholds",
        "scenarios",
    ):
        if field not in raw:
            errors.append(f"suite missing `{field}`")

    scenarios = raw.get("scenarios", [])
    if not isinstance(scenarios, list) or not scenarios:
        errors.append("suite.scenarios must be a non-empty array")
        scenarios = []

    seen: set[str] = set()
    base = path.parent
    for index, scenario in enumerate(scenarios):
        label = f"scenarios[{index}]"
        if not isinstance(scenario, dict):
            errors.append(f"{label} must be an object")
            continue
        scenario_id = scenario.get("id")
        if not isinstance(scenario_id, str) or not scenario_id:
            errors.append(f"{label}.id must be a non-empty string")
        elif scenario_id in seen:
            errors.append(f"duplicate scenario id `{scenario_id}`")
        else:
            seen.add(scenario_id)
        spec = scenario.get("spec")
        if not isinstance(spec, str) or not spec:
            errors.append(f"{label}.spec must be a non-empty path")
        elif not (base / spec).is_file():
            errors.append(f"{label}.spec does not exist: {spec}")
        gates = scenario.get("required_gates")
        if (
            not isinstance(gates, list)
            or not gates
            or not all(isinstance(value, str) and value for value in gates)
        ):
            errors.append(f"{label}.required_gates must be non-empty strings")
        min_runs = scenario.get(
            "minimum_independent_runs",
            raw.get("minimum_independent_runs"),
        )
        if not isinstance(min_runs, int) or isinstance(min_runs, bool) or min_runs < 1:
            errors.append(
                f"{label}.minimum_independent_runs must resolve to integer >= 1"
            )

    thresholds = raw.get("release_thresholds")
    if not isinstance(thresholds, dict):
        errors.append("suite.release_thresholds must be an object")
    else:
        for field in (
            "maximum_p0_escapes",
            "minimum_post_repair_pass_rate",
            "minimum_regression_retention_rate",
            "maximum_repair_loops_per_run",
        ):
            if field not in thresholds:
                errors.append(f"release_thresholds missing `{field}`")

    return raw, errors


def validate_runs(
    payload: Any, suite: dict[str, Any]
) -> tuple[list[dict[str, Any]], list[str]]:
    if isinstance(payload, dict):
        payload = payload.get("runs")
    if not isinstance(payload, list):
        return [], ["results must be an array or an object with a `runs` array"]

    errors: list[str] = []
    runs: list[dict[str, Any]] = []
    scenario_ids = {
        item["id"]
        for item in suite.get("scenarios", [])
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }
    seen_run_ids: set[str] = set()

    for index, run in enumerate(payload):
        label = f"runs[{index}]"
        if not isinstance(run, dict):
            errors.append(f"{label} must be an object")
            continue
        for field, expected in RUN_FIELDS.items():
            value = run.get(field)
            if isinstance(value, bool) or not isinstance(value, expected):
                expected_name = (
                    "number" if isinstance(expected, tuple) else expected.__name__
                )
                errors.append(f"{label}.{field} must be {expected_name}")
            elif expected is str and not value.strip():
                errors.append(f"{label}.{field} must not be blank")

        run_id = run.get("run_id")
        if isinstance(run_id, str):
            if run_id in seen_run_ids:
                errors.append(f"duplicate run_id `{run_id}`")
            seen_run_ids.add(run_id)

        scenario_id = run.get("scenario_id")
        if isinstance(scenario_id, str) and scenario_id not in scenario_ids:
            errors.append(f"{label}.scenario_id unknown: {scenario_id}")
        if run.get("result") not in RESULT_VALUES:
            errors.append(
                f"{label}.result must be one of {sorted(RESULT_VALUES)}"
            )

        for field in (
            "p0_escapes",
            "repair_loops",
            "behavior_tests_total",
            "behavior_tests_passed",
            "prior_behaviors_checked",
            "regressions",
            "human_minutes",
        ):
            value = run.get(field)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                if value < 0:
                    errors.append(f"{label}.{field} must be >= 0")
        total = run.get("behavior_tests_total")
        passed = run.get("behavior_tests_passed")
        if isinstance(total, int) and isinstance(passed, int):
            if total < 1:
                errors.append(f"{label}.behavior_tests_total must be >= 1")
            if passed > total:
                errors.append(
                    f"{label}.behavior_tests_passed cannot exceed total"
                )
        checked = run.get("prior_behaviors_checked")
        regressions = run.get("regressions")
        if isinstance(checked, int) and isinstance(regressions, int):
            if regressions > checked:
                errors.append(
                    f"{label}.regressions cannot exceed prior_behaviors_checked"
                )
        evidence = run.get("gate_evidence")
        if isinstance(evidence, list) and (
            not evidence
            or not all(isinstance(item, str) and item.strip() for item in evidence)
        ):
            errors.append(
                f"{label}.gate_evidence must contain non-empty evidence strings"
            )
        runs.append(run)

    configuration_fields = (
        "model",
        "model_version",
        "agent_runtime",
        "reasoning_effort",
        "tool_profile",
        "toolkit_version",
    )
    configurations = {
        tuple(run.get(field) for field in configuration_fields)
        for run in runs
    }
    if len(configurations) > 1:
        errors.append(
            "one results file must contain exactly one pinned model/runtime/"
            "reasoning/toolkit configuration; report comparisons separately"
        )
    return runs, errors


def rate(numerator: int | float, denominator: int | float) -> float:
    return float(numerator) / float(denominator) if denominator else 0.0


def summarize(
    runs: list[dict[str, Any]], suite: dict[str, Any]
) -> dict[str, Any]:
    by_scenario: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for run in runs:
        by_scenario[run["scenario_id"]].append(run)

    passed = sum(run["result"] == "pass" for run in runs)
    first_pass = sum(
        run["result"] == "pass" and run["repair_loops"] == 0 for run in runs
    )
    behavior_total = sum(run["behavior_tests_total"] for run in runs)
    behavior_passed = sum(run["behavior_tests_passed"] for run in runs)
    prior_checked = sum(run["prior_behaviors_checked"] for run in runs)
    regressions = sum(run["regressions"] for run in runs)

    scenario_rows: dict[str, Any] = {}
    scenario_specs = {
        scenario["id"]: scenario for scenario in suite["scenarios"]
    }
    for scenario_id, spec in scenario_specs.items():
        scenario_runs = by_scenario.get(scenario_id, [])
        minimum = spec.get(
            "minimum_independent_runs",
            suite["minimum_independent_runs"],
        )
        scenario_rows[scenario_id] = {
            "runs": len(scenario_runs),
            "minimum_runs": minimum,
            "enough_runs": len(scenario_runs) >= minimum,
            "pass_rate": rate(
                sum(run["result"] == "pass" for run in scenario_runs),
                len(scenario_runs),
            ),
            "p0_escapes": sum(run["p0_escapes"] for run in scenario_runs),
        }

    summary = {
        "suite_id": suite["suite_id"],
        "runs": len(runs),
        "models": sorted(
            {
                f"{run['model']}@{run['model_version']}"
                for run in runs
            }
        ),
        "post_repair_pass_rate": rate(passed, len(runs)),
        "first_pass_rate": rate(first_pass, len(runs)),
        "behavior_test_pass_rate": rate(behavior_passed, behavior_total),
        "p0_escapes": sum(run["p0_escapes"] for run in runs),
        "regression_retention_rate": (
            1.0 - rate(regressions, prior_checked)
            if prior_checked
            else 1.0
        ),
        "average_repair_loops": (
            statistics.fmean(run["repair_loops"] for run in runs)
            if runs
            else 0.0
        ),
        "average_human_minutes": (
            statistics.fmean(run["human_minutes"] for run in runs)
            if runs
            else 0.0
        ),
        "scenarios": scenario_rows,
    }

    thresholds = suite["release_thresholds"]
    failures: list[str] = []
    if summary["p0_escapes"] > thresholds["maximum_p0_escapes"]:
        failures.append(
            f"P0 escapes {summary['p0_escapes']} > "
            f"{thresholds['maximum_p0_escapes']}"
        )
    if (
        summary["post_repair_pass_rate"]
        < thresholds["minimum_post_repair_pass_rate"]
    ):
        failures.append(
            "post-repair pass rate "
            f"{summary['post_repair_pass_rate']:.1%} < "
            f"{thresholds['minimum_post_repair_pass_rate']:.1%}"
        )
    if (
        summary["regression_retention_rate"]
        < thresholds["minimum_regression_retention_rate"]
    ):
        failures.append(
            "regression retention "
            f"{summary['regression_retention_rate']:.1%} < "
            f"{thresholds['minimum_regression_retention_rate']:.1%}"
        )
    max_loops = thresholds["maximum_repair_loops_per_run"]
    over_loop_runs = [
        run["run_id"] for run in runs if run["repair_loops"] > max_loops
    ]
    if over_loop_runs:
        failures.append(
            f"{len(over_loop_runs)} run(s) exceeded {max_loops} repair loops"
        )
    for scenario_id, row in scenario_rows.items():
        if not row["enough_runs"]:
            failures.append(
                f"{scenario_id} has {row['runs']}/{row['minimum_runs']} runs"
            )
    summary["threshold_failures"] = failures
    summary["release_ready"] = not failures
    return summary


def print_report(summary: dict[str, Any]) -> None:
    print(f"=== {summary['suite_id']} benchmark ===")
    print(f"Runs: {summary['runs']}")
    print(f"Models: {', '.join(summary['models']) or '(none)'}")
    print(f"First-pass: {summary['first_pass_rate']:.1%}")
    print(f"Post-repair pass: {summary['post_repair_pass_rate']:.1%}")
    print(f"Behavior assertions: {summary['behavior_test_pass_rate']:.1%}")
    print(f"P0 escapes: {summary['p0_escapes']}")
    print(
        "Regression retention: "
        f"{summary['regression_retention_rate']:.1%}"
    )
    print(f"Average repair loops: {summary['average_repair_loops']:.2f}")
    print(f"Average human minutes: {summary['average_human_minutes']:.1f}")
    print("Scenarios:")
    for scenario_id, row in summary["scenarios"].items():
        status = "enough" if row["enough_runs"] else "insufficient"
        print(
            f"  {scenario_id}: runs={row['runs']}/{row['minimum_runs']} "
            f"pass={row['pass_rate']:.1%} p0={row['p0_escapes']} {status}"
        )
    if summary["release_ready"]:
        print("RESULT: PASS - flagship benchmark thresholds met")
    else:
        print("RESULT: FAIL")
        for failure in summary["threshold_failures"]:
            print(f"  - {failure}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate or report the model-agnostic flagship eval suite."
    )
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser("validate-suite")
    validate.add_argument("--suite", type=Path, default=DEFAULT_SUITE)

    report = sub.add_parser("report")
    report.add_argument("results", type=Path)
    report.add_argument("--suite", type=Path, default=DEFAULT_SUITE)
    report.add_argument("--json-output", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        suite, suite_errors = validate_suite(args.suite)
    except ValueError as exc:
        print(f"ERROR: {exc}")
        return 2
    if suite_errors:
        for error in suite_errors:
            print(f"ERROR: {error}")
        return 2
    if args.command == "validate-suite":
        print(
            f"PASS: {suite['suite_id']} has "
            f"{len(suite['scenarios'])} valid scenarios"
        )
        return 0

    try:
        payload = load_json(args.results)
    except ValueError as exc:
        print(f"ERROR: {exc}")
        return 2
    runs, run_errors = validate_runs(payload, suite)
    if run_errors:
        for error in run_errors:
            print(f"ERROR: {error}")
        return 2

    summary = summarize(runs, suite)
    print_report(summary)
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    return 0 if summary["release_ready"] else 1


if __name__ == "__main__":
    sys.exit(main())
