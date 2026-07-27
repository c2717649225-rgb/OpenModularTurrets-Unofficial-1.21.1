#!/usr/bin/env python3
"""Provisional core Execution Policy and one real Linux sandbox adapter.

This module intentionally supports exactly one enforcing backend: bubblewrap.
Process groups, timeouts, clean copies, and post-run hash checks are useful
defences, but they are not filesystem or network sandboxes and are never
reported as such here.

The caller owns process execution and terminal-state evidence.  This module
only validates the frozen policy, probes enforcement capabilities, prepares a
wrapped argv, and creates an attestation template whose state remains
``prepared`` until the trusted Runner records the actual result.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Mapping, Optional, Sequence


SCHEMA_VERSION = 1
STABILITY = "provisional"
BACKEND_NAME = "bubblewrap"
POLICY_ID = re.compile(r"^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")
CORE_CAPABILITIES = frozenset(
    {
        "filesystem_read_only",
        "workspace_write_allowlist",
        "network_denied",
        "process_timeout",
        "process_tree_cleanup",
        "evidence_unmounted",
    }
)


class PolicyError(ValueError):
    """The policy or requested launch cannot be enforced safely."""


class CapabilityUnavailable(PolicyError):
    """The selected backend cannot enforce the frozen policy."""


def _reject_duplicate_pairs(
    pairs: list[tuple[str, Any]],
) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PolicyError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise PolicyError(f"non-standard JSON number: {value}")


def canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def sha256_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value)).hexdigest()


def _safe_relative_path(raw: str) -> str:
    if not isinstance(raw, str) or not raw:
        raise PolicyError("writable paths must be non-empty strings")
    if "\\" in raw or ":" in raw:
        raise PolicyError(
            f"writable path must use portable forward slashes: {raw!r}"
        )
    path = PurePosixPath(raw)
    if path.is_absolute() or raw in {".", ".."}:
        raise PolicyError(f"writable path must be a scoped relative path: {raw!r}")
    if (
        path.as_posix() != raw
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise PolicyError(f"writable path contains unsafe traversal: {raw!r}")
    return path.as_posix()


@dataclass(frozen=True)
class ExecutionPolicy:
    policy_id: str
    backend: str
    writable_paths: tuple[str, ...]
    timeout_seconds: float
    required_capabilities: frozenset[str]
    document: Mapping[str, Any]
    digest: str


@dataclass(frozen=True)
class BackendProbe:
    backend: str
    available: bool
    version: str
    capabilities: frozenset[str]
    reason: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "backend": self.backend,
            "available": self.available,
            "version": self.version,
            "capabilities": sorted(self.capabilities),
            "reason": self.reason,
        }


@dataclass(frozen=True)
class PreparedSandbox:
    argv: tuple[str, ...]
    policy_digest: str
    attestation: Mapping[str, Any]


def load_policy(path: Path | str) -> ExecutionPolicy:
    policy_path = Path(path)
    try:
        document = json.loads(
            policy_path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_pairs,
            parse_constant=_reject_constant,
        )
    except OSError as error:
        raise PolicyError(f"cannot read execution policy: {error}") from error
    except json.JSONDecodeError as error:
        raise PolicyError(f"invalid execution policy JSON: {error}") from error
    if not isinstance(document, dict):
        raise PolicyError("execution policy root must be an object")

    allowed_keys = {
        "$schema",
        "schema_version",
        "stability",
        "policy_id",
        "backend",
        "writable_paths",
        "timeout_seconds",
        "required_capabilities",
    }
    unknown = sorted(set(document) - allowed_keys)
    if unknown:
        raise PolicyError(
            "unknown provisional execution policy field(s): "
            + ", ".join(unknown)
        )
    if document.get("schema_version") != SCHEMA_VERSION:
        raise PolicyError("execution policy schema_version must be 1")
    if document.get("stability") != STABILITY:
        raise PolicyError("execution policy stability must be provisional")

    policy_id = document.get("policy_id")
    if not isinstance(policy_id, str) or not POLICY_ID.fullmatch(policy_id):
        raise PolicyError("execution policy policy_id is invalid")
    backend = document.get("backend")
    if backend != BACKEND_NAME:
        raise PolicyError(
            "v1.3 provisional core only supports the bubblewrap backend"
        )

    raw_paths = document.get("writable_paths")
    if not isinstance(raw_paths, list) or not raw_paths:
        raise PolicyError("execution policy requires writable_paths")
    writable_paths = tuple(_safe_relative_path(item) for item in raw_paths)
    if len(set(writable_paths)) != len(writable_paths):
        raise PolicyError("execution policy writable_paths must be unique")
    for parent in writable_paths:
        prefix = parent + "/"
        if any(
            child != parent and child.startswith(prefix)
            for child in writable_paths
        ):
            raise PolicyError(
                f"nested writable path is redundant and ambiguous: {parent}"
            )

    timeout = document.get("timeout_seconds")
    if (
        isinstance(timeout, bool)
        or not isinstance(timeout, (int, float))
        or not math.isfinite(float(timeout))
        or not 1 <= float(timeout) <= 7200
    ):
        raise PolicyError(
            "execution policy timeout_seconds must be finite in [1, 7200]"
        )

    raw_capabilities = document.get("required_capabilities")
    if not isinstance(raw_capabilities, list) or not raw_capabilities:
        raise PolicyError("execution policy requires required_capabilities")
    if any(not isinstance(item, str) for item in raw_capabilities):
        raise PolicyError("execution policy capabilities must be strings")
    capabilities = frozenset(raw_capabilities)
    unknown_capabilities = sorted(capabilities - CORE_CAPABILITIES)
    if unknown_capabilities:
        raise PolicyError(
            "unknown provisional capability: "
            + ", ".join(unknown_capabilities)
        )
    missing = sorted(CORE_CAPABILITIES - capabilities)
    if missing:
        raise PolicyError(
            "provisional core policy must fail closed on missing capability: "
            + ", ".join(missing)
        )

    return ExecutionPolicy(
        policy_id=policy_id,
        backend=backend,
        writable_paths=writable_paths,
        timeout_seconds=float(timeout),
        required_capabilities=capabilities,
        document=document,
        digest=sha256_json(document),
    )


def probe_bubblewrap(
    *,
    which: Callable[[str], Optional[str]] = shutil.which,
    run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> BackendProbe:
    if not sys.platform.startswith("linux"):
        return BackendProbe(
            BACKEND_NAME,
            False,
            "",
            frozenset(),
            "bubblewrap is only supported by this provisional adapter on Linux",
        )
    executable = which("bwrap")
    if not executable:
        return BackendProbe(
            BACKEND_NAME,
            False,
            "",
            frozenset(),
            "bwrap executable was not found",
        )
    try:
        result = run(
            [executable, "--version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return BackendProbe(
            BACKEND_NAME,
            False,
            "",
            frozenset(),
            f"bwrap probe failed: {error}",
        )
    version = (result.stdout or "").strip().splitlines()
    if result.returncode != 0:
        return BackendProbe(
            BACKEND_NAME,
            False,
            version[-1] if version else "",
            frozenset(),
            f"bwrap --version exited with {result.returncode}",
        )
    return BackendProbe(
        BACKEND_NAME,
        True,
        version[-1] if version else "bubblewrap (version unknown)",
        CORE_CAPABILITIES,
        "",
    )


def _is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def prepare_bubblewrap(
    policy: ExecutionPolicy,
    *,
    project_dir: Path | str,
    evidence_root: Path | str,
    command: Sequence[str],
    cwd: Path | str | None = None,
    probe: BackendProbe | None = None,
    executable: str | None = None,
) -> PreparedSandbox:
    if not command or any(not isinstance(item, str) or not item for item in command):
        raise PolicyError("sandbox command must be a non-empty argv sequence")
    project = Path(project_dir).resolve()
    evidence = Path(evidence_root).resolve()
    working_dir = Path(cwd).resolve() if cwd is not None else project
    if not project.is_dir():
        raise PolicyError(f"project directory does not exist: {project}")
    if not evidence.is_dir():
        raise PolicyError(f"evidence root does not exist: {evidence}")
    if _is_relative_to(evidence, project) or _is_relative_to(project, evidence):
        raise PolicyError(
            "evidence root and project workspace must be disjoint"
        )
    if not _is_relative_to(working_dir, project):
        raise PolicyError("sandbox cwd must remain inside the project workspace")

    active_probe = probe or probe_bubblewrap()
    unavailable = policy.required_capabilities - active_probe.capabilities
    if not active_probe.available or unavailable:
        details = active_probe.reason or (
            "missing capability: " + ", ".join(sorted(unavailable))
        )
        raise CapabilityUnavailable(details)

    resolved_writable: list[Path] = []
    resolved_keys: set[str] = set()
    for relative in policy.writable_paths:
        candidate = project.joinpath(*PurePosixPath(relative).parts)
        candidate.mkdir(parents=True, exist_ok=True)
        resolved = candidate.resolve()
        if not _is_relative_to(resolved, project):
            raise PolicyError(
                f"writable path escapes the project after resolution: {relative}"
            )
        resolved_key = os.path.normcase(str(resolved))
        if resolved_key in resolved_keys:
            raise PolicyError(
                "writable paths resolve to the same directory: "
                f"{relative}"
            )
        resolved_keys.add(resolved_key)
        resolved_writable.append(resolved)

    bwrap = executable or shutil.which("bwrap")
    if not bwrap:
        raise CapabilityUnavailable("bwrap executable was not found")
    wrapped: list[str] = [
        str(bwrap),
        "--die-with-parent",
        "--new-session",
        "--unshare-net",
        "--unshare-pid",
        "--ro-bind",
        "/",
        "/",
        "--proc",
        "/proc",
        "--dev",
        "/dev",
        "--tmpfs",
        str(evidence),
    ]
    for writable in resolved_writable:
        wrapped.extend(["--bind", str(writable), str(writable)])
    wrapped.extend(["--chdir", str(working_dir), "--"])
    wrapped.extend(command)

    attestation = {
        "schema_version": SCHEMA_VERSION,
        "stability": STABILITY,
        "state": "prepared",
        "backend": active_probe.as_dict(),
        "policy_id": policy.policy_id,
        "policy_digest": policy.digest,
        "enforced_capabilities": sorted(policy.required_capabilities),
        "unavailable_capabilities": [],
        "workspace_mode": "read_only_with_write_allowlist",
        "writable_paths": list(policy.writable_paths),
        "network": "denied",
        "evidence_mount": "empty_tmpfs_overlay",
        "timeout_seconds": policy.timeout_seconds,
        "cwd": working_dir.relative_to(project).as_posix() or ".",
        "command_digest": hashlib.sha256(
            canonical_json(list(command))
        ).hexdigest(),
    }
    return PreparedSandbox(
        argv=tuple(wrapped),
        policy_digest=policy.digest,
        attestation=attestation,
    )


def finalized_attestation(
    prepared: PreparedSandbox,
    *,
    launched: bool,
    returncode: int | None,
    timed_out: bool,
    process_tree_cleaned: bool,
) -> dict[str, Any]:
    attestation = dict(prepared.attestation)
    attestation.update(
        {
            "state": "enforced" if launched else "not_launched",
            "launched": launched,
            "returncode": returncode,
            "timed_out": timed_out,
            "process_tree_cleaned": process_tree_cleaned,
        }
    )
    if launched and not process_tree_cleaned:
        attestation["state"] = "enforcement_failed"
    return attestation
