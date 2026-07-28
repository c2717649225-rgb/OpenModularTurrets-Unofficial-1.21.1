#!/usr/bin/env python3
"""Trusted runner façade for the provisional external evidence ledger.

The host pipeline may stage reports inside its workspace, but only this
runner writes the authoritative journal and sealed bundle outside that
workspace.  The API is intentionally small: bind immutable input digests,
declare report/artifact paths, then execute one operation.
"""
from __future__ import annotations

import math
import os
import signal
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path, PurePath
from types import TracebackType
from typing import Any, Callable, Mapping, Optional, Sequence

try:
    from .evidence import (
        DIGEST_PATTERN,
        OUTCOMES,
        EvidenceBudgetError,
        EvidenceIntegrityError,
        EvidenceLedger,
        EvidencePathError,
        ReplayResult,
        canonical_json_bytes,
        digest_json,
        digest_path,
        digest_path_set,
        sha256_bytes,
        sha256_file,
    )
    from .execution_policy import (
        PreparedSandbox,
        finalized_attestation,
        validate_prepared_sandbox,
    )
except ImportError:  # Direct execution/tests with studio/ on sys.path.
    from evidence import (  # type: ignore
        DIGEST_PATTERN,
        OUTCOMES,
        EvidenceBudgetError,
        EvidenceIntegrityError,
        EvidenceLedger,
        EvidencePathError,
        ReplayResult,
        canonical_json_bytes,
        digest_json,
        digest_path,
        digest_path_set,
        sha256_bytes,
        sha256_file,
    )
    from execution_policy import (  # type: ignore
        PreparedSandbox,
        finalized_attestation,
        validate_prepared_sandbox,
    )


class RunnerError(RuntimeError):
    """Base class for trusted-runner failures."""


class RunnerBlockedError(RunnerError):
    """The requested run is blocked by an explicit capability precondition."""


class VerifiedInfrastructureError(RunnerError):
    """A checked infrastructure failure prevented meaningful execution."""


@dataclass(frozen=True)
class DigestBindings:
    """Digests that bind one run to its source, controls, inputs, and policy."""

    host_tree_digest: str
    control_digest: str
    input_digest: str
    policy_digest: str

    def __post_init__(self) -> None:
        for name, value in self.as_dict().items():
            if not isinstance(value, str) or not DIGEST_PATTERN.fullmatch(
                value
            ):
                raise EvidenceIntegrityError(
                    f"{name} must be a lowercase SHA-256 digest"
                )

    def as_dict(self) -> dict[str, str]:
        return {
            "host_tree_digest": self.host_tree_digest,
            "control_digest": self.control_digest,
            "input_digest": self.input_digest,
            "policy_digest": self.policy_digest,
        }

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "DigestBindings":
        expected = {
            "host_tree_digest",
            "control_digest",
            "input_digest",
            "policy_digest",
        }
        if set(value) != expected:
            raise EvidenceIntegrityError(
                "digest bindings have invalid fields; "
                f"missing={sorted(expected - set(value))}, "
                f"extra={sorted(set(value) - expected)}"
            )
        return cls(**{key: value[key] for key in sorted(expected)})

    @classmethod
    def capture(
        cls,
        host_workspace: Path | str,
        *,
        control_paths: Sequence[Path | str] = (".agents",),
        input_paths: Sequence[Path | str] = (),
        policy: Path | str | bytes | Mapping[str, Any],
        host_tree_digest: Optional[str] = None,
        control_digest: Optional[str] = None,
    ) -> "DigestBindings":
        """Capture bindings, allowing Git-derived source/control overrides.

        Independent verification should pass its committed Git tree digests
        through ``host_tree_digest`` and ``control_digest``.  Small fixtures
        and non-Git callers may omit them to hash the live paths.
        """
        workspace = _resolve_workspace(host_workspace)
        if host_tree_digest is None:
            tree_record = digest_path(workspace)
            host_tree_digest = tree_record.get("sha256")
        if control_digest is None:
            control_digest = digest_path_set(
                control_paths,
                base_dir=workspace,
            )
        input_digest = digest_path_set(input_paths, base_dir=workspace)
        policy_digest = _digest_policy(policy, workspace)
        return cls(
            host_tree_digest=host_tree_digest,
            control_digest=control_digest,
            input_digest=input_digest,
            policy_digest=policy_digest,
        )


