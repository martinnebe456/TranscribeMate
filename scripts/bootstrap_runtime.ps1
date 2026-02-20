param(
    [switch]$WithModels,
    [switch]$InstallDiarization,
    [switch]$DownloadFfmpeg,
    [string]$BackendRoot,
    [string]$ProgressFile,
    [string]$LogFile
)

$ErrorActionPreference = "Stop"

if (-not $PSBoundParameters.ContainsKey("InstallDiarization")) {
    $InstallDiarization = $true
}
if (-not $PSBoundParameters.ContainsKey("DownloadFfmpeg")) {
    $DownloadFfmpeg = $true
}

$script:EmbeddedPythonVersion = "3.12.10"
$script:EmbeddedPythonZipName = "python-$($script:EmbeddedPythonVersion)-embed-amd64.zip"
$script:EmbeddedPythonUrl = "https://www.python.org/ftp/python/$($script:EmbeddedPythonVersion)/$($script:EmbeddedPythonZipName)"
$script:GetPipUrl = "https://bootstrap.pypa.io/get-pip.py"
$script:LastLogWriteWarningUtc = $null

try {
    $tls12 = [Net.SecurityProtocolType]::Tls12
    [Net.ServicePointManager]::SecurityProtocol = $tls12 -bor [Net.ServicePointManager]::SecurityProtocol
}
catch {
    # Best effort only.
}

function Write-LogFileLine([string]$Path, [string]$Line) {
    $maxAttempts = 5
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        try {
            $logDir = Split-Path -Parent $Path
            if (-not [string]::IsNullOrWhiteSpace($logDir) -and -not (Test-Path $logDir)) {
                New-Item -Path $logDir -ItemType Directory -Force | Out-Null
            }

            $encoding = New-Object System.Text.UTF8Encoding($false)
            $stream = New-Object System.IO.FileStream(
                $Path,
                [System.IO.FileMode]::OpenOrCreate,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::ReadWrite
            )
            try {
                [void]$stream.Seek(0, [System.IO.SeekOrigin]::End)
                $writer = New-Object System.IO.StreamWriter($stream, $encoding)
                try {
                    $writer.WriteLine($Line)
                    $writer.Flush()
                }
                finally {
                    $writer.Dispose()
                }
            }
            finally {
                $stream.Dispose()
            }
            return $true
        }
        catch {
            if ($attempt -lt $maxAttempts) {
                Start-Sleep -Milliseconds ([Math]::Min(500, 60 * $attempt))
                continue
            }
            return $false
        }
    }
    return $false
}

function Write-Log([string]$Message, [string]$Level = "INFO") {
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$timestamp] [$Level] $Message"
    Write-Host $line
    if ($script:LogFilePath) {
        if (-not (Write-LogFileLine -Path $script:LogFilePath -Line $line)) {
            $now = Get-Date
            $shouldWarn = $true
            if ($script:LastLogWriteWarningUtc -ne $null) {
                $elapsed = (New-TimeSpan -Start $script:LastLogWriteWarningUtc -End $now).TotalSeconds
                if ($elapsed -lt 15) {
                    $shouldWarn = $false
                }
            }
            if ($shouldWarn) {
                Write-Host "[$timestamp] [WARN] Failed to write runtime-bootstrap.log (file may be locked)."
                $script:LastLogWriteWarningUtc = $now
            }
        }
    }
}

function Update-InstallerProgress([int]$Percent, [string]$Message) {
    $clampedPercent = [Math]::Min(100, [Math]::Max(0, $Percent))
    $script:LastInstallerPercent = $clampedPercent
    if ($script:ProgressFilePath) {
        try {
            Set-Content -Path $script:ProgressFilePath -Value "$clampedPercent|$Message" -Encoding UTF8
        }
        catch {
            # Best effort only.
        }
    }
    Write-Output "TM_PROGRESS|$clampedPercent|$Message"
    Write-Log "[$clampedPercent%] $Message"
}

