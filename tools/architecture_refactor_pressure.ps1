[CmdletBinding()]
param(
    [ValidateSet('baseline', 'candidate', 'compare', 'old-save-check', 'manifest')]
    [string]$Mode = 'manifest',
    [string]$EvidenceRoot = 'D:\c128\phase25-evidence\architecture-refactor-20260806',
    [ValidateRange(1, 10)]
    [int]$Runs = 3,
    [ValidateRange(20, 2000)]
    [int]$WarmupTicks = 200,
    [ValidateRange(20, 2000)]
    [int]$SampleTicks = 200,
    [string]$OldSaveWorld = 'D:\c128\phase25-evidence\phase25-runtime-20260806-oldsave3\world'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$EvidenceRoot = [IO.Path]::GetFullPath($EvidenceRoot)
New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    # The explicit @() at call sites keeps PS 5.1 from collapsing one-item
    # collections into objects, which would make records schema-unstable.
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-SourceManifest {
    $roots = @(
        (Join-Path $ProjectRoot 'src/main/java'),
        (Join-Path $ProjectRoot 'src/main/resources'),
        (Join-Path $ProjectRoot 'src/generated/resources'),
        (Join-Path $ProjectRoot 'docs/features'),
        (Join-Path $ProjectRoot 'docs/porting'),
        (Join-Path $ProjectRoot 'tools')
    )
    $records = @()
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) {
            continue
        }
        foreach ($file in @(Get-ChildItem -LiteralPath $root -Recurse -File | Sort-Object FullName)) {
            $relative = $file.FullName.Substring($ProjectRoot.Length + 1).Replace('\', '/')
            $records += [ordered]@{
                path = $relative
                sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                bytes = [int64]$file.Length
            }
        }
    }
    return @($records)
}

function New-Manifest {
    $status = @(git -C $ProjectRoot status --short)
    $records = @(Get-SourceManifest)
    return [ordered]@{
        schema = 'omt.architecture-refactor.manifest.v1'
        created_utc = [DateTime]::UtcNow.ToString('o')
        project = $ProjectRoot
        minecraft = '1.21.1'
        neoforge = '21.1.234'
        java_executable = (Get-Command java -ErrorAction SilentlyContinue).Source
        git_status = @($status)
        records = @($records)
    }
}

function Get-ChildJavaProcesses {
    $processes = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue)
    return @($processes | Where-Object {
        # ModDevGradle launches the actual test JVM through DevLaunch.Main and
        # passes the run identity via the generated VM-args file.  The namespace
        # is a JVM system property, not a stable command-line token.
        $_.CommandLine -and ($_.CommandLine -match
            'gameTestServerRunVmArgs\.txt|net\.neoforged\.devlaunch\.Main')
    })
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percent)
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($Percent * $sorted.Count) - 1)
    return [Math]::Round([double]$sorted[$index], 6)
}

function Read-JfrTickValues {
    param([string]$JfrPath)
    if (-not (Test-Path -LiteralPath $JfrPath)) {
        return @()
    }
    $jsonPath = "$JfrPath.json"
    $plainPath = "$JfrPath.txt"
    & jfr print --json --events minecraft.ServerTickTime $JfrPath 2> "$JfrPath.json.err" |
        Set-Content -LiteralPath $jsonPath -Encoding UTF8
    & jfr print --events minecraft.ServerTickTime $JfrPath 2> "$JfrPath.txt.err" |
        Set-Content -LiteralPath $plainPath -Encoding UTF8

    $values = @()
    $plain = if (Test-Path -LiteralPath $plainPath) { Get-Content -Raw $plainPath } else { '' }
    foreach ($match in [regex]::Matches($plain,
            'averageTickDuration\s*=\s*(?<value>[0-9]+(?:\.[0-9]+)?)\s*(?<unit>ms|ns|s)')) {
        $value = [double]::Parse($match.Groups['value'].Value,
            [Globalization.CultureInfo]::InvariantCulture)
        switch ($match.Groups['unit'].Value) {
            'ns' { $values += $value / 1000000.0 }
            's' { $values += $value * 1000.0 }
            default { $values += $value }
        }
    }
    if ($values.Count -eq 0) {
        $json = if (Test-Path -LiteralPath $jsonPath) { Get-Content -Raw $jsonPath } else { '' }
        # JFR JSON commonly emits @Timespan as an ISO-8601 duration.
        foreach ($match in [regex]::Matches($json,
                'averageTickDuration"\s*:\s*"PT(?<seconds>[0-9]+(?:\.[0-9]+)?)S"')) {
            $seconds = [double]::Parse($match.Groups['seconds'].Value,
                [Globalization.CultureInfo]::InvariantCulture)
            $values += $seconds * 1000.0
        }
    }
    return @($values | Select-Object -Unique)
}

