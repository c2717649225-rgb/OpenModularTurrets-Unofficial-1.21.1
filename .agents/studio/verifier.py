#!/usr/bin/env python3
"""Independent clean-worktree preparation for studio verification.

The verifier deliberately refuses a dirty source repository.  It records the
committed tree and control-plane digests, creates a detached Git worktree in a
trusted temporary directory, and re-computes those digests before yielding the
checkout to a caller.

This is source isolation, not an execution sandbox.  Commands executed in the
checkout still need an enforcing execution-policy backend.
"""
from __future__ import annotations

import contextlib
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator, Mapping, Optional, Sequence

try:
    from .evidence import EvidenceLedger, ReplayResult, digest_json
    from .execution_policy import (
        CapabilityUnavailable,
        ExecutionPolicy,
        prepare_bubblewrap,
    )
    from .runner import DigestBindings, StudioRunner
except ImportError:  # Direct execution/tests with studio/ on sys.path.
    from evidence import EvidenceLedger, ReplayResult, digest_json  # type: ignore
    from execution_policy import (  # type: ignore
        CapabilityUnavailable,
        ExecutionPolicy,
        prepare_bubblewrap,
    )
    from runner import DigestBindings, StudioRunner  # type: ignore


CONTROL_PATHSPEC = ".agents"
GIT_TIMEOUT_SECONDS = 30.0


class VerificationError(RuntimeError):
    """The requested clean-clone verification cannot be trusted."""


@dataclass(frozen=True)
class SourceSnapshot:
    commit: str
    tree_digest: str
    control_digest: str

    def as_dict(self) -> dict[str, str]:
        return {
            "commit": self.commit,
            "tree_digest": self.tree_digest,
            "control_digest": self.control_digest,
        }


@dataclass(frozen=True)
class VerifiedWorktree:
    source_repository: Path
    checkout: Path
    source: SourceSnapshot
    checkout_snapshot: SourceSnapshot

    def attestation(self) -> dict[str, object]:
        return {
            "schema_version": 1,
            "stability": "provisional",
            "state": "verified",
            "isolation": "detached_clean_git_worktree",
            "execution_sandbox": False,
            "source_repository": str(self.source_repository),
            "checkout": str(self.checkout),
            "source": self.source.as_dict(),
            "checkout_snapshot": self.checkout_snapshot.as_dict(),
        }


@dataclass(frozen=True)
class VerificationDecision:
    """Digest-bound independent verdict over a source and verifier bundle."""

    document: Mapping[str, Any]

    @property
    def passed(self) -> bool:
        return self.document.get("passed") is True

    @property
    def decision_digest(self) -> str:
        return str(self.document["decision_digest"])

    def as_dict(self) -> dict[str, Any]:
        return json.loads(
            json.dumps(
                dict(self.document),
                ensure_ascii=False,
                allow_nan=False,
                sort_keys=True,
            )
        )


def _git_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "LC_ALL": "C",
        }
    )
    return environment


