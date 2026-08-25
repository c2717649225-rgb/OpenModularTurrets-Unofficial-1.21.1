import os
import sys

if sys.version_info < (3, 10):
    sys.stderr.write(
        f"[ERROR] Python 3.10 or higher is required to run gate scripts. "
        f"Current Python: {sys.version_info.major}.{sys.version_info.minor}\n"
        "Please use: python .agents/run.py ...\n"
    )
    sys.exit(1)

import subprocess
import re
import json
import math
import signal
import threading
import time
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple


DATAGEN_GIT_PATHS = ("src/generated/resources",)
BOOLEAN_OPTIONS = frozenset(
    {
        "--with-data",
        "--with-static",
        "--skip-static",
        "--with-assets",
        "--with-server",
        "--with-contracts",
        "--with-gametest",
        "--allow-reference-host-only",
        "--strict-traceability",
        "--strict-datagen-layout",
        "--warnings-as-errors",
        "--verify-data-clean",
        "--check-data-clean",
    }
)
VALUE_OPTIONS = frozenset({"--contract-root", "--gametest-timeout"})


def validate_generated_resources(project_dir: str) -> Tuple[bool, str]:
    """Require a non-empty generated-resource tree whose JSON all parses."""
    generated_root = Path(project_dir) / "src" / "generated" / "resources"
    if not generated_root.is_dir():
        return False, (
            "src/generated/resources does not exist after runData. "
            "Register at least one DataProvider and configure the generated resource source set."
        )

    json_files = sorted(
        path for path in generated_root.rglob("*.json") if path.is_file()
    )
    if not json_files:
        return False, (
            "src/generated/resources contains no JSON after runData. "
            "DataGen completed but produced no verifiable resources."
        )

    malformed: List[str] = []
    for path in json_files:
        try:
            json.loads(path.read_text(encoding="utf-8", errors="strict"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            rel = path.relative_to(Path(project_dir)).as_posix()
            malformed.append(f"{rel}: {exc}")

    if malformed:
        preview = "\n".join(f"  - {item}" for item in malformed[:10])
        if len(malformed) > 10:
            preview += f"\n  - ... (+{len(malformed) - 10} more)"
        return False, (
            f"{len(malformed)} malformed generated JSON file(s):\n{preview}"
        )

    return True, (
        f"validated {len(json_files)} JSON file(s) under "
        "src/generated/resources"
    )


def git_worktree_changes(
    project_dir: str, paths: Tuple[str, ...] = ()
) -> Optional[List[str]]:
    """Return Git status lines, optionally scoped to paths, or None without Git."""
    try:
        probe = subprocess.run(
            ["git", "-C", project_dir, "rev-parse", "--is-inside-work-tree"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if probe.returncode != 0 or probe.stdout.strip() != "true":
            return None

        command = [
            "git", "-C", project_dir, "status", "--porcelain=v1",
            "--untracked-files=all",
        ]
        if paths:
            command += ["--", *paths]
        status = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError:
        return None

    if status.returncode != 0:
        return None
    return [line for line in status.stdout.splitlines() if line.strip()]


def datagen_git_changes(project_dir: str) -> Optional[List[str]]:
    """Return Git status lines scoped to generated resources."""
    return git_worktree_changes(project_dir, DATAGEN_GIT_PATHS)


def is_ci_environment() -> bool:
    return os.environ.get("CI", "").strip().lower() in {
        "1", "true", "yes", "on",
    }


def cli_option_value(
    argv: List[str], option: str, default: Optional[str] = None
) -> Optional[str]:
    """Read ``--option value`` or ``--option=value`` from the legacy CLI.

    The gate predates argparse and intentionally keeps its stable flag surface.
    This helper adds the two value-bearing options without silently accepting a
    missing value.
    """
    prefix = option + "="
    for index, argument in enumerate(argv):
        if argument.startswith(prefix):
            value = argument[len(prefix):].strip()
            if not value:
                raise ValueError(f"{option} requires a non-empty value")
            return value
        if argument == option:
            if index + 1 >= len(argv) or argv[index + 1].startswith("--"):
                raise ValueError(f"{option} requires a value")
            return argv[index + 1]
    return default


def validate_cli_arguments(argv: List[str]) -> None:
    """Reject unknown or malformed flags instead of silently skipping gates."""
    index = 0
    while index < len(argv):
        argument = argv[index]
        if argument in BOOLEAN_OPTIONS:
            index += 1
            continue
        if argument in VALUE_OPTIONS:
            if index + 1 >= len(argv) or argv[index + 1].startswith("--"):
                raise ValueError(f"{argument} requires a value")
            index += 2
            continue
        if any(
            argument.startswith(option + "=") for option in VALUE_OPTIONS
        ):
            if not argument.split("=", 1)[1].strip():
                raise ValueError(
                    f"{argument.split('=', 1)[0]} requires a non-empty value"
                )
            index += 1
            continue
        raise ValueError(f"unknown argument: {argument}")


def positive_finite_float(text: Optional[str], option: str) -> float:
    try:
        value = float(text)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{option} requires a number") from error
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f"{option} must be finite and greater than zero")
    return value


def build_gametest_gate_command(
    script_dir: str,
    project_dir: str,
    timeout_seconds: float,
    *,
    allow_reference_host_only: bool = False,
) -> List[str]:
    command = [
        sys.executable,
        os.path.join(script_dir, "gametest_gate.py"),
        "--project-dir",
        project_dir,
        "--require-tests",
        "--run",
        "--timeout",
        f"{timeout_seconds:g}",
        "--json-report",
        "build/reports/gametest-gate.json",
    ]
    if allow_reference_host_only:
        command.append("--allow-reference-host-only")
    return command


def terminate_process_tree(proc: subprocess.Popen) -> None:
    """Force-stop a Gradle wrapper and every child it launched.

    Killing only the wrapper can leave runServer's Java process alive while it
    continues to hold the inherited stdout pipe open. The L3 reader then blocks
    forever even though its timer fired.
    """
    if proc.poll() is not None:
        return

    if os.name == "nt":
        result = subprocess.run(
            ["taskkill", "/PID", str(proc.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode != 0 and proc.poll() is None:
            proc.kill()
        return

    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
    except ProcessLookupError:
        pass


def run_server_smoke(gradle_path: str, project_dir: str, timeout_s: int = 600) -> bool:
    """L3 smoke: boot the dedicated server headless, assert it reaches 'Done',
    then shut it down. Catches client-class leaks and boot-time crashes that
    static scanning cannot see. Returns True on PASS."""
    print("--------------------------------------------------")
    print(f"L3 server smoke: gradlew runServer (timeout {timeout_s}s)")

    # Dedicated servers refuse to boot without an accepted EULA. Running with
    # --with-server implies acceptance of the Mojang EULA for this test run.
    run_dir = os.path.join(project_dir, "run")
    eula_path = os.path.join(run_dir, "eula.txt")
    try:
        os.makedirs(run_dir, exist_ok=True)
        needs_eula = True
        if os.path.exists(eula_path):
            with open(eula_path, "r", encoding="utf-8", errors="replace") as f:
                needs_eula = "eula=true" not in f.read()
        if needs_eula:
            with open(eula_path, "w", encoding="utf-8") as f:
                f.write("# Auto-accepted for --with-server smoke test (implies Mojang EULA consent)\n")
                f.write("eula=true\n")
            print(f"NOTE: wrote eula=true to {eula_path} (--with-server implies EULA consent).")
    except OSError as e:
        print(f"WARNING: could not prepare eula.txt: {e}")

    popen_options: Dict[str, Any] = {}
    if os.name == "nt":
        popen_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        popen_options["start_new_session"] = True

    proc = subprocess.Popen(
        [gradle_path, "runServer", "--no-daemon", "--console=plain"],
        cwd=project_dir,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        **popen_options,
    )
    watchdog = threading.Timer(timeout_s, terminate_process_tree, args=(proc,))
    watchdog.daemon = True
    watchdog.start()

    done_seen = False
    fatal_seen = False
    error_lines: List[str] = []
    tail: List[str] = []
    graceful_timer: Optional[threading.Timer] = None
    try:
        for line in proc.stdout:
            line = line.rstrip()
            tail.append(line)
            if len(tail) > 60:
                tail.pop(0)
            if "/FATAL]" in line or "Exception in server" in line:
                fatal_seen = True
            if "/ERROR]" in line and len(error_lines) < 30:
                error_lines.append(line)
            if not done_seen and re.search(r"\bDone \(", line):
                done_seen = True
                print("Server reached 'Done' — issuing graceful stop...")
                try:
                    proc.stdin.write("stop\n")
                    proc.stdin.flush()
                except OSError:
                    pass
                # Gradle does not reliably forward stdin to runServer on every
                # platform. Reap its complete process tree if stop is ignored.
                graceful_timer = threading.Timer(
                    10.0, terminate_process_tree, args=(proc,)
                )
                graceful_timer.daemon = True
                graceful_timer.start()
    finally:
        watchdog.cancel()
        if graceful_timer is not None:
            graceful_timer.cancel()
        try:
            proc.stdin.close()
        except OSError:
            pass
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            terminate_process_tree(proc)
            proc.wait(timeout=15)

    # Verdict: booting to 'Done' without FATAL is the hard assertion. Exit code
    # is ignored when we had to kill a server that would not stop via stdin.
    if done_seen and not fatal_seen:
        print(f"L3 PASS: dedicated server booted to 'Done'. ({len(error_lines)} ERROR line(s) observed)")
        for el in error_lines:
            print(f"  [server-error] {el}")
        if error_lines:
            print("  ^ Review these ERROR lines — not all are fatal, but none should ship unexplained.")
        return True

    print("L3 FAIL: server never reached 'Done' (crash, hang past timeout, or FATAL).")
    print("Last output lines:")
    for line in tail[-40:]:
        print(f"  {line}")

    # Environment triage: distinguish network/toolchain failures from mod bugs,
    # so the AI does not "fix" code that was never the problem.
    joined = "\n".join(tail)
    if re.search(r"downloadAssets|Could not (?:download|resolve|GET)|Connection (?:timed out|reset)|piston-(?:meta|data)", joined):
        print("--------------------------------------------------")
        print("[TRIAGE] Failure looks like NETWORK/ASSET DOWNLOAD, not a mod defect:")
        print("  - Gradle ':downloadAssets' pulls client assets from Mojang CDN and")
        print("    commonly times out on restricted networks (e.g. direct CN routes).")
        print("  - Fix options: set systemProp.http.proxyHost/Port (+https) in")
        print("    gradle.properties; or warm the ~/.gradle asset cache once on an")
        print("    unrestricted network / CI; then rerun --with-server.")
        print("  - Do NOT edit mod code in response to this failure mode.")
    return False


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')

    # 检查参数
    try:
        validate_cli_arguments(sys.argv[1:])
    except ValueError as error:
        print(f"Error: {error}")
        sys.exit(2)

    print("==================================================")
    print("Starting Automated Compilation & Error Diagnostics...")
    print("==================================================")

    with_data = "--with-data" in sys.argv
    with_static = "--with-static" in sys.argv
    skip_static = "--skip-static" in sys.argv
    with_assets = "--with-assets" in sys.argv
    with_server = "--with-server" in sys.argv
    with_contracts = "--with-contracts" in sys.argv
    with_gametest = "--with-gametest" in sys.argv
    allow_reference_host_only = "--allow-reference-host-only" in sys.argv
    strict_traceability = "--strict-traceability" in sys.argv
    strict_datagen_layout = "--strict-datagen-layout" in sys.argv
    warnings_as_errors = "--warnings-as-errors" in sys.argv
    require_data_clean = (
        "--verify-data-clean" in sys.argv
        or "--check-data-clean" in sys.argv
        or is_ci_environment()
    )
    try:
        contract_root = cli_option_value(
            sys.argv[1:], "--contract-root"
        )
        gametest_timeout_text = cli_option_value(
            sys.argv[1:], "--gametest-timeout", "900"
        )
        gametest_timeout = positive_finite_float(
            gametest_timeout_text, "--gametest-timeout"
        )
    except ValueError as error:
        print(f"Error: {error}")
        sys.exit(2)
    if strict_traceability and not (with_contracts and with_gametest):
        print(
            "Error: --strict-traceability requires both --with-contracts "
            "and --with-gametest"
        )
        sys.exit(2)
    if allow_reference_host_only and not with_gametest:
        print(
            "Error: --allow-reference-host-only requires --with-gametest"
        )
        sys.exit(2)
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    # 动态向上解析定位项目根目录 (.agents/gates/)
    project_dir = os.path.realpath(os.path.join(script_dir, "..", ".."))
    datagen_reproducible = False
    
    gradle_cmd = "gradlew.bat" if os.name == 'nt' else "./gradlew"
    gradle_path = os.path.join(project_dir, gradle_cmd)
    
    if not os.path.exists(gradle_path):
        print(f"Error: Gradle wrapper not found at {gradle_path}")
        sys.exit(1)

    if with_contracts:
        print("Step 0: Running L0 major-feature contract gate...")
        contract_script = os.path.join(script_dir, "contract_gate.py")
        contract_command = [
            sys.executable,
            contract_script,
            "--require",
        ]
        if contract_root is not None:
            contract_command.append(contract_root)
        contract_result = subprocess.run(
            contract_command,
            cwd=project_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if contract_result.stdout:
            print(contract_result.stdout.rstrip())
        if contract_result.stderr:
            print(contract_result.stderr.rstrip())
        if contract_result.returncode != 0:
            print("==================================================")
            print("FAILURE: L0 major-feature contract gate failed.")
            print("==================================================")
            sys.exit(contract_result.returncode)

    print("Step 1: Running gradlew compileJava...")
    
    # 运行编译，捕获编译输出
    result = subprocess.run(
        [gradle_path, "compileJava"],
        cwd=project_dir,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding='utf-8',
        errors='replace'
    )
    
    if result.returncode != 0:
        print("==================================================")
        print("FAILURE: Compilation failed. Analyzing syntax errors...")
        print("==================================================")
        
        full_output = result.stdout + "\n" + result.stderr
        
        # 匹配 Java 编译器的标准报错格式
        error_pattern = re.compile(r"^(.*?\.java):(\d+):\s+(?:error|错误):\s+(.*)$", re.MULTILINE)
        errors = error_pattern.findall(full_output)
        
        if not errors:
            print("Could not parse structured compiler errors. Raw output tail:")
            print("--------------------------------------------------")
            lines = full_output.splitlines()
            for line in lines[-40:]:
                print(line)
            sys.exit(1)
            
        print(f"Found {len(errors)} structured compiler errors:")
        print("--------------------------------------------------")
        for idx, (filepath, line_str, msg) in enumerate(errors, 1):
            rel_path = os.path.relpath(filepath, project_dir).replace("\\", "/")
            print(f"Error #{idx}:")
            print(f"  File: {rel_path} (Line {line_str})")
            print(f"  Message: {msg.strip()}")
            
            # 精准读取错误行上下文 (上下各三行)
            try:
                with open(filepath, "r", encoding="utf-8", errors="replace") as f:
                    file_lines = f.readlines()
                line_idx = int(line_str) - 1
                start = max(0, line_idx - 3)
                end = min(len(file_lines), line_idx + 4)
                print("  Context:")
                for l_num in range(start, end):
                    marker = ">>>" if l_num == line_idx else "   "
                    print(f"    {marker} L{l_num+1}: {file_lines[l_num].rstrip()}")
            except Exception as ex:
                print(f"    (Could not load context lines: {ex})")
            print("--------------------------------------------------")
            
        # ==================================================
        # 🔌 AI Diagnostic Suggestion Rules (AND-Regex Chain)
        # ==================================================
        suggestion_triggered = False
        rules_path = os.path.join(script_dir, "repair_rules.json")
        if os.path.exists(rules_path):
            try:
                with open(rules_path, "r", encoding="utf-8") as rf:
                    rules_data: Dict[str, Any] = json.load(rf)
                
                rules: List[Dict[str, Any]] = rules_data.get("rules", [])
                fallback: str = rules_data.get("fallback_suggestion", "")
                
                for rule in rules:
                    patterns: List[str] = rule.get("patterns", [])
                    suggestion: str = rule.get("suggestion", "")
                    
                    # AND-Regex 链条模式：报错全文本必须命中所有的 pattern
                    if patterns and all(re.search(p, full_output) for p in patterns):
                        print("\n[AI SUGGESTION]")
                        print(suggestion)
                        print("--------------------------------------------------")
                        suggestion_triggered = True
                        break # 仅打印第一条匹配中的特化建议，防多重轰炸
                
                if not suggestion_triggered and fallback:
                    print("\n[AI SUGGESTION]")
                    print(fallback)
                    print("--------------------------------------------------")
            except Exception as e:
                print(f"\n(Failed to run AI diagnostics rules: {e})")
        
        print("\nCRITICAL INSTRUCTION FOR AI AGENT:")
        print("You MUST fix the above syntax errors immediately using code editing tools.")
        print("After editing, run this compiler repair script again. Repeat this cycle until compile passes.")
        sys.exit(1)
        
    print("Step 1 SUCCESS: Compilation passed 100%! No syntax errors.")

    full_output = result.stdout + "\n" + result.stderr
    if re.search(
        r"Note:\s+.*?\buses or overrides a deprecated API\b",
        full_output,
        re.IGNORECASE,
    ):
        print(
            "[info] Deprecation Warning: javac reported deprecated API usage; "
            "the summary note does not provide a reliable occurrence count. "
            "Rerun with -Xlint:deprecation/-Xlint:removal for file-level diagnostics."
        )

    step = 2
    # L2 static gate (only when requested; never widens scan beyond src/main/java)
    if with_static and not skip_static:
        print(f"\nStep {step}: Running L2 static_gate.py...")
        static_script = os.path.join(script_dir, "static_gate.py")
        static_command = [sys.executable, static_script]
        if warnings_as_errors:
            static_command.append("--warnings-as-errors")
        static_result = subprocess.run(
            static_command,
            cwd=project_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding='utf-8',
            errors='replace'
        )
        # Forward child output
        if static_result.stdout:
            print(static_result.stdout.rstrip())
        if static_result.stderr:
            print(static_result.stderr.rstrip())
        if static_result.returncode != 0:
            print("==================================================")
            print("FAILURE: L2 static gate failed.")
            print("==================================================")
            sys.exit(static_result.returncode)
        step += 1
    elif with_static and skip_static:
        print("\n(Skipping L2 static gate because --skip-static was set)")
    
    # 如果指定了 --with-data，则接着运行 runData
    if with_data:
        baseline_changes = git_worktree_changes(project_dir)
        verify_data_diff = baseline_changes == []
        if baseline_changes is None:
            if require_data_clean:
                print("==================================================")
                print("FAILURE: DataGen reproducibility requires an accessible Git worktree.")
                print("==================================================")
                sys.exit(1)
            print(
                "\nNOTE: Git worktree unavailable; DataGen diff check will be skipped "
                "(generated JSON validation still runs)."
            )
        elif baseline_changes:
            if require_data_clean:
                print("==================================================")
                print("FAILURE: Git worktree was already dirty before runData.")
                print("Refusing to run while reproducibility verification is required:")
                for line in baseline_changes:
                    print(f"  {line}")
                print("==================================================")
                sys.exit(1)
            print(
                "\nNOTE: pre-existing worktree changes detected; "
                "the post-run Git diff assertion will be skipped to avoid treating "
                "developer work as DataGen drift."
            )
            for line in baseline_changes:
                print(f"  {line}")

        print(f"\nStep {step}: Running gradlew runData (DataGen Update)...")
        data_result = subprocess.run(
            [gradle_path, "runData"],
            cwd=project_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding='utf-8',
            errors='replace'
        )

        if data_result.returncode != 0:
            print("==================================================")
            print("FAILURE: DataGen runData execution failed!")
            print("==================================================")
            print("Raw DataGen output tail:")
            print("--------------------------------------------------")
            lines = (data_result.stdout + "\n" + data_result.stderr).splitlines()
            for line in lines[-40:]:
                print(line)
            sys.exit(1)

        valid, validation_message = validate_generated_resources(project_dir)
        if not valid:
            print("==================================================")
            print("FAILURE: DataGen output validation failed!")
            print("==================================================")
            print(validation_message)
            sys.exit(1)
        print(f"DataGen output OK — {validation_message}.")

        if verify_data_diff:
            post_changes = datagen_git_changes(project_dir)
            if post_changes is None:
                print("==================================================")
                print("FAILURE: Git became unavailable during DataGen reproducibility check.")
                print("==================================================")
                sys.exit(1)
            if post_changes:
                print("==================================================")
                print("FAILURE: runData changed committed DataGen outputs.")
                print("Regenerate and commit these paths, then rerun the gate:")
                for line in post_changes:
                    print(f"  {line}")
                print("==================================================")
                sys.exit(1)
            datagen_reproducible = True
            print("DataGen reproducibility OK — generated resource Git diff is clean.")
        step += 1

    # L2.5 asset gate AFTER DataGen so freshly generated resources count.
    if with_assets:
        print(f"\nStep {step}: Running L2.5 asset_gate.py (registry <-> resource reconciliation)...")
        asset_script = os.path.join(script_dir, "asset_gate.py")
        asset_command = [sys.executable, asset_script]
        if strict_datagen_layout:
            asset_command.append("--strict-datagen-layout")
        if warnings_as_errors:
            asset_command.append("--warnings-as-errors")
        asset_result = subprocess.run(
            asset_command,
            cwd=project_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding='utf-8',
            errors='replace'
        )
        if asset_result.stdout:
            print(asset_result.stdout.rstrip())
        if asset_result.stderr:
            print(asset_result.stderr.rstrip())
        if asset_result.returncode != 0:
            print("==================================================")
            print("FAILURE: L2.5 asset gate failed (missing/dangling resources).")
            print("==================================================")
            sys.exit(asset_result.returncode)
        step += 1

    # L4 executable behavior tests run after resources are reconciled and
    # before the slower dedicated-server smoke. Major/release profiles require
    # at least one real host @GameTest and an unambiguous all-green run.
    if with_gametest:
        print(f"\nStep {step}: Running L4 NeoForge GameTest gate...")
        gametest_command = build_gametest_gate_command(
            script_dir,
            project_dir,
            gametest_timeout,
            allow_reference_host_only=allow_reference_host_only,
        )
        gametest_result = subprocess.run(
            gametest_command,
            cwd=project_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if gametest_result.stdout:
            print(gametest_result.stdout.rstrip())
        if gametest_result.stderr:
            print(gametest_result.stderr.rstrip())
        if gametest_result.returncode != 0:
            print("==================================================")
            print("FAILURE: L4 GameTest gate failed.")
            print("==================================================")
            sys.exit(gametest_result.returncode)
        step += 1

    # Join the validated v2 contract to exact reporter-backed GameTest symbols.
    # Major/release runs always emit the report; --strict-traceability upgrades
    # incomplete or stale coverage from advisory evidence to a blocking gate.
    if with_contracts and with_gametest:
        mode = "strict" if strict_traceability else "advisory"
        print(
            f"\nStep {step}: Running L4 acceptance traceability "
            f"({mode})..."
        )
        traceability_script = os.path.join(
            script_dir, "traceability_gate.py"
        )
        traceability_command = [
            sys.executable,
            traceability_script,
            "--gametest-report",
            "build/reports/gametest-gate.json",
            "--project-dir",
            project_dir,
            "--json-report",
            "build/reports/traceability-gate.json",
        ]
        if contract_root is not None:
            traceability_command.append(contract_root)
        if not strict_traceability:
            traceability_command.append("--advisory")
        traceability_result = subprocess.run(
            traceability_command,
            cwd=project_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if traceability_result.stdout:
            print(traceability_result.stdout.rstrip())
        if traceability_result.stderr:
            print(traceability_result.stderr.rstrip())
        if traceability_result.returncode != 0:
            print("==================================================")
            print("FAILURE: L4 acceptance traceability gate failed.")
            print("==================================================")
            sys.exit(traceability_result.returncode)
        step += 1

    # L3 dedicated-server smoke boot, the last and slowest gate.
    if with_server:
        print(f"\nStep {step}: Running L3 dedicated server smoke test...")
        if not run_server_smoke(gradle_path, project_dir):
            print("==================================================")
            print("FAILURE: L3 server smoke test failed.")
            print("==================================================")
            sys.exit(1)
        step += 1

    print("==================================================")
    passed = ["L1 compile"]
    if with_contracts:
        passed.insert(0, "L0 feature contract")
    if with_static and not skip_static:
        passed.append("L2 static")
    if with_data:
        passed.append("DataGen JSON")
        if datagen_reproducible:
            passed.append("DataGen reproducibility")
    if with_assets:
        passed.append("L2.5 assets")
    if with_gametest:
        passed.append("L4 GameTest")
    if with_contracts and with_gametest:
        passed.append(
            "L4 strict traceability"
            if strict_traceability
            else "L4 traceability report"
        )
    if with_server:
        passed.append("L3 server smoke")
    print(f"SUCCESS: {' + '.join(passed)} passed!")
    print("==================================================")
    sys.exit(0)

if __name__ == "__main__":
    main()
