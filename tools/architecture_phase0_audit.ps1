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

function Get-RelativeProjectPath {
    param([string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    if ($full.StartsWith($ProjectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($ProjectRoot.Length).TrimStart('\', '/').Replace('\', '/')
    }
    return $full
}

function Write-JsonFile {
    param([string]$Name, [object]$Value)
    $path = Join-Path $EvidenceRoot $Name
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

function Get-SourceLines {
    param([string]$RelativePath)
    $path = Resolve-ProjectPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Expected source file is missing: $RelativePath"
    }
    return @(Get-Content -LiteralPath $path -Encoding UTF8)
}

function Get-SourceHash {
    param([string]$RelativePath)
    $path = Resolve-ProjectPath $RelativePath
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Find-SourcePattern {
    param(
        [string]$RelativePath,
        [string]$Pattern
    )
    $path = Resolve-ProjectPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return @()
    }
    return @(
        Select-String -LiteralPath $path -Pattern $Pattern -AllMatches |
            ForEach-Object {
                [ordered]@{
                    file = $RelativePath
                    line = [int]$_.LineNumber
                    text = $_.Line.Trim()
                }
            }
    )
}

function Get-Layer {
    param([string]$RelativePath)
    $trimmed = $RelativePath -replace '^src/main/java/omtteam/openmodularturrets/', ''
    $first = ($trimmed -split '/')[0]
    if ([string]::IsNullOrWhiteSpace($first) -or $first -eq $trimmed) {
        return 'root'
    }
    return $first
}

$basePath = 'src/main/java/omtteam/openmodularturrets/blockentity/TurretBaseBlockEntity.java'
$baseBlockPath = 'src/main/java/omtteam/openmodularturrets/block/TurretBaseBlock.java'
$headPath = 'src/main/java/omtteam/openmodularturrets/blockentity/TurretHeadBlockEntity.java'
$projectilePath = 'src/main/java/omtteam/openmodularturrets/entity/TurretProjectileEntity.java'
$networkPath = 'src/main/java/omtteam/openmodularturrets/network/ModNetwork.java'

$cacheDefinitions = @(
    [ordered]@{
        name = 'ammo_topology'
        fields = @('cachedAmmoExpanderPositions', 'ammoTopologyCached', 'cachedAmmoInventories', 'cachedAmmoLevel')
        compute_methods = @('ammoInventories', 'extractAmmo')
        invalidate_methods = @('invalidateNeighborCaches')
    },
    [ordered]@{
        name = 'range'
        fields = @('cachedRangeLevel', 'cachedRangeGameTime', 'cachedRangeUpgradeLevel', 'cachedMaximumRange')
        compute_methods = @('getEffectiveRange')
        invalidate_methods = @('invalidateRangeCache', 'invalidateNeighborCaches')
    },
    [ordered]@{
        name = 'energy_capacity'
        fields = @('cachedCapacityLevel', 'cachedCapacityGameTime', 'cachedMaxEnergyCapacity')
        compute_methods = @('getMaxEnergyStored', 'getMaxEnergyCapacity')
        invalidate_methods = @('invalidateNeighborCaches')
    }
)

$cacheEntries = @()
foreach ($definition in $cacheDefinitions) {
    $fieldObservations = @()
    foreach ($field in $definition.fields) {
        $matches = Find-SourcePattern $basePath ([regex]::Escape($field))
        $fieldObservations += [ordered]@{
            field = $field
            observed = $matches.Count -gt 0
            references = @($matches)
        }
    }
    $computeObservations = @()
    foreach ($method in $definition.compute_methods) {
        $computeObservations += [ordered]@{
            method = $method
            references = @(Find-SourcePattern $basePath ("\b" + [regex]::Escape($method) + "\s*\("))
        }
    }
    $invalidateObservations = @()
    foreach ($method in $definition.invalidate_methods) {
        $invalidateObservations += [ordered]@{
            method = $method
            references = @(Find-SourcePattern $basePath ("\b" + [regex]::Escape($method) + "\s*\("))
        }
    }
    $cacheEntries += [ordered]@{
        name = $definition.name
        owner = 'TurretBaseBlockEntity'
        fields = @($fieldObservations)
        compute_methods = @($computeObservations)
        invalidate_methods = @($invalidateObservations)
    }
}

$lifecycleChecks = @(
    [ordered]@{ event = 'TurretBaseBlock.neighborChanged'; files = @($baseBlockPath); pattern = 'invalidateNeighborCaches\('; purpose = 'neighbor attachment topology and derived cache invalidation'; required = $true; absence_rationale = '' },
    [ordered]@{ event = 'upgrade inventory onContentsChanged'; files = @($basePath); pattern = 'onContentsChanged\('; purpose = 'range cache invalidation on upgrade changes'; required = $true; absence_rationale = '' },
    [ordered]@{ event = 'BlockEntity load'; files = @($basePath, $headPath, $projectilePath); pattern = 'loadAdditional\('; purpose = 'cache state after loading'; required = $true; absence_rationale = '' },
    [ordered]@{ event = 'BlockEntity removal'; files = @($basePath, $headPath, $projectilePath); pattern = 'setRemoved\('; purpose = 'old Level/entity references after removal'; required = $true; absence_rationale = '' },
    [ordered]@{ event = 'BlockEntity insertion'; files = @($basePath, $headPath, $projectilePath); pattern = 'blockEntityIn\('; purpose = 'cache initialization/invalidation on insertion'; required = $false; absence_rationale = 'A newly constructed BlockEntity starts with empty caches; a reused instance is cleared by setRemoved(), and the level identity key invalidates derived views before reuse.' },
    [ordered]@{ event = 'Level change/unload'; files = @($basePath, $headPath, $projectilePath); pattern = 'onLoad\(|onChunkUnloaded\('; purpose = 'cross-Level cache lifetime'; required = $false; absence_rationale = 'Caches are keyed by the current Level where applicable, and setRemoved() clears all custom caches on removal; no asynchronous owner retains the cache.' }
)
$lifecycleObservations = @()
$openCacheChecks = @()
foreach ($check in $lifecycleChecks) {
    $matches = @()
    foreach ($file in $check.files) {
        $matches += @(Find-SourcePattern $file $check.pattern)
    }
    $observed = $matches.Count -gt 0
    $lifecycleObservations += [ordered]@{
        event = $check.event
        purpose = $check.purpose
        observed = $observed
        required = $check.required
        absence_rationale = $check.absence_rationale
        disposition = if ($observed) { 'observed' } elseif ($check.required) { 'open_required_hook' } else { 'safe_by_invariant' }
        references = @($matches)
    }
    if (-not $observed -and $check.required) {
        $openCacheChecks += "$($check.event): no matching source hook observed; determine whether the lifecycle requires an explicit invalidation or whether the absence is safe."
    }
}

$cacheLedger = [ordered]@{
    schema = 'omt.architecture-optimization.cache-invalidation-ledger.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    owner = 'TurretBaseBlockEntity'
    source_files = @(
        [ordered]@{ path = $basePath; sha256 = Get-SourceHash $basePath },
        [ordered]@{ path = $baseBlockPath; sha256 = Get-SourceHash $baseBlockPath },
        [ordered]@{ path = $headPath; sha256 = Get-SourceHash $headPath }
    )
    entries = @($cacheEntries)
    lifecycle_observations = @($lifecycleObservations)
    open_checks = @($openCacheChecks)
    conclusion = if ($openCacheChecks.Count -eq 0) { 'observed_no_open_hook_gap' } else { 'observed_with_lifecycle_checks_open' }
}
Write-JsonFile 'cache-invalidation-ledger.json' $cacheLedger | Out-Null

$commonJavaFiles = @(
    Get-ChildItem -LiteralPath (Resolve-ProjectPath 'src/main/java') -Recurse -Filter '*.java' |
        Where-Object { $_.FullName -notmatch '\\client(\\|$)' }
)
$clientImportHits = @()
foreach ($file in $commonJavaFiles) {
    $relative = Get-RelativeProjectPath $file.FullName
    $clientImportHits += @(Find-SourcePattern $relative 'net\.minecraft\.client')
}
$clientAudit = [ordered]@{
    schema = 'omt.architecture-optimization.client-import-audit.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    scanned_root = 'src/main/java'
    excluded_path = 'src/main/java/**/client/**'
    files_scanned = $commonJavaFiles.Count
    hits = @($clientImportHits)
    result = if ($clientImportHits.Count -eq 0) { 'pass' } else { 'open_common_client_references' }
    interpretation = 'A guarded reference is still a physical-boundary debt even when short-circuiting makes it safe at runtime; it is not reported as a dedicated-server crash.'
}
$clientAudit | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'client-import-audit.txt') -Encoding UTF8

$syncFiles = @(
    'src/main/java/omtteam/openmodularturrets/blockentity/TurretBaseBlockEntity.java',
    'src/main/java/omtteam/openmodularturrets/blockentity/TurretHeadBlockEntity.java',
    'src/main/java/omtteam/openmodularturrets/blockentity/ManualChargerBlockEntity.java',
    'src/main/java/omtteam/openmodularturrets/network/ModNetwork.java'
)
$syncRecords = @()
foreach ($file in $syncFiles) {
    $syncRecords += @(Find-SourcePattern $file 'markForSaveAndSync\(' | ForEach-Object {
        $_.category = 'markForSaveAndSync'
        $_
    })
    $syncRecords += @(Find-SourcePattern $file 'sendBlockEntityUpdateToTracking\(' | ForEach-Object {
        $_.category = 'sendBlockEntityUpdateToTracking'
        $_
    })
    $syncRecords += @(Find-SourcePattern $file '(?<!And)markForSave\(' | ForEach-Object {
        $_.category = 'markForSave'
        $_
    })
}
$syncLedger = [ordered]@{
    schema = 'omt.architecture-optimization.sync-path-ledger.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    semantics = [ordered]@{
        markForSave = 'persistent dirty marker only'
        markForSaveAndSync = 'persistent dirty marker plus explicit tracking update for visual/state changes'
        sendBlockEntityUpdateToTracking = 'explicit targeted tracking update entry point'
    }
    records = @($syncRecords)
    counts = [ordered]@{
        markForSave = @($syncRecords | Where-Object { $_.category -eq 'markForSave' }).Count
        markForSaveAndSync = @($syncRecords | Where-Object { $_.category -eq 'markForSaveAndSync' }).Count
        sendBlockEntityUpdateToTracking = @($syncRecords | Where-Object { $_.category -eq 'sendBlockEntityUpdateToTracking' }).Count
    }
    conclusion = 'observed; phase-2C must not recreate this split'
}
Write-JsonFile 'sync-path-ledger.json' $syncLedger | Out-Null

$persistenceFiles = @($basePath, $headPath, $projectilePath)
$persistenceEntries = @()
foreach ($file in $persistenceFiles) {
    $lines = Get-SourceLines $file
    $keyMatches = @()
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        if ($line -match 'tag\.(?:put\w*|get\w*|contains|remove)\(\s*"([^"]+)"') {
            $keyMatches += [ordered]@{
                key = $Matches[1]
                line = $index + 1
                operation = $line.Trim()
            }
        }
    }
    $persistenceEntries += [ordered]@{
        file = $file
        sha256 = Get-SourceHash $file
        observed_tag_accesses = @($keyMatches)
    }
}

$persistenceExpectations = @(
    [ordered]@{
        file = $basePath
        save_load_keys = @(
            'data_version', 'owner', 'owner_name', 'owner_team', 'energy',
            'inventory', 'mode_id', 'redstone_powered', 'active',
            'use_global_trust', 'local_trust_revision', 'attack_hostile',
            'attack_neutral', 'attack_players', 'multi_targeting', 'range',
            'shots_fired', 'kills', 'player_kills', 'addon_render_mask',
            'camouflage_state', 'camouflage_light_value',
            'camouflage_light_opacity', 'local_trust', 'mode'
        )
        legacy_read_only_keys = @('mode')
        update_tag_keys = @(
            'energy', 'mode_id', 'redstone_powered', 'active',
            'use_global_trust', 'owner_name', 'owner', 'range',
            'addon_render_mask', 'camouflage_state',
            'camouflage_light_value', 'camouflage_light_opacity'
        )
    },
    [ordered]@{
        file = $headPath
        save_load_keys = @(
            'data_version', 'cooldown', 'aim_yaw', 'aim_pitch',
            'priority_max_health', 'priority_missing_health',
            'priority_distance', 'priority_armor', 'priority_player'
        )
        legacy_read_only_keys = @()
        update_tag_keys = @(
            'target', 'aim_yaw', 'aim_pitch', 'priority_max_health',
            'priority_missing_health', 'priority_distance',
            'priority_armor', 'priority_player'
        )
    },
    [ordered]@{
        file = $projectilePath
        save_load_keys = @(
            'data_version', 'projectile_kind', 'damage', 'damage_amp_level',
            'fake_drops_level', 'suppress_loot', 'grenade_hit',
            'source_base_pos', 'target_uuid'
        )
        legacy_read_only_keys = @()
        update_tag_keys = @()
    }
)
$persistenceClassifications = @()
foreach ($expectation in $persistenceExpectations) {
    $entry = @($persistenceEntries | Where-Object { $_.file -eq $expectation.file })[0]
    $observedKeys = @($entry.observed_tag_accesses | ForEach-Object { $_.key } | Sort-Object -Unique)
    $expectedKeys = @($expectation.save_load_keys + $expectation.update_tag_keys | Sort-Object -Unique)
    $missingKeys = @($expectedKeys | Where-Object { $_ -notin $observedKeys })
    $unexpectedKeys = @($observedKeys | Where-Object { $_ -notin $expectedKeys })
    $persistenceClassifications += [ordered]@{
        file = $expectation.file
        save_load_keys = @($expectation.save_load_keys)
        legacy_read_only_keys = @($expectation.legacy_read_only_keys)
        update_tag_keys = @($expectation.update_tag_keys)
        observed_keys = @($observedKeys)
        missing_expected_keys = @($missingKeys)
        unexpected_observed_keys = @($unexpectedKeys)
        result = if ($missingKeys.Count -eq 0 -and $unexpectedKeys.Count -eq 0) { 'pass' } else { 'mismatch' }
    }
}
$persistenceLedger = [ordered]@{
    schema = 'omt.architecture-optimization.persistence-key-ledger.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    files = @($persistenceEntries)
    classifications = @($persistenceClassifications)
    result = if (@($persistenceClassifications | Where-Object { $_.result -ne 'pass' }).Count -eq 0) { 'pass' } else { 'mismatch' }
    note = 'Observed tag accesses are reconciled against the frozen save/load and update-tag key sets. The legacy mode key is read-only compatibility input; no key is authorized to change in this batch.'
}
Write-JsonFile 'persistence-key-ledger.json' $persistenceLedger | Out-Null

$javaFiles = @(
    Get-ChildItem -LiteralPath (Resolve-ProjectPath 'src/main/java') -Recurse -Filter '*.java'
)
$edgeCounts = @{}
$edgeExamples = @{}
foreach ($file in $javaFiles) {
    $sourceLayer = Get-Layer (Get-RelativeProjectPath $file.FullName)
    $imports = Select-String -LiteralPath $file.FullName -Pattern '^import\s+omtteam\.openmodularturrets\.([^;]+);' -AllMatches
    foreach ($match in @($imports)) {
        $imported = $match.Matches[0].Groups[1].Value
        $targetLayer = ($imported -split '\.')[0]
        $edge = "$sourceLayer -> $targetLayer"
        if (-not $edgeCounts.ContainsKey($edge)) { $edgeCounts[$edge] = 0 }
        $edgeCounts[$edge]++
        if (-not $edgeExamples.ContainsKey($edge)) { $edgeExamples[$edge] = @() }
        if ($edgeExamples[$edge].Count -lt 5) {
            $edgeExamples[$edge] += "$(Get-RelativeProjectPath $file.FullName) -> $imported"
        }
    }
}
$dependencyMarkdown = @(
    '# Phase 0 dependency graph audit',
    '',
    "Generated UTC: $([DateTime]::UtcNow.ToString('o'))",
    '',
    '| Source layer | Target layer | Import count | Examples |',
    '| --- | --- | ---: | --- |'
)
foreach ($edge in @($edgeCounts.Keys | Sort-Object)) {
    $parts = $edge -split ' -> '
    $examples = ($edgeExamples[$edge] -join '<br>')
    $dependencyMarkdown += "| $($parts[0]) | $($parts[1]) | $($edgeCounts[$edge]) | $examples |"
}
$dependencyMarkdown += @(
    '',
    'Interpretation:',
    '',
    '- `service -> blockentity` is an open-boundary signal only when observed; phase-1 services should depend on narrow state/query interfaces and keep world/permission calls in explicit server adapters.',
    '- `blockentity -> client` and common/server imports of `net.minecraft.client` require separate physical-boundary review.',
    '- Import counts are evidence for review, not a line-count quality score.'
)
$dependencyMarkdown -join "`r`n" | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'dependency-graph.md') -Encoding UTF8

