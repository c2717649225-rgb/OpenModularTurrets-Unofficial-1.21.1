#!/usr/bin/env python3
"""
L2 static gate for NeoForge host projects.

HARD CONSTRAINTS (do not relax):
  - Scan ONLY <project_root>/src/main/java/**/*.java
  - Never scan build/, .agents/, .gradle/, jars, or non-Java files
  - eventbus_nonstatic ONLY inside types annotated with @EventBusSubscriber
  - Instance methods registered via addListener / EVENT_BUS.register(this) are OK
"""
from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
# scripts/ -> workspace_setup/ -> skills/ -> .agents/ -> project root
DEFAULT_PROJECT_ROOT = SCRIPT_DIR.parent.parent.parent.parent


@dataclass
class Finding:
    rule_id: str
    severity: str  # error | warning
    path: Path
    line: int
    message: str


def find_project_root(start: Optional[Path] = None) -> Path:
    """Prefer explicit env, else walk up for gradle.properties + gradlew."""
    env = os.environ.get("STATIC_GATE_PROJECT_ROOT")
    if env:
        return Path(env).resolve()

    cur = (start or DEFAULT_PROJECT_ROOT).resolve()
    for _ in range(8):
        props = cur / "gradle.properties"
        wrapper = cur / ("gradlew.bat" if os.name == "nt" else "gradlew")
        if props.is_file() and wrapper.is_file():
            return cur
        if cur.parent == cur:
            break
        cur = cur.parent
    return DEFAULT_PROJECT_ROOT.resolve()


def read_mod_id(project_root: Path) -> str:
    props = project_root / "gradle.properties"
    if not props.is_file():
        return "tutorialmod"
    for line in props.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("mod_id=") or line.startswith("mod_id ="):
            return line.split("=", 1)[1].strip()
    return "tutorialmod"


def iter_host_java_files(project_root: Path) -> List[Path]:
    java_root = project_root / "src" / "main" / "java"
    if not java_root.is_dir():
        return []
    files: List[Path] = []
    for p in java_root.rglob("*.java"):
        # Defense in depth: reject any path escaping java_root or hitting build/.agents
        try:
            p.resolve().relative_to(java_root.resolve())
        except ValueError:
            continue
        parts = {part.lower() for part in p.parts}
        if "build" in parts or ".agents" in parts or ".gradle" in parts:
            continue
        files.append(p)
    return sorted(files)


def is_client_path(path: Path, java_root: Path) -> bool:
    try:
        rel = path.resolve().relative_to(java_root.resolve())
    except ValueError:
        return False
    return "client" in [part.lower() for part in rel.parts]


def is_client_isolated_source(text: str) -> bool:
    """
    True if this compilation unit is explicitly physical-client-only.

    NeoForge templates often keep *Client classes in the main package with
    @Mod(..., dist = Dist.CLIENT) / @EventBusSubscriber(..., Dist.CLIENT)
    rather than a /client/ folder. Those must not trip client_import_in_common.
    """
    if re.search(r"@Mod\s*\([^;]*\bdist\s*=\s*Dist\.CLIENT\b", text, re.DOTALL):
        return True
    if re.search(
        r"@EventBusSubscriber\s*\([^;]*\b(?:value\s*=\s*)?Dist\.CLIENT\b",
        text,
        re.DOTALL,
    ):
        return True
    if re.search(r"\bvalue\s*=\s*Dist\.CLIENT\b", text):
        return True
    return False


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def find_matching_brace(text: str, open_idx: int) -> int:
    """open_idx points at '{'. Returns index of matching '}' or -1."""
    depth = 0
    i = open_idx
    n = len(text)
    in_sl_comment = False
    in_ml_comment = False
    in_str = False
    str_ch = ""
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_sl_comment:
            if ch == "\n":
                in_sl_comment = False
            i += 1
            continue
        if in_ml_comment:
            if ch == "*" and nxt == "/":
                in_ml_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == str_ch:
                in_str = False
            i += 1
            continue
        if ch == "/" and nxt == "/":
            in_sl_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_ml_comment = True
            i += 2
            continue
        if ch in ('"', "'"):
            in_str = True
            str_ch = ch
            i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def eventbus_subscriber_ranges(text: str) -> List[Tuple[int, int]]:
    """
    Return (start, end) character ranges for class bodies that carry
    @EventBusSubscriber on the type (annotation appears before class keyword).
    """
    ranges: List[Tuple[int, int]] = []
    for m in re.finditer(r"@EventBusSubscriber\b", text):
        # Look ahead for 'class' or 'interface' then '{'
        after = text[m.start() :]
        class_m = re.search(r"\b(class|interface)\s+[A-Za-z_][A-Za-z0-9_]*", after)
        if not class_m:
            continue
        brace_rel = after.find("{", class_m.end())
        if brace_rel < 0:
            continue
        open_idx = m.start() + brace_rel
        close_idx = find_matching_brace(text, open_idx)
        if close_idx < 0:
            continue
        ranges.append((open_idx, close_idx))
    return ranges


