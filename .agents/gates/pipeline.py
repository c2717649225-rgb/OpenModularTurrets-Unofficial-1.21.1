#!/usr/bin/env python3
"""One-command quality profiles for an autonomous NeoForge mod workflow.

The profile names describe evidence strength, not feature size:

* fast:    compile plus deterministic static checks;
* major:   a feature contract, DataGen/resources, and real GameTests;
* release: major evidence plus clean generated output and dedicated-server boot.

The implementation deliberately delegates domain checks to the existing
fail-closed gates.  This file only fixes their order, records evidence, and
stops at the first failure.
"""
from __future__ import annotations

import sys

if sys.version_info < (3, 10):
    sys.stderr.write(
        f"[ERROR] Python 3.10 or higher is required to run gate scripts. "
        f"Current Python: {sys.version_info.major}.{sys.version_info.minor}\n"
        "Please use: python .agents/run.py ...\n"
    )
    sys.exit(1)

import argparse
import hashlib
import json
import math
import os
import signal
import subprocess
import sys
import tempfile
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, Sequence


SCHEMA_VERSION = 1
PROFILE_NAMES = ("fast", "major", "release")
GRACEFUL_SHUTDOWN_SECONDS = 5.0
LEDGER_ENV_VAR = "TOOLKIT_EVIDENCE_LEDGER"
LEDGER_EVENT_TYPE = "PIPELINE_RESULT"


@dataclass(frozen=True)
class PlannedStep:
    name: str
    command: list[str]


@dataclass
class StepResult:
    name: str
    command: list[str]
    returncode: Optional[int]
    duration_seconds: float
    status: str
    output_tail: list[str]
    timed_out: bool = False


def default_project_dir() -> Path:
    return Path(__file__).resolve().parents[2]


def build_plan(
    project_dir: Path,
    profile: str,
    *,
    contract_root: Optional[Path] = None,
    gametest_timeout: float = 900.0,
    strict_traceability: bool = False,
    allow_reference_host_only: bool = False,
) -> list[PlannedStep]:
    """Build the exact argv plan for a profile without executing it."""
    if profile not in PROFILE_NAMES:
        raise ValueError(f"unknown profile: {profile}")
    if strict_traceability and profile == "fast":
        raise ValueError(
            "strict traceability is only available for major/release profiles"
        )
    if allow_reference_host_only and profile == "fast":
        raise ValueError(
            "reference-host-only evidence is only available for "
            "major/release profiles"
        )

    gates = project_dir / ".agents" / "gates"
    plan: list[PlannedStep] = [
        PlannedStep(
            "documentation index",
            [sys.executable, str(gates / "check_doc_index.py")],
        ),
        PlannedStep(
            "documentation trust metadata",
            [sys.executable, str(gates / "check_doc_meta.py")],
        ),
    ]

    quality_command = [
        sys.executable,
        str(gates / "compile_and_repair.py"),
        "--with-static",
    ]
    if profile in {"major", "release"}:
        quality_command.extend(
            [
                "--with-contracts",
                "--with-data",
                "--with-assets",
                "--strict-datagen-layout",
                "--warnings-as-errors",
                "--with-gametest",
                "--gametest-timeout",
                f"{gametest_timeout:g}",
            ]
        )
        if contract_root is not None:
            quality_command.extend(
                ["--contract-root", str(contract_root)]
            )
        if strict_traceability:
            quality_command.append("--strict-traceability")
        if allow_reference_host_only:
            quality_command.append("--allow-reference-host-only")
    if profile == "release":
        quality_command.extend(["--verify-data-clean", "--with-server"])

    plan.append(PlannedStep(f"{profile} quality gates", quality_command))

    if profile == "release":
        plan.append(
            PlannedStep(
                "flagship benchmark suite integrity",
                [
                    sys.executable,
                    str(
                        project_dir
                        / ".agents"
                        / "eval"
                        / "flagship"
                        / "benchmark.py"
                    ),
                    "validate-suite",
                ],
            )
        )
    return plan


