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
import socket
import stat
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Mapping, Optional, Sequence


SCHEMA_VERSION = 1
STABILITY = "provisional"
BACKEND_NAME = "bubblewrap"
LIVE_PROBE_VERIFICATION = "live_minimal_isolation_probe_v2"
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
PROTECTED_WORKSPACE_ROOTS = frozenset(
    {".agents", ".git", "control", "evidence"}
)
DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
MAX_COMMAND_ARGUMENTS = 1024
MAX_COMMAND_BYTES = 256 * 1024
MAX_SANDBOX_ENVIRONMENT_ENTRIES = 32
MAX_SANDBOX_ENVIRONMENT_BYTES = 32 * 1024

# Strict mode never imports the caller's process environment.  These are the
# only names the trusted control plane may deliberately place *inside* the
# already-created bubblewrap namespace.
SANDBOX_ENVIRONMENT_ALLOWLIST = frozenset(
    {
        "CI",
        "GRADLE_USER_HOME",
        "HOME",
        "JAVA_HOME",
        "LANG",
        "LANGUAGE",
        "LC_ALL",
        "LC_CTYPE",
        "LOGNAME",
        "NO_COLOR",
        "PATH",
        "SOURCE_DATE_EPOCH",
        "TEMP",
        "TERM",
        "TMP",
        "TMPDIR",
        "TZ",
        "USER",
        "XDG_RUNTIME_DIR",
    }
)

# Mount executable/runtime material, not the host root.  In particular this
# excludes /home, /root, /run, /tmp, /var, /mnt, /srv, /opt, /usr/src and
# /usr/local/src.  A runtime installed outside these paths is unsupported by
# the strict adapter rather than silently broadening host visibility.
SYSTEM_RUNTIME_READONLY_CANDIDATES = (
    "/bin",
    "/sbin",
    "/lib",
    "/lib64",
    "/usr/bin",
    "/usr/sbin",
    "/usr/lib",
    "/usr/lib64",
    "/usr/libexec",
    "/usr/local/bin",
    "/usr/local/lib",
    "/usr/local/lib64",
    "/usr/share",
)
SYSTEM_CONFIG_READONLY_CANDIDATES = (
    "/etc/alternatives",
    "/etc/fonts",
    "/etc/group",
    "/etc/ld.so.cache",
    "/etc/ld.so.conf",
    "/etc/ld.so.conf.d",
    "/etc/localtime",
    "/etc/nsswitch.conf",
    "/etc/passwd",
    "/etc/pki",
    "/etc/ssl/certs",
    "/etc/timezone",
)
EPHEMERAL_SANDBOX_ROOTS = ("/home", "/run", "/tmp")


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


def sha256_file(path: Path | str) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _default_sandbox_environment(project: Path) -> dict[str, str]:
    return {
        "GRADLE_USER_HOME": str(project / ".gradle"),
        "HOME": "/home/studio",
        "LANG": "C",
        "LC_ALL": "C",
        "LOGNAME": "studio",
        "PATH": (
            "/usr/local/bin:/usr/bin:/bin:"
            "/usr/local/sbin:/usr/sbin:/sbin"
        ),
        "TEMP": "/tmp",
        "TMP": "/tmp",
        "TMPDIR": "/tmp",
        "USER": "studio",
        "XDG_RUNTIME_DIR": "/run/studio",
    }


def _normalize_sandbox_environment(
    environment: Optional[Mapping[str, str]],
    *,
    project: Path,
) -> dict[str, str]:
    if environment is not None and not isinstance(environment, Mapping):
        raise PolicyError("sandbox environment must be a string mapping")
    normalized = _default_sandbox_environment(project)
    if environment is not None:
        if len(environment) > MAX_SANDBOX_ENVIRONMENT_ENTRIES:
            raise PolicyError("sandbox environment has too many entries")
        for key, value in environment.items():
            if (
                not isinstance(key, str)
                or key not in SANDBOX_ENVIRONMENT_ALLOWLIST
            ):
                raise PolicyError(
                    f"sandbox environment variable is not permitted: {key!r}"
                )
            if (
                not isinstance(value, str)
                or "\0" in value
                or value in {"--", "--clearenv", "--setenv"}
            ):
                raise PolicyError(
                    f"sandbox environment value is unsafe: {key!r}"
                )
            normalized[key] = value

    reserved = _default_sandbox_environment(project)
    for key in (
        "GRADLE_USER_HOME",
        "HOME",
        "TEMP",
        "TMP",
        "TMPDIR",
        "XDG_RUNTIME_DIR",
    ):
        if normalized[key] != reserved[key]:
            raise PolicyError(
                f"sandbox environment cannot override isolated {key}"
            )
    if len(normalized) > MAX_SANDBOX_ENVIRONMENT_ENTRIES:
        raise PolicyError("sandbox environment has too many entries")
    encoded_size = sum(
        len(key.encode("utf-8")) + len(value.encode("utf-8")) + 2
        for key, value in normalized.items()
    )
    if encoded_size > MAX_SANDBOX_ENVIRONMENT_BYTES:
        raise PolicyError("sandbox environment exceeds its size budget")
    return dict(sorted(normalized.items()))


