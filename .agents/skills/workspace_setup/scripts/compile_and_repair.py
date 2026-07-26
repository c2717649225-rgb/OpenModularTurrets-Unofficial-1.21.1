import os
import sys
import subprocess
import re
import json
import threading
import time
from typing import List, Dict, Any


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

    proc = subprocess.Popen(
        [gradle_path, "runServer"],
        cwd=project_dir,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    watchdog = threading.Timer(timeout_s, proc.kill)
    watchdog.start()

    done_seen = False
    fatal_seen = False
    error_lines: List[str] = []
    tail: List[str] = []
    stop_deadline = None
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
                # If stdin isn't wired through Gradle, fall back to kill shortly.
                stop_deadline = time.time() + 45
            if stop_deadline and time.time() > stop_deadline:
                print("Graceful stop not honored (stdin not forwarded) — killing process.")
                proc.kill()
                stop_deadline = None
    finally:
        watchdog.cancel()
        try:
            proc.stdin.close()
        except OSError:
            pass
        proc.wait()

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

    print("==================================================")
    print("Starting Automated Compilation & Error Diagnostics...")
    print("==================================================")
    
    # 检查参数
    with_data = "--with-data" in sys.argv
    with_static = "--with-static" in sys.argv
    skip_static = "--skip-static" in sys.argv
    with_assets = "--with-assets" in sys.argv
    with_server = "--with-server" in sys.argv
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    # 动态向上解析定位项目根目录 (.agents/skills/workspace_setup/scripts/)
    project_dir = os.path.realpath(os.path.join(script_dir, "..", "..", "..", ".."))
    
    gradle_cmd = "gradlew.bat" if os.name == 'nt' else "./gradlew"
    gradle_path = os.path.join(project_dir, gradle_cmd)
    
    if not os.path.exists(gradle_path):
        print(f"Error: Gradle wrapper not found at {gradle_path}")
        sys.exit(1)
        
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

    step = 2
    # L2 static gate (only when requested; never widens scan beyond src/main/java)
    if with_static and not skip_static:
        print(f"\nStep {step}: Running L2 static_gate.py...")
        static_script = os.path.join(script_dir, "static_gate.py")
        static_result = subprocess.run(
            [sys.executable, static_script],
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
        print("DataGen OK — generated resources written (typically src/generated/resources/).")
        step += 1

    # L2.5 asset gate AFTER DataGen so freshly generated resources count.
    if with_assets:
        print(f"\nStep {step}: Running L2.5 asset_gate.py (registry <-> resource reconciliation)...")
        asset_script = os.path.join(script_dir, "asset_gate.py")
        asset_result = subprocess.run(
            [sys.executable, asset_script],
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
    if with_static and not skip_static:
        passed.append("L2 static")
    if with_data:
        passed.append("DataGen")
    if with_assets:
        passed.append("L2.5 assets")
    if with_server:
        passed.append("L3 server smoke")
    print(f"SUCCESS: {' + '.join(passed)} passed!")
    print("==================================================")
    sys.exit(0)

if __name__ == "__main__":
    main()
