#!/usr/bin/env python3
"""L4 NeoForge GameTest discovery and execution gate.

The gate has two deliberately separate jobs:

* discover source-backed ``@GameTest`` methods under ``src/main/java``;
* compile-bind those symbols to the official annotation descriptors;
* optionally run ``gradlew runGameTestServer`` with a frozen reporter and
  require both the aggregate markers and the exact runtime test-symbol set.

Discovery never scans this toolkit's scaffolds, generated sources, or build
outputs.  Execution uses a fresh Gradle process group and force-cleans that
group on timeout so a Minecraft child process cannot outlive the gate.

API and log markers were verified against the merged Minecraft 1.21.1 /
NeoForge 21.1.234 dependency sources (GameTest, GameTestHolder,
RegisterGameTestsEvent, GameTestHooks, and GameTestServer).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Optional, Sequence


SCHEMA_VERSION = 2
DEFAULT_TIMEOUT_SECONDS = 900.0
DEFAULT_TAIL_LINES = 30
RUNTIME_EVENT_PROTOCOL = "codex-gametest-events-v1"
MAX_RUNTIME_EVENT_BYTES = 1024 * 1024
ATTESTATION_SCOPE = (
    "process-local GameTest observation bound to retained events and the "
    "current reporter control files"
)
TAMPER_RESISTANCE = (
    "detects post-run report/event/control drift during revalidation; active "
    "tampering or event forgery by another mod in the same JVM is out of scope"
)
MOD_ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]{1,63}$")
EXCLUDED_SOURCE_PARTS = frozenset(
    {
        ".gradle",
        "build",
        "generated",
        "out",
        "scaffold",
        "scaffolds",
        "target",
    }
)
GAME_TEST_ANNOTATION = re.compile(
    r"(?<![\w$])@(?:[A-Za-z_$][\w$]*\.)*GameTest\b"
)
GAME_TEST_HOLDER_ANNOTATION = re.compile(
    r"(?<![\w$])@(?:[A-Za-z_$][\w$]*\.)*GameTestHolder\b"
)
IMPORT_DECLARATION = re.compile(
    r"(?m)^\s*import\s+(?!static\b)"
    r"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;"
)
PACKAGE_DECLARATION = re.compile(
    r"(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;"
)
TOP_LEVEL_CLASS = re.compile(
    r"\b(?:(?:public|protected|private|abstract|final|sealed|non-sealed|"
    r"strictfp|static)\s+)*class\s+([A-Za-z_$][\w$]*)\b"
)
METHOD_DECLARATION = re.compile(
    r"\s*"
    r"(?P<modifiers>(?:(?:public|protected|private|static|final|"
    r"synchronized|abstract|native|strictfp)\s+)*)"
    r"(?P<return_type>[A-Za-z_$][\w$.\[\]<>?]*)\s+"
    r"(?P<method>[A-Za-z_$][\w$]*)\s*"
    r"\((?P<parameters>[^)]*)\)"
    r"(?:\s+throws\s+[^{;]+)?\s*\{",
    re.DOTALL,
)
GAME_TEST_HELPER_PARAMETER = re.compile(
    r"^(?:final\s+)?"
    r"(?P<type>(?:[A-Za-z_$][\w$]*\.)*GameTestHelper)"
    r"\s+[A-Za-z_$][\w$]*$"
)
OFFICIAL_GAME_TEST = "net.minecraft.gametest.framework.GameTest"
OFFICIAL_GAME_TEST_HELPER = (
    "net.minecraft.gametest.framework.GameTestHelper"
)
OFFICIAL_GAME_TEST_HOLDER = (
    "net.neoforged.neoforge.gametest.GameTestHolder"
)
GAME_TEST_DESCRIPTOR = "Lnet/minecraft/gametest/framework/GameTest;"
GAME_TEST_HELPER_METHOD_DESCRIPTOR = (
    "(Lnet/minecraft/gametest/framework/GameTestHelper;)V"
)
GAME_TEST_HOLDER_DESCRIPTOR = (
    "Lnet/neoforged/neoforge/gametest/GameTestHolder;"
)
TEST_CLASS_FEATURE = "feature"
TEST_CLASS_INFRASTRUCTURE_PROBE = "infrastructure_probe"
REFERENCE_HOST_FQCN_PREFIX = "dev.modstudio.referencehost."
ANSI_ESCAPE = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1b\\))")
RUNNING_COUNT = re.compile(r"\b(\d+)\s+tests?\s+are now running\b", re.IGNORECASE)
COMPLETE_COUNT = re.compile(
    r"=+\s*(\d+)\s+GAME TESTS COMPLETE\b", re.IGNORECASE
)
REQUIRED_PASS = re.compile(
    r"\bAll\s+(\d+)\s+required tests passed\b", re.IGNORECASE
)
REQUIRED_FAILURES = re.compile(
    r"\b(\d+)\s+required tests failed\b", re.IGNORECASE
)
OPTIONAL_FAILURES = re.compile(
    r"\b(\d+)\s+optional tests failed\b", re.IGNORECASE
)
NO_TESTS_MARKERS = (
    "No test functions were given!",
    "No test functions were given",
)


@dataclass(frozen=True)
class TestOccurrence:
    path: str
    line: int
    column: int
    fqcn: Optional[str] = None
    method: Optional[str] = None
    symbol: Optional[str] = None
    holder_namespace: Optional[str] = None
    runtime_name: Optional[str] = None
    classification: str = TEST_CLASS_FEATURE
    source_sha256: Optional[str] = None
    signature_valid: bool = False
    signature_errors: tuple[str, ...] = ()
    bytecode_path: Optional[str] = None
    bytecode_sha256: Optional[str] = None
    bytecode_verified: bool = False


@dataclass
class DiscoveryResult:
    project_dir: str
    scanned_files: int
    tests: list[TestOccurrence]
    errors: list[str]

    @property
    def count(self) -> int:
        return len(self.tests)

    @property
    def feature_count(self) -> int:
        return sum(
            test.classification == TEST_CLASS_FEATURE for test in self.tests
        )

    @property
    def infrastructure_probe_count(self) -> int:
        return sum(
            test.classification == TEST_CLASS_INFRASTRUCTURE_PROBE
            for test in self.tests
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "project_dir": self.project_dir,
            "scanned_files": self.scanned_files,
            "count": self.count,
            "feature_count": self.feature_count,
            "infrastructure_probe_count": self.infrastructure_probe_count,
            "tests": [asdict(test) for test in self.tests],
            "errors": list(self.errors),
        }


@dataclass
class ExecutionResult:
    command: list[str]
    status: str
    passed: bool
    reason: str
    returncode: Optional[int]
    timed_out: bool
    termination_attempted: bool
    duration_seconds: float
    total_tests: Optional[int]
    required_passed_marker: bool
    required_failures: int
    optional_failures: int
    completion_marker: bool
    no_tests_marker: bool
    output_tail: list[str]
    launch_error: Optional[str] = None
    discovered_tests: Optional[int] = None
    running_tests: Optional[int] = None
    complete_tests: Optional[int] = None
    required_passed_tests: Optional[int] = None
    count_consistent: bool = False
    evidence_level: str = "console_aggregate"
    runtime_events_verified: bool = False
    reporter_protocol: Optional[str] = None
    reporter_jar_sha256: Optional[str] = None
    reporter_control_sha256: Optional[str] = None
    reporter_control_files: tuple[dict[str, str], ...] = ()
    event_stream_sha256: Optional[str] = None
    canonical_event_stream_sha256: Optional[str] = None
    runtime_event_nonce: Optional[str] = None
    runtime_event_raw_jsonl: Optional[str] = None
    canonical_runtime_events: tuple[dict[str, Any], ...] = ()
    executed_symbols: tuple[str, ...] = ()
    passed_symbols: tuple[str, ...] = ()
    failed_symbols: tuple[str, ...] = ()
    missing_symbols: tuple[str, ...] = ()
    unexpected_runtime_tests: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class ReporterBundle:
    jar_path: Path
    jar_sha256: str
    init_script: Path
    control_sha256: str
    control_files: tuple[dict[str, str], ...]


@dataclass(frozen=True)
class RuntimeEventEvidence:
    passed: bool
    reason: str
    protocol: Optional[str]
    stream_sha256: Optional[str]
    executed_symbols: tuple[str, ...] = ()
    passed_symbols: tuple[str, ...] = ()
    failed_symbols: tuple[str, ...] = ()
    missing_symbols: tuple[str, ...] = ()
    unexpected_runtime_tests: tuple[str, ...] = ()
    nonce: Optional[str] = None
    raw_jsonl: Optional[str] = None
    canonical_events: tuple[dict[str, Any], ...] = ()
    canonical_stream_sha256: Optional[str] = None


class ClassFileError(ValueError):
    """Raised when a compiled GameTest class cannot be proven structurally."""


def _mask_java_noncode(source: str) -> str:
    """Replace Java comments and literals with spaces while preserving offsets."""
    output: list[str] = []
    index = 0
    state = "code"
    length = len(source)

    def blank(text: str) -> str:
        return "".join(char if char in "\r\n" else " " for char in text)

    while index < length:
        if state == "code":
            if source.startswith("//", index):
                output.append("  ")
                index += 2
                state = "line_comment"
            elif source.startswith("/*", index):
                output.append("  ")
                index += 2
                state = "block_comment"
            elif source.startswith('"""', index):
                output.append("   ")
                index += 3
                state = "text_block"
            elif source[index] == '"':
                output.append(" ")
                index += 1
                state = "string"
            elif source[index] == "'":
                output.append(" ")
                index += 1
                state = "char"
            else:
                output.append(source[index])
                index += 1
            continue

        if state == "line_comment":
            char = source[index]
            output.append(char if char in "\r\n" else " ")
            index += 1
            if char in "\r\n":
                state = "code"
            continue

        if state == "block_comment":
            if source.startswith("*/", index):
                output.append("  ")
                index += 2
                state = "code"
            else:
                output.append(
                    source[index] if source[index] in "\r\n" else " "
                )
                index += 1
            continue

        if state in {"string", "char"}:
            quote = '"' if state == "string" else "'"
            if source[index] == "\\" and index + 1 < length:
                output.append(blank(source[index : index + 2]))
                index += 2
            else:
                char = source[index]
                output.append(char if char in "\r\n" else " ")
                index += 1
                if char == quote:
                    state = "code"
            continue

        # Java text block.
        if source.startswith('"""', index):
            output.append("   ")
            index += 3
            state = "code"
        elif source[index] == "\\" and index + 1 < length:
            output.append(blank(source[index : index + 2]))
            index += 2
        else:
            output.append(source[index] if source[index] in "\r\n" else " ")
            index += 1

    return "".join(output)


