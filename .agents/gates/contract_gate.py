#!/usr/bin/env python3
"""
Major feature contract gate for NeoForge 1.21.1 projects.

The gate deliberately uses only Python's standard library.  The companion
JSON Schema remains useful to editors and other tooling, while this module
implements the schema subset used by that file plus project-level semantic
checks:

* required fields, JSON types, enums, patterns and collection constraints;
* unresolved scaffold placeholders;
* duplicate contract/test/flow IDs;
* feature dependency cycles;
* authority, persistence, network and rate-limit consistency;
* executable acceptance-test declarations and their cross-references.

Usage:
    python .agents/run.py .agents/gates/contract_gate.py [FILE_OR_DIRECTORY ...]
    python .agents/run.py .agents/gates/contract_gate.py --require
    python .agents/run.py .agents/gates/contract_gate.py docs/features \
        --json-report build/reports/major-feature-contracts.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Set, Tuple


AGENTS_DIR = Path(__file__).resolve().parent.parent
PROJECT_DIR = AGENTS_DIR.parent
DEFAULT_SCHEMA = AGENTS_DIR / "contracts" / "major-feature.schema.json"
V2_SCHEMA = AGENTS_DIR / "contracts" / "major-feature-v2.schema.json"
DEFAULT_CONTRACT_DIRECTORY = PROJECT_DIR / "docs" / "features"
SCHEMA_PATHS = {
    1: DEFAULT_SCHEMA,
    2: V2_SCHEMA,
}

PLACEHOLDER_RE = re.compile(
    r"\{\{[^{}\r\n]+\}\}|<[^<>\r\n]+>|"
    r"\b(?:TODO|TBD|FIXME|CHANGEME|REPLACE_ME)\b",
    re.IGNORECASE,
)
SHELL_CONTROL_TOKENS = {"&&", "||", ";", "|", ">", ">>", "<", "2>", "2>>"}


@dataclass(frozen=True)
class Finding:
    severity: str
    code: str
    file: str
    json_path: str
    message: str

    def as_dict(self) -> Dict[str, str]:
        return {
            "severity": self.severity,
            "code": self.code,
            "file": self.file,
            "json_path": self.json_path,
            "message": self.message,
        }


@dataclass(frozen=True)
class SchemaIssue:
    code: str
    json_path: str
    message: str


@dataclass
class ContractDocument:
    path: Path
    data: Mapping[str, Any]
    schema_version: int = 1
    design_source_path: Optional[Path] = None
    design_source_sha256: Optional[str] = None
    design_source_verified: bool = False

    @property
    def contract_id(self) -> Optional[str]:
        value = self.data.get("id")
        return value if isinstance(value, str) else None


@dataclass
class GateReport:
    schema_path: Path
    project_dir: Path
    input_paths: List[str]
    contract_files: List[Path]
    documents: List[ContractDocument]
    findings: List[Finding]
    automatic_schema_dispatch: bool = False

    @property
    def passed(self) -> bool:
        return not any(item.severity == "error" for item in self.findings)

    def as_dict(self) -> Dict[str, Any]:
        contracts: List[Dict[str, Any]] = []
        for document in self.documents:
            contracts.append({
                "file": str(document.path),
                "id": document.data.get("id"),
                "version": document.data.get("version"),
                "schema_version": document.schema_version,
                "status": document.data.get("status"),
                "design_source": (
                    {
                        "path": (
                            str(document.design_source_path)
                            if document.design_source_path is not None
                            else None
                        ),
                        "sha256": document.design_source_sha256,
                        "verified": document.design_source_verified,
                    }
                    if document.schema_version == 2
                    else None
                ),
            })
        return {
            "gate": "major_feature_contract",
            "project_dir": str(self.project_dir),
            "schema": (
                "auto"
                if self.automatic_schema_dispatch
                else str(self.schema_path)
            ),
            "schemas": (
                {
                    str(version): str(path)
                    for version, path in sorted(SCHEMA_PATHS.items())
                }
                if self.automatic_schema_dispatch
                else {"override": str(self.schema_path)}
            ),
            "inputs": self.input_paths,
            "contracts_checked": len(self.contract_files),
            "contracts_parsed": len(self.documents),
            "passed": self.passed,
            "error_count": sum(
                item.severity == "error" for item in self.findings
            ),
            "contracts": contracts,
            "findings": [item.as_dict() for item in self.findings],
        }


class SchemaDefinitionError(ValueError):
    """Raised when the bundled schema uses an unsupported or invalid $ref."""


def _json_type_matches(value: Any, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return (
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and math.isfinite(value)
        )
    if expected == "null":
        return value is None
    raise SchemaDefinitionError(f"unsupported JSON Schema type: {expected}")


def _child_path(parent: str, key: str) -> str:
    if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", key):
        return f"{parent}.{key}"
    return f"{parent}[{json.dumps(key, ensure_ascii=False)}]"


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


class SchemaValidator:
    """Small JSON Schema validator covering exactly the bundled schema."""

    def __init__(self, root_schema: Mapping[str, Any]):
        self.root_schema = root_schema

    def validate(self, instance: Any) -> List[SchemaIssue]:
        issues: List[SchemaIssue] = []
        self._validate(instance, self.root_schema, "$", issues)
        return issues

    def _resolve_ref(self, reference: str) -> Mapping[str, Any]:
        if not reference.startswith("#/"):
            raise SchemaDefinitionError(
                f"only local JSON Pointer $ref values are supported: {reference}"
            )
        current: Any = self.root_schema
        for raw_part in reference[2:].split("/"):
            part = raw_part.replace("~1", "/").replace("~0", "~")
            if not isinstance(current, Mapping) or part not in current:
                raise SchemaDefinitionError(f"unresolvable schema $ref: {reference}")
            current = current[part]
        if not isinstance(current, Mapping):
            raise SchemaDefinitionError(
                f"schema $ref does not resolve to an object: {reference}"
            )
        return current

    def _validate(
        self,
        instance: Any,
        schema: Mapping[str, Any],
        path: str,
        issues: List[SchemaIssue],
    ) -> None:
        reference = schema.get("$ref")
        if isinstance(reference, str):
            self._validate(instance, self._resolve_ref(reference), path, issues)
            return

        expected_type = schema.get("type")
        if isinstance(expected_type, str) and not _json_type_matches(
            instance, expected_type
        ):
            actual = (
                "boolean" if isinstance(instance, bool)
                else "null" if instance is None
                else type(instance).__name__
            )
            issues.append(SchemaIssue(
                "schema_type",
                path,
                f"expected JSON type `{expected_type}`, got `{actual}`",
            ))
            return

        enum_values = schema.get("enum")
        if isinstance(enum_values, list) and instance not in enum_values:
            allowed = ", ".join(repr(item) for item in enum_values)
            issues.append(SchemaIssue(
                "schema_enum",
                path,
                f"value {instance!r} is not one of: {allowed}",
            ))

        if isinstance(instance, str):
            min_length = schema.get("minLength")
            if isinstance(min_length, int) and len(instance) < min_length:
                issues.append(SchemaIssue(
                    "schema_min_length",
                    path,
                    f"string length must be at least {min_length}",
                ))
            pattern = schema.get("pattern")
            if isinstance(pattern, str) and re.search(pattern, instance) is None:
                issues.append(SchemaIssue(
                    "schema_pattern",
                    path,
                    f"value does not match required pattern `{pattern}`",
                ))

        if (
            isinstance(instance, (int, float))
            and not isinstance(instance, bool)
            and math.isfinite(instance)
        ):
            minimum = schema.get("minimum")
            if isinstance(minimum, (int, float)) and instance < minimum:
                issues.append(SchemaIssue(
                    "schema_minimum",
                    path,
                    f"value must be greater than or equal to {minimum}",
                ))
            exclusive_minimum = schema.get("exclusiveMinimum")
            if (
                isinstance(exclusive_minimum, (int, float))
                and instance <= exclusive_minimum
            ):
                issues.append(SchemaIssue(
                    "schema_exclusive_minimum",
                    path,
                    f"value must be greater than {exclusive_minimum}",
                ))
            maximum = schema.get("maximum")
            if isinstance(maximum, (int, float)) and instance > maximum:
                issues.append(SchemaIssue(
                    "schema_maximum",
                    path,
                    f"value must be less than or equal to {maximum}",
                ))

        if isinstance(instance, dict):
            required = schema.get("required")
            if isinstance(required, list):
                for key in required:
                    if isinstance(key, str) and key not in instance:
                        issues.append(SchemaIssue(
                            "schema_required",
                            _child_path(path, key),
                            f"required property `{key}` is missing",
                        ))

            properties = schema.get("properties")
            properties = properties if isinstance(properties, Mapping) else {}
            for key, value in instance.items():
                property_schema = properties.get(key)
                if isinstance(property_schema, Mapping):
                    self._validate(
                        value,
                        property_schema,
                        _child_path(path, key),
                        issues,
                    )
                elif schema.get("additionalProperties") is False:
                    issues.append(SchemaIssue(
                        "schema_additional_property",
                        _child_path(path, key),
                        f"property `{key}` is not allowed",
                    ))

        if isinstance(instance, list):
            min_items = schema.get("minItems")
            if isinstance(min_items, int) and len(instance) < min_items:
                issues.append(SchemaIssue(
                    "schema_min_items",
                    path,
                    f"array must contain at least {min_items} item(s)",
                ))
            max_items = schema.get("maxItems")
            if isinstance(max_items, int) and len(instance) > max_items:
                issues.append(SchemaIssue(
                    "schema_max_items",
                    path,
                    f"array must contain at most {max_items} item(s)",
                ))
            if schema.get("uniqueItems") is True:
                seen: Set[str] = set()
                for index, item in enumerate(instance):
                    encoded = _canonical_json(item)
                    if encoded in seen:
                        issues.append(SchemaIssue(
                            "schema_unique_items",
                            f"{path}[{index}]",
                            "array items must be unique",
                        ))
                    seen.add(encoded)

            item_schema = schema.get("items")
            if isinstance(item_schema, Mapping):
                for index, item in enumerate(instance):
                    self._validate(item, item_schema, f"{path}[{index}]", issues)


def _strict_json_load(path: Path) -> Any:
    def reject_constant(value: str) -> None:
        raise ValueError(f"non-standard JSON number `{value}`")

    def reject_duplicate_keys(
        pairs: Sequence[Tuple[str, Any]],
    ) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON object key `{key}`")
            result[key] = value
        return result

    return json.loads(
        path.read_text(encoding="utf-8"),
        parse_constant=reject_constant,
        object_pairs_hook=reject_duplicate_keys,
    )


def load_json_strict(path: Path) -> Any:
    """Load standards-compliant JSON and reject duplicate object keys."""
    return _strict_json_load(path)


def _path_text(path: Path) -> str:
    return str(path.resolve())


def discover_contract_files(
    input_paths: Sequence[Path],
    *,
    excluded_paths: Optional[Iterable[Path]] = None,
) -> Tuple[List[Path], List[Finding]]:
    excluded = {
        item.resolve() for item in (excluded_paths or [])
    }
    discovered: Dict[Path, Path] = {}
    findings: List[Finding] = []

    for input_path in input_paths:
        path = input_path.expanduser()
        if not path.exists():
            findings.append(Finding(
                "error",
                "path_not_found",
                _path_text(path),
                "$",
                "input path does not exist",
            ))
            continue
        if path.is_file():
            if path.suffix.lower() != ".json":
                findings.append(Finding(
                    "error",
                    "unsupported_contract_file",
                    _path_text(path),
                    "$",
                    "contract input must be a .json file",
                ))
                continue
            if path.name.endswith(".schema.json"):
                findings.append(Finding(
                    "error",
                    "schema_is_not_contract",
                    _path_text(path),
                    "$",
                    "a JSON Schema is not a feature contract",
                ))
                continue
            resolved = path.resolve()
            if resolved not in excluded:
                discovered[resolved] = path
            continue
        if not path.is_dir():
            findings.append(Finding(
                "error",
                "unsupported_contract_path",
                _path_text(path),
                "$",
                "contract input must be a JSON file or directory",
            ))
            continue
        for candidate in path.rglob("*.json"):
            if candidate.name.endswith(".schema.json"):
                continue
            resolved = candidate.resolve()
            if resolved not in excluded:
                discovered[resolved] = candidate

    return (
        sorted(discovered.values(), key=lambda item: str(item).lower()),
        findings,
    )


def _walk_strings(value: Any, path: str = "$") -> Iterable[Tuple[str, str]]:
    if isinstance(value, str):
        yield path, value
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _walk_strings(item, f"{path}[{index}]")
    elif isinstance(value, dict):
        for key, item in value.items():
            yield from _walk_strings(item, _child_path(path, str(key)))


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _list(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def _string_list(value: Any) -> List[str]:
    return [item for item in _list(value) if isinstance(item, str)]


def _verify_design_source(
    document: ContractDocument,
    project_dir: Path,
) -> List[Finding]:
    """Bind a v2 contract to the exact, in-project design source bytes."""
    if document.schema_version != 2:
        return []

    findings: List[Finding] = []
    file_name = _path_text(document.path)

    def add(code: str, json_path: str, message: str) -> None:
        findings.append(Finding("error", code, file_name, json_path, message))

    design_source = _mapping(document.data.get("design_source"))
    raw_path = design_source.get("path")
    expected_sha256 = design_source.get("sha256")
    if not isinstance(raw_path, str) or not isinstance(expected_sha256, str):
        return findings
    if re.fullmatch(r"[0-9a-f]{64}", expected_sha256) is None:
        return findings

    posix_path = PurePosixPath(raw_path)
    if (
        not raw_path
        or posix_path.is_absolute()
        or raw_path != posix_path.as_posix()
        or any(part in {"", ".", ".."} for part in posix_path.parts)
        or ":" in posix_path.parts[0]
    ):
        add(
            "design_source_path_unsafe",
            "$.design_source.path",
            "design source path must be canonical and project-relative",
        )
        return findings

    raw_root = Path(project_dir).expanduser()
    try:
        if raw_root.is_symlink():
            raise OSError("project root is a symbolic link")
        root = raw_root.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        add(
            "design_source_project_root_invalid",
            "$.design_source.path",
            f"cannot establish a trusted project root: {error}",
        )
        return findings
    if not root.is_dir():
        add(
            "design_source_project_root_invalid",
            "$.design_source.path",
            f"project root is not a directory: {root}",
        )
        return findings

    candidate = root.joinpath(*posix_path.parts)
    current = root
    try:
        for part in posix_path.parts:
            current = current / part
            if current.is_symlink():
                add(
                    "design_source_symlink",
                    "$.design_source.path",
                    (
                        "design source paths must not cross symbolic links: "
                        f"{current}"
                    ),
                )
                return findings
        resolved = candidate.resolve(strict=False)
        resolved.relative_to(root)
    except (OSError, RuntimeError, ValueError) as error:
        add(
            "design_source_path_escape",
            "$.design_source.path",
            f"design source escapes or cannot be resolved inside the project: {error}",
        )
        return findings

    document.design_source_path = resolved
    if not resolved.exists():
        add(
            "design_source_missing",
            "$.design_source.path",
            f"declared design source does not exist: {resolved}",
        )
        return findings
    if not resolved.is_file():
        add(
            "design_source_not_file",
            "$.design_source.path",
            f"declared design source is not a regular file: {resolved}",
        )
        return findings

    digest = hashlib.sha256()
    try:
        with resolved.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        add(
            "design_source_unreadable",
            "$.design_source.path",
            f"cannot read declared design source: {error}",
        )
        return findings

    actual_sha256 = digest.hexdigest()
    document.design_source_sha256 = actual_sha256
    if actual_sha256 != expected_sha256:
        add(
            "design_source_digest_mismatch",
            "$.design_source.sha256",
            (
                "design source content drifted from the approved digest: "
                f"expected {expected_sha256}, got {actual_sha256}"
            ),
        )
        return findings
    document.design_source_verified = True
    return findings


def _semantic_findings(document: ContractDocument) -> List[Finding]:
    data = document.data
    file_name = _path_text(document.path)
    findings: List[Finding] = []

    def add(code: str, json_path: str, message: str) -> None:
        findings.append(Finding("error", code, file_name, json_path, message))

    for json_path, text in _walk_strings(data):
        match = PLACEHOLDER_RE.search(text)
        if match is not None:
            add(
                "unresolved_placeholder",
                json_path,
                f"replace scaffold placeholder `{match.group(0)}`",
            )

    acceptance = _mapping(data.get("acceptance"))
    tests = _list(acceptance.get("tests"))
    tests_by_id: Dict[str, Mapping[str, Any]] = {}
    executable_test_ids: Set[str] = set()
    required_executable_test_ids: Set[str] = set()
    required_executable = 0
    for index, raw_test in enumerate(tests):
        test = _mapping(raw_test)
        test_id = test.get("id")
        test_path = f"$.acceptance.tests[{index}]"
        if isinstance(test_id, str):
            if test_id in tests_by_id:
                add(
                    "duplicate_test_id",
                    f"{test_path}.id",
                    f"acceptance test id `{test_id}` is duplicated",
                )
            else:
                tests_by_id[test_id] = test

        command = test.get("command")
        executable = (
            isinstance(command, list)
            and bool(command)
            and all(isinstance(token, str) and token.strip() for token in command)
        )
        if executable:
            first = command[0].strip()
            if first.startswith("-"):
                add(
                    "invalid_test_command",
                    f"{test_path}.command[0]",
                    "the first argv token must name an executable",
                )
                executable = False
            for token_index, token in enumerate(command):
                if token in SHELL_CONTROL_TOKENS or "\n" in token or "\r" in token:
                    add(
                        "shell_syntax_in_test_command",
                        f"{test_path}.command[{token_index}]",
                        "commands are argv arrays; shell control syntax is forbidden",
                    )
                    executable = False
        if executable and test.get("required") is True:
            required_executable += 1
            if isinstance(test_id, str):
                required_executable_test_ids.add(test_id)
        if executable and isinstance(test_id, str):
            executable_test_ids.add(test_id)
        if (
            document.schema_version == 2
            and test.get("kind") == "gametest"
            and not (
                isinstance(test.get("test_ref"), str)
                and test["test_ref"].strip()
            )
        ):
            add(
                "gametest_ref_missing",
                f"{test_path}.test_ref",
                (
                    "v2 GameTest declarations require a stable "
                    "`fully.qualified.Class#method` test_ref"
                ),
            )

    if required_executable == 0:
        add(
            "no_required_executable_test",
            "$.acceptance.tests",
            "declare at least one required automatic test with a non-empty argv command",
        )

    manual_ids: Set[str] = set()
    for index, raw_check in enumerate(_list(acceptance.get("manual_checks"))):
        manual_id = _mapping(raw_check).get("id")
        if isinstance(manual_id, str):
            if manual_id in manual_ids:
                add(
                    "duplicate_manual_check_id",
                    f"$.acceptance.manual_checks[{index}].id",
                    f"manual check id `{manual_id}` is duplicated",
                )
            manual_ids.add(manual_id)

    if document.schema_version == 2:
        review_ids: Set[str] = set()
        for index, raw_review in enumerate(_list(data.get("review_required"))):
            review_id = _mapping(raw_review).get("id")
            if isinstance(review_id, str):
                if review_id in review_ids:
                    add(
                        "duplicate_review_required_id",
                        f"$.review_required[{index}].id",
                        f"review-required id `{review_id}` is duplicated",
                    )
                review_ids.add(review_id)

        criterion_ids: Set[str] = set()
        for index, raw_criterion in enumerate(
            _list(acceptance.get("criteria"))
        ):
            criterion = _mapping(raw_criterion)
            criterion_path = f"$.acceptance.criteria[{index}]"
            criterion_id = criterion.get("id")
            if isinstance(criterion_id, str):
                if criterion_id in criterion_ids:
                    add(
                        "duplicate_acceptance_criterion_id",
                        f"{criterion_path}.id",
                        (
                            "acceptance criterion id "
                            f"`{criterion_id}` is duplicated"
                        ),
                    )
                criterion_ids.add(criterion_id)

            referenced_test_ids = _string_list(criterion.get("test_ids"))
            known_references = [
                test_id
                for test_id in referenced_test_ids
                if test_id in tests_by_id
            ]
            for test_index, test_id in enumerate(referenced_test_ids):
                if test_id not in tests_by_id:
                    add(
                        "unknown_criterion_test_reference",
                        f"{criterion_path}.test_ids[{test_index}]",
                        (
                            f"test id `{test_id}` is not declared in "
                            "acceptance.tests"
                        ),
                    )

            if criterion.get("required") is True:
                if not referenced_test_ids:
                    add(
                        "required_criterion_without_test",
                        f"{criterion_path}.test_ids",
                        (
                            "a required acceptance criterion must reference "
                            "at least one executable test"
                        ),
                    )
                elif not any(
                    test_id in required_executable_test_ids
                    for test_id in known_references
                ):
                    add(
                        "required_criterion_without_required_executable_test",
                        f"{criterion_path}.test_ids",
                        (
                            "a required acceptance criterion must map to at "
                            "least one required test with an executable argv"
                        ),
                    )

            if criterion.get("risk") == "P0" and not any(
                test_id in executable_test_ids
                for test_id in known_references
            ):
                add(
                    "p0_criterion_without_executable_test",
                    f"{criterion_path}.test_ids",
                    "a P0 acceptance criterion cannot be manual-only",
                )

        non_draft_statuses = {
            "approved",
            "implementing",
            "verifying",
            "released",
            "deprecated",
        }
        if data.get("status") in non_draft_statuses:
            for index, raw_criterion in enumerate(
                _list(acceptance.get("criteria"))
            ):
                if _mapping(raw_criterion).get("risk") == "unclassified":
                    add(
                        "unclassified_criterion_after_draft",
                        f"$.acceptance.criteria[{index}].risk",
                        (
                            "approved or later contracts must classify every "
                            "acceptance risk"
                        ),
                    )
            if _list(data.get("review_required")):
                add(
                    "migration_review_incomplete",
                    "$.review_required",
                    (
                        "approved or later contracts cannot retain unresolved "
                        "migration review items"
                    ),
                )

    def require_test(
        test_id: Any,
        json_path: str,
        *,
        allowed_kinds: Optional[Set[str]] = None,
    ) -> None:
        if not isinstance(test_id, str):
            return
        target = tests_by_id.get(test_id)
        if target is None:
            add(
                "unknown_test_reference",
                json_path,
                f"test id `{test_id}` is not declared in acceptance.tests",
            )
            return
        kind = target.get("kind")
        if allowed_kinds is not None and kind not in allowed_kinds:
            allowed = ", ".join(sorted(allowed_kinds))
            add(
                "wrong_test_kind",
                json_path,
                f"test `{test_id}` has kind `{kind}`; expected one of: {allowed}",
            )

    authority = _mapping(data.get("server_authority"))
    owner = authority.get("owner")
    authoritative_state = _list(authority.get("authoritative_state"))
    if owner == "logical_server" and not authoritative_state:
        add(
            "missing_authoritative_state",
            "$.server_authority.authoritative_state",
            "a logical-server-owned feature must list its authoritative state",
        )
    if owner == "client_cosmetic_only":
        if authoritative_state:
            add(
                "client_feature_claims_authoritative_state",
                "$.server_authority.authoritative_state",
                "a client-cosmetic-only feature cannot own authoritative state",
            )
        if authority.get("client_input_policy") != "not_applicable":
            add(
                "client_feature_input_policy",
                "$.server_authority.client_input_policy",
                "a client-cosmetic-only feature must use `not_applicable`",
            )

    persistence = _mapping(data.get("persistence"))
    persistence_schema = _mapping(persistence.get("schema"))
    migration = _mapping(persistence.get("migration"))
    persistence_required = persistence.get("required")
    if persistence_required is True:
        if persistence.get("scope") == "none":
            add(
                "persistence_scope_missing",
                "$.persistence.scope",
                "persistent state must declare a non-`none` storage scope",
            )
        if persistence_schema.get("format") == "none":
            add(
                "persistence_format_missing",
                "$.persistence.schema.format",
                "persistent state must declare a concrete serialization format",
            )
        if not _list(persistence_schema.get("fields")):
            add(
                "persistence_fields_missing",
                "$.persistence.schema.fields",
                "persistent state must list the fields owned by its schema",
            )
        version = persistence_schema.get("version")
        if isinstance(version, int) and not isinstance(version, bool) and version < 1:
            add(
                "persistence_version_invalid",
                "$.persistence.schema.version",
                "persistent state schema versions start at 1",
            )
    elif persistence_required is False:
        if persistence.get("scope") != "none":
            add(
                "nonpersistent_scope_mismatch",
                "$.persistence.scope",
                "a non-persistent feature must use scope `none`",
            )
        if persistence_schema.get("format") != "none":
            add(
                "nonpersistent_format_mismatch",
                "$.persistence.schema.format",
                "a non-persistent feature must use schema format `none`",
            )
        if _list(persistence_schema.get("fields")):
            add(
                "nonpersistent_fields_mismatch",
                "$.persistence.schema.fields",
                "a non-persistent feature cannot declare stored fields",
            )
        if migration.get("strategy") != "none":
            add(
                "nonpersistent_migration_mismatch",
                "$.persistence.migration.strategy",
                "a non-persistent feature cannot declare a migration strategy",
            )

    migration_strategy = migration.get("strategy")
    migration_sources = _list(migration.get("supported_from"))
    migration_tests = _string_list(migration.get("test_ids"))
    if migration_strategy == "none":
        if migration_sources or migration_tests:
            add(
                "migration_none_has_work",
                "$.persistence.migration",
                "strategy `none` requires empty supported_from and test_ids",
            )
        if migration.get("fallback") != "not_applicable":
            add(
                "migration_none_fallback",
                "$.persistence.migration.fallback",
                "strategy `none` requires fallback `not_applicable`",
            )
    elif isinstance(migration_strategy, str):
        if not migration_sources:
            add(
                "migration_sources_missing",
                "$.persistence.migration.supported_from",
                "a migration strategy must list supported source versions",
            )
        if not migration_tests:
            add(
                "migration_tests_missing",
                "$.persistence.migration.test_ids",
                "a migration strategy must reference executable migration tests",
            )
        if migration.get("fallback") == "not_applicable":
            add(
                "migration_fallback_missing",
                "$.persistence.migration.fallback",
                "a migration strategy must define incompatible-data behavior",
            )
        for index, test_id in enumerate(migration_tests):
            require_test(
                test_id,
                f"$.persistence.migration.test_ids[{index}]",
                allowed_kinds={"migration"},
            )

    network = _mapping(data.get("network"))
    flows = _list(network.get("flows"))
    if network.get("required") is True and not flows:
        add(
            "network_flows_missing",
            "$.network.flows",
            "network.required is true, so at least one flow must be declared",
        )
    if network.get("required") is False and flows:
        add(
            "network_flows_unexpected",
            "$.network.flows",
            "network.required is false, so flows must be empty",
        )

    flow_ids: Set[str] = set()
    has_c2s = False
    for index, raw_flow in enumerate(flows):
        flow = _mapping(raw_flow)
        flow_path = f"$.network.flows[{index}]"
        flow_id = flow.get("id")
        if isinstance(flow_id, str):
            if flow_id in flow_ids:
                add(
                    "duplicate_network_flow_id",
                    f"{flow_path}.id",
                    f"network flow id `{flow_id}` is duplicated",
                )
            flow_ids.add(flow_id)

        direction = flow.get("direction")
        has_c2s = has_c2s or direction == "c2s"
        rate_limit = _mapping(flow.get("rate_limit"))
        strategy = rate_limit.get("strategy")
        maximum = rate_limit.get("max_per_second")
        burst = rate_limit.get("burst")
        if direction == "c2s" and strategy == "none":
            add(
                "c2s_rate_limit_missing",
                f"{flow_path}.rate_limit.strategy",
                "every C2S intent needs an explicit per-player/connection rate limit",
            )
        if strategy == "none":
            if maximum not in (0, 0.0) or burst != 0:
                add(
                    "rate_limit_none_has_budget",
                    f"{flow_path}.rate_limit",
                    "strategy `none` requires max_per_second=0 and burst=0",
                )
            if rate_limit.get("key") != "not_applicable":
                add(
                    "rate_limit_none_has_key",
                    f"{flow_path}.rate_limit.key",
                    "strategy `none` requires key `not_applicable`",
                )
        elif isinstance(strategy, str):
            if (
                not isinstance(maximum, (int, float))
                or isinstance(maximum, bool)
                or maximum <= 0
            ):
                add(
                    "rate_limit_budget_missing",
                    f"{flow_path}.rate_limit.max_per_second",
                    "an active rate limit requires max_per_second > 0",
                )
            if not isinstance(burst, int) or isinstance(burst, bool) or burst < 1:
                add(
                    "rate_limit_burst_missing",
                    f"{flow_path}.rate_limit.burst",
                    "an active rate limit requires burst >= 1",
                )
            if direction == "c2s" and rate_limit.get("key") not in {
                "player", "connection"
            }:
                add(
                    "c2s_rate_limit_key",
                    f"{flow_path}.rate_limit.key",
                    "C2S rate limits must be keyed by player or connection",
                )
        for test_index, test_id in enumerate(_string_list(flow.get("test_ids"))):
            require_test(
                test_id,
                f"{flow_path}.test_ids[{test_index}]",
                allowed_kinds={
                    "unit",
                    "gametest",
                    "integration",
                    "dedicated_server",
                    "client_smoke",
                },
            )

    if has_c2s:
        if owner != "logical_server":
            add(
                "c2s_without_server_authority",
                "$.server_authority.owner",
                "C2S intents require logical-server authority",
            )
        if authority.get("client_input_policy") != "intent_only":
            add(
                "c2s_input_policy",
                "$.server_authority.client_input_policy",
                "C2S payloads must be treated as intent, never authoritative state",
            )
        if authority.get("invalid_input_policy") == "not_applicable":
            add(
                "c2s_invalid_input_policy",
                "$.server_authority.invalid_input_policy",
                "C2S payloads need an explicit rejection policy",
            )

    client_boundary = _mapping(data.get("client_boundary"))
    require_test(
        client_boundary.get("dedicated_server_test_id"),
        "$.client_boundary.dedicated_server_test_id",
        allowed_kinds={"dedicated_server"},
    )

    assets = _mapping(data.get("assets"))
    for index, test_id in enumerate(_string_list(assets.get("validation_test_ids"))):
        require_test(
            test_id,
            f"$.assets.validation_test_ids[{index}]",
            allowed_kinds={"static", "datagen", "integration"},
        )
    if _list(assets.get("manual")) and assets.get("license_review") == "not_applicable":
        add(
            "manual_assets_without_license_review",
            "$.assets.license_review",
            "manual assets require a pending or complete provenance/license review",
        )
    if (
        data.get("status") == "released"
        and _list(assets.get("manual"))
        and assets.get("license_review") != "complete"
    ):
        add(
            "released_assets_license_incomplete",
            "$.assets.license_review",
            "released manual assets require a complete license review",
        )

    performance = _mapping(data.get("performance"))
    budget_ids: Set[str] = set()
    for index, raw_budget in enumerate(_list(performance.get("budgets"))):
        budget = _mapping(raw_budget)
        budget_path = f"$.performance.budgets[{index}]"
        budget_id = budget.get("id")
        if isinstance(budget_id, str):
            if budget_id in budget_ids:
                add(
                    "duplicate_performance_budget_id",
                    f"{budget_path}.id",
                    f"performance budget id `{budget_id}` is duplicated",
                )
            budget_ids.add(budget_id)
        require_test(
            budget.get("test_id"),
            f"{budget_path}.test_id",
            allowed_kinds={"performance"},
        )

    registries = _mapping(data.get("registries"))
    registry_ids: Set[str] = set()
    for entry_index, raw_entry in enumerate(_list(registries.get("entries"))):
        entry = _mapping(raw_entry)
        for id_index, registry_id in enumerate(_string_list(entry.get("ids"))):
            if registry_id in registry_ids:
                add(
                    "duplicate_registry_id",
                    (
                        f"$.registries.entries[{entry_index}]"
                        f".ids[{id_index}]"
                    ),
                    f"registry id `{registry_id}` is declared more than once",
                )
            registry_ids.add(registry_id)

    return findings


def _normalized_cycle(nodes: Sequence[str]) -> Tuple[str, ...]:
    """Canonicalize a directed cycle without its repeated terminal node."""
    if not nodes:
        return ()
    rotations = [
        tuple(nodes[index:] + nodes[:index])
        for index in range(len(nodes))
    ]
    return min(rotations)


def _global_findings(documents: Sequence[ContractDocument]) -> List[Finding]:
    findings: List[Finding] = []
    by_id: Dict[str, List[ContractDocument]] = {}
    for document in documents:
        contract_id = document.contract_id
        if contract_id is not None:
            by_id.setdefault(contract_id, []).append(document)

    for contract_id, matching in sorted(by_id.items()):
        if len(matching) < 2:
            continue
        files = ", ".join(_path_text(item.path) for item in matching)
        for document in matching:
            findings.append(Finding(
                "error",
                "duplicate_contract_id",
                _path_text(document.path),
                "$.id",
                f"contract id `{contract_id}` appears in: {files}",
            ))

    graph: Dict[str, List[str]] = {}
    owner_document: Dict[str, ContractDocument] = {}
    for contract_id, matching in by_id.items():
        document = matching[0]
        owner_document[contract_id] = document
        dependencies = _mapping(document.data.get("dependencies"))
        graph[contract_id] = [
            dependency
            for dependency in _string_list(dependencies.get("features"))
            if dependency in by_id
        ]

    state: Dict[str, int] = {}
    stack: List[str] = []
    positions: Dict[str, int] = {}
    seen_cycles: Set[Tuple[str, ...]] = set()

    def visit(node: str) -> None:
        state[node] = 1
        positions[node] = len(stack)
        stack.append(node)
        for dependency in graph.get(node, []):
            dependency_state = state.get(dependency, 0)
            if dependency_state == 0:
                visit(dependency)
            elif dependency_state == 1:
                cycle_nodes = stack[positions[dependency]:]
                normalized = _normalized_cycle(cycle_nodes)
                if normalized not in seen_cycles:
                    seen_cycles.add(normalized)
                    rendered = " -> ".join(cycle_nodes + [dependency])
                    findings.append(Finding(
                        "error",
                        "dependency_cycle",
                        _path_text(owner_document[node].path),
                        "$.dependencies.features",
                        f"feature dependency cycle detected: {rendered}",
                    ))
        stack.pop()
        positions.pop(node, None)
        state[node] = 2

    for contract_id in sorted(graph):
        if state.get(contract_id, 0) == 0:
            visit(contract_id)
    return findings


def run_gate(
    paths: Optional[Sequence[Path]] = None,
    *,
    require: bool = False,
    schema_path: Optional[Path] = None,
    excluded_paths: Optional[Iterable[Path]] = None,
    project_dir: Optional[Path] = None,
) -> GateReport:
    project_root = (
        Path(project_dir).expanduser()
        if project_dir is not None
        else PROJECT_DIR
    )
    report_project_root = project_root.resolve(strict=False)
    selected_paths = list(paths) if paths else [DEFAULT_CONTRACT_DIRECTORY]
    input_strings = [str(item) for item in selected_paths]
    contract_files, findings = discover_contract_files(
        selected_paths,
        excluded_paths=excluded_paths,
    )
    documents: List[ContractDocument] = []

    if require and not contract_files:
        findings.append(Finding(
            "error",
            "no_contracts",
            ", ".join(input_strings),
            "$",
            "--require was set but no feature contract JSON files were found",
        ))

    automatic_schema_dispatch = schema_path is None
    report_schema_path = DEFAULT_SCHEMA if schema_path is None else schema_path
    selected_schema_paths = (
        SCHEMA_PATHS
        if automatic_schema_dispatch
        else {0: report_schema_path}
    )
    validators: Dict[int, SchemaValidator] = {}
    for schema_version, selected_schema_path in selected_schema_paths.items():
        try:
            raw_schema = _strict_json_load(selected_schema_path)
            if not isinstance(raw_schema, Mapping):
                raise SchemaDefinitionError("schema root must be an object")
            validators[schema_version] = SchemaValidator(raw_schema)
        except (OSError, json.JSONDecodeError, ValueError) as error:
            findings.append(Finding(
                "error",
                "schema_load_error",
                _path_text(selected_schema_path),
                "$",
                str(error),
            ))
    if len(validators) != len(selected_schema_paths):
        return GateReport(
            report_schema_path,
            report_project_root,
            input_strings,
            contract_files,
            documents,
            _sorted_findings(findings),
            automatic_schema_dispatch,
        )

    for contract_file in contract_files:
        try:
            raw_data = _strict_json_load(contract_file)
        except (OSError, json.JSONDecodeError, ValueError) as error:
            findings.append(Finding(
                "error",
                "invalid_json",
                _path_text(contract_file),
                "$",
                str(error),
            ))
            continue

        document_schema_version = 1
        validator_key = 0
        selected_schema_path = report_schema_path
        if isinstance(raw_data, Mapping):
            declared_version = raw_data.get("schema_version")
            if declared_version is None:
                document_schema_version = 1
            elif (
                isinstance(declared_version, int)
                and not isinstance(declared_version, bool)
                and declared_version == 2
            ):
                document_schema_version = declared_version
            else:
                findings.append(Finding(
                    "error",
                    "unknown_schema_version",
                    _path_text(contract_file),
                    "$.schema_version",
                    (
                        "schema_version must be 2; legacy v1 contracts "
                        "identify themselves by omitting schema_version"
                    ),
                ))
                continue

        if automatic_schema_dispatch:
            validator_key = document_schema_version
            selected_schema_path = SCHEMA_PATHS[document_schema_version]

        try:
            schema_issues = validators[validator_key].validate(raw_data)
        except SchemaDefinitionError as error:
            findings.append(Finding(
                "error",
                "schema_definition_error",
                _path_text(selected_schema_path),
                "$",
                str(error),
            ))
            break
        for issue in schema_issues:
            findings.append(Finding(
                "error",
                issue.code,
                _path_text(contract_file),
                issue.json_path,
                issue.message,
            ))

        if isinstance(raw_data, Mapping):
            document = ContractDocument(
                contract_file,
                raw_data,
                document_schema_version,
            )
            documents.append(document)
            findings.extend(_semantic_findings(document))
            findings.extend(_verify_design_source(document, project_root))

    findings.extend(_global_findings(documents))
    return GateReport(
        report_schema_path,
        report_project_root,
        input_strings,
        contract_files,
        documents,
        _sorted_findings(findings),
        automatic_schema_dispatch,
    )


def _sorted_findings(findings: Iterable[Finding]) -> List[Finding]:
    return sorted(
        findings,
        key=lambda item: (
            0 if item.severity == "error" else 1,
            item.file.lower(),
            item.json_path,
            item.code,
        ),
    )


def print_human_report(report: GateReport) -> None:
    print("==================================================")
    print("Major Feature Contract Gate")
    print("==================================================")
    if report.automatic_schema_dispatch:
        rendered_schemas = ", ".join(
            f"v{version}={path}"
            for version, path in sorted(SCHEMA_PATHS.items())
        )
        print(f"Schemas: automatic ({rendered_schemas})")
    else:
        print(f"Schema override: {report.schema_path}")
    print(f"Contracts checked: {len(report.contract_files)}")
    for document in report.documents:
        print(
            "  - "
            f"{document.data.get('id', '<invalid-id>')} "
            f"(schema v{document.schema_version}, "
            f"contract v{document.data.get('version', '?')}, "
            f"{document.data.get('status', '?')})"
        )

    errors = [item for item in report.findings if item.severity == "error"]
    print(f"\nERRORS: {len(errors)}")
    for finding in errors:
        print(
            f"  [{finding.code}] {finding.file} @ {finding.json_path}\n"
            f"    {finding.message}"
        )
    print(f"\nRESULT: {'PASS' if report.passed else 'FAIL'} (Major contracts)")


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Validate NeoForge major-feature JSON contracts. Directories are "
            "searched recursively for .json files; *.schema.json is ignored."
        )
    )
    parser.add_argument(
        "paths",
        nargs="*",
        type=Path,
        help=(
            "contract JSON file or directory (default: "
            "docs/features)"
        ),
    )
    parser.add_argument(
        "--project-dir",
        type=Path,
        default=PROJECT_DIR,
        help="trusted project root used to resolve v2 design_source paths",
    )
    parser.add_argument(
        "--require",
        action="store_true",
        help="fail when no feature contract JSON files are found",
    )
    parser.add_argument(
        "--schema",
        type=Path,
        default=None,
        help=(
            "explicit JSON Schema override; by default schema_version is "
            "dispatched automatically (missing=v1, 2=v2)"
        ),
    )
    parser.add_argument(
        "--json-report",
        nargs="?",
        const="-",
        metavar="PATH",
        help=(
            "also write a JSON report; omit PATH or use '-' for JSON-only stdout"
        ),
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    args = _build_parser().parse_args(argv)
    report_target = (
        Path(args.json_report)
        if args.json_report not in (None, "-")
        else None
    )
    report = run_gate(
        args.paths,
        require=args.require,
        schema_path=args.schema,
        excluded_paths=[report_target] if report_target is not None else None,
        project_dir=args.project_dir,
    )
    payload = json.dumps(
        report.as_dict(),
        ensure_ascii=False,
        indent=2,
    ) + "\n"

    if args.json_report == "-":
        print(payload, end="")
    else:
        print_human_report(report)
        if report_target is not None:
            try:
                report_target.parent.mkdir(parents=True, exist_ok=True)
                report_target.write_text(payload, encoding="utf-8")
                print(f"JSON report: {report_target}")
            except OSError as error:
                print(
                    f"ERROR: could not write JSON report {report_target}: {error}",
                    file=sys.stderr,
                )
                return 2
    return 0 if report.passed else 1


if __name__ == "__main__":
    sys.exit(main())
