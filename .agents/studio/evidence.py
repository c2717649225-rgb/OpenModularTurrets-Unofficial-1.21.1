#!/usr/bin/env python3
"""External, append-only evidence journal primitives.

This module is deliberately independent from the host pipeline.  A trusted
Runner owns an :class:`EvidenceLedger` outside the Builder workspace, while
pipeline and gate reports remain non-authoritative staging inputs whose
content digests can be recorded in the journal.

The format is provisional for v1.3.  It uses only the Python standard library
and fails closed on malformed, non-canonical, truncated, reordered, deleted,
or hash-mismatched events.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from contextlib import contextmanager
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterator, Mapping, Optional, Sequence


SCHEMA_VERSION = 1
STABILITY = "provisional"
JOURNAL_NAME = "authoritative-journal.jsonl"
BUNDLE_RELATIVE_PATH = Path("sealed-evidence") / "bundle.json"
LOCK_NAME = ".journal.lock"
RUN_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
TRANSITION_ID_PATTERN = re.compile(
    r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$"
)
EVENT_TYPE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{1,63}$")
DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
OUTCOMES = frozenset(
    {
        "PASS",
        "FAIL",
        "BLOCKED",
        "TIMEOUT",
        "VERIFIED_INFRA_ERROR",
    }
)
MAX_EVIDENCE_BYTES = 10 * 1024 * 1024
# Every non-terminal append leaves enough room for a compact terminal event and
# bundle.  Large reports/logs belong in artifact storage and are represented
# here only by bounded path digests.
MINIMUM_SEAL_RESERVE_BYTES = 64 * 1024
EVENT_KEYS = frozenset(
    {
        "schema_version",
        "stability",
        "run_id",
        "sequence",
        "transition_id",
        "event_type",
        "recorded_at_utc",
        "previous_event_digest",
        "payload",
        "event_digest",
    }
)
BUNDLE_KEYS = frozenset(
    {
        "schema_version",
        "stability",
        "kind",
        "run_id",
        "outcome",
        "sealed_at_utc",
        "event_count",
        "journal_head_digest",
        "journal_sha256",
        "terminal_transition_id",
        "terminal_event_digest",
        "summary",
        "bundle_digest",
    }
)
STARTED_PAYLOAD_KEYS = frozenset(
    {
        "argv",
        "cwd",
        "host_workspace",
        "digests",
        "report_staging",
        "declared_reports",
        "declared_artifacts",
    }
)
DIGEST_BINDING_KEYS = frozenset(
    {
        "host_tree_digest",
        "control_digest",
        "input_digest",
        "policy_digest",
    }
)
DECLARED_PATH_KEYS = frozenset({"name", "path"})
SEALED_PAYLOAD_KEYS = frozenset(
    {
        "outcome",
        "duration_seconds",
        "exit_code",
        "timed_out",
        "interrupted",
        "reports",
        "artifacts",
        "details",
    }
)
PATH_EVIDENCE_REQUIRED_KEYS = frozenset(
    {
        "name",
        "path",
        "kind",
        "exists",
        "size_bytes",
        "sha256",
    }
)
PATH_EVIDENCE_OPTIONAL_KEYS = frozenset({"entry_count"})
PATH_EVIDENCE_KINDS = frozenset(
    {"file", "directory", "symlink", "missing", "unreadable"}
)


class EvidenceError(RuntimeError):
    """Base class for evidence control-plane failures."""


class EvidencePathError(EvidenceError):
    """Evidence storage is unsafe or overlaps the host workspace."""


class EvidenceIntegrityError(EvidenceError):
    """Journal or bundle contents fail deterministic integrity checks."""


class EvidenceBudgetError(EvidenceIntegrityError):
    """Authoritative evidence would exceed the frozen per-run byte budget."""


class EvidenceSequenceConflict(EvidenceError):
    """The caller's compare-and-swap sequence is stale."""


class EvidenceTransitionConflict(EvidenceError):
    """A transition ID was reused with different semantic content."""


class EvidenceSealedError(EvidenceError):
    """A caller attempted to mutate an already sealed run."""


class EvidenceUnsealedError(EvidenceIntegrityError):
    """A complete bundle was required but the run is not sealed."""


@dataclass(frozen=True)
class ReplayResult:
    """Validated projection reconstructed only from authoritative files."""

    run_id: str
    events: tuple[dict[str, Any], ...]
    bundle: Optional[dict[str, Any]]

    @property
    def last_sequence(self) -> int:
        return self.events[-1]["sequence"] if self.events else 0

    @property
    def head_digest(self) -> Optional[str]:
        return self.events[-1]["event_digest"] if self.events else None

    @property
    def sealed(self) -> bool:
        return bool(
            self.events and self.events[-1]["event_type"] == "RUN_SEALED"
        )

    @property
    def outcome(self) -> Optional[str]:
        if not self.sealed:
            return None
        return self.events[-1]["payload"]["outcome"]


def canonical_json_bytes(value: Any) -> bytes:
    """Return the one canonical JSON representation used by all digests."""
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    except (TypeError, ValueError) as error:
        raise EvidenceIntegrityError(
            f"value is not canonical JSON data: {error}"
        ) from error
    return encoded.encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path | str) -> str:
    target = Path(path)
    digest = hashlib.sha256()
    try:
        with target.open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                digest.update(chunk)
    except OSError as error:
        raise EvidenceIntegrityError(
            f"could not hash file {target}: {error}"
        ) from error
    return digest.hexdigest()


