#!/usr/bin/env python3
"""Fail-closed acceptance-to-GameTest traceability gate.

The gate joins three independently inspectable facts:

* a v2 acceptance criterion references an acceptance test by ``test_ids``;
* a ``kind: gametest`` acceptance test names a stable ``Class#method`` symbol;
* a schema-v2 GameTest report discovered that exact source symbol and proved
  the complete discovered set passed in a real GameTestServer run.

No contract command is executed here.  This module only validates and joins
already-produced evidence, including re-hashing the current Java source so a
stale GameTest report fails closed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

import gametest_gate


SCHEMA_VERSION = 1
GAME_TEST_REPORT_SCHEMA_VERSION = 2
SYMBOL_PATTERN = re.compile(
    r"^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"
    r"#[A-Za-z_$][\w$]*$"
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
HOLDER_NAMESPACE_PATTERN = re.compile(r"^[a-z0-9_.-]+$")


@dataclass(frozen=True)
class Finding:
    code: str
    message: str
    file: Optional[str] = None
    json_path: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class TraceMapping:
    contract_id: str
    criterion_id: str
    acceptance_test_id: str
    symbol: str
    source_path: str
    source_sha256: str
    holder_namespace: str
    evidence_level: str
    l4_status: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class TraceabilityReport:
    contract_inputs: list[dict[str, str]]
    gametest_report: dict[str, str]
    project_dir: Optional[str]
    aggregate_evidence: dict[str, Any]
    mappings: list[TraceMapping]
    criterion_coverage: list[dict[str, Any]]
    findings: list[Finding]

    @property
    def passed(self) -> bool:
        return not self.findings and bool(self.mappings)

    def to_dict(self) -> dict[str, Any]:
        required_coverage = [
            entry
            for entry in self.criterion_coverage
            if entry.get("required") is True
        ]
        required_covered = [
            entry
            for entry in required_coverage
            if entry.get("l4_covered") is True
        ]
        full_required_coverage = bool(required_coverage) and (
            len(required_covered) == len(required_coverage)
        )
        return {
            "schema_version": SCHEMA_VERSION,
            "gate": "L4 acceptance traceability",
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "inputs": {
                "contracts": list(self.contract_inputs),
                "gametest_report": dict(self.gametest_report),
                "project_dir": self.project_dir,
            },
            "aggregate_evidence": dict(self.aggregate_evidence),
            "mappings": [mapping.to_dict() for mapping in self.mappings],
            "criterion_coverage": list(self.criterion_coverage),
            "coverage": {
                "scope": "required_acceptance_criteria_backed_by_gametest",
                "required_criteria_total": len(required_coverage),
                "required_criteria_covered": len(required_covered),
                "required_criteria_uncovered": (
                    len(required_coverage) - len(required_covered)
                ),
                "full_required_criteria_coverage": full_required_coverage,
                "covered": [
                    {
                        "contract_id": entry["contract_id"],
                        "criterion_id": entry["criterion_id"],
                    }
                    for entry in required_covered
                ],
                "uncovered": [
                    {
                        "contract_id": entry["contract_id"],
                        "criterion_id": entry["criterion_id"],
                        "reason": entry["reason"],
                    }
                    for entry in required_coverage
                    if entry.get("l4_covered") is not True
                ],
                "note": (
                    "A passing traceability gate validates the GameTest chains "
                    "it reports; it does not imply that non-GameTest criteria "
                    "have been verified."
                ),
            },
            "findings": [finding.to_dict() for finding in self.findings],
            "result": {
                "status": "passed" if self.passed else "failed",
                "passed": self.passed,
                "mapping_count": len(self.mappings),
                "error_count": len(self.findings),
                "full_required_criteria_coverage": full_required_coverage,
            },
        }


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, dict) else {}


def _list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


class _DuplicateJsonKey(ValueError):
    pass


def _strict_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateJsonKey(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _reject_nonfinite_json(value: str) -> None:
    raise ValueError(f"non-finite JSON number is not allowed: {value}")


def _contract_files(paths: Sequence[Path | str]) -> list[Path]:
    files: dict[str, Path] = {}
    for raw_path in paths:
        path = Path(raw_path).resolve()
        if path.is_file() and path.suffix.lower() == ".json":
            if not path.name.endswith(".schema.json"):
                files[str(path)] = path
        elif path.is_dir():
            for candidate in path.rglob("*.json"):
                if (
                    candidate.is_file()
                    and not candidate.name.endswith(".schema.json")
                ):
                    resolved = candidate.resolve()
                    files[str(resolved)] = resolved
    return sorted(files.values(), key=lambda path: path.as_posix().lower())


def _read_json_object(
    path: Path,
    findings: list[Finding],
    *,
    label: str,
) -> tuple[Optional[Mapping[str, Any]], Optional[str]]:
    try:
        content = path.read_bytes()
    except OSError as exc:
        findings.append(
            Finding(
                code=f"{label}_unreadable",
                message=str(exc),
                file=str(path),
            )
        )
        return None, None
    try:
        parsed = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=_strict_json_object,
            parse_constant=_reject_nonfinite_json,
        )
    except (UnicodeError, json.JSONDecodeError, ValueError) as exc:
        findings.append(
            Finding(
                code=f"{label}_invalid_json",
                message=str(exc),
                file=str(path),
            )
        )
        return None, _sha256(content)
    if not isinstance(parsed, dict):
        findings.append(
            Finding(
                code=f"{label}_not_object",
                message="top-level JSON value must be an object",
                file=str(path),
            )
        )
        return None, _sha256(content)
    return parsed, _sha256(content)


def _validate_gametest_report(
    report_path: Path,
    data: Mapping[str, Any],
    project_override: Optional[Path],
    findings: list[Finding],
) -> tuple[dict[str, Mapping[str, Any]], Optional[Path], dict[str, Any]]:
    start_error_count = len(findings)
    if data.get("schema_version") != GAME_TEST_REPORT_SCHEMA_VERSION:
        findings.append(
            Finding(
                code="unsupported_gametest_report_version",
                message=(
                    "traceability requires a schema-v2 GameTest report with "
                    "stable source symbols"
                ),
                file=str(report_path),
                json_path="$.schema_version",
            )
        )

    discovery = _mapping(data.get("discovery"))
    tests = _list(discovery.get("tests"))
    discovery_count = discovery.get("count")
    if not _is_int(discovery_count) or discovery_count != len(tests):
        findings.append(
            Finding(
                code="discovery_count_mismatch",
                message=(
                    f"discovery.count={discovery_count!r} but "
                    f"{len(tests)} test record(s) were reported"
                ),
                file=str(report_path),
                json_path="$.discovery.count",
            )
        )
    if discovery_count == 0:
        findings.append(
            Finding(
                code="no_discovered_gametests",
                message="aggregate L4 evidence requires at least one GameTest",
                file=str(report_path),
                json_path="$.discovery.tests",
            )
        )
    raw_discovery_errors = discovery.get("errors")
    if not isinstance(raw_discovery_errors, list):
        findings.append(
            Finding(
                code="invalid_discovery_errors",
                message="discovery.errors must be an array",
                file=str(report_path),
                json_path="$.discovery.errors",
            )
        )
        raw_discovery_errors = []
    for error in raw_discovery_errors:
        findings.append(
            Finding(
                code="gametest_discovery_error",
                message=str(error),
                file=str(report_path),
                json_path="$.discovery.errors",
            )
        )

    raw_project = discovery.get("project_dir")
    reported_project = (
        Path(raw_project).resolve()
        if isinstance(raw_project, str) and raw_project.strip()
        else None
    )
    if project_override is not None:
        project_dir = project_override.resolve()
        if reported_project is not None and reported_project != project_dir:
            findings.append(
                Finding(
                    code="project_dir_mismatch",
                    message=(
                        "GameTest report source root does not match the "
                        f"verification root: {reported_project} != {project_dir}"
                    ),
                    file=str(report_path),
                    json_path="$.discovery.project_dir",
                )
            )
    elif reported_project is not None:
        project_dir = reported_project
    else:
        project_dir = None
        findings.append(
            Finding(
                code="project_dir_missing",
                message=(
                    "GameTest report does not identify the source tree and no "
                    "--project-dir override was supplied"
                ),
                file=str(report_path),
                json_path="$.discovery.project_dir",
            )
        )

    current_symbol_index: dict[str, Mapping[str, Any]] = {}
    if project_dir is not None:
        current_discovery = gametest_gate.discover_gametests(project_dir)
        for error in current_discovery.errors:
            findings.append(
                Finding(
                    code="current_source_discovery_error",
                    message=error,
                    file=str(project_dir),
                )
            )
        for occurrence in current_discovery.tests:
            if occurrence.symbol is None:
                continue
            current_symbol_index.setdefault(
                occurrence.symbol, asdict(occurrence)
            )

    symbol_index: dict[str, Mapping[str, Any]] = {}
    source_hash_cache: dict[Path, Optional[str]] = {}
    for index, raw_test in enumerate(tests):
        test_path = f"$.discovery.tests[{index}]"
        test = _mapping(raw_test)
        if not isinstance(raw_test, dict):
            findings.append(
                Finding(
                    code="invalid_discovered_test_record",
                    message="discovered test record must be an object",
                    file=str(report_path),
                    json_path=test_path,
                )
            )
        symbol = test.get("symbol")
        fqcn = test.get("fqcn")
        method = test.get("method")
        source_path = test.get("path")
        source_sha256 = test.get("source_sha256")

        if not isinstance(symbol, str) or not SYMBOL_PATTERN.fullmatch(symbol):
            findings.append(
                Finding(
                    code="invalid_discovered_symbol",
                    message=f"invalid or missing GameTest symbol: {symbol!r}",
                    file=str(report_path),
                    json_path=f"{test_path}.symbol",
                )
            )
        elif symbol in symbol_index:
            findings.append(
                Finding(
                    code="duplicate_discovered_symbol",
                    message=f"GameTest symbol `{symbol}` is duplicated",
                    file=str(report_path),
                    json_path=f"{test_path}.symbol",
                )
            )
        else:
            symbol_index[symbol] = test

        if not (
            isinstance(fqcn, str)
            and SYMBOL_PATTERN.fullmatch(f"{fqcn}#placeholder")
        ):
            findings.append(
                Finding(
                    code="invalid_discovered_fqcn",
                    message=f"invalid or missing GameTest fqcn: {fqcn!r}",
                    file=str(report_path),
                    json_path=f"{test_path}.fqcn",
                )
            )
        if not (
            isinstance(method, str)
            and re.fullmatch(r"[A-Za-z_$][\w$]*", method)
        ):
            findings.append(
                Finding(
                    code="invalid_discovered_method",
                    message=f"invalid or missing GameTest method: {method!r}",
                    file=str(report_path),
                    json_path=f"{test_path}.method",
                )
            )
        if (
            isinstance(fqcn, str)
            and isinstance(method, str)
            and symbol != f"{fqcn}#{method}"
        ):
            findings.append(
                Finding(
                    code="discovered_symbol_components_mismatch",
                    message=(
                        f"GameTest symbol `{symbol}` does not equal "
                        f"`{fqcn}#{method}`"
                    ),
                    file=str(report_path),
                    json_path=f"{test_path}.symbol",
                )
            )
        if test.get("signature_valid") is not True:
            findings.append(
                Finding(
                    code="invalid_gametest_signature",
                    message=(
                        f"GameTest `{symbol}` is outside the strict static "
                        "source subset"
                    ),
                    file=str(report_path),
                    json_path=f"{test_path}.signature_valid",
                )
            )
        signature_errors = test.get("signature_errors")
        if not isinstance(signature_errors, list) or signature_errors:
            findings.append(
                Finding(
                    code="gametest_signature_errors_present",
                    message=(
                        f"GameTest `{symbol}` must report an empty "
                        "signature_errors array"
                    ),
                    file=str(report_path),
                    json_path=f"{test_path}.signature_errors",
                )
            )
        if not (
            isinstance(test.get("holder_namespace"), str)
            and HOLDER_NAMESPACE_PATTERN.fullmatch(
                test["holder_namespace"]
            )
        ):
            findings.append(
                Finding(
                    code="holder_namespace_missing",
                    message=f"GameTest `{symbol}` has no holder namespace",
                    file=str(report_path),
                    json_path=f"{test_path}.holder_namespace",
                )
            )
        if not (
            isinstance(source_sha256, str)
            and SHA256_PATTERN.fullmatch(source_sha256)
        ):
            findings.append(
                Finding(
                    code="invalid_source_digest",
                    message=f"GameTest `{symbol}` has no valid source SHA-256",
                    file=str(report_path),
                    json_path=f"{test_path}.source_sha256",
                )
            )
            continue
        if not (
            isinstance(source_path, str)
            and source_path.startswith("src/main/java/")
            and source_path.endswith(".java")
            and "\\" not in source_path
            and not source_path.startswith("/")
            and "/../" not in f"/{source_path}/"
        ):
            findings.append(
                Finding(
                    code="source_path_outside_main",
                    message=(
                        f"GameTest `{symbol}` source must be a portable path "
                        f"under src/main/java: {source_path!r}"
                    ),
                    file=str(report_path),
                    json_path=f"{test_path}.path",
                )
            )
            continue
        if (
            isinstance(fqcn, str)
            and source_path
            != f"src/main/java/{fqcn.replace('.', '/')}.java"
        ):
            findings.append(
                Finding(
                    code="source_path_fqcn_mismatch",
                    message=(
                        f"GameTest `{symbol}` source path does not match "
                        f"its top-level fqcn `{fqcn}`"
                    ),
                    file=str(report_path),
                    json_path=f"{test_path}.path",
                )
            )
        if project_dir is None:
            if not isinstance(source_path, str):
                findings.append(
                    Finding(
                        code="source_path_missing",
                        message=f"GameTest `{symbol}` has no source path",
                        file=str(report_path),
                        json_path=f"{test_path}.path",
                    )
                )
            continue

        main_source_root = (project_dir / "src" / "main" / "java").resolve()
        try:
            main_source_root.relative_to(project_dir)
        except ValueError:
            findings.append(
                Finding(
                    code="main_source_root_escape",
                    message="src/main/java resolves outside the project root",
                    file=str(main_source_root),
                )
            )
            continue
        source_file = (project_dir / source_path).resolve()
        try:
            source_file.relative_to(main_source_root)
        except ValueError:
            findings.append(
                Finding(
                    code="source_path_escape",
                    message=f"source path escapes project root: {source_path}",
                    file=str(report_path),
                    json_path=f"{test_path}.path",
                )
            )
            continue
        if source_file not in source_hash_cache:
            try:
                source_hash_cache[source_file] = _sha256(
                    source_file.read_bytes()
                )
            except OSError as exc:
                source_hash_cache[source_file] = None
                findings.append(
                    Finding(
                        code="source_unreadable",
                        message=str(exc),
                        file=str(source_file),
                    )
                )
        current_digest = source_hash_cache[source_file]
        if current_digest is not None and current_digest != source_sha256:
            findings.append(
                Finding(
                    code="source_digest_drift",
                    message=(
                        f"source changed after GameTest discovery: {source_path}"
                    ),
                    file=str(source_file),
                    json_path=f"{test_path}.source_sha256",
                )
            )

        if isinstance(symbol, str):
            current = current_symbol_index.get(symbol)
            if current is None:
                findings.append(
                    Finding(
                        code="source_symbol_not_currently_discovered",
                        message=(
                            f"GameTest `{symbol}` is absent from the current "
                            "strict src/main/java discovery"
                        ),
                        file=str(source_file),
                    )
                )
            else:
                for field in (
                    "path",
                    "fqcn",
                    "method",
                    "symbol",
                    "holder_namespace",
                    "source_sha256",
                    "signature_valid",
                ):
                    if test.get(field) != current.get(field):
                        findings.append(
                            Finding(
                                code="discovery_metadata_drift",
                                message=(
                                    f"GameTest `{symbol}` field `{field}` "
                                    "differs from current strict discovery"
                                ),
                                file=str(report_path),
                                json_path=f"{test_path}.{field}",
                            )
                        )

    if set(symbol_index) != set(current_symbol_index):
        findings.append(
            Finding(
                code="discovered_source_set_drift",
                message=(
                    "GameTest report symbols differ from current strict "
                    f"src/main/java discovery (report={sorted(symbol_index)}, "
                    f"current={sorted(current_symbol_index)})"
                ),
                file=str(report_path),
                json_path="$.discovery.tests",
            )
        )

    execution = _mapping(data.get("execution"))
    result = _mapping(data.get("result"))
    evidence_level = execution.get("evidence_level")
    count_values = {
        "discovered": execution.get("discovered_tests"),
        "running": execution.get("running_tests"),
        "complete": execution.get("complete_tests"),
        "passed": execution.get("required_passed_tests"),
        "total": execution.get("total_tests"),
    }
    count_set = {
        value for value in count_values.values() if _is_int(value)
    }
    count_evidence_valid = (
        all(_is_int(value) for value in count_values.values())
        and len(count_set) == 1
        and next(iter(count_set), 0) > 0
        and count_values["discovered"] == discovery_count
        and execution.get("count_consistent") is True
    )
    if not count_evidence_valid:
        findings.append(
            Finding(
                code="aggregate_count_mismatch",
                message=f"aggregate GameTest counts disagree: {count_values}",
                file=str(report_path),
                json_path="$.execution",
            )
        )
    execution_coherent = (
        execution.get("status") == "passed"
        and execution.get("passed") is True
        and _is_int(execution.get("returncode"))
        and execution.get("returncode") == 0
        and execution.get("timed_out") is False
        and execution.get("required_passed_marker") is True
        and _is_int(execution.get("required_failures"))
        and execution.get("required_failures") == 0
        and _is_int(execution.get("optional_failures"))
        and execution.get("optional_failures") == 0
        and execution.get("completion_marker") is True
        and execution.get("no_tests_marker") is False
        and execution.get("launch_error") is None
        and evidence_level == "aggregate_set"
        and result.get("status") == "passed"
        and result.get("passed") is True
        and result.get("command_ok") is True
        and result.get("executed") is True
        and result.get("evidence_satisfied") is True
    )
    if not execution_coherent:
        findings.append(
            Finding(
                code="aggregate_l4_not_passed",
                message=(
                    "GameTest report does not contain successful aggregate-set "
                    "L4 evidence"
                ),
                file=str(report_path),
                json_path="$.execution",
            )
        )

    aggregate = {
        "status": (
            "passed" if len(findings) == start_error_count else "failed"
        ),
        "passed": len(findings) == start_error_count,
        "evidence_level": evidence_level,
        "counts": count_values,
    }
    return symbol_index, project_dir, aggregate


def _join_contracts(
    contract_documents: Sequence[tuple[Path, Mapping[str, Any], str]],
    symbol_index: Mapping[str, Mapping[str, Any]],
    aggregate_evidence: Mapping[str, Any],
    findings: list[Finding],
) -> tuple[list[TraceMapping], list[dict[str, Any]]]:
    mappings: list[TraceMapping] = []
    criterion_coverage: list[dict[str, Any]] = []
    global_contract_ids: dict[str, Path] = {}
    global_criterion_ids: dict[str, Path] = {}
    global_test_refs: dict[str, tuple[Path, str]] = {}
    declared_gametests: dict[tuple[str, str], tuple[Path, str]] = {}
    mapped_gametests: set[tuple[str, str]] = set()

    for contract_path, contract, _contract_sha256 in contract_documents:
        if contract.get("schema_version") != 2:
            findings.append(
                Finding(
                    code="unsupported_contract_version",
                    message="traceability requires a schema_version 2 contract",
                    file=str(contract_path),
                    json_path="$.schema_version",
                )
            )
            continue
        contract_id = contract.get("id")
        if not isinstance(contract_id, str) or not contract_id:
            findings.append(
                Finding(
                    code="contract_id_missing",
                    message="contract id must be a non-empty string",
                    file=str(contract_path),
                    json_path="$.id",
                )
            )
            continue
        previous_contract_path = global_contract_ids.get(contract_id)
        if previous_contract_path is not None:
            findings.append(
                Finding(
                    code="duplicate_contract_id",
                    message=(
                        f"contract id `{contract_id}` was already declared by "
                        f"{previous_contract_path}"
                    ),
                    file=str(contract_path),
                    json_path="$.id",
                )
            )
            continue
        global_contract_ids[contract_id] = contract_path

        acceptance = _mapping(contract.get("acceptance"))
        raw_tests = _list(acceptance.get("tests"))
        raw_criteria = _list(acceptance.get("criteria"))
        tests_by_id: dict[str, Mapping[str, Any]] = {}
        for index, raw_test in enumerate(raw_tests):
            test = _mapping(raw_test)
            test_id = test.get("id")
            test_path = f"$.acceptance.tests[{index}]"
            if not isinstance(test_id, str) or not test_id:
                findings.append(
                    Finding(
                        code="acceptance_test_id_missing",
                        message="acceptance test id must be a non-empty string",
                        file=str(contract_path),
                        json_path=f"{test_path}.id",
                    )
                )
                continue
            if test_id in tests_by_id:
                findings.append(
                    Finding(
                        code="duplicate_acceptance_test_id",
                        message=f"acceptance test id `{test_id}` is duplicated",
                        file=str(contract_path),
                        json_path=f"{test_path}.id",
                    )
                )
                continue
            tests_by_id[test_id] = test

            if test.get("kind") != "gametest":
                continue
            if test.get("required") is not True:
                findings.append(
                    Finding(
                        code="gametest_acceptance_not_required",
                        message=(
                            f"GameTest acceptance test `{test_id}` must be "
                            "required to support an L4 evidence chain"
                        ),
                        file=str(contract_path),
                        json_path=f"{test_path}.required",
                    )
                )
            key = (contract_id, test_id)
            test_ref = test.get("test_ref")
            declared_gametests[key] = (contract_path, str(test_ref))
            if not (
                isinstance(test_ref, str)
                and SYMBOL_PATTERN.fullmatch(test_ref)
            ):
                findings.append(
                    Finding(
                        code="gametest_ref_missing_or_invalid",
                        message=(
                            "v2 GameTest acceptance tests require "
                            "`fully.qualified.Class#method` test_ref"
                        ),
                        file=str(contract_path),
                        json_path=f"{test_path}.test_ref",
                    )
                )
                continue
            previous = global_test_refs.get(test_ref)
            if previous is not None:
                findings.append(
                    Finding(
                        code="duplicate_gametest_ref",
                        message=(
                            f"`{test_ref}` is already declared by "
                            f"{previous[0]} acceptance test `{previous[1]}`"
                        ),
                        file=str(contract_path),
                        json_path=f"{test_path}.test_ref",
                    )
                )
            else:
                global_test_refs[test_ref] = (contract_path, test_id)

        for index, raw_criterion in enumerate(raw_criteria):
            criterion = _mapping(raw_criterion)
            criterion_path = f"$.acceptance.criteria[{index}]"
            criterion_id = criterion.get("id")
            if not isinstance(criterion_id, str) or not criterion_id:
                findings.append(
                    Finding(
                        code="criterion_id_missing",
                        message="criterion id must be a non-empty string",
                        file=str(contract_path),
                        json_path=f"{criterion_path}.id",
                    )
                )
                continue
            previous_contract = global_criterion_ids.get(criterion_id)
            if previous_contract is not None:
                findings.append(
                    Finding(
                        code="duplicate_criterion_id",
                        message=(
                            f"criterion id `{criterion_id}` was already "
                            f"declared by {previous_contract}"
                        ),
                        file=str(contract_path),
                        json_path=f"{criterion_path}.id",
                    )
                )
            else:
                global_criterion_ids[criterion_id] = contract_path

            test_ids = criterion.get("test_ids")
            if not isinstance(test_ids, list):
                findings.append(
                    Finding(
                        code="criterion_test_ids_missing",
                        message="criterion test_ids must be an array",
                        file=str(contract_path),
                        json_path=f"{criterion_path}.test_ids",
                    )
                )
                continue
            if not test_ids:
                findings.append(
                    Finding(
                        code="criterion_test_ids_empty",
                        message=(
                            f"criterion `{criterion_id}` must reference at "
                            "least one acceptance test"
                        ),
                        file=str(contract_path),
                        json_path=f"{criterion_path}.test_ids",
                    )
                )
            if len(test_ids) != len(set(map(str, test_ids))):
                findings.append(
                    Finding(
                        code="duplicate_criterion_test_reference",
                        message=(
                            f"criterion `{criterion_id}` repeats a test id"
                        ),
                        file=str(contract_path),
                        json_path=f"{criterion_path}.test_ids",
                    )
                )

            criterion_gametest_ids: list[str] = []
            criterion_symbols: list[str] = []
            for test_index, test_id in enumerate(test_ids):
                reference_path = (
                    f"{criterion_path}.test_ids[{test_index}]"
                )
                if not isinstance(test_id, str) or test_id not in tests_by_id:
                    findings.append(
                        Finding(
                            code="unknown_criterion_test_reference",
                            message=(
                                f"criterion `{criterion_id}` references "
                                f"unknown test `{test_id}`"
                            ),
                            file=str(contract_path),
                            json_path=reference_path,
                        )
                    )
                    continue
                test = tests_by_id[test_id]
                if test.get("kind") != "gametest":
                    continue
                criterion_gametest_ids.append(test_id)
                key = (contract_id, test_id)
                mapped_gametests.add(key)
                symbol = test.get("test_ref")
                if not (
                    isinstance(symbol, str)
                    and SYMBOL_PATTERN.fullmatch(symbol)
                ):
                    continue
                discovered = symbol_index.get(symbol)
                if discovered is None:
                    findings.append(
                        Finding(
                            code="gametest_symbol_not_discovered",
                            message=(
                                f"acceptance test `{test_id}` references "
                                f"undiscovered GameTest `{symbol}`"
                            ),
                            file=str(contract_path),
                            json_path=reference_path,
                        )
                    )
                    continue
                if discovered.get("signature_valid") is not True:
                    findings.append(
                        Finding(
                            code="mapped_gametest_signature_invalid",
                            message=(
                                f"mapped GameTest `{symbol}` has an invalid "
                                "strict signature"
                            ),
                            file=str(contract_path),
                            json_path=reference_path,
                        )
                    )
                    continue
                mappings.append(
                    TraceMapping(
                        contract_id=contract_id,
                        criterion_id=criterion_id,
                        acceptance_test_id=test_id,
                        symbol=symbol,
                        source_path=str(discovered.get("path")),
                        source_sha256=str(discovered.get("source_sha256")),
                        holder_namespace=str(
                            discovered.get("holder_namespace")
                        ),
                        evidence_level=str(
                            aggregate_evidence.get("evidence_level")
                        ),
                        l4_status=str(aggregate_evidence.get("status")),
                    )
                )
                criterion_symbols.append(symbol)

            aggregate_passed = (
                aggregate_evidence.get("passed") is True
                and aggregate_evidence.get("status") == "passed"
                and aggregate_evidence.get("evidence_level")
                == "aggregate_set"
            )
            l4_covered = bool(criterion_symbols) and aggregate_passed
            if l4_covered:
                coverage_reason = "mapped GameTest symbol is in the passed L4 set"
            elif not criterion_gametest_ids:
                coverage_reason = (
                    "criterion has no GameTest acceptance test; another gate "
                    "must provide its evidence"
                )
            elif not criterion_symbols:
                coverage_reason = (
                    "criterion references GameTest acceptance tests, but no "
                    "valid current source symbol was joined"
                )
            else:
                coverage_reason = "aggregate L4 evidence did not pass"
            criterion_coverage.append(
                {
                    "contract_id": contract_id,
                    "criterion_id": criterion_id,
                    "required": criterion.get("required") is True,
                    "gametest_test_ids": criterion_gametest_ids,
                    "mapped_symbols": criterion_symbols,
                    "l4_covered": l4_covered,
                    "reason": coverage_reason,
                }
            )

    for key, (contract_path, symbol) in declared_gametests.items():
        if key not in mapped_gametests:
            findings.append(
                Finding(
                    code="gametest_not_linked_to_criterion",
                    message=(
                        f"GameTest acceptance test `{key[1]}` ({symbol}) is "
                        "not referenced by any acceptance criterion"
                    ),
                    file=str(contract_path),
                    json_path="$.acceptance.criteria",
                )
            )
    if not mappings:
        findings.append(
            Finding(
                code="no_traceability_mappings",
                message=(
                    "no acceptance criterion maps to a discovered GameTest"
                ),
            )
        )
    return mappings, criterion_coverage


def run_gate(
    contract_paths: Sequence[Path | str],
    gametest_report_path: Path | str,
    *,
    project_dir: Optional[Path | str] = None,
) -> TraceabilityReport:
    findings: list[Finding] = []
    contract_files = _contract_files(contract_paths)
    contract_documents: list[tuple[Path, Mapping[str, Any], str]] = []
    contract_inputs: list[dict[str, str]] = []
    if not contract_files:
        findings.append(
            Finding(
                code="no_contracts",
                message="no v2 feature contract JSON files were found",
            )
        )
    for contract_path in contract_files:
        contract, digest = _read_json_object(
            contract_path, findings, label="contract"
        )
        if digest is not None:
            contract_inputs.append(
                {"path": str(contract_path), "sha256": digest}
            )
        if contract is not None and digest is not None:
            contract_documents.append((contract_path, contract, digest))

    report_path = Path(gametest_report_path).resolve()
    gametest_data, gametest_digest = _read_json_object(
        report_path, findings, label="gametest_report"
    )
    report_input = {
        "path": str(report_path),
        "sha256": gametest_digest or "",
    }
    symbol_index: dict[str, Mapping[str, Any]] = {}
    resolved_project: Optional[Path] = (
        Path(project_dir).resolve() if project_dir is not None else None
    )
    aggregate_evidence: dict[str, Any] = {
        "status": "failed",
        "passed": False,
        "evidence_level": None,
        "counts": {},
    }
    if gametest_data is not None:
        symbol_index, resolved_project, aggregate_evidence = (
            _validate_gametest_report(
                report_path,
                gametest_data,
                resolved_project,
                findings,
            )
        )

    mappings, criterion_coverage = _join_contracts(
        contract_documents,
        symbol_index,
        aggregate_evidence,
        findings,
    )
    return TraceabilityReport(
        contract_inputs=contract_inputs,
        gametest_report=report_input,
        project_dir=(
            str(resolved_project) if resolved_project is not None else None
        ),
        aggregate_evidence=aggregate_evidence,
        mappings=mappings,
        criterion_coverage=criterion_coverage,
        findings=findings,
    )


def _default_project_dir() -> Path:
    return Path(__file__).resolve().parents[2]


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Join v2 acceptance criteria to strict aggregate-set GameTest "
            "evidence."
        )
    )
    parser.add_argument(
        "contracts",
        nargs="*",
        type=Path,
        help=(
            "v2 contract file(s) or directorie(s); default: "
            "<project>/docs/features"
        ),
    )
    parser.add_argument(
        "--gametest-report",
        type=Path,
        required=True,
        help="schema-v2 JSON report produced by gametest_gate.py",
    )
    parser.add_argument(
        "--project-dir",
        type=Path,
        default=_default_project_dir(),
        help="source tree used to re-hash discovered Java files",
    )
    parser.add_argument(
        "--json-report",
        type=Path,
        help="optionally write a machine-readable traceability report",
    )
    return parser


def _write_json_report(path: Path, report: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    args = _parser().parse_args(argv)
    project = args.project_dir.resolve()
    contracts = args.contracts or [project / "docs" / "features"]
    report = run_gate(
        contracts,
        args.gametest_report,
        project_dir=project,
    )

    print(
        "L4 traceability: "
        f"{len(report.mappings)} mapping(s), "
        f"{len(report.findings)} error(s)."
    )
    for mapping in report.mappings:
        print(
            f"  PASS {mapping.criterion_id} -> {mapping.symbol} -> "
            f"{mapping.source_sha256}"
        )
    for finding in report.findings:
        location = f" [{finding.file}]" if finding.file else ""
        print(f"  ERROR {finding.code}{location}: {finding.message}")

    if args.json_report is not None:
        report_path = (
            args.json_report
            if args.json_report.is_absolute()
            else project / args.json_report
        )
        try:
            _write_json_report(report_path, report.to_dict())
        except OSError as exc:
            print(
                f"L4 TRACEABILITY TOOL ERROR: could not write JSON report: {exc}"
            )
            return 2
        print(f"JSON report: {report_path}")
    return 0 if report.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
