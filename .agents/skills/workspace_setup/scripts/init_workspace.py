#!/usr/bin/env python3
"""
Host workspace rename / align engine for NeoForge projects.

Usage:
  python init_workspace.py           # apply changes (requires clean git unless --force)
  python init_workspace.py --dry-run # print planned actions only
  python init_workspace.py --force   # apply even if git working tree is dirty
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, Set, Tuple

SYSTEM_NAMESPACES = {"minecraft", "c", "neoforge", "forge"}
MOD_ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]{1,63}$")
JAVA_PACKAGE_SEGMENT_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*$")
JAVA_RESERVED_WORDS = {
    "_",
    "abstract",
    "assert",
    "boolean",
    "break",
    "byte",
    "case",
    "catch",
    "char",
    "class",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "exports",
    "extends",
    "false",
    "final",
    "finally",
    "float",
    "for",
    "goto",
    "if",
    "implements",
    "import",
    "instanceof",
    "int",
    "interface",
    "long",
    "module",
    "native",
    "new",
    "non-sealed",
    "null",
    "open",
    "opens",
    "package",
    "permits",
    "private",
    "protected",
    "provides",
    "public",
    "record",
    "requires",
    "return",
    "sealed",
    "short",
    "static",
    "strictfp",
    "super",
    "switch",
    "synchronized",
    "this",
    "throw",
    "throws",
    "to",
    "transient",
    "transitive",
    "true",
    "try",
    "uses",
    "var",
    "void",
    "volatile",
    "while",
    "with",
    "yield",
}

# These fingerprints identify only the starter implementation shipped with
# this toolkit.  A changed file is preserved and minimal mode fails closed if
# removing the starter symbols would leave it uncompilable.
STARTER_JAVA_FINGERPRINTS = {
    "TutorialMod.java": "99ed54968c5112984847c95ebba3b9f63888eed6d4c44b025b8f59d609e3b7bc",
    "Config.java": "323c065689c14a5968693661842a7ebf4c8502a3cfb5ead04ce87ee0c9afe217",
    "datagen/ModBlockLootSubProvider.java": "3db93eef68662e7d03e7df3c6d3e3103f526549c1069c3626b40bfa81af82b18",
    "datagen/ModBlockStateProvider.java": "6e165d557809dd13806e63150f942d20723c9081b0ea5248cf45a8a37cd72e90",
    "datagen/ModBlockTagProvider.java": "11357cc6866e9d34cabad8fa69cb57cd3243f08dfd775a75659f165d88c6fc17",
    "datagen/ModDataGenerators.java": "e2dee7f18a4dee853124c9e4348e47e3d41815430953feb461f3f14eaef000a0",
    "datagen/ModItemModelProvider.java": "de0bd6999b980190398c29c4e129824b0c04400403e8a401f9a76d74b6c078a2",
    "datagen/ModLanguageProvider.java": "f52ab357222f610c9123aac6f5a8ae9192c6df2cc9d26ac71c6c78c58f9d38e8",
    "datagen/ModRecipeProvider.java": "b66dd7cef4d80892879208a499bcbc07e625855bca3308d9a1c0026dc8e09c91",
}
STARTER_MAIN_SYMBOLS = {
    "BLOCKS",
    "ITEMS",
    "CREATIVE_MODE_TABS",
    "EXAMPLE_BLOCK",
    "EXAMPLE_BLOCK_ITEM",
    "EXAMPLE_ITEM",
    "EXAMPLE_TAB",
}
STARTER_GENERATED_PATHS = (
    "assets/{modid}/blockstates/example_block.json",
    "assets/{modid}/models/block/example_block.json",
    "assets/{modid}/models/item/example_block.json",
    "assets/{modid}/models/item/example_item.json",
    "data/{modid}/advancement/recipes/building_blocks/example_block.json",
    "data/{modid}/advancement/recipes/food/example_item.json",
    "data/{modid}/loot_table/blocks/example_block.json",
    "data/{modid}/recipe/example_block.json",
    "data/{modid}/recipe/example_item.json",
)


class WorkspaceSafetyError(ValueError):
    """A fail-closed workspace mutation or metadata error."""


@dataclass(frozen=True)
class PlannedFileMutation:
    path: Path
    root: Path
    label: str
    content: Optional[str] = None


@dataclass
class MinimalProfilePlan:
    actions: List[str] = field(default_factory=list)
    updates: List[PlannedFileMutation] = field(default_factory=list)
    deletes: List[PlannedFileMutation] = field(default_factory=list)

    @property
    def active(self) -> bool:
        return bool(self.updates or self.deletes)


def _reconfigure_stdio() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def project_paths() -> Tuple[Path, Path]:
    script_dir = Path(__file__).resolve().parent
    project_dir = script_dir.parent.parent.parent.parent
    agents_dir = project_dir / ".agents"
    return project_dir, agents_dir


def parse_props(path: Path) -> dict:
    props = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def validate_mod_id(mod_id: str) -> str:
    if not MOD_ID_PATTERN.fullmatch(mod_id):
        raise WorkspaceSafetyError(
            "mod_id must match [a-z][a-z0-9_]{1,63}; "
            f"got {mod_id!r}"
        )
    return mod_id


def validate_java_package(package_name: str) -> Tuple[str, ...]:
    if not package_name or "/" in package_name or "\\" in package_name:
        raise WorkspaceSafetyError(
            "mod_group_id must be a dotted Java package without path separators"
        )
    segments = package_name.split(".")
    if any(
        not JAVA_PACKAGE_SEGMENT_PATTERN.fullmatch(segment)
        or segment in JAVA_RESERVED_WORDS
        for segment in segments
    ):
        raise WorkspaceSafetyError(
            "mod_group_id contains an invalid or reserved Java package segment: "
            f"{package_name!r}"
        )
    return tuple(segments)


def require_path_within(
    path: Path,
    root: Path,
    *,
    label: str,
    allow_root: bool = False,
) -> Path:
    """Resolve a mutation path and reject traversal and symlink ambiguity."""
    raw_root = Path(os.path.abspath(root))
    raw_path = Path(os.path.abspath(path))
    try:
        resolved_root = raw_root.resolve(strict=False)
        resolved_path = raw_path.resolve(strict=False)
    except (OSError, RuntimeError) as error:
        raise WorkspaceSafetyError(f"{label} could not be resolved: {error}") from error

    try:
        resolved_path.relative_to(resolved_root)
    except ValueError as error:
        raise WorkspaceSafetyError(
            f"{label} escapes its allowed root: {path} (root: {root})"
        ) from error
    if resolved_path == resolved_root and not allow_root:
        raise WorkspaceSafetyError(
            f"{label} must not target the allowed root itself: {root}"
        )

    if raw_root.is_symlink():
        raise WorkspaceSafetyError(
            f"{label} allowed root must not be a symlink: {raw_root}"
        )
    current = raw_path
    while True:
        if current.exists() and current.is_symlink():
            raise WorkspaceSafetyError(
                f"{label} crosses a symlink and is not safe to mutate: {current}"
            )
        if current == raw_root:
            break
        if current.parent == current:
            break
        current = current.parent
    return resolved_path


def _safe_unlink(path: Path, root: Path, *, label: str) -> None:
    resolved = require_path_within(path, root, label=label)
    if resolved.exists():
        if not resolved.is_file():
            raise WorkspaceSafetyError(f"{label} is not a regular file: {resolved}")
        resolved.unlink()


def _safe_rmtree(path: Path, root: Path, *, label: str) -> None:
    resolved = require_path_within(path, root, label=label)
    if resolved.exists():
        if not resolved.is_dir():
            raise WorkspaceSafetyError(f"{label} is not a directory: {resolved}")
        shutil.rmtree(resolved)


def _prune_empty_parents(path: Path, root: Path) -> None:
    resolved_root = require_path_within(
        root, root, label="empty-directory prune root", allow_root=True
    )
    current = require_path_within(
        path, resolved_root, label="empty-directory prune candidate"
    )
    while current != resolved_root and current.is_dir():
        try:
            if any(current.iterdir()):
                break
            current.rmdir()
        except OSError:
            break
        current = current.parent


def _strict_json_object(pairs: List[Tuple[str, object]]) -> Dict[str, object]:
    result: Dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise WorkspaceSafetyError(f"duplicate JSON object key {key!r}")
        result[key] = value
    return result


def _load_json_object(path: Path) -> Dict[str, object]:
    try:
        parsed = json.loads(
            path.read_text(encoding="utf-8", errors="strict"),
            object_pairs_hook=_strict_json_object,
            parse_constant=lambda value: (_ for _ in ()).throw(
                WorkspaceSafetyError(f"non-standard JSON number {value!r}")
            ),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise WorkspaceSafetyError(f"cannot read JSON {path}: {error}") from error
    if not isinstance(parsed, dict):
        raise WorkspaceSafetyError(f"JSON root must be an object: {path}")
    return parsed


def _normalized_starter_java(
    content: str,
    package_name: str,
    mod_ids: List[str],
) -> str:
    normalized = content.replace("\r\n", "\n").replace("\r", "\n")
    normalized = normalized.replace(package_name, "{{PACKAGE}}")
    for mod_id in sorted(set(mod_ids), key=len, reverse=True):
        if mod_id:
            normalized = re.sub(
                rf"\b{re.escape(mod_id)}\b",
                "{{MODID}}",
                normalized,
            )
    return normalized


def _starter_java_digest(
    path: Path,
    package_name: str,
    mod_ids: List[str],
) -> str:
    content = path.read_text(encoding="utf-8", errors="strict")
    normalized = _normalized_starter_java(content, package_name, mod_ids)
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _minimal_main_source(
    package_name: str,
    class_name: str,
    mod_id: str,
) -> str:
    return f"""package {package_name};

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod({class_name}.MODID)
public class {class_name} {{
    public static final String MODID = "{mod_id}";
    public static final Logger LOGGER = LogUtils.getLogger();

