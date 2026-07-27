#!/usr/bin/env python3
"""
Create a reviewable Major Feature Contract v2 draft from a valid v1 contract.

The source is never modified and every output is created exclusively.  The
migration preserves all v1 behavior declarations, promotes tests[*].covers
into stable atomic criteria, and records every decision that still needs
human/design review instead of guessing it.
"""
from __future__ import annotations

import argparse
import copy
import difflib
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence


AGENTS_DIR = Path(__file__).resolve().parent.parent
GATES_DIR = AGENTS_DIR / "gates"
if str(GATES_DIR) not in sys.path:
    sys.path.insert(0, str(GATES_DIR))

import contract_gate


DEFAULT_V2_SCHEMA_REFERENCE = (
    "../../.agents/contracts/major-feature-v2.schema.json"
)


class MigrationError(ValueError):
    """A safe, user-correctable migration failure."""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _render_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def _criterion_id(statement: str) -> str:
    digest = hashlib.sha256(statement.encode("utf-8")).hexdigest()
    return f"criterion.cover_{digest}"


def _v2_schema_reference(source_value: Any) -> str:
    if isinstance(source_value, str) and source_value:
        if source_value.endswith("major-feature.schema.json"):
            return (
                source_value[: -len("major-feature.schema.json")]
                + "major-feature-v2.schema.json"
            )
    return DEFAULT_V2_SCHEMA_REFERENCE


def _promote_criteria(
    source: Mapping[str, Any],
) -> tuple[List[Dict[str, Any]], List[Dict[str, str]]]:
    acceptance = source.get("acceptance")
    if not isinstance(acceptance, Mapping):
        raise MigrationError("v1 acceptance must be an object")
    tests = acceptance.get("tests")
    if not isinstance(tests, list):
        raise MigrationError("v1 acceptance.tests must be an array")

    by_statement: Dict[str, Dict[str, Any]] = {}
    order: List[str] = []
    reviews: List[Dict[str, str]] = []
    for test_index, raw_test in enumerate(tests):
        if not isinstance(raw_test, Mapping):
            continue
        test_id = raw_test.get("id")
        if not isinstance(test_id, str):
            continue
        covers = raw_test.get("covers")
        if not isinstance(covers, list):
            continue
        for cover_index, statement in enumerate(covers):
            if not isinstance(statement, str):
                continue
            criterion = by_statement.get(statement)
            if criterion is None:
                criterion_id = _criterion_id(statement)
                criterion = {
                    "id": criterion_id,
                    "risk": "unclassified",
                    "required": raw_test.get("required") is True,
                    "statement": statement,
                    "observation": statement,
                    "test_ids": [test_id],
                }
                by_statement[statement] = criterion
                order.append(statement)
                reviews.append({
                    "id": f"review.risk.{len(order)}",
                    "path": f"$.acceptance.criteria[{len(order) - 1}].risk",
                    "reason": (
                        "Classify this migrated acceptance criterion as "
                        "P0, P1 or P2 from the approved design."
                    ),
                    "source_value": "unclassified",
                })
                reviews.append({
                    "id": f"review.observation.{len(order)}",
                    "path": (
                        f"$.acceptance.criteria[{len(order) - 1}]"
                        ".observation"
                    ),
                    "reason": (
                        "Replace the copied legacy covers text with a "
                        "specific, externally observable pass/fail surface."
                    ),
                    "source_value": statement,
                })
            else:
                criterion["required"] = (
                    criterion["required"]
                    or raw_test.get("required") is True
                )
                if test_id not in criterion["test_ids"]:
                    criterion["test_ids"].append(test_id)

        if raw_test.get("kind") == "gametest" and not raw_test.get("test_ref"):
            reviews.append({
                "id": f"review.gametest_ref.{test_index + 1}",
                "path": f"$.acceptance.tests[{test_index}].test_ref",
                "reason": (
                    "Add the stable fully.qualified.GameTestClass#method "
                    "symbol for this migrated GameTest."
                ),
            })

    criteria = [by_statement[statement] for statement in order]
    if not criteria:
        raise MigrationError(
            "v1 contract contains no acceptance.tests[*].covers to promote"
        )
    return criteria, reviews