@dataclass(frozen=True)
class RunDecision:
    """Explicit terminal classification returned by a runner operation."""

    outcome: str
    exit_code: Optional[int] = None
    timed_out: bool = False
    details: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.outcome not in OUTCOMES:
            raise EvidenceIntegrityError(
                f"unsupported run outcome: {self.outcome!r}"
            )
        if self.exit_code is not None and (
            not isinstance(self.exit_code, int)
            or isinstance(self.exit_code, bool)
        ):
            raise EvidenceIntegrityError(
                "exit_code must be an integer or null"
            )
        if not isinstance(self.timed_out, bool):
            raise EvidenceIntegrityError("timed_out must be a boolean")
        if self.outcome == "TIMEOUT" and not self.timed_out:
            raise EvidenceIntegrityError(
                "TIMEOUT decisions must set timed_out=true"
            )
        # Canonicalization both validates and snapshots the mapping contract.
        canonical_json_bytes(dict(self.details))

    @classmethod
    def passed(
        cls, *, details: Optional[Mapping[str, Any]] = None
    ) -> "RunDecision":
        return cls("PASS", 0, False, details or {})

    @classmethod
    def failed(
        cls,
        exit_code: Optional[int] = 1,
        *,
        details: Optional[Mapping[str, Any]] = None,
    ) -> "RunDecision":
        return cls("FAIL", exit_code, False, details or {})

    @classmethod
    def blocked(
        cls, *, details: Optional[Mapping[str, Any]] = None
    ) -> "RunDecision":
        return cls("BLOCKED", None, False, details or {})

    @classmethod
    def infrastructure_error(
        cls, *, details: Optional[Mapping[str, Any]] = None
    ) -> "RunDecision":
        return cls(
            "VERIFIED_INFRA_ERROR",
            None,
            False,
            details or {},
        )

    @classmethod
    def timeout(
        cls, *, details: Optional[Mapping[str, Any]] = None
    ) -> "RunDecision":
        return cls("TIMEOUT", None, True, details or {})


@dataclass(frozen=True)
class RunRecord:
    """Validated terminal result returned after the bundle is sealed."""

    run_id: str
    outcome: str
    exit_code: Optional[int]
    bundle_path: Path
    replay: ReplayResult


@dataclass(frozen=True)
class _DeclaredPath:
    name: str
    relative_path: Path

    def as_started_payload(self) -> dict[str, str]:
        return {
            "name": self.name,
            "path": self.relative_path.as_posix(),
        }


class RunContext:
    """CAS-safe event writer exposed to one running pipeline operation."""

    def __init__(self, ledger: EvidenceLedger, sequence: int):
        self._ledger = ledger
        self._sequence = sequence
        self._guard = threading.Lock()

    @property
    def run_id(self) -> str:
        return self._ledger.run_id

    @property
    def sequence(self) -> int:
        with self._guard:
            return self._sequence

    def record_event(
        self,
        *,
        transition_id: str,
        event_type: str,
        payload: Mapping[str, Any],
    ) -> dict[str, Any]:
        """Append one operation event while preserving ledger CAS semantics."""
        with self._guard:
            event = self._ledger.append_event(
                transition_id=transition_id,
                event_type=event_type,
                payload=payload,
                expected_sequence=self._sequence,
            )
            # Idempotent retries may return an older event.  Never move the
            # local CAS cursor backwards.
            self._sequence = max(self._sequence, event["sequence"])
            return event