def digest_json(value: Any) -> str:
    return sha256_bytes(canonical_json_bytes(value))


def digest_path(path: Path | str) -> dict[str, Any]:
    """Describe and hash one file, directory, symlink, or missing path.

    Directory digests bind relative names, entry kinds, sizes, and content
    digests.  Symlinks are hashed as links and are never followed.
    """
    target = Path(path)
    absolute = Path(os.path.abspath(target))
    if target.is_symlink():
        try:
            link_target = os.readlink(target)
        except OSError as error:
            raise EvidenceIntegrityError(
                f"could not inspect symlink {target}: {error}"
            ) from error
        content_digest = digest_json(
            {"kind": "symlink", "target": link_target}
        )
        return {
            "path": str(absolute),
            "kind": "symlink",
            "exists": True,
            "size_bytes": len(link_target.encode("utf-8")),
            "sha256": content_digest,
        }
    if not target.exists():
        return {
            "path": str(absolute),
            "kind": "missing",
            "exists": False,
            "size_bytes": None,
            "sha256": None,
        }
    if target.is_file():
        try:
            size = target.stat().st_size
        except OSError as error:
            raise EvidenceIntegrityError(
                f"could not stat file {target}: {error}"
            ) from error
        return {
            "path": str(target.resolve()),
            "kind": "file",
            "exists": True,
            "size_bytes": size,
            "sha256": sha256_file(target),
        }
    if not target.is_dir():
        raise EvidenceIntegrityError(
            f"unsupported evidence input type: {target}"
        )

    root = target.resolve()
    entries: list[dict[str, Any]] = []
    total_size = 0
    try:
        for current_root, directory_names, file_names in os.walk(
            root, topdown=True, followlinks=False
        ):
            directory_names.sort()
            file_names.sort()
            current = Path(current_root)

            retained_directories: list[str] = []
            for name in directory_names:
                child = current / name
                relative = child.relative_to(root).as_posix()
                if child.is_symlink():
                    link_target = os.readlink(child)
                    entries.append(
                        {
                            "path": relative,
                            "kind": "symlink",
                            "target": link_target,
                        }
                    )
                else:
                    entries.append(
                        {"path": relative, "kind": "directory"}
                    )
                    retained_directories.append(name)
            directory_names[:] = retained_directories

            for name in file_names:
                child = current / name
                relative = child.relative_to(root).as_posix()
                if child.is_symlink():
                    link_target = os.readlink(child)
                    entries.append(
                        {
                            "path": relative,
                            "kind": "symlink",
                            "target": link_target,
                        }
                    )
                    continue
                if not child.is_file():
                    raise EvidenceIntegrityError(
                        f"unsupported tree entry type: {child}"
                    )
                size = child.stat().st_size
                total_size += size
                entries.append(
                    {
                        "path": relative,
                        "kind": "file",
                        "size_bytes": size,
                        "sha256": sha256_file(child),
                    }
                )
    except OSError as error:
        raise EvidenceIntegrityError(
            f"could not hash directory {target}: {error}"
        ) from error

    return {
        "path": str(root),
        "kind": "directory",
        "exists": True,
        "size_bytes": total_size,
        "sha256": digest_json(entries),
        "entry_count": len(entries),
    }


def digest_path_set(
    paths: Sequence[Path | str],
    *,
    base_dir: Optional[Path | str] = None,
) -> str:
    """Hash an ordered-independent set of paths and their current contents."""
    base = Path(base_dir).resolve() if base_dir is not None else None
    records: list[dict[str, Any]] = []
    for raw_path in paths:
        candidate = Path(raw_path)
        if base is not None and not candidate.is_absolute():
            candidate = base / candidate
        record = digest_path(candidate)
        if base is not None:
            resolved_or_absolute = (
                candidate.resolve()
                if candidate.exists()
                else Path(os.path.abspath(candidate))
            )
            try:
                record["label"] = resolved_or_absolute.relative_to(
                    base
                ).as_posix()
            except ValueError:
                record["label"] = str(resolved_or_absolute)
        else:
            record["label"] = record["path"]
        record.pop("path", None)
        records.append(record)
    records.sort(key=lambda item: item["label"])
    return digest_json(records)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceIntegrityError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _strict_json_loads(raw: bytes, *, context: str) -> Any:
    def reject_constant(value: str) -> None:
        raise EvidenceIntegrityError(
            f"{context}: non-standard JSON number {value}"
        )

    try:
        return json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_strict_object,
            parse_constant=reject_constant,
        )
    except EvidenceIntegrityError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise EvidenceIntegrityError(
            f"{context}: malformed UTF-8 JSON: {error}"
        ) from error


def _normalized_json_object(
    value: Mapping[str, Any], *, context: str
) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise EvidenceIntegrityError(f"{context} must be a JSON object")
    raw = canonical_json_bytes(dict(value))
    normalized = _strict_json_loads(raw, context=context)
    if not isinstance(normalized, dict):
        raise EvidenceIntegrityError(f"{context} must be a JSON object")
    return normalized


def _utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


