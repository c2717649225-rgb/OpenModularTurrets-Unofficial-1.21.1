# MCP 探针注册权威配置指引

> [!IMPORTANT]
> **请勿在此手动复制并填写绝对路径占位符！**
> 为了防止由于手动拼写或不同操作系统（Windows/Mac/Linux）下的 Python 环境变量冲突导致 MCP 探针拉起失败，请**一律通过运行本地自省脚本获取当前环境的专属注册配置。**

---

## 🚀 自动生成你的多端配置 (0 门槛复制)

请在您的项目根目录下，直接打开终端运行以下指令：

```bash
python .agents/run.py .agents/mcp/minecraft_mcp.py --help
```

### 🎁 运行后你将获得：
1. **自动定位并解析的物理绝对路径**
2. **当前 Python 环境的绝对执行文件路径**（自动替换为 `sys.executable`，彻底防范 Windows 商店广告或 python/python3 找不到的问题）
3. **Codex、Cursor、Cline (Roo Code)、Claude Code、Grok 的专用配置命令或配置块**

Codex 用户执行帮助输出中的两条命令，并重启 Codex：

```text
codex mcp add minecraft-mcp -- "<PYTHON_3_10+>" "<ABS_PROJECT>/.agents/mcp/minecraft_mcp.py"
codex mcp get minecraft-mcp
```

您可以直接在终端输出的高亮框线中复制配置进行一键贴入！

---

## ⚠️ 通用协议规范
*   **通信协议**：JSON-RPC 2.0 over Stdio
*   **分帧格式**：Newline-delimited JSON (每一行是一个独立的完整 JSON)。本探针目前暂不支持 LSP 规范下的 Content-Length 强制报头分帧。
*   **适用客户端**：Cursor, Cline, Roo Code, Claude Code, Grok Build 等 stdio MCP 宿主环境。

---

## 🔒 信任边界与安全限制 (Trust Boundary)
1. **100% 离线安全**：本探针完全在本地运行，绝无任何外网 HTTP 请求或数据向外传输行为，绝对不泄露项目隐私。
2. **数据读取范围**：本探针对磁盘的读取权限被严格限制在当前项目工程根目录 (`PROJECT_PATH`) 以及 Gradle 用户依赖缓存目录 (`GRADLE_USER_HOME` / `~/.gradle`) 内部，绝对禁止读取其他无关敏感路径。
3. **运行前提**：建议仅在受信的本地 Minecraft 开发工程中注册启用本 MCP 服务。