class StudioRunner:
    """Execute one operation and seal authoritative evidence in ``finally``."""

    def __init__(
        self,
        evidence_root: Path | str,
        host_workspace: Path | str,
        run_id: str,
        *,
        digests: DigestBindings | Mapping[str, Any],
        frozen_policy_digest: str,
        reports: Optional[Mapping[str, Path | str]] = None,
        artifacts: Optional[Mapping[str, Path | str]] = None,
        monotonic: Callable[[], float] = time.monotonic,
    ):
        self.host_workspace = _resolve_workspace(host_workspace)
        self.ledger = EvidenceLedger(
            evidence_root,
            self.host_workspace,
            run_id,
        )
        self.digests = (
            digests
            if isinstance(digests, DigestBindings)
            else DigestBindings.from_mapping(digests)
        )
        if (
            not isinstance(frozen_policy_digest, str)
            or not DIGEST_PATTERN.fullmatch(frozen_policy_digest)
        ):
            raise RunnerError(
                "frozen_policy_digest must be a lowercase SHA-256 digest"
            )
        if self.digests.policy_digest != frozen_policy_digest:
            raise RunnerError(
                "digest bindings do not match the externally frozen policy"
            )
        self.frozen_policy_digest = frozen_policy_digest
        self.reports = _normalize_declarations(
            reports or {},
            context="reports",
        )
        self.artifacts = _normalize_declarations(
            artifacts or {},
            context="artifacts",
        )
        self._monotonic = monotonic
        self._used = False

    @property
    def run_id(self) -> str:
        return self.ledger.run_id

    def run(
        self,
        argv: Sequence[str],
        operation: Callable[[RunContext], Any],
        *,
        cwd: Path | str = ".",
    ) -> RunRecord:
        """Run one callable and seal PASS/FAIL/timeout/block/error evidence.

        Unexpected operation exceptions and process interruptions are
        re-raised *after* sealing.  Typed blocked/infrastructure exceptions
        and ``subprocess.TimeoutExpired`` are terminal classifications and
        return normally.
        """
        if self._used:
            raise RunnerError("a StudioRunner instance may execute only once")
        self._used = True
        normalized_argv = _normalize_argv(argv)
        working_directory = _resolve_cwd(self.host_workspace, cwd)
        prior = self.ledger.replay(require_sealed=False)
        if prior.events:
            raise RunnerError(
                f"run_id {self.run_id!r} already contains evidence"
            )

        started_payload = {
            "argv": normalized_argv,
            "cwd": str(working_directory),
            "host_workspace": str(self.host_workspace),
            "digests": self.digests.as_dict(),
            "report_staging": "non_authoritative",
            "declared_reports": [
                item.as_started_payload() for item in self.reports
            ],
            "declared_artifacts": [
                item.as_started_payload() for item in self.artifacts
            ],
        }
        started = self.ledger.append_event(
            transition_id="runner:started",
            event_type="RUN_STARTED",
            payload=started_payload,
            expected_sequence=0,
        )
        context = RunContext(self.ledger, started["sequence"])
        started_at = self._monotonic()
        decision = RunDecision.infrastructure_error(
            details={"classification": "runner_did_not_start_operation"}
        )
        pending_exception: Optional[BaseException] = None
        pending_traceback: Optional[TracebackType] = None
        interrupted = False
        bundle: Optional[dict[str, Any]] = None

        try:
            try:
                raw_result = operation(context)
                decision = _coerce_decision(raw_result)
            except RunnerBlockedError as error:
                decision = RunDecision.blocked(
                    details=_exception_details(error)
                )
            except VerifiedInfrastructureError as error:
                decision = RunDecision.infrastructure_error(
                    details=_exception_details(error)
                )
            except subprocess.TimeoutExpired as error:
                decision = RunDecision.timeout(
                    details={
                        **_exception_details(error),
                        "timeout_seconds": error.timeout,
                    }
                )
            except (KeyboardInterrupt, SystemExit) as error:
                interrupted = True
                pending_exception = error
                pending_traceback = error.__traceback__
                decision = RunDecision.failed(
                    exit_code=None,
                    details=_exception_details(error),
                )
            except BaseException as error:
                pending_exception = error
                pending_traceback = error.__traceback__
                decision = RunDecision.failed(
                    exit_code=None,
                    details=_exception_details(error),
                )
        finally:
            duration = self._monotonic() - started_at
            if not math.isfinite(duration) or duration < 0:
                duration = 0.0
            report_evidence, report_errors = self._capture_declared(
                self.reports
            )
            artifact_evidence, artifact_errors = self._capture_declared(
                self.artifacts
            )
            capture_errors = report_errors + artifact_errors
            if capture_errors and not interrupted:
                decision = RunDecision.infrastructure_error(
                    details={
                        "classification": "output_digest_capture_failed",
                        "prior_outcome": decision.outcome,
                        "errors": capture_errors,
                    }
                )
            details = dict(decision.details)
            if capture_errors and interrupted:
                details["output_digest_capture_errors"] = capture_errors
            summary = {
                "outcome": decision.outcome,
                "duration_seconds": duration,
                "exit_code": decision.exit_code,
                "timed_out": decision.timed_out,
                "interrupted": interrupted,
                "reports": report_evidence,
                "artifacts": artifact_evidence,
                "details": details,
            }
            try:
                bundle = self.ledger.seal(
                    transition_id="runner:sealed",
                    outcome=decision.outcome,
                    summary=summary,
                    expected_sequence=context.sequence,
                )
            except EvidenceBudgetError as error:
                # Oversized model/gate details must not strand an unsealed run.
                # The ledger rejects the projected terminal append before any
                # bytes are written, so a compact verified infrastructure
                # outcome can still be sealed within the reserved budget.
                decision = RunDecision.infrastructure_error(
                    details={
                        "classification": "evidence_budget_exceeded",
                        "prior_outcome": decision.outcome,
                        "message": str(error),
                    }
                )
                summary = {
                    "outcome": decision.outcome,
                    "duration_seconds": duration,
                    "exit_code": decision.exit_code,
                    "timed_out": decision.timed_out,
                    "interrupted": interrupted,
                    "reports": [],
                    "artifacts": [],
                    "details": dict(decision.details),
                }
                bundle = self.ledger.seal(
                    transition_id="runner:sealed",
                    outcome=decision.outcome,
                    summary=summary,
                    expected_sequence=context.sequence,
                )

        replay = self.ledger.replay(require_sealed=True)
        if bundle != replay.bundle:
            raise EvidenceIntegrityError(
                "sealed bundle changed between write and replay"
            )
        if pending_exception is not None:
            raise pending_exception.with_traceback(pending_traceback)
        return RunRecord(
            run_id=self.run_id,
            outcome=decision.outcome,
            exit_code=decision.exit_code,
            bundle_path=self.ledger.bundle_path,
            replay=replay,
        )

    def run_command(
        self,
        prepared: PreparedSandbox,
        *,
        cwd: Path | str | None = None,
        timeout_seconds: Optional[float] = None,
        environment: Optional[Mapping[str, str]] = None,
    ) -> RunRecord:
        """Execute only a live-probed, policy-bound sandbox launch.

        Bare builder argv is intentionally unsupported: callers must first
        produce a :class:`PreparedSandbox` from the frozen policy.  This keeps
        the Builder in a PID/network/mount namespace where the external
        authoritative evidence root is hidden by an empty tmpfs overlay.
        Runtime environment values must also be frozen by
        ``prepare_bubblewrap``; the host bwrap process always starts with an
        empty environment so loader/runtime injection variables cannot affect
        the trusted launcher.
        """
        validate_prepared_sandbox(prepared)
        if prepared.policy_digest != self.frozen_policy_digest:
            raise RunnerError(
                "prepared sandbox does not match the frozen policy digest"
            )
        if Path(prepared.project_dir).resolve() != self.host_workspace:
            raise RunnerError(
                "prepared sandbox workspace does not match this runner"
            )
        if (
            Path(prepared.evidence_root).resolve()
            != self.ledger.evidence_root
        ):
            raise RunnerError(
                "prepared sandbox does not hide this runner's evidence root"
            )
        if timeout_seconds is not None and (
            not isinstance(timeout_seconds, (int, float))
            or isinstance(timeout_seconds, bool)
            or not math.isfinite(float(timeout_seconds))
            or timeout_seconds <= 0
        ):
            raise RunnerError("timeout_seconds must be a positive finite value")
        effective_timeout = (
            prepared.timeout_seconds
            if timeout_seconds is None
            else float(timeout_seconds)
        )
        if effective_timeout > prepared.timeout_seconds:
            raise RunnerError(
                "runtime timeout cannot expand the frozen policy timeout"
            )
        normalized_argv = _normalize_argv(prepared.argv)
        prepared_cwd = str(prepared.attestation.get("cwd"))
        working_directory = _resolve_cwd(
            self.host_workspace,
            "." if prepared_cwd == "." else prepared_cwd,
        )
        if cwd is not None and _resolve_cwd(
            self.host_workspace,
            cwd,
        ) != working_directory:
            raise RunnerError(
                "runtime cwd cannot differ from the prepared sandbox cwd"
            )
        if environment is not None:
            raise RunnerError(
                "runtime environment overrides are forbidden; freeze allowed "
                "sandbox values with prepare_bubblewrap(environment=...)"
            )

        def execute(context: RunContext) -> RunDecision:
            process: Optional[subprocess.Popen] = None
            process_identity: Optional[dict[str, Any]] = None
            try:
                process = subprocess.Popen(
                    normalized_argv,
                    cwd=working_directory,
                    # Never let LD_PRELOAD, LD_LIBRARY_PATH, PYTHONPATH or any
                    # other caller/host variable influence the trusted bwrap
                    # launcher.  Allowed child values are encoded as
                    # --clearenv/--setenv in the validated prepared argv.
                    env={},
                    stdin=subprocess.DEVNULL,
                    start_new_session=True,
                )
                process_identity = _linux_process_identity(process.pid)
                try:
                    returncode = process.wait(timeout=effective_timeout)
                    process_tree_cleaned = process.poll() is not None
                    attestation = finalized_attestation(
                        prepared,
                        launched=True,
                        returncode=returncode,
                        timed_out=False,
                        process_tree_cleaned=process_tree_cleaned,
                        process_identity=process_identity,
                        cleanup_verification=(
                            "bubblewrap_pid_namespace_reaped"
                            if process_tree_cleaned
                            else ""
                        ),
                    )
                    context.record_event(
                        transition_id="sandbox:attested",
                        event_type="SANDBOX_ATTESTED",
                        payload=attestation,
                    )
                    if not process_tree_cleaned:
                        return RunDecision.infrastructure_error(
                            details={
                                "classification": (
                                    "sandbox_process_tree_not_reaped"
                                ),
                                "sandbox_attestation_digest": attestation[
                                    "attestation_digest"
                                ],
                            }
                        )
                    return (
                        RunDecision.passed(
                            details={
                                "sandbox_attestation_digest": attestation[
                                    "attestation_digest"
                                ]
                            }
                        )
                        if returncode == 0
                        else RunDecision.failed(
                            returncode,
                            details={
                                "sandbox_attestation_digest": attestation[
                                    "attestation_digest"
                                ]
                            },
                        )
                    )
                except subprocess.TimeoutExpired:
                    process_tree_cleaned = _terminate_sandbox_process(process)
                    attestation = finalized_attestation(
                        prepared,
                        launched=True,
                        returncode=process.returncode,
                        timed_out=True,
                        process_tree_cleaned=process_tree_cleaned,
                        process_identity=process_identity,
                        cleanup_verification=(
                            "bubblewrap_pid_namespace_reaped"
                            if process_tree_cleaned
                            else ""
                        ),
                    )
                    context.record_event(
                        transition_id="sandbox:attested",
                        event_type="SANDBOX_ATTESTED",
                        payload=attestation,
                    )
                    if not process_tree_cleaned:
                        return RunDecision.infrastructure_error(
                            details={
                                "classification": (
                                    "sandbox_timeout_cleanup_failed"
                                ),
                                "sandbox_attestation_digest": attestation[
                                    "attestation_digest"
                                ],
                            }
                        )
                    return RunDecision.timeout(
                        details={
                            "timeout_seconds": effective_timeout,
                            "sandbox_attestation_digest": attestation[
                                "attestation_digest"
                            ],
                        }
                    )
            except OSError as error:
                attestation = finalized_attestation(
                    prepared,
                    launched=False,
                    returncode=None,
                    timed_out=False,
                    process_tree_cleaned=True,
                    process_identity=None,
                    cleanup_verification="prelaunch_not_applicable",
                )
                context.record_event(
                    transition_id="sandbox:attested",
                    event_type="SANDBOX_ATTESTED",
                    payload=attestation,
                )
                raise VerifiedInfrastructureError(
                    f"could not launch command: {error}"
                ) from error
            except BaseException:
                if process is not None and process.poll() is None:
                    _terminate_sandbox_process(process)
                raise

        return self.run(normalized_argv, execute, cwd=working_directory)

    def seal_without_launch(
        self,
        argv: Sequence[str],
        decision: RunDecision,
        *,
        cwd: Path | str = ".",
        event: Optional[Mapping[str, Any]] = None,
    ) -> RunRecord:
        """Write RUN_STARTED and seal a checked pre-launch terminal decision.

        This is the narrow path for capability probes that establish
        ``BLOCKED`` or ``VERIFIED_INFRA_ERROR`` before a command can launch.
        An optional event mapping may contain exactly ``transition_id``,
        ``event_type``, and ``payload`` to preserve the probe attestation.
        """
        if decision.outcome not in {"BLOCKED", "VERIFIED_INFRA_ERROR"}:
            raise RunnerError(
                "seal_without_launch only accepts BLOCKED or "
                "VERIFIED_INFRA_ERROR decisions"
            )
        normalized_event: Optional[dict[str, Any]] = None
        if event is not None:
            expected = {"transition_id", "event_type", "payload"}
            if set(event) != expected:
                raise RunnerError(
                    "pre-launch event has invalid fields; "
                    f"missing={sorted(expected - set(event))}, "
                    f"extra={sorted(set(event) - expected)}"
                )
            if not isinstance(event["payload"], Mapping):
                raise RunnerError(
                    "pre-launch event payload must be an object"
                )
            normalized_event = {
                "transition_id": event["transition_id"],
                "event_type": event["event_type"],
                "payload": dict(event["payload"]),
            }

        def classify(context: RunContext) -> RunDecision:
            if normalized_event is not None:
                context.record_event(**normalized_event)
            return decision

        return self.run(argv, classify, cwd=cwd)

    def _capture_declared(
        self,
        declarations: Sequence[_DeclaredPath],
    ) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
        evidence: list[dict[str, Any]] = []
        errors: list[dict[str, str]] = []
        for declaration in declarations:
            target = self.host_workspace / declaration.relative_path
            try:
                _reject_symlink_ancestors(
                    self.host_workspace,
                    declaration.relative_path,
                )
                record = digest_path(target)
                record["name"] = declaration.name
                evidence.append(record)
                if record["kind"] in {"missing", "symlink", "unreadable"}:
                    errors.append(
                        {
                            "name": declaration.name,
                            "error_type": "RequiredOutputMissing",
                            "message": (
                                "declared required output is not a readable "
                                f"file/directory: {declaration.relative_path} "
                                f"({record['kind']})"
                            ),
                        }
                    )
            except Exception as error:
                # Seal an honest infrastructure failure instead of leaving the
                # journal unsealed.  No digest is fabricated.
                absolute = Path(os.path.abspath(target))
                evidence.append(
                    {
                        "name": declaration.name,
                        "path": str(absolute),
                        "kind": "unreadable",
                        "exists": target.exists(),
                        "size_bytes": None,
                        "sha256": None,
                    }
                )
                errors.append(
                    {
                        "name": declaration.name,
                        "error_type": type(error).__name__,
                        "message": str(error),
                    }
                )
        return evidence, errors