$baselineStatus = 'not_run'
$baselineSummaryPath = Join-Path $EvidenceRoot 'baseline-summary.json'
if (Test-Path -LiteralPath $baselineSummaryPath -PathType Leaf) {
    try {
        $baselineSummary = Get-Content -LiteralPath $baselineSummaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $baselineRuns = @($baselineSummary.runs)
        $invalidRuns = @(
            $baselineRuns | Where-Object {
                [int]$_.exit_code -ne 0 -or
                [int]$_.fixture_samples -lt 200
            }
        )
        if ($baselineSummary.label -eq 'baseline' -and
                $baselineRuns.Count -ge 3 -and
                $invalidRuns.Count -eq 0) {
            $baselineStatus = 'pass'
        } else {
            $baselineStatus = 'present_incomplete'
        }
    } catch {
        $baselineStatus = 'unreadable'
    }
}

$phaseStatus = [ordered]@{
    schema = 'omt.architecture-optimization.phase-status.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    batch = 'architecture-optimization-20260807'
    phase = '0'
    status = if ($baselineStatus -eq 'pass' -and
            $cacheLedger.conclusion -eq 'observed_no_open_hook_gap' -and
            $persistenceLedger.result -eq 'pass' -and
            $clientAudit.result -eq 'pass') {
        'completed_with_deferred_manual_client'
    } elseif ($baselineStatus -eq 'pass' -and
            $cacheLedger.conclusion -eq 'observed_no_open_hook_gap' -and
            $persistenceLedger.result -eq 'pass') {
        'completed_with_deferred_client_boundary'
    } else {
        'audit_collected'
    }
    contract_executor_audit = 'separate command; see contract-test-audit.json'
    cache_audit = $cacheLedger.conclusion
    client_import_audit = $clientAudit.result
    sync_ledger = 'generated'
    persistence_ledger = $persistenceLedger.result
    baseline = $baselineStatus
    contract_gate = 'not_run_in_this_script'
    next = if ($baselineStatus -eq 'pass' -and $clientAudit.result -eq 'pass') {
        'Phase 0 evidence is complete; the manual client round remains intentionally deferred to phase 7. Proceed with final automated regression and performance closure.'
    } elseif ($baselineStatus -eq 'pass') {
        'Phase 0 evidence is complete except the intentionally deferred OmtTooltips client bridge; complete the client boundary review before final closure.'
    } else {
        'Run or repair the current-workspace baseline, then complete the L1/L2/L3/L4 regression before closing phase 0.'
    }
}
Write-JsonFile 'phase-status.json' $phaseStatus | Out-Null

Write-Output "Phase 0 audit evidence generated under: $EvidenceRoot"
Write-Output "Cache conclusion: $($cacheLedger.conclusion)"
Write-Output "Client import result: $($clientAudit.result); hits=$($clientImportHits.Count)"
Write-Output "Sync records: $($syncRecords.Count); persistence files: $($persistenceEntries.Count)"