def run_step(
    step: PlannedStep,
    *,
    project_dir: Path,
    tail_lines: int,
    timeout_seconds: float,
) -> StepResult:
    """Run one step with bounded time, process cleanup, and evidence output."""
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise ValueError("timeout_seconds must be finite and greater than zero")

    started = time.monotonic()
    popen_options = {}
    if os.name == "nt":
        popen_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        popen_options["start_new_session"] = True

    timed_out = False
    interrupted = False
    output = ""
    returncode: Optional[int] = None
    with tempfile.TemporaryFile(
        mode="w+t", encoding="utf-8", errors="replace"
    ) as log_file:
        try:
            process = subprocess.Popen(
                step.command,
                cwd=project_dir,
                stdin=subprocess.DEVNULL,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                **popen_options,
            )
        except OSError as error:
            message = f"could not launch step: {error}"
            print(message)
            return StepResult(
                name=step.name,
                command=step.command,
                returncode=None,
                duration_seconds=time.monotonic() - started,
                status="tool_error",
                output_tail=[message],
            )

        try:
            process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            terminate_process_tree(process)
            bounded_wait(process)
        except KeyboardInterrupt:
            interrupted = True
            terminate_process_tree(process)
            bounded_wait(process)
        except BaseException:
            terminate_process_tree(process)
            bounded_wait(process)
            raise
        returncode = 130 if interrupted else process.returncode
        log_file.flush()
        log_file.seek(0)
        output = log_file.read()

    lines = [line.rstrip() for line in output.splitlines()]
    for line in lines:
        print(line)
    tail = [line for line in lines if line][-tail_lines:]
    status = (
        "interrupted"
        if interrupted
        else "timed_out"
        if timed_out
        else "passed"
        if returncode == 0
        else "failed"
    )
    return StepResult(
        name=step.name,
        command=step.command,
        returncode=returncode,
        duration_seconds=time.monotonic() - started,
        status=status,
        output_tail=tail,
        timed_out=timed_out,
    )


def terminate_process_tree(process: subprocess.Popen) -> None:
    if os.name == "nt":
        if process.poll() is not None:
            return
        result = subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode != 0 and process.poll() is None:
            process.kill()
        return

    # Inner gates intentionally create their own sessions for Gradle/Minecraft.
    # Snapshot every descendant before signaling so a re-parented nested
    # session can still be reaped if its Python owner exits first.
    descendants = process_descendants(process.pid)
    pidfds = open_pidfds(descendants)
    if process.poll() is None:
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGINT)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=GRACEFUL_SHUTDOWN_SECONDS)
    except subprocess.TimeoutExpired:
        newer = process_descendants(process.pid)
        descendants.update(newer)
        pidfds.update(
            open_pidfds(
                {
                    pid: identity
                    for pid, identity in newer.items()
                    if pid not in pidfds
                }
            )
        )

    # SIGINT gives compile_and_repair/gametest_gate a chance to run their own
    # cleanup. Any still-live snapshotted process is force-killed through a
    # Linux pidfd when available. Other POSIX systems re-check PID + process
    # start identity immediately before signaling, avoiding delayed raw-PID
    # kills after PID reuse.
    try:
        force_kill_identities(descendants, pidfds)
    finally:
        for descriptor in pidfds.values():
            try:
                os.close(descriptor)
            except OSError:
                pass


def parse_process_table(
    output: str,
) -> tuple[dict[int, set[int]], dict[int, str]]:
    children: dict[int, set[int]] = {}
    starts: dict[int, str] = {}
    for raw_line in output.splitlines():
        parts = raw_line.split(maxsplit=2)
        if len(parts) < 2:
            continue
        try:
            pid, parent_pid = int(parts[0]), int(parts[1])
        except ValueError:
            continue
        children.setdefault(parent_pid, set()).add(pid)
        starts[pid] = parts[2].strip() if len(parts) == 3 else ""
    return children, starts


def read_process_table() -> tuple[dict[int, set[int]], dict[int, str]]:
    try:
        result = subprocess.run(
            ["ps", "-eo", "pid=,ppid=,lstart="],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
            timeout=2,
        )
    except (OSError, subprocess.TimeoutExpired):
        return {}, {}
    if result.returncode != 0:
        return {}, {}
    return parse_process_table(result.stdout)


def stable_process_identity(pid: int, ps_start: str = "") -> str:
    """Return a start-time identity that changes when a PID is reused."""
    stat_path = Path("/proc") / str(pid) / "stat"
    try:
        raw_stat = stat_path.read_text(encoding="ascii", errors="strict")
        close_paren = raw_stat.rfind(")")
        fields_after_comm = raw_stat[close_paren + 1 :].split()
        # fields_after_comm[0] is stat field 3 (state); starttime is field 22.
        if close_paren >= 0 and len(fields_after_comm) > 19:
            return "proc-start:" + fields_after_comm[19]
    except (OSError, UnicodeError):
        pass
    return "ps-lstart:" + ps_start if ps_start else ""


def process_descendants(root_pid: int) -> dict[int, str]:
    children, ps_starts = read_process_table()
    descendant_pids = {root_pid}
    pending = [root_pid]
    while pending:
        parent = pending.pop()
        for child in children.get(parent, set()):
            if child not in descendant_pids:
                descendant_pids.add(child)
                pending.append(child)
    return {
        pid: identity
        for pid in descendant_pids
        if (
            identity := stable_process_identity(
                pid, ps_starts.get(pid, "")
            )
        )
    }