def migrate_data(
    source: Mapping[str, Any],
    *,
    source_name: str,
    source_sha256: str,
) -> Dict[str, Any]:
    """Return a deterministic, schema-valid v2 review draft."""
    if "schema_version" in source:
        declared = source.get("schema_version")
        if declared == 2:
            raise MigrationError("input is already a v2 contract")
        raise MigrationError(
            f"unsupported source schema_version: {declared!r}"
        )

    criteria, criterion_reviews = _promote_criteria(source)
    original_status = source.get("status")
    reviews: List[Dict[str, str]] = [
        {
            "id": "review.design_source",
            "path": "$.design_source",
            "reason": (
                "Replace the legacy-contract anchor with the approved, "
                "versioned design document and its byte digest."
            ),
            "source_value": source_name,
        },
    ]
    if original_status != "draft":
        reviews.append({
            "id": "review.lifecycle_status",
            "path": "$.status",
            "reason": (
                "Restore a non-draft lifecycle status only after every "
                "migration review item is resolved."
            ),
            "source_value": str(original_status),
        })
    reviews.extend(criterion_reviews)

    result: Dict[str, Any] = {
        "$schema": _v2_schema_reference(source.get("$schema")),
        "schema_version": 2,
        "design_source": {
            "path": source_name,
            "revision": "legacy-v1-contract",
            "sha256": source_sha256,
        },
        "review_required": reviews,
    }
    for key, value in source.items():
        if key in {"$schema", "status", "acceptance"}:
            continue
        result[key] = copy.deepcopy(value)
    result["status"] = "draft"

    source_acceptance = source["acceptance"]
    migrated_acceptance: Dict[str, Any] = {
        "criteria": criteria,
    }
    for key, value in source_acceptance.items():
        migrated_acceptance[key] = copy.deepcopy(value)
    result["acceptance"] = migrated_acceptance

    for key, value in source.items():
        if key in {"$schema", "status", "acceptance"}:
            continue
        if result.get(key) != value:
            raise MigrationError(
                f"internal lossless-migration check failed at $.{key}"
            )
    for key, value in source_acceptance.items():
        if migrated_acceptance.get(key) != value:
            raise MigrationError(
                "internal lossless-migration check failed at "
                f"$.acceptance.{key}"
            )
    return result


def _child_path(parent: str, key: str) -> str:
    if key and all(character.isalnum() or character in "_$" for character in key):
        return f"{parent}.{key}"
    return f"{parent}[{json.dumps(key, ensure_ascii=False)}]"


def changed_paths(before: Any, after: Any, path: str = "$") -> List[str]:
    """Return deterministic semantic paths whose values were added or changed."""
    if isinstance(before, Mapping) and isinstance(after, Mapping):
        changes: List[str] = []
        for key in sorted(set(before) | set(after)):
            child = _child_path(path, str(key))
            if key not in before or key not in after:
                changes.append(child)
            else:
                changes.extend(changed_paths(before[key], after[key], child))
        return changes
    if isinstance(before, list) and isinstance(after, list):
        return [] if before == after else [path]
    return [] if before == after else [path]


def unified_diff(before: Mapping[str, Any], after: Mapping[str, Any]) -> str:
    before_lines = _render_json(before).splitlines(keepends=True)
    after_lines = _render_json(after).splitlines(keepends=True)
    return "".join(difflib.unified_diff(
        before_lines,
        after_lines,
        fromfile="v1-contract",
        tofile="v2-draft",
    ))


def _validate_source(source_path: Path) -> Mapping[str, Any]:
    try:
        raw = contract_gate.load_json_strict(source_path)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        raise MigrationError(f"cannot load source as strict JSON: {error}") from error
    if not isinstance(raw, Mapping):
        raise MigrationError("v1 contract root must be an object")
    if "schema_version" in raw:
        declared = raw.get("schema_version")
        if declared == 2:
            raise MigrationError("input is already a v2 contract")
        raise MigrationError(
            f"unsupported source schema_version: {declared!r}"
        )

    report = contract_gate.run_gate(
        [source_path],
        require=True,
        schema_path=contract_gate.DEFAULT_SCHEMA,
    )
    if not report.passed:
        codes = ", ".join(sorted({item.code for item in report.findings}))
        raise MigrationError(
            "source is not a valid v1 contract"
            + (f" ({codes})" if codes else "")
        )
    return raw