def _is_excluded_source(path: Path, src_root: Path) -> bool:
    try:
        relative = path.relative_to(src_root)
    except ValueError:
        return True
    return any(part.lower() in EXCLUDED_SOURCE_PARTS for part in relative.parts)


def _source_files(project_dir: Path) -> Iterable[Path]:
    # NeoForge's gameTestServer run uses sourceSets.main in this starter.  A
    # wider ``src/**/*.java`` scan can claim tests from src/test/java that the
    # server never registers, so the evidence-bearing subset is deliberately
    # pinned to the real runtime source set.
    src_root = project_dir / "src" / "main" / "java"
    if not src_root.is_dir():
        return ()
    return (
        path
        for path in sorted(
            src_root.rglob("*.java"),
            key=lambda candidate: candidate.as_posix().lower(),
        )
        if path.is_file() and not _is_excluded_source(path, src_root)
    )


@dataclass(frozen=True)
class _ClassContext:
    name: str
    start: int
    body_start: int
    body_end: int
    holder_namespace: Optional[str]
    prefix_template_false: bool


def _brace_depth(masked: str, end: int) -> int:
    return masked.count("{", 0, end) - masked.count("}", 0, end)


def _matching_brace(masked: str, opening: int) -> Optional[int]:
    depth = 0
    for index in range(opening, len(masked)):
        char = masked[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    return None


def _annotation_end(masked: str, token_end: int) -> Optional[int]:
    index = token_end
    while index < len(masked) and masked[index].isspace():
        index += 1
    if index >= len(masked) or masked[index] != "(":
        return index

    depth = 0
    for cursor in range(index, len(masked)):
        char = masked[cursor]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return cursor + 1
    return None


def _imports(masked: str) -> frozenset[str]:
    return frozenset(
        match.group(1)
        for match in IMPORT_DECLARATION.finditer(masked)
        if _brace_depth(masked, match.start()) == 0
    )


def _resolves_to_official_type(
    spelling: str,
    official_fqcn: str,
    imports: frozenset[str],
) -> bool:
    if spelling == official_fqcn:
        return True
    simple_name = official_fqcn.rsplit(".", 1)[1]
    # Wildcard imports are intentionally not accepted for evidence-bearing
    # discovery.  A same-package lookalike type can otherwise shadow the
    # official annotation/helper while the lexical scanner reports success.
    return spelling == simple_name and official_fqcn in imports


def _read_mod_id(project_dir: Path) -> tuple[Optional[str], Optional[str]]:
    properties_path = project_dir / "gradle.properties"
    try:
        text = properties_path.read_text(encoding="utf-8", errors="strict")
    except (OSError, UnicodeError) as error:
        return None, f"cannot read gradle.properties mod_id: {error}"

    values: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        match = re.match(r"^mod_id\s*[:=]\s*(.*?)\s*$", line)
        if match:
            values.append(match.group(1))
    if len(values) != 1:
        return None, (
            "gradle.properties must declare mod_id exactly once for "
            "GameTest namespace binding"
        )
    mod_id = values[0]
    if not MOD_ID_PATTERN.fullmatch(mod_id):
        return None, f"invalid gradle.properties mod_id: {mod_id!r}"
    return mod_id, None


def _holder_annotations(
    source: str,
    masked: str,
    imports: frozenset[str],
) -> list[tuple[int, int, Optional[str]]]:
    holders: list[tuple[int, int, Optional[str]]] = []
    for match in GAME_TEST_HOLDER_ANNOTATION.finditer(masked):
        if _brace_depth(masked, match.start()) != 0:
            continue
        spelling = match.group(0)[1:]
        if not _resolves_to_official_type(
            spelling, OFFICIAL_GAME_TEST_HOLDER, imports
        ):
            continue
        end = _annotation_end(masked, match.end())
        if end is None:
            holders.append((match.start(), match.end(), None))
            continue
        annotation = source[match.start() : end]
        namespace_match = re.search(
            r'\(\s*(?:value\s*=\s*)?"([a-z0-9_.-]+)"\s*\)',
            annotation,
            re.DOTALL,
        )
        holders.append(
            (
                match.start(),
                end,
                namespace_match.group(1) if namespace_match else None,
            )
        )
    return holders


def _top_level_classes(
    source: str,
    masked: str,
    imports: frozenset[str],
) -> list[_ClassContext]:
    holders = _holder_annotations(source, masked, imports)
    classes: list[_ClassContext] = []
    previous_class_end = 0

    for match in TOP_LEVEL_CLASS.finditer(masked):
        if _brace_depth(masked, match.start()) != 0:
            continue
        body_start = masked.find("{", match.end())
        if body_start < 0 or _brace_depth(masked, body_start) != 0:
            continue
        body_end = _matching_brace(masked, body_start)
        if body_end is None:
            continue

        namespace: Optional[str] = None
        eligible = [
            holder
            for holder in holders
            if previous_class_end <= holder[0] < match.start()
            and not any(
                token in masked[holder[1] : match.start()]
                for token in (";", "{", "}")
            )
        ]
        if eligible:
            namespace = eligible[-1][2]
        class_header = masked[previous_class_end : match.start()]
        prefix_template_false = re.search(
            r"@(?:[A-Za-z_$][\w$]*\.)*PrefixGameTestTemplate"
            r"\s*\(\s*(?:value\s*=\s*)?false\s*\)",
            class_header,
        ) is not None
        classes.append(
            _ClassContext(
                name=match.group(1),
                start=match.start(),
                body_start=body_start,
                body_end=body_end,
                holder_namespace=namespace,
                prefix_template_false=prefix_template_false,
            )
        )
        previous_class_end = body_end + 1

    return classes


def _containing_class(
    classes: Sequence[_ClassContext], position: int
) -> Optional[_ClassContext]:
    return next(
        (
            context
            for context in classes
            if context.body_start < position < context.body_end
        ),
        None,
    )


def _inspect_occurrence(
    *,
    relative: str,
    source: str,
    masked: str,
    source_sha256: str,
    package_name: Optional[str],
    classes: Sequence[_ClassContext],
    imports: frozenset[str],
    expected_mod_id: Optional[str],
    match: re.Match[str],
) -> TestOccurrence:
    line = masked.count("\n", 0, match.start()) + 1
    line_start = masked.rfind("\n", 0, match.start()) + 1
    column = match.start() - line_start + 1
    errors: list[str] = []
    context = _containing_class(classes, match.start())
    annotation_end = _annotation_end(masked, match.end())
    annotation_spelling = match.group(0)[1:]
    annotation_text = (
        source[match.start() : annotation_end]
        if annotation_end is not None
        else ""
    )

    method_match = (
        METHOD_DECLARATION.match(masked, annotation_end)
        if annotation_end is not None
        else None
    )
    method = method_match.group("method") if method_match else None

    if not _resolves_to_official_type(
        annotation_spelling, OFFICIAL_GAME_TEST, imports
    ):
        errors.append(
            "@GameTest must resolve to "
            f"{OFFICIAL_GAME_TEST} through an explicit import or FQCN"
        )
    if context is None:
        errors.append("@GameTest must be declared directly in a top-level class")
    elif _brace_depth(masked, match.start()) != 1:
        errors.append("nested or block-local @GameTest is outside the strict subset")
    if package_name is None:
        errors.append("a named Java package is required")
    if context is not None and context.holder_namespace is None:
        errors.append(
            'top-level class must declare @GameTestHolder("namespace")'
        )
    if annotation_end is None:
        errors.append("@GameTest annotation has unbalanced arguments")
    elif re.search(
        r"\brequired\s*=\s*false\b",
        masked[match.start() : annotation_end],
    ):
        errors.append("optional GameTests are outside the aggregate-set subset")

    namespace_override = re.search(
        r'\btemplateNamespace\s*=\s*"([a-z0-9_.-]+)"',
        annotation_text,
    )
    effective_namespace = (
        namespace_override.group(1)
        if namespace_override is not None
        else context.holder_namespace
        if context is not None
        else None
    )
    if (
        expected_mod_id is not None
        and effective_namespace is not None
        and effective_namespace != expected_mod_id
    ):
        errors.append(
            "GameTest registration namespace "
            f"`{effective_namespace}` does not match gradle.properties "
            f"mod_id `{expected_mod_id}`"
        )

    if context is not None and (
        context.prefix_template_false
        or re.search(
            r"@(?:[A-Za-z_$][\w$]*\.)*PrefixGameTestTemplate"
            r"\s*\(\s*(?:value\s*=\s*)?false\s*\)",
            masked[context.body_start : context.body_end],
        )
    ):
        errors.append(
            "@PrefixGameTestTemplate(false) is outside the runtime-symbol "
            "evidence subset"
        )

    if method_match is None:
        errors.append(
            "@GameTest must be followed by a standard Java method declaration"
        )
    else:
        modifiers = set(method_match.group("modifiers").split())
        if "public" not in modifiers:
            errors.append("GameTest method must be public")
        if "static" not in modifiers:
            errors.append("GameTest method must be static")
        if method_match.group("return_type") != "void":
            errors.append("GameTest method must return void")
        parameter = re.sub(
            r"\s+", " ", method_match.group("parameters").strip()
        )
        parameter_match = GAME_TEST_HELPER_PARAMETER.fullmatch(parameter)
        if parameter_match is None:
            errors.append(
                "GameTest method must take exactly one GameTestHelper parameter"
            )
        elif not _resolves_to_official_type(
            parameter_match.group("type"),
            OFFICIAL_GAME_TEST_HELPER,
            imports,
        ):
            errors.append(
                "GameTestHelper must resolve to "
                f"{OFFICIAL_GAME_TEST_HELPER} through an explicit import "
                "or FQCN"
            )

    fqcn = (
        f"{package_name}.{context.name}"
        if package_name is not None and context is not None
        else None
    )
    symbol = f"{fqcn}#{method}" if fqcn and method else None
    runtime_name = (
        f"{context.name.lower()}.{method.lower()}"
        if context is not None and method is not None
        else None
    )
    classification = (
        TEST_CLASS_INFRASTRUCTURE_PROBE
        if fqcn is not None and fqcn.startswith(REFERENCE_HOST_FQCN_PREFIX)
        else TEST_CLASS_FEATURE
    )
    return TestOccurrence(
        path=relative,
        line=line,
        column=column,
        fqcn=fqcn,
        method=method,
        symbol=symbol,
        holder_namespace=effective_namespace,
        runtime_name=runtime_name,
        classification=classification,
        source_sha256=source_sha256,
        signature_valid=not errors,
        signature_errors=tuple(errors),
    )


def discover_gametests(project_dir: Path | str) -> DiscoveryResult:
    """Discover the strict, source-backed GameTest subset under main Java."""
    project = Path(project_dir).resolve()
    occurrences: list[TestOccurrence] = []
    errors: list[str] = []
    scanned_files = 0
    expected_mod_id, mod_id_error = _read_mod_id(project)
    if mod_id_error is not None:
        errors.append(mod_id_error)

    for java_file in _source_files(project):
        scanned_files += 1
        relative = java_file.relative_to(project).as_posix()
        try:
            source_bytes = java_file.read_bytes()
            source = source_bytes.decode("utf-8", errors="strict")
        except (OSError, UnicodeError) as exc:
            errors.append(f"{relative}: {exc}")
            continue

        masked = _mask_java_noncode(source)
        imports = _imports(masked)
        package_match = PACKAGE_DECLARATION.search(masked)
        package_name = package_match.group(1) if package_match else None
        classes = _top_level_classes(source, masked, imports)
        source_sha256 = hashlib.sha256(source_bytes).hexdigest()
        for match in GAME_TEST_ANNOTATION.finditer(masked):
            occurrence = _inspect_occurrence(
                relative=relative,
                source=source,
                masked=masked,
                source_sha256=source_sha256,
                package_name=package_name,
                classes=classes,
                imports=imports,
                expected_mod_id=expected_mod_id,
                match=match,
            )
            occurrences.append(occurrence)
            for error in occurrence.signature_errors:
                errors.append(
                    f"{relative}:{occurrence.line}:{occurrence.column}: {error}"
                )

    by_symbol: dict[str, list[int]] = {}
    for index, occurrence in enumerate(occurrences):
        if occurrence.symbol is not None:
            by_symbol.setdefault(occurrence.symbol, []).append(index)
    for symbol, indexes in by_symbol.items():
        if len(indexes) < 2:
            continue
        duplicate_error = f"duplicate GameTest symbol: {symbol}"
        errors.append(duplicate_error)
        for index in indexes:
            occurrence = occurrences[index]
            occurrences[index] = replace(
                occurrence,
                signature_valid=False,
                signature_errors=occurrence.signature_errors
                + (duplicate_error,),
            )

    by_runtime_name: dict[str, list[int]] = {}
    for index, occurrence in enumerate(occurrences):
        if occurrence.runtime_name is not None:
            by_runtime_name.setdefault(occurrence.runtime_name, []).append(index)
    for runtime_name, indexes in by_runtime_name.items():
        if len(indexes) < 2:
            continue
        collision_error = (
            "duplicate lower-cased runtime GameTest name: " + runtime_name
        )
        errors.append(collision_error)
        for index in indexes:
            occurrence = occurrences[index]
            occurrences[index] = replace(
                occurrence,
                signature_valid=False,
                signature_errors=occurrence.signature_errors
                + (collision_error,),
            )

    return DiscoveryResult(
        project_dir=str(project),
        scanned_files=scanned_files,
        tests=occurrences,
        errors=errors,
    )


class _ClassReader:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def _take(self, size: int) -> bytes:
        end = self.offset + size
        if size < 0 or end > len(self.data):
            raise ClassFileError("truncated class file")
        value = self.data[self.offset : end]
        self.offset = end
        return value

    def u1(self) -> int:
        return self._take(1)[0]

    def u2(self) -> int:
        return struct.unpack(">H", self._take(2))[0]

    def u4(self) -> int:
        return struct.unpack(">I", self._take(4))[0]


def _cp_utf8(constant_pool: Sequence[Any], index: int) -> str:
    if not 0 < index < len(constant_pool):
        raise ClassFileError(f"invalid constant-pool index: {index}")
    value = constant_pool[index]
    if not isinstance(value, str):
        raise ClassFileError(
            f"constant-pool entry {index} is not a UTF-8 string"
        )
    return value


def _skip_annotation_value(
    reader: _ClassReader, constant_pool: Sequence[Any]
) -> None:
    tag = chr(reader.u1())
    if tag in "BCDFIJSZs":
        reader.u2()
    elif tag == "e":
        reader.u2()
        reader.u2()
    elif tag == "c":
        reader.u2()
    elif tag == "@":
        _read_annotation(reader, constant_pool)
    elif tag == "[":
        for _ in range(reader.u2()):
            _skip_annotation_value(reader, constant_pool)
    else:
        raise ClassFileError(f"unsupported annotation element tag: {tag!r}")


def _read_annotation(
    reader: _ClassReader, constant_pool: Sequence[Any]
) -> str:
    descriptor = _cp_utf8(constant_pool, reader.u2())
    for _ in range(reader.u2()):
        reader.u2()  # element_name_index
        _skip_annotation_value(reader, constant_pool)
    return descriptor


def _annotation_descriptors(
    payload: bytes, constant_pool: Sequence[Any]
) -> frozenset[str]:
    reader = _ClassReader(payload)
    descriptors = {
        _read_annotation(reader, constant_pool)
        for _ in range(reader.u2())
    }
    if reader.offset != len(payload):
        raise ClassFileError("annotation attribute contains trailing bytes")
    return frozenset(descriptors)


def _read_attributes(
    reader: _ClassReader, constant_pool: Sequence[Any]
) -> list[tuple[str, bytes]]:
    attributes: list[tuple[str, bytes]] = []
    for _ in range(reader.u2()):
        name = _cp_utf8(constant_pool, reader.u2())
        length = reader.u4()
        attributes.append((name, reader._take(length)))
    return attributes


def _parse_class_file(
    data: bytes,
) -> tuple[frozenset[str], list[dict[str, Any]]]:
    reader = _ClassReader(data)
    if reader.u4() != 0xCAFEBABE:
        raise ClassFileError("invalid class-file magic")
    reader.u2()  # minor
    reader.u2()  # major
    constant_pool: list[Any] = [None] * reader.u2()
    index = 1
    while index < len(constant_pool):
        tag = reader.u1()
        if tag == 1:
            constant_pool[index] = reader._take(reader.u2()).decode(
                "utf-8", errors="replace"
            )
        elif tag in {3, 4}:
            reader._take(4)
        elif tag in {5, 6}:
            reader._take(8)
            index += 1
        elif tag in {7, 8, 16, 19, 20}:
            reader.u2()
        elif tag in {9, 10, 11, 12, 17, 18}:
            reader.u2()
            reader.u2()
        elif tag == 15:
            reader.u1()
            reader.u2()
        else:
            raise ClassFileError(f"unsupported constant-pool tag: {tag}")
        index += 1

    reader.u2()  # class access
    reader.u2()  # this class
    reader.u2()  # super class
    for _ in range(reader.u2()):
        reader.u2()

    for _ in range(reader.u2()):  # fields
        reader.u2()
        reader.u2()
        reader.u2()
        _read_attributes(reader, constant_pool)

    methods: list[dict[str, Any]] = []
    for _ in range(reader.u2()):
        access = reader.u2()
        name = _cp_utf8(constant_pool, reader.u2())
        descriptor = _cp_utf8(constant_pool, reader.u2())
        annotations: set[str] = set()
        for attribute_name, payload in _read_attributes(
            reader, constant_pool
        ):
            if attribute_name == "RuntimeVisibleAnnotations":
                annotations.update(
                    _annotation_descriptors(payload, constant_pool)
                )
        methods.append(
            {
                "access": access,
                "name": name,
                "descriptor": descriptor,
                "annotations": frozenset(annotations),
            }
        )

    class_annotations: set[str] = set()
    for attribute_name, payload in _read_attributes(reader, constant_pool):
        if attribute_name == "RuntimeVisibleAnnotations":
            class_annotations.update(
                _annotation_descriptors(payload, constant_pool)
            )
    if reader.offset != len(data):
        raise ClassFileError("class file contains trailing bytes")
    return frozenset(class_annotations), methods


def verify_compiled_gametests(
    project_dir: Path | str, discovery: DiscoveryResult
) -> DiscoveryResult:
    """Bind source discovery to the exact compiled annotation descriptors."""
    project = Path(project_dir).resolve()
    classes_root = (project / "build" / "classes" / "java" / "main").resolve()
    occurrences: list[TestOccurrence] = []
    errors = list(discovery.errors)

    for occurrence in discovery.tests:
        bytecode_errors: list[str] = []
        bytecode_path: Optional[str] = None
        bytecode_sha256: Optional[str] = None
        if occurrence.fqcn is None or occurrence.method is None:
            bytecode_errors.append(
                "unresolved source symbol cannot be bound to bytecode"
            )
        else:
            class_file = (
                classes_root
                / (occurrence.fqcn.replace(".", "/") + ".class")
            ).resolve()
            try:
                class_file.relative_to(classes_root)
            except ValueError:
                bytecode_errors.append("compiled class path escapes build root")
            else:
                try:
                    class_bytes = class_file.read_bytes()
                    bytecode_path = class_file.relative_to(project).as_posix()
                    bytecode_sha256 = hashlib.sha256(class_bytes).hexdigest()
                    class_annotations, methods = _parse_class_file(class_bytes)
                except (OSError, ClassFileError) as error:
                    bytecode_errors.append(
                        f"cannot inspect compiled class: {error}"
                    )
                else:
                    if (
                        GAME_TEST_HOLDER_DESCRIPTOR
                        not in class_annotations
                    ):
                        bytecode_errors.append(
                            "compiled class lacks the official "
                            "NeoForge @GameTestHolder descriptor"
                        )
                    matching = [
                        method
                        for method in methods
                        if method["name"] == occurrence.method
                        and method["descriptor"]
                        == GAME_TEST_HELPER_METHOD_DESCRIPTOR
                        and GAME_TEST_DESCRIPTOR in method["annotations"]
                    ]
                    if len(matching) != 1:
                        bytecode_errors.append(
                            "compiled method is not uniquely annotated with "
                            "the official @GameTest descriptor and "
                            "GameTestHelper signature"
                        )
                    elif matching[0]["access"] & 0x0001 == 0 or (
                        matching[0]["access"] & 0x0008 == 0
                    ):
                        bytecode_errors.append(
                            "compiled GameTest method is not public static"
                        )

        combined_errors = occurrence.signature_errors + tuple(
            bytecode_errors
        )
        updated = replace(
            occurrence,
            signature_valid=not combined_errors,
            signature_errors=combined_errors,
            bytecode_path=bytecode_path,
            bytecode_sha256=bytecode_sha256,
            bytecode_verified=not bytecode_errors,
        )
        occurrences.append(updated)
        for error in bytecode_errors:
            errors.append(
                f"{occurrence.path}:{occurrence.line}:{occurrence.column}: "
                f"{error}"
            )

    return DiscoveryResult(
        project_dir=discovery.project_dir,
        scanned_files=discovery.scanned_files,
        tests=occurrences,
        errors=errors,
    )


def gradle_command(project_dir: Path | str) -> list[str]:
    project = Path(project_dir).resolve()
    wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [
        str(wrapper),
        "runGameTestServer",
        "--no-daemon",
        "--console=plain",
    ]


def terminate_process_tree(process: subprocess.Popen[Any]) -> None:
    """Force-stop the process group created for a Gradle GameTest run."""
    if os.name == "nt":
        try:
            result = subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
                timeout=15,
            )
            if result.returncode == 0:
                return
        except (OSError, subprocess.TimeoutExpired):
            pass
    else:
        try:
            # start_new_session=True makes the wrapper PID the process-group ID.
            # Using the known ID still works if the group leader has just exited.
            os.killpg(process.pid, signal.SIGKILL)
            return
        except (ProcessLookupError, PermissionError, OSError):
            pass

    if process.poll() is None:
        try:
            process.kill()
        except OSError:
            pass


