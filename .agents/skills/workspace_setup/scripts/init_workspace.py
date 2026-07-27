#!/usr/bin/env python3
"""
Host workspace rename / align engine for NeoForge projects.

Usage:
  python init_workspace.py           # apply changes (requires clean git unless --force)
  python init_workspace.py --dry-run # print planned actions only
  python init_workspace.py --force   # apply even if git working tree is dirty
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable, List, Optional, Set, Tuple

SYSTEM_NAMESPACES = {"minecraft", "c", "neoforge", "forge"}


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


def merge_or_move(old_path: Path, new_path: Path, label: str, log: List[str], dry_run: bool) -> None:
    if not old_path.exists():
        return
    if old_path.resolve() == new_path.resolve():
        return
    if new_path.exists():
        log.append(f"[{label}] merge {old_path.relative_to(old_path.parents[2])} -> {new_path.relative_to(new_path.parents[2])}")
        if dry_run:
            return
        for root, _, files in os.walk(old_path):
            for f in files:
                src_file = Path(root) / f
                dest_file = new_path / src_file.relative_to(old_path)
                dest_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src_file, dest_file)
        shutil.rmtree(old_path)
    else:
        log.append(f"[{label}] rename {old_path.name} -> {new_path.name}")
        if not dry_run:
            new_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(old_path), str(new_path))


def replace_in_text(content: str, old_ids: List[str], mod_id: str) -> str:
    out = content
    for oid in old_ids:
        if oid and oid != mod_id:
            out = re.sub(rf"\b{re.escape(oid)}\b", mod_id, out)
    return out


def align_generated_resource_contents(
    generated_root: Path,
    old_ids: List[str],
    mod_id: str,
    log: List[str],
    dry_run: bool,
) -> int:
    """Align namespace references inside generated JSON after a Mod ID rename."""
    changed = 0
    if not generated_root.is_dir():
        return changed

    for path in sorted(generated_root.rglob("*.json")):
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        updated = replace_in_text(content, old_ids, mod_id)
        if updated == content:
            continue
        changed += 1
        if not dry_run:
            path.write_text(updated, encoding="utf-8")

    if changed:
        log.append(
            f"[Generated Content] align namespaces in {changed} JSON file(s)"
        )
    return changed


def main(argv: Optional[List[str]] = None) -> int:
    _reconfigure_stdio()
    argv = list(sys.argv[1:] if argv is None else argv)
    dry_run = "--dry-run" in argv
    force = "--force" in argv

    print("==================================================")
    print("Mod Workspace Auto-Refactoring Engine")
    print(f"Mode: {'DRY-RUN (no writes)' if dry_run else 'APPLY'}")
    print("==================================================")

    project_dir, agents_dir = project_paths()
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
    mod_id = props.get("mod_id", "tutorialmod")
    mod_name = props.get("mod_name", "Tutorial Mod")
    mod_group_id = props.get("mod_group_id", "com.tutorial.tutorialmod")

    print(f"[Properties] Mod ID: {mod_id}")
    print(f"[Properties] Mod Name: {mod_name}")
    print(f"[Properties] Mod Package: {mod_group_id}")

    # Collect known stale ids for text replace (template defaults)
    old_ids = ["examplemod", "tutorialmod"]
    resources_dir = project_dir / "src" / "main" / "resources"
    assets_dir = resources_dir / "assets"
    data_dir = resources_dir / "data"
    java_root = project_dir / "src" / "main" / "java"

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

    # 1. Detect Main Class & Old Java Package
    main_class_file = None
    main_class_full_path: Optional[Path] = None
    old_package: Optional[str] = None

    if java_root.is_dir():
        for full_path in java_root.rglob("*.java"):
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
                break

    main_class_rel = (
        "./" + main_class_full_path.relative_to(project_dir).as_posix()
        if main_class_full_path
        else f"./src/main/java/{mod_group_id.replace('.', '/')}/TutorialMod.java"
    )
    print(f"[Java Main Class] Located: {main_class_rel}")
    if old_package:
        print(f"[Java Old Package] Detected: {old_package}")

    # 2. Refactor Java package statements, imports, and directory structure
    if old_package and old_package != mod_group_id and java_root.is_dir():
        log.append(f"[Java Refactor] package {old_package} -> {mod_group_id}")
        
        # Refactor Java source files text (packages + imports)
        for jf in java_root.rglob("*.java"):
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
        old_pkg_dir = java_root / Path(*old_package.split("."))
        new_pkg_dir = java_root / Path(*mod_group_id.split("."))

        if old_pkg_dir.is_dir() and old_pkg_dir.resolve() != new_pkg_dir.resolve():
            merge_or_move(old_pkg_dir, new_pkg_dir, "Java Sources Package", log, dry_run)
            
            # Clean up empty parent directories of old package
            curr = old_pkg_dir.parent
            while curr != java_root and curr.is_dir():
                try:
                    if not any(curr.iterdir()):
                        if not dry_run:
                            curr.rmdir()
                        curr = curr.parent
                    else:
                        break
                except OSError:
                    break

    # 3. Assets rename (protected by SYSTEM_NAMESPACES)
    if assets_dir.is_dir():
        for sub in list(assets_dir.iterdir()):
            if sub.is_dir() and sub.name not in SYSTEM_NAMESPACES and sub.name != mod_id:
                merge_or_move(sub, assets_dir / mod_id, "Assets", log, dry_run)

    lang_dir = assets_dir / mod_id / "lang"
    if lang_dir.is_dir():
        for file in lang_dir.iterdir():
            if file.suffix != ".json":
                continue
            try:
                lang_data = json.loads(file.read_text(encoding="utf-8"))
            except Exception as e:
                log.append(f"[Language JSON] skip {file.name}: {e}")
                continue
            new_lang_data = {}
            changed = False
            for k, v in lang_data.items():
                new_k = k
                for oid in old_ids:
                    if oid != mod_id:
                        new_k = re.sub(rf"\b{re.escape(oid)}\b", mod_id, new_k)
                if new_k != k:
                    changed = True
                new_lang_data[new_k] = v
            if changed:
                log.append(f"[Language JSON] align namespaces in {file.name}")
                if not dry_run:
                    file.write_text(
                        json.dumps(new_lang_data, indent=2, ensure_ascii=False) + "\n",
                        encoding="utf-8",
                    )

    # 4. Data namespace + singular folders (protected by SYSTEM_NAMESPACES)
    if data_dir.is_dir():
        for sub in list(data_dir.iterdir()):
            if not sub.is_dir():
                continue
            if sub.name in SYSTEM_NAMESPACES:
                continue
            if sub.name != mod_id:
                merge_or_move(sub, data_dir / mod_id, "Data", log, dry_run)

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
                log.append(f"[Singular Rule] {plural} -> {singular}")
                if dry_run:
                    continue
                if singular_path.exists():
                    for root, _, files in os.walk(plural_path):
                        for f in files:
                            src_file = Path(root) / f
                            dest_file = singular_path / src_file.relative_to(plural_path)
                            dest_file.parent.mkdir(parents=True, exist_ok=True)
                            shutil.copy2(src_file, dest_file)
                    shutil.rmtree(plural_path)
                else:
                    plural_path.rename(singular_path)

    # 5. Generated resources namespaces
    gen_root = project_dir / "src" / "generated" / "resources"
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
                    merge_or_move(sub, kind_dir / mod_id, f"Generated/{kind}", log, dry_run)
        align_generated_resource_contents(gen_root, old_ids, mod_id, log, dry_run)

    # 6. pack.mcmeta
    pack_mcmeta_path = resources_dir / "pack.mcmeta"
    if pack_mcmeta_path.is_file():
        mcmeta_content = pack_mcmeta_path.read_text(encoding="utf-8", errors="replace")
        new_mcmeta = replace_in_text(mcmeta_content, old_ids, mod_id)
        if new_mcmeta != mcmeta_content:
            log.append("[META] align pack.mcmeta namespaces")
            if not dry_run:
                pack_mcmeta_path.write_text(new_mcmeta, encoding="utf-8")

    # 7. Mixin json rename + create
    if resources_dir.is_dir():
        for p in list(resources_dir.glob("*.mixins.json")):
            if p.name != f"{mod_id}.mixins.json":
                target = resources_dir / f"{mod_id}.mixins.json"
                log.append(f"[Mixin] rename {p.name} -> {target.name}")
                if not dry_run:
                    if target.exists():
                        p.unlink()
                    else:
                        p.rename(target)

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

    mixin_config_path = resources_dir / f"{mod_id}.mixins.json"
    if not mixin_config_path.exists():
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
            mixin_data = json.loads(mixin_config_path.read_text(encoding="utf-8"))
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

    # 8. Update MODID in main class
    if main_class_full_path and main_class_full_path.is_file():
        # Compute updated location if package moved
        target_main_path = (
            java_root / Path(*mod_group_id.split(".")) / main_class_file
            if old_package and old_package != mod_group_id
            else main_class_full_path
        )
        if target_main_path.is_file():
            java_content = target_main_path.read_text(encoding="utf-8", errors="replace")
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

    # 9. Update AGENTS.md metadata lines
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


if __name__ == "__main__":
    sys.exit(main())
