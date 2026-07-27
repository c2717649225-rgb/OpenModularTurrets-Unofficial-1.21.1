#!/usr/bin/env python3
"""L4 NeoForge GameTest discovery and execution gate.

The gate has two deliberately separate jobs:

* discover source-backed ``@GameTest`` methods under ``src/main/java``;
* optionally run ``gradlew runGameTestServer`` and require an unambiguous,
  all-green completion marker.

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
import signal
import subprocess
import sys
import tempfile
import time
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Optional, Sequence


SCHEMA_VERSION = 2
DEFAULT_TIMEOUT_SECONDS = 900.0
DEFAULT_TAIL_LINES = 30
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
    source_sha256: Optional[str] = None
    signature_valid: bool = False
    signature_errors: tuple[str, ...] = ()


@dataclass
class DiscoveryResult:
    project_dir: str
    scanned_files: int
    tests: list[TestOccurrence]
    errors: list[str]

    @property
    def count(self) -> int:
        return len(self.tests)

    def to_dict(self) -> dict[str, Any]:
        return {
            "project_dir": self.project_dir,
            "scanned_files": self.scanned_files,
            "count": self.count,
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
    evidence_level: str = "aggregate_set"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


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
    package_name = official_fqcn.rsplit(".", 1)[0]
    return spelling == simple_name and (
        official_fqcn in imports or f"{package_name}.*" in imports
    )


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
        classes.append(
            _ClassContext(
                name=match.group(1),
                start=match.start(),
                body_start=body_start,
                body_end=body_end,
                holder_namespace=namespace,
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
    match: re.Match[str],
) -> TestOccurrence:
    line = masked.count("\n", 0, match.start()) + 1
    line_start = masked.rfind("\n", 0, match.start()) + 1
    column = match.start() - line_start + 1
    errors: list[str] = []
    context = _containing_class(classes, match.start())
    annotation_end = _annotation_end(masked, match.end())
    annotation_spelling = match.group(0)[1:]

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
    return TestOccurrence(
        path=relative,
        line=line,
        column=column,
        fqcn=fqcn,
        method=method,
        symbol=symbol,
        holder_namespace=(
            context.holder_namespace if context is not None else None
        ),
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

    return DiscoveryResult(
        project_dir=str(project),
        scanned_files=scanned_files,
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
        evidence_level="aggregate_set",
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


def run_game_tests(
    project_dir: Path | str,
    *,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    tail_lines: int = DEFAULT_TAIL_LINES,
    discovered_tests: Optional[int] = None,
    popen_factory: Optional[
        Callable[..., subprocess.Popen[Any]]
    ] = None,
) -> ExecutionResult:
    """Run the dedicated GameTest server with bounded lifetime and output."""
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise ValueError("timeout_seconds must be finite and greater than zero")

    project = Path(project_dir).resolve()
    command = gradle_command(project)
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
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "gate": "L4 GameTest",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "discovery": discovery.to_dict(),
        "execution": execution.to_dict() if execution is not None else None,
        "policy": {
            "contracts_present": contracts_present,
            "tests_required": tests_required,
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
        f"found {discovery.count} @GameTest annotation(s)."
    )
    for occurrence in discovery.tests[:50]:
        identity = occurrence.symbol or "<unresolved>"
        validity = "valid" if occurrence.signature_valid else "INVALID"
        print(
            f"  - {occurrence.path}:{occurrence.line}:{occurrence.column} "
            f"{identity} [{validity}]"
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
    elif args.run or (args.run_if_present and discovery.count > 0):
        command = gradle_command(project)
        print("L4 GameTest run: " + " ".join(command[1:]))
        execution = run_game_tests(
            project,
            timeout_seconds=args.timeout,
            tail_lines=args.tail_lines,
            discovered_tests=discovery.count,
        )
        execution = bind_discovery_count(execution, discovery.count)
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
