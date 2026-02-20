param(
    [switch]$SkipFrontendBuild,
    [string]$AppVersion,
    [string]$CodeSignThumbprint,
    [string]$CodeSignTimestampUrl = "http://timestamp.digicert.com",
    [switch]$RequireCodeSigning,
    [switch]$SkipZipCreation,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

# ============================================================================
# TranscribeMate v2 release workflow (high level):
# 1) Resolve prerequisites (JDK with jpackage, Maven, output paths).
# 2) Resolve/set application version (manual value or daily auto-increment).
# 3) Build JavaFX frontend artifacts (unless skipped).
# 4) Build app-image with jpackage.
# 5) Optionally sign executable with signtool and an Authenticode certificate.
# 6) Bundle Python backend sources/scripts into app image.
# 7) Optionally produce ZIP + SHA256 checksum in dist_release/.
# ============================================================================

# ============================================================================
# Helper functions
# ============================================================================
# Prints command usage for interactive/manual runs.
function Show-Help {
    $helpText = @"

==================================================================
TranscribeMate v2 release build script.
==================================================================

Usage:

  .\scripts\build_v2_release.ps1 [-SkipFrontendBuild] [-AppVersion <version>] [-CodeSignThumbprint <thumbprint>] [-CodeSignTimestampUrl <url>] [-RequireCodeSigning] [-SkipZipCreation] [-Help]

Parameters:

    -SkipFrontendBuild:
        Skip building the Java frontend. Use this if you have already built the frontend JAR and dependencies.
    
    -AppVersion:
        Specify the app version to embed in the build. If not provided, an auto-incrementing version based on the current date will be used.
    
    -CodeSignThumbprint:
        Thumbprint of the code signing certificate to use. If not provided, the script will attempt to auto-detect a suitable certificate from the Windows certificate store.
    
    -CodeSignTimestampUrl:
        URL of the timestamp server to use for code signing. Default is http://timestamp.digicert.com.
    
    -RequireCodeSigning:
        If specified, the script will fail if a valid code signing certificate is not found or if signing fails.
    
    -SkipZipCreation:
        Skip creating the ZIP release package. The app image will still be created in dist/TranscribeMate.
    
    -Help:
        Show this help message and exit.

Example:
    
    .\scripts\build_v2_release.ps1 -AppVersion "26.02.18.005"
    
    .\scripts\build_v2_release.ps1
    
    .\scripts\build_v2_release.ps1 -SkipZipCreation

==================================================================
"@
    Write-Host $helpText
}
if ($Help) {
    Show-Help
    exit 0
}


# Validates that a directory looks like a full JDK installation required by this script.
function Test-JavaHome([string]$HomePath) {
    if ([string]::IsNullOrWhiteSpace($HomePath)) {
        return $false
    }
    $javaExe = Join-Path $HomePath "bin\java.exe"
    $javacExe = Join-Path $HomePath "bin\javac.exe"
    $jpackageExe = Join-Path $HomePath "bin\jpackage.exe"
    return (Test-Path $javaExe) -and (Test-Path $javacExe) -and (Test-Path $jpackageExe)
}


# Resolves JAVA_HOME with fallback order:
# 1) Existing $env:JAVA_HOME if valid.
# 2) java.home reported by `java -XshowSettings`.
# 3) Parent directory from java.exe location in PATH.
function Resolve-JavaHome {
    if (Test-JavaHome $env:JAVA_HOME) {
        return $env:JAVA_HOME
    }

    # java.exe is required for runtime discovery even when JAVA_HOME is unset.
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        return $null
    }

    $javaHome = $null
    try {
        # `java.home` can point either to JDK root or nested runtime folder.
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

    # Final fallback: infer JDK root from ...\bin\java.exe.
    $candidate = Split-Path -Parent (Split-Path -Parent $javaCmd.Source)
    if (Test-JavaHome $candidate) {
        return $candidate
    }

    return $null
}

# Canonicalizes certificate thumbprint input:
# - strips spaces/separators accidentally copied from cert manager
# - normalizes to uppercase hex for reliable comparisons/logging
function Normalize-Thumbprint([string]$Thumbprint) {
    if ([string]::IsNullOrWhiteSpace($Thumbprint)) {
        return ""
    }
    return ($Thumbprint -replace "[^0-9A-Fa-f]", "").ToUpperInvariant()
}

# Locates signtool.exe via PATH first, then common Windows SDK installation paths.
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
        # Prefer newest SDK folder (sorted descending by version-like name).
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

# Tries to auto-select a valid code-signing cert from standard user/machine stores.
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
                # OID 1.3.6.1.5.5.7.3.3 = Code Signing EKU.
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

# Signs one file and throws on any signing failure.
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
    # Timestamp keeps signature valid even after certificate expiry.
    if (-not [string]::IsNullOrWhiteSpace($TimestampUrl)) {
        $args += @("/tr", $TimestampUrl)
    }
    $args += @($FilePath)

    & $SignTool @args
    if ($LASTEXITCODE -ne 0) {
        throw "[TM] signtool failed for: $FilePath (exit $LASTEXITCODE)"
    }
}

