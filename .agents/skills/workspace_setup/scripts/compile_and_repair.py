import os
import sys
import subprocess
import re
import json
from typing import List, Dict, Any

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
            
        print("==================================================")
        print("SUCCESS: Compilation (+ optional L2/DataGen) completed successfully!")
        print("Please verify that the generated resources (JSONs) exist in your output directory")
        print("(typically src/generated/resources/ or the configured assets output folder).")
        print("==================================================")
        sys.exit(0)
    else:
        print("==================================================")
        if with_static and not skip_static:
            print("SUCCESS: L1 compile + L2 static gate passed!")
        else:
            print("SUCCESS: Compilation passed 100%! No syntax errors.")
        print("==================================================")
        sys.exit(0)

if __name__ == "__main__":
    main()