def _validate_timestamp(value: Any, *, context: str) -> None:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise EvidenceIntegrityError(
            f"{context} must be an explicit UTC timestamp ending in Z"
        )
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise EvidenceIntegrityError(
            f"{context} is not an ISO-8601 timestamp"
        ) from error
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(
        parsed
    ):
        raise EvidenceIntegrityError(f"{context} is not UTC")


def _require_exact_keys(
    value: Mapping[str, Any],
    expected: frozenset[str],
    *,
    context: str,
) -> None:
    keys = set(value)
    if keys != expected:
        raise EvidenceIntegrityError(
            f"{context} has invalid fields; "
            f"missing={sorted(expected - keys)}, "
            f"extra={sorted(keys - expected)}"
        )


def _validate_declared_paths(value: Any, *, context: str) -> None:
    if not isinstance(value, list):
        raise EvidenceIntegrityError(f"{context} must be an array")
    seen_names: set[str] = set()
    for index, item in enumerate(value):
        item_context = f"{context}[{index}]"
        if not isinstance(item, dict):
            raise EvidenceIntegrityError(
                f"{item_context} must be an object"
            )
        _require_exact_keys(
            item,
            DECLARED_PATH_KEYS,
            context=item_context,
        )
        for key in ("name", "path"):
            if not isinstance(item[key], str) or not item[key]:
                raise EvidenceIntegrityError(
                    f"{item_context}.{key} must be a non-empty string"
                )
        if item["name"] in seen_names:
            raise EvidenceIntegrityError(
                f"{context} contains duplicate name {item['name']!r}"
            )
        seen_names.add(item["name"])


def _validate_started_payload(
    payload: Mapping[str, Any], *, context: str
) -> None:
    _require_exact_keys(payload, STARTED_PAYLOAD_KEYS, context=context)
    argv = payload["argv"]
    if (
        not isinstance(argv, list)
        or not argv
        or any(not isinstance(value, str) or not value for value in argv)
    ):
        raise EvidenceIntegrityError(
            f"{context}.argv must be a non-empty string array"
        )
    for key in ("cwd", "host_workspace"):
        if not isinstance(payload[key], str) or not payload[key]:
            raise EvidenceIntegrityError(
                f"{context}.{key} must be a non-empty string"
            )
    digests = payload["digests"]
    if not isinstance(digests, dict):
        raise EvidenceIntegrityError(f"{context}.digests must be an object")
    _require_exact_keys(
        digests,
        DIGEST_BINDING_KEYS,
        context=f"{context}.digests",
    )
    for key, value in digests.items():
        if not isinstance(value, str) or not DIGEST_PATTERN.fullmatch(value):
            raise EvidenceIntegrityError(
                f"{context}.digests.{key} is not a SHA-256 digest"
            )
    if payload["report_staging"] != "non_authoritative":
        raise EvidenceIntegrityError(
            f"{context}.report_staging must be non_authoritative"
        )
    _validate_declared_paths(
        payload["declared_reports"],
        context=f"{context}.declared_reports",
    )
    _validate_declared_paths(
        payload["declared_artifacts"],
        context=f"{context}.declared_artifacts",
    )


def _validate_path_evidence(value: Any, *, context: str) -> None:
    if not isinstance(value, dict):
        raise EvidenceIntegrityError(f"{context} must be an object")
    keys = set(value)
    allowed = PATH_EVIDENCE_REQUIRED_KEYS | PATH_EVIDENCE_OPTIONAL_KEYS
    if not PATH_EVIDENCE_REQUIRED_KEYS.issubset(keys) or not keys.issubset(
        allowed
    ):
        raise EvidenceIntegrityError(
            f"{context} has invalid fields; "
            f"missing={sorted(PATH_EVIDENCE_REQUIRED_KEYS - keys)}, "
            f"extra={sorted(keys - allowed)}"
        )
    for key in ("name", "path"):
        if not isinstance(value[key], str) or not value[key]:
            raise EvidenceIntegrityError(
                f"{context}.{key} must be a non-empty string"
            )
    kind = value["kind"]
    if kind not in PATH_EVIDENCE_KINDS:
        raise EvidenceIntegrityError(f"{context}.kind is unsupported")
    if not isinstance(value["exists"], bool):
        raise EvidenceIntegrityError(f"{context}.exists must be boolean")
    size = value["size_bytes"]
    if size is not None and (
        not isinstance(size, int) or isinstance(size, bool) or size < 0
    ):
        raise EvidenceIntegrityError(
            f"{context}.size_bytes must be a non-negative integer or null"
        )
    digest = value["sha256"]
    if digest is not None and (
        not isinstance(digest, str)
        or not DIGEST_PATTERN.fullmatch(digest)
    ):
        raise EvidenceIntegrityError(
            f"{context}.sha256 must be a SHA-256 digest or null"
        )
    if kind in {"file", "directory", "symlink"}:
        if not value["exists"] or size is None or digest is None:
            raise EvidenceIntegrityError(
                f"{context} has inconsistent evidence for {kind}"
            )
    elif kind == "missing":
        if value["exists"] or size is not None or digest is not None:
            raise EvidenceIntegrityError(
                f"{context} has inconsistent missing-path evidence"
            )
    elif size is not None or digest is not None:
        raise EvidenceIntegrityError(
            f"{context} unreadable evidence must not claim size/digest"
        )
    if kind == "directory":
        count = value.get("entry_count")
        if (
            not isinstance(count, int)
            or isinstance(count, bool)
            or count < 0
        ):
            raise EvidenceIntegrityError(
                f"{context}.entry_count is required for directories"
            )
    elif "entry_count" in value:
        raise EvidenceIntegrityError(
            f"{context}.entry_count is only valid for directories"
        )