def _runtime_readonly_mounts() -> tuple[str, ...]:
    mounts: list[str] = []
    for raw in (
        *SYSTEM_RUNTIME_READONLY_CANDIDATES,
        *SYSTEM_CONFIG_READONLY_CANDIDATES,
    ):
        path = Path(raw)
        try:
            exists = path.exists()
            acceptable = path.is_file() or path.is_dir()
        except OSError:
            continue
        if exists and acceptable:
            mounts.append(raw)
    return tuple(mounts)


def _scaffold_directories(paths: Sequence[Path]) -> list[str]:
    ephemeral = {Path(raw) for raw in EPHEMERAL_SANDBOX_ROOTS}
    directories: set[Path] = {
        Path("/home/studio"),
        Path("/run/studio"),
    }
    for path in paths:
        try:
            current = path.parent if path.is_file() else path
        except OSError:
            current = path.parent
        while current != current.parent and current != Path("/"):
            if current not in ephemeral:
                directories.add(current)
            current = current.parent
    result: list[str] = []
    for directory in sorted(
        directories,
        key=lambda item: (len(item.parts), item.as_posix()),
    ):
        result.extend(["--dir", str(directory)])
    return result


def _minimal_bubblewrap_argv(
    *,
    executable: Path,
    project: Path,
    evidence: Path,
    writable: Sequence[Path],
    working_dir: Path,
    command: Sequence[str],
    environment: Mapping[str, str],
    runtime_mounts: Sequence[str],
) -> list[str]:
    wrapped: list[str] = [
        str(executable),
        "--die-with-parent",
        "--new-session",
        "--unshare-net",
        "--unshare-pid",
        "--clearenv",
    ]
    for key, value in sorted(environment.items()):
        wrapped.extend(["--setenv", key, value])
    wrapped.extend(
        [
            "--proc",
            "/proc",
            "--dev",
            "/dev",
        ]
    )
    for ephemeral in EPHEMERAL_SANDBOX_ROOTS:
        wrapped.extend(["--tmpfs", ephemeral])
    scaffold_targets = [
        *(Path(raw) for raw in runtime_mounts),
        project.parent,
        evidence,
    ]
    wrapped.extend(_scaffold_directories(scaffold_targets))
    for runtime_path in runtime_mounts:
        wrapped.extend(["--ro-bind", runtime_path, runtime_path])
    wrapped.extend(["--ro-bind", str(project), str(project)])
    wrapped.extend(["--tmpfs", str(evidence)])
    for writable_path in writable:
        wrapped.extend(
            ["--bind", str(writable_path), str(writable_path)]
        )
    wrapped.extend(["--chdir", str(working_dir), "--"])
    wrapped.extend(command)
    return wrapped


def _path_targets_protected_root(
    path: Path,
    *,
    project: Path,
    protected_roots: Sequence[Path],
) -> bool:
    if _is_relative_to(path, project):
        relative = path.relative_to(project)
        if (
            relative.parts
            and relative.parts[0].casefold()
            in PROTECTED_WORKSPACE_ROOTS
        ):
            return True
    return any(
        path == protected or _is_relative_to(path, protected)
        for protected in protected_roots
    )


def _protected_workspace_paths(project: Path) -> tuple[Path, ...]:
    protected: set[Path] = set()
    for name in PROTECTED_WORKSPACE_ROOTS:
        lexical = project / name
        protected.add(lexical)
        try:
            protected.add(lexical.resolve(strict=False))
        except OSError:
            # Keeping the lexical path is fail-closed for the normal case;
            # an unresolvable alias is rejected when a writable path reaches
            # it below.
            continue
    return tuple(protected)