def _last_match_int(pattern: re.Pattern[str], output: str) -> Optional[int]:
    matches = list(pattern.finditer(output))
    return int(matches[-1].group(1)) if matches else None


def parse_gametest_output(
    output: str,
    *,
    command: Sequence[str],
    returncode: Optional[int],
    timed_out: bool,
    termination_attempted: bool,
    duration_seconds: float,
    tail_lines: int = DEFAULT_TAIL_LINES,
    launch_error: Optional[str] = None,
    discovered_tests: Optional[int] = None,
) -> ExecutionResult:
    """Parse the stable markers emitted by Minecraft 1.21.1 GameTestServer."""
    clean_output = ANSI_ESCAPE.sub("", output).replace("\r\n", "\n")
    nonempty_lines = [
        line.rstrip() for line in clean_output.splitlines() if line.strip()
    ]
    tail = nonempty_lines[-tail_lines:]

    running_count = _last_match_int(RUNNING_COUNT, clean_output)
    complete_count = _last_match_int(COMPLETE_COUNT, clean_output)
    required_pass_count = _last_match_int(REQUIRED_PASS, clean_output)
    required_failures = _last_match_int(REQUIRED_FAILURES, clean_output) or 0
    optional_failures = _last_match_int(OPTIONAL_FAILURES, clean_output) or 0
    completion_marker = complete_count is not None
    required_passed_marker = required_pass_count is not None
    no_tests_marker = any(marker in clean_output for marker in NO_TESTS_MARKERS)
    total_tests = complete_count if complete_count is not None else running_count
    observed_counts = [
        running_count,
        complete_count,
        required_pass_count,
    ]
    if discovered_tests is not None:
        observed_counts.insert(0, discovered_tests)
    count_consistent = (
        all(count is not None for count in observed_counts)
        and len(set(observed_counts)) == 1
        and bool(observed_counts)
        and observed_counts[0] > 0
    )

    if launch_error is not None:
        status = "tool_error"
        passed = False
        reason = f"could not launch Gradle wrapper: {launch_error}"
    elif timed_out:
        status = "timed_out"
        passed = False
        reason = "GameTest run exceeded its timeout; process tree was terminated"
    elif no_tests_marker:
        status = "failed"
        passed = False
        reason = "GameTestServer received no registered test functions"
    elif required_failures:
        status = "failed"
        passed = False
        reason = f"{required_failures} required GameTest(s) failed"
    elif optional_failures:
        # Vanilla exits zero for optional failures.  A quality gate named
        # "all green" must not silently accept them.
        status = "failed"
        passed = False
        reason = f"{optional_failures} optional GameTest(s) failed"
    elif returncode not in (0,):
        status = "failed"
        passed = False
        reason = f"Gradle exited with code {returncode}"
    elif not (
        running_count is not None
        and completion_marker
        and required_passed_marker
    ):
        status = "unparsed"
        passed = False
        reason = (
            "Gradle exited successfully but the official GameTest running, "
            "completion, and pass markers were not all observed"
        )
    elif not count_consistent:
        status = "failed"
        passed = False
        reason = (
            "GameTest evidence counts disagree "
            f"(discovered={discovered_tests!r}, running={running_count!r}, "
            f"complete={complete_count!r}, passed={required_pass_count!r})"
        )
    else:
        status = "passed"
        passed = True
        reason = (
            f"all {total_tests if total_tests is not None else '?'} "
            "GameTest(s) completed with no failures"
        )

    return ExecutionResult(
        command=list(command),
        status=status,
        passed=passed,
        reason=reason,
        returncode=returncode,
        timed_out=timed_out,
        termination_attempted=termination_attempted,
        duration_seconds=round(duration_seconds, 3),
        total_tests=total_tests,
        discovered_tests=discovered_tests,
        running_tests=running_count,
        complete_tests=complete_count,
        required_passed_tests=required_pass_count,
        count_consistent=count_consistent,
        evidence_level="console_aggregate",
        required_passed_marker=required_passed_marker,
        required_failures=required_failures,
        optional_failures=optional_failures,
        completion_marker=completion_marker,
        no_tests_marker=no_tests_marker,
        output_tail=tail,
        launch_error=launch_error,
    )