def _linux_process_identity(pid: int) -> dict[str, Any]:
    if not sys.platform.startswith("linux"):
        raise VerifiedInfrastructureError(
            "the provisional sandbox runner is supported only on Linux"
        )
    stat_path = Path("/proc") / str(pid) / "stat"
    try:
        raw = stat_path.read_text(encoding="utf-8")
        closing = raw.rfind(")")
        if closing < 0:
            raise ValueError("missing command terminator")
        fields_after_command = raw[closing + 2 :].split()
        # /proc/<pid>/stat field 22 is starttime.  The remainder starts at
        # field 3, so the stable identity token is index 19.
        start_token = fields_after_command[19]
    except (OSError, IndexError, ValueError) as error:
        raise VerifiedInfrastructureError(
            f"could not capture sandbox process identity for pid {pid}: {error}"
        ) from error
    return {
        "pid": pid,
        "start_token": start_token,
        "platform": "linux-procfs",
    }


def _terminate_sandbox_process(
    process: subprocess.Popen,
    *,
    grace_seconds: float = 2.0,
) -> bool:
    """Terminate the bwrap wrapper; its verified PID namespace reaps children."""
    if process.poll() is not None:
        return True
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except (OSError, ProcessLookupError):
        try:
            process.terminate()
        except OSError:
            pass
    try:
        process.wait(timeout=grace_seconds)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except (OSError, ProcessLookupError):
            try:
                process.kill()
            except OSError:
                pass
        try:
            process.wait(timeout=grace_seconds)
        except subprocess.TimeoutExpired:
            return False
    return process.poll() is not None