def _resolve_writable_paths(
    project: Path,
    relative_paths: Sequence[str],
    *,
    create: bool,
) -> list[Path]:
    protected_roots = _protected_workspace_paths(project)
    resolved_writable: list[Path] = []
    resolved_keys: set[str] = set()
    for relative in relative_paths:
        canonical_relative = _safe_relative_path(relative)
        candidate = project.joinpath(
            *PurePosixPath(canonical_relative).parts
        )
        try:
            before_creation = candidate.resolve(strict=False)
        except OSError as error:
            raise PolicyError(
                f"writable path cannot be resolved safely: {relative}: {error}"
            ) from error
        if not _is_relative_to(before_creation, project):
            raise PolicyError(
                f"writable path escapes the project after resolution: {relative}"
            )
        if _path_targets_protected_root(
            before_creation,
            project=project,
            protected_roots=protected_roots,
        ):
            raise PolicyError(
                "writable path resolves into a protected control root: "
                f"{relative}"
            )
        if create:
            try:
                candidate.mkdir(parents=True, exist_ok=True)
            except OSError as error:
                raise PolicyError(
                    f"writable path cannot be created safely: {relative}: {error}"
                ) from error
        try:
            resolved = candidate.resolve(strict=create)
        except OSError as error:
            raise PolicyError(
                f"writable path cannot be resolved safely: {relative}: {error}"
            ) from error
        if not _is_relative_to(resolved, project):
            raise PolicyError(
                f"writable path escapes the project after resolution: {relative}"
            )
        if _path_targets_protected_root(
            resolved,
            project=project,
            protected_roots=protected_roots,
        ):
            raise PolicyError(
                "writable path resolves into a protected control root: "
                f"{relative}"
            )
        resolved_key = os.path.normcase(str(resolved))
        if resolved_key in resolved_keys:
            raise PolicyError(
                "writable paths resolve to the same directory: "
                f"{relative}"
            )
        resolved_keys.add(resolved_key)
        resolved_writable.append(resolved)
    return resolved_writable


def _path_is_runtime_visible(
    path: Path,
    runtime_mounts: Sequence[str],
) -> bool:
    try:
        resolved = path.resolve(strict=False)
    except OSError:
        return False
    return any(
        resolved == Path(raw).resolve(strict=False)
        or _is_relative_to(resolved, Path(raw).resolve(strict=False))
        for raw in runtime_mounts
    )


def _validate_command_runtime(
    command: Sequence[str],
    *,
    project: Path,
    working_dir: Path,
    environment: Mapping[str, str],
    runtime_mounts: Sequence[str],
) -> None:
    # Fake probes are used by the standard-library tests on non-Linux hosts.
    # A real non-Linux prepare still fails at probe_bubblewrap().
    if not sys.platform.startswith("linux"):
        return
    executable = command[0]
    if "/" in executable:
        candidate = Path(executable)
        if not candidate.is_absolute():
            candidate = working_dir / candidate
        resolved = candidate.resolve(strict=False)
    else:
        located = shutil.which(
            executable,
            path=environment.get("PATH", ""),
        )
        if not located:
            raise CapabilityUnavailable(
                f"sandbox command is unavailable in the clean PATH: {executable}"
            )
        resolved = Path(located).resolve(strict=False)
    if not (
        _is_relative_to(resolved, project)
        or _path_is_runtime_visible(resolved, runtime_mounts)
    ):
        raise CapabilityUnavailable(
            "sandbox command runtime is outside the minimal system mounts: "
            f"{resolved}"
        )


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
    if path.parts[0].casefold() in PROTECTED_WORKSPACE_ROOTS:
        raise PolicyError(
            "writable path targets a protected control root: "
            f"{path.parts[0]!r}"
        )
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
    executable: str = ""
    executable_sha256: str = ""
    verification: str = ""
    probe_digest: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "backend": self.backend,
            "available": self.available,
            "version": self.version,
            "capabilities": sorted(self.capabilities),
            "reason": self.reason,
            "executable": self.executable,
            "executable_sha256": self.executable_sha256,
            "verification": self.verification,
            "probe_digest": self.probe_digest,
        }


@dataclass(frozen=True)
class PreparedSandbox:
    argv: tuple[str, ...]
    command: tuple[str, ...]
    policy_digest: str
    project_dir: str
    evidence_root: str
    backend_executable: str
    backend_executable_sha256: str
    timeout_seconds: float
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
    if len(capabilities) != len(raw_capabilities):
        raise PolicyError("execution policy capabilities must be unique")
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


def validate_execution_policy(policy: ExecutionPolicy) -> None:
    """Revalidate an in-memory policy so dataclass construction cannot bypass IO."""
    if not isinstance(policy, ExecutionPolicy):
        raise PolicyError("execution policy must be loaded and validated")
    if policy.digest != sha256_json(dict(policy.document)):
        raise PolicyError("execution policy document digest mismatch")
    try:
        document_timeout = float(policy.document.get("timeout_seconds", -1))
    except (TypeError, ValueError) as error:
        raise PolicyError(
            "execution policy document timeout is invalid"
        ) from error
    if (
        policy.document.get("schema_version") != SCHEMA_VERSION
        or policy.document.get("stability") != STABILITY
        or policy.document.get("policy_id") != policy.policy_id
        or policy.document.get("backend") != policy.backend
        or policy.document.get("writable_paths")
        != list(policy.writable_paths)
        or document_timeout != policy.timeout_seconds
        or frozenset(policy.document.get("required_capabilities", []))
        != policy.required_capabilities
    ):
        raise PolicyError("execution policy fields differ from its document")
    if policy.backend != BACKEND_NAME or not POLICY_ID.fullmatch(
        policy.policy_id
    ):
        raise PolicyError("execution policy identity/backend is invalid")
    normalized_paths = tuple(
        _safe_relative_path(path) for path in policy.writable_paths
    )
    if normalized_paths != policy.writable_paths:
        raise PolicyError("execution policy writable paths are not canonical")
    if policy.required_capabilities != CORE_CAPABILITIES:
        raise PolicyError("execution policy capabilities are not fail-closed")


