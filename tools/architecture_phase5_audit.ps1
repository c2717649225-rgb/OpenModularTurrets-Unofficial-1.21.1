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

function Read-Source {
    param([string]$RelativePath)
    $path = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $RelativePath))
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Expected source file is missing: $RelativePath"
    }
    return Get-Content -LiteralPath $path -Raw -Encoding UTF8
}

function Test-Pattern {
    param([string]$RelativePath, [string]$Pattern)
    return [regex]::IsMatch((Read-Source $RelativePath), $Pattern,
        [Text.RegularExpressions.RegexOptions]::Multiline)
}

$definition = 'src/main/java/omtteam/openmodularturrets/data/TurretDefinition.java'
$blocks = 'src/main/java/omtteam/openmodularturrets/registration/ModBlocks.java'
$head = 'src/main/java/omtteam/openmodularturrets/blockentity/TurretHeadBlockEntity.java'
$base = 'src/main/java/omtteam/openmodularturrets/blockentity/TurretBaseBlockEntity.java'
$combat = 'src/main/java/omtteam/openmodularturrets/service/TurretCombatService.java'
$tests = 'src/main/java/omtteam/openmodularturrets/gametest/OpenModularTurretsGameTests.java'
$pressure = 'src/main/java/omtteam/openmodularturrets/gametest/ArchitecturePressureGameTests.java'

$checks = [ordered]@{
    definition = Test-Pattern $definition '\bPOTATO\s*\('
    registration = Test-Pattern $blocks 'POTATO_CANNON_TURRET'
    registration_uses_definition = Test-Pattern $blocks 'new TurretHeadBlock\(TurretDefinition\.POTATO'
    combat_mapping = Test-Pattern $combat 'case POTATO\s*->\s*ProjectileKind\.POTATO'
    head_has_no_potato_branch = -not ((Read-Source $head) -match 'POTATO')
    base_has_no_potato_branch = -not ((Read-Source $base) -match 'POTATO')
    behavior_gametest = Test-Pattern $tests 'potatoTurretAcquiresVisibleHostileAndFires'
    pressure_fixture = Test-Pattern $pressure 'ModBlocks\.POTATO_CANNON_TURRET'
}

$result = [ordered]@{
    schema = 'omt.architecture-optimization.phase5-audit.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    batch = 'architecture-optimization-20260807'
    subject = 'POTATO turret extension path'
    result = if (@($checks.Values | Where-Object { -not $_ }).Count -eq 0) { 'pass' } else { 'fail' }
    checks = $checks
    conclusion = 'The existing Potato turret is definition-driven through registration and combat mapping; Head/Base orchestration has no Potato-specific branch. This is an architecture proof, not authorization to change gameplay or add a new turret in this batch.'
    evidence = @(
        'OpenModularTurretsGameTests#potatoTurretAcquiresVisibleHostileAndFires',
        'ArchitecturePressureGameTests#architecturePressureFixture',
        'L0/L1/L2/L4 gates'
    )
}
$output = Join-Path $EvidenceRoot 'phase5-audit.json'
$result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $output -Encoding UTF8
Write-Output "Phase 5 audit written: $output"
Write-Output "Result: $($result.result)"
if ($result.result -ne 'pass') {
    exit 1
}