def _resolve_workspace(path: Path | str) -> Path:
    candidate = Path(path).expanduser()
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        raise EvidencePathError(
            f"host workspace does not resolve: {candidate}: {error}"
        ) from error
    if not resolved.is_dir():
        raise EvidencePathError(
            f"host workspace is not a directory: {resolved}"
        )
    return resolved


def _resolve_cwd(workspace: Path, cwd: Path | str) -> Path:
    candidate = Path(cwd)
    if not candidate.is_absolute():
        candidate = workspace / candidate
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        raise RunnerError(f"working directory does not resolve: {error}") from error
    if not resolved.is_dir():
        raise RunnerError(f"working directory is not a directory: {resolved}")
    if resolved != workspace and workspace not in resolved.parents:
        raise RunnerError(
            f"working directory escapes host workspace: {resolved}"
        )
    return resolved


def _normalize_argv(argv: Sequence[str]) -> list[str]:
    if isinstance(argv, (str, bytes)) or not argv:
        raise RunnerError("argv must be a non-empty sequence of strings")
    result: list[str] = []
    for index, value in enumerate(argv):
        if not isinstance(value, str) or not value or "\0" in value:
            raise RunnerError(
                f"argv[{index}] must be a non-empty NUL-free string"
            )
        result.append(value)
    return result