def bind_discovery_count(
    execution: ExecutionResult, discovered_tests: int
) -> ExecutionResult:
    """Bind runtime aggregate markers to the exact discovered source set."""
    counts = (
        discovered_tests,
        execution.running_tests,
        execution.complete_tests,
        execution.required_passed_tests,
    )
    consistent = all(count is not None for count in counts) and len(
        set(counts)
    ) == 1 and discovered_tests > 0
    updated = replace(
        execution,
        discovered_tests=discovered_tests,
        count_consistent=consistent,
    )
    if execution.passed and not consistent:
        return replace(
            updated,
            status="failed",
            passed=False,
            reason=(
                "GameTest evidence counts disagree "
                f"(discovered={discovered_tests!r}, "
                f"running={execution.running_tests!r}, "
                f"complete={execution.complete_tests!r}, "
                f"passed={execution.required_passed_tests!r})"
            ),
        )
    return updated


def _run_bounded_command(
    command: Sequence[str],
    *,
    cwd: Path,
    timeout_seconds: float,
    popen_factory: Optional[
        Callable[..., subprocess.Popen[Any]]
    ] = None,
) -> tuple[Optional[int], str, bool, Optional[str]]:
    popen_options: dict[str, Any] = {}
    if os.name == "nt":
        popen_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        popen_options["start_new_session"] = True
    launch_process = popen_factory or subprocess.Popen
    returncode: Optional[int] = None
    timed_out = False
    launch_error: Optional[str] = None
    output = ""

    with tempfile.TemporaryFile(
        mode="w+t", encoding="utf-8", errors="replace"
    ) as log_file:
        try:
            process = launch_process(
                list(command),
                cwd=str(cwd),
                stdin=subprocess.DEVNULL,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                **popen_options,
            )
        except OSError as error:
            process = None
            launch_error = str(error)

        if process is not None:
            try:
                process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                timed_out = True
                terminate_process_tree(process)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    try:
                        process.kill()
                    except OSError:
                        pass
            except BaseException:
                terminate_process_tree(process)
                raise
            returncode = process.returncode

        log_file.flush()
        log_file.seek(0)
        output = log_file.read()
    return returncode, output, timed_out, launch_error