def open_pidfds(identities: dict[int, str]) -> dict[int, int]:
    if not hasattr(os, "pidfd_open") or not hasattr(
        signal, "pidfd_send_signal"
    ):
        return {}
    descriptors: dict[int, int] = {}
    for pid, expected_identity in identities.items():
        try:
            descriptor = os.pidfd_open(pid)
        except (OSError, ProcessLookupError):
            continue
        # pidfd_open is identity-safe, but re-check the snapshot so a process
        # reused between `ps` and pidfd_open is never adopted.
        if stable_process_identity(pid) != expected_identity:
            os.close(descriptor)
            continue
        descriptors[pid] = descriptor
    return descriptors


def force_kill_identities(
    identities: dict[int, str], pidfds: dict[int, int]
) -> None:
    _, current_ps_starts = read_process_table()
    for pid, expected_identity in identities.items():
        if pid == os.getpid():
            continue
        descriptor = pidfds.get(pid)
        if descriptor is not None:
            try:
                signal.pidfd_send_signal(descriptor, signal.SIGKILL)
            except (OSError, ProcessLookupError):
                pass
            continue
        current_identity = stable_process_identity(
            pid, current_ps_starts.get(pid, "")
        )
        if not current_identity or current_identity != expected_identity:
            continue
        try:
            os.kill(pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass


def bounded_wait(process: subprocess.Popen) -> None:
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        try:
            process.kill()
        except OSError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass


def report_payload(
    *,
    project_dir: Path,
    profile: str,
    dry_run: bool,
    started_at: str,
    duration_seconds: float,
    results: list[StepResult],
    passed: bool,
    strict_traceability: bool = False,
) -> dict:
    return {
        "schema_version": SCHEMA_VERSION,
        "gate": "autonomous-mod-studio pipeline",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "started_at_utc": started_at,
        "project_dir": str(project_dir),
        "profile": profile,
        "strict_traceability": strict_traceability,
        "dry_run": dry_run,
        "duration_seconds": round(duration_seconds, 3),
        "status": "planned" if dry_run else "passed" if passed else "failed",
        "passed": passed and not dry_run,
        "steps": [asdict(result) for result in results],
    }


def write_report(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def append_ledger_event(ledger_path: Path, payload: dict, report_path: Path) -> None:
    """Append one PIPELINE_RESULT JSONL event to the evidence ledger.

    The ledger is an append-only best-effort journal: the pipeline report's
    sha256 is recorded so releases can be traced to an exact artifact.  The
    ledger path comes from the TOOLKIT_EVIDENCE_LEDGER environment variable;
    failures here never change the pipeline exit code.
    """
    event = {
        "event_type": LEDGER_EVENT_TYPE,
        "schema_version": SCHEMA_VERSION,
        "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
        "profile": payload["profile"],
        "status": payload["status"],
        "passed": payload["passed"],
        "report_sha256": sha256_file(report_path),
        "project_dir": payload["project_dir"],
    }
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    with open(ledger_path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(event, ensure_ascii=False) + "\n")



def parser() -> argparse.ArgumentParser:
    command_parser = argparse.ArgumentParser(
        description="Run a fail-closed NeoForge quality profile."
    )
    command_parser.add_argument(
        "--profile",
        choices=PROFILE_NAMES,
        default="fast",
        help="evidence profile (default: fast)",
    )
    command_parser.add_argument(
        "--project-dir",
        type=Path,
        default=default_project_dir(),
        help="host Gradle project (default: repository containing .agents)",
    )
    command_parser.add_argument(
        "--contract-root",
        type=Path,
        help="override the default host contract directory (docs/features)",
    )
    command_parser.add_argument(
        "--strict-traceability",
        action="store_true",
        help=(
            "block major/release unless every required v2 criterion maps to "
            "an exact reporter-backed runtime GameTest symbol"
        ),
    )
    command_parser.add_argument(
        "--gametest-timeout",
        type=float,
        default=900.0,
        metavar="SECONDS",
        help="L4 GameTest timeout for major/release profiles (default: 900)",
    )
    command_parser.add_argument(
        "--allow-reference-host-only",
        action="store_true",
        help=(
            "allow the permanent dev.modstudio.referencehost infrastructure "
            "probe to satisfy the GameTest existence requirement; use only "
            "with the isolated reference-host contract"
        ),
    )
    command_parser.add_argument(
        "--tail-lines",
        type=int,
        default=60,
        metavar="N",
        help="non-empty lines retained per step in the JSON report",
    )
    command_parser.add_argument(
        "--step-timeout",
        type=float,
        default=1800.0,
        metavar="SECONDS",
        help="maximum lifetime of each profile step (default: 1800)",
    )
    command_parser.add_argument(
        "--json-report",
        type=Path,
        help="write bounded machine-readable evidence",
    )
    command_parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the exact plan without running commands",
    )
    return command_parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    command_parser = parser()
    args = command_parser.parse_args(argv)
    if (
        not math.isfinite(args.gametest_timeout)
        or args.gametest_timeout <= 0
    ):
        command_parser.error(
            "--gametest-timeout must be finite and greater than zero"
        )
    if not math.isfinite(args.step_timeout) or args.step_timeout <= 0:
        command_parser.error(
            "--step-timeout must be finite and greater than zero"
        )
    if not 1 <= args.tail_lines <= 500:
        command_parser.error("--tail-lines must be between 1 and 500")
    if args.strict_traceability and args.profile == "fast":
        command_parser.error(
            "--strict-traceability requires --profile major or release"
        )
    if args.allow_reference_host_only and args.profile == "fast":
        command_parser.error(
            "--allow-reference-host-only requires --profile major or release"
        )

    project_dir = args.project_dir.resolve()
    contract_root = None
    if args.contract_root is not None:
        contract_root = (
            args.contract_root
            if args.contract_root.is_absolute()
            else project_dir / args.contract_root
        ).resolve()
    plan = build_plan(
        project_dir,
        args.profile,
        contract_root=contract_root,
        gametest_timeout=args.gametest_timeout,
        strict_traceability=args.strict_traceability,
        allow_reference_host_only=args.allow_reference_host_only,
    )
    started_at = datetime.now(timezone.utc).isoformat()
    started = time.monotonic()
    results: list[StepResult] = []
    passed = True
    interrupted = False

    print(
        f"Quality profile '{args.profile}': {len(plan)} step(s) "
        f"for {project_dir}"
    )
    for index, step in enumerate(plan, 1):
        print(f"\n[{index}/{len(plan)}] {step.name}")
        print("argv: " + json.dumps(step.command, ensure_ascii=False))
        if args.dry_run:
            results.append(
                StepResult(
                    name=step.name,
                    command=step.command,
                    returncode=None,
                    duration_seconds=0.0,
                    status="planned",
                    output_tail=[],
                )
            )
            continue

        result = run_step(
            step,
            project_dir=project_dir,
            tail_lines=args.tail_lines,
            timeout_seconds=args.step_timeout,
        )
        results.append(result)
        if result.status != "passed":
            passed = False
            interrupted = result.status == "interrupted"
            print(f"PIPELINE FAIL: {step.name}; later steps were not run.")
            break

    duration = time.monotonic() - started
    if args.dry_run:
        print(
            "PIPELINE DRY RUN: plan constructed; paths and commands were not "
            "validated or executed."
        )
    elif passed:
        print(f"PIPELINE PASS: profile '{args.profile}'.")

    payload = report_payload(
        project_dir=project_dir,
        profile=args.profile,
        dry_run=args.dry_run,
        started_at=started_at,
        duration_seconds=duration,
        results=results,
        passed=passed,
        strict_traceability=args.strict_traceability,
    )
    if args.json_report is not None:
        report_path = (
            args.json_report
            if args.json_report.is_absolute()
            else project_dir / args.json_report
        )
        try:
            write_report(report_path, payload)
        except OSError as error:
            print(f"PIPELINE TOOL ERROR: could not write report: {error}")
            return 2
        print(f"JSON report: {report_path}")
        ledger_env = os.environ.get(LEDGER_ENV_VAR)
        if ledger_env and not args.dry_run:
            try:
                raw_ledger = Path(ledger_env)
                if raw_ledger.is_symlink():
                    raise OSError("ledger path must not be a symlink")
                ledger_path = raw_ledger.resolve()
                if ledger_path.exists() and not ledger_path.is_file():
                    raise OSError(
                        "ledger path exists and is not a regular file"
                    )
                append_ledger_event(ledger_path, payload, report_path)
                print(
                    f"Evidence ledger: {LEDGER_EVENT_TYPE} appended to "
                    f"{ledger_path}"
                )
            except OSError as error:
                print(
                    "PIPELINE TOOL WARNING: could not append evidence "
                    f"ledger: {error}"
                )
        elif args.profile == "release":
            # Shown even during --dry-run so a release rehearsal surfaces
            # the ledger requirement before a real run.
            if ledger_env:
                print(
                    "PIPELINE TOOL WARNING: evidence ledger is skipped for "
                    "--dry-run; a real release run will record it"
                )
            else:
                print(
                    f"PIPELINE TOOL WARNING: {LEDGER_ENV_VAR} is not set; "
                    "release pipeline result is not recorded in an evidence "
                    "ledger"
                )
    elif args.profile == "release":
        print(
            "PIPELINE TOOL WARNING: release profile without --json-report; "
            "evidence ledger cannot be recorded"
        )
    if interrupted:
        return 130
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
