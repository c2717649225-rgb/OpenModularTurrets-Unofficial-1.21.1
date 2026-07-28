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

from execution_policy import (
    ExecutionPolicy,
    PolicyError,
    canonical_json,
    sha256_json,
    validate_execution_policy,
)


SCHEMA_VERSION = 1
STABILITY = "provisional"
FROZEN_INPUT_SCHEMA_VERSION = 1
FROZEN_INPUT_ROLES = frozenset(
    {
        "contract",
        "manifest",
        "design_source",
        "approved_asset",
    }
)
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


def _load_json_object_and_digest(
    path: Path | str,
) -> tuple[dict[str, Any], str]:
    target = Path(path)
    try:
        raw = target.read_bytes()
        value = json.loads(
            raw.decode("utf-8", errors="strict"),
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
    return value, hashlib.sha256(raw).hexdigest()


def load_json_object(path: Path | str) -> dict[str, Any]:
    value, _digest = _load_json_object_and_digest(path)
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


def _require_mapping_matches_file(
    supplied: Mapping[str, Any],
    loaded: Mapping[str, Any],
    *,
    field: str,
) -> None:
    if not isinstance(supplied, Mapping):
        raise TaskEnvelopeError(f"{field} must be a JSON object")
    try:
        supplied_bytes = canonical_json(dict(supplied))
        loaded_bytes = canonical_json(dict(loaded))
    except (TypeError, ValueError) as error:
        raise TaskEnvelopeError(
            f"{field} is not finite plain JSON data: {error}"
        ) from error
    if supplied_bytes != loaded_bytes:
        raise TaskEnvelopeError(
            f"{field} mapping does not match the strict JSON content "
            "loaded from its path"
        )


def _build_frozen_input_snapshot(
    *,
    contract: Mapping[str, str],
    manifest: Mapping[str, str],
    design_sources: Sequence[Mapping[str, str]],
    approved_assets: Sequence[Mapping[str, str]],
) -> dict[str, Any]:
    grouped: dict[str, dict[str, Any]] = {}

    def add(record: Mapping[str, str], role: str) -> None:
        path = record["path"]
        digest = record["sha256"]
        existing = grouped.get(path)
        if existing is None:
            grouped[path] = {
                "path": path,
                "sha256": digest,
                "roles": {role},
            }
            return
        if existing["sha256"] != digest:
            raise TaskEnvelopeError(
                f"frozen input {path!r} has conflicting SHA-256 digests"
            )
        existing["roles"].add(role)

    add(contract, "contract")
    add(manifest, "manifest")
    for record in design_sources:
        add(record, "design_source")
    for record in approved_assets:
        add(record, "approved_asset")

    records = [
        {
            "path": record["path"],
            "sha256": record["sha256"],
            "roles": sorted(record["roles"]),
        }
        for record in grouped.values()
    ]
    records.sort(key=lambda record: record["path"])
    without_digest = {
        "schema_version": FROZEN_INPUT_SCHEMA_VERSION,
        "records": records,
    }
    snapshot = dict(without_digest)
    snapshot["digest"] = sha256_json(without_digest)
    return snapshot


def verify_frozen_input_snapshot(
    snapshot: Mapping[str, Any],
    *,
    workspace: Path | str,
) -> str:
    """Re-hash every frozen task input after execution.

    The returned digest is the immutable snapshot digest that callers can bind
    into authoritative evidence. Any missing, symlinked, changed, reordered, or
    structurally malformed input fails closed.
    """
    if not isinstance(snapshot, Mapping):
        raise TaskEnvelopeError("frozen input snapshot must be an object")
    if set(snapshot) != {"schema_version", "records", "digest"}:
        raise TaskEnvelopeError(
            "frozen input snapshot has invalid fields"
        )
    if snapshot.get("schema_version") != FROZEN_INPUT_SCHEMA_VERSION:
        raise TaskEnvelopeError(
            "frozen input snapshot schema_version is unsupported"
        )
    records = snapshot.get("records")
    if not isinstance(records, list) or not records:
        raise TaskEnvelopeError(
            "frozen input snapshot records must be a non-empty array"
        )
    claimed_digest = snapshot.get("digest")
    if (
        not isinstance(claimed_digest, str)
        or len(claimed_digest) != 64
        or any(character not in "0123456789abcdef" for character in claimed_digest)
    ):
        raise TaskEnvelopeError(
            "frozen input snapshot digest must be lowercase SHA-256"
        )
    without_digest = {
        "schema_version": snapshot["schema_version"],
        "records": records,
    }
    if sha256_json(without_digest) != claimed_digest:
        raise TaskEnvelopeError("frozen input snapshot digest mismatch")

    root = Path(workspace).resolve()
    normalized_records: list[dict[str, Any]] = []
    seen_paths: set[str] = set()
    for index, raw_record in enumerate(records):
        field = f"frozen_inputs.records[{index}]"
        if not isinstance(raw_record, Mapping) or set(raw_record) != {
            "path",
            "sha256",
            "roles",
        }:
            raise TaskEnvelopeError(f"{field} has invalid fields")
        roles = raw_record.get("roles")
        if (
            not isinstance(roles, list)
            or not roles
            or any(
                not isinstance(role, str)
                or role not in FROZEN_INPUT_ROLES
                for role in roles
            )
            or roles != sorted(roles)
            or len(set(roles)) != len(roles)
        ):
            raise TaskEnvelopeError(
                f"{field}.roles must be sorted, unique, and supported"
            )
        verified = _verified_input_file(
            root,
            raw_record,
            field=field,
        )
        if verified["path"] in seen_paths:
            raise TaskEnvelopeError(
                f"frozen input path is duplicated: {verified['path']}"
            )
        seen_paths.add(verified["path"])
        normalized_records.append(
            {
                **verified,
                "roles": list(roles),
            }
        )
    normalized_records.sort(key=lambda record: record["path"])
    if normalized_records != records:
        raise TaskEnvelopeError(
            "frozen input snapshot records are not canonical"
        )
    return claimed_digest


def _reject_writable_input_overlap(
    *,
    workspace: Path,
    writable_paths: Sequence[str],
    frozen_inputs: Mapping[str, Any],
) -> None:
    records = frozen_inputs.get("records")
    if not isinstance(records, list):
        raise TaskEnvelopeError("frozen input records are unavailable")
    resolved_inputs = [
        workspace.joinpath(*PurePosixPath(record["path"]).parts).resolve()
        for record in records
    ]
    for writable in writable_paths:
        writable_path = PurePosixPath(writable)
        writable_candidate = workspace.joinpath(*writable_path.parts)
        resolved_writable = (
            writable_candidate.resolve()
            if writable_candidate.exists()
            else None
        )
        for record, resolved_input in zip(records, resolved_inputs):
            input_path = PurePosixPath(record["path"])
            lexical_overlap = (
                input_path == writable_path
                or writable_path in input_path.parents
            )
            resolved_overlap = bool(
                resolved_writable is not None
                and (
                    resolved_input == resolved_writable
                    or _within(resolved_input, resolved_writable)
                )
            )
            if lexical_overlap or resolved_overlap:
                raise TaskEnvelopeError(
                    "execution policy writable path "
                    f"{writable!r} overlaps frozen input "
                    f"{record['path']!r}"
                )


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
    expected_policy_digest: str,
) -> dict[str, Any]:
    """Project validated inputs into one immutable, permission-narrow task."""
    try:
        validate_execution_policy(policy)
    except PolicyError as error:
        raise TaskEnvelopeError(
            f"execution policy is not valid: {error}"
        ) from error
    root = Path(workspace).resolve()
    if (
        not isinstance(expected_policy_digest, str)
        or len(expected_policy_digest) != 64
        or any(
            character not in "0123456789abcdef"
            for character in expected_policy_digest
        )
    ):
        raise TaskEnvelopeError(
            "expected_policy_digest must be a lowercase SHA-256 digest"
        )
    if policy.digest != expected_policy_digest:
        raise TaskEnvelopeError(
            "execution policy differs from the externally frozen digest"
        )
    raw_contract_file = Path(contract_path)
    raw_manifest_file = Path(manifest_path)
    contract_file = raw_contract_file.resolve()
    manifest_file = raw_manifest_file.resolve()
    for label, raw_target, target in (
        ("contract", raw_contract_file, contract_file),
        ("manifest", raw_manifest_file, manifest_file),
    ):
        if (
            raw_target.is_symlink()
            or not _within(target, root)
            or not target.is_file()
        ):
            raise TaskEnvelopeError(
                f"{label} must be a non-symlink file inside the workspace"
            )

    loaded_contract, contract_digest = _load_json_object_and_digest(
        contract_file
    )
    loaded_manifest, manifest_digest = _load_json_object_and_digest(
        manifest_file
    )
    _require_mapping_matches_file(
        contract,
        loaded_contract,
        field="contract",
    )
    _require_mapping_matches_file(
        manifest,
        loaded_manifest,
        field="manifest",
    )
    contract = loaded_contract
    manifest = loaded_manifest

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

    contract_record = {
        "path": contract_file.relative_to(root).as_posix(),
        "sha256": contract_digest,
    }
    manifest_record = {
        "path": manifest_file.relative_to(root).as_posix(),
        "sha256": manifest_digest,
    }
    frozen_inputs = _build_frozen_input_snapshot(
        contract=contract_record,
        manifest=manifest_record,
        design_sources=verified_manifest["design_sources"],
        approved_assets=verified_manifest["approved_assets"],
    )
    verify_frozen_input_snapshot(frozen_inputs, workspace=root)
    _reject_writable_input_overlap(
        workspace=root,
        writable_paths=policy.writable_paths,
        frozen_inputs=frozen_inputs,
    )

    envelope_without_digest: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "stability": STABILITY,
        "kind": "single_task_envelope",
        "task_id": f"{contract_id}.v{contract_version}",
        "project_id": verified_manifest["project_id"],
        "contract": {
            "id": contract_id,
            "version": contract_version,
            **contract_record,
        },
        "manifest": manifest_record,
        "design_source": verified_design,
        "frozen_inputs": frozen_inputs,
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