def _tool_error_execution(
    command: Sequence[str],
    reason: str,
    *,
    output: str = "",
    discovered_tests: Optional[int] = None,
) -> ExecutionResult:
    tail = [line for line in output.splitlines() if line.strip()][
        -DEFAULT_TAIL_LINES:
    ]
    return ExecutionResult(
        command=list(command),
        status="tool_error",
        passed=False,
        reason=reason,
        returncode=None,
        timed_out=False,
        termination_attempted=False,
        duration_seconds=0.0,
        total_tests=None,
        required_passed_marker=False,
        required_failures=0,
        optional_failures=0,
        completion_marker=False,
        no_tests_marker=False,
        output_tail=tail,
        launch_error=reason,
        discovered_tests=discovered_tests,
        evidence_level="untrusted",
    )


def _reporter_sources(project: Path) -> tuple[Path, Path, Path]:
    root = (
        project
        / ".agents"
        / "gates"
        / "runtime"
        / "gametest-reporter"
    ).resolve()
    source_candidate = (
        root
        / "src"
        / "dev"
        / "codex"
        / "gametest"
        / "TrustedGameTestReporterMod.java"
    )
    candidates = (
        source_candidate,
        root / "META-INF" / "neoforge.mods.toml",
        root / "inject.init.gradle",
    )
    resolved: list[Path] = []
    for path in candidates:
        if path.is_symlink():
            raise OSError(f"reporter control file must not be a symlink: {path}")
        control_path = path.resolve()
        try:
            control_path.relative_to(root)
        except ValueError as error:
            raise OSError("reporter control path escaped its root") from error
        if not control_path.is_file():
            raise OSError(f"missing reporter control file: {control_path}")
        resolved.append(control_path)
    return resolved[0], resolved[1], resolved[2]