function Read-PressureFixtureMetrics {
    param([string]$LogPath)
    $log = Get-Content -LiteralPath $LogPath -Raw
    $pattern = 'OMT_PRESSURE_METRICS mean_ms=(?<mean>[0-9.]+) p50_ms=(?<p50>[0-9.]+) p95_ms=(?<p95>[0-9.]+) p99_ms=(?<p99>[0-9.]+) max_ms=(?<max>[0-9.]+) samples=(?<samples>[0-9]+)'
    $match = [regex]::Match($log, $pattern)
    if (-not $match.Success) {
        return $null
    }
    $parse = { param([string]$value) [double]::Parse($value,
        [Globalization.CultureInfo]::InvariantCulture) }
    return [ordered]@{
        mean_ms = & $parse $match.Groups['mean'].Value
        p50_ms = & $parse $match.Groups['p50'].Value
        p95_ms = & $parse $match.Groups['p95'].Value
        p99_ms = & $parse $match.Groups['p99'].Value
        max_ms = & $parse $match.Groups['max'].Value
        samples = [int]$match.Groups['samples'].Value
    }
}

function Invoke-PressureRun {
    param([string]$Label, [int]$RunNumber)
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
    $runDir = Join-Path $EvidenceRoot "$Label-$RunNumber-$stamp"
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $logPath = Join-Path $runDir 'gametest.log'
    $errPath = Join-Path $runDir 'gametest.err.log'
    $jfrPath = Join-Path $runDir 'server-ticks.jfr'
    $manifestPath = Join-Path $runDir 'manifest.json'
    Write-JsonFile $manifestPath (New-Manifest)

    $beforeJava = @(Get-Process java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
    $started = [DateTime]::UtcNow
    $arguments = @(
        'runGameTestServer', '--no-daemon', '--console=plain',
        '-PgameTestNamespaces=openmodularturrets'
    )
    $process = Start-Process -FilePath (Join-Path $ProjectRoot 'gradlew.bat') `
        -ArgumentList $arguments -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $logPath -RedirectStandardError $errPath -PassThru

    $jfrStarted = $false
    $deadline = [DateTime]::UtcNow.AddMinutes(35)
    while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        if (-not $jfrStarted) {
            $java = @(Get-ChildJavaProcesses | Where-Object {
                # WMI CreationDate is local-time text on this host while the
                # harness clock is UTC; the pre-existing PID set is the stable
                # boundary and avoids a timezone-dependent false negative.
                ($beforeJava -notcontains $_.ProcessId)
            } | Sort-Object ProcessId)
            foreach ($candidate in $java) {
                $jfrOutput = & jcmd $candidate.ProcessId JFR.start `
                    "name=omt-$Label-$RunNumber" `
                    'settings=profile' 'dumponexit=true' 'duration=30m' `
                    'jdk.ExecutionSample#period=10ms' `
                    "filename=$jfrPath" 2>&1
                $jfrOutput | Set-Content -LiteralPath (Join-Path $runDir 'jfr-start.log') -Encoding UTF8
                if (@($jfrOutput -join "`n") -match 'Started recording') {
                    $jfrStarted = $true
                    break
                }
            }
        }
        Start-Sleep -Seconds 1
        $process.Refresh()
    }
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        throw "Pressure GameTest timed out: $runDir"
    }
    $process.Refresh()
    $exitCode = $process.ExitCode
    if ($null -eq $exitCode) {
        $exitCode = if ((Get-Content -LiteralPath $logPath -Raw) -match 'BUILD SUCCESSFUL') {
            0
        } else {
            1
        }
    }
    Start-Sleep -Seconds 2

    $tickValues = @(Read-JfrTickValues $jfrPath)
    $fixtureMetrics = Read-PressureFixtureMetrics $logPath
    $warmupEvents = [Math]::Ceiling($WarmupTicks / 20.0)
    $sampleValues = @($tickValues | Select-Object -Skip ([int]$warmupEvents))
    if ($exitCode -ne 0 -or $null -eq $fixtureMetrics -or $fixtureMetrics.samples -lt $SampleTicks) {
        throw "Pressure run did not produce a complete GameTest/fixture sample: $runDir"
    }
    $jfrPositiveValues = @($tickValues | Where-Object { $_ -gt 0.0 })
    $record = [ordered]@{
        label = $Label
        run = $RunNumber
        exit_code = $exitCode
        jfr_started = $jfrStarted
        jfr_events = $tickValues.Count
        jfr_positive_events = $jfrPositiveValues.Count
        warmup_events_discarded = [int]$warmupEvents
        sample_events = $sampleValues.Count
        fixture_samples = $fixtureMetrics.samples
        mean_ms = $fixtureMetrics.mean_ms
        p50_ms = $fixtureMetrics.p50_ms
        p95_ms = $fixtureMetrics.p95_ms
        p99_ms = $fixtureMetrics.p99_ms
        max_ms = $fixtureMetrics.max_ms
        jfr_p95_ms = Get-Percentile $jfrPositiveValues 0.95
        directory = $runDir
    }
    Write-JsonFile (Join-Path $runDir 'metrics.json') $record
    return $record
}