def _validate_sealed_payload(
    payload: Mapping[str, Any], *, context: str
) -> None:
    _require_exact_keys(payload, SEALED_PAYLOAD_KEYS, context=context)
    if payload["outcome"] not in OUTCOMES:
        raise EvidenceIntegrityError(f"{context}.outcome is unsupported")
    duration = payload["duration_seconds"]
    if (
        not isinstance(duration, (int, float))
        or isinstance(duration, bool)
        or not float(duration) >= 0
        or not float(duration) < float("inf")
    ):
        raise EvidenceIntegrityError(
            f"{context}.duration_seconds must be finite and non-negative"
        )
    exit_code = payload["exit_code"]
    if exit_code is not None and (
        not isinstance(exit_code, int) or isinstance(exit_code, bool)
    ):
        raise EvidenceIntegrityError(
            f"{context}.exit_code must be an integer or null"
        )
    for key in ("timed_out", "interrupted"):
        if not isinstance(payload[key], bool):
            raise EvidenceIntegrityError(
                f"{context}.{key} must be boolean"
            )
    if payload["outcome"] == "TIMEOUT" and not payload["timed_out"]:
        raise EvidenceIntegrityError(
            f"{context}: TIMEOUT requires timed_out=true"
        )
    for key in ("reports", "artifacts"):
        values = payload[key]
        if not isinstance(values, list):
            raise EvidenceIntegrityError(f"{context}.{key} must be an array")
        seen_names: set[str] = set()
        for index, item in enumerate(values):
            _validate_path_evidence(
                item,
                context=f"{context}.{key}[{index}]",
            )
            if item["name"] in seen_names:
                raise EvidenceIntegrityError(
                    f"{context}.{key} contains duplicate name "
                    f"{item['name']!r}"
                )
            seen_names.add(item["name"])
    if not isinstance(payload["details"], dict):
        raise EvidenceIntegrityError(f"{context}.details must be an object")


def _validate_typed_payload(
    event_type: str,
    payload: Mapping[str, Any],
    *,
    context: str,
) -> None:
    if event_type == "RUN_STARTED":
        _validate_started_payload(payload, context=context)
    elif event_type == "RUN_SEALED":
        _validate_sealed_payload(payload, context=context)


def _is_within(path: Path, parent: Path) -> bool:
    return path == parent or parent in path.parents


def _validate_disjoint(root: Path, host_workspace: Path) -> None:
    if _is_within(root, host_workspace) or _is_within(
        host_workspace, root
    ):
        raise EvidencePathError(
            "evidence root and host workspace must be disjoint after resolve: "
            f"evidence={root}, host={host_workspace}"
        )


def _prepare_directory(path: Path, *, parent: Optional[Path] = None) -> Path:
    if path.exists() and path.is_symlink():
        raise EvidencePathError(f"control-plane directory is a symlink: {path}")
    try:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise EvidencePathError(
            f"could not prepare control-plane directory {path}: {error}"
        ) from error
    if parent is not None and resolved.parent != parent:
        raise EvidencePathError(
            f"control-plane directory escaped its parent: {path}"
        )
    return resolved


def _fsync_directory(path: Path) -> None:
    if os.name == "nt":
        return
    flags = os.O_RDONLY
    if hasattr(os, "O_DIRECTORY"):
        flags |= os.O_DIRECTORY
    try:
        descriptor = os.open(path, flags)
    except OSError:
        return
    try:
        os.fsync(descriptor)
    except OSError:
        pass
    finally:
        os.close(descriptor)


def _atomic_write_new(path: Path, data: bytes) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary_path: Optional[Path] = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temporary_path = Path(handle.name)
            try:
                os.chmod(temporary_path, 0o600)
            except OSError:
                pass
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            # A hard link publishes the fully fsynced temporary inode while
            # failing atomically if the immutable target already exists.
            # Unlike os.replace(), this can never overwrite a raced bundle.
            os.link(temporary_path, path)
        except FileExistsError as error:
            raise EvidenceTransitionConflict(
                f"immutable evidence file already exists: {path}"
            ) from error
        _fsync_directory(path.parent)
        temporary_path.unlink()
        temporary_path = None
        _fsync_directory(path.parent)
    except OSError as error:
        raise EvidenceError(
            f"could not atomically write evidence file {path}: {error}"
        ) from error
    finally:
        if temporary_path is not None:
            try:
                temporary_path.unlink()
            except OSError:
                pass


