[CmdletBinding()]
param(
    [string]$EvidenceRoot = '..\..\phase25-evidence\architecture-maturity-20260807',
    [string]$OptimizationEvidenceRoot = '..\..\phase25-evidence\architecture-optimization-20260807',
    [switch]$RequireRetainedEvidence
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-AnyPath {
    param([string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) {
        return [IO.Path]::GetFullPath($Path)
    }
    return [IO.Path]::GetFullPath((Join-Path $ProjectRoot $Path))
}

$EvidenceRoot = Resolve-AnyPath $EvidenceRoot
$OptimizationEvidenceRoot = Resolve-AnyPath $OptimizationEvidenceRoot
New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null

function Resolve-ProjectFile {
    param([string]$RelativePath)
    return Resolve-AnyPath $RelativePath
}

function Read-ProjectText {
    param([string]$RelativePath)
    $path = Resolve-ProjectFile $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Expected project file is missing: $RelativePath"
    }
    return Get-Content -LiteralPath $path -Raw -Encoding UTF8
}

function Get-ProjectHash {
    param([string]$RelativePath)
    return (Get-FileHash -LiteralPath (Resolve-ProjectFile $RelativePath) -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Add-Check {
    param(
        [string]$Id,
        [bool]$Pass,
        [string]$Purpose,
        [object]$Details
    )
    $checks[$Id] = [ordered]@{
        pass = $Pass
        purpose = $Purpose
        details = $Details
    }
}

function Test-TextPattern {
    param([string]$RelativePath, [string]$Pattern)
    return [regex]::IsMatch((Read-ProjectText $RelativePath), $Pattern,
        [Text.RegularExpressions.RegexOptions]::Multiline)
}

$checks = [ordered]@{}
$serviceRoot = Resolve-ProjectFile 'src/main/java/omtteam/openmodularturrets/service'
$serviceFiles = @()
if (Test-Path -LiteralPath $serviceRoot -PathType Container) {
    $serviceFiles = @(Get-ChildItem -LiteralPath $serviceRoot -Filter '*.java' -File)
}
$serviceConcreteImports = @()
foreach ($file in $serviceFiles) {
    $serviceConcreteImports += @(Select-String -LiteralPath $file.FullName -Pattern 'import\s+omtteam\.openmodularturrets\.blockentity\.(TurretBaseBlockEntity|TurretHeadBlockEntity)' -AllMatches)
}
Add-Check 'service_direction' ($serviceFiles.Count -ge 2 -and $serviceConcreteImports.Count -eq 0) `
    'Service files exist and do not import concrete Base/Head block entities.' `
    ([ordered]@{
        service_files = @($serviceFiles | ForEach-Object { $_.Name })
        concrete_import_hits = @($serviceConcreteImports | ForEach-Object { $_.Line.Trim() })
    })

$javaRoot = Resolve-ProjectFile 'src/main/java'
$commonFiles = @(Get-ChildItem -LiteralPath $javaRoot -Recurse -Filter '*.java' -File |
    Where-Object { $_.FullName -notmatch '\\client(\\|$)' })
$commonClientHits = @()
foreach ($file in $commonFiles) {
    $commonClientHits += @(Select-String -LiteralPath $file.FullName -Pattern 'net\.minecraft\.client' -AllMatches)
}
Add-Check 'common_client_isolation' ($commonClientHits.Count -eq 0) `
    'Common/server Java files contain no physical net.minecraft.client import.' `
    ([ordered]@{
        scanned_files = $commonFiles.Count
        hits = @($commonClientHits | ForEach-Object { "$(Resolve-Path $_.Path -Relative):$($_.LineNumber):$($_.Line.Trim())" })
    })

$contextFiles = @(
    'src/main/java/omtteam/openmodularturrets/data/TurretCombatContext.java',
    'src/main/java/omtteam/openmodularturrets/data/TurretTargetingWorldQueries.java',
    'src/main/java/omtteam/openmodularturrets/data/TurretVolleyResourcesView.java'
)
$contextMissing = @($contextFiles | Where-Object { -not (Test-Path -LiteralPath (Resolve-ProjectFile $_) -PathType Leaf) })
$combatText = Read-ProjectText 'src/main/java/omtteam/openmodularturrets/service/TurretCombatService.java'
$targetingText = Read-ProjectText 'src/main/java/omtteam/openmodularturrets/service/TurretTargetingService.java'
$contextUsePass = $combatText.Contains('TurretCombatContext') -and
    $combatText.Contains('TurretVolleyResourcesView') -and
    $targetingText.Contains('TurretTargetingWorldQueries') -and
    $targetingText.Contains('int range')
Add-Check 'narrow_contexts' ($contextMissing.Count -eq 0 -and $contextUsePass) `
    'Narrow service context interfaces exist and are used instead of concrete aggregate inputs.' `
    ([ordered]@{ missing = @($contextMissing); combat_context_used = $combatText.Contains('TurretCombatContext'); resource_view_used = $combatText.Contains('TurretVolleyResourcesView'); targeting_queries_used = $targetingText.Contains('TurretTargetingWorldQueries'); scalar_range_used = $targetingText.Contains('int range') })

$basePath = 'src/main/java/omtteam/openmodularturrets/blockentity/TurretBaseBlockEntity.java'
$headPath = 'src/main/java/omtteam/openmodularturrets/blockentity/TurretHeadBlockEntity.java'
$baseText = Read-ProjectText $basePath
$headText = Read-ProjectText $headPath
$baseOwnerPass = $baseText.Contains('saveAdditional') -and $baseText.Contains('loadAdditional') -and
    $baseText.Contains('cachedAmmoExpanderPositions') -and $baseText.Contains('invalidateNeighborCaches')
$headOwnerPass = $headText.Contains('saveAdditional') -and $headText.Contains('loadAdditional') -and
    $headText.Contains('serverTick') -and $headText.Contains('TurretTargetingService')
Add-Check 'state_ownership' ($baseOwnerPass -and $headOwnerPass) `
    'Base and Head retain their documented state/lifecycle owners and derived-cache hooks.' `
    ([ordered]@{ base_owner = $baseOwnerPass; head_owner = $headOwnerPass })

$clientEventsPath = 'src/main/java/omtteam/openmodularturrets/client/ClientGameEvents.java'
$clientEventsText = Read-ProjectText $clientEventsPath
$clientLifecyclePass = $clientEventsText.Contains('RegisterClientReloadListenersEvent') -and
    $clientEventsText.Contains('ClientProjectileEffects.clear()') -and
    $clientEventsText.Contains('BeamRenderCache.clear()') -and
    $clientEventsText.Contains('LoggingOut')
Add-Check 'client_lifecycle_cleanup' $clientLifecyclePass `
    'Client projectile/Beam caches have reload and connection lifecycle cleanup entries.' `
    ([ordered]@{ reload_listener = $clientEventsText.Contains('RegisterClientReloadListenersEvent'); projectile_clear = $clientEventsText.Contains('ClientProjectileEffects.clear()'); beam_clear = $clientEventsText.Contains('BeamRenderCache.clear()'); logging_out = $clientEventsText.Contains('LoggingOut') })

$planPath = 'docs/porting/architecture-maturity-plan.md'
$contractPath = 'docs/features/architecture_maturity.contract.json'
$planHash = Get-ProjectHash $planPath
$contract = Get-Content -LiteralPath (Resolve-ProjectFile $contractPath) -Raw -Encoding UTF8 | ConvertFrom-Json
$hashPass = $contract.design_source.sha256 -eq $planHash
Add-Check 'design_contract_hash' $hashPass `
    'The maturity contract is bound to the exact plan hash.' `
    ([ordered]@{ plan_sha256 = $planHash; contract_sha256 = $contract.design_source.sha256; match = $hashPass })

$workflowPath = Resolve-ProjectFile '.github/workflows/quality.yml'
$workflowText = if (Test-Path -LiteralPath $workflowPath -PathType Leaf) { Get-Content -LiteralPath $workflowPath -Raw -Encoding UTF8 } else { '' }
$workflowPass = $workflowText.Contains('pipeline.py') -and
    $workflowText.Contains('--profile major') -and
    $workflowText.Contains('--with-server') -and
    $workflowText.Contains('architecture_maturity_audit.ps1') -and
    $workflowText.Contains('upload-artifact')
Add-Check 'ci_quality_line' $workflowPass `
    'Project CI calls the documented Major/server/audit quality line and retains reports.' `
    ([ordered]@{ exists = (Test-Path -LiteralPath $workflowPath -PathType Leaf); required_tokens = @('pipeline.py', '--profile major', '--with-server', 'architecture_maturity_audit.ps1', 'upload-artifact') })

$optimizationComparisonPath = Join-Path $OptimizationEvidenceRoot 'pressure-comparison.json'
$optimizationOldSavePath = Join-Path $OptimizationEvidenceRoot 'old-save-input.json'
$optimizationGameTestPath = Resolve-ProjectFile 'build/reports/gametest-gate.json'
$pressureObserved = Test-Path -LiteralPath $optimizationComparisonPath -PathType Leaf
$oldSaveObserved = Test-Path -LiteralPath $optimizationOldSavePath -PathType Leaf
$pressurePass = $false
$pressureDetails = [ordered]@{ path = $optimizationComparisonPath; observed = $false }
if ($pressureObserved) {
    $comparison = Get-Content -LiteralPath $optimizationComparisonPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $pressurePass = [bool]$comparison.pass
    $pressureDetails = [ordered]@{ path = $optimizationComparisonPath; observed = $true; pass = [bool]$comparison.pass; baseline_median_p95_ms = $comparison.baseline_median_p95_ms; candidate_median_p95_ms = $comparison.candidate_median_p95_ms; absolute_delta_ms = $comparison.absolute_delta_ms; allowed_delta_ms = $comparison.allowed_delta_ms }
}
$oldSavePass = $false
$oldSaveDetails = [ordered]@{ path = $optimizationOldSavePath; observed = $false }
if ($oldSaveObserved) {
    $oldSave = Get-Content -LiteralPath $optimizationOldSavePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $oldSavePass = $oldSave.status -eq 'pass' -and [bool]$oldSave.save_observed -and [bool]$oldSave.reload_observed
    $oldSaveDetails = [ordered]@{ path = $optimizationOldSavePath; observed = $true; status = $oldSave.status; save_observed = [bool]$oldSave.save_observed; reload_observed = [bool]$oldSave.reload_observed }
}
$gameTestPass = $false
$gameTestDetails = [ordered]@{ path = $optimizationGameTestPath; observed = $false }
if (Test-Path -LiteralPath $optimizationGameTestPath -PathType Leaf) {
    $gameTest = Get-Content -LiteralPath $optimizationGameTestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $gameTestPass = [bool]$gameTest.result.passed -and [int]$gameTest.discovery.count -gt 0
    $gameTestDetails = [ordered]@{ path = $optimizationGameTestPath; observed = $true; status = $gameTest.result.status; discovered = [int]$gameTest.discovery.count; total_tests = [int]$gameTest.execution.total_tests; required_passed_tests = [int]$gameTest.execution.required_passed_tests; required_failures = [int]$gameTest.execution.required_failures }
}
$externalEvidenceDeferred = -not $RequireRetainedEvidence -and (-not $pressureObserved -or -not $oldSaveObserved)
$retainedEvidencePass = if ($externalEvidenceDeferred) { $gameTestPass } else { $pressurePass -and $oldSavePass -and $gameTestPass }
$retainedEvidenceStatus = if ($externalEvidenceDeferred) { 'not_evaluated_external_evidence_not_present' } elseif ($retainedEvidencePass) { 'pass' } else { 'fail' }
Add-Check 'retained_runtime_evidence' $retainedEvidencePass `
    'Retained pressure, old-save and GameTest evidence are present and passing; the maturity batch does not fabricate replacement evidence.' `
    ([ordered]@{ status = $retainedEvidenceStatus; require_retained_evidence = [bool]$RequireRetainedEvidence; pressure = $pressureDetails; old_save = $oldSaveDetails; gametest = $gameTestDetails })

$extensionGuide = Test-Path -LiteralPath (Resolve-ProjectFile 'docs/porting/architecture-extension-guide.md') -PathType Leaf
$phase5Evidence = Test-Path -LiteralPath (Join-Path $EvidenceRoot 'phase5-audit.json') -PathType Leaf
Add-Check 'extension_handoff' ($extensionGuide -and $phase5Evidence) `
    'The extension guide and executable Potato path audit are both present.' `
    ([ordered]@{ guide = $extensionGuide; phase5_audit = $phase5Evidence })

$agentsStatus = @(& git -C $ProjectRoot status --short -- .agents 2>$null)
$agentsPass = [string]::IsNullOrWhiteSpace(($agentsStatus -join "`n"))
Add-Check 'agents_untouched' $agentsPass `
    'The external .agents toolkit has no worktree changes.' `
    ([ordered]@{ status = @($agentsStatus) })

$failedChecks = @($checks.GetEnumerator() | Where-Object { -not [bool]$_.Value.pass } | ForEach-Object { $_.Key })
$result = [ordered]@{
    schema = 'omt.architecture-maturity.audit.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    batch = 'architecture-maturity-20260807'
    project_root = $ProjectRoot
    evidence_root = $EvidenceRoot
    optimization_evidence_root = $OptimizationEvidenceRoot
    plan = [ordered]@{ path = $planPath; sha256 = $planHash }
    checks = $checks
    failed_checks = @($failedChecks)
    result = if ($failedChecks.Count -eq 0) { 'pass' } else { 'fail' }
    interpretation = 'This is a project-side file/class/evidence audit. It is not an AST proof and does not replace compiler, GameTest, dedicated-server, pressure or manual client evidence.'
}
$output = Join-Path $EvidenceRoot 'maturity-audit.json'
$result | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $output -Encoding UTF8
Write-Output "Architecture maturity audit: $output"
Write-Output "Result: $($result.result); failed_checks=$($failedChecks.Count)"
if ($failedChecks.Count -ne 0) {
    Write-Output ("Failed: " + ($failedChecks -join ', '))
    exit 1
}