def _normalize_declarations(
    declarations: Mapping[str, Path | str],
    *,
    context: str,
) -> tuple[_DeclaredPath, ...]:
    result: list[_DeclaredPath] = []
    for name, raw_path in declarations.items():
        if not isinstance(name, str) or not name.strip():
            raise RunnerError(f"{context} names must be non-empty strings")
        if "\0" in name:
            raise RunnerError(f"{context} names must not contain NUL")
        path = Path(raw_path)
        drive = getattr(PurePath(str(raw_path)), "drive", "")
        if path.is_absolute() or drive or path == Path("."):
            raise RunnerError(
                f"{context}[{name!r}] must be a non-root relative path"
            )
        if any(part in ("", ".", "..") for part in path.parts):
            raise RunnerError(
                f"{context}[{name!r}] contains unsafe path components"
            )
        result.append(_DeclaredPath(name, path))
    result.sort(key=lambda item: (item.name, item.relative_path.as_posix()))
    return tuple(result)


def _reject_symlink_ancestors(workspace: Path, relative_path: Path) -> None:
    current = workspace
    for part in relative_path.parts[:-1]:
        current = current / part
        if current.is_symlink():
            raise EvidencePathError(
                f"declared output traverses a symlink: {current}"
            )
        if not current.exists():
            break


def _digest_policy(
    policy: Path | str | bytes | Mapping[str, Any],
    workspace: Path,
) -> str:
    if isinstance(policy, Mapping):
        return digest_json(dict(policy))
    if isinstance(policy, bytes):
        return sha256_bytes(policy)
    candidate = Path(policy)
    if not candidate.is_absolute():
        candidate = workspace / candidate
    return sha256_file(candidate)


def _coerce_decision(value: Any) -> RunDecision:
    if isinstance(value, RunDecision):
        return value
    if isinstance(value, subprocess.CompletedProcess):
        return (
            RunDecision.passed()
            if value.returncode == 0
            else RunDecision.failed(value.returncode)
        )
    if value is None:
        return RunDecision.passed()
    if isinstance(value, int) and not isinstance(value, bool):
        return (
            RunDecision.passed()
            if value == 0
            else RunDecision.failed(value)
        )
    raise RunnerError(
        "operation must return None, int, CompletedProcess, or RunDecision"
    )


def _exception_details(error: BaseException) -> dict[str, str]:
    return {
        "classification": "exception",
        "exception_type": type(error).__name__,
        "message": str(error),
    }