# Best-effort MOTW cleanup for files copied from downloaded sources.
# This avoids unnecessary "downloaded from internet" friction on end-user machines.
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

# Windows file locks can hold directories for a short time (Explorer, AV, indexers),
# so deletion is retried before failing with a user-action hint.
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
Then run .\scripts\build_v2_release.ps1 again.
"@
    throw $hint
}

# Compresses app-image into ZIP with retry/backoff, which helps with transient locks.
function New-ReleaseZipWithRetry(
    [string]$SourcePath,
    [string]$DestinationPath,
    [int]$MaxAttempts = 8,
    [int]$DelayMilliseconds = 1500
) {
    if (-not (Test-Path -LiteralPath $SourcePath)) {
        throw ("[TM] Cannot create ZIP. Source path not found: {0}" -f $SourcePath)
    }

    $lastError = $null
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            if (Test-Path -LiteralPath $DestinationPath) {
                Remove-PathWithRetry -TargetPath $DestinationPath -Description "previous release zip" -MaxAttempts 3 -DelayMilliseconds 400
            }

            Compress-Archive -Path $SourcePath -DestinationPath $DestinationPath -CompressionLevel Optimal -ErrorAction Stop

            if (-not (Test-Path -LiteralPath $DestinationPath)) {
                throw ("ZIP was not created: {0}" -f $DestinationPath)
            }

            $zipInfo = Get-Item -LiteralPath $DestinationPath -ErrorAction Stop
            if ($zipInfo.Length -le 0) {
                throw ("ZIP has invalid size (0 B): {0}" -f $DestinationPath)
            }

            return
        }
        catch {
            $lastError = $_.Exception.Message
            if ($attempt -lt $MaxAttempts) {
                Write-Warning ("[TM] ZIP creation failed (attempt {0}/{1}): {2}" -f $attempt, $MaxAttempts, $lastError)
                Start-Sleep -Milliseconds ($DelayMilliseconds * $attempt)
                continue
            }
        }
    }

    $hint = @"
[TM] Failed to create ZIP release package.
Source: $SourcePath
Destination: $DestinationPath
Reason: $lastError

Close running TranscribeMate instances and close any Explorer window opened inside dist/TranscribeMate.
If antivirus is scanning files, wait a moment and run .\scripts\build_v2_release.ps1 again.
"@
    throw $hint
}

# Resolves build version:
# - explicit -AppVersion wins and is persisted to version.txt
# - otherwise auto-increments YY.MM.DD.NNN based on previous version.txt value
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
        # Preserve existing counter width (at least 3 digits) for stable filename sorting.
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

# Shared guard for required files/directories in later build stages.
function Assert-PathExists([string]$Path, [string]$Description) {
    if (-not (Test-Path $Path)) {
        throw ("[TM] Missing {0}: {1}" -f $Description, $Path)
    }
}

# Verifies that the minimal backend runtime payload exists inside app image.
# This fails fast if packaging accidentally omitted critical runtime files.
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

# ============================================================================
# Main build pipeline
# ============================================================================
# Resolve key repository/output paths relative to this script location so the
# script can be launched from repo root or scripts/ subfolder.
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$candidateRoot = Split-Path -Parent $scriptDir
$root = if (Test-Path (Join-Path $candidateRoot "javafx-client")) { $candidateRoot } else { $scriptDir }
$frontendDir = Join-Path $root "javafx-client"
$distDir = Join-Path $root "dist"
$appImageDir = Join-Path $distDir "TranscribeMate"
$releaseDir = Join-Path $root "dist_release"
$zipPath = ""

