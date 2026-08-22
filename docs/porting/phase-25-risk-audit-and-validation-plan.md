# Phase 25 - 风险审计与验证收口方案

Date: 2026-08-05

Status: revised_for_review

本版修订重点：所有会写入 `run/` 或生成资源的运行移入临时副本；Major/Release 强制启用严格追踪；DataGen 改为实际内容备份与候选提交；Jade 改为服务端/客户端 2×2 矩阵；Common→Client、P0 负向复查、动态资源注册和证据文件归属均增加明确收口条件。本轮又补齐了外部 `.agents` 工具包边界、固定证据根目录、真人 GUI 验收、PowerShell 分块执行和稳定 JSON 数组序列化。`.agents` 的实现、测试和迭代不属于本项目计划，见 [外部工具包边界说明](phase-25-external-agents-toolkit-boundary.md)。

## 1. 目的与范围

本阶段的目标是把当前项目的潜在问题分成三类，并为每一类建立可复现、可审计的证据：

1. 已证实的代码或工程问题；
2. 只违反源码规范、但当前运行时安全的模式；
3. 仍需要专服、客户端或可选兼容模组验证的假设。

本阶段首先是审计和证据收口，不默认重构运行时代码。任何网络行为、客户端隔离或资源生成行为变更，都必须在独立变更后重新建立证据链。

非目标：本阶段不重新决定许可证（当前项目决定为 GPL-3.0-only）、不清理用户的脏工作区、不修改外部 `.agents` 工具包、不做无关重构，也不在证据不足时宣称项目整体完成或可发布。许可证来源/第三方资产的合规审查仍需单独留痕，不能被“许可证选择已决定”替代。

## 2. 当前基线

### 2.1 项目基线

- Minecraft 1.21.1；NeoForge 21.1.234；Java 21。
- Mod ID：openmodularturrets。
- 当前源码约 95 个 Java 文件。
- 当前工作区不是干净状态，已有 README、LICENSE、功能文档修改；本次明确排除历史残留的 `.github/workflows/`，并将本地截图目录 `图片/` 加入 `.gitignore`。
- run/ 已在 .gitignore 中忽略，但忽略只保证 Git 不跟踪，不保证现有世界、配置和日志不会被运行测试污染。
- run/mods/ 当前为空。
- 项目当前使用外部 `.agents` 工具包 1.3.1；它被 `.gitignore` 忽略，门禁脚本和 `.agents/AGENTS.md` 不是本项目变更。审计只把它作为不可变的外部输入，复制/挂载时记录文件清单和 SHA-256，不在本阶段修改它。
- `settings.gradle`、`gradle/`、`gradlew`、`gradlew.bat`、`src/main/templates/` 都是可复现构建所需输入，不能只复制 `build.gradle` 和 `gradle.properties`。
- 当前 `HEAD`、已跟踪差异、未跟踪文件清单及其哈希必须作为同一个 `candidate_id` 的基线保存；仅 `git diff` 不足以描述当前工作区。
- 发布质量线还要求 A1～A5 自动验收和 M1～M8 人工/AI 自查；`pipeline release` 本身不会替代这些人工项，也不会自动构建最终 Mod JAR。
- LICENSE 已替换为 GPL v3 全文，`gradle.properties` 使用 `mod_license=GPL-3.0-only`，模板通过 `${mod_license}` 生成元数据；README 当前使用 GPL-3.0 badge。G5 的许可证选择已关闭，发布前只核验这些来源的一致性，并单独记录第三方来源/资产的许可证情况。

### 2.2 已有验证证据

以下是本阶段开始前已经获得的证据，不作为本阶段最终证据：

- pipeline.py --profile fast：通过；L1 编译和 L2 静态门禁通过，95 个 Java 文件，0 错误、0 警告。
- 严格 Asset gate：通过；但有 5 个动态注册调用无法静态解析，因此资源检查仍有覆盖边界。
- compile_and_repair.py --with-server：通过；专服达到 Done，观察到 0 条 ERROR。
- GameTest discovery：发现 55 个功能测试和 1 个基础设施测试；本阶段尚未产生新的 L4 执行证据。
- Jade 有/无环境矩阵：尚未完成。已有的专服冒烟没有把 Jade JAR 放入 run/mods/。

## 3. 风险登记表

| ID | 风险 | 当前判断 | 证据状态 | 本阶段处理 |
| --- | --- | --- | --- | --- |
| R1 | Common→Client 物理隔离违反或静态漏报 | `OmtTooltips`、`OmtJadePlugin`、`ModNetwork` 均必须按 P0 规则单独判定；#9/#10 的运行时安全不能自动变成规范豁免 | L3 无 Jade 通过，但未覆盖客户端类链接和 `ModNetwork` 入口 | G1 未决前严格按 P0 处理；不对方法或 import 静默白名单 |
| R2 | Jade 可选兼容路径未做真实矩阵 | 可能存在插件发现、服务端扫描、客户端 Provider 或无 Jade 客户端问题 | 仅有源码和本地 API 证据 | 临时副本执行无/有 Jade 的服务端和客户端 2×2 对照 |
| R3 | 静态门禁只检查 `net.minecraft.client.*` import | 不能覆盖项目 `client` 包引用、全限定客户端类名、宽泛 `Dist.CLIENT` 文件豁免；通用范围解析器属于外部工具包能力缺口 | 当前 L2 通过不能覆盖该盲区 | 使用现有 L2，补充模组侧 P0 人工复查；缺口转外部反馈，不在本项目改门禁 |
| R4 | `broadcastAll(..., dimension)` 发送范围过宽 | 性能风险候选，不直接判定为缺陷；必须以接收人数、频率和字节量衡量 | 尚无包频率、单包大小和多人实测 | 预先登记测量场景和阈值，只审计；经批准后才修改 |
| R5 | DataGen 输出漂移或覆盖用户改动 | `runData` 会重写生成文件；脏工作区模式只跳过清洁断言，不能防止覆盖 | 当前没有本阶段 DataGen diff | 所有 `--with-data` 在临时副本执行；实际备份内容、逐项审查、候选 clean gate |
| R6 | 历史报告或不同运行被拼成当前证据 | `build/reports` 中有旧报告，pipeline 内部还会固定写 `gametest-gate.json` 和 `traceability-gate.json` | 已确认存在历史报告；报告自身不含完整源码树哈希 | 使用外置证据目录、唯一 `candidate_id`，运行前后复制并校验所有内部报告 |
| R7 | 发布元数据和项目文件状态不一致 | LICENSE、README、第三方来源说明和未跟踪文件已有明确处理决策 | 已由工作区检查确认 | 保留 GPL-3.0-only 源码许可范围；逐项记录第三方来源与项目文件排除清单 |
| R8 | Asset gate 的动态注册覆盖缺口 | 已知 5 个动态注册调用未被静态解析；当前门禁仍会 PASS | 严格 Asset gate 只有 NOTE，不是失败 | 为每个调用建立人工资源映射；未关闭前不能宣称 L2.5 完整覆盖 |
| R9 | 临时候选遗漏项目差异 | `git diff` 不包含未跟踪的方案文档、合同和工具脚本 | 当前工作区仍有这些项目文件 | `.github/workflows/` 和 `图片/` 明确排除；其余未跟踪项目文件逐项复制/排除并记录哈希；外部工具包不计入项目变更 |
| R10 | 证据目录、候选 ID 或命令包装不安全 | TEMP 目录可能被清理，秒级 ID 可能冲突，PowerShell 原生 stderr/编码可能丢失败码 | 历史执行片段曾使用 `$env:TEMP`、秒级时间戳和裸 `Tee-Object`；本版 runner 已改为 GUID、外置根目录、合并流后 UTF-8 写入 | 继续用统一 fail-closed 命令包装器，并把退出码/日志编码作为基线验收项 |
| R11 | Release 产物和质量线未闭环 | Release pipeline 不包含 `jar`、产物哈希、`verifyReferenceHostNotPackaged` 或 M1～M8 清单 | 由 pipeline dry-run 和 quality_bar 实际规则确认 | 候选内构建/检查产物，并逐项保存质量线结论 |
| R12 | GPL-3.0-only 与第三方代码/资产来源未完成 provenance 审查 | 许可证选择已决定，但选择本身不证明所有输入均可按该许可证发布 | 尚无完整来源清单 | 将来源/许可证核验作为发布前独立项；不得用 G5 已关闭替代它 |

## 4. 不可违反的执行原则

1. 不使用 git reset、git clean、git checkout 覆盖用户工作。
2. 不在审计中途修改运行时行为；审计结论和修复必须分开。
3. 任何“安全”结论都必须说明验证范围，不能把源码推理扩大成完整兼容保证。
4. 涉及 NeoForge API 的代码修改必须先按 MCP-first 规则确认实际签名和线程语义。
5. build/、run/ 和临时审计目录中的输出不能自动作为发布证据；报告必须绑定当前源码和当前运行。
6. 预先存在的 Git 修改不能被 DataGen 或自动修复逻辑覆盖；本阶段默认不在主工作区运行 `runData` 或 `runServer`。
7. 所有外置证据必须保存 `candidate_id`、目标提交、工具包哈希、源码/资源清单哈希、依赖 JAR 哈希和生成时间；临时副本删除前必须先把证据复制到副本外。
8. 任何临时目录删除前必须验证其绝对路径位于本次审计专用根目录内，禁止用宽泛递归删除代替定点清理。
9. 所有门禁、Gradle 和客户端命令必须通过统一包装器执行：合并 stdout/stderr、保存日志、检查原始退出码；禁止用裸 `Tee-Object` 作为通过依据。
10. 证据根目录必须是维护者指定的、位于仓库和系统 TEMP 之外的持久化绝对路径；系统 TEMP 只能作为中间缓存，不能作为最终证据归档。
11. `.agents` 只作为外部工具输入使用；本项目不得修改其脚本、测试、白名单、版本或真源规则。发现工具包缺陷时记录外部反馈并保持本项目结论保守，具体边界见配套文档。

### 4.1 本机证据根目录与跨会话约定

本项目当前执行机器的默认证据根目录固定为：

~~~powershell
# 每个新的 PowerShell 会话都要设置；不要把证据写入仓库或系统 TEMP。
$env:OMT_PHASE25_EVIDENCE_ROOT = 'D:\c128\phase25-evidence'
~~~

该目录位于 `D:\c128\mods\OpenModularTurrets-Unofficial` 之外，也不在系统 TEMP/TMP 下。阶段 0 仍会对绝对路径和边界做 fail-closed 校验；其他机器必须替换为自己的持久绝对路径并通过同样校验。一个 `candidate_id` 生命周期内不得更换证据根目录；跨会话时先设置同一根目录，再通过 `candidate-handoff.json` 恢复，不得重新创建 candidate 或修改主工作区。

### 4.2 PowerShell 执行协议

本文的 PowerShell 块是按阶段组织、共享变量和函数的操作片段，不是可以无脑连续粘贴的单一脚本。实际执行时：

1. 将需要执行的片段按“初始化、阶段、恢复、清理”顺序抽取到证据根目录下的外置 runner（不得放进仓库）；保留原文档提交/哈希、runner SHA-256 和抽取清单。
2. 先用 PowerShell Parser 做语法检查，再在同一会话执行共享函数；不得把恢复块当成新的初始化块运行。
3. 每个阶段只执行一次对应的创建/清理生命周期；失败时保留 runner、哈希和证据，不能用复制粘贴重跑覆盖原证据。
4. 不得机械拼接所有 fenced block；包含 `$candidateWorktree`、`$verificationProject` 等前置变量的片段只能在其依赖已恢复并通过边界校验后执行。

若确实发现 JSON 数组序列化差异，必须用 `ConvertTo-Json -InputObject @(...)`（而不是把数组通过管道传入）并在写出后 `ConvertFrom-Json` 验证数组形状。`candidate-manifest.records` 的追加逻辑已使用 `@($manifest.records) + ...`；其字段形状需保留为数组，但不能据此忽略其他 manifest 的顶层数组检查。

### 4.3 预计耗时与分会话安排

完整执行预计约 6–10 小时，取决于依赖下载、客户端启动和真人 GUI 操作；这是排期信息，不是放宽任何 timeout 或通过条件的理由。可按以下会话拆分：baseline 约 30 分钟；Jade 四个 cell 约 2–3 小时（含真人 C0/C1）；静态/P0、广播和 DataGen 约 1–2 小时；Major 约 90 分钟；Release 与产物/质量线约 150 分钟。每次跨会话都必须保存并恢复同一 `candidate-handoff.json`，不得在会话之间编辑主工作区；发现主工作区基线变化就废弃当前 candidate 并重新冻结。

## 5. 阶段 0：冻结基线

### 目标