def scan_file(
    path: Path,
    text: str,
    *,
    java_root: Path,
    mod_id: str,
) -> List[Finding]:
    findings: List[Finding] = []
    rel = path

    # --- client_import_in_common ---
    # Exempt: path under **/client/** OR file explicitly Dist.CLIENT-isolated
    if not is_client_path(path, java_root) and not is_client_isolated_source(text):
        for m in re.finditer(
            r"(?m)^\s*import\s+net\.minecraft\.client(?:\.[A-Za-z0-9_.*]+)?\s*;",
            text,
        ):
            findings.append(
                Finding(
                    "client_import_in_common",
                    "error",
                    rel,
                    line_of(text, m.start()),
                    "Common (non-client) code imports net.minecraft.client.*; "
                    "use a client package and/or Dist.CLIENT-only class.",
                )
            )

    # --- getitemstack_nbt ---
    for m in re.finditer(r"\b(getOrCreateTag|getTag)\s*\(", text):
        findings.append(
            Finding(
                "getitemstack_nbt",
                "error",
                rel,
                line_of(text, m.start()),
                f"Legacy NBT API `{m.group(1)}(` — use Data Components on 1.21.1.",
            )
        )

    # --- onlyin_usage ---
    for m in re.finditer(
        r"import\s+net\.neoforged\.api\.distmarker\.OnlyIn\s*;|@OnlyIn\b",
        text,
    ):
        findings.append(
            Finding(
                "onlyin_usage",
                "warning",
                rel,
                line_of(text, m.start()),
                "Prefer Dist.CLIENT isolation over OnlyIn; see architecture / anti_patterns.",
            )
        )

    # --- eventbus_nonstatic: ONLY inside @EventBusSubscriber class bodies ---
    for start, end in eventbus_subscriber_ranges(text):
        body = text[start : end + 1]
        for sm in re.finditer(r"@SubscribeEvent\b", body):
            # Method signature window after annotation
            window = body[sm.end() : sm.end() + 400]
            # Skip if another annotation block only; find method-like line
            method_m = re.search(
                r"(public|protected|private)\s+(?:static\s+)?[\w.<>,\s\[\]]+\s+[A-Za-z_][A-Za-z0-9_]*\s*\(",
                window,
            )
            if not method_m:
                # try without access modifier
                method_m = re.search(
                    r"(?:static\s+)?[\w.<>,\s\[\]]+\s+[A-Za-z_][A-Za-z0-9_]*\s*\(",
                    window,
                )
            if not method_m:
                continue
            sig = method_m.group(0)
            if re.search(r"\bstatic\b", sig):
                continue
            abs_idx = start + sm.start()
            findings.append(
                Finding(
                    "eventbus_nonstatic",
                    "warning",
                    rel,
                    line_of(text, abs_idx),
                    "@EventBusSubscriber handler must be static. "
                    "(Instance methods via addListener / EVENT_BUS.register(this) are OK outside this annotation.)",
                )
            )

    # --- hardcoded_stale_modid: only quoted literals, only if != current mod_id ---
    # Template default id often left after rename
    stale_candidates = {"tutorialmod"}
    if mod_id and mod_id != "tutorialmod":
        for stale in stale_candidates:
            if stale == mod_id:
                continue
            for m in re.finditer(
                rf'["\']{re.escape(stale)}["\']',
                text,
            ):
                findings.append(
                    Finding(
                        "hardcoded_stale_modid",
                        "warning",
                        rel,
                        line_of(text, m.start()),
                        f"Quoted stale mod id `{stale}` while gradle mod_id=`{mod_id}`. "
                        "Run init_workspace or replace with current MODID.",
                    )
                )

    return findings


def run_gate(project_root: Path) -> Tuple[int, List[Finding]]:
    java_root = project_root / "src" / "main" / "java"
    if not java_root.is_dir():
        print(f"ERROR: missing host sources root: {java_root}")
        print("static_gate only scans src/main/java — refusing to widen scope.")
        return 2, []

    mod_id = read_mod_id(project_root)
    files = iter_host_java_files(project_root)
    all_findings: List[Finding] = []
    for f in files:
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            print(f"WARNING: cannot read {f}: {e}")
            continue
        all_findings.extend(
            scan_file(f, text, java_root=java_root, mod_id=mod_id)
        )

    return 0, all_findings


def print_report(
    project_root: Path,
    findings: List[Finding],
    *,
    treat_warnings_as_errors: bool = False,
) -> int:
    print("==================================================")
    print("L2 Static Gate (host src/main/java only)")
    print("==================================================")
    print(f"Project root: {project_root}")
    print(f"mod_id: {read_mod_id(project_root)}")
    java_root = project_root / "src" / "main" / "java"
    n_files = len(iter_host_java_files(project_root))
    print(f"Scanned Java files: {n_files}")
    print("Excluded: build/, .agents/, .gradle/, jars, non-Java, src/generated/")

    errors = [f for f in findings if f.severity == "error"]
    warnings = [f for f in findings if f.severity == "warning"]

    def show(items: Iterable[Finding], title: str) -> None:
        items = list(items)
        if not items:
            print(f"\n{title}: 0")
            return
        print(f"\n{title}: {len(items)}")
        for f in items:
            try:
                rel = f.path.resolve().relative_to(project_root.resolve())
            except ValueError:
                rel = f.path
            print(f"  [{f.severity}] {f.rule_id} @ {rel}:{f.line}")
            print(f"    {f.message}")

    show(errors, "ERRORS")
    show(warnings, "WARNINGS")

    fail = bool(errors) or (treat_warnings_as_errors and bool(warnings))
    if fail:
        print("\nRESULT: FAIL (L2)")
        return 1
    print("\nRESULT: PASS (L2)")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    argv = list(sys.argv[1:] if argv is None else argv)
    treat_w = "--warnings-as-errors" in argv
    project_root = find_project_root()
    code, findings = run_gate(project_root)
    if code != 0:
        return code
    return print_report(project_root, findings, treat_warnings_as_errors=treat_w)


if __name__ == "__main__":
    sys.exit(main())
