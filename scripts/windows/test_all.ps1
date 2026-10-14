param(
    [switch]$SkipFastTests,
    [string]$AppDataDir
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent (Split-Path -Parent $scriptDir)
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactRoot = Join-Path $root "artifacts\test_all\windows\$timestamp"
if ([string]::IsNullOrWhiteSpace($AppDataDir)) {
    $appDataDir = Join-Path $artifactRoot "app-data"
}
else {
    $appDataDir = [System.IO.Path]::GetFullPath($AppDataDir)
}
$testInstallRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("TranscribeMate-release-smoke-" + [guid]::NewGuid().ToString("N"))
$pythonEnvDir = Join-Path $root ".venv"
$pythonExe = Join-Path $pythonEnvDir "Scripts\python.exe"
$releaseZip = $null
$appVersion = "$(Get-Date -Format 'yy.MM.dd').999"
$pwshExe = Join-Path $PSHOME "pwsh.exe"
$stageSummary = [System.Collections.Generic.List[string]]::new()

New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
$env:TM_APP_DATA_DIR = $appDataDir
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
$env:HF_HUB_DISABLE_TELEMETRY = "1"
Remove-Item Env:TM_PROJECT_ROOT -ErrorAction SilentlyContinue
Remove-Item Env:TM_BACKEND_ROOT -ErrorAction SilentlyContinue
Remove-Item Env:TM_BACKEND_CMD -ErrorAction SilentlyContinue
Remove-Item Env:TM_BACKEND_PYTHON -ErrorAction SilentlyContinue

function Invoke-Stage(
    [string]$Name,
    [string]$Executable,
    [string[]]$Arguments,
    [string]$WorkingDirectory = $root
) {
    $logPath = Join-Path $artifactRoot "$Name.log"
    Write-Host "[TM-WIN-FULL] Starting $Name (log: $logPath)"
    Push-Location $WorkingDirectory
    try {
        & $Executable @Arguments 2>&1 | Tee-Object -FilePath $logPath
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "$Name failed with exit code $exitCode. See $logPath"
        }
        $stageSummary.Add("${Name}: OK")
        Write-Host "[TM-WIN-FULL] $Name completed."
    }
    catch {
        $stageSummary.Add("${Name}: FAILED")
        Write-Error "[TM-WIN-FULL] $Name failed. See $logPath"
        throw
    }
    finally {
        Pop-Location
    }
}

function Write-Summary {
    $summaryPath = Join-Path $artifactRoot "summary.txt"
    @(
        "TranscribeMate Windows full-suite"
        "Timestamp: $timestamp"
        "App version: $appVersion"
        "Artifacts: $artifactRoot"
        "App data: $appDataDir"
        ""
        $stageSummary
    ) | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    Get-Content -LiteralPath $summaryPath
}