function Invoke-Manifest {
    $path = Join-Path $EvidenceRoot 'candidate-manifest.json'
    Write-JsonFile $path (New-Manifest)
    Write-Output "Manifest: $path"
}

function New-RconPacket {
    param(
        [int]$RequestId,
        [int]$Type,
        [string]$Payload
    )
    $payloadBytes = [Text.Encoding]::UTF8.GetBytes($Payload)
    $body = New-Object byte[] ($payloadBytes.Length + 10)
    [Array]::Copy([BitConverter]::GetBytes($RequestId), 0, $body, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes($Type), 0, $body, 4, 4)
    [Array]::Copy($payloadBytes, 0, $body, 8, $payloadBytes.Length)
    $packet = New-Object byte[] ($body.Length + 4)
    [Array]::Copy([BitConverter]::GetBytes($body.Length), 0, $packet, 0, 4)
    [Array]::Copy($body, 0, $packet, 4, $body.Length)
    return ,$packet
}

function Read-RconBytes {
    param(
        [System.IO.Stream]$Stream,
        [byte[]]$Buffer
    )
    $offset = 0
    while ($offset -lt $Buffer.Length) {
        $read = $Stream.Read($Buffer, $offset, $Buffer.Length - $offset)
        if ($read -le 0) {
            throw 'RCON connection closed before a complete response arrived.'
        }
        $offset += $read
    }
}

function Read-RconPacket {
    param([System.IO.Stream]$Stream)
    $lengthBytes = New-Object byte[] 4
    Read-RconBytes $Stream $lengthBytes
    $length = [BitConverter]::ToInt32($lengthBytes, 0)
    if ($length -lt 10 -or $length -gt 1MB) {
        throw "Invalid RCON response length: $length"
    }
    $body = New-Object byte[] $length
    Read-RconBytes $Stream $body
    [pscustomobject]@{
        RequestId = [BitConverter]::ToInt32($body, 0)
        Type = [BitConverter]::ToInt32($body, 4)
        Payload = [Text.Encoding]::UTF8.GetString($body, 8, $length - 10)
    }
}

