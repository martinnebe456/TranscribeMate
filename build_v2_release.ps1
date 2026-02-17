param(
    [switch]$SkipFrontendBuild,
    [switch]$SkipInstaller,
    [switch]$RequireInstaller,
    [string]$AppVersion,
    [string]$CodeSignThumbprint,
    [string]$CodeSignTimestampUrl = "http://timestamp.digicert.com",
    [switch]$RequireCodeSigning
)

$ErrorActionPreference = "Stop"

function Test-JavaHome([string]$HomePath) {
    if ([string]::IsNullOrWhiteSpace($HomePath)) {
        return $false
    }
    $javaExe = Join-Path $HomePath "bin\java.exe"
    $javacExe = Join-Path $HomePath "bin\javac.exe"
    $jpackageExe = Join-Path $HomePath "bin\jpackage.exe"
    return (Test-Path $javaExe) -and (Test-Path $javacExe) -and (Test-Path $jpackageExe)
}

function Resolve-JavaHome {
    if (Test-JavaHome $env:JAVA_HOME) {
        return $env:JAVA_HOME
    }

    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        return $null
    }

    $javaHome = $null
    try {
        $settings = & $javaCmd.Source -XshowSettings:properties -version 2>&1
        $line = $settings | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if ($line) {
            $javaHome = ($line -split "=", 2)[1].Trim()
        }
    }
    catch {
        $javaHome = $null
    }

    if ($javaHome) {
        if (Test-JavaHome $javaHome) {
            return $javaHome
        }

        $parent = Split-Path -Parent $javaHome
        if (Test-JavaHome $parent) {
            return $parent
        }
    }

    $candidate = Split-Path -Parent (Split-Path -Parent $javaCmd.Source)
    if (Test-JavaHome $candidate) {
        return $candidate
    }

    return $null
}

function Resolve-Iscc {
    $cmd = Get-Command iscc -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $candidates = @(
        (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
        (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe")
    )

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    return $null
}

function Normalize-Thumbprint([string]$Thumbprint) {
    if ([string]::IsNullOrWhiteSpace($Thumbprint)) {
        return ""
    }
    return ($Thumbprint -replace "[^0-9A-Fa-f]", "").ToUpperInvariant()
}

function Resolve-SignTool {
    $cmd = Get-Command signtool -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $kitRoots = @(
        (Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"),
        (Join-Path $env:ProgramFiles "Windows Kits\10\bin")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path $_) }

    foreach ($root in $kitRoots) {
        $candidates = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending
        foreach ($dir in $candidates) {
            $x64 = Join-Path $dir.FullName "x64\signtool.exe"
            if (Test-Path $x64) {
                return $x64
            }
            $x86 = Join-Path $dir.FullName "x86\signtool.exe"
            if (Test-Path $x86) {
                return $x86
            }
        }
    }

    return $null
}

function Resolve-DefaultCodeSignThumbprint {
    $stores = @(
        "Cert:\CurrentUser\My",
        "Cert:\LocalMachine\My"
    )

    foreach ($store in $stores) {
        if (-not (Test-Path $store)) {
            continue
        }

        $candidate = Get-ChildItem -Path $store -ErrorAction SilentlyContinue |
            Where-Object {
                $_.HasPrivateKey -and
                $_.NotAfter -gt (Get-Date) -and
                ($_.EnhancedKeyUsageList | Where-Object { $_.ObjectId -eq "1.3.6.1.5.5.7.3.3" })
            } |
            Sort-Object NotAfter -Descending |
            Select-Object -First 1

        if ($candidate) {
            return Normalize-Thumbprint $candidate.Thumbprint
        }
    }

    return ""
}

function Sign-File([string]$SignTool, [string]$Thumbprint, [string]$TimestampUrl, [string]$FilePath) {
    if (-not (Test-Path $FilePath)) {
        throw "[TM] Cannot sign missing file: $FilePath"
    }

    Write-Host "[TM] Signing: $FilePath"
    $args = @(
        "sign",
        "/fd", "SHA256",
        "/td", "SHA256",
        "/sha1", $Thumbprint,
        "/v"
    )
    if (-not [string]::IsNullOrWhiteSpace($TimestampUrl)) {
        $args += @("/tr", $TimestampUrl)
    }
    $args += @($FilePath)

    & $SignTool @args
    if ($LASTEXITCODE -ne 0) {
        throw "[TM] signtool failed for: $FilePath (exit $LASTEXITCODE)"
    }
}

function Unblock-PathRecursive([string]$TargetPath, [string]$Description) {
    if (-not (Test-Path $TargetPath)) {
        return
    }

    try {
        $item = Get-Item -Path $TargetPath -ErrorAction Stop
        if ($item.PSIsContainer) {
            Write-Host "[TM] Removing MOTW from $Description..."
            Get-ChildItem -Path $TargetPath -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
                try {
                    Unblock-File -Path $_.FullName -ErrorAction Stop
                }
                catch {
                    # Best effort only.
                }
            }
        }
        else {
            Write-Host "[TM] Removing MOTW from $Description..."
            Unblock-File -Path $TargetPath -ErrorAction SilentlyContinue
        }
    }
    catch {
        Write-Warning "[TM] Failed to unblock ${Description}: $($_.Exception.Message)"
    }
}

function Remove-PathWithRetry(
    [string]$TargetPath,
    [string]$Description,
    [int]$MaxAttempts = 10,
    [int]$DelayMilliseconds = 750
) {
    if (-not (Test-Path -LiteralPath $TargetPath)) {
        return
    }

    $lastError = $null
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            Remove-Item -LiteralPath $TargetPath -Recurse -Force -ErrorAction Stop
            return
        }
        catch {
            $lastError = $_.Exception.Message
            if ($attempt -lt $MaxAttempts) {
                Write-Warning ("[TM] Could not remove {0} (attempt {1}/{2}): {3}" -f $Description, $attempt, $MaxAttempts, $lastError)
                Start-Sleep -Milliseconds $DelayMilliseconds
                continue
            }
        }
    }

    $hint = @"