try {
    $java = Get-Command java -ErrorAction Stop
    $jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\jpackage.exe" } else { Get-Command jpackage -ErrorAction Stop | Select-Object -ExpandProperty Source }
    $maven = Get-Command mvn -ErrorAction Stop | Select-Object -ExpandProperty Source
    $basePython = Get-Command python -ErrorAction Stop | Select-Object -ExpandProperty Source

    @(
        "Repository: $root"
        "Artifacts: $artifactRoot"
        "App data: $appDataDir"
        "Java: $($java.Source)"
        "jpackage: $jpackage"
        "Maven: $maven"
        "Python: $basePython"
    ) | Set-Content -LiteralPath (Join-Path $artifactRoot "environment.txt") -Encoding UTF8

    if (-not (Test-Path -LiteralPath $pythonExe)) {
        Invoke-Stage -Name "01-create-python-venv" -Executable $basePython -Arguments @("-m", "venv", $pythonEnvDir)
    }
    $pythonVersion = & $pythonExe -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
    if ($LASTEXITCODE -ne 0 -or $pythonVersion -ne "3.12") {
        throw "Repository .venv must use Python 3.12; found '$pythonVersion'. Remove .venv and rerun this workflow."
    }

    Invoke-Stage -Name "02-install-python-dependencies" -Executable $pythonExe -Arguments @(
        "-m", "pip", "install", "--upgrade", "pip"
    )
    Invoke-Stage -Name "03-install-test-dependencies" -Executable $pythonExe -Arguments @(
        "-m", "pip", "install", "-r", "requirements-dev.txt"
    )

    if (-not $SkipFastTests) {
        Invoke-Stage -Name "04-backend-pytest" -Executable $pythonExe -Arguments @("-m", "pytest", "-q")
    }

    Invoke-Stage -Name "04b-bootstrap-integrity-corrupt-artifact" -Executable $pwshExe -Arguments @(
        "-NoProfile", "-File", (Join-Path $root "scripts\windows\test_bootstrap_integrity.ps1")
    )

    Invoke-Stage -Name "05-build-windows-release" -Executable $pwshExe -Arguments @(
        "-NoProfile", "-File", (Join-Path $root "scripts\windows\build_v2_release.ps1"), "-AppVersion", $appVersion
    )

    $releaseZip = Join-Path $root "dist\windows\TranscribeMate-$appVersion-win-x64.zip"
    $checksumPath = Join-Path $root "dist\windows\checksums.txt"
    if (-not (Test-Path -LiteralPath $releaseZip)) {
        throw "Windows release ZIP was not generated: $releaseZip"
    }
    if (-not (Test-Path -LiteralPath $checksumPath)) {
        throw "Windows release checksum file was not generated: $checksumPath"
    }
    $actualHash = (Get-FileHash -LiteralPath $releaseZip -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksumLine = Get-Content -LiteralPath $checksumPath | Where-Object { $_ -match [regex]::Escape((Split-Path -Leaf $releaseZip)) } | Select-Object -First 1
    if (-not $checksumLine -or $checksumLine.Split(' ', 2)[0].ToLowerInvariant() -ne $actualHash) {
        throw "Windows release ZIP checksum mismatch: $releaseZip"
    }

    Expand-Archive -LiteralPath $releaseZip -DestinationPath $testInstallRoot -Force
    $extractRoot = $testInstallRoot
    $appImageDir = Join-Path $extractRoot "TranscribeMate"
    $bundledBackendDir = Join-Path $appImageDir "app\backend"
    foreach ($requiredPath in @(
        (Join-Path $appImageDir "TranscribeMate.exe"),
        (Join-Path $appImageDir "LICENSE.txt"),
        (Join-Path $bundledBackendDir "bootstrap_runtime.ps1"),
        (Join-Path $bundledBackendDir "transcribemate\v2\backend\server.py")
    )) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "Windows release ZIP is missing required file: $requiredPath"
        }
    }

    Invoke-Stage -Name "06-first-launch-runtime-setup" -Executable $pwshExe -Arguments @(
        "-NoProfile", "-File", (Join-Path $bundledBackendDir "bootstrap_runtime.ps1"),
        "-BackendRoot", $bundledBackendDir,
        "-WithModels",
        "-InstallDiarization",
        "-DownloadFfmpeg",
        "-LogFile", (Join-Path $artifactRoot "runtime-bootstrap.log"),
        "-ProgressFile", (Join-Path $artifactRoot "runtime-progress.txt")
    )

    $runtimeReadyPath = Join-Path $appDataDir "runtime\runtime-ready.json"
    if (-not (Test-Path -LiteralPath $runtimeReadyPath -PathType Leaf)) {
        throw "Clean first-launch bootstrap did not write its runtime-ready marker: $runtimeReadyPath"
    }
    $runtimeReady = Get-Content -LiteralPath $runtimeReadyPath -Raw | ConvertFrom-Json
    if (-not $runtimeReady.bootstrap_complete -or
        $runtimeReady.platform -ne "windows" -or
        $runtimeReady.python_relpath -ne "runtime/python/python.exe" -or
        $runtimeReady.app_version -ne $appVersion) {
        throw "Clean first-launch bootstrap wrote an unexpected runtime-ready marker: $runtimeReadyPath"
    }
    $stageSummary.Add("06b-runtime-ready-marker: OK")
    Write-Host "[TM-WIN-FULL] Runtime-ready marker validated: $runtimeReadyPath"

    $managedPythonExe = Join-Path $appDataDir "runtime\python\python.exe"
    if (-not (Test-Path -LiteralPath $managedPythonExe -PathType Leaf)) {
        throw "First-launch setup completed without managed Python: $managedPythonExe"
    }

    Invoke-Stage -Name "08-install-fixture-validator" -Executable $managedPythonExe -Arguments @(
        "-m", "pip", "install", "pypdf>=6.8.0,<7"
    )

    $fixtureArgs = @(
        (Join-Path $root "tests\full_suite\run_fixture_regression.py"),
        "--fixtures-dir", (Join-Path $root "tests\test_files"),
        "--manifest", (Join-Path $root "tests\test_files\manifest.json"),
        "--artifact-dir", (Join-Path $artifactRoot "fixture-regression"),
        "--app-data-dir", $appDataDir,
        "--backend-root", $bundledBackendDir,
        "--projects-root", (Join-Path $appDataDir "workspace\projects"),
        "--stdio-server"
    )
    Invoke-Stage -Name "09-packaged-backend-stdio-transcription" -Executable $managedPythonExe -Arguments $fixtureArgs

    $workspaceRoot = Join-Path $appDataDir "workspace"
    $offlineProjectRoot = Join-Path $workspaceRoot "projects\offline-fixtures"
    $videoProjectRoot = Join-Path $workspaceRoot "projects\video-smoke"
    $workspaceTimestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $workspaceState = [ordered]@{
        version = 1
        active_project_id = "offline-fixtures"
        projects = @(
            [ordered]@{
                project_id = "offline-fixtures"
                name = "Packaged Transcription Acceptance"
                root_dir = $offlineProjectRoot
                created_at = $workspaceTimestamp
                updated_at = $workspaceTimestamp
                last_opened_at = $workspaceTimestamp
            },
            [ordered]@{
                project_id = "video-smoke"
                name = "Packaged Video Acceptance"
                root_dir = $videoProjectRoot
                created_at = $workspaceTimestamp
                updated_at = $workspaceTimestamp
                last_opened_at = $workspaceTimestamp
            }
        )
    }
    $workspaceMetaPath = Join-Path $workspaceRoot "workspace.json"
    $workspaceState | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $workspaceMetaPath -Encoding UTF8

    $env:TM_UI_TEST_MODE = "1"
    $appExe = Join-Path $appImageDir "TranscribeMate.exe"
    $startupLogPath = Join-Path $artifactRoot "packaged-ui-startup.log"
    $appProcess = Start-Process -FilePath $appExe -WorkingDirectory $appImageDir -WindowStyle Hidden -PassThru
    try {
        $startupDeadline = (Get-Date).AddSeconds(90)
        $startupConfirmed = $false
        while ((Get-Date) -lt $startupDeadline) {
            $appProcess.Refresh()
            if ($appProcess.HasExited) {
                throw "Packaged TranscribeMate.exe exited during startup with code $($appProcess.ExitCode)."
            }

            $appLogFiles = Get-ChildItem -LiteralPath (Join-Path $appDataDir "Logs") -Filter "frontend-*.log" -File -ErrorAction SilentlyContinue
            $appLogText = ($appLogFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue }) -join "`n"
            if (
                $appLogText -match "Backend process started \(pid=" -and
                $appLogText -match "Primary stage displayed\." -and
                $appLogText -match "ui\.test\.files\.loaded.*project_id=offline-fixtures.*transcript_count=3.*visible_transcript_count=3.*visible_output_transcript_count=3"
            ) {
                $startupConfirmed = $true
                $appLogText | Set-Content -LiteralPath $startupLogPath -Encoding UTF8
                break
            }
            Start-Sleep -Seconds 2
        }
        if (-not $startupConfirmed) {
            $appLogFiles = Get-ChildItem -LiteralPath (Join-Path $appDataDir "Logs") -Filter "frontend-*.log" -File -ErrorAction SilentlyContinue
            ($appLogFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue }) -join "`n" |
                Set-Content -LiteralPath $startupLogPath -Encoding UTF8
            throw "Packaged app did not display the main window and load all three transcripts into its Files view within 90 seconds."
        }
        $stageSummary.Add("10-packaged-ui-files-view: OK")
        Write-Host "[TM-WIN-FULL] Packaged JavaFX app loaded the generated project transcripts into Files."
    }
    finally {
        $appProcess.Refresh()
        if (-not $appProcess.HasExited) {
            try {
                $appProcess.Kill($true)
                $appProcess.WaitForExit(15000)
            }
            catch {
                Write-Warning "Could not stop packaged app process tree cleanly: $($_.Exception.Message)"
            }
        }
    }

    $stageSummary.Add("Release ZIP: $releaseZip")
    Write-Summary
    Write-Host "[TM-WIN-FULL] Full Windows suite completed successfully."
}
catch {
    Write-Summary
    throw
}