class EvidenceLedger:
    """One authoritative, hash-chained journal for a single run ID."""

    def __init__(
        self,
        evidence_root: Path | str,
        host_workspace: Path | str,
        run_id: str,
        *,
        clock: Callable[[], str] = _utc_now,
    ):
        if not RUN_ID_PATTERN.fullmatch(run_id):
            raise EvidencePathError(
                "run_id must match "
                f"{RUN_ID_PATTERN.pattern!r}; received {run_id!r}"
            )
        host = Path(host_workspace).expanduser()
        try:
            host = host.resolve(strict=True)
        except OSError as error:
            raise EvidencePathError(
                f"host workspace does not resolve: {host}: {error}"
            ) from error
        if not host.is_dir():
            raise EvidencePathError(
                f"host workspace is not a directory: {host}"
            )

        raw_root = Path(evidence_root).expanduser()
        if not raw_root.is_absolute():
            raw_root = Path.cwd() / raw_root
        candidate_root = raw_root.resolve(strict=False)
        _validate_disjoint(candidate_root, host)
        root = _prepare_directory(candidate_root)
        _validate_disjoint(root, host)

        runs_dir = _prepare_directory(root / "runs", parent=root)
        run_dir = _prepare_directory(runs_dir / run_id, parent=runs_dir)
        _validate_disjoint(run_dir, host)

        self.evidence_root = root
        self.host_workspace = host
        self.run_id = run_id
        self.run_dir = run_dir
        self.journal_path = run_dir / JOURNAL_NAME
        self.bundle_path = run_dir / BUNDLE_RELATIVE_PATH
        self.lock_path = run_dir / LOCK_NAME
        self._clock = clock

    def _authoritative_size_unlocked(self) -> int:
        total = 0
        for path in (self.journal_path, self.bundle_path):
            if not path.exists():
                continue
            if path.is_symlink():
                raise EvidencePathError(
                    f"authoritative evidence must not be a symlink: {path}"
                )
            try:
                total += path.stat().st_size
            except OSError as error:
                raise EvidenceIntegrityError(
                    f"could not stat authoritative evidence {path}: {error}"
                ) from error
        return total

    def _assert_budget_unlocked(
        self,
        *,
        extra_bytes: int = 0,
        context: str = "authoritative evidence",
    ) -> None:
        if (
            not isinstance(extra_bytes, int)
            or isinstance(extra_bytes, bool)
            or extra_bytes < 0
        ):
            raise EvidenceBudgetError("extra evidence bytes must be non-negative")
        projected = self._authoritative_size_unlocked() + extra_bytes
        if projected > MAX_EVIDENCE_BYTES:
            raise EvidenceBudgetError(
                f"{context} exceeds the frozen {MAX_EVIDENCE_BYTES}-byte "
                f"budget: projected={projected}"
            )

    @contextmanager
    def _locked(self) -> Iterator[None]:
        if self.lock_path.exists() and self.lock_path.is_symlink():
            raise EvidencePathError(
                f"journal lock must not be a symlink: {self.lock_path}"
            )
        try:
            handle = self.lock_path.open("a+b", buffering=0)
            try:
                os.chmod(self.lock_path, 0o600)
            except OSError:
                pass
        except OSError as error:
            raise EvidenceError(
                f"could not open journal lock {self.lock_path}: {error}"
            ) from error

        try:
            handle.seek(0, os.SEEK_END)
            if handle.tell() == 0:
                handle.write(b"\0")
                handle.flush()
                os.fsync(handle.fileno())
            handle.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                handle.seek(0)
                if os.name == "nt":
                    import msvcrt

                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl

                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        except OSError as error:
            raise EvidenceError(
                f"journal lock operation failed for {self.lock_path}: {error}"
            ) from error
        finally:
            handle.close()

    def _read_events_unlocked(self) -> list[dict[str, Any]]:
        self._assert_budget_unlocked(context="authoritative evidence replay")
        if not self.journal_path.exists():
            return []
        if self.journal_path.is_symlink():
            raise EvidencePathError(
                f"journal must not be a symlink: {self.journal_path}"
            )
        try:
            raw = self.journal_path.read_bytes()
        except OSError as error:
            raise EvidenceIntegrityError(
                f"could not read journal {self.journal_path}: {error}"
            ) from error
        if not raw:
            raise EvidenceIntegrityError("journal exists but is empty")
        if not raw.endswith(b"\n"):
            raise EvidenceIntegrityError(
                "journal is truncated: final JSONL record lacks newline"
            )

        events: list[dict[str, Any]] = []
        transitions: dict[str, dict[str, Any]] = {}
        expected_previous: Optional[str] = None
        sealed_seen = False
        for line_number, raw_line in enumerate(
            raw.splitlines(keepends=True), 1
        ):
            if not raw_line.endswith(b"\n"):
                raise EvidenceIntegrityError(
                    f"journal line {line_number} is truncated"
                )
            content = raw_line[:-1]
            if not content:
                raise EvidenceIntegrityError(
                    f"journal line {line_number} is blank"
                )
            parsed = _strict_json_loads(
                content, context=f"journal line {line_number}"
            )
            if not isinstance(parsed, dict):
                raise EvidenceIntegrityError(
                    f"journal line {line_number} is not a JSON object"
                )
            if set(parsed) != EVENT_KEYS:
                missing = sorted(EVENT_KEYS - set(parsed))
                extra = sorted(set(parsed) - EVENT_KEYS)
                raise EvidenceIntegrityError(
                    f"journal line {line_number} has invalid fields; "
                    f"missing={missing}, extra={extra}"
                )
            if canonical_json_bytes(parsed) != content:
                raise EvidenceIntegrityError(
                    f"journal line {line_number} is not canonical JSON"
                )
            self._validate_event(
                parsed,
                line_number=line_number,
                expected_previous=expected_previous,
            )
            if sealed_seen:
                raise EvidenceIntegrityError(
                    "RUN_SEALED must be the final journal event"
                )
            transition_id = parsed["transition_id"]
            if transition_id in transitions:
                raise EvidenceIntegrityError(
                    f"duplicate transition_id in journal: {transition_id}"
                )
            transitions[transition_id] = parsed
            events.append(parsed)
            expected_previous = parsed["event_digest"]
            sealed_seen = parsed["event_type"] == "RUN_SEALED"

        if not events:
            return []
        if events[0]["event_type"] != "RUN_STARTED":
            raise EvidenceIntegrityError(
                "the first journal event must be RUN_STARTED"
            )
        if any(
            event["event_type"] == "RUN_STARTED" for event in events[1:]
        ):
            raise EvidenceIntegrityError(
                "RUN_STARTED may appear only as sequence 1"
            )
        return events

    def _validate_event(
        self,
        event: Mapping[str, Any],
        *,
        line_number: int,
        expected_previous: Optional[str],
    ) -> None:
        context = f"journal line {line_number}"
        if event["schema_version"] != SCHEMA_VERSION:
            raise EvidenceIntegrityError(
                f"{context}: unsupported schema_version"
            )
        if event["stability"] != STABILITY:
            raise EvidenceIntegrityError(f"{context}: invalid stability")
        if event["run_id"] != self.run_id:
            raise EvidenceIntegrityError(f"{context}: run_id mismatch")
        if (
            not isinstance(event["sequence"], int)
            or isinstance(event["sequence"], bool)
            or event["sequence"] != line_number
        ):
            raise EvidenceIntegrityError(
                f"{context}: sequence is not strictly monotonic"
            )
        transition_id = event["transition_id"]
        if (
            not isinstance(transition_id, str)
            or not TRANSITION_ID_PATTERN.fullmatch(transition_id)
        ):
            raise EvidenceIntegrityError(
                f"{context}: invalid transition_id"
            )
        event_type = event["event_type"]
        if (
            not isinstance(event_type, str)
            or not EVENT_TYPE_PATTERN.fullmatch(event_type)
        ):
            raise EvidenceIntegrityError(f"{context}: invalid event_type")
        _validate_timestamp(
            event["recorded_at_utc"],
            context=f"{context}.recorded_at_utc",
        )
        if event["previous_event_digest"] != expected_previous:
            raise EvidenceIntegrityError(
                f"{context}: previous_event_digest mismatch"
            )
        if not isinstance(event["payload"], dict):
            raise EvidenceIntegrityError(f"{context}: payload is not an object")
        _validate_typed_payload(
            event_type,
            event["payload"],
            context=f"{context}.payload",
        )
        event_digest = event["event_digest"]
        if (
            not isinstance(event_digest, str)
            or not DIGEST_PATTERN.fullmatch(event_digest)
        ):
            raise EvidenceIntegrityError(
                f"{context}: invalid event_digest"
            )
        digest_input = dict(event)
        digest_input.pop("event_digest")
        expected_digest = digest_json(digest_input)
        if event_digest != expected_digest:
            raise EvidenceIntegrityError(
                f"{context}: event_digest mismatch"
            )
        if event_type == "RUN_SEALED":
            outcome = event["payload"].get("outcome")
            if outcome not in OUTCOMES:
                raise EvidenceIntegrityError(
                    f"{context}: invalid sealed outcome {outcome!r}"
                )

    def _read_bundle_unlocked(
        self,
        events: Sequence[Mapping[str, Any]],
    ) -> Optional[dict[str, Any]]:
        self._assert_budget_unlocked(context="sealed evidence replay")
        sealed = bool(events and events[-1]["event_type"] == "RUN_SEALED")
        if not self.bundle_path.exists():
            if sealed:
                raise EvidenceIntegrityError(
                    "journal has RUN_SEALED but bundle.json is missing"
                )
            return None
        if self.bundle_path.is_symlink():
            raise EvidencePathError(
                f"bundle must not be a symlink: {self.bundle_path}"
            )
        if not sealed:
            raise EvidenceIntegrityError(
                "bundle.json exists for an unsealed journal"
            )
        try:
            raw = self.bundle_path.read_bytes()
        except OSError as error:
            raise EvidenceIntegrityError(
                f"could not read bundle {self.bundle_path}: {error}"
            ) from error
        if not raw.endswith(b"\n"):
            raise EvidenceIntegrityError(
                "bundle.json is truncated or non-canonical"
            )
        parsed = _strict_json_loads(raw[:-1], context="bundle.json")
        if not isinstance(parsed, dict):
            raise EvidenceIntegrityError("bundle.json is not an object")
        if set(parsed) != BUNDLE_KEYS:
            missing = sorted(BUNDLE_KEYS - set(parsed))
            extra = sorted(set(parsed) - BUNDLE_KEYS)
            raise EvidenceIntegrityError(
                "bundle.json has invalid fields; "
                f"missing={missing}, extra={extra}"
            )
        if canonical_json_bytes(parsed) + b"\n" != raw:
            raise EvidenceIntegrityError(
                "bundle.json is not canonical JSON"
            )
        digest_input = dict(parsed)
        claimed_digest = digest_input.pop("bundle_digest")
        if (
            not isinstance(claimed_digest, str)
            or not DIGEST_PATTERN.fullmatch(claimed_digest)
            or digest_json(digest_input) != claimed_digest
        ):
            raise EvidenceIntegrityError("bundle_digest mismatch")

        terminal = events[-1]
        expected_values = {
            "schema_version": SCHEMA_VERSION,
            "stability": STABILITY,
            "kind": "run_evidence_bundle",
            "run_id": self.run_id,
            "outcome": terminal["payload"]["outcome"],
            "sealed_at_utc": terminal["recorded_at_utc"],
            "event_count": terminal["sequence"],
            "journal_head_digest": terminal["event_digest"],
            "journal_sha256": sha256_file(self.journal_path),
            "terminal_transition_id": terminal["transition_id"],
            "terminal_event_digest": terminal["event_digest"],
            "summary": terminal["payload"],
        }
        for key, expected in expected_values.items():
            if parsed.get(key) != expected:
                raise EvidenceIntegrityError(
                    f"bundle field {key} does not match the journal"
                )
        return parsed

    def _replay_unlocked(
        self,
        *,
        require_sealed: bool,
        validate_bundle: bool,
    ) -> ReplayResult:
        events = self._read_events_unlocked()
        sealed = bool(events and events[-1]["event_type"] == "RUN_SEALED")
        if require_sealed and not sealed:
            raise EvidenceUnsealedError(
                f"run {self.run_id} has no sealed evidence bundle"
            )
        bundle = (
            self._read_bundle_unlocked(events)
            if validate_bundle
            else None
        )
        return ReplayResult(
            self.run_id,
            tuple(deepcopy(events)),
            deepcopy(bundle),
        )

    def replay(self, *, require_sealed: bool = True) -> ReplayResult:
        """Validate and deterministically replay the journal and bundle."""
        with self._locked():
            return self._replay_unlocked(
                require_sealed=require_sealed,
                validate_bundle=True,
            )

    def _append_bytes_unlocked(
        self,
        data: bytes,
        *,
        reserve_bytes: int = 0,
    ) -> None:
        self._assert_budget_unlocked(
            extra_bytes=len(data) + reserve_bytes,
            context="journal append",
        )
        if self.journal_path.exists() and self.journal_path.is_symlink():
            raise EvidencePathError(
                f"journal must not be a symlink: {self.journal_path}"
            )
        flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT
        if hasattr(os, "O_BINARY"):
            flags |= os.O_BINARY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            descriptor = os.open(self.journal_path, flags, 0o600)
            try:
                view = memoryview(data)
                while view:
                    written = os.write(descriptor, view)
                    if written <= 0:
                        raise OSError("zero-byte journal append")
                    view = view[written:]
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            _fsync_directory(self.run_dir)
            self._assert_budget_unlocked(
                extra_bytes=reserve_bytes,
                context="journal append postcondition",
            )
        except OSError as error:
            raise EvidenceError(
                f"could not append journal {self.journal_path}: {error}"
            ) from error

    def _append_event_unlocked(
        self,
        *,
        transition_id: str,
        event_type: str,
        payload: Mapping[str, Any],
        expected_sequence: int,
        allow_seal: bool,
        reserve_factory: Optional[
            Callable[[Mapping[str, Any], bytes], int]
        ] = None,
    ) -> dict[str, Any]:
        if (
            not isinstance(expected_sequence, int)
            or isinstance(expected_sequence, bool)
            or expected_sequence < 0
        ):
            raise EvidenceSequenceConflict(
                "expected_sequence must be a non-negative integer"
            )
        if not TRANSITION_ID_PATTERN.fullmatch(transition_id):
            raise EvidenceTransitionConflict(
                f"invalid transition_id: {transition_id!r}"
            )
        if not EVENT_TYPE_PATTERN.fullmatch(event_type):
            raise EvidenceIntegrityError(
                f"invalid event_type: {event_type!r}"
            )
        if event_type == "RUN_SEALED" and not allow_seal:
            raise EvidenceSealedError(
                "RUN_SEALED must be appended through seal()"
            )
        normalized_payload = _normalized_json_object(
            payload, context=f"{event_type} payload"
        )
        _validate_typed_payload(
            event_type,
            normalized_payload,
            context=f"{event_type} payload",
        )
        state = self._replay_unlocked(
            require_sealed=False,
            validate_bundle=False,
        )

        for existing in state.events:
            if existing["transition_id"] != transition_id:
                continue
            if (
                existing["event_type"] == event_type
                and existing["payload"] == normalized_payload
            ):
                return deepcopy(existing)
            raise EvidenceTransitionConflict(
                f"transition_id {transition_id!r} was reused with "
                "different event content"
            )

        if state.sealed:
            raise EvidenceSealedError(
                f"run {self.run_id} is already sealed"
            )
        if state.last_sequence != expected_sequence:
            raise EvidenceSequenceConflict(
                "stale expected_sequence: "
                f"expected {expected_sequence}, actual {state.last_sequence}"
            )
        if not state.events and event_type != "RUN_STARTED":
            raise EvidenceIntegrityError(
                "the first event must be RUN_STARTED"
            )
        if state.events and event_type == "RUN_STARTED":
            raise EvidenceIntegrityError(
                "RUN_STARTED may only be the first event"
            )

        recorded_at = self._clock()
        _validate_timestamp(recorded_at, context="recorded_at_utc")
        event_without_digest: dict[str, Any] = {
            "schema_version": SCHEMA_VERSION,
            "stability": STABILITY,
            "run_id": self.run_id,
            "sequence": state.last_sequence + 1,
            "transition_id": transition_id,
            "event_type": event_type,
            "recorded_at_utc": recorded_at,
            "previous_event_digest": state.head_digest,
            "payload": normalized_payload,
        }
        event = dict(event_without_digest)
        event["event_digest"] = digest_json(event_without_digest)
        encoded_event = canonical_json_bytes(event) + b"\n"
        reserve_bytes = (
            reserve_factory(event, encoded_event)
            if reserve_factory is not None
            else MINIMUM_SEAL_RESERVE_BYTES
        )
        self._append_bytes_unlocked(
            encoded_event,
            reserve_bytes=reserve_bytes,
        )
        return deepcopy(event)

    def append_event(
        self,
        *,
        transition_id: str,
        event_type: str,
        payload: Mapping[str, Any],
        expected_sequence: int,
    ) -> dict[str, Any]:
        """CAS-append one event, with transition-level idempotency."""
        with self._locked():
            return self._append_event_unlocked(
                transition_id=transition_id,
                event_type=event_type,
                payload=payload,
                expected_sequence=expected_sequence,
                allow_seal=False,
            )

    def seal(
        self,
        *,
        transition_id: str,
        outcome: str,
        summary: Mapping[str, Any],
        expected_sequence: int,
    ) -> dict[str, Any]:
        """Append RUN_SEALED and create the immutable evidence bundle."""
        if outcome not in OUTCOMES:
            raise EvidenceIntegrityError(
                f"unsupported run outcome: {outcome!r}"
            )
        normalized_summary = _normalized_json_object(
            summary, context="seal summary"
        )
        existing_outcome = normalized_summary.get("outcome")
        if existing_outcome not in (None, outcome):
            raise EvidenceIntegrityError(
                "seal summary outcome conflicts with requested outcome"
            )
        normalized_summary["outcome"] = outcome

        with self._locked():
            def reserved_bundle_size(
                candidate_terminal: Mapping[str, Any],
                _encoded_event: bytes,
            ) -> int:
                placeholder_without_digest: dict[str, Any] = {
                    "schema_version": SCHEMA_VERSION,
                    "stability": STABILITY,
                    "kind": "run_evidence_bundle",
                    "run_id": self.run_id,
                    "outcome": outcome,
                    "sealed_at_utc": candidate_terminal["recorded_at_utc"],
                    "event_count": candidate_terminal["sequence"],
                    "journal_head_digest": candidate_terminal["event_digest"],
                    "journal_sha256": "0" * 64,
                    "terminal_transition_id": candidate_terminal[
                        "transition_id"
                    ],
                    "terminal_event_digest": candidate_terminal[
                        "event_digest"
                    ],
                    "summary": candidate_terminal["payload"],
                }
                placeholder = dict(placeholder_without_digest)
                placeholder["bundle_digest"] = digest_json(
                    placeholder_without_digest
                )
                return len(canonical_json_bytes(placeholder) + b"\n")

            terminal = self._append_event_unlocked(
                transition_id=transition_id,
                event_type="RUN_SEALED",
                payload=normalized_summary,
                expected_sequence=expected_sequence,
                allow_seal=True,
                reserve_factory=reserved_bundle_size,
            )
            bundle_without_digest: dict[str, Any] = {
                "schema_version": SCHEMA_VERSION,
                "stability": STABILITY,
                "kind": "run_evidence_bundle",
                "run_id": self.run_id,
                "outcome": outcome,
                "sealed_at_utc": terminal["recorded_at_utc"],
                "event_count": terminal["sequence"],
                "journal_head_digest": terminal["event_digest"],
                "journal_sha256": sha256_file(self.journal_path),
                "terminal_transition_id": terminal["transition_id"],
                "terminal_event_digest": terminal["event_digest"],
                "summary": terminal["payload"],
            }
            bundle = dict(bundle_without_digest)
            bundle["bundle_digest"] = digest_json(bundle_without_digest)
            encoded = canonical_json_bytes(bundle) + b"\n"
            self._assert_budget_unlocked(
                extra_bytes=0 if self.bundle_path.exists() else len(encoded),
                context="sealed bundle write",
            )

            if self.bundle_path.exists():
                try:
                    existing = self.bundle_path.read_bytes()
                except OSError as error:
                    raise EvidenceIntegrityError(
                        f"could not read existing bundle: {error}"
                    ) from error
                if existing != encoded:
                    raise EvidenceTransitionConflict(
                        "sealed bundle already exists with different content"
                    )
            else:
                sealed_dir = _prepare_directory(
                    self.run_dir / "sealed-evidence",
                    parent=self.run_dir,
                )
                if self.bundle_path.parent.resolve() != sealed_dir:
                    raise EvidencePathError(
                        "bundle path escaped sealed-evidence directory"
                    )
                _atomic_write_new(self.bundle_path, encoded)
                self._assert_budget_unlocked(
                    context="sealed bundle postcondition"
                )

            validated = self._read_bundle_unlocked(
                self._read_events_unlocked()
            )
            if validated is None:
                raise EvidenceIntegrityError(
                    "sealed bundle was not recoverable after write"
                )
            return deepcopy(validated)