[TM] Failed to remove ${Description}: $TargetPath
Reason: $lastError

Close all running TranscribeMate app windows/processes and close any Explorer window opened inside this folder.
Then run build_v2_installer.ps1 again.
"@
    throw $hint
}

function Resolve-AppVersion([string]$Requested, [string]$RootPath) {
    $versionFile = Join-Path $RootPath "version.txt"
    $previous = ""
    if (Test-Path $versionFile) {
        $raw = (Get-Content $versionFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if (-not [string]::IsNullOrWhiteSpace($raw)) {
            $previous = $raw.Trim()
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $resolved = $Requested.Trim()
        Set-Content -Path $versionFile -Value $resolved -Encoding ASCII
        Write-Host "[TM] version.txt set from -AppVersion: $resolved"
        return $resolved
    }

    $todayPrefix = (Get-Date).ToString("yy.MM.dd", [System.Globalization.CultureInfo]::InvariantCulture)
    $counterWidth = 3
    $nextCounter = 1

    if ($previous -match "^(?<date>\d{2}\.\d{2}\.\d{2})\.(?<build>\d+)$") {
        $counterWidth = [Math]::Max(3, $matches["build"].Length)
        if ($matches["date"] -eq $todayPrefix) {
            try {
                $parsed = [int]$matches["build"]
                if ($parsed -ge 0) {
                    $nextCounter = $parsed + 1
                }
            }
            catch {
                $nextCounter = 1
            }
        }
    }

    $counterFormat = "D{0}" -f $counterWidth
    $resolved = "{0}.{1}" -f $todayPrefix, $nextCounter.ToString($counterFormat, [System.Globalization.CultureInfo]::InvariantCulture)
    Set-Content -Path $versionFile -Value $resolved -Encoding ASCII

    if ([string]::IsNullOrWhiteSpace($previous)) {
        Write-Host "[TM] version.txt initialized: $resolved"
    }
    else {
        Write-Host "[TM] version.txt auto-increment: $previous -> $resolved"
    }

    return $resolved
}

function Assert-PathExists([string]$Path, [string]$Description) {
    if (-not (Test-Path $Path)) {
        throw ("[TM] Missing {0}: {1}" -f $Description, $Path)
    }
}

function Validate-BundledBackend([string]$BundledBackendDir) {
    Assert-PathExists -Path $BundledBackendDir -Description "bundled backend directory"

    $required = @(
        @{ Path = (Join-Path $BundledBackendDir "transcribemate\v2\backend\server.py"); Description = "backend server module" },
        @{ Path = (Join-Path $BundledBackendDir "transcribemate\v2\backend\service.py"); Description = "backend service module" },
        @{ Path = (Join-Path $BundledBackendDir "transcribemate\pipeline\transcribe.py"); Description = "transcription pipeline module" },
        @{ Path = (Join-Path $BundledBackendDir "requirements.txt"); Description = "runtime requirements file" },
        @{ Path = (Join-Path $BundledBackendDir "bootstrap_runtime.ps1"); Description = "runtime bootstrap script" },
        @{ Path = (Join-Path $BundledBackendDir "version.txt"); Description = "version file" }
    )

    foreach ($entry in $required) {
        Assert-PathExists -Path $entry.Path -Description $entry.Description
    }
}

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontendDir = Join-Path $root "javafx-client"
$distDir = Join-Path $root "dist"
$appImageDir = Join-Path $distDir "TranscribeMate"
$issFile = Join-Path $root "installer\TranscribeMate.iss"

if (-not (Test-Path $frontendDir)) {
    throw "[TM] Missing javafx-client directory."
}
if (-not (Test-Path $issFile)) {
    throw "[TM] Missing installer script: $issFile"
}

$resolvedJavaHome = Resolve-JavaHome
if (-not $resolvedJavaHome) {
    throw @"
[TM] Java JDK (21+) with jpackage was not found.
Install JDK and run script again.

Example (winget):
  winget install EclipseAdoptium.Temurin.21.JDK
"@
}
$env:JAVA_HOME = $resolvedJavaHome
$jpackageExe = Join-Path $env:JAVA_HOME "bin\jpackage.exe"

$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCmd) {
    throw "[TM] Maven (mvn) not found in PATH."
}

$appVersion = Resolve-AppVersion -Requested $AppVersion -RootPath $root
Write-Host "[TM] JAVA_HOME=$($env:JAVA_HOME)"
Write-Host "[TM] Version=$appVersion"

if (-not $SkipFrontendBuild) {
    Write-Host "[TM] Building frontend JAR and runtime dependencies..."
    Push-Location $frontendDir
    try {
        & $mvnCmd.Source "-DskipTests" "clean" "package" "dependency:copy-dependencies" "-DincludeScope=runtime" "-DoutputDirectory=target\dependency"
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

$targetDir = Join-Path $frontendDir "target"
$jar = Get-ChildItem -Path $targetDir -Filter "*.jar" -File |
    Where-Object { $_.Name -notmatch "(sources|javadoc|original)" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "[TM] No frontend runnable JAR found in $targetDir"
}

$depDir = Join-Path $targetDir "dependency"
if (-not (Test-Path $depDir)) {
    throw "[TM] Dependency folder missing: $depDir (run without -SkipFrontendBuild first)."
}

$inputDir = Join-Path $targetDir "jpackage-input"
if (Test-Path $inputDir) {
    Remove-PathWithRetry -TargetPath $inputDir -Description "jpackage input directory"
}
New-Item -Path $inputDir -ItemType Directory | Out-Null

Copy-Item -Path $jar.FullName -Destination (Join-Path $inputDir $jar.Name) -Force
Copy-Item -Path (Join-Path $depDir "*.jar") -Destination $inputDir -Force

if (-not (Test-Path $distDir)) {
    New-Item -Path $distDir -ItemType Directory | Out-Null
}
if (Test-Path $appImageDir) {
    Remove-PathWithRetry -TargetPath $appImageDir -Description "existing app image directory"
}

Write-Host "[TM] Creating app image via jpackage..."
$jpackageArgs = @(
    "--type", "app-image",
    "--name", "TranscribeMate",
    "--dest", $distDir,
    "--input", $inputDir,
    "--main-jar", $jar.Name,
    "--main-class", "com.transcribemate.v2.fx.Launcher",
    "--app-version", $appVersion,
    "--vendor", "Martin Nebehay"
)

$iconFile = Join-Path $root "icon.ico"
if (Test-Path $iconFile) {
    $jpackageArgs += @("--icon", $iconFile)
}

& $jpackageExe @jpackageArgs
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

if (-not (Test-Path $appImageDir)) {
    throw "[TM] jpackage finished but app image not found: $appImageDir"
}

$normalizedThumbprint = Normalize-Thumbprint $CodeSignThumbprint
$envThumbprint = Normalize-Thumbprint $env:TM_CODESIGN_THUMBPRINT
if ([string]::IsNullOrWhiteSpace($normalizedThumbprint) -and -not [string]::IsNullOrWhiteSpace($envThumbprint)) {
    $normalizedThumbprint = $envThumbprint
    Write-Host "[TM] Using code signing certificate from TM_CODESIGN_THUMBPRINT."
}
if ([string]::IsNullOrWhiteSpace($normalizedThumbprint)) {
    $autoThumbprint = Resolve-DefaultCodeSignThumbprint
    if (-not [string]::IsNullOrWhiteSpace($autoThumbprint)) {
        $normalizedThumbprint = $autoThumbprint
        Write-Host "[TM] Using auto-detected code signing certificate thumbprint: $normalizedThumbprint"
    }
}
$codeSigningEnabled = -not [string]::IsNullOrWhiteSpace($normalizedThumbprint)
$signtool = $null
if ($codeSigningEnabled -or $RequireCodeSigning) {
    $signtool = Resolve-SignTool
    if (-not $signtool) {
        $message = "[TM] SignTool (signtool.exe) was not found. Install Windows SDK / Signing Tools."
        if ($RequireCodeSigning) {
            throw $message
        }
        Write-Warning $message
        $codeSigningEnabled = $false
    }
}
if ($RequireCodeSigning -and -not $codeSigningEnabled) {
    throw "[TM] Code signing is required, but no valid -CodeSignThumbprint was provided."
}
if (-not $codeSigningEnabled) {
    Write-Warning "[TM] Build is unsigned. Windows SmartScreen/Smart App Control may block install or app launch."
}

# Bundle Python backend source inside the Java app image.
$bundledBackendDir = Join-Path $appImageDir "app\backend"
if (Test-Path $bundledBackendDir) {
    Remove-PathWithRetry -TargetPath $bundledBackendDir -Description "bundled backend directory"
}
New-Item -Path $bundledBackendDir -ItemType Directory | Out-Null

Copy-Item -Path (Join-Path $root "transcribemate") -Destination (Join-Path $bundledBackendDir "transcribemate") -Recurse -Force
if (Test-Path (Join-Path $root "version.txt")) {
    Copy-Item -Path (Join-Path $root "version.txt") -Destination (Join-Path $bundledBackendDir "version.txt") -Force
}
Copy-Item -Path (Join-Path $root "requirements.txt") -Destination (Join-Path $bundledBackendDir "requirements.txt") -Force
if (Test-Path (Join-Path $root "requirements-diarization.txt")) {
    Copy-Item -Path (Join-Path $root "requirements-diarization.txt") -Destination (Join-Path $bundledBackendDir "requirements-diarization.txt") -Force
}
$runtimeBootstrapScript = Join-Path $root "scripts\bootstrap_runtime.ps1"
if (Test-Path $runtimeBootstrapScript) {
    Copy-Item -Path $runtimeBootstrapScript -Destination (Join-Path $bundledBackendDir "bootstrap_runtime.ps1") -Force
} else {
    throw "[TM] Runtime bootstrap script not found: $runtimeBootstrapScript"
}

Get-ChildItem -Path $bundledBackendDir -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem -Path $bundledBackendDir -Recurse -File -Include "*.pyc", "*.pyo" | Remove-Item -Force -ErrorAction SilentlyContinue

Validate-BundledBackend -BundledBackendDir $bundledBackendDir

Write-Host "[TM] App image ready: $appImageDir"

if ($codeSigningEnabled) {
    $appImageExe = Join-Path $appImageDir "TranscribeMate.exe"
    Sign-File -SignTool $signtool -Thumbprint $normalizedThumbprint -TimestampUrl $CodeSignTimestampUrl -FilePath $appImageExe
}
Unblock-PathRecursive -TargetPath $appImageDir -Description "app image files"

if (-not $SkipInstaller) {
    $iscc = Resolve-Iscc
    if (-not $iscc) {
        $message = @"
[TM] Inno Setup compiler (ISCC.exe) was not found.
Installer step is skipped.
Run Inno Setup Compiler manually, install Inno Setup 6, or use -RequireInstaller to fail on missing ISCC.
"@
        if ($RequireInstaller) {
            throw $message
        }
        Write-Warning $message
        Write-Host "[TM] App image is ready for manual Inno compilation: $appImageDir"
        Write-Host "[TM] Suggested ISS file: $issFile"
        Write-Host "[TM] Skipping installer build."
        Write-Host "[TM] Done."
        exit 0
    }

    Write-Host "[TM] Building installer via ISCC..."
    & $iscc "/DMyAppVersion=$appVersion" $issFile
    if ($LASTEXITCODE -ne 0) {
        throw "ISCC failed with exit code $LASTEXITCODE"
    }

    if ($codeSigningEnabled) {
        $setupExe = Join-Path $root "dist_installer\TranscribeMate-Setup.exe"
        Sign-File -SignTool $signtool -Thumbprint $normalizedThumbprint -TimestampUrl $CodeSignTimestampUrl -FilePath $setupExe
    }
    Unblock-PathRecursive -TargetPath (Join-Path $root "dist_installer\TranscribeMate-Setup.exe") -Description "installer executable"

    Write-Host "[TM] Installer ready in: $(Join-Path $root 'dist_installer')"
}
else {
    Write-Host "[TM] Skipping installer build (-SkipInstaller)."
}

Write-Host "[TM] Done."
