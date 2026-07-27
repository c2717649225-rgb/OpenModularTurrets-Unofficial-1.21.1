#!/usr/bin/env python3
"""Build one deterministic task envelope without introducing a WorkGraph.

The provisional v1.3 envelope is a projection of one validated Major Contract
v2, one Studio Manifest, and one frozen Execution Policy.  It has no graph,
planner, dependency scheduler, recovery state machine, or permission override.
In particular, writable paths are copied only from the validated policy.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Sequence

from execution_policy import ExecutionPolicy, canonical_json, sha256_json


SCHEMA_VERSION = 1
STABILITY = "provisional"
ELIGIBLE_STATUSES = frozenset(
    {"approved", "implementing", "verifying", "released"}
)
RISK_ORDER = {"unclassified": 0, "P2": 1, "P1": 2, "P0": 3}
REQUIRED_GATES = (
    "major_feature_contract",
    "compile",
    "static",
    "datagen",
    "assets",
    "gametest",
    "traceability",
)


class TaskEnvelopeError(ValueError):
    """A single safe task cannot be derived from the supplied inputs."""


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise TaskEnvelopeError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise TaskEnvelopeError(f"non-standard JSON number: {value}")


def load_json_object(path: Path | str) -> dict[str, Any]:
    target = Path(path)
    try:
        value = json.loads(
            target.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_object,
            parse_constant=_reject_constant,
        )
    except OSError as error:
        raise TaskEnvelopeError(f"cannot read {target}: {error}") from error
    except (UnicodeError, json.JSONDecodeError) as error:
        raise TaskEnvelopeError(f"invalid JSON in {target}: {error}") from error
    if not isinstance(value, dict):
        raise TaskEnvelopeError(f"JSON root must be an object: {target}")
    # Round-tripping proves the value is finite, plain JSON data.
    canonical_json(value)
    return value


def sha256_file(path: Path | str) -> str:
    digest = hashlib.sha256()
    try:
        with Path(path).open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                digest.update(chunk)
    except OSError as error:
        raise TaskEnvelopeError(f"cannot hash {path}: {error}") from error
    return digest.hexdigest()


def _within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _portable_relative_path(raw: Any, *, field: str) -> str:
    if not isinstance(raw, str) or not raw or "\\" in raw or ":" in raw:
        raise TaskEnvelopeError(f"{field} must be a portable relative path")
    parsed = PurePosixPath(raw)
    if (
        parsed.is_absolute()
        or parsed.as_posix() != raw
        or any(part in {"", ".", ".."} for part in parsed.parts)
    ):
        raise TaskEnvelopeError(f"{field} contains unsafe traversal")
    return parsed.as_posix()


def _verified_input_file(
    workspace: Path,
    record: Mapping[str, Any],
    *,
    field: str,
) -> dict[str, str]:
    relative = _portable_relative_path(record.get("path"), field=field)
    claimed = record.get("sha256")
    if (
        not isinstance(claimed, str)
        or len(claimed) != 64
        or any(char not in "0123456789abcdef" for char in claimed)
    ):
        raise TaskEnvelopeError(f"{field}.sha256 must be lowercase SHA-256")
    candidate = workspace.joinpath(*PurePosixPath(relative).parts)
    if candidate.is_symlink():
        raise TaskEnvelopeError(f"{field} must not be a symlink: {relative}")
    resolved = candidate.resolve()
    if not _within(resolved, workspace) or not resolved.is_file():
        raise TaskEnvelopeError(
            f"{field} must resolve to a file inside the workspace: {relative}"
        )
    actual = sha256_file(resolved)
    if actual != claimed:
        raise TaskEnvelopeError(
            f"{field} digest drift: expected {claimed}, got {actual}"
        )
    return {"path": relative, "sha256": actual}


def verify_manifest_inputs(
    manifest: Mapping[str, Any],
    *,
    workspace: Path | str,
) -> dict[str, Any]:
    root = Path(workspace).resolve()
    if manifest.get("schema_version") != 1:
        raise TaskEnvelopeError("Studio Manifest schema_version must be 1")
    project_id = manifest.get("project_id")
    if not isinstance(project_id, str) or not project_id:
        raise TaskEnvelopeError("Studio Manifest project_id is required")
    versions = manifest.get("versions")
    if not isinstance(versions, Mapping) or set(versions) != {
        "minecraft",
        "neoforge",
        "java",
        "gradle",
    }:
        raise TaskEnvelopeError("Studio Manifest versions are incomplete")
    if any(not isinstance(value, str) or not value for value in versions.values()):
        raise TaskEnvelopeError("Studio Manifest versions must be non-empty")

    verified: dict[str, Any] = {
        "project_id": project_id,
        "versions": dict(versions),
    }
    for key, minimum in (("design_sources", 1), ("approved_assets", 0)):
        raw_records = manifest.get(key)
        if not isinstance(raw_records, list) or len(raw_records) < minimum:
            raise TaskEnvelopeError(f"Studio Manifest {key} is invalid")
        records: list[dict[str, str]] = []
        for index, raw_record in enumerate(raw_records):
            if not isinstance(raw_record, Mapping):
                raise TaskEnvelopeError(f"{key}[{index}] must be an object")
            records.append(
                _verified_input_file(
                    root,
                    raw_record,
                    field=f"{key}[{index}]",
                )
            )
        if len({item["path"] for item in records}) != len(records):
            raise TaskEnvelopeError(f"Studio Manifest {key} paths must be unique")
        verified[key] = records
    return verified


def _required_acceptance(
    contract: Mapping[str, Any],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    acceptance = contract.get("acceptance")
    if not isinstance(acceptance, Mapping):
        raise TaskEnvelopeError("contract acceptance is missing")
    raw_criteria = acceptance.get("criteria")
    raw_tests = acceptance.get("tests")
    if not isinstance(raw_criteria, list) or not isinstance(raw_tests, list):
        raise TaskEnvelopeError("contract acceptance criteria/tests are invalid")

    tests_by_id: dict[str, Mapping[str, Any]] = {}
    for raw_test in raw_tests:
        if not isinstance(raw_test, Mapping):
            raise TaskEnvelopeError("acceptance test must be an object")
        test_id = raw_test.get("id")
        if not isinstance(test_id, str) or not test_id:
            raise TaskEnvelopeError("acceptance test id is invalid")
        if test_id in tests_by_id:
            raise TaskEnvelopeError(f"duplicate acceptance test id: {test_id}")
        tests_by_id[test_id] = raw_test

    criteria: list[dict[str, Any]] = []
    selected_test_ids: set[str] = set()
    for raw_criterion in raw_criteria:
        if not isinstance(raw_criterion, Mapping):
            raise TaskEnvelopeError("acceptance criterion must be an object")
        if raw_criterion.get("required") is not True:
            continue
        criterion_id = raw_criterion.get("id")
        risk = raw_criterion.get("risk")
        test_ids = raw_criterion.get("test_ids")
        if (
            not isinstance(criterion_id, str)
            or not criterion_id
            or risk not in RISK_ORDER
            or not isinstance(test_ids, list)
            or not test_ids
            or any(not isinstance(item, str) for item in test_ids)
        ):
            raise TaskEnvelopeError(
                "required acceptance criterion is not atomic and test-linked"
            )
        missing = sorted(set(test_ids) - set(tests_by_id))
        if missing:
            raise TaskEnvelopeError(
                f"criterion {criterion_id} references missing tests: "
                + ", ".join(missing)
            )
        selected_test_ids.update(test_ids)
        criteria.append(
            {
                "id": criterion_id,
                "risk": risk,
                "test_ids": list(test_ids),
            }
        )
    if not criteria:
        raise TaskEnvelopeError("contract has no required acceptance criteria")

    tests: list[dict[str, Any]] = []
    for test_id in sorted(selected_test_ids):
        raw_test = tests_by_id[test_id]
        command = raw_test.get("command")
        if (
            not isinstance(command, list)
            or not command
            or any(not isinstance(item, str) or not item for item in command)
        ):
            raise TaskEnvelopeError(f"acceptance test {test_id} has invalid argv")
        tests.append(
            {
                "id": test_id,
                "kind": raw_test.get("kind"),
                "test_ref": raw_test.get("test_ref"),
                "required": raw_test.get("required"),
                "timeout_seconds": raw_test.get("timeout_seconds"),
                "command": list(command),
                "command_digest": hashlib.sha256(
                    canonical_json(command)
                ).hexdigest(),
            }
        )
    criteria.sort(key=lambda item: item["id"])
    return criteria, tests


def build_task_envelope(
    *,
    workspace: Path | str,
    contract_path: Path | str,
    contract: Mapping[str, Any],
    manifest_path: Path | str,
    manifest: Mapping[str, Any],
    policy: ExecutionPolicy,
) -> dict[str, Any]:
    """Project validated inputs into one immutable, permission-narrow task."""
    root = Path(workspace).resolve()
    contract_file = Path(contract_path).resolve()
    manifest_file = Path(manifest_path).resolve()
    for label, target in (
        ("contract", contract_file),
        ("manifest", manifest_file),
    ):
        if not _within(target, root) or not target.is_file():
            raise TaskEnvelopeError(
                f"{label} must be a file inside the workspace"
            )

    if contract.get("schema_version") != 2:
        raise TaskEnvelopeError("single task envelope requires contract v2")
    status = contract.get("status")
    if status not in ELIGIBLE_STATUSES:
        raise TaskEnvelopeError(
            f"contract status {status!r} is not eligible for execution"
        )
    review_required = contract.get("review_required")
    if not isinstance(review_required, list) or review_required:
        raise TaskEnvelopeError(
            "contract has unresolved review_required design decisions"
        )
    contract_id = contract.get("id")
    contract_version = contract.get("version")
    if (
        not isinstance(contract_id, str)
        or not contract_id
        or not isinstance(contract_version, int)
        or isinstance(contract_version, bool)
        or contract_version < 1
    ):
        raise TaskEnvelopeError("contract id/version is invalid")

    verified_manifest = verify_manifest_inputs(manifest, workspace=root)
    design_source = contract.get("design_source")
    if not isinstance(design_source, Mapping):
        raise TaskEnvelopeError("contract design_source is missing")
    verified_design = _verified_input_file(
        root,
        design_source,
        field="contract.design_source",
    )
    manifest_designs = {
        (item["path"], item["sha256"])
        for item in verified_manifest["design_sources"]
    }
    if (verified_design["path"], verified_design["sha256"]) not in manifest_designs:
        raise TaskEnvelopeError(
            "contract design_source is not pinned by the Studio Manifest"
        )

    criteria, tests = _required_acceptance(contract)
    highest_risk = max(
        (item["risk"] for item in criteria),
        key=lambda risk: RISK_ORDER[risk],
    )
    dependencies = contract.get("dependencies")
    feature_dependencies = (
        dependencies.get("features", [])
        if isinstance(dependencies, Mapping)
        else []
    )
    if (
        not isinstance(feature_dependencies, list)
        or any(not isinstance(item, str) for item in feature_dependencies)
    ):
        raise TaskEnvelopeError("contract feature dependencies are invalid")

    envelope_without_digest: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "stability": STABILITY,
        "kind": "single_task_envelope",
        "task_id": f"{contract_id}.v{contract_version}",
        "project_id": verified_manifest["project_id"],
        "contract": {
            "id": contract_id,
            "version": contract_version,
            "path": contract_file.relative_to(root).as_posix(),
            "sha256": sha256_file(contract_file),
        },
        "manifest": {
            "path": manifest_file.relative_to(root).as_posix(),
            "sha256": sha256_file(manifest_file),
        },
        "design_source": verified_design,
        "risk": highest_risk,
        "acceptance_criteria": criteria,
        "acceptance_tests": tests,
        "feature_dependencies": sorted(set(feature_dependencies)),
        "required_gates": list(REQUIRED_GATES),
        "execution_policy": {
            "policy_id": policy.policy_id,
            "policy_digest": policy.digest,
            "writable_paths": list(policy.writable_paths),
        },
    }
    envelope = dict(envelope_without_digest)
    envelope["envelope_digest"] = sha256_json(envelope_without_digest)
    return envelope


def write_task_envelope(path: Path | str, envelope: Mapping[str, Any]) -> None:
    """Write a non-authoritative staging projection for review and evidence."""
    target = Path(path)
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(canonical_json(dict(envelope)) + b"\n")
    except OSError as error:
        raise TaskEnvelopeError(
            f"cannot write task envelope {target}: {error}"
        ) from error