    public {class_name}(IEventBus modEventBus, ModContainer modContainer) {{
        // Register feature systems here. The minimal profile intentionally
        // contains no tutorial registrations or example behavior.
    }}
}}
"""


def _starter_lang_keys(mod_ids: List[str]) -> Set[str]:
    keys: Set[str] = set()
    for mod_id in mod_ids:
        if not mod_id:
            continue
        keys.update(
            {
                f"itemGroup.{mod_id}",
                f"block.{mod_id}.example_block",
                f"item.{mod_id}.example_block",
                f"item.{mod_id}.example_item",
                f"{mod_id}.configuration.title",
                f"{mod_id}.configuration.section.{mod_id}.common.toml",
                f"{mod_id}.configuration.section.{mod_id}.common.toml.title",
                f"{mod_id}.configuration.items",
                f"{mod_id}.configuration.logDirtBlock",
                f"{mod_id}.configuration.magicNumberIntroduction",
                f"{mod_id}.configuration.magicNumber",
            }
        )
    return keys


def _plan_update(
    plan: MinimalProfilePlan,
    path: Path,
    root: Path,
    label: str,
    content: str,
) -> None:
    require_path_within(path, root, label=label)
    if path.is_file() and path.read_text(
        encoding="utf-8", errors="strict"
    ) == content:
        return
    plan.updates.append(PlannedFileMutation(path, root, label, content))
    plan.actions.append(f"[Minimal Profile] {label}")


def _plan_delete(
    plan: MinimalProfilePlan,
    path: Path,
    root: Path,
    label: str,
) -> None:
    require_path_within(path, root, label=label)
    if not path.exists():
        return
    if not path.is_file():
        raise WorkspaceSafetyError(f"{label} is not a regular file: {path}")
    plan.deletes.append(PlannedFileMutation(path, root, label))
    plan.actions.append(f"[Minimal Profile] {label}")


def build_minimal_profile_plan(
    *,
    project_dir: Path,
    java_root: Path,
    resources_dir: Path,
    generated_root: Path,
    package_dir: Path,
    package_name: str,
    main_class_path: Path,
    mod_id: str,
    old_ids: List[str],
) -> MinimalProfilePlan:
    """Plan a conservative starter removal without touching user packages."""
    plan = MinimalProfilePlan()
    require_path_within(main_class_path, java_root, label="minimal main class")
    require_path_within(package_dir, java_root, label="minimal package directory")
    if not main_class_path.is_file():
        raise WorkspaceSafetyError(
            "minimal profile requires a source-backed @Mod main class"
        )

    mod_ids = list(dict.fromkeys([*old_ids, mod_id]))
    main_content = main_class_path.read_text(
        encoding="utf-8", errors="strict"
    )
    try:
        main_relative = main_class_path.relative_to(package_dir).as_posix()
    except ValueError as error:
        raise WorkspaceSafetyError(
            "the @Mod main class path does not match its declared package: "
            f"{main_class_path} vs {package_dir}"
        ) from error
    main_expected = STARTER_JAVA_FINGERPRINTS.get(main_relative)
    main_digest = _starter_java_digest(
        main_class_path, package_name, mod_ids
    )
    has_starter_main_symbols = any(
        re.search(rf"\b{re.escape(symbol)}\b", main_content)
        for symbol in STARTER_MAIN_SYMBOLS
    ) or "Config.SPEC" in main_content

    if has_starter_main_symbols and main_digest != main_expected:
        raise WorkspaceSafetyError(
            "minimal profile found modified starter registrations in "
            f"{main_class_path}; refusing to rewrite user-modified code"
        )
    if main_digest == main_expected:
        class_match = re.search(
            r"\bpublic\s+class\s+([A-Za-z][A-Za-z0-9_]*)\b",
            main_content,
        )
        if class_match is None:
            raise WorkspaceSafetyError(
                f"cannot identify starter main class in {main_class_path}"
            )
        current_mod_id_match = re.search(
            r'public\s+static\s+final\s+String\s+MODID\s*=\s*"([^"]+)"',
            main_content,
        )
        if current_mod_id_match is None:
            raise WorkspaceSafetyError(
                f"cannot identify starter MODID in {main_class_path}"
            )
        _plan_update(
            plan,
            main_class_path,
            java_root,
            f"rewrite {main_relative} as the compile-safe minimal entrypoint",
            _minimal_main_source(
                package_name,
                class_match.group(1),
                current_mod_id_match.group(1),
            ),
        )

    for relative, expected_digest in STARTER_JAVA_FINGERPRINTS.items():
        if relative == main_relative:
            continue
        candidate = package_dir / Path(relative)
        require_path_within(
            candidate,
            java_root,
            label=f"minimal starter source {relative}",
        )
        if not candidate.is_file():
            continue
        try:
            digest = _starter_java_digest(candidate, package_name, mod_ids)
        except (OSError, UnicodeError) as error:
            raise WorkspaceSafetyError(
                f"cannot inspect starter source {candidate}: {error}"
            ) from error
        if digest == expected_digest:
            _plan_delete(
                plan,
                candidate,
                java_root,
                f"remove recognized starter source {relative}",
            )

    if not plan.active:
        return plan

    deleted_paths = {
        mutation.path.resolve(strict=False) for mutation in plan.deletes
    }
    updated_paths = {
        mutation.path.resolve(strict=False) for mutation in plan.updates
    }
    deleted_classes = {
        mutation.path.stem
        for mutation in plan.deletes
        if mutation.path.suffix == ".java"
    }
    removed_symbols = "|".join(
        re.escape(symbol) for symbol in sorted(STARTER_MAIN_SYMBOLS)
    )
    deleted_class_pattern = (
        re.compile(
            r"\b(?:" + "|".join(
                re.escape(name) for name in sorted(deleted_classes)
            ) + r")\b"
        )
        if deleted_classes
        else None
    )
    dangling: List[str] = []
    for java_file in sorted(java_root.rglob("*.java")):
        resolved = require_path_within(
            java_file, java_root, label="minimal residual Java source"
        )
        if resolved in deleted_paths or resolved in updated_paths:
            continue
        content = java_file.read_text(encoding="utf-8", errors="strict")
        if re.search(
            rf"\b[A-Za-z][A-Za-z0-9_]*\s*\.\s*(?:{removed_symbols})\b",
            content,
        ):
            dangling.append(
                f"{java_file.relative_to(project_dir).as_posix()}: "
                "references a removed starter registration"
            )
        if deleted_class_pattern and deleted_class_pattern.search(content):
            dangling.append(
                f"{java_file.relative_to(project_dir).as_posix()}: "
                "references a removed starter class"
            )
    if dangling:
        raise WorkspaceSafetyError(
            "minimal profile would leave dangling Java references:\n  - "
            + "\n  - ".join(sorted(set(dangling)))
        )

    namespace_ids = [
        namespace
        for namespace in mod_ids
        if namespace and namespace not in SYSTEM_NAMESPACES
    ]
    starter_keys = _starter_lang_keys(namespace_ids)
    for resource_root in (resources_dir, generated_root):
        assets_root = resource_root / "assets"
        for namespace in namespace_ids:
            for locale in ("en_us", "zh_cn"):
                lang_path = assets_root / namespace / "lang" / f"{locale}.json"
                require_path_within(
                    lang_path,
                    resource_root,
                    label=f"minimal language file {locale}",
                )
                if not lang_path.is_file():
                    continue
                lang_data = _load_json_object(lang_path)
                cleaned = {
                    key: value
                    for key, value in lang_data.items()
                    if key not in starter_keys
                }
                if cleaned == lang_data:
                    continue
                if cleaned:
                    rendered_lang = (
                        json.dumps(
                            cleaned,
                            indent=2,
                            ensure_ascii=False,
                        )
                        + "\n"
                    )
                    rendered_lang = replace_in_text(
                        rendered_lang,
                        mod_ids,
                        mod_id,
                    )
                    _plan_update(
                        plan,
                        lang_path,
                        resource_root,
                        (
                            "remove starter translation keys from "
                            f"{lang_path.relative_to(project_dir).as_posix()}"
                        ),
                        rendered_lang,
                    )
                else:
                    _plan_delete(
                        plan,
                        lang_path,
                        resource_root,
                        (
                            "remove empty starter language file "
                            f"{lang_path.relative_to(project_dir).as_posix()}"
                        ),
                    )

    affected_generated: Set[str] = set()
    for namespace in namespace_ids:
        for template in STARTER_GENERATED_PATHS:
            relative = template.format(modid=namespace)
            path = generated_root / Path(relative)
            if path.is_file():
                _plan_delete(
                    plan,
                    path,
                    generated_root,
                    f"remove starter generated resource {relative}",
                )
                affected_generated.add(relative)

    tag_path = (
        generated_root
        / "data"
        / "minecraft"
        / "tags"
        / "block"
        / "mineable"
        / "pickaxe.json"
    )
    require_path_within(
        tag_path, generated_root, label="minimal generated pickaxe tag"
    )
    if tag_path.is_file():
        tag_data = _load_json_object(tag_path)
        values = tag_data.get("values")
        if isinstance(values, list):
            removed_values = {
                f"{namespace}:example_block" for namespace in namespace_ids
            }
            cleaned_values = [
                value for value in values if value not in removed_values
            ]
            if cleaned_values != values:
                tag_relative = tag_path.relative_to(generated_root).as_posix()
                affected_generated.add(tag_relative)
                cleaned_tag = dict(tag_data)
                cleaned_tag["values"] = cleaned_values
                if not cleaned_values and set(cleaned_tag) <= {
                    "replace",
                    "values",
                }:
                    _plan_delete(
                        plan,
                        tag_path,
                        generated_root,
                        f"remove empty starter generated tag {tag_relative}",
                    )
                else:
                    rendered_tag = (
                        json.dumps(
                            cleaned_tag,
                            indent=2,
                            ensure_ascii=False,
                        )
                        + "\n"
                    )
                    rendered_tag = replace_in_text(
                        rendered_tag,
                        mod_ids,
                        mod_id,
                    )
                    _plan_update(
                        plan,
                        tag_path,
                        generated_root,
                        f"remove starter entry from generated tag {tag_relative}",
                        rendered_tag,
                    )

    for mutation in [*plan.updates, *plan.deletes]:
        try:
            affected_generated.add(
                mutation.path.relative_to(generated_root).as_posix()
            )
        except ValueError:
            pass

    cache_root = generated_root / ".cache"
    if cache_root.is_dir() and affected_generated:
        for cache_file in sorted(cache_root.iterdir()):
            require_path_within(
                cache_file,
                generated_root,
                label="minimal generated cache entry",
            )
            if not cache_file.is_file():
                continue
            lines = cache_file.read_text(
                encoding="utf-8", errors="strict"
            ).splitlines()
            kept = []
            changed = False
            for line in lines:
                parts = line.split(maxsplit=1)
                if len(parts) == 2 and parts[1] in affected_generated:
                    changed = True
                    continue
                kept.append(line)
            if not changed:
                continue
            if not any(
                line and not line.lstrip().startswith("//") for line in kept
            ):
                _plan_delete(
                    plan,
                    cache_file,
                    generated_root,
                    (
                        "remove empty generated cache "
                        f"{cache_file.relative_to(project_dir).as_posix()}"
                    ),
                )
            else:
                cache_content = replace_in_text(
                    "\n".join(kept) + "\n",
                    mod_ids,
                    mod_id,
                )
                _plan_update(
                    plan,
                    cache_file,
                    generated_root,
                    (
                        "remove starter outputs from generated cache "
                        f"{cache_file.relative_to(project_dir).as_posix()}"
                    ),
                    cache_content,
                )
    return plan


def apply_minimal_profile_plan(
    plan: MinimalProfilePlan,
    *,
    log: List[str],
    dry_run: bool,
) -> None:
    log.extend(plan.actions)
    if dry_run or not plan.active:
        return
    for mutation in plan.updates:
        resolved = require_path_within(
            mutation.path,
            mutation.root,
            label=mutation.label,
        )
        if mutation.content is None:
            raise WorkspaceSafetyError(
                f"planned update has no content: {mutation.label}"
            )
        resolved.parent.mkdir(parents=True, exist_ok=True)
        resolved.write_text(mutation.content, encoding="utf-8")
    for mutation in sorted(
        plan.deletes,
        key=lambda item: len(item.path.parts),
        reverse=True,
    ):
        _safe_unlink(mutation.path, mutation.root, label=mutation.label)


def git_dirty(project_dir: Path) -> bool:
    try:
        r = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=str(project_dir),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if r.returncode != 0:
            return False
        return bool(r.stdout.strip())
    except OSError:
        return False


def merge_or_move(
    old_path: Path,
    new_path: Path,
    label: str,
    log: List[str],
    dry_run: bool,
    *,
    allowed_root: Path,
) -> None:
    old_path = require_path_within(
        old_path, allowed_root, label=f"{label} source"
    )
    new_path = require_path_within(
        new_path, allowed_root, label=f"{label} destination"
    )
    if not old_path.exists():
        return
    if old_path == new_path:
        return
    if new_path.exists():
        if not old_path.is_dir() or not new_path.is_dir():
            raise WorkspaceSafetyError(
                f"{label} merge requires two directories: "
                f"{old_path} -> {new_path}"
            )
        pending: List[Tuple[Path, Path]] = []
        for root, _, files in os.walk(old_path):
            for file_name in files:
                src_file = require_path_within(
                    Path(root) / file_name,
                    allowed_root,
                    label=f"{label} merge source file",
                )
                dest_file = require_path_within(
                    new_path / src_file.relative_to(old_path),
                    allowed_root,
                    label=f"{label} merge destination file",
                )
                if (
                    dest_file.exists()
                    and dest_file.read_bytes() != src_file.read_bytes()
                ):
                    raise WorkspaceSafetyError(
                        f"{label} would overwrite a different file: {dest_file}"
                    )
                pending.append((src_file, dest_file))
        old_relative = old_path.relative_to(allowed_root.resolve()).as_posix()
        new_relative = new_path.relative_to(allowed_root.resolve()).as_posix()
        log.append(f"[{label}] merge {old_relative} -> {new_relative}")
        if dry_run:
            return
        for src_file, dest_file in pending:
            dest_file.parent.mkdir(parents=True, exist_ok=True)
            if not dest_file.exists():
                shutil.copy2(src_file, dest_file)
        _safe_rmtree(old_path, allowed_root, label=f"{label} merged source")
    else:
        old_relative = old_path.relative_to(allowed_root.resolve()).as_posix()
        new_relative = new_path.relative_to(allowed_root.resolve()).as_posix()
        log.append(f"[{label}] rename {old_relative} -> {new_relative}")
        if not dry_run:
            new_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(old_path), str(new_path))


def replace_in_text(content: str, old_ids: List[str], mod_id: str) -> str:
    out = content
    for oid in old_ids:
        if oid and oid != mod_id:
            out = re.sub(rf"\b{re.escape(oid)}\b", mod_id, out)
    return out


def _mask_java_non_code(content: str) -> str:
    """Preserve Java token positions while hiding comments and literals."""
    masked = list(content)
    index = 0
    state = "code"
    while index < len(content):
        current = content[index]
        following = content[index + 1] if index + 1 < len(content) else ""
        third = content[index + 2] if index + 2 < len(content) else ""

        if state == "code":
            if current == "/" and following == "/":
                masked[index] = masked[index + 1] = " "
                index += 2
                state = "line_comment"
                continue
            if current == "/" and following == "*":
                masked[index] = masked[index + 1] = " "
                index += 2
                state = "block_comment"
                continue
            if current == '"' and following == '"' and third == '"':
                masked[index] = masked[index + 1] = masked[index + 2] = " "
                index += 3
                state = "text_block"
                continue
            if current == '"':
                masked[index] = " "
                index += 1
                state = "string"
                continue
            if current == "'":
                masked[index] = " "
                index += 1
                state = "character"
                continue
            index += 1
            continue

        if state == "line_comment":
            if current in "\r\n":
                state = "code"
            else:
                masked[index] = " "
            index += 1
            continue

        if state == "block_comment":
            if current == "*" and following == "/":
                masked[index] = masked[index + 1] = " "
                index += 2
                state = "code"
            else:
                if current not in "\r\n":
                    masked[index] = " "
                index += 1
            continue

        if state == "text_block":
            if current == '"' and following == '"' and third == '"':
                masked[index] = masked[index + 1] = masked[index + 2] = " "
                index += 3
                state = "code"
            else:
                if current not in "\r\n":
                    masked[index] = " "
                index += 1
            continue

        if current == "\\" and index + 1 < len(content):
            masked[index] = " "
            if content[index + 1] not in "\r\n":
                masked[index + 1] = " "
            index += 2
            continue
        if (
            (state == "string" and current == '"')
            or (state == "character" and current == "'")
        ):
            masked[index] = " "
            index += 1
            state = "code"
            continue
        if current not in "\r\n":
            masked[index] = " "
        index += 1
    return "".join(masked)


def align_gametest_holder_literals(
    content: str,
    old_ids: List[str],
    mod_id: str,
) -> str:
    """Align only the namespace literal owned by official GameTest holders."""
    masked = _mask_java_non_code(content)
    has_official_import = re.search(
        (
            r"(?m)^\s*import\s+"
            r"net\.neoforged\.neoforge\.gametest\.GameTestHolder\s*;"
        ),
        masked,
    ) is not None
    token = re.compile(
        (
            r"@(?:(?P<qualified>"
            r"net\.neoforged\.neoforge\.gametest\.)?"
            r"GameTestHolder)\b"
        )
    )
    annotation = re.compile(
        (
            r"@(?:net\.neoforged\.neoforge\.gametest\.)?"
            r"GameTestHolder\s*\(\s*(?:value\s*=\s*)?"
            r'"(?P<namespace>[a-z0-9_.-]+)"\s*\)'
        ),
        re.DOTALL,
    )
    replaceable = {
        old_id for old_id in old_ids if old_id and old_id != mod_id
    }
    replacements: List[Tuple[int, int]] = []
    for match in token.finditer(masked):
        if match.group("qualified") is None and not has_official_import:
            continue
        declaration = annotation.match(content, match.start())
        if declaration is None:
            continue
        namespace = declaration.group("namespace")
        if namespace not in replaceable:
            continue
        replacements.append(declaration.span("namespace"))

    updated = content
    for start, end in reversed(replacements):
        updated = updated[:start] + mod_id + updated[end:]
    return updated


def align_language_resources(
    assets_dir: Path,
    resources_dir: Path,
    old_ids: List[str],
    mod_id: str,
    log: List[str],
    dry_run: bool,
    *,
    planned_contents: Optional[Dict[Path, Optional[str]]] = None,
) -> int:
    """Align language keys before namespace moves so dry-run matches apply."""
    if not assets_dir.is_dir():
        return 0

    overrides = {
        path.resolve(strict=False): content
        for path, content in (planned_contents or {}).items()
    }
    by_destination: Dict[str, List[Path]] = {}
    for namespace_dir in sorted(assets_dir.iterdir()):
        if (
            not namespace_dir.is_dir()
            or namespace_dir.name in SYSTEM_NAMESPACES
        ):
            continue
        lang_dir = namespace_dir / "lang"
        if not lang_dir.is_dir():
            continue
        for language_path in sorted(lang_dir.glob("*.json")):
            language_path = require_path_within(
                language_path,
                resources_dir,
                label="language namespace alignment",
            )
            if (
                language_path in overrides
                and overrides[language_path] is None
            ):
                continue
            by_destination.setdefault(language_path.name, []).append(
                language_path
            )

    changed = 0
    for file_name, source_paths in sorted(by_destination.items()):
        rendered_by_source: List[Tuple[Path, str, bool]] = []
        skipped = False
        for source_path in source_paths:
            try:
                source_content = (
                    overrides[source_path]
                    if source_path in overrides
                    else source_path.read_text(encoding="utf-8")
                )
                if source_content is None:
                    continue
                language_data = json.loads(
                    source_content
                )
                if not isinstance(language_data, dict):
                    raise ValueError("language JSON root must be an object")
            except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
                log.append(f"[Language JSON] skip {file_name}: {error}")
                skipped = True
                break

            aligned: Dict[str, object] = {}
            source_changed = False
            for key, value in language_data.items():
                new_key = replace_in_text(key, old_ids, mod_id)
                if new_key in aligned and new_key != key:
                    raise WorkspaceSafetyError(
                        "language namespace alignment would collapse two keys "
                        f"onto {new_key!r} in {source_path}"
                    )
                source_changed = source_changed or new_key != key
                aligned[new_key] = value
            rendered = (
                json.dumps(aligned, indent=2, ensure_ascii=False) + "\n"
            )
            rendered_by_source.append(
                (source_path, rendered, source_changed)
            )
        if skipped:
            continue

        distinct_outputs = {
            rendered for _, rendered, _ in rendered_by_source
        }
        if len(distinct_outputs) > 1:
            sources = ", ".join(str(path) for path in source_paths)
            raise WorkspaceSafetyError(
                "language namespace merge would overwrite different "
                f"{file_name} files: {sources}"
            )
        changed_sources = [
            (path, rendered)
            for path, rendered, source_changed in rendered_by_source
            if source_changed
        ]
        if not changed_sources:
            continue
        changed += 1
        log.append(f"[Language JSON] align namespaces in {file_name}")
        if not dry_run:
            for path, rendered in changed_sources:
                path.write_text(rendered, encoding="utf-8")
    return changed


def align_generated_resource_contents(
    generated_root: Path,
    old_ids: List[str],
    mod_id: str,
    log: List[str],
    dry_run: bool,
    *,
    excluded_paths: Optional[Set[Path]] = None,
) -> int:
    """Align namespace references inside generated JSON after a Mod ID rename."""
    changed = 0
    if not generated_root.is_dir():
        return changed

    excluded = {
        path.resolve(strict=False) for path in (excluded_paths or set())
    }
    for path in sorted(generated_root.rglob("*.json")):
        resolved_path = require_path_within(
            path,
            generated_root,
            label="generated resource content",
        )
        if resolved_path in excluded:
            continue
        try:
            content = resolved_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        updated = replace_in_text(content, old_ids, mod_id)
        if updated == content:
            continue
        changed += 1
        if not dry_run:
            resolved_path.write_text(updated, encoding="utf-8")

    if changed:
        log.append(
            f"[Generated Content] align namespaces in {changed} JSON file(s)"
        )
    return changed


def align_generated_cache_contents(
    generated_root: Path,
    old_ids: List[str],
    mod_id: str,
    log: List[str],
    dry_run: bool,
    *,
    excluded_paths: Optional[Set[Path]] = None,
) -> int:
    """Align DataGen cache comments and output paths after namespace moves."""
    cache_root = generated_root / ".cache"
    require_path_within(
        cache_root,
        generated_root,
        label="generated cache root",
    )
    if not cache_root.is_dir():
        return 0

    excluded = {
        path.resolve(strict=False) for path in (excluded_paths or set())
    }
    changed = 0
    for cache_file in sorted(cache_root.iterdir()):
        cache_file = require_path_within(
            cache_file,
            generated_root,
            label="generated cache namespace alignment",
        )
        if not cache_file.is_file():
            continue
        if cache_file in excluded:
            continue
        try:
            content = cache_file.read_text(
                encoding="utf-8",
                errors="strict",
            )
        except (OSError, UnicodeError):
            continue
        updated = replace_in_text(content, old_ids, mod_id)
        if updated == content:
            continue
        changed += 1
        if not dry_run:
            cache_file.write_text(updated, encoding="utf-8")
    if changed:
        log.append(
            "[Generated Cache] align namespaces in "
            f"{changed} cache file(s)"
        )
    return changed


def _parse_args(argv: List[str]) -> argparse.Namespace:
    # Keep the historical bare ``minimal`` spelling as a compatibility alias.
    if argv == ["minimal"] or (
        len(argv) == 2 and argv[0] in {"--dry-run", "--force"}
        and argv[1] == "minimal"
    ):
        prefix = argv[:-1]
        argv = [*prefix, "--profile", "minimal"]
    parser = argparse.ArgumentParser(
        description="Safely align a NeoForge host workspace."
    )
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--force", action="store_true")
    parser.add_argument(
        "--profile",
        choices=("example", "minimal"),
        default="example",
    )
    return parser.parse_args(argv)


def _run_main(argv: Optional[List[str]] = None) -> int:
    _reconfigure_stdio()
    argv = list(sys.argv[1:] if argv is None else argv)
    args = _parse_args(argv)
    dry_run = args.dry_run
    force = args.force
    profile = args.profile

    print("==================================================")
    print("Mod Workspace Auto-Refactoring Engine")
    print(f"Mode: {'DRY-RUN (no writes)' if dry_run else 'APPLY'} | Profile: {profile.upper()}")
    print("==================================================")

    project_dir, agents_dir = project_paths()
    project_dir = project_dir.resolve()
    agents_dir = require_path_within(
        agents_dir,
        project_dir,
        label=".agents directory",
    )
    gradle_properties_path = project_dir / "gradle.properties"
    agents_md_path = agents_dir / "AGENTS.md"
    log: List[str] = []

    if not gradle_properties_path.is_file():
        print(f"Error: gradle.properties not found at {gradle_properties_path}")
        return 1

    if not dry_run and not force and git_dirty(project_dir):
        print("ERROR: git working tree is not clean.")
        print("  Refuse to apply renames (data loss risk).")
        print("  Use --dry-run to preview, or --force to override.")
        return 2

    props = parse_props(gradle_properties_path)
    mod_id = validate_mod_id(props.get("mod_id", "tutorialmod"))
    mod_name = props.get("mod_name", "Tutorial Mod")
    mod_group_id = props.get("mod_group_id", "com.tutorial.tutorialmod")
    mod_group_segments = validate_java_package(mod_group_id)

    print(f"[Properties] Mod ID: {mod_id}")
    print(f"[Properties] Mod Name: {mod_name}")
    print(f"[Properties] Mod Package: {mod_group_id}")

    # Collect known stale ids for text replace (template defaults)
    old_ids = ["examplemod", "tutorialmod"]
    resources_dir = project_dir / "src" / "main" / "resources"
    assets_dir = resources_dir / "assets"
    data_dir = resources_dir / "data"
    java_root = project_dir / "src" / "main" / "java"
    snbt_root = project_dir / "src" / "main" / "snbt"
    snbt_data_dir = snbt_root / "data"
    generated_root = project_dir / "src" / "generated" / "resources"
    for root, label in (
        (java_root, "Java source root"),
        (resources_dir, "main resource root"),
        (snbt_root, "SNBT source root"),
        (generated_root, "generated resource root"),
    ):
        require_path_within(root, project_dir, label=label)

    if assets_dir.is_dir():
        for sub in assets_dir.iterdir():
            if sub.is_dir() and sub.name not in old_ids and sub.name != mod_id and sub.name not in SYSTEM_NAMESPACES:
                old_ids.append(sub.name)
    if data_dir.is_dir():
        for sub in data_dir.iterdir():
            if (
                sub.is_dir()
                and sub.name not in old_ids
                and sub.name != mod_id
                and sub.name not in SYSTEM_NAMESPACES
            ):
                old_ids.append(sub.name)
    if snbt_data_dir.is_dir():
        for sub in snbt_data_dir.iterdir():
            if (
                sub.is_dir()
                and sub.name not in old_ids
                and sub.name != mod_id
                and sub.name not in SYSTEM_NAMESPACES
            ):
                old_ids.append(sub.name)

    # 1. Detect Main Class & Old Java Package
    main_class_file = None
    main_class_full_path: Optional[Path] = None
    old_package: Optional[str] = None

    if java_root.is_dir():
        for full_path in java_root.rglob("*.java"):
            full_path = require_path_within(
                full_path,
                java_root,
                label="Java main-class discovery",
            )
            try:
                content = full_path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if "@Mod(" in content:
                main_class_file = full_path.name
                main_class_full_path = full_path
                pkg_match = re.search(r"^\s*package\s+([\w.]+)\s*;", content, re.MULTILINE)
                if pkg_match:
                    old_package = pkg_match.group(1)
                    validate_java_package(old_package)
                break

    main_class_rel = (
        "./" + main_class_full_path.relative_to(project_dir).as_posix()
        if main_class_full_path
        else f"./src/main/java/{mod_group_id.replace('.', '/')}/TutorialMod.java"
    )
    print(f"[Java Main Class] Located: {main_class_rel}")
    if old_package:
        print(f"[Java Old Package] Detected: {old_package}")

    old_pkg_dir = (
        java_root / Path(*old_package.split("."))
        if old_package
        else None
    )
    new_pkg_dir = java_root / Path(*mod_group_segments)
    require_path_within(
        new_pkg_dir,
        java_root,
        label="target Java package directory",
    )
    if old_pkg_dir is not None:
        require_path_within(
            old_pkg_dir,
            java_root,
            label="source Java package directory",
        )

    # Minimal is planned and validated before any package/resource move.  This
    # makes dry-run and apply observe the same source tree and prevents partial
    # mutations when a user-modified starter file cannot be removed safely.
    minimal_plan = MinimalProfilePlan()
    if profile == "minimal":
        if (
            main_class_full_path is None
            or old_package is None
            or old_pkg_dir is None
        ):
            raise WorkspaceSafetyError(
                "minimal profile requires a detectable packaged @Mod main class"
            )
        minimal_plan = build_minimal_profile_plan(
            project_dir=project_dir,
            java_root=java_root,
            resources_dir=resources_dir,
            generated_root=generated_root,
            package_dir=old_pkg_dir,
            package_name=old_package,
            main_class_path=main_class_full_path,
            mod_id=mod_id,
            old_ids=old_ids,
        )
        apply_minimal_profile_plan(
            minimal_plan,
            log=log,
            dry_run=dry_run,
        )

    # 2. Refactor Java package statements, imports, and directory structure
    if old_package and old_package != mod_group_id and java_root.is_dir():
        log.append(f"[Java Refactor] package {old_package} -> {mod_group_id}")
        
        # Refactor Java source files text (packages + imports)
        for jf in java_root.rglob("*.java"):
            jf = require_path_within(
                jf, java_root, label="Java package refactor source"
            )
            try:
                jcontent = jf.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            new_jcontent = re.sub(
                rf"\bpackage\s+{re.escape(old_package)}\b",
                f"package {mod_group_id}",
                jcontent,
            )
            new_jcontent = re.sub(
                rf"\bimport\s+{re.escape(old_package)}\b",
                f"import {mod_group_id}",
                new_jcontent,
            )
            if new_jcontent != jcontent:
                if not dry_run:
                    jf.write_text(new_jcontent, encoding="utf-8")

        # Physical move of Java source directory
        if old_pkg_dir.is_dir() and old_pkg_dir.resolve() != new_pkg_dir.resolve():
            merge_or_move(
                old_pkg_dir,
                new_pkg_dir,
                "Java Sources Package",
                log,
                dry_run,
                allowed_root=java_root,
            )
            
            # Clean up empty parent directories of old package
            curr = old_pkg_dir.parent
            while curr != java_root and curr.is_dir():
                curr = require_path_within(
                    curr,
                    java_root,
                    label="old Java package parent cleanup",
                )
                try:
                    if not any(curr.iterdir()):
                        if not dry_run:
                            curr.rmdir()
                        curr = curr.parent
                    else:
                        break
                except OSError:
                    break

    # GameTestHolder requires a compile-time namespace literal for strict source
    # discovery. Keep that literal aligned even when only mod_id changes and the
    # Java package itself stays unchanged.
    aligned_holders = 0
    if java_root.is_dir():
        for java_file in java_root.rglob("*.java"):
            java_file = require_path_within(
                java_file,
                java_root,
                label="GameTest holder namespace alignment",
            )
            try:
                java_content = java_file.read_text(
                    encoding="utf-8",
                    errors="strict",
                )
            except (OSError, UnicodeError):
                continue
            aligned_java = align_gametest_holder_literals(
                java_content,
                old_ids,
                mod_id,
            )
            if aligned_java == java_content:
                continue
            aligned_holders += 1
            if not dry_run:
                java_file.write_text(aligned_java, encoding="utf-8")
    if aligned_holders:
        log.append(
            "[GameTest] align holder namespaces in "
            f"{aligned_holders} Java file(s)"
        )

    # 3. Align language keys against the current source namespaces before
    # moving directories. This keeps dry-run and apply action plans identical.
    align_language_resources(
        assets_dir,
        resources_dir,
        old_ids,
        mod_id,
        log,
        dry_run,
        planned_contents={
            mutation.path: mutation.content
            for mutation in (
                *minimal_plan.updates,
                *minimal_plan.deletes,
            )
            if mutation.root.resolve(strict=False)
            == resources_dir.resolve(strict=False)
        },
    )

    # 4. Assets rename (protected by SYSTEM_NAMESPACES)
    if assets_dir.is_dir():
        for sub in list(assets_dir.iterdir()):
            if sub.is_dir() and sub.name not in SYSTEM_NAMESPACES and sub.name != mod_id:
                merge_or_move(
                    sub,
                    assets_dir / mod_id,
                    "Assets",
                    log,
                    dry_run,
                    allowed_root=assets_dir,
                )

    # 5. Data namespace + singular folders (protected by SYSTEM_NAMESPACES)
    if data_dir.is_dir():
        for sub in list(data_dir.iterdir()):
            if not sub.is_dir():
                continue
            if sub.name in SYSTEM_NAMESPACES:
                continue
            if sub.name != mod_id:
                merge_or_move(
                    sub,
                    data_dir / mod_id,
                    "Data",
                    log,
                    dry_run,
                    allowed_root=data_dir,
                )

        target_data_ns = data_dir / mod_id
        if target_data_ns.is_dir():
            plural_to_singular = {
                "recipes": "recipe",
                "loot_tables": "loot_table",
                "advancements": "advancement",
            }
            for plural, singular in plural_to_singular.items():
                plural_path = target_data_ns / plural
                if not plural_path.exists():
                    continue
                singular_path = target_data_ns / singular
                merge_or_move(
                    plural_path,
                    singular_path,
                    "Singular Rule",
                    log,
                    dry_run,
                    allowed_root=target_data_ns,
                )

    # 6. Reviewed SNBT source namespace. This is separate from normal resources
    # and must follow the mod id so DataGen emits structures into the namespace
    # selected by the GameTest holder.
    if snbt_data_dir.is_dir():
        for sub in list(snbt_data_dir.iterdir()):
            if not sub.is_dir() or sub.name in SYSTEM_NAMESPACES:
                continue
            if sub.name != mod_id:
                merge_or_move(
                    sub,
                    snbt_data_dir / mod_id,
                    "SNBT/Data",
                    log,
                    dry_run,
                    allowed_root=snbt_data_dir,
                )

    # 7. Generated resources namespaces
    gen_root = generated_root
    if gen_root.is_dir():
        for kind in ("assets", "data"):
            kind_dir = gen_root / kind
            if not kind_dir.is_dir():
                continue
            for sub in list(kind_dir.iterdir()):
                if not sub.is_dir():
                    continue
                if sub.name in SYSTEM_NAMESPACES:
                    continue
                if sub.name not in old_ids and sub.name != mod_id:
                    old_ids.append(sub.name)
                if sub.name != mod_id:
                    merge_or_move(
                        sub,
                        kind_dir / mod_id,
                        f"Generated/{kind}",
                        log,
                        dry_run,
                        allowed_root=kind_dir,
                    )
        align_generated_resource_contents(
            gen_root,
            old_ids,
            mod_id,
            log,
            dry_run,
            excluded_paths={
                mutation.path
                for mutation in (
                    *minimal_plan.deletes,
                    *minimal_plan.updates,
                )
                if mutation.root.resolve(strict=False)
                == generated_root.resolve(strict=False)
            },
        )
        align_generated_cache_contents(
            gen_root,
            old_ids,
            mod_id,
            log,
            dry_run,
            excluded_paths={
                mutation.path
                for mutation in (
                    *minimal_plan.deletes,
                    *minimal_plan.updates,
                )
                if mutation.root.resolve(strict=False)
                == generated_root.resolve(strict=False)
            },
        )

    # 8. pack.mcmeta
    pack_mcmeta_path = resources_dir / "pack.mcmeta"
    if pack_mcmeta_path.is_file():
        pack_mcmeta_path = require_path_within(
            pack_mcmeta_path,
            resources_dir,
            label="pack.mcmeta alignment",
        )
        mcmeta_content = pack_mcmeta_path.read_text(encoding="utf-8", errors="replace")
        new_mcmeta = replace_in_text(mcmeta_content, old_ids, mod_id)
        if new_mcmeta != mcmeta_content:
            log.append("[META] align pack.mcmeta namespaces")
            if not dry_run:
                pack_mcmeta_path.write_text(new_mcmeta, encoding="utf-8")

    # 9. Mixin json rename + create
    dry_run_mixin_source: Optional[Path] = None
    if resources_dir.is_dir():
        mixin_sources = [
            require_path_within(
                path, resources_dir, label="mixin config source"
            )
            for path in resources_dir.glob("*.mixins.json")
        ]
        target_candidate = resources_dir / f"{mod_id}.mixins.json"
        content_candidates = [
            path
            for path in mixin_sources
            if path.name != target_candidate.name
        ]
        if target_candidate.is_file():
            content_candidates.append(
                require_path_within(
                    target_candidate,
                    resources_dir,
                    label="existing mixin config destination",
                )
            )
        if len({path.read_bytes() for path in content_candidates}) > 1:
            raise WorkspaceSafetyError(
                "multiple mixin configs would merge into "
                f"{target_candidate.name} with different content"
            )
        for p in mixin_sources:
            if p.name != f"{mod_id}.mixins.json":
                target = require_path_within(
                    resources_dir / f"{mod_id}.mixins.json",
                    resources_dir,
                    label="mixin config destination",
                )
                log.append(f"[Mixin] rename {p.name} -> {target.name}")
                if dry_run:
                    dry_run_mixin_source = p
                if not dry_run:
                    if target.exists():
                        if target.read_bytes() != p.read_bytes():
                            raise WorkspaceSafetyError(
                                "mixin rename would overwrite different content: "
                                f"{target}"
                            )
                        _safe_unlink(
                            p,
                            resources_dir,
                            label="duplicate mixin config source",
                        )
                    else:
                        shutil.move(str(p), str(target))

    mods_toml_template = (
        project_dir / "src" / "main" / "templates" / "META-INF" / "neoforge.mods.toml"
    )
    if mods_toml_template.is_file():
        toml_content = mods_toml_template.read_text(encoding="utf-8", errors="replace")
        if "#[[mixins]]" in toml_content:
            log.append("[META-INF Template] activate mixin blocks")
            if not dry_run:
                toml_content = toml_content.replace("#[[mixins]]", "[[mixins]]")
                toml_content = toml_content.replace(
                    '#config="${mod_id}.mixins.json"',
                    'config="${mod_id}.mixins.json"',
                )
                mods_toml_template.write_text(toml_content, encoding="utf-8")

    mixin_config_path = require_path_within(
        resources_dir / f"{mod_id}.mixins.json",
        resources_dir,
        label="mixin config",
    )
    readable_mixin_config = (
        mixin_config_path
        if mixin_config_path.exists()
        else dry_run_mixin_source
    )
    if readable_mixin_config is None:
        log.append(f"[Mixin Config] create {mod_id}.mixins.json")
        if not dry_run:
            mixin_data = {
                "required": True,
                "minVersion": "0.8",
                "package": f"{mod_group_id}.mixin",
                "compatibilityLevel": "JAVA_21",
                "refmap": f"{mod_id}.refmap.json",
                "mixins": [],
                "client": [],
                "injectors": {"defaultRequire": 1},
            }
            mixin_config_path.write_text(
                json.dumps(mixin_data, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
    else:
        try:
            readable_mixin_config = require_path_within(
                readable_mixin_config,
                resources_dir,
                label="mixin config content",
            )
            mixin_data = json.loads(
                readable_mixin_config.read_text(encoding="utf-8")
            )
            desired_pkg = f"{mod_group_id}.mixin"
            if mixin_data.get("package") != desired_pkg:
                log.append(f"[Mixin Config] package -> {desired_pkg}")
                if not dry_run:
                    mixin_data["package"] = desired_pkg
                    mixin_data["refmap"] = f"{mod_id}.refmap.json"
                    mixin_config_path.write_text(
                        json.dumps(mixin_data, indent=2, ensure_ascii=False) + "\n",
                        encoding="utf-8",
                    )
        except Exception as e:
            log.append(f"[Mixin Config] skip package align: {e}")

    # 10. Update MODID in main class
    if main_class_full_path:
        # Compute updated location if package moved
        target_main_path = (
            new_pkg_dir / main_class_file
            if old_package and old_package != mod_group_id
            else main_class_full_path
        )
        target_main_path = require_path_within(
            target_main_path,
            java_root,
            label="main class MODID update",
        )
        readable_main_path = (
            target_main_path
            if target_main_path.is_file()
            else main_class_full_path
        )
        if readable_main_path.is_file():
            readable_main_path = require_path_within(
                readable_main_path,
                java_root,
                label="main class MODID source",
            )
            java_content = readable_main_path.read_text(
                encoding="utf-8", errors="replace"
            )
            new_java = re.sub(
                r'public static final String MODID = "[^"]*";',
                f'public static final String MODID = "{mod_id}";',
                java_content,
            )
            new_java = replace_in_text(new_java, old_ids, mod_id)
            if new_java != java_content:
                log.append(f"[Java Code] update MODID in {main_class_file}")
                if not dry_run:
                    target_main_path.write_text(new_java, encoding="utf-8")

    # 11. Update AGENTS.md metadata lines
    if agents_md_path.is_file():
        agents_content = agents_md_path.read_text(encoding="utf-8", errors="replace")
        updated2 = re.sub(
            r"- \*\*参考 Mod ID\*\*: .*",
            f"- **参考 Mod ID**: {mod_id} (已由初始化引擎自动对齐)",
            agents_content,
        )
        updated2 = re.sub(
            r"- \*\*参考 Mod Name\*\*: .*",
            f"- **参考 Mod Name**: {mod_name} (已由初始化引擎自动对齐)",
            updated2,
        )
        main_class_name = main_class_file or "TutorialMod.java"
        updated2 = re.sub(
            r"- \*\*参考基类路径\*\*: .*",
            f"- **参考基类路径**: [{main_class_name}]({main_class_rel})",
            updated2,
        )
        if updated2 != agents_content:
            log.append("[AGENTS.md] align metadata lines if present")
            if not dry_run:
                agents_md_path.write_text(updated2, encoding="utf-8")

    print("\n--- Planned / Applied actions ---")
    if not log:
        print("(no file changes needed; already aligned)")
    else:
        for line in log:
            print(line)

    print("==================================================")
    if dry_run:
        print("DRY-RUN complete. Re-run without --dry-run to apply.")
    else:
        print("Refactoring complete.")
    print("==================================================")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    actual_argv = list(sys.argv[1:] if argv is None else argv)
    try:
        parsed = _parse_args(actual_argv)
        if not parsed.dry_run:
            preflight_argv = [
                "--dry-run",
                "--profile",
                parsed.profile,
            ]
            preflight_code = _run_main(preflight_argv)
            if preflight_code != 0:
                return preflight_code
        return _run_main(actual_argv)
    except WorkspaceSafetyError as error:
        print(f"ERROR: unsafe workspace mutation refused: {error}")
        return 3


if __name__ == "__main__":
    sys.exit(main())