def reporter_control_attestation(
    project_dir: Path | str,
) -> tuple[str, tuple[dict[str, str], ...]]:
    """Hash the stable source, metadata, and injection controls as one unit."""
    project = Path(project_dir).resolve()
    source, metadata, init_script = _reporter_sources(project)
    root = init_script.parent
    files = tuple(
        {
            "path": path.relative_to(root).as_posix(),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        for path in (source, metadata, init_script)
    )
    canonical_manifest = json.dumps(
        {
            "schema_version": 1,
            "files": files,
        },
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(canonical_manifest).hexdigest(), files


def _ensure_moddev_gametest_runtime(project: Path, wrapper: str,
                                    timeout_seconds: float) -> None:
    """Bootstrap ModDevGradle runtime files on fresh environments.

    The reporter precheck needs a main Minecraft/NeoForge artifact jar in
    build/moddev/artifacts plus gameTestServerLegacyClasspath.txt.  Both are
    only materialized once a gameTestServer run has been prepared, which
    fresh CI checkouts never had.  A single unreported runGameTestServer
    execution generates them; the reported gate run then proceeds normally.
    Layout note: Windows setups carry ``*-merged.jar``; fresh Linux checkouts
    produce plain ``neoforge-<v>.jar`` — both qualify as the main artifact.
    """
    artifacts = project / "build" / "moddev" / "artifacts"
    main_jars = [
        jar for jar in (artifacts.glob("*.jar") if artifacts.is_dir() else [])
        if "minecraft-resources" not in jar.name
        and not jar.name.endswith("-sources.jar")
    ]
    legacy = (project / "build" / "moddev" /
              "gameTestServerLegacyClasspath.txt")
    if main_jars and legacy.is_file():
        return
    bootstrap_command = [
        wrapper, "runGameTestServer", "--no-daemon", "--console=plain",
    ]
    code, _output, timed_out, launch_error = _run_bounded_command(
        bootstrap_command,
        cwd=project,
        timeout_seconds=min(timeout_seconds, 1800.0),
    )
    main_after = [
        jar for jar in (artifacts.glob("*.jar") if artifacts.is_dir() else [])
        if "minecraft-resources" not in jar.name
        and not jar.name.endswith("-sources.jar")
    ]
    if not main_after or not legacy.is_file():
        listing = sorted(
            p.name for p in artifacts.glob("*.jar")
        ) if artifacts.is_dir() else []
        diag = (
            f"artifacts_dir_exists={artifacts.is_dir()} "
            f"jars={listing} "
            f"legacy_classpath={legacy.is_file()}"
        )
        if launch_error is not None:
            reason = f"bootstrap runGameTestServer launch failed: {launch_error}"
        elif timed_out:
            reason = "bootstrap runGameTestServer timed out"
        else:
            reason = (f"bootstrap runGameTestServer exited with code {code} "
                      "and did not produce the ModDevGradle runtime files")
        raise OSError(f"{reason}; DIAG {diag}")


def _reporter_compile_classpath(project: Path) -> tuple[Path, Path]:
    """Resolve the Minecraft/NeoForge artifact and the FML loader jar.

    Layout differs per environment: Windows setups carry a
    ``*-merged.jar`` (recompiled), fresh Linux checkouts only produce
    ``neoforge-<v>.jar`` plus the client-extra resources jar.  Selection is
    therefore: drop the resources jar, prefer a -merged variant when exactly
    one exists, otherwise accept the single remaining candidate.
    """
    artifacts_dir = project / "build" / "moddev" / "artifacts"
    all_jars = sorted(artifacts_dir.glob("*.jar")) \
        if artifacts_dir.is_dir() else []
    candidates = [
        jar for jar in all_jars if "minecraft-resources" not in jar.name
        and not jar.name.endswith("-sources.jar")
    ]
    merged = [jar for jar in candidates if jar.name.endswith("-merged.jar")]
    chosen_pool = merged or candidates
    if len(chosen_pool) != 1:
        raise OSError(
            "cannot determine the ModDevGradle minecraft artifact: "
            f"jars={[j.name for j in all_jars]}"
        )
    classpath_file = (
        project / "build" / "moddev" / "gameTestServerLegacyClasspath.txt"
    )
    try:
        entries = [
            Path(line.strip())
            for line in classpath_file.read_text(
                encoding="utf-8", errors="strict"
            ).splitlines()
            if line.strip()
        ]
    except (OSError, UnicodeError) as error:
        raise OSError(
            "cannot read ModDevGradle GameTest runtime classpath"
        ) from error
    loaders = [
        path
        for path in entries
        if path.name.startswith("loader-")
        and path.suffix == ".jar"
        and "fancymodloader" in path.as_posix().lower()
        and path.is_file()
    ]
    if len(loaders) != 1:
        raise OSError(
            "expected exactly one FancyModLoader jar in the generated "
            "GameTest classpath"
        )
    return chosen_pool[0].resolve(), loaders[0].resolve()


def build_reporter_bundle(
    project_dir: Path | str,
    output_root: Path,
    *,
    timeout_seconds: float = 120.0,
) -> ReporterBundle:
    project = Path(project_dir).resolve()
    source, metadata, init_script = _reporter_sources(project)
    control_digest, control_files = reporter_control_attestation(project)
    merged, loader = _reporter_compile_classpath(project)
    javac = shutil.which("javac")
    if javac is None:
        raise OSError("javac was not found on PATH")

    classes = output_root / "reporter-classes"
    classes.mkdir(parents=True, exist_ok=False)
    command = [
        javac,
        "--release",
        "21",
        "-cp",
        os.pathsep.join((str(merged), str(loader))),
        "-d",
        str(classes),
        str(source),
    ]
    returncode, output, timed_out, launch_error = _run_bounded_command(
        command,
        cwd=project,
        timeout_seconds=timeout_seconds,
    )
    if launch_error is not None:
        raise OSError(f"could not launch javac: {launch_error}")
    if timed_out:
        raise OSError("GameTest evidence reporter compilation timed out")
    if returncode != 0:
        raise OSError(
            "GameTest evidence reporter compilation failed:\n"
            + "\n".join(output.splitlines()[-30:])
        )

    jar_path = output_root / "codex-gametest-reporter.jar"
    with zipfile.ZipFile(
        jar_path, mode="x", compression=zipfile.ZIP_DEFLATED
    ) as archive:
        archive.write(metadata, "META-INF/neoforge.mods.toml")
        archive.writestr(
            "META-INF/codex-gametest-control.json",
            json.dumps(
                {
                    "schema_version": 1,
                    "control_sha256": control_digest,
                    "files": control_files,
                },
                ensure_ascii=True,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8"),
        )
        for class_file in sorted(classes.rglob("*.class")):
            archive.write(
                class_file, class_file.relative_to(classes).as_posix()
            )
    jar_digest = hashlib.sha256(jar_path.read_bytes()).hexdigest()
    return ReporterBundle(
        jar_path=jar_path,
        jar_sha256=jar_digest,
        init_script=init_script,
        control_sha256=control_digest,
        control_files=control_files,
    )


def _strict_json_line(raw_line: str) -> dict[str, Any]:
    def reject_duplicates(
        pairs: list[tuple[str, Any]],
    ) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise ValueError(f"duplicate JSON key: {key}")
            value[key] = item
        return value

    parsed = json.loads(
        raw_line,
        object_pairs_hook=reject_duplicates,
        parse_constant=lambda value: (_ for _ in ()).throw(
            ValueError(f"non-standard JSON constant: {value}")
        ),
    )
    if not isinstance(parsed, dict):
        raise ValueError("runtime event must be a JSON object")
    return parsed


def _canonical_runtime_event_bytes(
    events: Sequence[dict[str, Any]],
) -> bytes:
    return "".join(
        json.dumps(
            event,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
        for event in events
    ).encode("utf-8")


def discovery_runtime_symbol_map(
    discovery: DiscoveryResult,
) -> dict[str, str]:
    return {
        occurrence.runtime_name: occurrence.symbol
        for occurrence in discovery.tests
        if occurrence.runtime_name is not None
        and occurrence.symbol is not None
        and occurrence.signature_valid
        and occurrence.bytecode_verified
    }


def validate_runtime_event_bytes(
    raw: bytes,
    *,
    nonce: str,
    expected_runtime_symbols: dict[str, str],
) -> RuntimeEventEvidence:
    stream_digest = hashlib.sha256(raw).hexdigest()
    if not raw or len(raw) > MAX_RUNTIME_EVENT_BYTES:
        return RuntimeEventEvidence(
            False,
            "runtime event stream is empty or exceeds 1 MiB",
            None,
            stream_digest,
        )
    try:
        text = raw.decode("utf-8", errors="strict")
        lines = [line for line in text.splitlines() if line]
        events = [_strict_json_line(line) for line in lines]
    except (UnicodeError, json.JSONDecodeError, ValueError) as error:
        return RuntimeEventEvidence(
            False,
            f"invalid runtime event stream: {error}",
            None,
            stream_digest,
        )
    canonical_events = tuple(events)
    canonical_digest = hashlib.sha256(
        _canonical_runtime_event_bytes(events)
    ).hexdigest()

    def failure(reason: str) -> RuntimeEventEvidence:
        return RuntimeEventEvidence(
            passed=False,
            reason=reason,
            protocol=RUNTIME_EVENT_PROTOCOL,
            stream_sha256=stream_digest,
            nonce=nonce,
            raw_jsonl=text,
            canonical_events=canonical_events,
            canonical_stream_sha256=canonical_digest,
        )

    if len(events) < 2:
        return failure("runtime event stream has no terminal event")

    for index, event in enumerate(events, 1):
        if (
            event.get("protocol") != RUNTIME_EVENT_PROTOCOL
            or event.get("nonce") != nonce
            or event.get("sequence") != index
            or isinstance(event.get("sequence"), bool)
        ):
            return failure(
                "runtime event protocol, nonce, or sequence mismatch"
            )
    if events[0].get("event") != "run_started":
        return failure(
            "runtime event stream does not start with run_started"
        )
    terminal = events[-1]
    if terminal.get("event") != "run_finished":
        return failure(
            "runtime event stream does not end with run_finished"
        )

    expected = dict(expected_runtime_symbols)
    observed: dict[str, str] = {}
    invalid_event = False
    for event in events[1:-1]:
        event_type = event.get("event")
        runtime_name = event.get("test_name")
        if (
            event_type not in {"test_passed", "test_failed"}
            or not isinstance(runtime_name, str)
            or not runtime_name
            or runtime_name in observed
            or not isinstance(event.get("required"), bool)
            or not isinstance(event.get("detail"), str)
        ):
            invalid_event = True
            break
        observed[runtime_name] = event_type
    if invalid_event:
        return failure(
            "runtime test events are malformed or duplicated"
        )

    expected_names = set(expected)
    observed_names = set(observed)
    missing_names = sorted(expected_names - observed_names)
    unexpected_names = sorted(observed_names - expected_names)
    passed_names = sorted(
        name for name, status in observed.items() if status == "test_passed"
    )
    failed_names = sorted(
        name for name, status in observed.items() if status == "test_failed"
    )
    executed_symbols = tuple(
        sorted(expected[name] for name in observed_names & expected_names)
    )
    passed_symbols = tuple(
        sorted(expected[name] for name in passed_names if name in expected)
    )
    failed_symbols = tuple(
        sorted(expected[name] for name in failed_names if name in expected)
    )
    missing_symbols = tuple(
        sorted(expected[name] for name in missing_names)
    )
    counts_valid = (
        isinstance(terminal.get("passed"), int)
        and not isinstance(terminal.get("passed"), bool)
        and isinstance(terminal.get("failed"), int)
        and not isinstance(terminal.get("failed"), bool)
        and terminal.get("passed") == len(passed_names)
        and terminal.get("failed") == len(failed_names)
    )
    passed = (
        bool(expected)
        and not missing_names
        and not unexpected_names
        and not failed_names
        and counts_valid
        and len(passed_names) == len(expected)
    )
    if passed:
        reason = (
            f"runtime reporter proved the exact {len(passed_symbols)}-symbol "
            "GameTest set passed"
        )
    else:
        reason = (
            "runtime symbol evidence mismatch "
            f"(missing={missing_names}, unexpected={unexpected_names}, "
            f"failed={failed_names}, counts_valid={counts_valid})"
        )
    return RuntimeEventEvidence(
        passed=passed,
        reason=reason,
        protocol=RUNTIME_EVENT_PROTOCOL,
        stream_sha256=stream_digest,
        executed_symbols=executed_symbols,
        passed_symbols=passed_symbols,
        failed_symbols=failed_symbols,
        missing_symbols=missing_symbols,
        unexpected_runtime_tests=tuple(unexpected_names),
        nonce=nonce,
        raw_jsonl=text,
        canonical_events=canonical_events,
        canonical_stream_sha256=canonical_digest,
    )


def validate_runtime_events(
    event_path: Path,
    *,
    nonce: str,
    discovery: DiscoveryResult,
) -> RuntimeEventEvidence:
    try:
        raw = event_path.read_bytes()
    except OSError as error:
        return RuntimeEventEvidence(
            False,
            f"runtime event stream is missing: {error}",
            None,
            None,
        )
    return validate_runtime_event_bytes(
        raw,
        nonce=nonce,
        expected_runtime_symbols=discovery_runtime_symbol_map(discovery),
    )


def bind_runtime_evidence(
    execution: ExecutionResult,
    evidence: RuntimeEventEvidence,
    *,
    reporter_jar_sha256: str,
    reporter_control_sha256: Optional[str] = None,
    reporter_control_files: tuple[dict[str, str], ...] = (),
) -> ExecutionResult:
    passed = execution.passed and evidence.passed
    reason = evidence.reason if execution.passed else execution.reason
    return replace(
        execution,
        status="passed" if passed else execution.status
        if execution.status != "passed"
        else "failed",
        passed=passed,
        reason=reason,
        evidence_level=(
            "runtime_symbol_set" if evidence.passed else "untrusted"
        ),
        runtime_events_verified=evidence.passed,
        reporter_protocol=evidence.protocol,
        reporter_jar_sha256=reporter_jar_sha256,
        reporter_control_sha256=reporter_control_sha256,
        reporter_control_files=reporter_control_files,
        event_stream_sha256=evidence.stream_sha256,
        canonical_event_stream_sha256=evidence.canonical_stream_sha256,
        runtime_event_nonce=evidence.nonce,
        runtime_event_raw_jsonl=evidence.raw_jsonl,
        canonical_runtime_events=evidence.canonical_events,
        executed_symbols=evidence.executed_symbols,
        passed_symbols=evidence.passed_symbols,
        failed_symbols=evidence.failed_symbols,
        missing_symbols=evidence.missing_symbols,
        unexpected_runtime_tests=evidence.unexpected_runtime_tests,
    )


def run_game_tests(
    project_dir: Path | str,
    *,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    tail_lines: int = DEFAULT_TAIL_LINES,
    discovered_tests: Optional[int] = None,
    popen_factory: Optional[
        Callable[..., subprocess.Popen[Any]]
    ] = None,
    command_override: Optional[Sequence[str]] = None,
) -> ExecutionResult:
    """Run the dedicated GameTest server with bounded lifetime and output."""
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise ValueError("timeout_seconds must be finite and greater than zero")

    project = Path(project_dir).resolve()
    command = (
        list(command_override)
        if command_override is not None
        else gradle_command(project)
    )
    wrapper = Path(command[0])
    started = time.monotonic()
    launch_process = popen_factory or subprocess.Popen

    if not wrapper.is_file():
        return parse_gametest_output(
            "",
            command=command,
            returncode=None,
            timed_out=False,
            termination_attempted=False,
            duration_seconds=time.monotonic() - started,
            tail_lines=tail_lines,
            launch_error=f"wrapper not found at {wrapper}",
            discovered_tests=discovered_tests,
        )

    popen_options: dict[str, Any] = {}
    if os.name == "nt":
        popen_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        popen_options["start_new_session"] = True

    timed_out = False
    termination_attempted = False
    launch_error: Optional[str] = None
    returncode: Optional[int] = None
    output = ""

    # A temporary file prevents a chatty Minecraft process from filling a pipe
    # while still keeping the complete log available for marker parsing.
    with tempfile.TemporaryFile(
        mode="w+t", encoding="utf-8", errors="replace"
    ) as log_file:
        try:
            process = launch_process(
                command,
                cwd=str(project),
                stdin=subprocess.DEVNULL,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                **popen_options,
            )
        except OSError as exc:
            launch_error = str(exc)
            process = None

        if process is not None:
            try:
                process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                timed_out = True
                termination_attempted = True
                terminate_process_tree(process)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    try:
                        process.kill()
                    except OSError:
                        pass
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        pass
            except BaseException:
                # The Gradle wrapper owns a Minecraft child process.  Always
                # reap the complete group on Ctrl+C or an unexpected wait()
                # failure before propagating the interruption to the caller.
                termination_attempted = True
                terminate_process_tree(process)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    try:
                        process.kill()
                    except OSError:
                        pass
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        pass
                raise
            returncode = process.returncode

        log_file.flush()
        log_file.seek(0)
        output = log_file.read()

    return parse_gametest_output(
        output,
        command=command,
        returncode=returncode,
        timed_out=timed_out,
        termination_attempted=termination_attempted,
        duration_seconds=time.monotonic() - started,
        tail_lines=tail_lines,
        launch_error=launch_error,
        discovered_tests=discovered_tests,
    )


def run_trusted_game_tests(
    project_dir: Path | str,
    discovery: DiscoveryResult,
    *,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    tail_lines: int = DEFAULT_TAIL_LINES,
) -> tuple[DiscoveryResult, ExecutionResult]:
    """Compile, bytecode-bind, then run the process-local symbol reporter.

    The historical function name is retained for API compatibility.  Its
    evidence is replayable and control-bound, but is not resistant to an
    actively malicious mod sharing the GameTestServer JVM.
    """
    project = Path(project_dir).resolve()
    base_command = gradle_command(project)
    wrapper = Path(base_command[0])
    compile_command = [
        str(wrapper),
        "compileJava",
        "--no-daemon",
        "--console=plain",
    ]
    if not wrapper.is_file():
        return discovery, _tool_error_execution(
            base_command,
            f"Gradle wrapper not found at {wrapper}",
            discovered_tests=discovery.count,
        )

    preflight_timeout = min(timeout_seconds, 600.0)
    code, output, timed_out, launch_error = _run_bounded_command(
        compile_command,
        cwd=project,
        timeout_seconds=preflight_timeout,
    )
    if launch_error is not None or timed_out or code != 0:
        reason = (
            f"compileJava preflight launch failed: {launch_error}"
            if launch_error is not None
            else "compileJava preflight timed out"
            if timed_out
            else f"compileJava preflight exited with code {code}"
        )
        return discovery, _tool_error_execution(
            compile_command,
            reason,
            output=output,
            discovered_tests=discovery.count,
        )

    verified_discovery = verify_compiled_gametests(project, discovery)
    if verified_discovery.errors:
        return verified_discovery, _tool_error_execution(
            base_command,
            "compiled GameTest bytecode did not match strict source discovery",
            discovered_tests=verified_discovery.count,
        )

    with tempfile.TemporaryDirectory(
        prefix="codex-gametest-control-"
    ) as raw_control_root:
        control_root = Path(raw_control_root).resolve()
        _ensure_moddev_gametest_runtime(
            project, str(wrapper), timeout_seconds
        )
        try:
            reporter = build_reporter_bundle(project, control_root)
        except OSError as error:
            return verified_discovery, _tool_error_execution(
                base_command,
                str(error),
                discovered_tests=verified_discovery.count,
            )
        event_path = control_root / "runtime-events.jsonl"
        nonce = uuid.uuid4().hex
        command = [
            str(wrapper),
            "--no-daemon",
            "--console=plain",
            f"-Dcodex.gametest.reporterJar={reporter.jar_path}",
            f"-Dcodex.gametest.events={event_path}",
            f"-Dcodex.gametest.nonce={nonce}",
            "-I",
            str(reporter.init_script),
            "runGameTestServer",
        ]
        execution = run_game_tests(
            project,
            timeout_seconds=timeout_seconds,
            tail_lines=tail_lines,
            discovered_tests=verified_discovery.count,
            command_override=command,
        )
        execution = bind_discovery_count(
            execution, verified_discovery.count
        )
        evidence = validate_runtime_events(
            event_path,
            nonce=nonce,
            discovery=verified_discovery,
        )
        execution = bind_runtime_evidence(
            execution,
            evidence,
            reporter_jar_sha256=reporter.jar_sha256,
            reporter_control_sha256=reporter.control_sha256,
            reporter_control_files=reporter.control_files,
        )
        return verified_discovery, execution


def _write_json_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def _build_report(
    discovery: DiscoveryResult,
    execution: Optional[ExecutionResult],
    *,
    command_ok: bool,
    evidence_satisfied: bool,
    reason: str,
    contracts_present: bool,
    tests_required: bool,
    allow_reference_host_only: bool = False,
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "gate": "L4 GameTest",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "attestation_scope": ATTESTATION_SCOPE,
        "tamper_resistance": TAMPER_RESISTANCE,
        "discovery": discovery.to_dict(),
        "execution": execution.to_dict() if execution is not None else None,
        "policy": {
            "contracts_present": contracts_present,
            "tests_required": tests_required,
            "allow_reference_host_only": allow_reference_host_only,
            "qualifying_feature_tests": discovery.feature_count,
            "reference_host_only": (
                discovery.count > 0 and discovery.feature_count == 0
            ),
        },
        "result": {
            "status": (
                "passed"
                if evidence_satisfied
                else "failed"
                if not command_ok
                else "advisory"
            ),
            "passed": evidence_satisfied,
            "command_ok": command_ok,
            "executed": execution is not None,
            "evidence_satisfied": evidence_satisfied,
            "reason": reason,
        },
    }


def _default_project_dir() -> Path:
    return Path(__file__).resolve().parents[2]


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Discover NeoForge @GameTest methods and optionally run the L4 "
            "GameTest server gate."
        )
    )
    parser.add_argument(
        "--project-dir",
        type=Path,
        default=_default_project_dir(),
        help="host Gradle project (default: repository containing .agents)",
    )
    parser.add_argument(
        "--require-tests",
        action="store_true",
        help="fail discovery when no authored @GameTest annotation is present",
    )
    parser.add_argument(
        "--require-tests-if-contracts",
        action="store_true",
        help=(
            "require authored tests when docs/features contains a feature "
            "contract; intended for reusable starter CI"
        ),
    )
    parser.add_argument(
        "--allow-reference-host-only",
        action="store_true",
        help=(
            "explicitly allow permanent dev.modstudio.referencehost "
            "infrastructure probes to satisfy --require-tests; intended only "
            "for the isolated reference-host evidence pipeline"
        ),
    )
    execution_group = parser.add_mutually_exclusive_group()
    execution_group.add_argument(
        "--run",
        action="store_true",
        help="run gradlew runGameTestServer after discovery",
    )
    execution_group.add_argument(
        "--run-if-present",
        action="store_true",
        help=(
            "run gradlew runGameTestServer when discovery finds authored tests; "
            "remain advisory when the host has none"
        ),
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT_SECONDS,
        metavar="SECONDS",
        help=f"GameTest process timeout (default: {DEFAULT_TIMEOUT_SECONDS:g})",
    )
    parser.add_argument(
        "--tail-lines",
        type=int,
        default=DEFAULT_TAIL_LINES,
        metavar="N",
        help=f"non-empty output lines to print/store (default: {DEFAULT_TAIL_LINES})",
    )
    parser.add_argument(
        "--json-report",
        type=Path,
        metavar="PATH",
        help="optionally write a machine-readable JSON report",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    parser = _parser()
    args = parser.parse_args(argv)
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be finite and greater than zero")
    if not 1 <= args.tail_lines <= 200:
        parser.error("--tail-lines must be between 1 and 200")

    project = args.project_dir.resolve()
    contract_directory = project / "docs" / "features"
    contracts_present = bool(
        contract_directory.is_dir()
        and any(
            path.is_file() and not path.name.endswith(".schema.json")
            for path in contract_directory.rglob("*.json")
        )
    )
    tests_required = args.require_tests or (
        args.require_tests_if_contracts and contracts_present
    )
    discovery = discover_gametests(project)
    print(
        "L4 GameTest discovery: "
        f"scanned {discovery.scanned_files} Java file(s); "
        f"found {discovery.count} @GameTest annotation(s) "
        f"({discovery.feature_count} feature, "
        f"{discovery.infrastructure_probe_count} infrastructure probe)."
    )
    for occurrence in discovery.tests[:50]:
        identity = occurrence.symbol or "<unresolved>"
        validity = "valid" if occurrence.signature_valid else "INVALID"
        print(
            f"  - {occurrence.path}:{occurrence.line}:{occurrence.column} "
            f"{identity} [{validity}; {occurrence.classification}]"
        )
    if discovery.count > 50:
        print(f"  - ... (+{discovery.count - 50} more)")
    for error in discovery.errors:
        print(f"  [discovery-error] {error}")

    execution: Optional[ExecutionResult] = None
    command_ok = not discovery.errors
    reason = "GameTest discovery completed"

    if discovery.errors:
        reason = (
            "one or more Java sources could not be inspected or proven as "
            "strict static GameTests"
        )
    elif tests_required and discovery.count == 0:
        command_ok = False
        reason = (
            "tests are required but no authored @GameTest was found"
        )
    elif (
        tests_required
        and discovery.feature_count == 0
        and not args.allow_reference_host_only
    ):
        command_ok = False
        reason = (
            "tests are required but only permanent reference-host "
            "infrastructure probes were found; add a feature-specific "
            "@GameTest or explicitly opt in with "
            "--allow-reference-host-only for the isolated reference-host "
            "pipeline"
        )
    elif args.run or (args.run_if_present and discovery.count > 0):
        command = gradle_command(project)
        print("L4 GameTest run: " + " ".join(command[1:]))
        discovery, execution = run_trusted_game_tests(
            project,
            discovery,
            timeout_seconds=args.timeout,
            tail_lines=args.tail_lines,
        )
        command_ok = execution.passed
        reason = execution.reason
        print(f"GameTest output tail ({len(execution.output_tail)} line(s)):")
        for line in execution.output_tail:
            print(f"  {line}")
    elif args.run_if_present:
        reason = "no authored GameTest is present; conditional run skipped"

    evidence_satisfied = bool(
        command_ok
        and discovery.count > 0
        and execution is not None
        and execution.passed
    )
    if evidence_satisfied:
        print(f"L4 PASS: {reason}.")
    elif not command_ok:
        print(f"L4 FAIL: {reason}.")
    else:
        print(f"L4 ADVISORY: {reason}; no L4 execution evidence was produced.")

    report = _build_report(
        discovery,
        execution,
        command_ok=command_ok,
        evidence_satisfied=evidence_satisfied,
        reason=reason,
        contracts_present=contracts_present,
        tests_required=tests_required,
        allow_reference_host_only=args.allow_reference_host_only,
    )
    if args.json_report is not None:
        report_path = (
            args.json_report
            if args.json_report.is_absolute()
            else project / args.json_report
        )
        try:
            _write_json_report(report_path, report)
        except OSError as exc:
            print(f"L4 TOOL ERROR: could not write JSON report: {exc}")
            return 2
        print(f"JSON report: {report_path}")

    return 0 if command_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
