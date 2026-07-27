#!/usr/bin/env python3
"""
L2 static gate for NeoForge host projects.

HARD CONSTRAINTS (do not relax):
  - Scan ONLY <project_root>/src/main/java/**/*.java
  - Never scan build/, .agents/, .gradle/, jars, or non-Java files
  - eventbus_nonstatic ONLY inside types annotated with @EventBusSubscriber
  - Instance methods registered via addListener / EVENT_BUS.register(this) are OK
  - Payload handlers default to MAIN. Thread warnings require a high-confidence
    same-compilation-unit registration explicitly using HandlerThread.NETWORK.
"""
from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
# gates/ -> .agents/ -> project root
DEFAULT_PROJECT_ROOT = SCRIPT_DIR.parent.parent


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


def find_matching_paren(text: str, open_idx: int) -> int:
    """open_idx points at '('. Returns index of matching ')' or -1.
    Tracks (), [], {} depth together; strings/comments are skipped."""
    depth = 0
    i = open_idx
    n = len(text)
    in_sl = in_ml = in_str = False
    str_ch = ""
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_sl:
            if ch == "\n":
                in_sl = False
            i += 1
            continue
        if in_ml:
            if ch == "*" and nxt == "/":
                in_ml = False
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
            in_sl = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_ml = True
            i += 2
            continue
        if ch in ('"', "'"):
            in_str = True
            str_ch = ch
            i += 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def count_top_level_commas(text: str, open_idx: int, close_idx: int) -> int:
    """Commas at depth 1 between matching parens (nested calls/lambdas excluded)."""
    depth = 0
    commas = 0
    in_str = False
    str_ch = ""
    i = open_idx
    while i <= close_idx:
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == str_ch:
                in_str = False
            i += 1
            continue
        if ch in ('"', "'"):
            in_str = True
            str_ch = ch
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 1:
            commas += 1
        i += 1
    return commas


def split_top_level(params: str) -> List[str]:
    """Split a parameter list on commas outside <>, (), [] nesting."""
    parts: List[str] = []
    depth = 0
    cur = []
    for ch in params:
        if ch in "<([{":
            depth += 1
        elif ch in ">)]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if cur:
        parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]


def explicit_network_handler_refs(text: str) -> set[Tuple[str, str]]:
    """
    Return ``(owner simple name, method name)`` references registered through a
    PayloadRegistrar that is explicitly configured for HandlerThread.NETWORK.

    This intentionally recognizes only high-confidence, same-source patterns:

      var registrar = event.registrar("1").executesOn(HandlerThread.NETWORK);
      registrar.playToServer(..., PayloadHandlers::handle);

      registrar = registrar.executesOn(HandlerThread.NETWORK);
      registrar.playToServer(..., PayloadHandlers::handle);

      event.registrar("1").executesOn(HandlerThread.NETWORK)
          .playToServer(..., PayloadHandlers::handle);

    PayloadRegistrar defaults to MAIN, and ``executesOn`` returns a configured
    copy. Cross-file/indirect registrations are deliberately left to manual
    review rather than guessed by regex.
    """
    code = re.sub(r"//[^\n]*|/\*.*?\*/", "", text, flags=re.DOTALL)
    registrar_modes: dict[str, str] = {}
    refs: set[Tuple[str, str]] = set()
    register_method = (
        r"(?:playToServer|playToClient|playBidirectional|"
        r"configurationToServer|configurationToClient|configurationBidirectional|"
        r"commonToServer|commonToClient|commonBidirectional)"
    )
    executes_on = re.compile(
        r"\.executesOn\s*\(\s*(?:[A-Za-z_]\w*\.)*"
        r"HandlerThread\.(MAIN|NETWORK)\s*\)"
    )

    for raw_statement in code.split(";"):
        statement = raw_statement.strip()
        if not statement:
            continue

        mode_match = executes_on.search(statement)
        declaration = re.search(
            r"\b(?:PayloadRegistrar|var)\s+([A-Za-z_]\w*)\s*=", statement
        )
        if declaration and (
            re.search(r"\bevent\s*\.\s*registrar\s*\(", statement)
            or mode_match is not None
        ):
            registrar_modes[declaration.group(1)] = (
                mode_match.group(1) if mode_match else "MAIN"
            )

        reassignment = re.search(
            r"\b([A-Za-z_]\w*)\s*=\s*\1\s*\.executesOn\s*\(\s*"
            r"(?:[A-Za-z_]\w*\.)*HandlerThread\.(MAIN|NETWORK)\s*\)",
            statement,
        )
        if reassignment:
            registrar_modes[reassignment.group(1)] = reassignment.group(2)

        if not re.search(rf"\b{register_method}\s*\(", statement):
            continue

        direct_network = bool(mode_match and mode_match.group(1) == "NETWORK")
        receiver = re.search(
            rf"\b([A-Za-z_]\w*)\s*\.\s*{register_method}\s*\(", statement
        )
        receiver_network = bool(
            receiver and registrar_modes.get(receiver.group(1)) == "NETWORK"
        )
        if not (direct_network or receiver_network):
            continue

        refs.update(
            (owner, method)
            for owner, method in re.findall(
                r"\b([A-Za-z_]\w*)\s*::\s*([A-Za-z_]\w*)\b", statement
            )
        )

    return refs