def probe_bubblewrap(
    *,
    which: Callable[[str], Optional[str]] = shutil.which,
    run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> BackendProbe:
    def unavailable(
        reason: str,
        *,
        executable: str = "",
        executable_sha256: str = "",
    ) -> BackendProbe:
        return BackendProbe(
            BACKEND_NAME,
            False,
            "",
            frozenset(),
            reason,
            executable,
            executable_sha256,
            "",
            "",
        )

    if not sys.platform.startswith("linux"):
        return unavailable(
            "bubblewrap is only supported by this provisional adapter on Linux",
        )
    located = which("bwrap")
    if not located:
        return unavailable("bwrap executable was not found")
    executable_path = Path(located).resolve()
    try:
        executable_stat = executable_path.stat()
        executable_digest = sha256_file(executable_path)
    except OSError as error:
        return unavailable(f"bwrap executable could not be inspected: {error}")
    if not executable_path.is_file():
        return unavailable(f"bwrap is not a regular file: {executable_path}")
    if executable_stat.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        return unavailable(
            "bwrap executable is group/world writable",
            executable=str(executable_path),
            executable_sha256=executable_digest,
        )
    if hasattr(executable_stat, "st_uid") and executable_stat.st_uid != 0:
        return unavailable(
            "strict backend requires a root-owned bwrap executable",
            executable=str(executable_path),
            executable_sha256=executable_digest,
        )

    try:
        version_result = run(
            [str(executable_path), "--version"],
            env={},
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
            timeout=5,
        )
    except (OSError, PolicyError, subprocess.TimeoutExpired) as error:
        return unavailable(
            f"bwrap version probe failed: {error}",
            executable=str(executable_path),
            executable_sha256=executable_digest,
        )
    version_lines = (version_result.stdout or "").strip().splitlines()
    version = (
        version_lines[-1] if version_lines else "bubblewrap (version unknown)"
    )
    if version_result.returncode != 0:
        return unavailable(
            f"bwrap --version exited with {version_result.returncode}",
            executable=str(executable_path),
            executable_sha256=executable_digest,
        )

    # A version string does not prove that user namespaces and mounts work on
    # this host.  Exercise the exact minimal mount/environment shape before
    # advertising any capability.  The probe deliberately creates a sibling
    # secret, sibling repository and pathname UNIX socket that must all remain
    # absent from the child namespace.
    try:
        with tempfile.TemporaryDirectory(prefix="afs-bwrap-probe-") as raw:
            probe_root = Path(raw).resolve()
            project = probe_root / "project"
            writable = project / "build"
            evidence = probe_root / "evidence"
            other_repository = probe_root / "other-repository"
            project.mkdir()
            writable.mkdir()
            evidence.mkdir()
            other_repository.mkdir()
            source = project / "source.txt"
            source.write_text("immutable", encoding="utf-8")
            secret = evidence / "secret.txt"
            secret.write_text("authority", encoding="utf-8")
            host_secret = probe_root / "host-secret.txt"
            host_secret.write_text("host-only", encoding="utf-8")
            other_secret = other_repository / "repository-secret.txt"
            other_secret.write_text("other-repository", encoding="utf-8")
            host_socket_path = probe_root / "host-control.sock"
            marker = writable / "probe.txt"
            net_namespace = writable / "netns.txt"
            parent_net_namespace = os.readlink("/proc/self/ns/net")
            runtime_mounts = _runtime_readonly_mounts()
            sandbox_environment = _normalize_sandbox_environment(
                None,
                project=project,
            )
            _validate_command_runtime(
                [sys.executable],
                project=project,
                working_dir=project,
                environment=sandbox_environment,
                runtime_mounts=runtime_mounts,
            )
            script_path = project / "probe.py"
            script_path.write_text(
                "import os\n"
                "import socket\n"
                "from pathlib import Path\n"
                f"source=Path({str(source)!r})\n"
                f"evidence=Path({str(secret)!r})\n"
                f"host_secret=Path({str(host_secret)!r})\n"
                f"other_secret=Path({str(other_secret)!r})\n"
                f"host_socket=Path({str(host_socket_path)!r})\n"
                f"marker=Path({str(marker)!r})\n"
                f"net_namespace=Path({str(net_namespace)!r})\n"
                "assert source.read_text(encoding='utf-8') == 'immutable'\n"
                "source_write_failed=False\n"
                "try:\n"
                "    source.write_text('tampered', encoding='utf-8')\n"
                "except OSError:\n"
                "    source_write_failed=True\n"
                "assert source_write_failed\n"
                "assert not evidence.exists()\n"
                "assert not host_secret.exists()\n"
                "assert not other_secret.exists()\n"
                "assert not Path('/etc/shadow').exists()\n"
                "client=socket.socket(socket.AF_UNIX)\n"
                "try:\n"
                "    client.connect(str(host_socket))\n"
                "except OSError:\n"
                "    pass\n"
                "else:\n"
                "    raise AssertionError('host UNIX socket was reachable')\n"
                "finally:\n"
                "    client.close()\n"
                "assert not host_socket.exists()\n"
                "assert all(item.name == 'studio' for item in Path('/run').iterdir())\n"
                "marker.write_text('ok', encoding='utf-8')\n"
                "net_namespace.write_text(os.readlink('/proc/self/ns/net'), encoding='utf-8')\n",
                encoding="utf-8",
            )
            with socket.socket(
                socket.AF_UNIX,
                socket.SOCK_STREAM,
            ) as host_socket:
                host_socket.bind(str(host_socket_path))
                host_socket.listen(1)
                smoke_argv = _minimal_bubblewrap_argv(
                    executable=executable_path,
                    project=project,
                    evidence=evidence,
                    writable=[writable],
                    working_dir=project,
                    command=[sys.executable, str(script_path)],
                    environment=sandbox_environment,
                    runtime_mounts=runtime_mounts,
                )
                smoke_result = run(
                    smoke_argv,
                    env={},
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    check=False,
                    timeout=10,
                )
            smoke_output = (smoke_result.stdout or "").strip()
            smoke_ok = (
                smoke_result.returncode == 0
                and source.read_text(encoding="utf-8") == "immutable"
                and secret.read_text(encoding="utf-8") == "authority"
                and host_secret.read_text(encoding="utf-8") == "host-only"
                and other_secret.read_text(encoding="utf-8")
                == "other-repository"
                and marker.read_text(encoding="utf-8") == "ok"
                and net_namespace.read_text(encoding="utf-8").strip()
                != parent_net_namespace
            )
            if not smoke_ok:
                return unavailable(
                    "live bwrap isolation probe failed"
                    + (f": {smoke_output}" if smoke_output else ""),
                    executable=str(executable_path),
                    executable_sha256=executable_digest,
                )
            survivor = writable / "survived-timeout.txt"
            cleanup_script = (
                f"(sleep 1; printf survived > {survivor}) & sleep 30"
            )
            cleanup_argv = _minimal_bubblewrap_argv(
                executable=executable_path,
                project=project,
                evidence=evidence,
                writable=[writable],
                working_dir=project,
                command=["/bin/sh", "-c", cleanup_script],
                environment=sandbox_environment,
                runtime_mounts=runtime_mounts,
            )
            try:
                run(
                    cleanup_argv,
                    env={},
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    check=False,
                    timeout=0.2,
                )
                return unavailable(
                    "bwrap process-tree probe exited before its forced timeout",
                    executable=str(executable_path),
                    executable_sha256=executable_digest,
                )
            except subprocess.TimeoutExpired:
                time.sleep(1.2)
                if survivor.exists():
                    return unavailable(
                        "bwrap descendant survived wrapper timeout cleanup",
                        executable=str(executable_path),
                        executable_sha256=executable_digest,
                    )
    except (OSError, PolicyError, subprocess.TimeoutExpired) as error:
        return unavailable(
            f"live bwrap isolation probe failed: {error}",
            executable=str(executable_path),
            executable_sha256=executable_digest,
        )

    probe_document = {
        "backend": BACKEND_NAME,
        "version": version,
        "executable": str(executable_path),
        "executable_sha256": executable_digest,
        "verification": LIVE_PROBE_VERIFICATION,
        "capabilities": sorted(CORE_CAPABILITIES),
    }
    return BackendProbe(
        BACKEND_NAME,
        True,
        version,
        CORE_CAPABILITIES,
        "",
        str(executable_path),
        executable_digest,
        LIVE_PROBE_VERIFICATION,
        sha256_json(probe_document),
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
    environment: Optional[Mapping[str, str]] = None,
) -> PreparedSandbox:
    validate_execution_policy(policy)
    if (
        isinstance(command, (str, bytes))
        or not command
        or len(command) > MAX_COMMAND_ARGUMENTS
        or any(
            not isinstance(item, str)
            or not item
            or "\0" in item
            for item in command
        )
        or sum(len(item.encode("utf-8")) for item in command)
        > MAX_COMMAND_BYTES
    ):
        raise PolicyError("sandbox command must be a non-empty argv sequence")
    project = Path(project_dir).resolve()
    evidence = Path(evidence_root).resolve()
    if cwd is None:
        working_dir = project
    else:
        working_candidate = Path(cwd)
        if not working_candidate.is_absolute():
            working_candidate = project / working_candidate
        working_dir = working_candidate.resolve()
    if not project.is_dir():
        raise PolicyError(f"project directory does not exist: {project}")
    if not evidence.is_dir():
        raise PolicyError(f"evidence root does not exist: {evidence}")
    if _is_relative_to(evidence, project) or _is_relative_to(project, evidence):
        raise PolicyError(
            "evidence root and project workspace must be disjoint"
        )
    if evidence in {Path(raw) for raw in EPHEMERAL_SANDBOX_ROOTS}:
        raise PolicyError(
            "evidence root cannot replace a sandbox ephemeral root"
        )
    if not _is_relative_to(working_dir, project):
        raise PolicyError("sandbox cwd must remain inside the project workspace")

    resolved_writable = _resolve_writable_paths(
        project,
        policy.writable_paths,
        create=True,
    )
    sandbox_environment = _normalize_sandbox_environment(
        environment,
        project=project,
    )
    runtime_mounts = _runtime_readonly_mounts()
    if any(
        _is_relative_to(project, Path(raw).resolve(strict=False))
        or _is_relative_to(evidence, Path(raw).resolve(strict=False))
        for raw in runtime_mounts
    ):
        raise CapabilityUnavailable(
            "project/evidence root overlaps a minimal system runtime mount"
        )
    _validate_command_runtime(
        command,
        project=project,
        working_dir=working_dir,
        environment=sandbox_environment,
        runtime_mounts=runtime_mounts,
    )

    active_probe = probe_bubblewrap()
    if active_probe.backend != BACKEND_NAME:
        raise CapabilityUnavailable(
            f"unexpected sandbox backend: {active_probe.backend!r}"
        )
    unavailable = policy.required_capabilities - active_probe.capabilities
    if not active_probe.available or unavailable:
        details = active_probe.reason or (
            "missing capability: " + ", ".join(sorted(unavailable))
        )
        raise CapabilityUnavailable(details)
    if (
        active_probe.verification != LIVE_PROBE_VERIFICATION
        or not DIGEST_PATTERN.fullmatch(active_probe.executable_sha256)
        or not DIGEST_PATTERN.fullmatch(active_probe.probe_digest)
    ):
        raise CapabilityUnavailable(
            "bubblewrap capabilities lack a live isolation attestation"
        )
    expected_probe_document = {
        "backend": BACKEND_NAME,
        "version": active_probe.version,
        "executable": active_probe.executable,
        "executable_sha256": active_probe.executable_sha256,
        "verification": active_probe.verification,
        "capabilities": sorted(active_probe.capabilities),
    }
    if sha256_json(expected_probe_document) != active_probe.probe_digest:
        raise CapabilityUnavailable("bubblewrap probe attestation digest mismatch")

    if not active_probe.executable:
        raise CapabilityUnavailable("bwrap executable was not found")
    bwrap = Path(active_probe.executable).resolve()
    if str(bwrap) != active_probe.executable:
        raise CapabilityUnavailable(
            "prepared backend executable differs from the live probe"
        )
    try:
        actual_backend_digest = sha256_file(bwrap)
    except OSError as error:
        raise CapabilityUnavailable(
            f"could not re-hash bwrap before launch: {error}"
        ) from error
    if actual_backend_digest != active_probe.executable_sha256:
        raise CapabilityUnavailable(
            "bwrap executable changed after the live capability probe"
        )
    wrapped = _minimal_bubblewrap_argv(
        executable=bwrap,
        project=project,
        evidence=evidence,
        writable=resolved_writable,
        working_dir=working_dir,
        command=command,
        environment=sandbox_environment,
        runtime_mounts=runtime_mounts,
    )

    attestation_without_digest = {
        "schema_version": SCHEMA_VERSION,
        "stability": STABILITY,
        "state": "prepared",
        "backend": active_probe.as_dict(),
        "policy_id": policy.policy_id,
        "policy_digest": policy.digest,
        "requested_capabilities": sorted(policy.required_capabilities),
        "unavailable_capabilities": [],
        "workspace_mode": "read_only_with_write_allowlist",
        "workspace_mount": "explicit_read_only_bind",
        "writable_paths": list(policy.writable_paths),
        "network": "denied",
        "host_root": "not_mounted",
        "system_readonly_mounts": list(runtime_mounts),
        "ephemeral_mounts": list(EPHEMERAL_SANDBOX_ROOTS),
        "evidence_mount": "empty_tmpfs_without_host_bind",
        "environment_mode": "clear_then_set_allowlist",
        "environment": sandbox_environment,
        "environment_digest": sha256_json(sandbox_environment),
        "timeout_seconds": policy.timeout_seconds,
        "cwd": working_dir.relative_to(project).as_posix() or ".",
        "command_digest": hashlib.sha256(
            canonical_json(list(command))
        ).hexdigest(),
    }
    attestation = dict(attestation_without_digest)
    attestation["attestation_digest"] = sha256_json(
        attestation_without_digest
    )
    return PreparedSandbox(
        argv=tuple(wrapped),
        command=tuple(command),
        policy_digest=policy.digest,
        project_dir=str(project),
        evidence_root=str(evidence),
        backend_executable=str(bwrap),
        backend_executable_sha256=actual_backend_digest,
        timeout_seconds=policy.timeout_seconds,
        attestation=attestation,
    )


def validate_prepared_sandbox(prepared: PreparedSandbox) -> None:
    """Fail closed unless a prepared launch still matches its live probe."""
    if not isinstance(prepared, PreparedSandbox):
        raise CapabilityUnavailable("runner requires a PreparedSandbox")
    attestation = dict(prepared.attestation)
    claimed_digest = attestation.pop("attestation_digest", None)
    if (
        not isinstance(claimed_digest, str)
        or not DIGEST_PATTERN.fullmatch(claimed_digest)
        or sha256_json(attestation) != claimed_digest
    ):
        raise CapabilityUnavailable("prepared attestation digest mismatch")
    if attestation.get("state") != "prepared":
        raise CapabilityUnavailable("sandbox attestation is not prepared")
    if attestation.get("policy_digest") != prepared.policy_digest:
        raise CapabilityUnavailable("prepared policy digest mismatch")
    attested_timeout = attestation.get("timeout_seconds")
    if (
        isinstance(attested_timeout, bool)
        or not isinstance(attested_timeout, (int, float))
        or not math.isfinite(float(attested_timeout))
        or float(attested_timeout) != prepared.timeout_seconds
    ):
        raise CapabilityUnavailable(
            "prepared timeout differs from the frozen attestation"
        )
    backend = attestation.get("backend")
    if (
        not isinstance(backend, Mapping)
        or backend.get("backend") != BACKEND_NAME
        or backend.get("available") is not True
        or backend.get("verification") != LIVE_PROBE_VERIFICATION
        or backend.get("executable") != prepared.backend_executable
        or backend.get("executable_sha256")
        != prepared.backend_executable_sha256
    ):
        raise CapabilityUnavailable("prepared live backend proof is invalid")
    probe_document = {
        "backend": backend["backend"],
        "version": backend.get("version"),
        "executable": backend.get("executable"),
        "executable_sha256": backend.get("executable_sha256"),
        "verification": backend.get("verification"),
        "capabilities": backend.get("capabilities"),
    }
    if (
        backend.get("probe_digest") != sha256_json(probe_document)
        or set(backend.get("capabilities", [])) != CORE_CAPABILITIES
    ):
        raise CapabilityUnavailable("prepared backend probe digest mismatch")
    try:
        current_digest = sha256_file(prepared.backend_executable)
    except OSError as error:
        raise CapabilityUnavailable(
            f"could not verify prepared backend executable: {error}"
        ) from error
    if current_digest != prepared.backend_executable_sha256:
        raise CapabilityUnavailable(
            "prepared backend executable changed before launch"
        )
    project = Path(prepared.project_dir)
    evidence = Path(prepared.evidence_root)
    if (
        str(project.resolve()) != prepared.project_dir
        or str(evidence.resolve()) != prepared.evidence_root
        or not project.is_dir()
        or not evidence.is_dir()
        or _is_relative_to(project, evidence)
        or _is_relative_to(evidence, project)
    ):
        raise CapabilityUnavailable("prepared sandbox roots are not canonical")
    writable_paths = attestation.get("writable_paths")
    if (
        not isinstance(writable_paths, list)
        or any(not isinstance(item, str) for item in writable_paths)
    ):
        raise CapabilityUnavailable(
            "prepared attestation lacks writable path bindings"
        )
    try:
        resolved_writable = _resolve_writable_paths(
            project,
            writable_paths,
            create=False,
        )
    except PolicyError as error:
        raise CapabilityUnavailable(
            f"prepared writable path is no longer safe: {error}"
        ) from error

    raw_environment = attestation.get("environment")
    if not isinstance(raw_environment, Mapping):
        raise CapabilityUnavailable(
            "prepared attestation lacks a sandbox environment"
        )
    try:
        sandbox_environment = _normalize_sandbox_environment(
            raw_environment,
            project=project,
        )
    except PolicyError as error:
        raise CapabilityUnavailable(
            f"prepared sandbox environment is unsafe: {error}"
        ) from error
    if (
        attestation.get("environment_mode")
        != "clear_then_set_allowlist"
        or dict(raw_environment) != sandbox_environment
        or attestation.get("environment_digest")
        != sha256_json(sandbox_environment)
    ):
        raise CapabilityUnavailable(
            "prepared sandbox environment binding is invalid"
        )

    runtime_mounts = _runtime_readonly_mounts()
    if (
        attestation.get("host_root") != "not_mounted"
        or attestation.get("workspace_mount")
        != "explicit_read_only_bind"
        or attestation.get("system_readonly_mounts")
        != list(runtime_mounts)
        or attestation.get("ephemeral_mounts")
        != list(EPHEMERAL_SANDBOX_ROOTS)
        or attestation.get("evidence_mount")
        != "empty_tmpfs_without_host_bind"
    ):
        raise CapabilityUnavailable(
            "prepared minimal mount attestation is invalid"
        )
    expected_cwd = (
        project
        if attestation.get("cwd") == "."
        else project.joinpath(
            *PurePosixPath(str(attestation.get("cwd"))).parts
        )
    ).resolve()
    if not _is_relative_to(expected_cwd, project):
        raise CapabilityUnavailable("prepared sandbox cwd drifted")
    if attestation.get("command_digest") != sha256_json(
        list(prepared.command)
    ):
        raise CapabilityUnavailable("prepared command digest mismatch")
    try:
        _validate_command_runtime(
            prepared.command,
            project=project,
            working_dir=expected_cwd,
            environment=sandbox_environment,
            runtime_mounts=runtime_mounts,
        )
    except PolicyError as error:
        raise CapabilityUnavailable(
            f"prepared command runtime is no longer available: {error}"
        ) from error
    expected_argv = tuple(
        _minimal_bubblewrap_argv(
            executable=Path(prepared.backend_executable),
            project=project,
            evidence=evidence,
            writable=resolved_writable,
            working_dir=expected_cwd,
            command=prepared.command,
            environment=sandbox_environment,
            runtime_mounts=runtime_mounts,
        )
    )
    if prepared.argv != expected_argv:
        raise CapabilityUnavailable(
            "prepared argv differs from the exact minimal sandbox plan"
        )
    if ("--ro-bind", "/", "/") in tuple(
        tuple(prepared.argv[index : index + 3])
        for index in range(max(0, len(prepared.argv) - 2))
    ):
        raise CapabilityUnavailable("prepared argv exposes the host root")


def finalized_attestation(
    prepared: PreparedSandbox,
    *,
    launched: bool,
    returncode: int | None,
    timed_out: bool,
    process_tree_cleaned: bool,
    process_identity: Optional[Mapping[str, Any]] = None,
    cleanup_verification: str = "",
) -> dict[str, Any]:
    validate_prepared_sandbox(prepared)
    if launched:
        if (
            not isinstance(process_identity, Mapping)
            or not isinstance(process_identity.get("pid"), int)
            or isinstance(process_identity.get("pid"), bool)
            or process_identity["pid"] <= 0
            or not isinstance(process_identity.get("start_token"), str)
            or not process_identity["start_token"]
            or process_identity.get("platform") != "linux-procfs"
        ):
            raise CapabilityUnavailable(
                "launched attestation requires a captured process identity"
            )
    elif process_identity is not None:
        raise CapabilityUnavailable(
            "non-launched attestation cannot claim a process identity"
        )
    if process_tree_cleaned and cleanup_verification not in {
        "bubblewrap_pid_namespace_reaped",
        "prelaunch_not_applicable",
    }:
        raise CapabilityUnavailable(
            "process-tree cleanup requires a recognized runtime verification"
        )

    prepared_attestation = dict(prepared.attestation)
    prepared_attestation.pop("attestation_digest", None)
    attestation = dict(prepared_attestation)
    attestation.update(
        {
            "state": "enforced" if launched else "not_launched",
            "launched": launched,
            "returncode": returncode,
            "timed_out": timed_out,
            "process_tree_cleaned": process_tree_cleaned,
            "process_identity": (
                dict(process_identity) if process_identity is not None else None
            ),
            "cleanup_verification": cleanup_verification,
        }
    )
    if launched and not process_tree_cleaned:
        attestation["state"] = "enforcement_failed"
    attestation["attestation_digest"] = sha256_json(attestation)
    return attestation