function Convert-ToProcessArgument([object]$Value) {
    if ($null -eq $Value) {
        return '""'
    }

    $text = [string]$Value
    if ([string]::IsNullOrEmpty($text)) {
        return '""'
    }
    if ($text -notmatch '[\s"]') {
        return $text
    }

    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append('"')

    $backslashCount = 0
    foreach ($char in $text.ToCharArray()) {
        if ($char -eq '\') {
            $backslashCount++
            continue
        }

        if ($char -eq '"') {
            if ($backslashCount -gt 0) {
                [void]$builder.Append(('\' * ($backslashCount * 2)))
                $backslashCount = 0
            }
            [void]$builder.Append('\"')
            continue
        }

        if ($backslashCount -gt 0) {
            [void]$builder.Append(('\' * $backslashCount))
            $backslashCount = 0
        }
        [void]$builder.Append($char)
    }

    if ($backslashCount -gt 0) {
        [void]$builder.Append(('\' * ($backslashCount * 2)))
    }

    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-RuntimePythonWithRetry(
    [string]$PythonExe,
    [string[]]$CommandArgs,
    [string]$Description,
    [int]$Retries = 3,
    [int]$TimeoutSeconds = 0,
    [switch]$IgnoreFailure
) {
    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        Write-Log "Running $Description (attempt $attempt/$Retries)"
        $stdoutFile = Join-Path ([IO.Path]::GetTempPath()) ("tm-bootstrap-stdout-{0}.log" -f ([guid]::NewGuid().ToString("N")))
        $stderrFile = Join-Path ([IO.Path]::GetTempPath()) ("tm-bootstrap-stderr-{0}.log" -f ([guid]::NewGuid().ToString("N")))
        $exitCode = -1
        $timedOut = $false
        try {
            $quotedArgs = @()
            foreach ($arg in $CommandArgs) {
                $quotedArgs += (Convert-ToProcessArgument -Value $arg)
            }
            $argumentLine = ($quotedArgs -join " ")

            $proc = Start-Process `
                -FilePath $PythonExe `
                -ArgumentList $argumentLine `
                -NoNewWindow `
                -PassThru `
                -RedirectStandardOutput $stdoutFile `
                -RedirectStandardError $stderrFile

            $startedAt = Get-Date
            $nextHeartbeat = 30
            while (-not $proc.HasExited) {
                Start-Sleep -Seconds 2
                $elapsedSeconds = [int]((Get-Date) - $startedAt).TotalSeconds

                if ($elapsedSeconds -ge $nextHeartbeat) {
                    if ($null -ne $script:LastInstallerPercent) {
                        Write-Output "TM_PROGRESS|$($script:LastInstallerPercent)|$Description is still running ($elapsedSeconds s elapsed)."
                    }
                    Write-Log "$Description still running ($elapsedSeconds s elapsed)."
                    $nextHeartbeat += 30
                }

                if (($TimeoutSeconds -gt 0) -and ($elapsedSeconds -ge $TimeoutSeconds)) {
                    $timedOut = $true
                    Write-Log "$Description timed out after $TimeoutSeconds seconds. Terminating process." "WARN"
                    try {
                        if (-not $proc.HasExited) {
                            $proc.Kill()
                        }
                    }
                    catch {
                        Write-Log "Failed to terminate timed-out process for ${Description}: $($_.Exception.Message)" "WARN"
                    }
                    break
                }
            }

            if (-not $proc.HasExited) {
                try {
                    $proc.WaitForExit()
                }
                catch {
                    # Best effort only.
                }
            }

            if ($timedOut) {
                $exitCode = 124
            }
            else {
                $exitCode = [int]$proc.ExitCode
            }
        }
        catch {
            Write-Log "$Description failed to start: $($_.Exception.Message)" "WARN"
        }

        try {
            if (Test-Path $stdoutFile) {
                Get-Content -Path $stdoutFile -ErrorAction SilentlyContinue | ForEach-Object {
                    $text = [string]$_
                    if (-not [string]::IsNullOrWhiteSpace($text)) {
                        Write-Log $text "PIP"
                    }
                }
            }
            if (Test-Path $stderrFile) {
                Get-Content -Path $stderrFile -ErrorAction SilentlyContinue | ForEach-Object {
                    $text = [string]$_
                    if (-not [string]::IsNullOrWhiteSpace($text)) {
                        Write-Log $text "PIP"
                    }
                }
            }
        }
        finally {
            if (Test-Path $stdoutFile) {
                Remove-Item -Path $stdoutFile -Force -ErrorAction SilentlyContinue
            }
            if (Test-Path $stderrFile) {
                Remove-Item -Path $stderrFile -Force -ErrorAction SilentlyContinue
            }
        }

        if ($exitCode -eq 0) {
            return $true
        }

        Write-Log "$Description failed with exit code $exitCode." "WARN"
        if ($attempt -lt $Retries) {
            Start-Sleep -Seconds ([Math]::Min(12, $attempt * 3))
        }
    }

    if ($IgnoreFailure) {
        Write-Log "$Description failed after retries. Continuing without blocking setup." "WARN"
        return $false
    }

    throw "$Description failed after retries."
}

function Invoke-DownloadWithRetry([string]$Url, [string]$OutFile, [int]$Retries = 3) {
    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        try {
            if (Test-Path $OutFile) {
                Remove-Item -Path $OutFile -Force -ErrorAction SilentlyContinue
            }
            Invoke-WebRequest -Uri $Url -OutFile $OutFile
            if (-not (Test-Path $OutFile)) {
                throw "Download did not produce file: $OutFile"
            }
            return
        }
        catch {
            if ($attempt -ge $Retries) {
                throw
            }
            Write-Log "Download attempt $attempt/$Retries failed: $($_.Exception.Message)" "WARN"
            Start-Sleep -Seconds ([Math]::Min(10, $attempt * 2))
        }
    }
}

function Enable-EmbeddedPythonSite([string]$PythonRoot) {
    $pthFile = Get-ChildItem -Path $PythonRoot -Filter "python*._pth" -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $pthFile) {
        throw "Embedded Python _pth file was not found in: $PythonRoot"
    }

    $lines = @(Get-Content -Path $pthFile.FullName -Encoding ASCII)
    $updated = New-Object System.Collections.Generic.List[string]
    $hasImportSite = $false
    foreach ($line in $lines) {
        $trim = $line.Trim()
        if ($trim -eq "import site" -or $trim -eq "#import site" -or $trim -eq "# import site") {
            $updated.Add("import site")
            $hasImportSite = $true
        }
        else {
            $updated.Add($line)
        }
    }
    if (-not $hasImportSite) {
        $updated.Add("import site")
    }

    Set-Content -Path $pthFile.FullName -Value $updated -Encoding ASCII
}

function Test-PipAvailable([string]$PythonExe) {
    try {
        & $PythonExe -m pip --version *> $null
        return ($LASTEXITCODE -eq 0)
    }
    catch {
        return $false
    }
}

function Get-NvidiaSmiPath {
    $cmd = Get-Command "nvidia-smi.exe" -ErrorAction SilentlyContinue
    if ($cmd -and -not [string]::IsNullOrWhiteSpace($cmd.Source)) {
        return $cmd.Source
    }
    $cmd = Get-Command "nvidia-smi" -ErrorAction SilentlyContinue
    if ($cmd -and -not [string]::IsNullOrWhiteSpace($cmd.Source)) {
        return $cmd.Source
    }

    $candidates = @()

    if (-not [string]::IsNullOrWhiteSpace($env:SystemRoot)) {
        $candidates += Join-Path $env:SystemRoot "System32\nvidia-smi.exe"
        # Helpful when running a 32-bit host process on 64-bit Windows.
        $candidates += Join-Path $env:SystemRoot "Sysnative\nvidia-smi.exe"
    }
    if (-not [string]::IsNullOrWhiteSpace($env:WINDIR)) {
        $candidates += Join-Path $env:WINDIR "System32\nvidia-smi.exe"
        $candidates += Join-Path $env:WINDIR "Sysnative\nvidia-smi.exe"
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramW6432)) {
        $candidates += Join-Path $env:ProgramW6432 "NVIDIA Corporation\NVSMI\nvidia-smi.exe"
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $candidates += Join-Path $env:ProgramFiles "NVIDIA Corporation\NVSMI\nvidia-smi.exe"
    }
    if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles(x86)})) {
        $candidates += Join-Path ${env:ProgramFiles(x86)} "NVIDIA Corporation\NVSMI\nvidia-smi.exe"
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    return $null
}

function Get-NvidiaSmiCudaVersion([string]$NvidiaSmiPath) {
    if ([string]::IsNullOrWhiteSpace($NvidiaSmiPath) -or -not (Test-Path $NvidiaSmiPath)) {
        return ""
    }

    try {
        $output = & $NvidiaSmiPath 2>$null
    }
    catch {
        return ""
    }

    if ($LASTEXITCODE -ne 0) {
        return ""
    }

    foreach ($line in @($output)) {
        $text = [string]$line
        if ($text -match "CUDA Version:\s*([0-9]+\.[0-9]+)") {
            return $matches[1].Trim()
        }
    }

    return ""
}

function Get-NvidiaGpuProbe {
    $probe = [ordered]@{
        detected = $false
        source = ""
        nvidia_smi_path = ""
        gpu_name = ""
        driver_version = ""
        cuda_version = ""
    }

    $nvidiaSmi = Get-NvidiaSmiPath
    if (-not [string]::IsNullOrWhiteSpace($nvidiaSmi)) {
        $probe.nvidia_smi_path = $nvidiaSmi
        $probe.cuda_version = Get-NvidiaSmiCudaVersion -NvidiaSmiPath $nvidiaSmi
        try {
            $query = & $nvidiaSmi "--query-gpu=name,driver_version" "--format=csv,noheader,nounits" 2>$null |
                Select-Object -First 1
            if (($LASTEXITCODE -eq 0) -and (-not [string]::IsNullOrWhiteSpace($query))) {
                $first = [string]$query
                $parts = $first -split ",", 2
                $probe.gpu_name = $parts[0].Trim()
                if ($parts.Count -gt 1) {
                    $probe.driver_version = $parts[1].Trim()
                }
                $probe.detected = $true
                $probe.source = "nvidia-smi"
                return $probe
            }
            Write-Log "nvidia-smi is present but did not return any GPU rows." "WARN"
        }
        catch {
            Write-Log "nvidia-smi probe failed: $($_.Exception.Message)" "WARN"
        }
    }

    try {
        $controllers = Get-CimInstance Win32_VideoController -ErrorAction Stop |
            Where-Object { [string]$_.Name -match "NVIDIA" }
        $first = $controllers | Select-Object -First 1
        if ($null -ne $first) {
            $probe.detected = $true
            $probe.source = "win32_video_controller"
            $probe.gpu_name = [string]$first.Name
            $probe.driver_version = [string]$first.DriverVersion
            if ([string]::IsNullOrWhiteSpace($probe.nvidia_smi_path)) {
                Write-Log "Detected NVIDIA adapter via Win32_VideoController but nvidia-smi was not found." "WARN"
            }
            return $probe
        }
    }
    catch {
        Write-Log "Win32_VideoController probe failed: $($_.Exception.Message)" "WARN"
    }

    return $probe
}

function Get-CudaTorchIndexes([string]$DetectedCudaVersion) {
    $indexMap = [ordered]@{
        cu128 = "https://download.pytorch.org/whl/cu128"
        cu126 = "https://download.pytorch.org/whl/cu126"
        cu124 = "https://download.pytorch.org/whl/cu124"
        cu121 = "https://download.pytorch.org/whl/cu121"
    }

    $fallback = @($indexMap.cu128, $indexMap.cu126, $indexMap.cu124, $indexMap.cu121)
    if ([string]::IsNullOrWhiteSpace($DetectedCudaVersion)) {
        return $fallback
    }

    try {
        $version = [version]$DetectedCudaVersion
    }
    catch {
        Write-Log "Could not parse detected CUDA version '$DetectedCudaVersion'. Using default CUDA index fallback order." "WARN"
        return $fallback
    }

    if ($version -ge [version]"12.8") {
        return @($indexMap.cu128, $indexMap.cu126, $indexMap.cu121)
    }
    if ($version -ge [version]"12.6") {
        return @($indexMap.cu126, $indexMap.cu121)
    }
    if ($version -ge [version]"12.4") {
        return @($indexMap.cu124, $indexMap.cu121)
    }
    if ($version -ge [version]"12.1") {
        return @($indexMap.cu121)
    }

    Write-Log "Detected CUDA version $DetectedCudaVersion is below 12.1. Compatible CUDA wheel was not selected automatically." "WARN"
    return @()
}

function Test-TorchCudaReady([string]$PythonExe) {
    $diag = Get-RuntimeDiagnostics -PythonExe $PythonExe
    if ($null -eq $diag) {
        return $false
    }

    # CUDA torch provisioning should only depend on torch runtime availability.
    return ([bool]$diag.torch_ok -and [bool]$diag.torch_cuda_available)
}

function Test-TorchCpuReady([string]$PythonExe) {
    $diag = Get-RuntimeDiagnostics -PythonExe $PythonExe
    if ($null -eq $diag) {
        return $false
    }

    if (-not [bool]$diag.torch_ok) {
        return $false
    }

    if ([bool]$diag.torch_cuda_available) {
        return $false
    }

    $torchVersion = Get-InstalledPipPackageVersion -PythonExe $PythonExe -PackageName "torch"
    if (-not [string]::IsNullOrWhiteSpace($torchVersion) -and $torchVersion.Contains("+cu")) {
        return $false
    }

    return $true
}

function Get-RuntimeDiagnostics([string]$PythonExe) {
    $checkScriptPath = Join-Path ([IO.Path]::GetTempPath()) ("tm-runtime-diag-{0}.py" -f ([guid]::NewGuid().ToString("N")))
    $checkCode = @'
import json

info = {
    "torch_ok": False,
    "torch_version": "",
    "torch_cuda_available": False,
    "torch_cuda_build": "",
    "torch_error": "",
    "ctranslate2_ok": False,
    "ctranslate2_version": "",
    "ctranslate2_cuda_devices": None,
    "ctranslate2_error": "",
}

try:
    import torch
    info["torch_ok"] = True
    info["torch_version"] = str(getattr(torch, "__version__", "unknown"))
    info["torch_cuda_available"] = bool(torch.cuda.is_available())
    info["torch_cuda_build"] = str(getattr(getattr(torch, "version", None), "cuda", "") or "")
except Exception as exc:
    info["torch_error"] = f"{type(exc).__name__}: {exc}"

try:
    import ctranslate2
    info["ctranslate2_ok"] = True
    info["ctranslate2_version"] = str(getattr(ctranslate2, "__version__", ""))
    if hasattr(ctranslate2, "get_cuda_device_count"):
        info["ctranslate2_cuda_devices"] = int(ctranslate2.get_cuda_device_count())
except Exception as exc:
    info["ctranslate2_error"] = f"{type(exc).__name__}: {exc}"

print("TM_DIAG|" + json.dumps(info, ensure_ascii=False))
'@
    Set-Content -Path $checkScriptPath -Value $checkCode -Encoding UTF8
    $output = $null
    try {
        $output = & $PythonExe $checkScriptPath 2>&1
    }
    catch {
        return $null
    }
    finally {
        if (Test-Path $checkScriptPath) {
            Remove-Item -Path $checkScriptPath -Force -ErrorAction SilentlyContinue
        }
    }

    $lines = @($output)
    $jsonLine = $null
    for ($idx = $lines.Count - 1; $idx -ge 0; $idx--) {
        $candidate = [string]$lines[$idx]
        $trimmed = $candidate.Trim()
        if ($trimmed.StartsWith("TM_DIAG|")) {
            $jsonLine = $trimmed.Substring("TM_DIAG|".Length)
            break
        }
        if ($trimmed.StartsWith("{")) {
            $jsonLine = $trimmed
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($jsonLine)) {
        return $null
    }

    try {
        $diag = ($jsonLine | ConvertFrom-Json -ErrorAction Stop)
        return $diag
    }
    catch {
        return $null
    }
}

function Get-InstalledPipPackageVersion([string]$PythonExe, [string]$PackageName) {
    if ([string]::IsNullOrWhiteSpace($PackageName)) {
        return ""
    }

    try {
        $output = & $PythonExe "-m" "pip" "show" $PackageName 2>$null
    }
    catch {
        return ""
    }

    if ($LASTEXITCODE -ne 0) {
        return ""
    }

    foreach ($line in @($output)) {
        $text = [string]$line
        if ($text -match "^\s*Version:\s*(.+)$") {
            return $matches[1].Trim()
        }
    }

    return ""
}

function Write-RuntimeDiagnostics([string]$PythonExe, [string]$Prefix = "Runtime diagnostics") {
    $diag = Get-RuntimeDiagnostics -PythonExe $PythonExe
    if ($null -eq $diag) {
        Write-Log "${Prefix}: unavailable."
        return
    }

    $torchVersion = [string]$diag.torch_version
    $torchCudaAvailable = [bool]$diag.torch_cuda_available
    $torchCudaBuild = [string]$diag.torch_cuda_build
    $ct2Version = [string]$diag.ctranslate2_version
    $ct2CudaDevices = $diag.ctranslate2_cuda_devices

    Write-Log (
        "${Prefix}: torch=$torchVersion, cuda_available=$torchCudaAvailable, " +
        "cuda_build=$torchCudaBuild, ctranslate2=$ct2Version, ctranslate2_cuda_devices=$ct2CudaDevices"
    )
    if (-not [bool]$diag.torch_ok -and -not [string]::IsNullOrWhiteSpace([string]$diag.torch_error)) {
        Write-Log "$Prefix torch error: $([string]$diag.torch_error)" "WARN"
    }
    if (-not [bool]$diag.ctranslate2_ok -and -not [string]::IsNullOrWhiteSpace([string]$diag.ctranslate2_error)) {
        Write-Log "$Prefix ctranslate2 error: $([string]$diag.ctranslate2_error)" "WARN"
    }
}

function Ensure-CpuTorchRuntime([string]$PythonExe) {
    Write-RuntimeDiagnostics -PythonExe $PythonExe -Prefix "Pre-CPU install diagnostics"

    if (Test-TorchCpuReady -PythonExe $PythonExe) {
        Write-Log "CPU Torch runtime is already available."
        return
    }

    Write-Log "Installing CPU Torch runtime from official CPU wheel index."
    $pipCpuArgs = @(
        "-m",
        "pip",
        "install",
        "--disable-pip-version-check",
        "--no-warn-script-location",
        "--retries",
        "5",
        "--timeout",
        "120",
        "--upgrade",
        "--force-reinstall",
        "--index-url",
        "https://download.pytorch.org/whl/cpu",
        "--prefer-binary",
        "--no-deps",
        "torch",
        "torchaudio"
    )

    $ok = Invoke-RuntimePythonWithRetry `
        -PythonExe $PythonExe `
        -CommandArgs $pipCpuArgs `
        -Description "CPU torch install" `
        -Retries 2 `
        -TimeoutSeconds 5400 `
        -IgnoreFailure

    Write-RuntimeDiagnostics -PythonExe $PythonExe -Prefix "Post-CPU install diagnostics"
    if (-not $ok) {
        Write-Log "CPU Torch install finished with warnings. Current runtime diagnostics will decide final profile." "WARN"
    }
}

function Ensure-CudaTorchRuntime([string]$PythonExe) {
    $gpuProbe = Get-NvidiaGpuProbe
    if (-not [bool]$gpuProbe.detected) {
        Write-Log "NVIDIA GPU was not detected. Keeping CPU runtime."
        return
    }

    if (-not [string]::IsNullOrWhiteSpace([string]$gpuProbe.nvidia_smi_path)) {
        Write-Log "nvidia-smi path: $([string]$gpuProbe.nvidia_smi_path)"
    }
    if ([string]$gpuProbe.source -eq "nvidia-smi") {
        Write-Log "Detected NVIDIA GPU via nvidia-smi: $([string]$gpuProbe.gpu_name) (driver $([string]$gpuProbe.driver_version))"
    }
    else {
        Write-Log "Detected NVIDIA GPU via Win32_VideoController: $([string]$gpuProbe.gpu_name) (driver $([string]$gpuProbe.driver_version))"
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$gpuProbe.cuda_version)) {
        Write-Log "Detected NVIDIA CUDA runtime version via nvidia-smi: $([string]$gpuProbe.cuda_version)"
    }

    Write-RuntimeDiagnostics -PythonExe $PythonExe -Prefix "Pre-CUDA install diagnostics"

    if (Test-TorchCudaReady -PythonExe $PythonExe) {
        Write-Log "CUDA-enabled Torch is already available."
        return
    }

    $cudaIndexes = Get-CudaTorchIndexes -DetectedCudaVersion ([string]$gpuProbe.cuda_version)
    if ($cudaIndexes.Count -eq 0) {
        Write-Log "No suitable CUDA wheel index was selected from detected GPU/CUDA information. Keeping CPU runtime." "WARN"
        return
    }
    Write-Log ("CUDA index preference order: " + (($cudaIndexes | ForEach-Object { [string]$_ }) -join ", "))

    foreach ($index in $cudaIndexes) {
        Write-Log "Attempting CUDA Torch install from: $index"
        $pipCudaArgs = @(
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--no-warn-script-location",
            "--retries",
            "5",
            "--timeout",
            "120",
            "--upgrade",
            "--force-reinstall",
            "--index-url",
            $index,
            "--prefer-binary",
            "--no-deps",
            "torch",
            "torchaudio"
        )

        $ok = Invoke-RuntimePythonWithRetry `
            -PythonExe $PythonExe `
            -CommandArgs $pipCudaArgs `
            -Description "CUDA torch install" `
            -Retries 2 `
            -TimeoutSeconds 5400 `
            -IgnoreFailure

        Write-RuntimeDiagnostics -PythonExe $PythonExe -Prefix "Post-CUDA install diagnostics ($index)"

        if ($ok -and (Test-TorchCudaReady -PythonExe $PythonExe)) {
            Write-Log "CUDA-enabled Torch installation verified."
            return
        }

        if ($ok) {
            $torchVersion = Get-InstalledPipPackageVersion -PythonExe $PythonExe -PackageName "torch"
            if ($torchVersion.Contains("+cu")) {
                Write-Log "Detected installed CUDA torch package version '$torchVersion'. Skipping additional CUDA index retries."
                return
            }
        }

        Write-Log "CUDA Torch install attempt from $index did not produce usable CUDA runtime." "WARN"
    }

    Write-Log "Could not provision CUDA-enabled Torch automatically. Continuing with CPU runtime." "WARN"
}

function Ensure-EmbeddedPythonRuntime([string]$RuntimeRoot) {
    $pythonRoot = Join-Path $RuntimeRoot "python"
    $pythonExe = Join-Path $pythonRoot "python.exe"
    $versionMarker = Join-Path $RuntimeRoot "python-runtime.version"
    $zipPath = Join-Path $RuntimeRoot $script:EmbeddedPythonZipName
    $extractDir = Join-Path $RuntimeRoot "python-extract"
    $getPipPath = Join-Path $RuntimeRoot "get-pip.py"

    $runtimeReady = $false
    if ((Test-Path $pythonExe) -and (Test-Path $versionMarker)) {
        $installedVersion = (Get-Content -Path $versionMarker -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
        if ($installedVersion -eq $script:EmbeddedPythonVersion) {
            $runtimeReady = $true
        }
    }

    if (-not $runtimeReady) {
        Update-InstallerProgress -Percent 5 -Message "Downloading embedded Python runtime."
        Write-Log "Downloading embedded Python from: $($script:EmbeddedPythonUrl)"
        Invoke-DownloadWithRetry -Url $script:EmbeddedPythonUrl -OutFile $zipPath

        Update-InstallerProgress -Percent 10 -Message "Extracting embedded Python runtime."
        if (Test-Path $extractDir) {
            Remove-Item -Path $extractDir -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path $pythonRoot) {
            Remove-Item -Path $pythonRoot -Recurse -Force -ErrorAction SilentlyContinue
        }

        New-Item -Path $extractDir -ItemType Directory -Force | Out-Null
        Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

        New-Item -Path $pythonRoot -ItemType Directory -Force | Out-Null
        Copy-Item -Path (Join-Path $extractDir "*") -Destination $pythonRoot -Recurse -Force

        Update-InstallerProgress -Percent 14 -Message "Configuring embedded Python runtime."
        Enable-EmbeddedPythonSite -PythonRoot $pythonRoot
        New-Item -Path (Join-Path $pythonRoot "Lib\site-packages") -ItemType Directory -Force | Out-Null
        Set-Content -Path $versionMarker -Value $script:EmbeddedPythonVersion -Encoding ASCII
    }
    else {
        Update-InstallerProgress -Percent 8 -Message "Reusing embedded Python runtime."
        Write-Log "Embedded Python runtime already present: $pythonExe"
    }

    if (-not (Test-Path $pythonExe)) {
        throw "Embedded Python executable not found: $pythonExe"
    }

    Enable-EmbeddedPythonSite -PythonRoot $pythonRoot
    New-Item -Path (Join-Path $pythonRoot "Lib\site-packages") -ItemType Directory -Force | Out-Null

    if (-not (Test-PipAvailable -PythonExe $pythonExe)) {
        Update-InstallerProgress -Percent 18 -Message "Installing pip into embedded Python."
        Write-Log "Installing pip into embedded runtime..."
        Invoke-DownloadWithRetry -Url $script:GetPipUrl -OutFile $getPipPath
        $pipBootstrapArgs = @(
            $getPipPath,
            "--disable-pip-version-check",
            "--no-warn-script-location"
        )
        [void](Invoke-RuntimePythonWithRetry -PythonExe $pythonExe -CommandArgs $pipBootstrapArgs -Description "pip bootstrap install" -Retries 2 -TimeoutSeconds 900)
        if (-not (Test-PipAvailable -PythonExe $pythonExe)) {
            throw "pip bootstrap finished but pip is still unavailable."
        }
    }
    else {
        Write-Log "pip already present in embedded runtime."
    }

    if (Test-Path $zipPath) {
        Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $extractDir) {
        Remove-Item -Path $extractDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $getPipPath) {
        Remove-Item -Path $getPipPath -Force -ErrorAction SilentlyContinue
    }

    $script:RuntimePythonExe = $pythonExe
}

function Register-EmbeddedBackendPath([string]$RuntimePythonExe, [string]$BackendRoot) {
    if ([string]::IsNullOrWhiteSpace($RuntimePythonExe)) {
        return
    }
    if ([string]::IsNullOrWhiteSpace($BackendRoot)) {
        return
    }
    if (-not (Test-Path $RuntimePythonExe)) {
        return
    }
    if (-not (Test-Path $BackendRoot)) {
        Write-Log "Backend root does not exist for path registration: $BackendRoot" "WARN"
        return
    }

    $pythonRoot = Split-Path -Parent $RuntimePythonExe
    $sitePackages = Join-Path $pythonRoot "Lib\site-packages"
    $backendPthPath = Join-Path $sitePackages "transcribemate-backend.pth"

    try {
        New-Item -Path $sitePackages -ItemType Directory -Force | Out-Null
        $resolvedBackendRoot = (Resolve-Path $BackendRoot).Path
        Set-Content -Path $backendPthPath -Value $resolvedBackendRoot -Encoding ASCII
        Write-Log "Registered backend import path: $resolvedBackendRoot"
    }
    catch {
        Write-Log "Failed to register backend import path: $($_.Exception.Message)" "WARN"
    }
}

function Invoke-RuntimeSanityChecks(
    [string]$PythonExe,
    [string]$BackendRoot,
    [string]$FfmpegExePath,
    [switch]$RequireFfmpeg
) {
    $checkScriptPath = Join-Path ([IO.Path]::GetTempPath()) ("tm-runtime-sanity-{0}.py" -f ([guid]::NewGuid().ToString("N")))
    $checkScript = @'
import argparse
import importlib
import json
import os
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--backend-root", required=True)
parser.add_argument("--ffmpeg", default="")
parser.add_argument("--require-ffmpeg", action="store_true")
args = parser.parse_args()

backend_root = os.path.abspath(args.backend_root)
if backend_root and backend_root not in sys.path:
    sys.path.insert(0, backend_root)

results = []

def run_check(name, fn, required=True):
    try:
        fn()
        results.append({"name": name, "ok": True, "required": bool(required), "error": ""})
    except Exception as exc:
        results.append(
            {
                "name": name,
                "ok": False,
                "required": bool(required),
                "error": f"{type(exc).__name__}: {exc}",
            }
        )

run_check("backend_import", lambda: importlib.import_module("transcribemate.v2.backend.server"), required=True)
run_check("faster_whisper_import", lambda: importlib.import_module("faster_whisper"), required=True)
run_check("transformers_import", lambda: importlib.import_module("transformers"), required=True)
run_check("torch_import", lambda: importlib.import_module("torch"), required=True)
run_check("ctranslate2_import", lambda: importlib.import_module("ctranslate2"), required=True)
run_check("yt_dlp_import", lambda: importlib.import_module("yt_dlp"), required=True)
run_check("edge_tts_import", lambda: importlib.import_module("edge_tts"), required=True)

if args.require_ffmpeg:
    ffmpeg_path = os.path.abspath(args.ffmpeg) if args.ffmpeg else ""
    if not ffmpeg_path or not os.path.isfile(ffmpeg_path):
        results.append(
            {
                "name": "ffmpeg_presence",
                "ok": False,
                "required": True,
                "error": f"ffmpeg executable is missing: {ffmpeg_path}",
            }
        )
    else:
        results.append({"name": "ffmpeg_presence", "ok": True, "required": True, "error": ""})

required_failures = [item for item in results if item["required"] and not item["ok"]]
payload = {
    "ok": len(required_failures) == 0,
    "results": results,
}
print("TM_SANITY|" + json.dumps(payload, ensure_ascii=False))
sys.exit(0 if payload["ok"] else 1)
'@

    Set-Content -Path $checkScriptPath -Value $checkScript -Encoding UTF8
    try {
        $checkArgs = @(
            $checkScriptPath,
            "--backend-root",
            $BackendRoot
        )
        if ($RequireFfmpeg) {
            $checkArgs += @("--ffmpeg", $FfmpegExePath, "--require-ffmpeg")
        }

        [void](Invoke-RuntimePythonWithRetry `
            -PythonExe $PythonExe `
            -CommandArgs $checkArgs `
            -Description "runtime sanity check" `
            -Retries 1 `
            -TimeoutSeconds 1200)
    }
    finally {
        if (Test-Path $checkScriptPath) {
            Remove-Item -Path $checkScriptPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Assert-DirectoryHasContent([string]$DirectoryPath, [string]$Description) {
    $label = if ([string]::IsNullOrWhiteSpace($Description)) { "Directory" } else { $Description }
    if ([string]::IsNullOrWhiteSpace($DirectoryPath)) {
        throw "$label path is empty."
    }
    if (-not (Test-Path -Path $DirectoryPath -PathType Container)) {
        throw "$label is missing: $DirectoryPath"
    }
    try {
        $firstEntry = Get-ChildItem -Path $DirectoryPath -Force -ErrorAction Stop | Select-Object -First 1
        if ($null -eq $firstEntry) {
            throw "$label is empty: $DirectoryPath"
        }
    }
    catch {
        throw "$label is empty: $DirectoryPath"
    }
}

function Write-RuntimeReadyMarker(
    [string]$AppDataDir,
    [string]$RuntimeRoot,
    [string]$BackendRoot,
    [string]$PythonExe,
    [string]$AppVersion,
    [string]$TorchProfile,
    [string]$WhisperModelPath,
    [string]$TranslationModelPath,
    [string]$FfmpegPath,
    [string]$FfprobePath
) {
    if ([string]::IsNullOrWhiteSpace($RuntimeRoot)) {
        return
    }

    try {
        $requiredPaths = @(
            "runtime/python/python.exe",
            "assets/ffmpeg.exe",
            "assets/ffprobe.exe",
            "cache/whisper/models/large-v3",
            "cache/huggingface/hub/models--Helsinki-NLP--opus-mt-en-cs"
        )
        $normalizedTorchProfile = ([string]$TorchProfile).Trim().ToLowerInvariant()
        if ($normalizedTorchProfile -ne "cuda" -and $normalizedTorchProfile -ne "cpu") {
            $normalizedTorchProfile = "cpu"
        }

        $markerPath = Join-Path $RuntimeRoot "runtime-ready.json"
        $payload = [ordered]@{
            schema_version = 2
            bootstrap_complete = $true
            ready_utc = (Get-Date).ToUniversalTime().ToString("o")
            backend_root = $BackendRoot
            python_exe = $PythonExe
            app_version = $AppVersion
            torch_profile = $normalizedTorchProfile
            components = [ordered]@{
                app_data_dir = $AppDataDir
                required_paths = $requiredPaths
                ffmpeg = $FfmpegPath
                ffprobe = $FfprobePath
            }
            models = [ordered]@{
                default_pack = @("large-v3", "Helsinki-NLP/opus-mt-en-cs")
                whisper_model_path = $WhisperModelPath
                translation_model_path = $TranslationModelPath
            }
        }
        $json = $payload | ConvertTo-Json -Depth 4
        Set-Content -Path $markerPath -Value $json -Encoding UTF8
        Write-Log "Runtime ready marker updated: $markerPath"
    }
    catch {
        Write-Log "Failed to write runtime ready marker: $($_.Exception.Message)" "WARN"
    }
}

try {
    if ([string]::IsNullOrWhiteSpace($BackendRoot)) {
        $BackendRoot = $PSScriptRoot
    }
    $BackendRoot = (Resolve-Path $BackendRoot).Path

    $requirementsPath = Join-Path $BackendRoot "requirements.txt"
    $diarizationRequirementsPath = Join-Path $BackendRoot "requirements-diarization.txt"
    $versionFilePath = Join-Path $BackendRoot "version.txt"

    $localAppData = [Environment]::GetFolderPath("LocalApplicationData")
    if ([string]::IsNullOrWhiteSpace($localAppData)) {
        $localAppData = Join-Path $HOME "AppData\Local"
    }
    $appDataDir = Join-Path $localAppData "TranscribeMate"
    $runtimeRoot = Join-Path $appDataDir "runtime"
    $assetsDir = Join-Path $appDataDir "assets"
    $whisperModelDir = Join-Path $appDataDir "cache\whisper\models\large-v3"
    $translationModelDir = Join-Path $appDataDir "cache\huggingface\hub\models--Helsinki-NLP--opus-mt-en-cs"
    $ffmpegValidationPath = Join-Path $assetsDir "ffmpeg.exe"
    $ffprobeValidationPath = Join-Path $assetsDir "ffprobe.exe"
    $torchProfile = "cpu"
    if (-not [string]::IsNullOrWhiteSpace($LogFile)) {
        $script:LogFilePath = $LogFile
    }
    else {
        $script:LogFilePath = Join-Path $appDataDir "runtime-bootstrap.log"
    }
    $script:ProgressFilePath = $null

    if (-not [string]::IsNullOrWhiteSpace($ProgressFile)) {
        try {
            $progressDir = Split-Path -Path $ProgressFile -Parent
            if (-not [string]::IsNullOrWhiteSpace($progressDir)) {
                New-Item -Path $progressDir -ItemType Directory -Force | Out-Null
            }
            $script:ProgressFilePath = $ProgressFile
        }
        catch {
            Write-Log "Progress file is not writable: $ProgressFile" "WARN"
        }
    }

    New-Item -Path $appDataDir -ItemType Directory -Force | Out-Null
    New-Item -Path $runtimeRoot -ItemType Directory -Force | Out-Null
    New-Item -Path $assetsDir -ItemType Directory -Force | Out-Null

    Update-InstallerProgress -Percent 1 -Message "Initializing runtime bootstrap."
    Write-Log "Backend root: $BackendRoot"
    Write-Log "Runtime root: $runtimeRoot"

    $script:RuntimePythonExe = $null
    Ensure-EmbeddedPythonRuntime -RuntimeRoot $runtimeRoot
    $runtimePython = $script:RuntimePythonExe
    if ([string]::IsNullOrWhiteSpace($runtimePython)) {
        throw "Embedded Python runtime path was not resolved."
    }
    if (-not (Test-Path $runtimePython)) {
        throw "Embedded Python executable not found after runtime setup: $runtimePython"
    }
    $runtimePythonRoot = Split-Path -Parent $runtimePython

    Register-EmbeddedBackendPath -RuntimePythonExe $runtimePython -BackendRoot $BackendRoot

    $env:PYTHONUTF8 = "1"
    $env:PYTHONIOENCODING = "utf-8"
    $env:PATH = "$runtimePythonRoot;$($runtimePythonRoot)\Scripts;$($env:PATH)"
    if ([string]::IsNullOrWhiteSpace($env:PYTHONPATH)) {
        $env:PYTHONPATH = $BackendRoot
    }
    else {
        $env:PYTHONPATH = "$BackendRoot;$($env:PYTHONPATH)"
    }
    $appVersion = ""
    if (Test-Path $versionFilePath) {
        $appVersion = (Get-Content $versionFilePath | Select-Object -First 1).Trim()
        if (-not [string]::IsNullOrWhiteSpace($appVersion)) {
            $env:TRANSCRIBEMATE_VERSION = $appVersion
        }
    }

    function Invoke-RuntimePython([string[]]$CommandArgs) {
        & $runtimePython @CommandArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed: $runtimePython $($CommandArgs -join ' ')"
        }
    }

    Update-InstallerProgress -Percent 20 -Message "Upgrading pip, setuptools and wheel."
    $pipUpgradeArgs = @()
    $pipUpgradeArgs += @(
        "-m",
        "pip",
        "install",
        "--disable-pip-version-check",
        "--no-warn-script-location",
        "--retries",
        "5",
        "--timeout",
        "120",
        "--upgrade",
        "pip",
        "setuptools",
        "wheel"
    )
    [void](Invoke-RuntimePythonWithRetry -PythonExe $runtimePython -CommandArgs $pipUpgradeArgs -Description "pip toolchain upgrade" -Retries 2 -TimeoutSeconds 900 -IgnoreFailure)

    if (-not (Test-Path $requirementsPath)) {
        throw "Missing requirements.txt in backend root: $requirementsPath"
    }

    Update-InstallerProgress -Percent 40 -Message "Installing base Python dependencies."
    $pipReqArgs = @()
    $pipReqArgs += @(
        "-m",
        "pip",
        "install",
        "--disable-pip-version-check",
        "--no-warn-script-location",
        "--retries",
        "5",
        "--timeout",
        "120",
        "--prefer-binary",
        "-r",
        $requirementsPath
    )
    [void](Invoke-RuntimePythonWithRetry -PythonExe $runtimePython -CommandArgs $pipReqArgs -Description "base dependency install" -Retries 3 -TimeoutSeconds 5400)

    Write-RuntimeDiagnostics -PythonExe $runtimePython -Prefix "Diagnostics after base dependency install"

    if ($InstallDiarization -and (Test-Path $diarizationRequirementsPath)) {
        Update-InstallerProgress -Percent 60 -Message "Installing speaker diarization dependencies."
        $pipDiarizationArgs = @()
        $pipDiarizationArgs += @(
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--no-warn-script-location",
            "--retries",
            "5",
            "--timeout",
            "120",
            "--prefer-binary",
            "-r",
            $diarizationRequirementsPath
        )
        [void](Invoke-RuntimePythonWithRetry -PythonExe $runtimePython -CommandArgs $pipDiarizationArgs -Description "diarization dependency install" -Retries 3 -TimeoutSeconds 5400)
    }

    Update-InstallerProgress -Percent 68 -Message "Installing optional Hugging Face transfer acceleration."
    $pipHfXetArgs = @(
        "-m",
        "pip",
        "install",
        "--disable-pip-version-check",
        "--no-warn-script-location",
        "--retries",
        "5",
        "--timeout",
        "120",
        "--prefer-binary",
        "hf_xet"
    )
    [void](Invoke-RuntimePythonWithRetry -PythonExe $runtimePython -CommandArgs $pipHfXetArgs -Description "hf_xet install" -Retries 2 -TimeoutSeconds 1200 -IgnoreFailure)

    Update-InstallerProgress -Percent 70 -Message "Detecting NVIDIA GPU and selecting Torch runtime profile."
    $gpuProbe = Get-NvidiaGpuProbe
    if ([bool]$gpuProbe.detected) {
        Update-InstallerProgress -Percent 72 -Message "NVIDIA GPU detected. Attempting CUDA Torch runtime."
        Ensure-CudaTorchRuntime -PythonExe $runtimePython
        if (Test-TorchCudaReady -PythonExe $runtimePython) {
            $torchProfile = "cuda"
            Write-Log "Torch runtime profile resolved to CUDA."
        }
        else {
            Update-InstallerProgress -Percent 74 -Message "CUDA runtime unavailable. Falling back to CPU Torch."
            Ensure-CpuTorchRuntime -PythonExe $runtimePython
            $torchProfile = "cpu"
            Write-Log "Torch runtime profile resolved to CPU (CUDA fallback failed)."
        }
    }
    else {
        Update-InstallerProgress -Percent 72 -Message "NVIDIA GPU not detected. Installing CPU Torch runtime."
        Ensure-CpuTorchRuntime -PythonExe $runtimePython
        $torchProfile = "cpu"
        Write-Log "Torch runtime profile resolved to CPU (no NVIDIA GPU detected)."
    }

    if ($WithModels) {
        Update-InstallerProgress -Percent 75 -Message "Downloading default AI models."
        Push-Location $BackendRoot
        try {
            $prefetchScriptPath = Join-Path $BackendRoot "prefetch-models.py"
            $prefetchScript = @'
import os
import sys

backend_root = os.path.dirname(os.path.abspath(__file__))
if backend_root not in sys.path:
    sys.path.insert(0, backend_root)

from transcribemate.core.models import configure_model_environment
from transcribemate.core.models import whisper_cache_dir, huggingface_cache_dir
from faster_whisper import WhisperModel
from faster_whisper.utils import download_model as download_whisper_model
from huggingface_hub import snapshot_download
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
from pathlib import Path

configure_model_environment()
cache_root = Path(whisper_cache_dir())
target_model_dir = cache_root / "models" / "large-v3"
target_model_dir.mkdir(parents=True, exist_ok=True)
download_whisper_model(
    "large-v3",
    output_dir=str(target_model_dir),
    local_files_only=False,
    cache_dir=str(cache_root),
)
WhisperModel(str(target_model_dir), device="cpu", compute_type="int8", local_files_only=True)

model_name = "Helsinki-NLP/opus-mt-en-cs"
hf_cache_root = Path(huggingface_cache_dir())
translation_model_dir = hf_cache_root / "hub" / "models--Helsinki-NLP--opus-mt-en-cs"
translation_model_dir.mkdir(parents=True, exist_ok=True)
snapshot_download(
    repo_id=model_name,
    local_dir=str(translation_model_dir),
    local_files_only=False,
)
AutoTokenizer.from_pretrained(str(translation_model_dir), local_files_only=True)
try:
    AutoModelForSeq2SeqLM.from_pretrained(
        str(translation_model_dir),
        local_files_only=True,
        use_safetensors=True
    )
except Exception:
    AutoModelForSeq2SeqLM.from_pretrained(str(translation_model_dir), local_files_only=True)

print("Model prefetch complete.")
'@
            Set-Content -Path $prefetchScriptPath -Value $prefetchScript -Encoding UTF8
            Invoke-RuntimePython @($prefetchScriptPath)
            Assert-DirectoryHasContent -DirectoryPath $whisperModelDir -Description "Whisper model directory"
            Assert-DirectoryHasContent -DirectoryPath $translationModelDir -Description "Translation model directory"
            Write-Log "Model prefetch validation passed."
        }
        finally {
            if (-not [string]::IsNullOrWhiteSpace($prefetchScriptPath) -and (Test-Path $prefetchScriptPath)) {
                Remove-Item -Path $prefetchScriptPath -Force -ErrorAction SilentlyContinue
            }
            Pop-Location
        }
    }

    if ($DownloadFfmpeg) {
        Update-InstallerProgress -Percent 90 -Message "Preparing FFmpeg tools."
        $ffmpegExe = Join-Path $assetsDir "ffmpeg.exe"
        $ffprobeExe = Join-Path $assetsDir "ffprobe.exe"
        if ((Test-Path $ffmpegExe) -and (Test-Path $ffprobeExe)) {
            Write-Log "FFmpeg assets already present."
        }
        else {
            Write-Log "Downloading FFmpeg essentials package..."
            $ffmpegZip = Join-Path $runtimeRoot "ffmpeg-release-essentials.zip"
            $ffmpegExtractDir = Join-Path $runtimeRoot "ffmpeg-extract"
            $ffmpegUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
            try {
                if (Test-Path $ffmpegExtractDir) {
                    Remove-Item -Path $ffmpegExtractDir -Recurse -Force
                }
                Invoke-DownloadWithRetry -Url $ffmpegUrl -OutFile $ffmpegZip
                Expand-Archive -Path $ffmpegZip -DestinationPath $ffmpegExtractDir -Force

                $ffmpegFound = Get-ChildItem -Path $ffmpegExtractDir -Recurse -File -Filter "ffmpeg.exe" | Select-Object -First 1
                $ffprobeFound = Get-ChildItem -Path $ffmpegExtractDir -Recurse -File -Filter "ffprobe.exe" | Select-Object -First 1
                if (-not $ffmpegFound -or -not $ffprobeFound) {
                    throw "ffmpeg.exe or ffprobe.exe was not found in downloaded archive."
                }

                Copy-Item -Path $ffmpegFound.FullName -Destination $ffmpegExe -Force
                Copy-Item -Path $ffprobeFound.FullName -Destination $ffprobeExe -Force
                Write-Log "FFmpeg assets installed into: $assetsDir"
            }
            catch {
                Write-Log "FFmpeg download failed: $($_.Exception.Message)" "WARN"
            }
            finally {
                if (Test-Path $ffmpegZip) {
                    Remove-Item -Path $ffmpegZip -Force -ErrorAction SilentlyContinue
                }
                if (Test-Path $ffmpegExtractDir) {
                    Remove-Item -Path $ffmpegExtractDir -Recurse -Force -ErrorAction SilentlyContinue
                }
            }
        }
    }

    Update-InstallerProgress -Percent 96 -Message "Running runtime validation checks."
    Invoke-RuntimeSanityChecks `
        -PythonExe $runtimePython `
        -BackendRoot $BackendRoot `
        -FfmpegExePath $ffmpegValidationPath `
        -RequireFfmpeg:$DownloadFfmpeg

    if (Test-TorchCudaReady -PythonExe $runtimePython) {
        $torchProfile = "cuda"
    }
    elseif (Test-TorchCpuReady -PythonExe $runtimePython) {
        $torchProfile = "cpu"
    }

    Write-RuntimeReadyMarker `
        -AppDataDir $appDataDir `
        -RuntimeRoot $runtimeRoot `
        -BackendRoot $BackendRoot `
        -PythonExe $runtimePython `
        -AppVersion $appVersion `
        -TorchProfile $torchProfile `
        -WhisperModelPath $whisperModelDir `
        -TranslationModelPath $translationModelDir `
        -FfmpegPath $ffmpegValidationPath `
        -FfprobePath $ffprobeValidationPath

    Update-InstallerProgress -Percent 100 -Message "Runtime bootstrap completed successfully."
}
catch {
    $message = $_.Exception.Message
    if (-not [string]::IsNullOrWhiteSpace($message)) {
        Write-Log "Bootstrap failed: $message" "ERROR"
    }
    $stack = $_.ScriptStackTrace
    if (-not [string]::IsNullOrWhiteSpace($stack)) {
        foreach ($line in ($stack -split "`r?`n")) {
            $traceLine = [string]$line
            if (-not [string]::IsNullOrWhiteSpace($traceLine)) {
                Write-Log $traceLine "TRACE"
            }
        }
    }
    throw
}