def _run_git(
    repository: Path,
    arguments: Sequence[str],
    *,
    timeout_seconds: float = GIT_TIMEOUT_SECONDS,
    binary: bool = False,
) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            [
                "git",
                "-c",
                "core.hooksPath=",
                "-c",
                "core.fsmonitor=false",
                "-C",
                str(repository),
                *arguments,
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=not binary,
            encoding=None if binary else "utf-8",
            errors=None if binary else "replace",
            env=_git_environment(),
            check=False,
            timeout=timeout_seconds,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VerificationError(f"git command failed to run: {error}") from error


def _git_stdout(
    repository: Path,
    arguments: Sequence[str],
    *,
    binary: bool = False,
) -> str | bytes:
    result = _run_git(repository, arguments, binary=binary)
    if result.returncode != 0:
        error_text = (
            result.stderr.decode("utf-8", errors="replace")
            if isinstance(result.stderr, bytes)
            else result.stderr
        ).strip()
        raise VerificationError(
            "git command failed"
            f" ({' '.join(arguments)}): {error_text or result.returncode}"
        )
    return result.stdout


def repository_root(path: Path | str) -> Path:
    candidate = Path(path).resolve()
    output = _git_stdout(candidate, ["rev-parse", "--show-toplevel"])
    root = Path(str(output).strip()).resolve()
    if root != candidate:
        raise VerificationError(
            f"verification must target the repository root: {root}"
        )
    return root


def require_clean_repository(repository: Path | str) -> None:
    root = repository_root(repository)
    status = _git_stdout(
        root,
        [
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            "--ignore-submodules=none",
        ],
        binary=True,
    )
    assert isinstance(status, bytes)
    if status:
        entries = [
            entry.decode("utf-8", errors="replace")
            for entry in status.split(b"\0")
            if entry
        ]
        preview = ", ".join(entries[:5])
        if len(entries) > 5:
            preview += f", … ({len(entries)} entries)"
        raise VerificationError(
            "source repository is dirty; commit or remove every change before "
            f"independent verification: {preview}"
        )


def _tree_listing(
    repository: Path,
    commit: str,
    pathspecs: Sequence[str] = (),
) -> bytes:
    arguments = ["ls-tree", "-r", "-z", "--full-tree", commit]
    if pathspecs:
        arguments.extend(["--", *pathspecs])
    output = _git_stdout(repository, arguments, binary=True)
    assert isinstance(output, bytes)
    return output


def git_tree_digest(
    repository: Path | str,
    commit: str,
    pathspecs: Sequence[str] = (),
    *,
    require_entries: bool = True,
) -> str:
    """Hash Git's committed mode/type/blob/path records with SHA-256."""
    root = repository_root(repository)
    listing = _tree_listing(root, commit, pathspecs)
    if require_entries and not listing:
        label = ", ".join(pathspecs) if pathspecs else "<entire tree>"
        raise VerificationError(f"committed pathspec has no entries: {label}")
    digest = hashlib.sha256()
    digest.update(b"afs-git-tree-digest-v1\0")
    for pathspec in pathspecs:
        digest.update(pathspec.encode("utf-8"))
        digest.update(b"\0")
    digest.update(listing)
    return digest.hexdigest()


def snapshot_repository(repository: Path | str) -> SourceSnapshot:
    root = repository_root(repository)
    commit_output = _git_stdout(root, ["rev-parse", "--verify", "HEAD^{commit}"])
    commit = str(commit_output).strip()
    return SourceSnapshot(
        commit=commit,
        tree_digest=git_tree_digest(root, commit),
        control_digest=git_tree_digest(
            root,
            commit,
            (CONTROL_PATHSPEC,),
        ),
    )


def _safe_temp_parent(path: Path | str | None) -> Path | None:
    if path is None:
        return None
    parent = Path(path).resolve()
    parent.mkdir(parents=True, exist_ok=True)
    if not parent.is_dir():
        raise VerificationError(f"temporary parent is not a directory: {parent}")
    return parent


@contextlib.contextmanager
def prepare_clean_worktree(
    repository: Path | str,
    *,
    expected_commit: Optional[str] = None,
    expected_control_digest: Optional[str] = None,
    temp_parent: Path | str | None = None,
) -> Iterator[VerifiedWorktree]:
    """Yield a digest-checked detached checkout and remove only that checkout."""
    root = repository_root(repository)
    require_clean_repository(root)
    source = snapshot_repository(root)
    if expected_commit is not None and source.commit != expected_commit:
        raise VerificationError(
            f"commit mismatch: expected {expected_commit}, got {source.commit}"
        )
    if (
        expected_control_digest is not None
        and source.control_digest != expected_control_digest
    ):
        raise VerificationError(
            "control digest mismatch before clean checkout: expected "
            f"{expected_control_digest}, got {source.control_digest}"
        )

    parent = _safe_temp_parent(temp_parent)
    temporary_root = Path(
        tempfile.mkdtemp(prefix="afs-verifier-", dir=parent)
    ).resolve()
    checkout = temporary_root / "checkout"
    registered = False
    try:
        result = _run_git(
            root,
            [
                "worktree",
                "add",
                "--detach",
                str(checkout),
                source.commit,
            ],
        )
        if result.returncode != 0:
            raise VerificationError(
                "could not create detached verifier worktree: "
                + str(result.stderr).strip()
            )
        registered = True
        checkout_root = repository_root(checkout)
        require_clean_repository(checkout_root)
        checkout_snapshot = snapshot_repository(checkout_root)
        if checkout_snapshot != source:
            raise VerificationError(
                "clean checkout digest mismatch; verifier source changed or "
                "the checkout is not faithful"
            )
        yield VerifiedWorktree(
            source_repository=root,
            checkout=checkout_root,
            source=source,
            checkout_snapshot=checkout_snapshot,
        )
    finally:
        if registered:
            remove_result = _run_git(
                root,
                ["worktree", "remove", "--force", str(checkout)],
            )
            if remove_result.returncode != 0 and checkout.exists():
                raise VerificationError(
                    "could not remove verifier-owned worktree: "
                    + str(remove_result.stderr).strip()
                )
        # The directory was created by this function and its resolved absolute
        # path is known.  Never broaden cleanup to the caller's temp parent.
        shutil.rmtree(temporary_root, ignore_errors=True)


def consume_sealed_bundle(
    ledger: EvidenceLedger,
    *,
    expected_bundle_digest: str,
    require_pass: bool = True,
) -> ReplayResult:
    """Replay an authoritative bundle and bind it to an external digest."""
    if (
        not isinstance(expected_bundle_digest, str)
        or len(expected_bundle_digest) != 64
        or any(
            character not in "0123456789abcdef"
            for character in expected_bundle_digest
        )
    ):
        raise VerificationError(
            "expected_bundle_digest must be lowercase SHA-256"
        )
    replay = ledger.replay(require_sealed=True)
    if replay.bundle is None:
        raise VerificationError("source evidence bundle is missing")
    if replay.bundle.get("bundle_digest") != expected_bundle_digest:
        raise VerificationError(
            "source evidence bundle differs from the externally frozen digest"
        )
    if require_pass and replay.outcome != "PASS":
        raise VerificationError(
            f"source run is not independently eligible: {replay.outcome}"
        )
    return replay


RUN_DIGEST_KEYS = frozenset(
    {
        "host_tree_digest",
        "control_digest",
        "input_digest",
        "policy_digest",
    }
)
VERIFICATION_INPUT_KEYS = frozenset(
    {
        "schema_version",
        "stability",
        "kind",
        "source_run_id",
        "source_bundle_digest",
        "source_input_digest",
        "commit",
        "source_tree_digest",
        "control_digest",
        "policy_digest",
    }
)
DECISION_KEYS = frozenset(
    {
        "schema_version",
        "stability",
        "kind",
        "source_run_id",
        "source_bundle_digest",
        "source_outcome",
        "verifier_run_id",
        "verifier_bundle_digest",
        "verifier_outcome",
        "commit",
        "source_tree_digest",
        "control_digest",
        "policy_digest",
        "source_input_digest",
        "verification_input_digest",
        "gate_argv_digest",
        "passed",
        "decision_digest",
    }
)


def _require_sha256(value: Any, *, field: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise VerificationError(f"{field} must be lowercase SHA-256")
    return value


def _require_commit(value: Any, *, field: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) not in {40, 64}
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise VerificationError(
            f"{field} must be a full lowercase Git object id"
        )
    return value


def _run_started_digests(
    replay: ReplayResult,
    *,
    label: str,
) -> dict[str, str]:
    if (
        not replay.events
        or replay.events[0].get("event_type") != "RUN_STARTED"
    ):
        raise VerificationError(f"{label} journal has no RUN_STARTED binding")
    payload = replay.events[0].get("payload")
    if not isinstance(payload, Mapping):
        raise VerificationError(f"{label} RUN_STARTED payload is invalid")
    digests = payload.get("digests")
    if not isinstance(digests, Mapping) or set(digests) != RUN_DIGEST_KEYS:
        raise VerificationError(
            f"{label} RUN_STARTED digest bindings are invalid"
        )
    return {
        key: _require_sha256(
            digests.get(key),
            field=f"{label} RUN_STARTED {key}",
        )
        for key in sorted(RUN_DIGEST_KEYS)
    }


def validate_source_replay_provenance(
    source_replay: ReplayResult,
    source_snapshot: SourceSnapshot,
    *,
    expected_policy_digest: str,
) -> dict[str, Any]:
    """Bind a source PASS journal to one exact clean Git snapshot.

    The returned canonical document is also the sole input to the verifier
    run's ``input_digest``. It carries the source bundle identity, its original
    input digest, and the exact commit/tree/control/policy being rechecked.
    """
    if source_replay.bundle is None or source_replay.outcome != "PASS":
        raise VerificationError(
            "source provenance requires a sealed PASS evidence bundle"
        )
    if not isinstance(source_snapshot, SourceSnapshot):
        raise VerificationError("source snapshot is invalid")

    commit = _require_commit(source_snapshot.commit, field="source commit")
    tree_digest = _require_sha256(
        source_snapshot.tree_digest,
        field="source tree digest",
    )
    control_digest = _require_sha256(
        source_snapshot.control_digest,
        field="source control digest",
    )
    policy_digest = _require_sha256(
        expected_policy_digest,
        field="expected policy digest",
    )
    bundle_digest = _require_sha256(
        source_replay.bundle.get("bundle_digest"),
        field="source bundle digest",
    )
    started = _run_started_digests(source_replay, label="source")
    expected_started = {
        "host_tree_digest": tree_digest,
        "control_digest": control_digest,
        "policy_digest": policy_digest,
    }
    for key, expected in expected_started.items():
        if started[key] != expected:
            raise VerificationError(
                f"source RUN_STARTED {key} does not match the clean "
                "verification snapshot"
            )

    provenance: dict[str, Any] = {
        "schema_version": 1,
        "stability": "provisional",
        "kind": "independent_verification_input",
        "source_run_id": source_replay.run_id,
        "source_bundle_digest": bundle_digest,
        "source_input_digest": started["input_digest"],
        "commit": commit,
        "source_tree_digest": tree_digest,
        "control_digest": control_digest,
        "policy_digest": policy_digest,
    }
    if set(provenance) != VERIFICATION_INPUT_KEYS:
        raise VerificationError("source provenance projection is invalid")
    return provenance


def verification_input_digest(
    source_replay: ReplayResult,
    source_snapshot: SourceSnapshot,
    *,
    expected_policy_digest: str,
) -> str:
    """Return the digest the independent verifier must journal as its input."""
    return digest_json(
        validate_source_replay_provenance(
            source_replay,
            source_snapshot,
            expected_policy_digest=expected_policy_digest,
        )
    )


def _decision_document(
    *,
    source_replay: ReplayResult,
    verifier_replay: ReplayResult,
    source_snapshot: SourceSnapshot,
    gate_argv: Sequence[str],
    policy_digest: str,
) -> dict[str, Any]:
    if source_replay.bundle is None or verifier_replay.bundle is None:
        raise VerificationError("verification requires two sealed bundles")
    if source_replay.run_id == verifier_replay.run_id:
        raise VerificationError(
            "source and verifier evidence must use distinct run ids"
        )
    provenance = validate_source_replay_provenance(
        source_replay,
        source_snapshot,
        expected_policy_digest=policy_digest,
    )
    verifier_input_digest = digest_json(provenance)
    verifier_started = _run_started_digests(
        verifier_replay,
        label="verifier",
    )
    expected_verifier_started = {
        "host_tree_digest": provenance["source_tree_digest"],
        "control_digest": provenance["control_digest"],
        "input_digest": verifier_input_digest,
        "policy_digest": provenance["policy_digest"],
    }
    for key, expected in expected_verifier_started.items():
        if verifier_started[key] != expected:
            raise VerificationError(
                f"verifier RUN_STARTED {key} does not match source provenance"
            )
    without_digest: dict[str, Any] = {
        "schema_version": 1,
        "stability": "provisional",
        "kind": "independent_verification_decision",
        "source_run_id": source_replay.run_id,
        "source_bundle_digest": source_replay.bundle["bundle_digest"],
        "source_outcome": source_replay.outcome,
        "verifier_run_id": verifier_replay.run_id,
        "verifier_bundle_digest": verifier_replay.bundle["bundle_digest"],
        "verifier_outcome": verifier_replay.outcome,
        "commit": source_snapshot.commit,
        "source_tree_digest": source_snapshot.tree_digest,
        "control_digest": source_snapshot.control_digest,
        "policy_digest": policy_digest,
        "source_input_digest": provenance["source_input_digest"],
        "verification_input_digest": verifier_input_digest,
        "gate_argv_digest": digest_json(list(gate_argv)),
        "passed": (
            source_replay.outcome == "PASS"
            and verifier_replay.outcome == "PASS"
        ),
    }
    document = dict(without_digest)
    document["decision_digest"] = digest_json(without_digest)
    return document


def validate_verification_decision(
    decision: VerificationDecision | Mapping[str, Any],
    *,
    source_replay: ReplayResult,
    verifier_replay: ReplayResult,
    expected_source_snapshot: SourceSnapshot,
    expected_policy_digest: str,
) -> None:
    document = (
        decision.as_dict()
        if isinstance(decision, VerificationDecision)
        else dict(decision)
    )
    if set(document) != DECISION_KEYS:
        raise VerificationError(
            "verification decision has invalid fields"
        )
    claimed = document.pop("decision_digest", None)
    if (
        not isinstance(claimed, str)
        or _require_sha256(
            claimed,
            field="verification decision digest",
        )
        != claimed
        or digest_json(document) != claimed
    ):
        raise VerificationError("verification decision digest mismatch")
    if (
        document.get("schema_version") != 1
        or document.get("stability") != "provisional"
        or document.get("kind") != "independent_verification_decision"
    ):
        raise VerificationError("verification decision identity is invalid")
    if source_replay.bundle is None or verifier_replay.bundle is None:
        raise VerificationError("decision inputs are not sealed")
    if source_replay.run_id == verifier_replay.run_id:
        raise VerificationError(
            "source and verifier evidence are not independent"
        )
    expected = {
        "source_run_id": source_replay.run_id,
        "source_bundle_digest": source_replay.bundle["bundle_digest"],
        "source_outcome": source_replay.outcome,
        "verifier_run_id": verifier_replay.run_id,
        "verifier_bundle_digest": verifier_replay.bundle["bundle_digest"],
        "verifier_outcome": verifier_replay.outcome,
    }
    for key, value in expected.items():
        if document.get(key) != value:
            raise VerificationError(
                f"verification decision {key} does not match its bundle"
            )
    expected_pass = (
        source_replay.outcome == "PASS"
        and verifier_replay.outcome == "PASS"
    )
    if document.get("passed") is not expected_pass:
        raise VerificationError("verification decision pass state is invalid")

    snapshot = SourceSnapshot(
        commit=_require_commit(
            document.get("commit"),
            field="verification decision commit",
        ),
        tree_digest=_require_sha256(
            document.get("source_tree_digest"),
            field="verification decision source_tree_digest",
        ),
        control_digest=_require_sha256(
            document.get("control_digest"),
            field="verification decision control_digest",
        ),
    )
    if not isinstance(expected_source_snapshot, SourceSnapshot):
        raise VerificationError("expected source snapshot is invalid")
    if snapshot != expected_source_snapshot:
        raise VerificationError(
            "verification decision does not match the expected clean snapshot"
        )
    policy_digest = _require_sha256(
        expected_policy_digest,
        field="expected verification policy digest",
    )
    if document.get("policy_digest") != policy_digest:
        raise VerificationError(
            "verification decision policy differs from the externally "
            "frozen digest"
        )
    provenance = validate_source_replay_provenance(
        source_replay,
        expected_source_snapshot,
        expected_policy_digest=policy_digest,
    )
    if document.get("source_input_digest") != provenance["source_input_digest"]:
        raise VerificationError(
            "verification decision source input is not bound to the "
            "source journal"
        )
    expected_verification_input = digest_json(provenance)
    if (
        document.get("verification_input_digest")
        != expected_verification_input
    ):
        raise VerificationError(
            "verification decision commit/provenance digest mismatch"
        )

    started_digests = _run_started_digests(
        verifier_replay,
        label="verifier",
    )
    for document_key, digest_key in (
        ("source_tree_digest", "host_tree_digest"),
        ("control_digest", "control_digest"),
        ("policy_digest", "policy_digest"),
        ("verification_input_digest", "input_digest"),
    ):
        if document.get(document_key) != started_digests[digest_key]:
            raise VerificationError(
                f"verification decision {document_key} is not bound "
                "to the verifier journal"
            )
    gate_argv_digest = _require_sha256(
        document.get("gate_argv_digest"),
        field="verification decision gate_argv_digest",
    )
    attestations = [
        event["payload"]
        for event in verifier_replay.events
        if event["event_type"] == "SANDBOX_ATTESTED"
    ]
    if len(attestations) != 1:
        raise VerificationError(
            "verifier journal must contain one sandbox attestation"
        )
    attestation = attestations[0]
    if (
        attestation.get("state") != "enforced"
        or attestation.get("policy_digest") != document.get("policy_digest")
        or attestation.get("command_digest")
        != gate_argv_digest
    ):
        raise VerificationError(
            "verification decision is not bound to an enforced gate command"
        )


def verify_sealed_bundle_and_run_gate(
    repository: Path | str,
    *,
    source_ledger: EvidenceLedger,
    expected_source_bundle_digest: str,
    expected_commit: str,
    expected_control_digest: str,
    evidence_root: Path | str,
    verifier_run_id: str,
    policy: ExecutionPolicy,
    expected_policy_digest: str,
    gate_argv: Sequence[str],
    reports: Mapping[str, Path | str],
    artifacts: Optional[Mapping[str, Path | str]] = None,
    temp_parent: Path | str | None = None,
) -> VerificationDecision:
    """Consume source evidence and rerun one gate in a clean sandboxed checkout."""
    if not reports:
        raise VerificationError(
            "independent verification requires a declared machine report"
        )
    if policy.digest != expected_policy_digest:
        raise VerificationError(
            "verifier policy differs from the externally frozen digest"
        )
    if Path(evidence_root).resolve() != source_ledger.evidence_root:
        raise VerificationError(
            "verifier evidence root differs from the source control plane"
        )
    source_replay = consume_sealed_bundle(
        source_ledger,
        expected_bundle_digest=expected_source_bundle_digest,
        require_pass=True,
    )
    if verifier_run_id == source_replay.run_id:
        raise VerificationError("verifier run_id must differ from source run_id")

    with prepare_clean_worktree(
        repository,
        expected_commit=expected_commit,
        expected_control_digest=expected_control_digest,
        temp_parent=temp_parent,
    ) as clean:
        try:
            prepared = prepare_bubblewrap(
                policy,
                project_dir=clean.checkout,
                evidence_root=evidence_root,
                command=gate_argv,
            )
        except CapabilityUnavailable as error:
            raise VerificationError(
                f"independent verifier sandbox is unavailable: {error}"
            ) from error
        bindings = DigestBindings(
            host_tree_digest=clean.checkout_snapshot.tree_digest,
            control_digest=clean.checkout_snapshot.control_digest,
            input_digest=verification_input_digest(
                source_replay,
                clean.checkout_snapshot,
                expected_policy_digest=expected_policy_digest,
            ),
            policy_digest=expected_policy_digest,
        )
        verifier_runner = StudioRunner(
            evidence_root,
            clean.checkout,
            verifier_run_id,
            digests=bindings,
            frozen_policy_digest=expected_policy_digest,
            reports=reports,
            artifacts=artifacts,
        )
        verifier_record = verifier_runner.run_command(prepared)
        decision = VerificationDecision(
            _decision_document(
                source_replay=source_replay,
                verifier_replay=verifier_record.replay,
                source_snapshot=clean.checkout_snapshot,
                gate_argv=gate_argv,
                policy_digest=expected_policy_digest,
            )
        )
        validate_verification_decision(
            decision,
            source_replay=source_replay,
            verifier_replay=verifier_record.replay,
            expected_source_snapshot=clean.checkout_snapshot,
            expected_policy_digest=expected_policy_digest,
        )
        return decision
