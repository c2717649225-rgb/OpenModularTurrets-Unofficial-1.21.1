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
             dangling model/blockstate reference), plus strict DataGen layout
             violations when --strict-datagen-layout is enabled
  warning -> quality gap that may be intentional or art-pending (missing
             loot table, mineable tag, texture PNG, zh_cn.json, translatable
             keys, en_us/zh_cn key-set drift)
"""
from __future__ import annotations

import json
import re
import sys

if sys.version_info < (3, 10):
    sys.stderr.write(
        f"[ERROR] Python 3.10 or higher is required to run asset_gate.py. "
        f"Current Python: {sys.version_info.major}.{sys.version_info.minor}\n"
        "Please use: python .agents/run.py ...\n"
    )
    sys.exit(1)

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

# Registration calls on a receiver (helper calls carry inherent kind; register checks receiver var kind).
REGISTER_CALL_RE = re.compile(
    r"\b(\w+)\s*\.\s*"
    r"(registerSimpleBlockItem|registerSimpleBlock|registerBlock|"
    r"registerSimpleItem|registerItem|register)"
    r"\s*\(\s*\"([a-z0-9_./-]+)\""
)
HELPER_KIND = {
    "registerSimpleBlockItem": "blockitem",
    "registerSimpleBlock": "block",
    "registerBlock": "block",
    "registerSimpleItem": "item",
    "registerItem": "item",
}

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

        for m in REGISTER_CALL_RE.finditer(text):
            var_name, method, name = m.group(1), m.group(2), m.group(3)
            kind = HELPER_KIND.get(method)
            if kind is None:  # plain .register("name", ...) -> check receiver var kind
                kind = var_kinds.get(var_name)
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

DATAGEN_ASSET_DIRS = {"blockstates", "models"}
DATAGEN_DATA_DIRS = {"advancement", "loot_table", "recipe"}
MANUALLY_MAINTAINED_LANG_FILES = {"zh_cn.json"}


def check_datagen_layout(project_root: Path, ns: str) -> List[Finding]:
    """Reject DataGen-managed JSON checked into src/main/resources.

    This is opt-in because existing host projects may still be migrating
    handwritten resources. The scan is intentionally narrow: it only covers
    well-known provider outputs, leaving metadata, textures, sounds, and other
    manually authored assets alone. Tags are checked across namespaces because
    a host mod commonly contributes generated entries to ``minecraft:``/``c:``.
    """
    main_root = project_root / "src" / "main" / "resources"
    if not main_root.is_dir():
        return []

    findings: List[Finding] = []
    for path in sorted(main_root.rglob("*.json")):
        if not path.is_file():
            continue
        rel = path.relative_to(main_root)
        parts = rel.parts
        managed = False

        if len(parts) >= 3 and parts[0] == "assets" and parts[1] == ns:
            managed = parts[2] in DATAGEN_ASSET_DIRS
            if parts[2] == "lang" and path.name not in MANUALLY_MAINTAINED_LANG_FILES:
                managed = True
        elif len(parts) >= 3 and parts[0] == "data":
            managed = parts[2] == "tags"
            if parts[1] == ns and parts[2] in DATAGEN_DATA_DIRS:
                managed = True

        if managed:
            subject = str(
                Path("src") / "main" / "resources" / rel
            ).replace("\\", "/")
            findings.append(Finding(
                "datagen_resource_in_main", "error", subject,
                "DataGen-managed JSON is under src/main/resources. Generate it "
                "with a DataProvider into src/generated/resources instead "
                "(zh_cn.json, metadata, textures, and other manual assets remain allowed).",
            ))
    return findings

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
            if t_ns == ns:
                if t_path.startswith("blocks/") or t_path.startswith("items/"):
                    add("legacy_plural_texture_reference", "error", model.name,
                        f"Legacy plural texture reference `{tex}` detected in model `{model.name}`. "
                        "1.21.1+ requires singular `block/` or `item/` path prefix.")
                elif not texture_exists(t_path):
                    add("missing_texture", "warning", model.name,
                        f"Texture `{tex}` missing (assets/{ns}/textures/{t_path}.png). "
                        "Add art or a placeholder PNG before release.")

    # Check for legacy 1.20.x plural texture directories: textures/blocks/ or textures/items/
    for plural_dir in ["blocks", "items"]:
        legacy_pngs = view.glob(f"assets/{ns}/textures/{plural_dir}/**/*.png")
        if legacy_pngs:
            add("legacy_plural_texture_directory", "warning", f"assets/{ns}/textures/{plural_dir}/",
                f"Legacy 1.20.x plural texture directory `textures/{plural_dir}/` detected ({len(legacy_pngs)} PNGs). "
                f"Block/item model textures should use singular `textures/{plural_dir[:-1]}/`. "
                "Move model textures and update their references; review manually loaded GUI/renderer textures before moving them.")

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
    zh_cn = load_lang(view, ns, "zh_cn")
    if zh_cn is None:
        findings.append(Finding(
            "missing_lang_file", "warning", f"assets/{ns}/lang/zh_cn.json",
            "zh_cn.json missing — quality bar expects bilingual coverage.",
        ))
    else:
        en_keys = {key for key in en_us if isinstance(key, str)}
        zh_keys = {key for key in zh_cn if isinstance(key, str)}

        def summarize(keys: Set[str]) -> str:
            ordered = sorted(keys)
            shown = ", ".join(f"`{key}`" for key in ordered[:8])
            if len(ordered) > 8:
                shown += f", ... (+{len(ordered) - 8} more)"
            return shown

        missing_zh = en_keys - zh_keys
        if missing_zh:
            findings.append(Finding(
                "lang_key_missing_zh_cn", "warning",
                f"assets/{ns}/lang/zh_cn.json",
                f"{len(missing_zh)} key(s) exist in en_us.json but not zh_cn.json: "
                f"{summarize(missing_zh)}.",
            ))

        missing_en = zh_keys - en_keys
        if missing_en:
            findings.append(Finding(
                "lang_key_missing_en_us", "warning",
                f"assets/{ns}/lang/en_us.json",
                f"{len(missing_en)} key(s) exist in zh_cn.json but not en_us.json: "
                f"{summarize(missing_en)}.",
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


def check_mods_toml(project_root: Path) -> List[Finding]:
    """
    Template-default metadata that would ship as-is (release-quality warnings).
    Line-scan on purpose: zero deps (tomllib is 3.11+; CI runs 3.10) and the
    file may contain ${gradle} placeholders that no TOML parser accepts.
    """
    findings: List[Finding] = []
    toml_path = None
    for rel in ("src/main/templates/META-INF/neoforge.mods.toml",
                "src/main/resources/META-INF/neoforge.mods.toml"):
        p = project_root / rel
        if p.is_file():
            toml_path = p
            break
    if toml_path is None:
        findings.append(Finding(
            "modstoml_missing", "warning", "META-INF/neoforge.mods.toml",
            "neoforge.mods.toml not found under templates/ or resources/.",
        ))
        return findings

    text = toml_path.read_text(encoding="utf-8", errors="replace")
    subject = str(toml_path.relative_to(project_root)).replace("\\", "/")
    active = [ln.strip() for ln in text.splitlines()
              if ln.strip() and not ln.strip().startswith("#")]

    if "Example mod description" in text:
        findings.append(Finding(
            "modstoml_template_default", "warning", subject,
            "description is still the template placeholder ('Example mod "
            "description') — this ships verbatim into the released jar.",
        ))
    if not any(re.match(r'authors\s*=\s*["\'].+["\']', ln) for ln in active):
        findings.append(Finding(
            "modstoml_template_default", "warning", subject,
            "no active authors=\"...\" entry — mod UI will show no author.",
        ))
    for ln in active:
        if re.search(r"(change\.me|example\.invalid)", ln):
            findings.append(Finding(
                "modstoml_template_default", "warning", subject,
                f"active line still points at a placeholder URL: {ln[:70]}",
            ))
    return findings


# ------------------------------------------------- visual integrity checks
# Added after the "expanders floating / turret surfaces mis-textured" incident
# class: three failure modes that compile fine, pass L1/L2, and only surface
# visually. See docs/porting/code-quality-remediation-plan.md Phase E notes.

ATTACHMENT_FAMILY_PREFIXES = ("expander_inv_", "expander_power_",
                              "base_addon_loot_deleter")
PLATE_SOURCE_JAVA = ("src/main/java/omtteam/openmodularturrets/block/"
                     "BaseAttachmentBlock.java")


def _plate_box_from_java(project_root: Path) -> Optional[Tuple[int, int, int, int, int, int]]:
    """Parse the NORTH plate dims from BaseAttachmentBlock.expanderShape.

    Single source of truth stays in Java; returns None when the source cannot
    be parsed (gate then skips geometry comparison instead of guessing).
    """
    p = project_root / PLATE_SOURCE_JAVA
    if not p.is_file():
        return None
    text = strip_comments(p.read_text(encoding="utf-8", errors="replace"))
    m = re.search(r"case\s+NORTH\s*->\s*box\(\s*([\d.\s,]+?)\)", text)
    if not m:
        return None
    nums = [int(float(part.strip())) for part in m.group(1).split(",")]
    if len(nums) != 6:
        return None
    return tuple(nums)


def _model_single_element_box(data: dict) -> Optional[Tuple[int, ...]]:
    elements = data.get("elements")
    if isinstance(elements, list) and len(elements) == 1 \
            and isinstance(elements[0], dict):
        try:
            return tuple(int(v) for v in
                         list(elements[0].get("from", [])) +
                         list(elements[0].get("to", [])))
        except (TypeError, ValueError):
            return None
    return None


def check_attachment_family(
    view: ResourceView, ns: str, project_root: Path,
) -> List[Finding]:
    """Geometry-vs-collision alignment and rotation-table drift for the
    six-way attachment family (expanders + loot deleter)."""
    findings: List[Finding] = []
    blocks_root = f"assets/{ns}/blockstates"
    names = sorted(
        bs.name[:-5] for bs in view.glob(f"{blocks_root}/*.json")
        if bs.name.startswith(ATTACHMENT_FAMILY_PREFIXES)
    )
    if len(names) < 2:
        return findings

    plate = _plate_box_from_java(project_root)
    reference_table: Optional[dict] = None
    reference_name = ""

    for name in names:
        model = view.load_json(f"assets/{ns}/models/block/{name}.json")
        state = view.load_json(f"{blocks_root}/{name}.json")
        if model is None or state is None:
            continue

        if plate is not None:
            box = _model_single_element_box(model)
            if box is not None and box != plate:
                subject = f"models/block/{name}.json"
                findings.append(Finding(
                    "attachment_model_geometry", "error", subject,
                    f"Element box {box} does not match the shared collision "
                    f"plate {plate} parsed from "
                    "BaseAttachmentBlock.expanderShape (NORTH). The rendered "
                    "plate would float off or clip into its base.",
                ))

        variants = state.get("variants") or {}
        table = {
            key.split("=", 1)[1]: (
                int(case.get("x", 0)), int(case.get("y", 0)))
            for key, case in variants.items() if isinstance(case, dict)
        }
        if reference_table is None:
            reference_table, reference_name = table, name
        elif table != reference_table:
            subject = f"blockstates/{name}.json"
            findings.append(Finding(
                "attachment_rotation_table_drift", "error", subject,
                f"Rotation table {table} differs from sibling "
                f"`{reference_name}` {reference_table}. A clockwise/"
                "counterclockwise mix shifts the plate along the face normal "
                "(east/west/up/down visibly detach from the base).",
            ))
    return findings


def _iter_render_java_files(project_root: Path) -> List[Path]:
    render_dir = project_root / "src/main/java/omtteam/openmodularturrets/client/render"
    if not render_dir.is_dir():
        return []
    return sorted(render_dir.glob("*.java"))


BOX_CALL_RE = re.compile(
    r'box\(\s*\w+\s*,\s*"([a-z0-9_]+)"\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*'
    r"-?[\d.F]+\s*,\s*-?[\d.F]+\s*,\s*-?[\d.F]+\s*,\s*"
    r"(-?[\d.F]+)\s*,\s*(-?[\d.F]+)\s*,\s*(-?[\d.F]+)"
)
TEXOFFS_ADDBOX_RE = re.compile(
    r"\.texOffs\((-?\d+)\s*,\s*(-?\d+)\)\s*\.\s*addBox\(([^)]*)\)", re.DOTALL
)


def _num(text: str) -> int:
    """int(float('2.002F')) -> 2; tolerates Java float suffix."""
    return abs(int(float(str(text).rstrip("F"))))


def check_programmatic_uv_overflow(
    project_root: Path, ns: str,
) -> List[Finding]:
    """Detect texOffs regions whose implied UV rect exceeds the texture size.

    Overflow never crashes — the GPU samples outside the atlas page and the
    part renders with garbled/wrong-surface pixels ("model right, skin
    wrong"). Two authoring styles are covered:
      - helper form:  box(parent, "name", u, v, x,y,z, w,h,d, ...) with a
        per-file TEXTURE_SIZE constant (default 64);
      - chain form:   .texOffs(u, v).addBox(x, y, z, w, h, d, ...) sized by
        LayerDefinition.create(mesh, W, H) in the same file.
    Non-numeric (computed) dimensions are counted as scanned-without-verdict;
    they can never produce a false positive here.
    """
    findings: List[Finding] = []

    for path in _iter_render_java_files(project_root):
        try:
            text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            continue
        if "texOffs" not in text and "box(" not in text:
            continue
        subject = str(path.relative_to(project_root)).replace("\\", "/")

        sizes = [(int(a), int(b)) for a, b in re.findall(
            r"LayerDefinition\.create\(\w+,\s*(\d+),\s*(\d+)\)", text)]

        # helper form: box(parent, "name", u, v, ...)
        tex_w = max((w for w, _ in sizes), default=64)
        tex_h = max((h for _, h in sizes), default=64)
        for m in BOX_CALL_RE.finditer(text):
            name, u, v = m.group(1), int(m.group(2)), int(m.group(3))
            w, h, d = (_num(m.group(i)) for i in (4, 5, 6))
            need_u, need_v = u + 2 * (w + d), v + d + h
            if need_u > tex_w or need_v > tex_h:
                findings.append(Finding(
                    "model_uv_overflow", "error", subject,
                    f'Part "{name}" texOffs({u},{v}) with size {w}x{h}x{d} '
                    f"needs UV up to u={need_u}, v={need_v} but the texture "
                    f"is {tex_w}x{tex_h} — surfaces will sample wrong pixels.",
                ))

        # chain form: .texOffs(u, v).addBox(...)
        for m in TEXOFFS_ADDBOX_RE.finditer(text):
            u, v = int(m.group(1)), int(m.group(2))
            args = [a.strip() for a in m.group(3).split(",")]
            if len(args) < 6:
                continue
            try:
                w, h, d = (_num(args[i]) for i in (3, 4, 5))
            except ValueError:
                continue  # computed size: no verdict possible, no false positive
            use_w, use_h = (sizes[-1] if sizes else (64, 64))
            need_u, need_v = u + 2 * (w + d), v + d + h
            if need_u > use_w or need_v > use_h:
                findings.append(Finding(
                    "model_uv_overflow", "error", subject,
                    f"addBox {w}x{h}x{d} at texOffs({u},{v}) needs UV up to "
                    f"u={need_u}, v={need_v} but LayerDefinition is "
                    f"{use_w}x{use_h} — garbled surface pixels.",
                ))
    return findings


HARDCODED_TEXTURE_RE = re.compile(
    r'"(?:textures/(?:block|item)/)?([a-z0-9_]+)\.png"'
)


def check_renderer_hardcoded_textures(
    project_root: Path, view: ResourceView, ns: str,
) -> List[Finding]:
    """PNG existence for renderer-hardcoded texture paths (BER overlays,
    projectile/crank textures). These bypass DataGen entirely."""
    findings: List[Finding] = []
    seen: Set[str] = set()
    for path in _iter_render_java_files(project_root):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for rel in HARDCODED_TEXTURE_RE.findall(text):
            if rel in seen:
                continue
            seen.add(rel)
            # Heuristic: renderer textures live under block/ unless the string
            # already carries an item/ prefix.
            for candidate in (f"block/{rel}", rel):
                if view.find(f"assets/{ns}/textures/{candidate}.png") is not None:
                    break
            else:
                subject = str(path.relative_to(project_root)).replace("\\", "/")
                findings.append(Finding(
                    "renderer_texture_missing", "error", subject,
                    f'Hardcoded texture "{rel}.png" not found under '
                    f"assets/{ns}/textures/block/ — renders as missing texture.",
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
    strict_datagen_layout: bool = False,
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
    print(f"Strict DataGen layout: {'enabled' if strict_datagen_layout else 'disabled'}")

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
    strict_layout = "--strict-datagen-layout" in argv

    project_root = find_project_root()
    ns = read_mod_id(project_root)
    view = ResourceView(project_root)

    items, blocks, blockitems, translatables, unresolved = parse_registrations(project_root)
    findings: List[Finding] = []
    findings += check_entries(view, ns, items, blocks, blockitems)
    findings += check_model_references(view, ns)
    findings += check_lang_quality(view, ns, translatables)
    findings += check_mods_toml(project_root)
    findings += check_attachment_family(view, ns, project_root)
    findings += check_programmatic_uv_overflow(project_root, ns)
    findings += check_renderer_hardcoded_textures(project_root, view, ns)
    if strict_layout:
        findings += check_datagen_layout(project_root, ns)

    return print_report(
        project_root, ns,
        (len(items), len(blocks), len(blockitems), unresolved),
        findings,
        treat_warnings_as_errors=treat_w,
        strict_datagen_layout=strict_layout,
    )


if __name__ == "__main__":
    sys.exit(main())
