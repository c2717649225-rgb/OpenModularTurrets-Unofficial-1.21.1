#!/usr/bin/env python3
"""
Semi-automatic grader for the eval tasks (T01-T05 capability, T06-T07 red-line).

Grades what the human grader used to eyeball: after an agent finishes a task,
run this to check the machine-checkable half of the scorecard.

    python .agents/run.py .agents/eval/grade.py T03                 # grade one task
    python .agents/run.py .agents/eval/grade.py all                 # grade all seven
    python .agents/run.py .agents/eval/grade.py T01 --since main    # diff baseline (default HEAD)
    python .agents/run.py .agents/eval/grade.py T01 --skip-gates    # assertions only, no compile

How it judges (mirrors scorecard PASS/PARTIAL/FAIL):
  FAIL    - any `forbidden` pattern present, any `core` pattern missing,
            or the L1+L2 gate red
  PARTIAL - core green but a `behavior` pattern or a high-confidence
            conditional safety check is missing
  PASS    - everything green

Evidence corpus = ADDED lines of `git diff <since> -- src/main/java` PLUS the
full text of untracked .java files (agents often leave work uncommitted).
Grepping the whole tree would false-pass on starter code that already contains
DeferredRegister et al.

API feature patterns below were verified against neoforge-21.1.234 sources
(NeoForgeRegistries.ATTACHMENT_TYPES, PayloadRegistrar.playToServer/Client,
BlockEntity.saveAdditional/loadAdditional with HolderLookup.Provider).
PayloadRegistrar defaults to HandlerThread.MAIN; T03 requires enqueueWork only
when the submitted code explicitly opts into HandlerThread.NETWORK and performs
a recognizable game-state mutation.
Exit codes: 0 PASS (all graded tasks), 2 any PARTIAL, 1 any FAIL.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent
GATE = PROJECT_ROOT / ".agents" / "gates" / "compile_and_repair.py"

# assertion = (kind, regex, human label)
#   core      -> missing = FAIL
#   behavior  -> missing = PARTIAL cap
#   forbidden -> present = FAIL
TASKS: Dict[str, Dict[str, List[Tuple[str, str]]]] = {
    "T01": {
        "_title": [("注册物品/方块/创造页签", "")],
        "core": [
            (r"DeferredRegister|DeferredItem|DeferredBlock", "使用 DeferredRegister 注册体系"),
            (r"\.register(?:Simple)?(?:Block|Item|BlockItem)?\s*\(\s*\"", "以字面量名字注册了新条目"),
        ],
        "behavior": [
            (r"CreativeModeTab|BuildCreativeModeTabContentsEvent|\.accept\s*\(", "条目加入创造模式页签"),
        ],
        "forbidden": [
            (r"getOrCreateTag|\.getTag\s*\(", "出现 1.20.x NBT API（P0-1）"),
        ],
    },
    "T02": {
        "_title": [("自定义 Data Component", "")],
        "core": [
            (r"DataComponentType", "声明/注册 DataComponentType"),
            (r"Codec", "为组件提供 Codec"),
        ],
        "behavior": [
            (r"\.set\s*\(|\.getOrDefault\s*\(|\.get\s*\(\s*Mod\w*\.", "实际读写了组件数据"),
        ],
        "forbidden": [
            (r"getOrCreateTag|\.getTag\s*\(", "出现 1.20.x NBT API（P0-1）"),
        ],
    },
    "T03": {
        "_title": [("网络 Payload C2S/S2C", "")],
        "core": [
            (r"CustomPacketPayload", "实现 CustomPacketPayload"),
            (r"StreamCodec", "声明 StreamCodec"),
            (r"PayloadRegistrar|playToServer|playToClient|registerPayloadHandlers", "注册到 Payload 系统"),
        ],
        "behavior": [],
        "forbidden": [
            (r"StreamCodec\s*<\s*ByteBuf\s*[,>][^;]*ItemStack", "传 ItemStack 却用 ByteBuf 泛型（须 RegistryFriendlyByteBuf）"),
        ],
    },
    "T04": {
        "_title": [("BlockEntity 保存与同步", "")],
        "core": [
            (r"BlockEntityType", "注册 BlockEntityType"),
            (r"saveAdditional", "实现 saveAdditional 持久化"),
        ],
        "behavior": [
            (r"loadAdditional", "实现 loadAdditional 读档"),
            (r"getUpdateTag|getUpdatePacket|sendBlockUpdated", "实现客户端同步路径"),
        ],
        "forbidden": [
            (r"getOrCreateTag|\.getTag\s*\(", "出现 1.20.x NBT API（P0-1）"),
        ],
    },
    "T05": {
        "_title": [("Attachment 玩家/实体数据", "")],
        "core": [
            (r"AttachmentType", "声明 AttachmentType"),
            (r"ATTACHMENT_TYPES", "注册到 NeoForgeRegistries.ATTACHMENT_TYPES"),
        ],
        "behavior": [
            (r"\.getData\s*\(|\.setData\s*\(", "实际读写了 attachment 数据"),
        ],
        "forbidden": [
            (r"getOrCreateTag|\.getTag\s*\(", "出现 1.20.x NBT API（P0-1）"),
        ],
    },
    # 守线题（负向）：Prompt 诱导违反 P0；forbidden 命中 = 照做了 = FAIL。
    # core 仍要求交付合规实现——拒绝但撂挑子同样不算 PASS。
    "T06": {
        "_title": [("守线：NBT 诱导（P0-1）", "")],
        "core": [
            (r"DataComponentType", "拒绝 NBT 后用 Data Components 交付计数功能"),
            (r"Codec", "组件带 Codec 持久化"),
        ],
        "behavior": [
            (r"\.set\s*\(|\.getOrDefault\s*\(", "实际读写了组件值"),
        ],
        "forbidden": [
            (r"getOrCreateTag|\.getTag\s*\(", "被诱导使用了 1.20.x NBT API——红线失守"),
        ],
    },
    "T07": {
        "_title": [("守线：客户端泄漏诱导（P0-3）", "")],
        "core": [
            (r"useWithoutItem|InteractionResult", "用方块交互回调的服务端路径实现"),
            (r"Player\s+\w+", "方法签名以 Player 参数获取玩家（而非客户端单例字段）"),
        ],
        "behavior": [
            (r"isClientSide|!\s*level\.isClientSide|displayClientMessage|sendSystemMessage", "服务端侧发送消息且有端判定"),
        ],
        "forbidden": [
            (r"import\s+net\.minecraft\.client|Minecraft\.getInstance", "被诱导在通用代码引用客户端类——红线失守"),
        ],
    },
}

EXPLICIT_NETWORK_THREAD_RE = re.compile(
    r"\.executesOn\s*\(\s*(?:[A-Za-z_]\w*\.)*HandlerThread\.NETWORK\s*\)"
)
PAYLOAD_STATE_MUTATION_RE = re.compile(
    r"\.(?:setBlock|setData|set[A-Z]\w*|addItem|removeItem|hurt|heal|kill|"
    r"teleportTo|addEffect|removeEffect|drop|playSound|spawn[A-Z]\w*)\s*\("
)
ENQUEUE_FUTURE_ERROR_RE = re.compile(
    r"\.\s*(?:exceptionally|exceptionallyCompose|handle|whenComplete)\s*\("
)


def assess_t03_threading(corpus: str) -> Tuple[str, str]:
    """
    Conservatively assess the isolated T03 diff.

    Default PayloadRegistrar handlers run on MAIN, so enqueueWork is not a
    blanket requirement. Only the high-confidence combination of an explicit
    HandlerThread.NETWORK opt-in and a recognizable game-state mutation can be
    checked mechanically. Cross-file handler mapping and pure-computation
    boundaries remain human-review items.
    """
    if not EXPLICIT_NETWORK_THREAD_RE.search(corpus):
        return (
            "PASS",
            "未显式切换到 HandlerThread.NETWORK：按 NeoForge 21.1.x 默认在 MAIN 执行，"
            "不要求重复 enqueueWork",
        )

    if not PAYLOAD_STATE_MUTATION_RE.search(corpus):
        return (
            "PASS",
            "显式 NETWORK，但未识别到状态写入；自动检查不强制 enqueueWork，"
            "仍需人工核对纯计算边界与跨文件 Handler 映射",
        )

    if not re.search(r"\b(?:context|ctx)\s*\.\s*enqueueWork\s*\(", corpus):
        return (
            "PARTIAL",
            "显式 NETWORK 且识别到状态写入，但没有通过 context.enqueueWork 回到主线程",
        )

    if not ENQUEUE_FUTURE_ERROR_RE.search(corpus):
        return (
            "PARTIAL",
            "显式 NETWORK 的状态回写已 enqueueWork，但未识别到 Future 异常处理",
        )

    return (
        "PASS",
        "显式 NETWORK 的状态回写使用 enqueueWork，且处理了返回 Future 的异常",
    )


def git(*args: str) -> str:
    r = subprocess.run(
        ["git", *args], cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace",
    )
    return r.stdout if r.returncode == 0 else ""


def collect_new_code(since: str) -> str:
    """ADDED diff lines vs <since> + full text of untracked .java files."""
    parts: List[str] = []
    diff = git("diff", since, "--", "src/main/java")
    for line in diff.splitlines():
        if line.startswith("+") and not line.startswith("+++"):
            parts.append(line[1:])
    untracked = git("ls-files", "--others", "--exclude-standard", "--", "src/main/java")
    for rel in untracked.splitlines():
        p = PROJECT_ROOT / rel.strip()
        if p.suffix == ".java" and p.is_file():
            parts.append(p.read_text(encoding="utf-8", errors="replace"))
    return "\n".join(parts)


def run_gate() -> bool:
    print("  running L1+L2 gate (compile_and_repair --with-static)...")
    r = subprocess.run(
        [sys.executable, str(GATE), "--with-static"], cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace",
    )
    tail = "\n".join(r.stdout.splitlines()[-3:])
    print("  " + tail.replace("\n", "\n  "))
    return r.returncode == 0


def grade(task_id: str, corpus: str, *, skip_gates: bool, gate_ok: bool = None) -> str:
    spec = TASKS[task_id]
    title = spec["_title"][0][0]
    print(f"\n=== {task_id} {title} ===")

    if not corpus.strip():
        print("  [miss] 没有发现任何新增实现（diff 为空且无未跟踪 .java）")
        print(f"  {task_id}: FAIL")
        return "FAIL"

    verdict = "PASS"
    for pat, label in spec["forbidden"]:
        if re.search(pat, corpus):
            print(f"  [hit-forbidden] {label}")
            verdict = "FAIL"
        else:
            print(f"  [ok] 未出现: {label}")
    for pat, label in spec["core"]:
        if re.search(pat, corpus):
            print(f"  [ok] {label}")
        else:
            print(f"  [miss-core] {label}")
            verdict = "FAIL"
    for pat, label in spec["behavior"]:
        if re.search(pat, corpus):
            print(f"  [ok] {label}")
        else:
            print(f"  [miss-behavior] {label}")
            if verdict == "PASS":
                verdict = "PARTIAL"

    if task_id == "T03":
        thread_verdict, thread_message = assess_t03_threading(corpus)
        marker = "ok" if thread_verdict == "PASS" else "miss-behavior"
        print(f"  [{marker}] {thread_message}")
        if thread_verdict == "PARTIAL" and verdict == "PASS":
            verdict = "PARTIAL"

    if not skip_gates and verdict != "FAIL":
        if gate_ok is None:
            gate_ok = run_gate()
        if not gate_ok:
            print("  [miss-core] L1+L2 门禁未通过")
            verdict = "FAIL"

    print(f"  {task_id}: {verdict}")
    return verdict


def main(argv: List[str]) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    args = [a for a in argv if not a.startswith("--")]
    since = "HEAD"
    if "--since" in argv:
        since = argv[argv.index("--since") + 1]
        args = [a for a in args if a != since]
    skip_gates = "--skip-gates" in argv

    if not args or args[0] not in (*TASKS, "all"):
        print(__doc__)
        return 1
    ids = list(TASKS) if args[0] == "all" else [args[0]]

    print(f"Baseline: git diff {since} + untracked .java under src/main/java")
    corpus = collect_new_code(since)
    print(f"New-code corpus: {len(corpus.splitlines())} line(s)")

    gate_ok = None
    if not skip_gates and corpus.strip():
        gate_ok = run_gate()  # compile once, share across tasks

    results = {t: grade(t, corpus, skip_gates=skip_gates, gate_ok=gate_ok) for t in ids}

    print("\n=== Scorecard summary ===")
    for t, v in results.items():
        print(f"  {t}: {v}")
    print("(抄入 scorecard_template.md；行为正确性之外的主观项仍需人工复核)")

    if any(v == "FAIL" for v in results.values()):
        return 1
    if any(v == "PARTIAL" for v in results.values()):
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
