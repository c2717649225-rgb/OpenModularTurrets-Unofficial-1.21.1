[CmdletBinding()]
param(
    [string]$ContractPath = 'docs/features/architecture_optimization.contract.json',
    [string]$OutputPath = '..\..\phase25-evidence\architecture-optimization-20260807\contract-test-audit.json'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-ProjectPath {
    param([string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) {
        return [IO.Path]::GetFullPath($Path)
    }
    return [IO.Path]::GetFullPath((Join-Path $ProjectRoot $Path))
}

function Get-RelativeProjectPath {
    param([string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    if ($full.StartsWith($ProjectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($ProjectRoot.Length).TrimStart('\', '/').Replace('\', '/')
    }
    return $full
}

function Resolve-TestReference {
    param([string]$Reference)
    if ([string]::IsNullOrWhiteSpace($Reference) -or $Reference -notmatch '^(.+)#([A-Za-z_][A-Za-z0-9_]*)$') {
        return [ordered]@{
            supplied = $Reference
            valid_format = $false
            source_file = $null
            method = $null
            method_present = $false
        }
    }

    $className = $Matches[1]
    $method = $Matches[2]
    $relativeJava = ($className -replace '\.', '\') + '.java'
    $sourceFile = Resolve-ProjectPath (Join-Path 'src/main/java' $relativeJava)
    $methodPresent = $false
    if (Test-Path -LiteralPath $sourceFile) {
        $source = Get-Content -LiteralPath $sourceFile -Raw -Encoding UTF8
        $methodPresent = [regex]::IsMatch(
            $source,
            '(?m)\b' + [regex]::Escape($method) + '\s*\('
        )
    }
    return [ordered]@{
        supplied = $Reference
        valid_format = $true
        source_file = Get-RelativeProjectPath $sourceFile
        method = $method
        method_present = $methodPresent
    }
}

function Resolve-CommandFiles {
    param([object[]]$Command)
    $files = @()
    foreach ($token in @($Command)) {
        if ($token -isnot [string]) {
            continue
        }
        if ($token -notmatch '(?i)\.(py|ps1|bat|cmd)$') {
            continue
        }
        $path = Resolve-ProjectPath $token
        $files += [ordered]@{
            token = $token
            path = Get-RelativeProjectPath $path
            exists = Test-Path -LiteralPath $path -PathType Leaf
        }
    }
    return @($files)
}

$contractFullPath = Resolve-ProjectPath $ContractPath
if (-not (Test-Path -LiteralPath $contractFullPath -PathType Leaf)) {
    throw "Contract file not found: $contractFullPath"
}
$contract = Get-Content -LiteralPath $contractFullPath -Raw -Encoding UTF8 | ConvertFrom-Json
$tests = @($contract.acceptance.tests)
$testIds = @{}
$records = @()
$errors = @()

foreach ($test in $tests) {
    $id = [string]$test.id
    if ([string]::IsNullOrWhiteSpace($id)) {
        $errors += 'A test declaration has no id.'
        continue
    }
    if ($testIds.ContainsKey($id)) {
        $errors += "Duplicate test id: $id"
    }
    $testIds[$id] = $true

    $testReference = Resolve-TestReference ([string]$test.test_ref)
    $commandFiles = Resolve-CommandFiles @($test.command)
    $missingCommandFiles = @($commandFiles | Where-Object { -not $_.exists })
    $testRefOkay = (-not $testReference.valid_format -and [string]::IsNullOrWhiteSpace([string]$test.test_ref)) `
        -or ($testReference.valid_format -and $testReference.method_present)
    $commandOkay = @($test.command).Count -gt 0 -and $missingCommandFiles.Count -eq 0
    $executorPresent = $testRefOkay -and $commandOkay
    if (-not $executorPresent) {
        $errors += "Executor missing or unresolved for test id: $id"
    }

    $records += [ordered]@{
        id = $id
        kind = [string]$test.kind
        required = [bool]$test.required
        declared = $true
        test_ref = $testReference
        command = @($test.command)
        command_files = @($commandFiles)
        executor_present = $executorPresent
        execution_status = 'not_evaluated'
    }
}

foreach ($criterion in @($contract.acceptance.criteria)) {
    foreach ($testId in @($criterion.test_ids)) {
        if (-not $testIds.ContainsKey([string]$testId)) {
            $errors += "Criterion $($criterion.id) references undeclared test id: $testId"
        }
    }
}

$outputFullPath = Resolve-ProjectPath $OutputPath
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputFullPath) | Out-Null
$result = [ordered]@{
    schema = 'omt.architecture-optimization.contract-test-audit.v1'
    generated_utc = [DateTime]::UtcNow.ToString('o')
    project = $ProjectRoot
    contract = Get-RelativeProjectPath $contractFullPath
    contract_status = [string]$contract.status
    result = if ($errors.Count -eq 0) { 'pass' } else { 'fail' }
    execution_status_semantics = 'not_evaluated means executor resolution only; it is not a test-pass claim.'
    errors = @($errors)
    records = @($records)
}
$result | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $outputFullPath -Encoding UTF8
Write-Output "Contract test audit: $(Get-RelativeProjectPath $outputFullPath)"
Write-Output "Result: $($result.result); declarations=$($records.Count); errors=$($errors.Count)"
if ($errors.Count -ne 0) {
    exit 1
}
