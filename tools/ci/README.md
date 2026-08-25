# 质量门禁执行方式（CI 与本地双轨）

历史上仓库曾因推送令牌缺少 `workflow` 权限而删除 GitHub Actions（提交 d085ca6）。
当前提供两条互补路径，按需启用：

## 1. 云端 CI（推荐，需一次性确认推送权限）

`.github/workflows/gates.yml` 已就位：push/PR 时自动运行
L1 编译 + L2 静态 + L0 合同 + L4 GameTest。
工作流只读（`permissions: contents: read`），不使用任何 Secret。

若 `git push` 被拒绝并提示 workflow scope：

```text
! [remote rejected] ... refusing to allow ... to use workflow scope
```

说明当前凭证仍无 `workflow` 权限。两种处理：
- 换用具备 `workflow` 权限的令牌后正常推送；
- 或临时把本文件移出 `.github/workflows/`（历史版本可从 git 找回），
  等凭证升级后再放回。

## 2. 本地 pre-push 钩子（无需任何云端权限）

```bash
git config core.hooksPath tools/git-hooks
```

之后每次 push 前自动运行 L1+L2 快速门禁；L4 因需启动测试服务器，
仍建议按 AGENTS.md 在重大变更后手动运行。
临时跳过：`OMT_SKIP_PUSH_GATE=1 git push`。