# Fail fast on missing required project layout.
if (-not (Test-Path $frontendDir)) {
    throw "[TM] Missing javafx-client directory."
}

# Prerequisite toolchain discovery.
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

# Version drives app metadata and final ZIP filename.
$appVersion = Resolve-AppVersion -Requested $AppVersion -RootPath $root
Write-Host "[TM] JAVA_HOME=$($env:JAVA_HOME)"
Write-Host "[TM] Version=$appVersion"
$zipName = "TranscribeMate-$appVersion-win-x64.zip"
$zipPath = Join-Path $releaseDir $zipName

# Frontend build can be skipped for local iteration when target artifacts already exist.
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

# Select the newest runnable JAR (exclude sources/javadocs/original artifacts).
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

# jpackage expects one input folder containing app JAR + dependency JARs.
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

# Build Windows app-image (portable folder containing EXE + runtime files).
Write-Host "[TM] Creating app image via jpackage..."
$jpackageArgs = @(
    "--type", "app-image",
    "--name", "TranscribeMate",
    "--dest", $distDir,
    "--input", $inputDir,
    "--main-jar", $jar.Name,
    # Launcher class owns JavaFX startup and runtime init.
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

# Code signing thumbprint resolution order:
# 1) -CodeSignThumbprint argument
# 2) TM_CODESIGN_THUMBPRINT environment variable
# 3) auto-detected certificate from Windows stores
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
    Write-Warning "[TM] Build is unsigned. Windows SmartScreen/Smart App Control may block app launch."
}

# Bundle Python backend source inside the Java app image so first-run bootstrap
# can create a managed runtime and execute backend locally.
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

# Remove Python bytecode caches from packaged payload to reduce size/noise.
Get-ChildItem -Path $bundledBackendDir -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem -Path $bundledBackendDir -Recurse -File -Include "*.pyc", "*.pyo" | Remove-Item -Force -ErrorAction SilentlyContinue

# Verify bundle structure before we continue with release output steps.
Validate-BundledBackend -BundledBackendDir $bundledBackendDir

Write-Host "[TM] App image ready: $appImageDir"

# Sign final executable after all app-image modifications are completed.
if ($codeSigningEnabled) {
    $appImageExe = Join-Path $appImageDir "TranscribeMate.exe"
    Sign-File -SignTool $signtool -Thumbprint $normalizedThumbprint -TimestampUrl $CodeSignTimestampUrl -FilePath $appImageExe
}
Unblock-PathRecursive -TargetPath $appImageDir -Description "app image files"

# ============================================================================
# Optional release ZIP creation
# ============================================================================
if (-not $SkipZipCreation) {
    if (-not (Test-Path $releaseDir)) {
        New-Item -Path $releaseDir -ItemType Directory | Out-Null
    }

    if (Test-Path $zipPath) {
        Remove-PathWithRetry -TargetPath $zipPath -Description "existing release zip"
    }

    Write-Host "[TM] Creating ZIP release package..."
    New-ReleaseZipWithRetry -SourcePath $appImageDir -DestinationPath $zipPath
    Unblock-PathRecursive -TargetPath $zipPath -Description "release zip"

    if (-not (Test-Path -LiteralPath $zipPath)) {
        throw ("[TM] ZIP release package was not created: {0}" -f $zipPath)
    }

    # checksums.txt currently contains one line for the newest ZIP artifact.
    $checksumPath = Join-Path $releaseDir "checksums.txt"
    $zipHash = (Get-FileHash -Path $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksumLine = "$zipHash *$zipName"
    Set-Content -Path $checksumPath -Value $checksumLine -Encoding ASCII
    Unblock-PathRecursive -TargetPath $checksumPath -Description "release checksums"

    Write-Host "[TM] ZIP release ready: $zipPath"
    Write-Host "[TM] SHA256 checksums written to: $checksumPath"
    Write-Host "[TM] Done."
} else {
    Write-Warning "[TM] ZIP creation skipped due to -SkipZipCreation. App image is available at: $appImageDir"
}

