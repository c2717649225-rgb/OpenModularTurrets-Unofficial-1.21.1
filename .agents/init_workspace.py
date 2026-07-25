import os
import sys

# 动态加载内部真正的初始化引擎
script_dir = os.path.dirname(os.path.abspath(__file__))
real_script_path = os.path.join(
    script_dir, "skills", "workspace_setup", "scripts", "init_workspace.py"
)

if os.path.exists(real_script_path):
    sys.path.insert(0, os.path.dirname(real_script_path))
    import init_workspace

    raise SystemExit(init_workspace.main(sys.argv[1:]))
else:
    print(f"Error: Real init_workspace.py not found at {real_script_path}")
    raise SystemExit(1)