记录当前状态，避免后续把既有修改、DataGen 漂移和审计产生的输出混在一起。

### 操作

~~~powershell
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path -LiteralPath ".").Path
if (-not (Test-Path -LiteralPath (Join-Path $repoRoot ".agents\AGENTS.md") -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $repoRoot "gradlew.bat") -PathType Leaf)) {
    throw "Run this block from the project root; .agents/AGENTS.md and gradlew.bat are required."
}

function Test-PathInside {
    param([Parameter(Mandatory)][string]$Child, [Parameter(Mandatory)][string]$Parent)
    $parentCanonical = $Parent.TrimEnd("\")
    $childCanonical = $Child.TrimEnd("\")
    if ([string]::Equals($parentCanonical, $childCanonical, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    $parentUri = New-Object System.Uri($parentCanonical + "\")
    $childUri = New-Object System.Uri($childCanonical)
    return $parentUri.IsBaseOf($childUri)
}

if ([string]::IsNullOrWhiteSpace($env:OMT_PHASE25_EVIDENCE_ROOT)) {
    throw "Set OMT_PHASE25_EVIDENCE_ROOT to a persistent directory outside the repository and system TEMP."
}
if (-not [System.IO.Path]::IsPathRooted($env:OMT_PHASE25_EVIDENCE_ROOT)) {
    throw "OMT_PHASE25_EVIDENCE_ROOT must be an absolute path."
}
if (-not (Test-Path -LiteralPath $env:TEMP -PathType Container)) {
    throw "The system TEMP directory must exist so its boundary can be checked."
}
$tempRoot = (Resolve-Path -LiteralPath $env:TEMP).Path
$tmpRoot = $null
if (-not [string]::IsNullOrWhiteSpace($env:TMP) -and (Test-Path -LiteralPath $env:TMP -PathType Container)) {
    $tmpRoot = (Resolve-Path -LiteralPath $env:TMP).Path
}
$evidenceRequest = [System.IO.Path]::GetFullPath($env:OMT_PHASE25_EVIDENCE_ROOT)
if (Test-PathInside -Child $evidenceRequest -Parent $repoRoot) {
    throw "Evidence root must be outside the repository."
}
if (Test-PathInside -Child $evidenceRequest -Parent $tempRoot) {
    throw "Evidence root must not be under system TEMP."
}
if ($tmpRoot -and (Test-PathInside -Child $evidenceRequest -Parent $tmpRoot)) {
    throw "Evidence root must not be under system TMP."
}
if (-not (Test-Path -LiteralPath $evidenceRequest -PathType Container)) {
    New-Item -ItemType Directory -Path $evidenceRequest | Out-Null
}
$evidenceBase = (Resolve-Path -LiteralPath $env:OMT_PHASE25_EVIDENCE_ROOT).Path
if ((Test-PathInside -Child $evidenceBase -Parent $repoRoot) -or (Test-PathInside -Child $evidenceBase -Parent $tempRoot)) {
    throw "Resolved evidence root points inside the repository or system TEMP."
}
if ($tmpRoot -and (Test-PathInside -Child $evidenceBase -Parent $tmpRoot)) {
    throw "Resolved evidence root points inside the system TMP directory."
}

$candidateId = "phase25-$([guid]::NewGuid().ToString('N'))"
$evidenceRoot = Join-Path $evidenceBase $candidateId
if (Test-Path -LiteralPath $evidenceRoot) {
    throw "Candidate evidence directory already exists; refuse to reuse a candidate ID."
}
New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
$candidateEvidenceRoot = $evidenceRoot

function Write-SourceManifest {
    param([Parameter(Mandatory)][string]$OutputPath)
    $tracked = @(git -C $repoRoot -c core.quotePath=false ls-files)
    if ($LASTEXITCODE -ne 0) { throw "git ls-files failed for tracked inputs." }
    $untracked = @(git -C $repoRoot -c core.quotePath=false ls-files --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw "git ls-files failed for untracked inputs." }
    $manifestPaths = @($tracked + $untracked) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique
    $manifest = foreach ($relativePath in $manifestPaths) {
        $absolutePath = Join-Path $repoRoot $relativePath
        if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
            throw "Manifest path is not a regular file: $relativePath"
        }
        [pscustomobject]@{
            path = ([string]$relativePath).Replace("\", "/")
            sha256 = (Get-FileHash -LiteralPath $absolutePath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $parent = Split-Path -Parent $OutputPath
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    ConvertTo-Json -InputObject @($manifest) -Depth 3 | Set-Content -Encoding UTF8 -LiteralPath $OutputPath
}

function Write-ToolkitManifest {
    param(
        [Parameter(Mandatory)][string]$ToolkitRoot,
        [Parameter(Mandatory)][string]$OutputPath
    )
    $resolvedRoot = (Resolve-Path -LiteralPath $ToolkitRoot).Path
    $manifest = Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File |
        Where-Object {
            $relative = $_.FullName.Substring($resolvedRoot.Length + 1).Replace("\", "/")
            $relative -notmatch '(^|/)(__pycache__/|.*\.pyc$|mcp/mcp_jar_cache\.json$|mcp/mcp_error\.log$|\.env$)'
        } |
        Sort-Object FullName |
        ForEach-Object {
            [pscustomobject]@{
                path = $_.FullName.Substring($resolvedRoot.Length + 1).Replace("\", "/")
                length = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
    ConvertTo-Json -InputObject @($manifest) -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath $OutputPath
}

function Assert-MainWorkspaceStable {
    param([Parameter(Mandatory)][string]$Checkpoint)
    $afterPath = Join-Path $evidenceRoot "manifests\source-manifest-$Checkpoint.json"
    Write-SourceManifest -OutputPath $afterPath
    Invoke-Gate -Name "git-status-$Checkpoint" -WorkingDirectory $repoRoot -LogDirectory (Join-Path $candidateEvidenceRoot "manifests") -Command @("git", "status", "--short", "--untracked-files=all")
    $before = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $evidenceRoot "source-manifest-before.json")
    $after = Get-Content -Raw -Encoding UTF8 -LiteralPath $afterPath
    $comparisonPath = Join-Path $evidenceRoot "manifests\source-comparison-$Checkpoint.json"
    $comparison = [ordered]@{
        candidate_id = $candidateId
        checkpoint = $Checkpoint
        before_manifest_sha256 = (Get-FileHash -LiteralPath (Join-Path $evidenceRoot "source-manifest-before.json") -Algorithm SHA256).Hash.ToLowerInvariant()
        after_manifest_sha256 = (Get-FileHash -LiteralPath $afterPath -Algorithm SHA256).Hash.ToLowerInvariant()
        result = if ($before -ceq $after) { "stable" } else { "changed" }
    }
    $comparison | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath $comparisonPath
    if ($before -cne $after) {
        throw "Main workspace changed during checkpoint $Checkpoint; discard this candidate and restart the audit."
    }
}

function Invoke-Gate {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string[]]$Command,
        [double]$TimeoutSeconds = 0,
        [int[]]$AllowedExitCodes = @(0),
        [string]$LogDirectory = $evidenceRoot
    )
    if ($TimeoutSeconds -lt 0 -or [double]::IsNaN($TimeoutSeconds) -or [double]::IsInfinity($TimeoutSeconds)) {
        throw "TimeoutSeconds must be zero or a finite positive number."
    }
    if ($Command.Count -lt 1 -or [string]::IsNullOrWhiteSpace($Command[0])) {
        throw "Command must contain an executable argv[0]."
    }
    $resolvedWorkingDirectory = (Resolve-Path -LiteralPath $WorkingDirectory).Path
    if (-not (Test-Path -LiteralPath $resolvedWorkingDirectory -PathType Container)) {
        throw "Working directory does not exist: $WorkingDirectory"
    }
    if (-not (Test-Path -LiteralPath $LogDirectory -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
    }
    $resolvedLogDirectory = (Resolve-Path -LiteralPath $LogDirectory).Path
    $logPath = Join-Path $resolvedLogDirectory "$Name.log"
    $argvJson = ConvertTo-Json -InputObject @($Command) -Compress
    @(
        "STARTED_AT_UTC: $([DateTime]::UtcNow.ToString('o'))"
        "WORKING_DIRECTORY: $resolvedWorkingDirectory"
        "COMMAND_ARGV_JSON: $argvJson"
    ) | Set-Content -Encoding UTF8 -LiteralPath $logPath
    $executable = $Command[0]
    $executablePath = $executable
    $localExecutable = Join-Path $resolvedWorkingDirectory $executable
    if (Test-Path -LiteralPath $localExecutable -PathType Leaf) {
        $executablePath = (Resolve-Path -LiteralPath $localExecutable).Path
    }
    Add-Content -Encoding UTF8 -LiteralPath $logPath -Value ("EXECUTABLE_RESOLVED: " + $executablePath)
    $arguments = @($Command | Select-Object -Skip 1)
    $timedOut = $false
    $exitCode = $null
    $exceptionText = $null
    try {
        if ($TimeoutSeconds -eq 0) {
            Push-Location -LiteralPath $resolvedWorkingDirectory
            try {
                $mergedOutput = @(& $executablePath @arguments 2>&1)
                $exitCode = $LASTEXITCODE
                if ($null -eq $exitCode) { $exitCode = 0 }
                if ($mergedOutput.Count -gt 0) {
                    $mergedOutput |
                        ForEach-Object { [string]$_ } |
                        Add-Content -Encoding UTF8 -LiteralPath $logPath
                }
            } finally {
                Pop-Location
            }
        } else {
            $argumentList = foreach ($argument in $arguments) {
                $value = [string]$argument
                if ($value -match '[\s"]') { '"' + $value.Replace('"', '\"') + '"' } else { $value }
            }
            $startInfo = New-Object System.Diagnostics.ProcessStartInfo
            $startInfo.UseShellExecute = $false
            $startInfo.CreateNoWindow = $true
            $startInfo.WorkingDirectory = $resolvedWorkingDirectory
            $startInfo.RedirectStandardOutput = $true
            $startInfo.RedirectStandardError = $true
            if ($executablePath -match '(?i)\.bat$') {
                $startInfo.FileName = $env:ComSpec
                $startInfo.Arguments = '/d /c call "' + $executablePath + '" ' + ($argumentList -join ' ')
            } else {
                $startInfo.FileName = $executablePath
                $startInfo.Arguments = ($argumentList -join ' ')
            }
            $process = New-Object System.Diagnostics.Process
            $process.StartInfo = $startInfo
            if (-not $process.Start()) {
                throw "Could not start process: $executablePath"
            }
            $stdoutTask = $process.StandardOutput.ReadToEndAsync()
            $stderrTask = $process.StandardError.ReadToEndAsync()
            Add-Content -Encoding UTF8 -LiteralPath $logPath -Value ("PROCESS_ID: " + $process.Id)
            if (-not $process.WaitForExit([int][Math]::Ceiling($TimeoutSeconds * 1000))) {
                $timedOut = $true
                & taskkill.exe /PID $process.Id /T /F 2>&1 | Add-Content -Encoding UTF8 -LiteralPath $logPath
                if (-not $process.WaitForExit(5000)) {
                    throw "Timed-out process tree did not exit after taskkill: PID $($process.Id)"
                }
                $exitCode = 124
            } else {
                $process.Refresh()
                $exitCode = $process.ExitCode
                if ($null -eq $exitCode) {
                    throw "Process exited but its exit code could not be read: PID $($process.Id)"
                }
            }
            $stdoutText = $stdoutTask.Result
            $stderrText = $stderrTask.Result
            if ($stdoutText) { Add-Content -Encoding UTF8 -LiteralPath $logPath -Value $stdoutText }
            if ($stderrText) { Add-Content -Encoding UTF8 -LiteralPath $logPath -Value $stderrText }
        }
    } catch {
        $exceptionText = $_.Exception.ToString()
        $exitCode = 125
    }
    if ($exceptionText) {
        Add-Content -Encoding UTF8 -LiteralPath $logPath -Value ("EXCEPTION: " + $exceptionText)
    }
    @(
        "TIMED_OUT: $timedOut"
        "EXIT_CODE: $exitCode"
        "FINISHED_AT_UTC: $([DateTime]::UtcNow.ToString('o'))"
    ) | Add-Content -Encoding UTF8 -LiteralPath $logPath
    if ($AllowedExitCodes -notcontains [int]$exitCode) {
        throw "$Name failed with exit code $exitCode; see $logPath"
    }
}

function Add-CandidateManifestRecord {
    param([Parameter(Mandatory)][object]$Record)
    $manifestPath = Join-Path $candidateEvidenceRoot "candidate-manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Candidate manifest has not been initialized."
    }
    $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json
    $manifest.records = @($manifest.records) + [pscustomobject]$Record
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $manifestPath
}

Invoke-Gate -Name "git-status-before" -WorkingDirectory $repoRoot -Command @("git", "status", "--short", "--branch")
Invoke-Gate -Name "git-head-before" -WorkingDirectory $repoRoot -Command @("git", "rev-parse", "HEAD")
Invoke-Gate -Name "git-status-untracked-before" -WorkingDirectory $repoRoot -Command @("git", "status", "--short", "--untracked-files=all")
Invoke-Gate -Name "git-diff-stat-before" -WorkingDirectory $repoRoot -Command @("git", "-c", "core.safecrlf=false", "diff", "--stat")
Invoke-Gate -Name "git-diff-check-before" -WorkingDirectory $repoRoot -AllowedExitCodes @(0, 1, 2) -Command @("git", "-c", "core.safecrlf=false", "diff", "--check")

Write-SourceManifest -OutputPath (Join-Path $evidenceRoot "source-manifest-before.json")
Write-ToolkitManifest -ToolkitRoot (Join-Path $repoRoot ".agents") -OutputPath (Join-Path $evidenceRoot "toolkit-manifest-before.json")
$baseCommit = (git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($baseCommit)) {
    throw "Could not resolve the baseline HEAD."
}
[ordered]@{
    schema_version = 1
    candidate_id = $candidateId
    base_commit = $baseCommit
    source_manifest = (Join-Path $evidenceRoot "source-manifest-before.json")
    source_manifest_sha256 = (Get-FileHash -LiteralPath (Join-Path $evidenceRoot "source-manifest-before.json") -Algorithm SHA256).Hash.ToLowerInvariant()
    toolkit_manifest = (Join-Path $evidenceRoot "toolkit-manifest-before.json")
    toolkit_manifest_sha256 = (Get-FileHash -LiteralPath (Join-Path $evidenceRoot "toolkit-manifest-before.json") -Algorithm SHA256).Hash.ToLowerInvariant()
    toolkit_version = (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repoRoot ".agents\VERSION")).Trim()
    generated_at_utc = [DateTime]::UtcNow.ToString("o")
    records = @()
} | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $candidateEvidenceRoot "candidate-manifest.json")
~~~

阶段 0 不在主工作区执行 `--with-server`、`runData` 或 `runClient`。已有的 `--with-server` 结果只作为历史基线；需要重新生成时，必须先完成阶段 1 的副本函数定义，再在新建的 baseline 副本内执行对应命令，不能引用尚未定义的 `$auditCopy`。

记录：

- `candidate_id`、Git 状态、当前提交和未跟踪文件清单；
- Java 文件数量；
- src/generated/resources 文件清单和 SHA-256；
- 审计副本的 `run/mods` 内容；
- `.agents/` 工具包清单和 SHA-256；
- 所有门禁的完整输出、JSON 报告和时间戳。

每个阶段结束后调用 `Assert-MainWorkspaceStable -Checkpoint $Checkpoint`；它会把唯一的 `source-manifest-$Checkpoint.json` 和比较结果写入外置证据目录，并同时保存 `git status --short --untracked-files=all`。至少执行 `after-baseline`、`after-jade`、`after-static`、`after-broadcast`、`after-datagen`、`after-verification` 六个 checkpoint，不能反复覆盖一个 `source-manifest-after.json`。若主工作区状态或哈希与基线不同，停止归因并重新建立 `candidate_id`；不能把并行产生的用户修改算作审计结果。

## 6. 阶段 1：Jade 隔离矩阵

### 6.1 为什么不能直接使用当前 run/

当前 compile_and_repair.py --with-server 将运行目录硬编码为项目下的 run/，并会准备 eula.txt 后执行 gradlew runServer。因此 run/ 虽然被 Git 忽略，仍会被测试修改。

本阶段不直接使用当前项目的 run/，而是建立一个临时审计副本：

- 先在主工作区生成 tracked/untracked 全量输入清单和 SHA-256；复制完成后再次生成主工作区清单，任何变化都使本次副本失效并要求重建；
- `auditCopy` 必须位于 `$evidenceRoot\tmp\` 下，且每个 S/C 单元使用不同子目录；
- 复制 `src/`、`build.gradle`、`settings.gradle`、`gradle.properties`、`gradle/`、`gradlew`、`gradlew.bat`、`AGENTS.md`、`.gitignore` 和当前需要的文档/合同；
- 从主工作区显式复制被 `.gitignore` 忽略的 `.agents/` 工具包，并生成工具包文件清单和 SHA-256；工具包不是 Git 候选的一部分，必须作为外部输入单独记录；
- 按 R9 的批准清单复制未跟踪文件；`.github/workflows/` 和 `图片/` 已记录为“候选排除”，方案文档、合同和工具脚本仍须逐项决定，不能默认为已应用；
- 排除 `.git/`、`build/`、`run/`、`.gradle/`、`repo/` 和任何旧日志/世界；
- 在副本中使用全新的 run/；
- 日志、JSON 报告和截图全部写到副本外的 `$evidenceRoot`；
- 审计结束后先校验 `$evidenceRoot` 已完整收集，再验证临时目录绝对路径位于本次审计根目录内，最后删除整个临时副本，而不是清理主工作区。

`auditCopy` 不是 Git worktree，不得复制 `.git/`；复制前后必须用同一份 manifest 比较源文件内容，避免在用户并行编辑时得到混合副本。

副本创建不能依赖“复制整个目录”的模糊操作。默认复制所有 tracked 文件，以及维护者批准的未跟踪输入；当前批准清单包含本方案和配套边界说明，`.github/` 与 `图片/` 明确排除，其他未跟踪文件须在清单中逐项选择后才复制。`.agents/` 另行复制并单独哈希：

~~~powershell
$approvedUntracked = @(
    "docs/porting/phase-25-risk-audit-and-validation-plan.md",
    "docs/porting/phase-25-external-agents-toolkit-boundary.md"
)

function New-AuditCopy {
    param(
        [Parameter(Mandatory)][string]$Cell
    )
    $auditCopy = Join-Path $evidenceRoot "tmp\jade-$Cell"
    if (Test-Path -LiteralPath $auditCopy) {
        throw "Audit copy already exists; refuse to reuse: $auditCopy"
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $auditCopy) | Out-Null
    New-Item -ItemType Directory -Path $auditCopy | Out-Null
    $sourceManifestBefore = Join-Path $candidateEvidenceRoot "source-manifest-$Cell-copy-before.json"
    $sourceManifestAfter = Join-Path $candidateEvidenceRoot "source-manifest-$Cell-copy-after.json"
    Write-SourceManifest -OutputPath $sourceManifestBefore
    if ((Get-Content -Raw -Encoding UTF8 (Join-Path $evidenceRoot "source-manifest-before.json")) -cne (Get-Content -Raw -Encoding UTF8 $sourceManifestBefore)) {
        throw "Main workspace already differs from the frozen baseline before creating audit copy $Cell."
    }

    $trackedInputs = @(git -C $repoRoot -c core.quotePath=false ls-files)
    if ($LASTEXITCODE -ne 0) { throw "Could not enumerate tracked inputs." }
    $copyInputs = @($trackedInputs + $approvedUntracked) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique
    foreach ($relativePath in $copyInputs) {
        $source = Join-Path $repoRoot $relativePath
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Approved copy input is missing or not a file: $relativePath"
        }
        $sourceResolved = (Resolve-Path -LiteralPath $source).Path
        if (-not (Test-PathInside -Child $sourceResolved -Parent $repoRoot)) {
            throw "Approved copy input escapes the repository: $relativePath"
        }
        $destination = Join-Path $auditCopy $relativePath
        if (-not (Test-PathInside -Child ([System.IO.Path]::GetFullPath($destination)) -Parent $auditCopy)) {
            throw "Approved copy destination escapes the audit copy: $relativePath"
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }
    $inputHashRecords = foreach ($relativePath in $copyInputs) {
        $source = Join-Path $repoRoot $relativePath
        $destination = Join-Path $auditCopy $relativePath
        $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        $destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($sourceHash -cne $destinationHash) {
            throw "Input hash changed while creating audit copy ${Cell}: $relativePath"
        }
        [pscustomobject]@{
            path = ([string]$relativePath).Replace("\", "/")
            length = (Get-Item -LiteralPath $destination).Length
            sha256 = $destinationHash
        }
    }
    ConvertTo-Json -InputObject @($inputHashRecords) -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $candidateEvidenceRoot "input-manifest-$Cell.json")

    $toolkitSource = Join-Path $repoRoot ".agents"
    $toolkitDestination = Join-Path $auditCopy ".agents"
    if (-not (Test-Path -LiteralPath $toolkitSource -PathType Container)) {
        throw "Missing external toolkit: $toolkitSource"
    }
    if (-not (Test-PathInside -Child (Resolve-Path -LiteralPath $toolkitSource).Path -Parent $repoRoot)) {
        throw "Toolkit source escapes the repository boundary."
    }
    Copy-Item -LiteralPath $toolkitSource -Destination $toolkitDestination -Recurse
    $toolkitSourceManifest = Join-Path $candidateEvidenceRoot "toolkit-manifest-$Cell-source.json"
    $toolkitCopyManifest = Join-Path $candidateEvidenceRoot "toolkit-manifest-$Cell.json"
    Write-ToolkitManifest -ToolkitRoot $toolkitSource -OutputPath $toolkitSourceManifest
    Write-ToolkitManifest -ToolkitRoot $toolkitDestination -OutputPath $toolkitCopyManifest
    if ((Get-Content -Raw -Encoding UTF8 (Join-Path $evidenceRoot "toolkit-manifest-before.json")) -cne
        (Get-Content -Raw -Encoding UTF8 $toolkitSourceManifest)) {
        throw "External toolkit changed after the frozen baseline; stop this candidate and use a separately approved toolkit input."
    }
    if ((Get-Content -Raw -Encoding UTF8 $toolkitSourceManifest) -cne (Get-Content -Raw -Encoding UTF8 $toolkitCopyManifest)) {
        throw "Toolkit changed while creating audit copy $Cell."
    }
    Write-SourceManifest -OutputPath $sourceManifestAfter
    if ((Get-Content -Raw -Encoding UTF8 $sourceManifestBefore) -cne (Get-Content -Raw -Encoding UTF8 $sourceManifestAfter)) {
        throw "Main workspace changed while creating audit copy $Cell."
    }
    New-Item -ItemType Directory -Path (Join-Path $auditCopy "run\mods") | Out-Null
    return (Resolve-Path -LiteralPath $auditCopy).Path
}

function Remove-AuditCopySafely {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $tmpRoot = ([System.IO.Path]::GetFullPath((Join-Path $evidenceRoot "tmp"))).TrimEnd("\")
    if ([string]::Equals($resolved.TrimEnd("\"), $tmpRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-PathInside -Child $resolved -Parent $tmpRoot)) {
        throw "Refuse to remove a path outside this audit's tmp root: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
    if (Test-Path -LiteralPath $resolved) {
        throw "Audit copy still exists after cleanup: $resolved"
    }
}
~~~

若需要重新生成阶段 0 的可复现基线，先运行下面完整的 baseline 生命周期；它与四个 Jade cell 使用不同目录：

~~~powershell
$baselineCopy = $null
$baselineEvidence = Join-Path $candidateEvidenceRoot "baseline"
New-Item -ItemType Directory -Force -Path $baselineEvidence | Out-Null
try {
    $baselineCopy = New-AuditCopy -Cell "baseline"
    Invoke-Gate -Name "phase-25-fast-baseline" -WorkingDirectory $baselineCopy -LogDirectory $baselineEvidence -TimeoutSeconds 1800 -Command @(
        "python", ".agents/run.py", ".agents/gates/pipeline.py", "--project-dir", $baselineCopy,
        "--profile", "fast", "--json-report", "$baselineEvidence\pipeline-fast.json"
    )
    Invoke-Gate -Name "phase-25-asset-baseline" -WorkingDirectory $baselineCopy -LogDirectory $baselineEvidence -TimeoutSeconds 300 -Command @(
        "python", ".agents/run.py", ".agents/gates/asset_gate.py",
        "--strict-datagen-layout", "--warnings-as-errors"
    )
    Invoke-Gate -Name "phase-25-gametest-discovery" -WorkingDirectory $baselineCopy -LogDirectory $baselineEvidence -TimeoutSeconds 300 -Command @(
        "python", ".agents/run.py", ".agents/gates/gametest_gate.py", "--project-dir", $baselineCopy,
        "--json-report", "$baselineEvidence\gametest-discovery.json"
    )
} finally {
    Remove-AuditCopySafely -Path $(if ($baselineCopy) { $baselineCopy } else { Join-Path $evidenceRoot "tmp\jade-baseline" })
}
Assert-MainWorkspaceStable -Checkpoint "after-baseline"
~~~

`New-AuditCopy` 执行前后必须用阶段 0 的源清单核对主工作区；复制到副本后的每个输入文件还要按同一路径重新计算 SHA-256，并把 `.agents/` 的相对路径、大小、哈希和工具包版本记录到 `$candidateEvidenceRoot\toolkit-manifest-$cell.json`。任何复制中途主工作区或工具包哈希变化都使该 cell 作废并要求重建。

### 6.2 Jade 版本与来源

优先使用本机 Gradle 缓存中的精确版本：

~~~text
$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\maven.modrinth\jade\15.10.0+neoforge\*\jade-15.10.0+neoforge.jar
~~~

实际发现必须使用显式数量检查：

~~~powershell
$jadeRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\maven.modrinth\jade\15.10.0+neoforge"
$jadeCandidates = @(Get-ChildItem -LiteralPath $jadeRoot -Recurse -File -Filter "jade-15.10.0+neoforge.jar")
if ($jadeCandidates.Count -ne 1) {
    throw "Expected exactly one Jade JAR, found $($jadeCandidates.Count)."
}
$jadeJar = $jadeCandidates[0].FullName
$jadeHash = (Get-FileHash -LiteralPath $jadeJar -Algorithm SHA256).Hash.ToLowerInvariant()
$jadeSourceEvidence = Join-Path $candidateEvidenceRoot "jade\source"
New-Item -ItemType Directory -Force -Path $jadeSourceEvidence | Out-Null
Invoke-Gate -Name "jade-jar-list" -WorkingDirectory $jadeSourceEvidence -LogDirectory $jadeSourceEvidence -TimeoutSeconds 120 -Command @("jar", "tf", $jadeJar)
Invoke-Gate -Name "jade-metadata-extract" -WorkingDirectory $jadeSourceEvidence -LogDirectory $jadeSourceEvidence -TimeoutSeconds 120 -Command @("jar", "xf", $jadeJar, "META-INF/neoforge.mods.toml")
$jadeMetadataPath = Join-Path $jadeSourceEvidence "META-INF\neoforge.mods.toml"
if (-not (Test-Path -LiteralPath $jadeMetadataPath -PathType Leaf)) {
    throw "Jade JAR does not contain META-INF/neoforge.mods.toml."
}
Add-CandidateManifestRecord -Record ([ordered]@{
    kind = "jade-input"
    path = $jadeJar
    sha256 = $jadeHash
    metadata_path = $jadeMetadataPath
    version = "15.10.0+neoforge"
})
~~~

0 个或多个都停止矩阵，不允许凭通配符选择。若缓存不存在，阶段保持阻塞；维护者必须从 Jade 官方发布页手工取得 `15.10.0+neoforge` 的精确 JAR 到证据根目录下的外部输入目录，并补记来源 URL、下载时间、文件名和 SHA-256，再把 `$jadeJar` 指向该已固定输入。禁止脚本自动选择 latest、从不明缓存挑选或把下载物直接放进主工作区 `run/mods/`。

将选中的 JAR 只复制到每个审计副本的 `run/mods/`，不使用指向缓存的符号链接；主工作区 `.gitignore` 不能被当作隔离措施。

运行前记录：

- JAR 完整路径；
- JAR SHA-256；
- Jade 的 NeoForge 元数据；
- Minecraft/NeoForge 版本匹配情况；
- `java -version`、`gradlew.bat --version`、`gradle.properties` 中的精确版本和实际工具包哈希；
- Gradle/Minecraft/NeoForge/Parchment 依赖是否来自本地缓存；若发生网络下载，记录下载结果和运行时间，不把网络可用性当作测试通过条件。

`dependency-report` 只提供解析依据，不能代替依赖哈希：根据该次运行实际解析出的 compile/runtime classpath，逐个记录 JAR 的模块坐标、绝对路径、大小和 SHA-256；无法把坐标唯一映射到实际 JAR 时，cell 证据不完整并保持“未验证”。不得用缓存目录通配符或只记录 Jade 一个 JAR 冒充完整依赖清单。

### 6.3 对照顺序

每个单元都使用全新的临时副本；每个单元开始前设置 `$cell`，调用 `New-AuditCopy -Cell $cell` 得到 `$auditCopy`，然后仅在 S1/C1 执行：

~~~powershell
$cellEvidence = Join-Path $candidateEvidenceRoot "jade\$cell"
New-Item -ItemType Directory -Force -Path $cellEvidence | Out-Null
$jadeDestination = Join-Path $auditCopy "run\mods\jade-15.10.0+neoforge.jar"
Copy-Item -LiteralPath $jadeJar -Destination $jadeDestination
if ((Get-FileHash -LiteralPath $jadeDestination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $jadeHash) {
    throw "Jade JAR hash changed while copying into $cell."
}
Add-CandidateManifestRecord -Record ([ordered]@{
    kind = "jade-cell-input"
    cell = $cell
    path = $jadeDestination
    sha256 = $jadeHash
})
~~~

每个 cell 都要执行以下目录断言；S0/C0 的 `$modEntries.Count` 必须为 0，S1/C1 必须恰好有一个同名 regular file，且不能存在子目录或第二个 JAR：

~~~powershell
$modEntries = @(Get-ChildItem -LiteralPath (Join-Path $auditCopy "run\mods") -Force)
$hasJade = $cell -in @("S1", "C1")
if ((-not $hasJade -and $modEntries.Count -ne 0) -or
    ($hasJade -and ($modEntries.Count -ne 1 -or $modEntries[0].PSIsContainer -or $modEntries[0].Name -ne "jade-15.10.0+neoforge.jar"))) {
    throw "Unexpected run/mods contents for $cell."
}
    ConvertTo-Json -InputObject @($modEntries | Select-Object FullName, Length, LastWriteTime) -Depth 3 |
        Set-Content -Encoding UTF8 -LiteralPath (Join-Path $cellEvidence "run-mods-manifest.json")
Invoke-Gate -Name "$cell-java-version" -WorkingDirectory $auditCopy -LogDirectory $cellEvidence -TimeoutSeconds 120 -Command @("java", "-version")
Invoke-Gate -Name "$cell-gradle-version" -WorkingDirectory $auditCopy -LogDirectory $cellEvidence -TimeoutSeconds 300 -Command @("gradlew.bat", "--version")
Invoke-Gate -Name "$cell-dependency-report" -WorkingDirectory $auditCopy -LogDirectory $cellEvidence -TimeoutSeconds 900 -Command @(
    "gradlew.bat", "dependencies", "--configuration", "compileClasspath", "--no-daemon", "--console=plain"
)
~~~

矩阵为：

表中的命令是验收所需的 argv；实际执行必须通过 `Invoke-Gate`，使服务端/客户端日志和退出码进入对应 cell 证据目录。C0/C1 的 `Invoke-Gate` 在真人维护者完成 GUI 验收、截图并正常关闭客户端后才返回。

| 单元 | `run/mods/` | 启动与验收 |
| --- | --- | --- |
| S0 | 空 | `python .agents/run.py .agents/gates/compile_and_repair.py --with-server`；达到 `Done` 且无未解释错误 |
| S1 | 仅 Jade 15.10.0 JAR | 同上；达到 `Done` 且无 OMT/Jade 错误 |
| C0 | 空 | `gradlew.bat runClient --no-daemon --console=plain`；在规定超时内进入世界且无 OMT/客户端加载错误 |
| C1 | 仅 Jade 15.10.0 JAR | 同上；对 OMT 基座和炮塔悬停，确认 Jade 客户端 Provider 显示服务端数据 |

每个 cell 必须按以下生命周期执行，不能复用副本：设置 `$cell` → 在 `try` 中调用 `New-AuditCopy` → 复制可选 Jade → 保存输入清单 → 执行对应命令 → 保存日志/截图/运行时模组清单 → 在证据完整性检查通过后清理。`finally` 必须调用 `Remove-AuditCopySafely`；若 `New-AuditCopy` 尚未返回，则按 `Join-Path $evidenceRoot "tmp\jade-$cell"` 清理可能留下的半成品。命令或人工验收失败时也走 `finally`，但保留 `$cellEvidence`，不重用失败副本。

S0/C0 必须记录实际加载模组列表并证明 Jade 不在运行时模组集合中；S1/C1 必须记录运行时 Jade Mod ID、JAR 路径和哈希。客户端启动和进入世界默认限时 900 秒；超时只能记为失败/未验证，不能延长后当作通过。截图后由操作者正常关闭客户端，若进程未退出，只能按已记录的 Gradle/Java PID 终止该进程树，不能使用全局进程名杀进程。

四个 cell 的实际执行使用同一 wrapper，但使用不同日志目录和明确的项目目录：

~~~powershell
if ($cell -in @("S0", "S1")) {
    Invoke-Gate -Name "$cell-server" -WorkingDirectory $auditCopy -LogDirectory $cellEvidence -TimeoutSeconds 1200 -Command @(
        "python", ".agents/run.py", ".agents/gates/compile_and_repair.py", "--with-server"
    )
} else {
    Invoke-Gate -Name "$cell-client" -WorkingDirectory $auditCopy -LogDirectory $cellEvidence -TimeoutSeconds 900 -Command @(
        "gradlew.bat", "runClient", "--no-daemon", "--console=plain"
    )
}
~~~

启动命令完成后，立即把副本的 `run/logs/` 中本次生成的日志按时间/启动 PID 复制到 `$cellEvidence`，并记录加载模组清单；不能只引用副本内会被下一 cell 删除的 `latest.log`。客户端必须暂停等待真人维护者操作；AI、源码推理、主菜单截图和日志中的“启动成功”都不能代替 GUI 验收。截图后由真人正常关闭客户端；wrapper 的超时路径必须记录 `TIMED_OUT=true`、日志中的实际 `PROCESS_ID`、按该 PID 执行的 `taskkill /PID 实际 PROCESS_ID /T /F` 输出和最终退出码。

专服 cell 的全新 `run/` 默认没有 `server.properties`。Minecraft 1.21.1 的 `Settings.loadFromFile` 会在文件不存在时记录 `Failed to load properties from file: server.properties` / `NoSuchFileException`，返回默认属性，随后 `Main` 调用 `DedicatedServerSettings.forceSave()` 写出默认文件；这是可由 NeoForm 真源码确认的首次初始化噪声，不是 OMT/Jade 错误，但不得从日志中删除或笼统归类为“无 ERROR”。为了使 S0/S1 的 fail-closed 判定确定且可重复，正式执行前应在对应隔离副本的 `run/server.properties` 创建空文件（只写副本，不改主工作区），记录该输入的路径、大小和 SHA-256，再启动专服。若某次诊断有意保留缺失文件，必须将上述**精确**一条记录为 `expected_initialization_noise`，其余 ERROR、异常栈或 OMT/Jade 相关错误仍按失败处理。

客户端单元必须记录启动命令、客户端日志和截图。真人维护者在创造模式放置至少 `openmodularturrets:turret_base_tier_one` 与 `openmodularturrets:machine_gun_turret`；截图/日志至少能对应到基座的 active/energy/kills 或炮塔的 tier/range/damage/energy 字段。另取一个 OMT 物品分别验证未按 Shift 和按 Shift 的 `OmtTooltips` 行为。C1 还必须悬停基座/炮塔并确认 Jade 客户端 Provider 显示服务端数据。仅看客户端成功进入主菜单不算 Provider 或 tooltip 验证。

C0/C1 的真人验收必须在 `$cellEvidence/gui-acceptance.json` 留下结构化记录，至少包括：`cell`、`actor`、开始/结束 UTC 时间、截图相对路径及 SHA-256、放置的方块 ID、Jade Provider 是否确认、未按 Shift/按 Shift tooltip 是否确认、是否正常关闭以及备注。任一 GUI 操作、截图、结构化记录或正常关闭缺失，cell 标记为“未验证”，R2 保持未关闭，不能进入 `audit_closed` 或 `release_ready`。本执行环境没有可替代真人的 GUI 代理时，必须在此处暂停并等待维护者完成操作。

每个单元保存独立日志、截图、JAR 哈希和副本输入清单到副本外的 `$evidenceRoot/jade/$cell/`，不能复用 `run/logs/latest.log` 的旧内容。矩阵结束后无需在同一副本“删除 Jade 恢复状态”，因为 S0/C0 已由新副本证明无 Jade 状态。

清理非 Git 审计副本时，只允许调用已经解析且确认位于 `$evidenceRoot\tmp\` 下的 `Remove-AuditCopySafely -Path $auditCopy`；该函数内部才可对单个路径使用 `Remove-Item -LiteralPath ... -Recurse -Force`，清理后必须确认路径不存在。不能对 `$env:TEMP`、仓库根目录或通配路径执行递归删除。

检查关键词：

- Done；
- NoClassDefFoundError；
- ClassNotFoundException；
- Error loading plugin；
- Mixin apply failed；
- OMT/Jade 相关 ERROR。

### 6.4 通过标准

- 无 Jade 服务端达到 Done；
- 有 Jade 服务端达到 Done，且没有未解释的 OMT/Jade 错误；
- S0/S1 的 `server.properties` 初始化方式已记录；预置文件的正式 cell 不应再出现上述首次初始化 ERROR，诊断 cell 中的该条噪声必须有源码归因；
- 无 Jade 客户端能进入世界且没有缺失 Jade 导致的 OMT 错误；
- 有 Jade 客户端能调用客户端 Provider，OMT 信息正常显示；
- 临时运行副本删除后，主工作区 Git 状态只保留审计前状态。

四个单元缺一不可。只有服务端启动通过、但没有 C0/C1 客户端证据时，R2 仍保持“部分验证”。

矩阵四个 cell 的副本都已安全删除、证据目录已检查后，执行：

~~~powershell
Assert-MainWorkspaceStable -Checkpoint "after-jade"
~~~

## 7. 阶段 2：外部门禁静态审计与模组 P0 复查

### 7.1 工具包边界

本阶段只使用当前固定版本的外部 `.agents` 工具包，不修改 `static_gate.py`、任何 `test_*.py`、白名单、`.agents/AGENTS.md` 或工具包版本。工具包的实现、回归测试和迭代属于另一个项目，见 [外部工具包边界说明](phase-25-external-agents-toolkit-boundary.md)。

审计副本可以携带一份与基线 SHA-256 相同的 `.agents` 只读副本，以满足门禁脚本按自身路径发现工具包的要求；它不是模组变更，不能纳入项目变更报告或发布 JAR。候选 worktree 也必须保持工具包与模组变更分离，工具包版本/文件清单只作为外部输入记录。

### 7.2 运行现有门禁

先在新的审计副本中运行既有 L2，并保存工具包版本、完整文件清单/哈希、实际命令、工作目录、stdout/stderr 和退出码：

~~~powershell
$staticCopy = $null
try {
    $staticCopy = New-AuditCopy -Cell "static"
    Invoke-Gate -Name "static-gate-l2" -WorkingDirectory $staticCopy -LogDirectory (Join-Path $candidateEvidenceRoot "static") -TimeoutSeconds 1800 -Command @(
        "python", ".agents/run.py", ".agents/gates/compile_and_repair.py", "--with-static"
    )
} finally {
    Remove-AuditCopySafely -Path $(if ($staticCopy) { $staticCopy } else { Join-Path $evidenceRoot "tmp\jade-static" })
}
~~~

L2 的 PASS 只证明当前外部门禁报告 PASS，不能自动证明 Common→Client 物理隔离、惰性类加载或 Jade Provider 安全。若门禁漏报、误报或无法解析，不能在本项目中添加白名单来消除结果；只能对模组代码做最小物理隔离修复，或保留风险未关闭，并把工具包改进需求转交配套文档所述的外部项目。

### 7.3 P0 负向复查

无论 L2 结果如何，都必须对模组源码进行独立 P0 复查，逐项记录匹配数、文件/行号和结论：

- ItemStack 旧 NBT API：`getOrCreateTag`、`getTag` 及同类 1.20.x 入口；
- Record Codec：`group(...)`、`.apply(...)` 和 record 构造器/适配 lambda 的字段映射顺序；
- 静态字段/静态块中对注册项的 `.get()`；
- 所有 Payload 注册点，区分默认接收端主线程与显式 `HandlerThread.NETWORK`；后者逐项检查不得在线程阶段访问 `Level`/`Entity`/玩家状态，并通过 `context.enqueueWork(...)` 回写且处理返回 `CompletableFuture` 异常；
- 所有 `StreamCodec.composite` 字段数量、`ItemStack` 网络 codec 的 `RegistryFriendlyByteBuf` 泛型以及 7 字段以上的手写 codec；
- 所有 `@EventBusSubscriber` 监听方法的 static 属性及 NeoForge 21.1.234 的总线语义；21.1.181+ 默认自动分流，任何显式 `bus` 都必须说明必要性，不能沿用旧版本默认总线假设；
- Common→Client 的 import、全限定引用、类型签名和类初始化路径，重点复核 `OmtTooltips`、`OmtJadePlugin`、`ModNetwork`；源码推理不能替代物理隔离结论。

任何无法由门禁证明安全的项都保留为“待人工确认”，不能以“L2 通过”关闭。

外部门禁清单、版本/哈希和 P0 复查证据全部保存后，执行 `Assert-MainWorkspaceStable -Checkpoint "after-static"`。任何工具包改进意见只进入外部工具包反馈记录，不改变本项目 candidate 的源码范围。

## 8. 阶段 3：广播范围性能审计

本阶段只产生审计报告，不改 broadcastAll 行为。

审计内容：

- 所有 broadcastAll(..., dimension) 调用点；
- 触发事件和理论频率；
- 包含的 BlockEntity 状态；
- 维度内玩家数量和区块追踪范围；
- 与 sendToPlayersTrackingChunk 等替代方案的语义差异；
- 是否存在可复现的多人网络放大。

在开始实测前登记固定场景和判定阈值，至少包含：

- 单人、同维度多玩家、不同区块玩家和不同维度玩家四类接收者；
- 每个调用点的触发次数/秒、接收人数/次、编码后字节数/次、每玩家字节数/秒和峰值；
- `broadcastAll` 实际接收人数与区块追踪替代方案接收人数的差值及比例；
- 采样持续时间、服务器 TPS、玩家数量、维度和触发操作；
- G3 批准的升级阈值。没有预先登记阈值时，只能报告观察，不能把理论范围升级为已确认性能缺陷。

采集方式必须在实测前写入报告：优先使用不改变行为的网络/服务端 profiler；若无法得到编码后字节数，则在一次性审计副本中加入临时诊断计数器或独立 codec harness，记录调用点、接收者和字节量，测试后销毁诊断补丁。单纯 grep、日志行数或玩家主观感受不能作为包大小/频率证据。

报告必须区分：

- API 语义确认的接收范围；
- 理论性能风险；
- 实际测试观察；
- 尚未验证的假设。

若维护者批准修复：

1. 先用 MCP/源码确认目标 API；
2. 单独修改发送范围；
3. 单独记录变更和行为假设；
4. 重新执行网络相关 GameTest、L3、客户端和多人验证；
5. 不使用阶段 3 之前的证据支持修复后的结论。

广播报告和（如有）独立诊断副本均已归档/销毁后，执行 `Assert-MainWorkspaceStable -Checkpoint "after-broadcast"`。

## 9. 阶段 4：DataGen 与生成资源漂移

### 9.1 脏工作区限制

`compile_and_repair.py --verify-data-clean` 会在 DataGen 前拒绝已有 Git 修改；而不带该选项时，脚本会在脏工作区继续执行 `runData`，这不能防止已有生成文件被覆盖。因此本阶段默认禁止在主工作区执行 `runData`。

主工作区只允许执行：

- 当前状态和生成物的只读清单；
- 不会调用 `runData` 的资源门禁；
- 对临时副本生成结果的人工审查。

真正的 `verify-data-clean` 必须在包含目标变更、但 Git 状态干净的临时候选中运行。该候选不能只是“从 HEAD 创建后再留下未提交补丁”：先把经批准的 tracked diff 和未跟踪文件应用到临时候选，在候选中创建临时提交，使 `git status --porcelain=v1 --untracked-files=all` 为空，再执行 clean gate。

### 9.2 当前工作区的 DataGen 流程

执行前保存：

- `git status --short --untracked-files=all`；
- `git diff -- src/generated/resources`；
- `src/generated/resources/` 的实际内容副本，保存到副本外的 `$evidenceRoot/datagen/pre/`；
- 生成资源全量文件列表、相对路径、大小和 SHA-256；
- 当前 DataProvider 源文件和源码清单哈希。

执行前的主工作区只读记录也通过 wrapper 保存；这些命令不调用 DataGen：

~~~powershell
$datagenEvidence = Join-Path $candidateEvidenceRoot "datagen"
$datagenPre = Join-Path $datagenEvidence "pre"
New-Item -ItemType Directory -Force -Path $datagenPre | Out-Null
Invoke-Gate -Name "datagen-main-status-before" -WorkingDirectory $repoRoot -LogDirectory $datagenEvidence -Command @("git", "status", "--short", "--untracked-files=all")
Invoke-Gate -Name "datagen-main-diff-before" -WorkingDirectory $repoRoot -LogDirectory $datagenEvidence -Command @("git", "diff", "--", "src/generated/resources")
$mainGeneratedRoot = Join-Path $repoRoot "src\generated\resources"
if (Test-Path -LiteralPath $mainGeneratedRoot -PathType Container) {
    Get-ChildItem -LiteralPath $mainGeneratedRoot -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $datagenPre -Recurse
    }
}
$preManifest = @(Get-ChildItem -LiteralPath $datagenPre -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object FullName |
    ForEach-Object {
        [pscustomobject]@{
            path = $_.FullName.Substring($datagenPre.Length + 1).Replace("\", "/")
            length = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
ConvertTo-Json -InputObject @($preManifest) -Depth 3 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $datagenEvidence "pre-manifest.json")

$datagenCopy = $null
try {
    $datagenCopy = New-AuditCopy -Cell "datagen"
    Invoke-Gate -Name "datagen-with-data" -WorkingDirectory $datagenCopy -LogDirectory $datagenEvidence -TimeoutSeconds 1800 -Command @(
        "python", ".agents/run.py", ".agents/gates/compile_and_repair.py", "--with-data"
    )
    $generatedRoot = Join-Path $datagenCopy "src\generated\resources"
    $postManifest = @(Get-ChildItem -LiteralPath $generatedRoot -Recurse -File |
        Sort-Object FullName |
        ForEach-Object {
            [pscustomobject]@{
                path = $_.FullName.Substring($generatedRoot.Length + 1).Replace("\", "/")
                length = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        })
    ConvertTo-Json -InputObject @($postManifest) -Depth 3 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $datagenEvidence "post-manifest.json")
} finally {
    Remove-AuditCopySafely -Path $(if ($datagenCopy) { $datagenCopy } else { Join-Path $evidenceRoot "tmp\jade-datagen" })
}
~~~

非 Git 的 `auditCopy` 不包含 `.git/`，因此其中不能执行 `git status` 或 `git diff`；上面的 `datagen-with-data` 只在副本中执行，随后只做文件级清单和内容比较：

~~~powershell
$preManifestText = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $datagenEvidence "pre-manifest.json")
$postManifestText = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $datagenEvidence "post-manifest.json")
[ordered]@{
    pre_manifest_sha256 = (Get-FileHash -LiteralPath (Join-Path $datagenEvidence "pre-manifest.json") -Algorithm SHA256).Hash.ToLowerInvariant()
    post_manifest_sha256 = (Get-FileHash -LiteralPath (Join-Path $datagenEvidence "post-manifest.json") -Algorithm SHA256).Hash.ToLowerInvariant()
    raw_equal = $preManifestText -ceq $postManifestText
    review_required = $true
} | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $datagenEvidence "manifest-comparison.json")
~~~

另外对生成目录重新生成全量文件清单和 SHA-256，并与审计前清单逐项比较；`git diff` 不足以发现新增未跟踪文件。每个变化必须标记为新增、删除或内容变化，并关联到 DataProvider 或明确源码变化。

只接受能追溯到 DataProvider 或明确源码变化的差异。排序、换行、缓存和无关重排不得自动保留或提交。

默认不向主工作区恢复任何生成物。如果必须恢复，只能恢复到产生它的同一审计副本；维护者明确批准恢复主工作区时，必须按文件逐项确认目标仍与 `datagen/pre/` 的基线一致，并在复制前后核对路径和哈希。不能用“只有哈希、没有原文”的清单恢复文件，也不使用全仓库清理命令。

### 9.3 清洁候选 worktree

当需要执行 --verify-data-clean 或 release profile 时：

从本节创建候选开始，到第 11.5 节最终产物证据复制完成，必须作为同一个候选生命周期放进 `try/finally`；不能只执行创建块后退出 PowerShell，也不能把清理块当作独立的可选步骤。若分多个会话，必须把 `$candidateWorktree`、`$candidateCommit`、`$candidateEvidenceRoot` 和清理责任写入外部 handoff 记录，并在下一会话先验证 worktree 注册和路径边界。

G4 未批准时不得创建候选；这种情况下只能完成审计副本、静态审查和报告，不能执行 `--verify-data-clean`、Release profile 或“可发布”收口。

~~~powershell
$currentHead = (git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($currentHead)) {
    throw "Could not resolve the current main-workspace HEAD."
}
if ($currentHead -cne $baseCommit) {
    throw "Main-workspace HEAD changed after the frozen baseline; discard this candidate and restart."
}
$candidateWorktree = Join-Path $evidenceRoot "tmp\candidate"
if (Test-Path -LiteralPath $candidateWorktree) {
    throw "Candidate worktree path already exists; refuse to reuse it."
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $candidateWorktree) | Out-Null
if (-not (Test-PathInside -Child (Split-Path -Parent $candidateWorktree) -Parent $evidenceRoot)) {
    throw "Candidate worktree parent is outside this audit evidence root."
}
Invoke-Gate -Name "candidate-worktree-add" -WorkingDirectory $repoRoot -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @(
    "git", "worktree", "add", "--detach", $candidateWorktree, $baseCommit
)

$trackedPatch = Join-Path $candidateEvidenceRoot "tracked.patch"
Invoke-Gate -Name "tracked-diff-export" -WorkingDirectory $repoRoot -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @(
    "git", "-c", "core.safecrlf=false", "diff", "--binary", $baseCommit, "--output=$trackedPatch"
)
if ((Get-Item -LiteralPath $trackedPatch).Length -gt 0) {
    Invoke-Gate -Name "tracked-diff-apply" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @(
        "git", "apply", "--binary", $trackedPatch
    )
}

foreach ($relativePath in $approvedUntracked) {
    $source = Join-Path $repoRoot $relativePath
    $destination = Join-Path $candidateWorktree $relativePath
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Approved untracked input is missing: $relativePath"
    }
    if (-not (Test-PathInside -Child (Resolve-Path -LiteralPath $source).Path -Parent $repoRoot)) {
        throw "Approved untracked input escapes the repository: $relativePath"
    }
    if (-not (Test-PathInside -Child ([System.IO.Path]::GetFullPath($destination)) -Parent $candidateWorktree)) {
        throw "Approved untracked destination escapes the candidate worktree: $relativePath"
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $source -Destination $destination
    $destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceHash -cne $destinationHash) {
        throw "Untracked input hash changed while copying: $relativePath"
    }
}

$candidateToolkit = Join-Path $candidateWorktree ".agents"
if (Test-Path -LiteralPath $candidateToolkit) {
    throw "Candidate toolkit path already exists; refuse to merge toolkits."
}
if (-not (Test-PathInside -Child (Resolve-Path -LiteralPath (Join-Path $repoRoot ".agents")).Path -Parent $repoRoot)) {
    throw "Toolkit source escapes the repository boundary."
}
Copy-Item -LiteralPath (Join-Path $repoRoot ".agents") -Destination $candidateToolkit -Recurse
$candidateToolkitSourceManifest = Join-Path $candidateEvidenceRoot "candidate\toolkit-source.json"
$candidateToolkitCopyManifest = Join-Path $candidateEvidenceRoot "candidate\toolkit-copy.json"
Write-ToolkitManifest -ToolkitRoot (Join-Path $repoRoot ".agents") -OutputPath $candidateToolkitSourceManifest
Write-ToolkitManifest -ToolkitRoot $candidateToolkit -OutputPath $candidateToolkitCopyManifest
if ((Get-Content -Raw -Encoding UTF8 $candidateToolkitSourceManifest) -cne (Get-Content -Raw -Encoding UTF8 $candidateToolkitCopyManifest)) {
    throw "Toolkit changed while copying into the candidate worktree."
}
Invoke-Gate -Name "candidate-toolkit-ignore-check" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @(
    "git", "check-ignore", "--no-index", "-q", ".agents\VERSION"
)

Invoke-Gate -Name "candidate-git-add" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @("git", "add", "--all")
Invoke-Gate -Name "candidate-status-precommit" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @("git", "status", "--porcelain=v1", "--untracked-files=all")
$candidateStatus = @(git -C $candidateWorktree status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) { throw "Could not inspect candidate status before commit." }
if ($candidateStatus.Count -gt 0) {
    Invoke-Gate -Name "candidate-commit" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @(
        "git", "-c", "user.name=Phase 25 Audit", "-c", "user.email=phase25-audit@localhost",
        "commit", "--no-gpg-sign", "-m", "phase25-audit-candidate"
    )
}
Invoke-Gate -Name "candidate-head" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @("git", "rev-parse", "HEAD")
$candidateCommit = (git -C $candidateWorktree rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Could not resolve candidate commit." }
Invoke-Gate -Name "candidate-status-postcommit" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @("git", "status", "--porcelain=v1", "--untracked-files=all")
$candidateStatus = @(git -C $candidateWorktree status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $candidateStatus.Count -ne 0) {
    throw "Candidate is not clean after applying approved inputs."
}
Add-CandidateManifestRecord -Record ([ordered]@{
    kind = "candidate-worktree"
    path = $candidateWorktree
    base_commit = $baseCommit
    candidate_commit = $candidateCommit
    tracked_patch = $trackedPatch
    approved_untracked = $approvedUntracked
    toolkit_path = $candidateToolkit
    toolkit_source_manifest = $candidateToolkitSourceManifest
    toolkit_copy_manifest = $candidateToolkitCopyManifest
    toolkit_version = (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $candidateToolkit "VERSION")).Trim()
})
[ordered]@{
    candidate_id = $candidateId
    evidence_root = $evidenceRoot
    candidate_manifest = (Join-Path $candidateEvidenceRoot "candidate-manifest.json")
    candidate_worktree = $candidateWorktree
    candidate_commit = $candidateCommit
    created_at_utc = [DateTime]::UtcNow.ToString("o")
} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $candidateEvidenceRoot "candidate-handoff.json")
~~~

只有在上面的 Git worktree 候选已经创建、输入已应用并通过 clean 状态检查后，才执行 Git 范围检查；这些检查也必须通过 wrapper：

~~~powershell
Invoke-Gate -Name "candidate-datagen-status" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "datagen") -Command @("git", "status", "--short", "--untracked-files=all")
Invoke-Gate -Name "candidate-datagen-diff" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "datagen") -Command @("git", "diff", "--stat", "--", "src/generated/resources")
Invoke-Gate -Name "candidate-datagen-diff-check" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "datagen") -Command @("git", "diff", "--check", "--", "src/generated/resources")
Invoke-Gate -Name "candidate-verify-data-clean" -WorkingDirectory $candidateWorktree -LogDirectory (Join-Path $candidateEvidenceRoot "datagen") -TimeoutSeconds 1800 -Command @(
    "python", ".agents/run.py", ".agents/gates/compile_and_repair.py",
    "--with-static", "--with-data", "--with-assets", "--strict-datagen-layout", "--warnings-as-errors", "--verify-data-clean"
)
~~~

- 从明确记录的 `$baseCommit = (git rev-parse HEAD).Trim()` 创建临时 Git worktree；
- 上面的代码以实际 `$baseCommit` 导出 tracked 差异；只有 patch 非空才 apply，不能把字面量 `BASE_COMMIT` 当作 Git revision；
- 按 R9 批准清单从主工作区复制未跟踪文件，并逐个核对源/候选 SHA-256；不能假定 `git diff` 会携带未跟踪内容；
- 在候选中确认 `.agents/VERSION` 仍被 Git 忽略后才执行 `git add --all`，只有存在差异时才用临时本地 identity 创建提交；主工作区不提交、不覆盖、不 reset；若 `.agents` 未被忽略则停止，不能把外部工具包提交进模组候选；
- 将 `.agents/` 工具包复制到候选并记录外部工具包哈希；候选的 Git 清洁状态不等于工具包可复现；
- 候选提交后必须执行 `git -C $candidateWorktree status --porcelain=v1 --untracked-files=all`，输出为空才允许 clean gate；上面的直接状态查询只用于 fail-closed 判定，完整命令仍须有 wrapper 日志；
- 在候选执行 DataGen、`--verify-data-clean`、Major/Release gate；
- 记录临时提交、补丁来源、排除文件、工具包哈希和候选状态；
- 所有候选操作必须放在 `try/finally` 中；证据复制到候选外的 `$evidenceRoot` 后，执行下列清理，并把退出码和注册表检查写入证据：

~~~powershell
if (Test-Path -LiteralPath $candidateWorktree) {
    if (-not (Test-PathInside -Child (Resolve-Path -LiteralPath $candidateWorktree).Path -Parent $evidenceRoot)) {
        throw "Refuse to remove a worktree outside the audit evidence root."
    }
    Invoke-Gate -Name "candidate-worktree-remove" -WorkingDirectory $repoRoot -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @(
        "git", "worktree", "remove", "--force", $candidateWorktree
    )
    if (Test-Path -LiteralPath $candidateWorktree) {
        throw "Candidate worktree path remains after git worktree remove."
    }
}
Invoke-Gate -Name "candidate-worktree-list-after" -WorkingDirectory $repoRoot -LogDirectory (Join-Path $candidateEvidenceRoot "candidate") -Command @("git", "worktree", "list", "--porcelain")
$worktreeList = @(git -C $repoRoot worktree list --porcelain)
if ($LASTEXITCODE -ne 0) { throw "Could not inspect Git worktree registration after cleanup." }
$registeredText = ($worktreeList -join "`n").Replace("\", "/")
$registeredNeedle = $candidateWorktree.Replace("\", "/")
$registeredPattern = '(?im)^worktree\s+' + [regex]::Escape($registeredNeedle) + '\s*$'
if ($registeredText -match $registeredPattern) {
    throw "Candidate worktree remains registered after cleanup."
}
~~~

若候选 gate 中途失败，也必须执行同一 `finally` 清理；清理失败或注册仍存在时，阶段状态保持阻塞，不能继续创建下一候选。

注意：上述清理块属于整个候选生命周期的 `finally`，不能在 9.3 创建并提交候选后立即执行；候选必须保持到阶段 6 的 DataGen、Major/Release、GameTest、质量线和最终 JAR 证据全部复制完成后才允许移除。

DataGen diff 审查、候选清洁状态和生成物哈希已归档后，执行 `Assert-MainWorkspaceStable -Checkpoint "after-datagen"`。该 checkpoint 只检查主工作区，不把候选 worktree 的 `build/` 或 `run/` 输出纳入主工作区基线。

## 10. 阶段 5：批准后的代码变更

只有下列情况才进入运行时代码修改：

- Jade 矩阵实际失败；
- 专服类加载失败，或 C0/C1 客户端实际发现 `OmtTooltips` tooltip 行为失败；
- 维护者选择严格 P0 隔离；此时 `OmtTooltips`、`OmtJadePlugin` 和 `ModNetwork` 的 Common→Client 路径都是待处理项，不能只给其中一个方法加白名单；
- 广播审计确认需要性能修复。

本阶段不做门禁逻辑变更。门禁脚本位于被忽略的 `.agents/` 时，必须额外记录工具包版本/哈希和实际加载路径；否则只能称为本机外部输入，不能称为项目提交内的永久门禁。若运行时发现工具包缺陷，先记录失败/风险证据，再移交外部工具包项目；模组修复仍须单独建立完整证据链。

## 11. 阶段 6：完整验证与报告

### 11.1 跨会话恢复与基础门禁

以下命令在已建立 `candidate_id` 的审计副本/临时候选中执行，并通过阶段 0 定义的 `Invoke-Gate` 保存合并后的 stdout/stderr、原始退出码和命令行。

若阶段被拆到新的 PowerShell 会话，不能重新执行阶段 0 的“新建 candidate”初始化块，否则会生成新 ID 并把证据链断开。必须先从上一会话写出的 `candidate-handoff.json` 恢复变量，再重新定义阶段 0 的函数（不重新创建 evidence root），并验证 manifest、路径边界、candidate worktree 注册和候选提交：

~~~powershell
$handoffPath = $env:OMT_PHASE25_HANDOFF
if ([string]::IsNullOrWhiteSpace($handoffPath) -or -not (Test-Path -LiteralPath $handoffPath -PathType Leaf)) {
    throw "Set OMT_PHASE25_HANDOFF to an existing candidate-handoff.json; refuse to start a new candidate implicitly."
}
$repoRoot = (Resolve-Path -LiteralPath ".").Path
$tempRoot = (Resolve-Path -LiteralPath $env:TEMP).Path
$tmpRoot = $null
if (-not [string]::IsNullOrWhiteSpace($env:TMP) -and (Test-Path -LiteralPath $env:TMP -PathType Container)) {
    $tmpRoot = (Resolve-Path -LiteralPath $env:TMP).Path
}
$handoff = Get-Content -Raw -Encoding UTF8 -LiteralPath $handoffPath | ConvertFrom-Json
$candidateId = [string]$handoff.candidate_id
$candidateEvidenceRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $handoffPath)).Path
$evidenceRoot = $candidateEvidenceRoot
$evidenceBase = (Split-Path -Parent $candidateEvidenceRoot)
$candidateWorktree = (Resolve-Path -LiteralPath ([string]$handoff.candidate_worktree)).Path
$candidateManifestPath = Join-Path $candidateEvidenceRoot "candidate-manifest.json"
if ($candidateId -notmatch '^phase25-[0-9a-f]{32}$' -or
    (Split-Path -Leaf $candidateEvidenceRoot) -cne $candidateId -or
    (Test-PathInside -Child $candidateEvidenceRoot -Parent $repoRoot) -or
    (Test-PathInside -Child $candidateEvidenceRoot -Parent $tempRoot) -or
    ($tmpRoot -and (Test-PathInside -Child $candidateEvidenceRoot -Parent $tmpRoot)) -or
    -not (Test-Path -LiteralPath $candidateManifestPath -PathType Leaf) -or
    -not (Test-PathInside -Child $candidateWorktree -Parent $candidateEvidenceRoot)) {
    throw "Candidate handoff failed ID, manifest, or path-boundary validation."
}
$manifestObject = Get-Content -Raw -Encoding UTF8 -LiteralPath $candidateManifestPath | ConvertFrom-Json
if ($manifestObject.candidate_id -cne $candidateId) {
    throw "Candidate handoff ID does not match candidate-manifest.json."
}
$registeredText = ((git -C $repoRoot worktree list --porcelain) -join "`n").Replace("\", "/")
$registeredPattern = '(?im)^worktree\s+' + [regex]::Escape($candidateWorktree.Replace("\", "/")) + '\s*$'
if ($LASTEXITCODE -ne 0 -or $registeredText -notmatch $registeredPattern) {
    throw "Candidate worktree is not registered at the handoff path."
}
~~~

恢复后重新确认 `$repoRoot`、`$evidenceRoot`、`$candidateId` 和 `$candidateWorktree`，再继续下列门禁；如果候选已被清理，必须停止并创建全新 candidate，不能复活旧路径。

~~~powershell
New-Item -ItemType Directory -Force -Path (Join-Path $candidateEvidenceRoot "major") | Out-Null
$verificationProject = $candidateWorktree
Invoke-Gate -Name "doc-index" -WorkingDirectory $verificationProject -LogDirectory (Join-Path $candidateEvidenceRoot "major") -TimeoutSeconds 120 -Command @(
    "python", ".agents/run.py", ".agents/gates/check_doc_index.py"
)
Invoke-Gate -Name "doc-meta" -WorkingDirectory $verificationProject -LogDirectory (Join-Path $candidateEvidenceRoot "major") -TimeoutSeconds 120 -Command @(
    "python", ".agents/run.py", ".agents/gates/check_doc_meta.py"
)
Invoke-Gate -Name "contract-l0" -WorkingDirectory $verificationProject -LogDirectory (Join-Path $candidateEvidenceRoot "major") -TimeoutSeconds 600 -Command @(
    "python", ".agents/run.py", ".agents/gates/contract_gate.py", "--project-dir", $verificationProject,
    "--require", "--json-report", "$candidateEvidenceRoot\major\contract-l0.json"
)
Invoke-Gate -Name "pipeline-fast" -WorkingDirectory $verificationProject -LogDirectory (Join-Path $candidateEvidenceRoot "major") -TimeoutSeconds 3600 -Command @(
    "python", ".agents/run.py", ".agents/gates/pipeline.py", "--project-dir", $verificationProject,
    "--profile", "fast", "--json-report", "$candidateEvidenceRoot\major\pipeline-fast.json"
)
$fastPayload = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $candidateEvidenceRoot "major\pipeline-fast.json") | ConvertFrom-Json
if ($fastPayload.status -ne "passed" -or $fastPayload.passed -ne $true -or $fastPayload.dry_run -ne $false) {
    throw "Fast JSON report is not a non-dry-run passing result."
}
~~~

### 11.2 Major 行为验证

~~~powershell
New-Item -ItemType Directory -Force -Path (Join-Path $candidateEvidenceRoot "major") | Out-Null
Invoke-Gate -Name "pipeline-major" -WorkingDirectory $verificationProject -LogDirectory (Join-Path $candidateEvidenceRoot "major") -TimeoutSeconds 5400 -Command @(
    "python", ".agents/run.py", ".agents/gates/pipeline.py", "--project-dir", $verificationProject,
    "--profile", "major", "--strict-traceability", "--json-report", "$candidateEvidenceRoot\major\pipeline-major.json"
)
$majorReport = Join-Path $candidateEvidenceRoot "major\pipeline-major.json"
$majorPayload = Get-Content -Raw -Encoding UTF8 -LiteralPath $majorReport | ConvertFrom-Json
if ($majorPayload.status -ne "passed" -or $majorPayload.passed -ne $true -or $majorPayload.dry_run -ne $false -or $majorPayload.strict_traceability -ne $true) {
    throw "Major JSON report is not a non-dry-run strict passing result."
}
~~~

该 profile 覆盖合同、DataGen、Asset、L2、GameTest 和严格追踪。运行后必须人工审查生成资源 diff，不能只看退出码。`--strict-traceability` 是必需参数；不带它时 pipeline 只生成 advisory 追踪结果。

pipeline 内部固定写入 `build/reports/gametest-gate.json` 和 `build/reports/traceability-gate.json`。Major 结束后立即将这两个文件从 `$verificationProject\build\reports\` 复制到 `$candidateEvidenceRoot\major\internal\`，确认源文件存在、复制后 SHA-256 一致并写入 candidate manifest；不能让后续独立运行覆盖后再把它们当作 Major 的报告。

复制必须在同一 PowerShell 会话中立即完成：

~~~powershell
$majorInternalEvidence = Join-Path $candidateEvidenceRoot "major\internal"
New-Item -ItemType Directory -Force -Path $majorInternalEvidence | Out-Null
foreach ($reportName in @("gametest-gate.json", "traceability-gate.json")) {
    $sourceReport = Join-Path $verificationProject "build\reports\$reportName"
    if (-not (Test-Path -LiteralPath $sourceReport -PathType Leaf)) {
        throw "Major internal report is missing: $sourceReport"
    }
    $destinationReport = Join-Path $majorInternalEvidence $reportName
    Copy-Item -LiteralPath $sourceReport -Destination $destinationReport
    $sourceHash = (Get-FileHash -LiteralPath $sourceReport -Algorithm SHA256).Hash.ToLowerInvariant()
    $destinationHash = (Get-FileHash -LiteralPath $destinationReport -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceHash -cne $destinationHash) { throw "Major report hash mismatch: $reportName" }
    Add-CandidateManifestRecord -Record ([ordered]@{
        kind = "major-internal-report"
        path = $destinationReport
        source_path = $sourceReport
        sha256 = $destinationHash
    })
}
~~~

### 11.3 当前 GameTest 与追踪

如果单独运行：

~~~powershell
New-Item -ItemType Directory -Force -Path (Join-Path $candidateEvidenceRoot "l4") | Out-Null
$standaloneGameTestReport = Join-Path $candidateEvidenceRoot "l4\gametest-current.json"
Invoke-Gate -Name "gametest-standalone" -WorkingDirectory $verificationProject -LogDirectory (Join-Path $candidateEvidenceRoot "l4") -TimeoutSeconds 1200 -Command @(
    "python", ".agents/run.py", ".agents/gates/gametest_gate.py", "--project-dir", $verificationProject,
    "--require-tests", "--run", "--timeout", "900", "--json-report", $standaloneGameTestReport
)
Invoke-Gate -Name "traceability-standalone" -WorkingDirectory $verificationProject -LogDirectory (Join-Path $candidateEvidenceRoot "l4") -TimeoutSeconds 300 -Command @(
    "python", ".agents/run.py", ".agents/gates/traceability_gate.py", "--project-dir", $verificationProject,
    "--gametest-report", $standaloneGameTestReport,
    "--json-report", "$candidateEvidenceRoot\l4\traceability-current.json"
)
~~~

只有在没有采用同一候选的 Major/Release 内置 L4 结果时才单独运行这一组命令。若 Major/Release 已经运行过 L4，优先使用同一次运行复制出的内部报告；不得把两次不同运行拼成一条通过证据。

不得引用已有的 gametest-gate.json 或旧 traceability 报告作为当前结果。新报告必须满足：

- generated_at_utc 为本次运行时间；
- 项目根目录匹配当前验证目录；
- GameTest 符号和源文件哈希匹配当前源码；
- Reporter 控制文件和协议验证通过。

由于 pipeline 报告本身不包含完整源码树哈希，必须另存候选 manifest：目标提交/临时提交、tracked diff、批准的未跟踪文件、`.agents/` 工具包哈希、Jade JAR 哈希和生成资源清单哈希。报告只有与该 manifest、同一 `candidate_id` 和同一运行时间链绑定后才算当前证据。

Major/L4 还必须附人工映射表：每个本轮变更或合同验收项对应到至少一个真实的 `GameTestClass#method`；不能因为 L4 全绿就推断测试与本轮变更相关。

### 11.4 Release 候选

只有在干净候选 worktree 中执行：

~~~powershell
$releaseEvidence = Join-Path $candidateEvidenceRoot "release"
New-Item -ItemType Directory -Force -Path $releaseEvidence | Out-Null
$env:TOOLKIT_EVIDENCE_LEDGER = Join-Path $releaseEvidence "evidence-ledger.jsonl"
if (Test-Path -LiteralPath $env:TOOLKIT_EVIDENCE_LEDGER) {
    throw "Release evidence ledger already exists; refuse to append to an old run."
}
$releaseReport = Join-Path $releaseEvidence "pipeline-release.json"
Invoke-Gate -Name "pipeline-release" -WorkingDirectory $verificationProject -LogDirectory $releaseEvidence -TimeoutSeconds 9000 -Command @(
    "python", ".agents/run.py", ".agents/gates/pipeline.py", "--project-dir", $verificationProject,
    "--profile", "release", "--strict-traceability", "--json-report", $releaseReport
)
$releaseInternalEvidence = Join-Path $releaseEvidence "internal"
New-Item -ItemType Directory -Force -Path $releaseInternalEvidence | Out-Null
foreach ($reportName in @("gametest-gate.json", "traceability-gate.json")) {
    $sourceReport = Join-Path $verificationProject "build\reports\$reportName"
    if (-not (Test-Path -LiteralPath $sourceReport -PathType Leaf)) {
        throw "Release internal report is missing: $sourceReport"
    }
    $destinationReport = Join-Path $releaseInternalEvidence $reportName
    Copy-Item -LiteralPath $sourceReport -Destination $destinationReport
    $sourceHash = (Get-FileHash -LiteralPath $sourceReport -Algorithm SHA256).Hash.ToLowerInvariant()
    $destinationHash = (Get-FileHash -LiteralPath $destinationReport -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceHash -cne $destinationHash) { throw "Release report hash mismatch: $reportName" }
    Add-CandidateManifestRecord -Record ([ordered]@{
        kind = "release-internal-report"
        path = $destinationReport
        source_path = $sourceReport
        sha256 = $destinationHash
    })
}
$releaseReportHash = (Get-FileHash -LiteralPath $releaseReport -Algorithm SHA256).Hash.ToLowerInvariant()
$releasePayload = Get-Content -Raw -Encoding UTF8 -LiteralPath $releaseReport | ConvertFrom-Json
if ($releasePayload.status -ne "passed" -or $releasePayload.passed -ne $true -or $releasePayload.dry_run -ne $false -or $releasePayload.strict_traceability -ne $true) {
    throw "Release JSON report is not a non-dry-run strict passing result."
}
$ledgerLines = @(Get-Content -Encoding UTF8 -LiteralPath $env:TOOLKIT_EVIDENCE_LEDGER)
if ($ledgerLines.Count -eq 0) { throw "Release evidence ledger is empty." }
$lastLedger = $ledgerLines[-1] | ConvertFrom-Json
$expectedProjectPath = (Resolve-Path -LiteralPath $verificationProject).Path.TrimEnd("\")
$ledgerProjectPath = ([System.IO.Path]::GetFullPath([string]$lastLedger.project_dir)).TrimEnd("\")
if ($lastLedger.event_type -ne "PIPELINE_RESULT" -or
    $lastLedger.profile -ne "release" -or
    $lastLedger.status -ne "passed" -or
    $lastLedger.passed -ne $true -or
    -not [string]::Equals($ledgerProjectPath, $expectedProjectPath, [System.StringComparison]::OrdinalIgnoreCase) -or
    $lastLedger.report_sha256 -cne $releaseReportHash) {
    throw "Release evidence ledger does not match the current release report."
}
Add-CandidateManifestRecord -Record ([ordered]@{
    kind = "release-pipeline-report"
    path = $releaseReport
    sha256 = $releaseReportHash
    ledger = $env:TOOLKIT_EVIDENCE_LEDGER
})
~~~

Release 报告必须包含 verify-data-clean、L3 专服启动、严格 Major/L4 证据和 flagship suite 完整性。`--strict-traceability` 是必需参数。运行后必须立即复制并哈希 pipeline 内部 GameTest/traceability 报告，读取证据账本最后一条记录并核对其 `report_sha256` 与 Release JSON 报告一致；账本写入失败不能仅凭 pipeline 的 warning 视为成功。

账本核对必须是 fail-closed 的后置检查：读取 `$releaseReport` 的 SHA-256，解析账本最后一行 JSON，确认 `event_type=PIPELINE_RESULT`、`profile=release`、`status=passed`、`passed=true`、`project_dir=$verificationProject` 和 `report_sha256` 全部匹配；任何缺失、旧记录或哈希不匹配都使 Release 失败。

`validate-suite` 只证明旗舰评测协议完整性；若发布声明需要实际评测结果，还必须按 `eval/README.md` 单独执行 `benchmark.py report RESULTS`，并把结果绑定到同一 manifest。

### 11.5 发布质量线和最终产物

Release 候选还必须逐项完成 [quality_bar.md](../../.agents/skills/neoforge/references/quality_bar.md)：

- A1～A5：资源、P0、专服、DataGen 可复现和中文语言资源；
- M1～M8：配置魔数、Mixin、性能反模式、日志、TOML、创造模式页签、标签和 GameTest；每项记录“通过/不通过/不适用 + 一句理由”；
- 通过下列 wrapper 命令构建并检查产物；确认 `build/libs/` 中所有 JAR 恰好一个，记录名称、大小、SHA-256 和当前 candidate：

~~~powershell
$artifactEvidence = Join-Path $candidateEvidenceRoot "artifact"
$jarInspection = Join-Path $artifactEvidence "jar-inspection"
New-Item -ItemType Directory -Force -Path $jarInspection | Out-Null
Invoke-Gate -Name "artifact-build" -WorkingDirectory $verificationProject -LogDirectory $artifactEvidence -TimeoutSeconds 1800 -Command @(
    "gradlew.bat", "jar", "verifyReferenceHostNotPackaged", "--no-daemon", "--console=plain"
)

$jarFiles = @(Get-ChildItem -LiteralPath (Join-Path $verificationProject "build\libs") -File -Filter "*.jar")
if ($jarFiles.Count -ne 1) {
    throw "Expected exactly one production JAR in build/libs, found $($jarFiles.Count)."
}
$finalJar = $jarFiles[0]
Invoke-Gate -Name "artifact-jar-list" -WorkingDirectory $jarInspection -LogDirectory $artifactEvidence -TimeoutSeconds 120 -Command @(
    "jar", "tf", $finalJar.FullName
)
Invoke-Gate -Name "artifact-metadata-extract" -WorkingDirectory $jarInspection -LogDirectory $artifactEvidence -TimeoutSeconds 120 -Command @(
    "jar", "xf", $finalJar.FullName, "META-INF/neoforge.mods.toml"
)
$metadataPath = Join-Path $jarInspection "META-INF\neoforge.mods.toml"
if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
    throw "Production JAR does not contain META-INF/neoforge.mods.toml."
}
$metadataText = Get-Content -Raw -Encoding UTF8 -LiteralPath $metadataPath
$modIdLine = @(Get-Content -Encoding UTF8 (Join-Path $verificationProject "gradle.properties") |
    Where-Object { $_ -match '^\s*mod_id\s*=' }) | Select-Object -First 1
$modVersionLine = @(Get-Content -Encoding UTF8 (Join-Path $verificationProject "gradle.properties") |
    Where-Object { $_ -match '^\s*mod_version\s*=' }) | Select-Object -First 1
if ($null -eq $modIdLine -or $null -eq $modVersionLine) { throw "Could not read mod_id/mod_version from gradle.properties." }
$expectedModId = ($modIdLine -split "=", 2)[1].Trim()
$expectedModVersion = ($modVersionLine -split "=", 2)[1].Trim()
if ($metadataText -notmatch ('(?m)^\s*modId\s*=\s*"' + [regex]::Escape($expectedModId) + '"')) {
    throw "JAR metadata modId does not match gradle.properties."
}
if ($metadataText -notmatch ('(?m)^\s*version\s*=\s*"' + [regex]::Escape($expectedModVersion) + '"')) {
    throw "JAR metadata version does not match gradle.properties."
}
$jarListLog = Join-Path $artifactEvidence "artifact-jar-list.log"
$jarListText = Get-Content -Raw -Encoding UTF8 -LiteralPath $jarListLog
$leakedReferenceHost = @($jarListText -split "`r?`n" |
    Where-Object { $_ -match '^(dev/modstudio/referencehost/|data/.*/structure/referencehostgametests\.smoke\.nbt$)' })
if ($leakedReferenceHost.Count -ne 0) {
    throw "Development-only referencehost entries are present in the production JAR."
}
$artifactRecord = [ordered]@{
    candidate_id = $candidateId
    candidate_manifest = (Join-Path $candidateEvidenceRoot "candidate-manifest.json")
    path = $finalJar.FullName
    file_name = $finalJar.Name
    size = $finalJar.Length
    sha256 = (Get-FileHash -LiteralPath $finalJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    metadata_sha256 = (Get-FileHash -LiteralPath $metadataPath -Algorithm SHA256).Hash.ToLowerInvariant()
    jar_listing_log = $jarListLog
    referencehost_entries = @()
}
$artifactRecord | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $artifactEvidence "artifact-manifest.json")
Add-CandidateManifestRecord -Record ([ordered]@{
    kind = "production-artifact"
    path = (Join-Path $artifactEvidence "artifact-manifest.json")
    jar_path = $finalJar.FullName
    jar_sha256 = $artifactRecord.sha256
    metadata_sha256 = $artifactRecord.metadata_sha256
})
$candidateManifestPath = Join-Path $candidateEvidenceRoot "candidate-manifest.json"
[ordered]@{
    candidate_id = $candidateId
    candidate_manifest = $candidateManifestPath
    candidate_manifest_sha256 = (Get-FileHash -LiteralPath $candidateManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    finalized_at_utc = [DateTime]::UtcNow.ToString("o")
} | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $candidateEvidenceRoot "candidate-manifest-final.json")
~~~

保存最终 JAR 清单、`jar tf` 输出、元数据副本和产物哈希，并把 `artifact-manifest.json` 追加到同一 candidate manifest；Release pipeline 的通过不能替代产物构建或人工质量线。生产 JAR 中应记录 `referencehost` 条目为“确认不存在”，不能把开发专用条目当作应存在的发布内容。

阶段 6 的所有报告、人工映射、质量线表和最终产物证据已复制到外置目录后，才执行 `Assert-MainWorkspaceStable -Checkpoint "after-verification"`，随后才进入工程一致性整理或候选 worktree 清理。

## 12. 阶段 7：模组工程一致性整理

运行证据稳定后单独处理模组项目自身：

- 核验 `LICENSE`、`gradle.properties`、`src/main/templates/META-INF/neoforge.mods.toml` 和 README 的 GPL-3.0-only 一致性；G5 不再等待许可证选择；
- 单独记录第三方代码、材质、音频和其他资产的来源与许可证，不把这项 provenance 审查并入 G5 的决定状态；
- 修正 README 的测试完成度和发布表述；
- 更新移植交接文档中的测试数量和当前状态；
- `.github/workflows/` 不纳入 Git；历史提交 `d085ca6` 已明确将其移除，当前残留已排除；
- `图片/` 仅为本地截图证据，不纳入 Git，已由根目录 `.gitignore` 忽略；
- 对未跟踪的方案文档和其他项目文件逐项记录纳入候选或排除原因。

`.agents` 工具包的版本、来源、哈希、实现和 AGENTS 真源同步不在本阶段处理；只按[外部工具包边界说明](phase-25-external-agents-toolkit-boundary.md)记录其作为外部输入的身份。

## 13. 决策门

| 决策 | 默认状态 | 需要维护者确认的内容 |
| --- | --- | --- |
| G1 | 待确认 | Common→Client 发现后是否按严格 P0 物理拆分模组代码；本项目不通过修改 `.agents` 添加白名单规避 |
| G2 | 待验证 | Jade 是否属于正式兼容范围 |
| G3 | 仅审计 | broadcastAll 是否纳入本轮运行时修复 |
| G4 | 待确认 | 是否允许创建干净临时 worktree 作为 release 候选 |
| G5 | 已关闭（许可证选择） | 核验 LICENSE、`gradle.properties`、TOML 模板和 README 的 GPL-3.0-only 一致性；第三方 provenance 另行验收 |
| G7 | 待关闭 | 5 个动态注册调用是否已完成逐项资源映射 |
| G8 | 待关闭 | Quality Bar A1～A5、M1～M8 和最终 JAR 是否已逐项验收 |

在 G1、G2、G3、G4、G7、G8 中任何一个与本次结论相关的决策未确认或关闭前，不把对应风险标记为已解决；G4 会阻塞候选/Release。G5 的选择已关闭，但一致性核验和第三方 provenance 未完成前不能作发布合规声明。

状态语义固定为：

- `audit_partial`：已收集部分证据，但存在未关闭风险，只能报告“仍待验证”；
- `audit_closed`：R1～R6 的审计项、G1/G3 决策和证据链收口，但不代表允许发布；
- `release_ready`：除 `audit_closed` 外，G2 兼容范围决定、G4 候选 worktree/清洁生命周期、G5 一致性核验、G7 动态资源映射、G8 Quality Bar/最终 JAR、第三方 provenance、非 dry-run Release ledger 和所有发布证据均通过。G1/G2/G3/G4/G7/G8 中任何未决项都不能使用“可发布”表述。

G2 若选择“Jade 不属于正式兼容范围”，仍必须完成 S0/C0，并对现有 Jade 入口至少完成 S1/C1 的风险记录；发布元数据/README 必须明确“不保证 Jade 集成”。不能用“未测试”掩盖现有 Jade 入口，也不能把带有已编译兼容代码的发布物描述为“已验证 Jade 兼容”。

## 14. 最终验收条件

本阶段只能在以下条件全部满足后结束：

1. R1/R2 的运行时结论有无 Jade、有 Jade 服务端和 C0/C1 客户端 2×2 证据，并包含 OmtTooltips tooltip 验收；
2. 当前外部门禁版本/哈希已记录，既有 L2 输出已保存，并对 `ModNetwork`、`OmtTooltips`、`OmtJadePlugin` 完成独立 P0 复查；
3. R3 的门禁输出与人工复查边界已明确；任何 Common→Client 漏报/误报都没有通过修改本项目外部工具包来静默消除；
4. P0 负向复查逐项完成，包含 Codec、Payload、静态 `.get()`、NBT、EventBus、StreamCodec 容量/类型，没有未解释项；
5. R4 有预先登记阈值的广播范围、频率和字节量审计；若改动，已有新的网络行为证据；
6. DataGen 使用了实际内容备份，diff 已逐项审查；干净候选 worktree 的 verify-data-clean 通过；
7. 当前 GameTest、traceability、pipeline 内部报告和外部证据 manifest 均为同一 candidate_id 的新生成结果；
8. L1、L2、L2.5、L3、L4 和必要的 Major/Release 门禁输出齐全，Major/Release 使用 `--strict-traceability`；
9. 5 个动态注册调用已经逐项映射；若仍未完成，阶段状态必须保持“审计未收口”，L2.5 只能报告为部分覆盖，禁止进入 Release 声明；
10. Quality Bar A1～A5、M1～M8 已逐项给出结论；不适用项有理由；
11. 最终 Mod JAR 已构建、检查、哈希并绑定到 candidate manifest，且 candidate manifest final SHA-256 已留档；
12. 外部门禁输入的版本/哈希、许可证一致性、第三方 provenance、README、CI 和未跟踪项目文件的处理决定已记录；
13. 证据保存在持久化外置目录，所有命令日志包含 stderr、退出码和实际命令；
14. 报告明确列出仍未验证的客户端视觉、多人矩阵或可选兼容范围；
15. 若要标记 `release_ready`，G1/G2/G3/G4/G5/G7/G8 的决策/核验状态、候选 worktree 清理注册、许可证一致性、第三方 provenance 和外部门禁输入哈希均有独立证据；任一项未决时最多标记 `audit_closed` 或 `audit_partial`。

在这些条件完成前，只能使用“已验证某项”“风险已降级”或“仍待验证”等表述，不能宣称项目整体完成或可发布。