def _validate_v2_schema(data: Mapping[str, Any]) -> None:
    try:
        raw_schema = contract_gate.load_json_strict(contract_gate.V2_SCHEMA)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        raise MigrationError(f"cannot load v2 schema: {error}") from error
    if not isinstance(raw_schema, Mapping):
        raise MigrationError("v2 schema root must be an object")
    issues = contract_gate.SchemaValidator(raw_schema).validate(data)
    if issues:
        rendered = "; ".join(
            f"{issue.json_path}: {issue.message}" for issue in issues
        )
        raise MigrationError(
            f"generated v2 draft does not satisfy its schema: {rendered}"
        )


def _exclusive_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("xb") as handle:
            handle.write(data)
    except FileExistsError as error:
        raise MigrationError(f"refusing to overwrite existing file: {path}") from error


def migrate_file(
    source_path: Path,
    output_path: Path,
    *,
    diff_path: Optional[Path] = None,
    report_path: Optional[Path] = None,
) -> Dict[str, Any]:
    source_path = source_path.resolve()
    output_path = output_path.resolve()
    optional_paths = [
        path.resolve()
        for path in (diff_path, report_path)
        if path is not None
    ]
    targets = [output_path, *optional_paths]
    if source_path in targets:
        raise MigrationError("source and output paths must be different")
    if len(set(targets)) != len(targets):
        raise MigrationError("output, diff and report paths must be distinct")
    for target in targets:
        if target.exists():
            raise MigrationError(f"refusing to overwrite existing file: {target}")

    source_bytes = source_path.read_bytes()
    source_digest = _sha256(source_bytes)
    source = _validate_source(source_path)
    if source_path.read_bytes() != source_bytes:
        raise MigrationError(
            "source changed while it was being validated; retry from a "
            "stable v1 input"
        )
    migrated = migrate_data(
        source,
        source_name=source_path.name,
        source_sha256=source_digest,
    )
    _validate_v2_schema(migrated)

    output_bytes = _render_json(migrated).encode("utf-8")
    diff_text = unified_diff(source, migrated)
    report: Dict[str, Any] = {
        "tool": "major_feature_v1_to_v2",
        "source": str(source_path),
        "target": str(output_path),
        "source_schema_version": 1,
        "target_schema_version": 2,
        "source_sha256": source_digest,
        "target_sha256": _sha256(output_bytes),
        "changed_paths": changed_paths(source, migrated),
        "review_required": copy.deepcopy(migrated["review_required"]),
        "diff_sha256": _sha256(diff_text.encode("utf-8")),
    }

    _exclusive_write(output_path, output_bytes)
    if diff_path is not None:
        _exclusive_write(diff_path.resolve(), diff_text.encode("utf-8"))
    if report_path is not None:
        _exclusive_write(
            report_path.resolve(),
            _render_json(report).encode("utf-8"),
        )
    report["diff"] = diff_text
    return report


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Create a review-required v2 draft from one valid v1 Major "
            "Feature Contract without modifying or overwriting any input."
        )
    )
    parser.add_argument("source", type=Path, help="valid v1 contract")
    parser.add_argument(
        "--output",
        "-o",
        type=Path,
        required=True,
        help="new v2 draft path (must not already exist)",
    )
    parser.add_argument(
        "--diff",
        type=Path,
        help="optional unified semantic-diff output (must not exist)",
    )
    parser.add_argument(
        "--json-report",
        type=Path,
        help="optional machine-readable migration report (must not exist)",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    args = _build_parser().parse_args(argv)
    try:
        report = migrate_file(
            args.source,
            args.output,
            diff_path=args.diff,
            report_path=args.json_report,
        )
    except (MigrationError, OSError, UnicodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    print(
        "Migrated v1 -> v2 draft: "
        f"{report['source_sha256']} -> {report['target_sha256']}"
    )
    print(
        f"Review items: {len(report['review_required'])}; "
        f"changed paths: {len(report['changed_paths'])}"
    )
    if args.diff is None:
        print(report["diff"], end="")
    else:
        print(f"Diff: {args.diff}")
    if args.json_report is not None:
        print(f"JSON report: {args.json_report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
