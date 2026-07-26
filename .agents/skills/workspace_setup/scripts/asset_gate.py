#!/usr/bin/env python3
"""
L2.5 asset gate for NeoForge host projects: registry <-> resource reconciliation.

Catches the #1 class of AI-generated mod defects that compile fine and never
crash, but ship broken: missing item/block models (purple-black checker),
missing blockstates, missing loot tables, untranslated `item.modid.x` names,
and dangling model/texture references.

HARD CONSTRAINTS (do not relax):
  - Parse ONLY <project_root>/src/main/java/**/*.java for registrations
  - Resource lookup is the merged view of src/main/resources + src/generated/resources
  - Only reconcile the host mod's own namespace; `minecraft:`/other namespaces skipped
  - Never scan build/, .agents/, .gradle/
  - Purely static: no Gradle, no game launch, no network

Severity policy:
  error   -> guaranteed-visible defect (missing model/blockstate/en_us key,
             dangling model/blockstate reference)
  warning -> quality gap that may be intentional or art-pending (missing
             loot table, mineable tag, texture PNG, zh_cn.json, translatable keys)
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from static_gate import find_project_root, read_mod_id


@dataclass
class Finding:
    rule_id: str
    severity: str  # error | warning
    subject: str   # registry entry or resource path the finding is about
    message: str


# ---------------------------------------------------------------- java parsing

LINE_COMMENT_RE = re.compile(r"//[^\n]*")
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)

DECL_PATTERNS = [
    # DeferredRegister.Items ITEMS = DeferredRegister.createItems(...)
    (re.compile(r"DeferredRegister\.Items\s+(\w+)\s*="), "item"),
    (re.compile(r"DeferredRegister\.Blocks\s+(\w+)\s*="), "block"),
    (re.compile(r"(\w+)\s*=\s*DeferredRegister\.createItems\("), "item"),
    (re.compile(r"(\w+)\s*=\s*DeferredRegister\.createBlocks\("), "block"),
    (re.compile(r"(\w+)\s*=\s*DeferredRegister\.create\(\s*Registries\.ITEM\b"), "item"),
    (re.compile(r"(\w+)\s*=\s*DeferredRegister\.create\(\s*Registries\.BLOCK\b"), "block"),
]

# Direct helper calls carry their own kind regardless of receiver variable.
HELPER_CALL_RE = re.compile(
    r"\.(registerSimpleBlockItem|registerSimpleBlock|registerBlock|"
    r"registerSimpleItem|registerItem|register)\s*\(\s*\"([a-z0-9_./-]+)\""
)
HELPER_KIND = {
    "registerSimpleBlockItem": "blockitem",
    "registerSimpleBlock": "block",
    "registerBlock": "block",
    "registerSimpleItem": "item",
    "registerItem": "item",
}

RECEIVER_RE = re.compile(r"(\w+)\s*\.\s*register\s*\(\s*\"")
TRANSLATABLE_RE = re.compile(r"Component\.translatable\(\s*\"([\w.\-]+)\"\s*[),]")
# Entry registration with a non-literal name: identifier first arg followed by a
# comma. Single-identifier calls like BLOCKS.register(modEventBus) are bus
# attachment, not entry registration, and must not count.
DYNAMIC_REGISTER_RE = re.compile(
    r"\.register(?:Simple)?(?:Block|Item|BlockItem)?\s*\(\s*[A-Za-z_][\w.]*\s*,"
)


def strip_comments(text: str) -> str:
    return LINE_COMMENT_RE.sub("", BLOCK_COMMENT_RE.sub("", text))


def iter_host_java_files(project_root: Path) -> List[Path]:
    java_root = project_root / "src" / "main" / "java"
    if not java_root.is_dir():
        return []
    files: List[Path] = []
    for p in java_root.rglob("*.java"):
        parts = {part.lower() for part in p.parts}
        if "build" in parts or ".agents" in parts or ".gradle" in parts:
            continue
        files.append(p)
    return sorted(files)


def parse_registrations(
    project_root: Path,
) -> Tuple[Set[str], Set[str], Set[str], Set[str], int]:
    """Returns (items, blocks, blockitems, translatable_keys, unresolved_dynamic)."""
    items: Set[str] = set()
    blocks: Set[str] = set()
    blockitems: Set[str] = set()
    translatables: Set[str] = set()
    unresolved = 0

    for path in iter_host_java_files(project_root):
        try:
            raw = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        text = strip_comments(raw)

        var_kinds: Dict[str, str] = {}
        for pat, kind in DECL_PATTERNS:
            for m in pat.finditer(text):
                var_kinds[m.group(1)] = kind

        for m in HELPER_CALL_RE.finditer(text):
            method, name = m.group(1), m.group(2)
            kind = HELPER_KIND.get(method)
            if kind is None:  # plain .register("name", ...) -> resolve receiver
                recv_m = RECEIVER_RE.match(text, m.start())
                kind = var_kinds.get(recv_m.group(1)) if recv_m else None
            if kind == "item":
                items.add(name)
            elif kind == "block":
                blocks.add(name)
            elif kind == "blockitem":
                blockitems.add(name)
            # unknown receiver (creative tabs, entities, ...) -> not ours to check

        for m in TRANSLATABLE_RE.finditer(text):
            translatables.add(m.group(1))

        # Dynamic (non-literal) registrations we cannot reconcile: count, never hide.
        unresolved += len(DYNAMIC_REGISTER_RE.findall(text))

    # An item sharing a block's name is a BlockItem (its lang key comes from the block).
    promoted = items & blocks
    blockitems |= promoted
    items -= promoted
    return items, blocks, blockitems, translatables, unresolved


# ------------------------------------------------------------- resource lookup

class ResourceView:
    """Merged read view over src/main/resources + src/generated/resources."""

    def __init__(self, project_root: Path):
        self.roots = [
            project_root / "src" / "main" / "resources",
            project_root / "src" / "generated" / "resources",
        ]

    def find(self, rel: str) -> Optional[Path]:
        for root in self.roots:
            p = root / rel
            if p.is_file():
                return p
        return None

    def glob(self, rel_pattern: str) -> List[Path]:
        out: List[Path] = []
        for root in self.roots:
            if root.is_dir():
                out.extend(root.glob(rel_pattern))
        return out

    def load_json(self, rel: str) -> Optional[dict]:
        p = self.find(rel)
        if p is None:
            return None
        try:
            return json.loads(p.read_text(encoding="utf-8", errors="replace"))
        except (OSError, json.JSONDecodeError):
            return None


def load_lang(view: ResourceView, ns: str, lang: str) -> Optional[dict]:
    return view.load_json(f"assets/{ns}/lang/{lang}.json")


def split_ref(ref: str, default_ns: str) -> Tuple[str, str]:
    if ":" in ref:
        ns, path = ref.split(":", 1)
        return ns, path
    return default_ns, ref


# ------------------------------------------------------------------ the checks

def check_entries(
    view: ResourceView,
    ns: str,
    items: Set[str],
    blocks: Set[str],
    blockitems: Set[str],
) -> List[Finding]:
    findings: List[Finding] = []
    en_us = load_lang(view, ns, "en_us") or {}

    mineable_members: Set[str] = set()
    for tag_file in view.glob("data/minecraft/tags/block/mineable/*.json"):
        try:
            data = json.loads(tag_file.read_text(encoding="utf-8", errors="replace"))
            for v in data.get("values", []):
                if isinstance(v, str):
                    mineable_members.add(v)
        except (OSError, json.JSONDecodeError):
            continue

    for name in sorted(items | blockitems):
        entry = f"{ns}:{name}"
        if view.find(f"assets/{ns}/models/item/{name}.json") is None:
            findings.append(Finding(
                "missing_item_model", "error", entry,
                f"No assets/{ns}/models/item/{name}.json — renders as the "
                "purple-black missing model. Generate via ItemModelProvider (DataGen).",
            ))
        if name in items and f"item.{ns}.{name}" not in en_us:
            findings.append(Finding(
                "missing_lang_key", "error", entry,
                f"en_us.json lacks `item.{ns}.{name}` — shows raw key in game.",
            ))

    for name in sorted(blocks):
        entry = f"{ns}:{name}"
        if view.find(f"assets/{ns}/blockstates/{name}.json") is None:
            findings.append(Finding(
                "missing_blockstate", "error", entry,
                f"No assets/{ns}/blockstates/{name}.json — block renders as missing "
                "model. Generate via BlockStateProvider (DataGen).",
            ))
        if f"block.{ns}.{name}" not in en_us:
            findings.append(Finding(
                "missing_lang_key", "error", entry,
                f"en_us.json lacks `block.{ns}.{name}` — shows raw key in game.",
            ))
        if view.find(f"data/{ns}/loot_table/blocks/{name}.json") is None:
            findings.append(Finding(
                "missing_loot_table", "warning", entry,
                f"No data/{ns}/loot_table/blocks/{name}.json — block drops nothing "
                "when mined. Generate via BlockLootSubProvider, or keep empty on purpose.",
            ))
        if entry not in mineable_members:
            findings.append(Finding(
                "missing_mineable_tag", "warning", entry,
                "Not in any data/minecraft/tags/block/mineable/*.json — mined at "
                "hand-speed and may not drop with tools. Add via BlockTagsProvider "
                "if tool-mineable is intended.",
            ))

    return findings


def check_model_references(view: ResourceView, ns: str) -> List[Finding]:
    findings: List[Finding] = []
    seen: Set[Tuple[str, str, str]] = set()

    def model_exists(path: str) -> bool:
        return view.find(f"assets/{ns}/models/{path}.json") is not None

    def texture_exists(path: str) -> bool:
        return view.find(f"assets/{ns}/textures/{path}.png") is not None

    def add(rule: str, severity: str, subject: str, message: str) -> None:
        key = (rule, subject, message)
        if key not in seen:
            seen.add(key)
            findings.append(Finding(rule, severity, subject, message))

    # blockstate -> model references
    for bs in view.glob(f"assets/{ns}/blockstates/*.json"):
        try:
            data = json.loads(bs.read_text(encoding="utf-8", errors="replace"))
        except (OSError, json.JSONDecodeError) as e:
            add("malformed_json", "error", bs.name, f"Unreadable blockstate JSON: {e}")
            continue
        refs: List[str] = []
        for variant in (data.get("variants") or {}).values():
            cases = variant if isinstance(variant, list) else [variant]
            refs.extend(c.get("model", "") for c in cases if isinstance(c, dict))
        for part in data.get("multipart", []) or []:
            apply = part.get("apply", {})
            cases = apply if isinstance(apply, list) else [apply]
            refs.extend(c.get("model", "") for c in cases if isinstance(c, dict))
        for ref in refs:
            r_ns, r_path = split_ref(ref, ns)
            if r_ns == ns and not model_exists(r_path):
                add("dangling_model_ref", "error", bs.name,
                    f"References model `{ref}` but assets/{ns}/models/{r_path}.json is missing.")

    # model -> parent / texture references
    for model in view.glob(f"assets/{ns}/models/**/*.json"):
        try:
            data = json.loads(model.read_text(encoding="utf-8", errors="replace"))
        except (OSError, json.JSONDecodeError) as e:
            add("malformed_json", "error", model.name, f"Unreadable model JSON: {e}")
            continue
        parent = data.get("parent", "")
        if parent:
            p_ns, p_path = split_ref(parent, ns)
            if p_ns == ns and not model_exists(p_path):
                add("dangling_model_ref", "error", model.name,
                    f"Parent `{parent}` missing (assets/{ns}/models/{p_path}.json).")
        for tex in (data.get("textures") or {}).values():
            if not isinstance(tex, str) or tex.startswith("#"):
                continue  # texture variable indirection
            t_ns, t_path = split_ref(tex, ns)
            if t_ns == ns and not texture_exists(t_path):
                add("missing_texture", "warning", model.name,
                    f"Texture `{tex}` missing (assets/{ns}/textures/{t_path}.png). "
                    "Add art or a placeholder PNG before release.")

    return findings


def check_lang_quality(
    view: ResourceView, ns: str, translatables: Set[str]
) -> List[Finding]:
    findings: List[Finding] = []
    en_us = load_lang(view, ns, "en_us")
    if en_us is None:
        findings.append(Finding(
            "missing_lang_file", "error", f"assets/{ns}/lang/en_us.json",
            "en_us.json missing entirely — every name shows as a raw key.",
        ))
        en_us = {}
    if load_lang(view, ns, "zh_cn") is None:
        findings.append(Finding(
            "missing_lang_file", "warning", f"assets/{ns}/lang/zh_cn.json",
            "zh_cn.json missing — quality bar expects bilingual coverage.",
        ))
    for key in sorted(translatables):
        # Only vouch for keys that look owned by this mod; vanilla keys skipped.
        if (ns in key or key.startswith(("itemGroup.", "tooltip.", "gui.", "message."))) \
                and key not in en_us:
            findings.append(Finding(
                "untranslated_key", "warning", key,
                f"Component.translatable(\"{key}\") has no en_us.json entry.",
            ))
    return findings


# ----------------------------------------------------------------- entry point

def print_report(
    project_root: Path,
    ns: str,
    counts: Tuple[int, int, int, int],
    findings: List[Finding],
    *,
    treat_warnings_as_errors: bool = False,
) -> int:
    n_items, n_blocks, n_blockitems, unresolved = counts
    print("==================================================")
    print("L2.5 Asset Gate (registry <-> resource reconciliation)")
    print("==================================================")
    print(f"Project root: {project_root}")
    print(f"Namespace: {ns}")
    print(f"Registered: {n_items} items, {n_blocks} blocks, {n_blockitems} block-items")
    if unresolved:
        print(f"NOTE: {unresolved} dynamic register call(s) could not be statically "
              "resolved and were NOT checked.")
    print("Resource roots: src/main/resources + src/generated/resources")

    errors = [f for f in findings if f.severity == "error"]
    warnings = [f for f in findings if f.severity == "warning"]

    def show(items_: List[Finding], title: str) -> None:
        if not items_:
            print(f"\n{title}: 0")
            return
        print(f"\n{title}: {len(items_)}")
        for f in items_:
            print(f"  [{f.severity}] {f.rule_id} @ {f.subject}")
            print(f"    {f.message}")

    show(errors, "ERRORS")
    show(warnings, "WARNINGS")

    fail = bool(errors) or (treat_warnings_as_errors and bool(warnings))
    if fail:
        print("\nRESULT: FAIL (L2.5)")
        return 1
    print("\nRESULT: PASS (L2.5)")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    argv = list(sys.argv[1:] if argv is None else argv)
    treat_w = "--warnings-as-errors" in argv

    project_root = find_project_root()
    ns = read_mod_id(project_root)
    view = ResourceView(project_root)

    items, blocks, blockitems, translatables, unresolved = parse_registrations(project_root)
    findings: List[Finding] = []
    findings += check_entries(view, ns, items, blocks, blockitems)
    findings += check_model_references(view, ns)
    findings += check_lang_quality(view, ns, translatables)

    return print_report(
        project_root, ns,
        (len(items), len(blocks), len(blockitems), unresolved),
        findings,
        treat_warnings_as_errors=treat_w,
    )


if __name__ == "__main__":
    sys.exit(main())