function Invoke-RconCommand {
    param(
        [int]$Port,
        [string]$Password,
        [string]$Command
    )
    $client = New-Object System.Net.Sockets.TcpClient
    $client.ReceiveTimeout = 5000
    $client.SendTimeout = 5000
    try {
        $client.Connect('127.0.0.1', $Port)
        $stream = $client.GetStream()
        $authId = 1001
        $auth = New-RconPacket $authId 3 $Password
        $stream.Write($auth, 0, $auth.Length)
        $authResponse = Read-RconPacket $stream
        if ($authResponse.RequestId -lt 0) {
            throw 'RCON authentication was rejected.'
        }
        $commandPacket = New-RconPacket ($authId + 1) 2 $Command
        $stream.Write($commandPacket, 0, $commandPacket.Length)
        $null = Read-RconPacket $stream
    } finally {
        $client.Dispose()
    }
}

function Get-FreeTcpPort {
    $listener = New-Object System.Net.Sockets.TcpListener `
            -ArgumentList @([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return [int]$listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Invoke-OldSaveServerPass {
    param(
        [string]$PassName,
        [string]$RunDirectory,
        [string]$WorldDirectory,
        [string]$Java,
        [string[]]$Arguments,
        [int]$RconPort,
        [string]$RconPassword
    )
    $lockPath = Join-Path $WorldDirectory 'session.lock'
    if (Test-Path -LiteralPath $lockPath) {
        Remove-Item -LiteralPath $lockPath -Force
    }
    $serverLog = Join-Path $RunDirectory "server-$PassName.log"
    $serverErr = Join-Path $RunDirectory "server-$PassName.err.log"
    Set-Content -LiteralPath $serverLog -Value '' -Encoding UTF8
    Set-Content -LiteralPath $serverErr -Value '' -Encoding UTF8
    $server = Start-Process -FilePath $Java -ArgumentList $Arguments `
        -WorkingDirectory $RunDirectory -RedirectStandardOutput $serverLog `
        -RedirectStandardError $serverErr -PassThru

    $ready = $false
    $failed = $false
    $stopSent = $false
    $gracefulStop = $false
    $stopError = $null
    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    while (-not $server.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $serverLog) {
            $text = Get-Content -LiteralPath $serverLog -Raw -ErrorAction SilentlyContinue
            if ($text -match 'Done \(' -or $text -match 'For help, type "help"') {
                $ready = $true
            }
            if ($text -match 'Exception in server tick loop|Failed to load level|Could not load level') {
                $failed = $true
            }
        }
        if ($failed) {
            break
        }
        if ($ready -and -not $stopSent) {
            try {
                Invoke-RconCommand $RconPort $RconPassword 'stop'
                $stopSent = $true
            } catch {
                $failed = $true
                $stopError = $_.Exception.Message
            }
        }
        Start-Sleep -Milliseconds 250
        $server.Refresh()
    }

    if ($stopSent -and -not $server.HasExited) {
        $shutdownDeadline = [DateTime]::UtcNow.AddMinutes(2)
        while (-not $server.HasExited -and [DateTime]::UtcNow -lt $shutdownDeadline) {
            Start-Sleep -Milliseconds 250
            $server.Refresh()
        }
    }
    if (-not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
    }
    $server.WaitForExit()
    $server.Refresh()
    $exitCode = $server.ExitCode
    $processExited = $server.HasExited
    $logText = if (Test-Path -LiteralPath $serverLog) {
        Get-Content -LiteralPath $serverLog -Raw -ErrorAction SilentlyContinue
    } else { '' }
    $saveCompleted = $logText -match 'Stopping server' `
            -and $logText -match 'Saving worlds' `
            -and $logText -match 'All dimensions are saved'
    $gracefulStop = $stopSent -and $processExited -and $saveCompleted `
            -and -not $failed
    $record = [ordered]@{
        pass = $PassName
        ready = $ready
        failed = $failed
        stop_sent = $stopSent
        graceful_stop = $gracefulStop
        process_exited = $processExited
        save_completed = $saveCompleted
        exit_code = $exitCode
        stop_error = $stopError
        log = $serverLog
        error_log = $serverErr
    }
    $server.Dispose()
    return $record
}

