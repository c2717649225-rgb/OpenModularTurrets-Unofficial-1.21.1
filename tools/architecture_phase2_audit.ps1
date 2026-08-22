[CmdletBinding()]
param(
    [string]$EvidenceRoot = '..\..\phase25-evidence\architecture-optimization-20260807'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot = [IO.Path]::GetFullPath($EvidenceRoot)
} else {
    $EvidenceRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $EvidenceRoot))
}
New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null

function Resolve-ProjectPath {
    param([string]$RelativePath)
    return [IO.Path]::GetFullPath((Join-Path $ProjectRoot $RelativePath))
}

function Read-Source {
    param([string]$RelativePath)
    $path = Resolve-ProjectPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Expected source file is missing: $RelativePath"
    }
    return Get-Content -LiteralPath $path -Raw -Encoding UTF8
}

function Has-Pattern {
    param(
        [string]$RelativePath,
        [string]$Pattern
    )
    return [regex]::IsMatch((Read-Source $RelativePath), $Pattern,
        [Text.RegularExpressions.RegexOptions]::Multiline)
}

function New-Check {
    param(
        [string]$Id,
        [string]$Phase,
        [string]$Decision,
        [bool]$Pass,
        [string]$Rationale,
        [string[]]$Files,
        [string[]]$Patterns
    )
    return [ordered]@{
        id = $Id
        phase = $Phase
        decision = $Decision
        pass = $Pass
        rationale = $Rationale
        files = @($Files)
        patterns = @($Patterns)
    }
}

$checks = @()

$ownershipFiles = @(
    'src/main/java/omtteam/openmodularturrets/data/OwnershipRules.java',
    'src/main/java/omtteam/openmodularturrets/data/TargetingRules.java',
    'src/main/java/omtteam/openmodularturrets/data/TurretTargetingWorldQueries.java',
    'src/main/java/omtteam/openmodularturrets/service/TurretTargetingService.java',
    'src/main/java/omtteam/openmodularturrets/blockentity/TurretBaseBlockEntity.java'
)
$ownershipPatterns = @(
    (Has-Pattern $ownershipFiles[0] 'static boolean matches'),
    (Has-Pattern $ownershipFiles[1] 'static boolean ownershipAllowsTarget'),
    (Has-Pattern $ownershipFiles[2] 'boolean isTargetClaimedBySibling'),
    (Has-Pattern $ownershipFiles[2] 'boolean mayDamage'),
    (Has-Pattern $ownershipFiles[3] 'TurretTargetingWorldQueries worldQueries'),
    ((Read-Source 'src/main/java/omtteam/openmodularturrets/service/TurretTargetingService.java') -notmatch 'TurretBaseBlockEntity'),
    (Has-Pattern $ownershipFiles[4] 'private UUID owner'),
    (Has-Pattern $ownershipFiles[4] 'Map<UUID, LocalTrustEntry> localTrust')
)
$checks += New-Check 'phase2a-ownership-trust' '2A' 'no_additional_extraction' `
    (($ownershipPatterns | Where-Object { -not $_ }).Count -eq 0) `
    'Pure identity rules already live in data classes; live player/team/security queries remain explicit Base-owned server adapters. The targeting service has no concrete BlockEntity dependency.' `
    $ownershipFiles @('OwnershipRules', 'TargetingRules', 'TurretTargetingWorldQueries', 'TurretTargetingService', 'owner', 'localTrust')

$cacheFiles = @(
    'src/main/java/omtteam/openmodularturrets/block/TurretBaseBlock.java',
    'src/main/java/omtteam/openmodularturrets/blockentity/TurretBaseBlockEntity.java'
)
$cachePatterns = @(
    (Has-Pattern $cacheFiles[1] 'cachedAmmoExpanderPositions'),
    (Has-Pattern $cacheFiles[1] 'cachedRangeGameTime'),
    (Has-Pattern $cacheFiles[1] 'cachedCapacityGameTime'),
    (Has-Pattern $cacheFiles[0] 'invalidateNeighborCaches'),
    (Has-Pattern $cacheFiles[1] 'onContentsChanged'),
    (Has-Pattern $cacheFiles[1] 'public void setRemoved\(\)'),
    (Has-Pattern $cacheFiles[1] 'invalidateRangeCache')
)
$checks += New-Check 'phase2b-cache-lifecycle' '2B' 'reviewed_existing_cache_system' `
    (($cachePatterns | Where-Object { -not $_ }).Count -eq 0) `
    'Attachment topology, range, and energy-capacity caches already have explicit keys and invalidation hooks. No resolver or duplicate cache layer is introduced; setRemoved clears derived attachment views.' `
    $cacheFiles @('cachedAmmoExpanderPositions', 'cachedRangeGameTime', 'cachedCapacityGameTime', 'neighborChanged', 'onContentsChanged', 'setRemoved')

$resourceFiles = @(
    'src/main/java/omtteam/openmodularturrets/blockentity/TurretBaseBlockEntity.java',
    'src/main/java/omtteam/openmodularturrets/data/TurretUpgradeRules.java',
    'src/main/java/omtteam/openmodularturrets/blockentity/TurretHeadBlockEntity.java',
    'src/main/java/omtteam/openmodularturrets/network/ModNetwork.java'
)
$resourcePatterns = @(
    (Has-Pattern $resourceFiles[0] 'consumeResourcesForVolley'),
    (Has-Pattern $resourceFiles[1] 'static int energyCost'),
    (Has-Pattern $resourceFiles[1] 'static int projectileCount'),
    (Has-Pattern $resourceFiles[0] 'private void markForSave\('),
    (Has-Pattern $resourceFiles[0] 'private void markForSaveAndSync\('),
    (Has-Pattern $resourceFiles[3] 'sendBlockEntityUpdateToTracking'),
    (Has-Pattern $resourceFiles[2] 'base\.consumeResourcesForVolley\(definition\)')
)
$checks += New-Check 'phase2c-resource-transaction' '2C' 'no_additional_extraction' `
    (($resourcePatterns | Where-Object { -not $_ }).Count -eq 0) `
    'Pure energy/projectile-count formulas stay in TurretUpgradeRules; the Base remains the sole resource transaction owner. The existing three sync meanings are inherited rather than repackaged.' `
    $resourceFiles @('consumeResourcesForVolley', 'energyCost', 'projectileCount', 'markForSave', 'markForSaveAndSync', 'sendBlockEntityUpdateToTracking')

$result = [ordered]@{
    schema = 'omt.architecture-optimization.phase2-audit.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    batch = 'architecture-optimization-20260807'
    result = if (@($checks | Where-Object { -not $_.pass }).Count -eq 0) { 'pass' } else { 'fail' }
    decisions = @($checks)
    invariants = @(
        'No registry ID, save/load key, Payload ID/order/encoding, config default, or gameplay rule changes are authorized by this audit.',
        'No new service may own Base persistence or mutable world state.',
        'A zero-code phase is a valid completion when the existing boundary is proven and a new wrapper would add no independent owner.'
    )
}
$output = Join-Path $EvidenceRoot 'phase2-audit.json'
$result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $output -Encoding UTF8
Write-Output "Phase 2 audit written: $output"
Write-Output "Result: $($result.result)"
foreach ($check in $checks) {
    Write-Output "$($check.id): pass=$($check.pass); decision=$($check.decision)"
}
if ($result.result -ne 'pass') {
    exit 1
}