def record_param_names(text: str) -> dict:
    """Map record name -> ordered constructor parameter names."""
    out = {}
    for m in re.finditer(r"\brecord\s+([A-Za-z_]\w*)\s*\(", text):
        close = find_matching_paren(text, m.end() - 1)
        if close < 0:
            continue
        params = text[m.end() : close]
        names = []
        for p in split_top_level(params):
            tokens = p.split()
            if tokens:
                names.append(tokens[-1])
        out[m.group(1)] = names
    return out


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
                    "error",
                    rel,
                    line_of(text, abs_idx),
                    "@EventBusSubscriber handler must be static — non-static handlers "
                    "silently never fire (P0). "
                    "(Instance methods via addListener / EVENT_BUS.register(this) are OK outside this annotation.)",
                )
            )

    # --- static_registry_get: P0-5, eager .get() in static initializers ---
    # Single-line heuristic: a `static` field assignment whose initializer calls
    # ALL_CAPS.get() with no lambda/method-ref on the line (those defer the call).
    for i, line in enumerate(text.splitlines(), start=1):
        if "->" in line or "::" in line:
            continue
        stripped = line.lstrip()
        if stripped.startswith(("//", "*", "/*")):
            continue
        if re.search(
            r"^\s*(?:public\s+|protected\s+|private\s+)?(?:static\s+final|final\s+static|static)\s+"
            r"[^=;]*=\s*[^;]*\b[A-Z][A-Z0-9_]{2,}\.get\(\)",
            line,
        ):
            findings.append(
                Finding(
                    "static_registry_get",
                    "error",
                    rel,
                    i,
                    "Eager `.get()` on a registry/config constant in a static initializer "
                    "— crashes with 'Registry not present' before registration runs (P0). "
                    "Defer to runtime (method body, lambda, or event handler).",
                )
            )
    for m in re.finditer(r"\bstatic\s*\{", text):
        close = find_matching_brace(text, text.find("{", m.start()))
        if close < 0:
            continue
        body = text[m.start() : close]
        for gm in re.finditer(r"\b[A-Z][A-Z0-9_]{2,}\.get\(\)", body):
            seg_start = body.rfind("\n", 0, gm.start()) + 1
            seg = body[seg_start : gm.end()]
            if "->" in seg or "::" in seg:
                continue
            findings.append(
                Finding(
                    "static_registry_get",
                    "error",
                    rel,
                    line_of(text, m.start() + gm.start()),
                    "Eager `.get()` inside a static block — runs at class load, "
                    "before registries exist (P0). Defer to runtime.",
                )
            )

    # --- codec_field_order: P0-2, only high-confidence mismatches are reported ---
    # Report only direct Record::new factories where fieldOf(...) names and record
    # component names are the same set in a different order. Explicit adapter
    # lambdas may intentionally reorder values and are deliberately skipped.
    # Different sets (including custom serialized names) are also skipped.
    records = record_param_names(text)
    for m in re.finditer(r"RecordCodecBuilder\s*\.\s*(?:create|mapCodec)\s*\(", text):
        apply_m = re.search(
            r"\.apply\s*\(\s*\w+\s*,\s*([A-Za-z_]\w*)\s*::\s*new",
            text[m.start() :],
        )
        if not apply_m:
            continue
        window = text[m.start() : m.start() + apply_m.end()]
        codec_names = re.findall(r"\.(?:optionalFieldOf|fieldOf)\s*\(\s*\"(\w+)\"", window)
        target = apply_m.group(1)
        rec_names = records.get(target)
        if (
            rec_names
            and len(codec_names) == len(rec_names)
            and set(codec_names) == set(rec_names)
            and codec_names != rec_names
        ):
            findings.append(
                Finding(
                    "codec_field_order",
                    "error",
                    rel,
                    line_of(text, m.start()),
                    f"With `{target}::new`, Codec field order {codec_names} != "
                    f"record component order {rec_names}; decoded values can be "
                    "mapped to the wrong components or rejected (P0). Reorder "
                    ".group(...) for `::new`, or use an explicit audited adapter "
                    "lambda.",
                )
            )

    # --- streamcodec_composite_overflow: composite supports at most 6 fields ---
    for m in re.finditer(r"StreamCodec\s*\.\s*composite\s*\(", text):
        open_idx = text.find("(", m.start())
        close_idx = find_matching_paren(text, open_idx)
        if close_idx < 0:
            continue
        n_args = count_top_level_commas(text, open_idx, close_idx) + 1
        if n_args > 13:  # 6 codec/getter pairs + constructor = 13 args max
            findings.append(
                Finding(
                    "streamcodec_composite_overflow",
                    "error",
                    rel,
                    line_of(text, m.start()),
                    f"StreamCodec.composite with {n_args} args (> 13 = more than 6 fields) "
                    "— no such overload. Use StreamCodec.of(encoder, decoder) for 7+ fields.",
                )
            )

    # --- payload_thread_safety: conditional P0-4 heuristic, warning ---
    # PayloadRegistrar defaults to MAIN. Warn only when the same compilation
    # unit explicitly registers this file's handler method on NETWORK.
    network_handler_refs = explicit_network_handler_refs(text)
    for m in re.finditer(
        r"[\w<>\[\],\s.]+\s+(?P<method>[A-Za-z_]\w*)\s*"
        r"\((?P<params>[^)]*\bIPayloadContext\b[^)]*)\)\s*\{",
        text,
    ):
        method_name = m.group("method")
        if (path.stem, method_name) not in network_handler_refs:
            continue
        open_idx = text.find("{", m.end() - 1)
        close_idx = find_matching_brace(text, open_idx)
        if close_idx < 0:
            continue
        body = text[open_idx : close_idx + 1]
        if "enqueueWork" in body:
            continue
        mut = re.search(
            r"\.(setBlock|setData|set[A-Z]\w*|addItem|removeItem|hurt|heal|kill|"
            r"teleportTo|addEffect|removeEffect|drop|playSound|spawn[A-Z]\w*)\s*\(",
            body,
        )
        if mut:
            findings.append(
                Finding(
                    "payload_thread_safety",
                    "warning",
                    rel,
                    line_of(text, open_idx + mut.start()),
                    f"Payload handler `{path.stem}::{method_name}` is registered with "
                    "HandlerThread.NETWORK in this compilation unit and mutates state "
                    f"(`.{mut.group(1)}(...)`) without context.enqueueWork(...). "
                    "Move the game-state write to enqueueWork and handle the returned "
                    "CompletableFuture; default MAIN handlers do not need this wrapper.",
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