function Invoke-OldSaveCheck {
    if (-not (Test-Path -LiteralPath $OldSaveWorld)) {
        throw "Real old-save world is missing: $OldSaveWorld"
    }
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
    $runDir = Join-Path $EvidenceRoot "old-save-validation-$stamp"
    $worldTarget = Join-Path $runDir 'world'
    $evidencePrefix = $EvidenceRoot.TrimEnd('\') + '\'
    if (-not $runDir.StartsWith($evidencePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing an old-save copy outside the evidence root: $runDir"
    }
    New-Item -ItemType Directory -Force -Path $worldTarget | Out-Null
    Copy-Item -Path (Join-Path $OldSaveWorld '*') -Destination $worldTarget -Recurse -Force
    $lockPath = Join-Path $worldTarget 'session.lock'
    if (Test-Path -LiteralPath $lockPath) {
        Remove-Item -LiteralPath $lockPath -Force
    }

    $java = (Get-Command java -ErrorAction Stop).Source
    $classpathArgs = "@$ProjectRoot\build\moddev\serverRunClasspath.txt"
    $vmArgs = "@$ProjectRoot\build\moddev\serverRunVmArgs.txt"
    $modFolders = "-Dfml.modFolders=openmodularturrets%%$ProjectRoot\build\classes\java\main;openmodularturrets%%$ProjectRoot\build\resources\main"
    $programArgs = "@$ProjectRoot\build\moddev\serverRunProgramArgs.txt"
    $serverArguments = @(
        $classpathArgs, $vmArgs, $modFolders, 'net.neoforged.devlaunch.Main', $programArgs
    )
    $serverPort = Get-FreeTcpPort
    $rconPort = Get-FreeTcpPort
    $rconPassword = [Guid]::NewGuid().ToString('N')
    @(
        '# Isolated architecture-refactor old-save round-trip server',
        'level-name=world',
        'online-mode=false',
        "server-port=$serverPort",
        'enable-rcon=true',
        "rcon.port=$rconPort",
        "rcon.password=$rconPassword"
    ) | Set-Content -LiteralPath (Join-Path $runDir 'server.properties') -Encoding ASCII
    $beforeLevelDat = Join-Path $worldTarget 'level.dat'
    $beforeHash = if (Test-Path -LiteralPath $beforeLevelDat) {
        (Get-FileHash -LiteralPath $beforeLevelDat -Algorithm SHA256).Hash.ToLowerInvariant()
    } else { $null }
    $first = Invoke-OldSaveServerPass 'load-save' $runDir $worldTarget $java $serverArguments $rconPort $rconPassword
    $afterFirstHash = if (Test-Path -LiteralPath $beforeLevelDat) {
        (Get-FileHash -LiteralPath $beforeLevelDat -Algorithm SHA256).Hash.ToLowerInvariant()
    } else { $null }
    $second = Invoke-OldSaveServerPass 'reload-save' $runDir $worldTarget $java $serverArguments $rconPort $rconPassword
    $afterSecondHash = if (Test-Path -LiteralPath $beforeLevelDat) {
        (Get-FileHash -LiteralPath $beforeLevelDat -Algorithm SHA256).Hash.ToLowerInvariant()
    } else { $null }
    $files = @(Get-ChildItem -LiteralPath $worldTarget -Recurse -File | Sort-Object FullName)
    $roundTripPassed = $first.ready -and $first.graceful_stop `
            -and $second.ready -and $second.graceful_stop `
            -and -not $first.failed -and -not $second.failed
    $record = [ordered]@{
        schema = 'omt.architecture-refactor.old-save-roundtrip.v2'
        checked_utc = [DateTime]::UtcNow.ToString('o')
        source_world = [IO.Path]::GetFullPath($OldSaveWorld)
        copied_world = $worldTarget
        file_count = $files.Count
        source_level_dat_sha256 = if (Test-Path (Join-Path $OldSaveWorld 'level.dat')) {
            (Get-FileHash (Join-Path $OldSaveWorld 'level.dat') -Algorithm SHA256).Hash.ToLowerInvariant()
        } else { $null }
        copied_level_dat_before_sha256 = $beforeHash
        copied_level_dat_after_first_save_sha256 = $afterFirstHash
        copied_level_dat_after_second_save_sha256 = $afterSecondHash
        server_port = $serverPort
        rcon_port = $rconPort
        first_pass = $first
        second_pass = $second
        save_observed = ($null -ne $beforeHash -and $beforeHash -ne $afterFirstHash)
        reload_observed = ($second.ready -and $second.graceful_stop)
        status = if ($roundTripPassed) {
            'pass'
        } else { 'fail' }
    }
    $path = Join-Path $EvidenceRoot 'old-save-input.json'
    Write-JsonFile $path $record
    Write-Output "Old-save round-trip recorded: $path"
    if ($record.status -ne 'pass') {
        throw "Real old-save load/save/reload did not complete cleanly: $runDir"
    }
}

switch ($Mode) {
    'manifest' { Invoke-Manifest }
    'old-save-check' { Invoke-OldSaveCheck }
    'baseline' {
        $records = @()
        for ($i = 1; $i -le $Runs; $i++) {
            $records += Invoke-PressureRun 'baseline' $i
        }
        Write-JsonFile (Join-Path $EvidenceRoot 'baseline-summary.json') ([ordered]@{
            schema = 'omt.architecture-refactor.pressure-summary.v1'
            label = 'baseline'
            runs = @($records)
            manifest = (New-Manifest)
        })
    }
    'candidate' {
        $records = @()
        for ($i = 1; $i -le $Runs; $i++) {
            $records += Invoke-PressureRun 'candidate' $i
        }
        Write-JsonFile (Join-Path $EvidenceRoot 'candidate-summary.json') ([ordered]@{
            schema = 'omt.architecture-refactor.pressure-summary.v1'
            label = 'candidate'
            runs = @($records)
            manifest = (New-Manifest)
        })
    }
    'compare' {
        $baseline = Get-Content -Raw (Join-Path $EvidenceRoot 'baseline-summary.json') | ConvertFrom-Json
        $candidate = Get-Content -Raw (Join-Path $EvidenceRoot 'candidate-summary.json') | ConvertFrom-Json
        $baseP95 = @($baseline.runs | ForEach-Object { $_.p95_ms } | Where-Object { $_ -ne $null })
        $candP95 = @($candidate.runs | ForEach-Object { $_.p95_ms } | Where-Object { $_ -ne $null })
        if ($baseP95.Count -lt $Runs -or $candP95.Count -lt $Runs) {
            throw 'Both baseline and candidate require complete JFR ServerTickTime p95 samples.'
        }
        $base = [double](Get-Percentile $baseP95 0.50)
        $cand = [double](Get-Percentile $candP95 0.50)
        $absoluteDelta = $cand - $base
        $allowed = if ($base -lt 1.0) { 0.25 } else { $base * 0.10 }
        $pass = $absoluteDelta -le $allowed
        $result = [ordered]@{
            schema = 'omt.architecture-refactor.pressure-comparison.v1'
            compared_utc = [DateTime]::UtcNow.ToString('o')
            baseline_median_p95_ms = [Math]::Round($base, 6)
            candidate_median_p95_ms = [Math]::Round($cand, 6)
            absolute_delta_ms = [Math]::Round($absoluteDelta, 6)
            allowed_delta_ms = [Math]::Round($allowed, 6)
            pass = $pass
        }
        Write-JsonFile (Join-Path $EvidenceRoot 'pressure-comparison.json') $result
        $result | ConvertTo-Json -Depth 8
        if (-not $pass) { throw 'Pressure p95 regression exceeds the declared budget.' }
    }
}
