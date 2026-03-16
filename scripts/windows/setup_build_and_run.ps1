param(
    [switch]$SkipBuild,
    [switch]$SkipRun,
    [switch]$SkipMavenAutoInstall,
    [string]$MavenVersion = "3.9.9"
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
    Write-Host "[TM-SETUP] $Message"
}

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

function Get-JavaMajorVersion([string]$JavaHome) {
    $javacExe = Join-Path $JavaHome "bin\javac.exe"
    if (-not (Test-Path $javacExe)) {
        return $null
    }

    try {
        $firstLine = (& $javacExe -version 2>&1 | Select-Object -First 1)
        if ($firstLine -match '\b(?<major>\d+)\.') {
            return [int]$Matches.major
        }
    }
    catch {
        return $null
    }

    return $null
}

function Normalize-PathEntry([string]$PathEntry) {
    if ([string]::IsNullOrWhiteSpace($PathEntry)) {
        return ""
    }
    return $PathEntry.Trim().TrimEnd("\")
}

function Add-ToSessionPath([string]$PathEntry) {
    $normalized = Normalize-PathEntry $PathEntry
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return
    }
    $entries = ($env:Path -split ";") | ForEach-Object { Normalize-PathEntry $_ } | Where-Object { $_ }
    if (-not ($entries | Where-Object { $_ -ieq $normalized })) {
        $env:Path = "$normalized;$env:Path"
    }
}

function Add-ToUserPath([string]$PathEntry) {
    $normalized = Normalize-PathEntry $PathEntry
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return
    }

    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @()
    if (-not [string]::IsNullOrWhiteSpace($userPath)) {
        $entries = ($userPath -split ";") | ForEach-Object { Normalize-PathEntry $_ } | Where-Object { $_ }
    }

    if ($entries | Where-Object { $_ -ieq $normalized }) {
        return
    }

    $newUserPath = $normalized
    if (-not [string]::IsNullOrWhiteSpace($userPath)) {
        $newUserPath = "$normalized;$userPath"
    }
    [Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")
}

function Refresh-MavenFromEnvironment {
    if (-not [string]::IsNullOrWhiteSpace($env:MAVEN_HOME)) {
        Add-ToSessionPath (Join-Path $env:MAVEN_HOME "bin")
    }

    $userMavenHome = [Environment]::GetEnvironmentVariable("MAVEN_HOME", "User")
    if (-not [string]::IsNullOrWhiteSpace($userMavenHome)) {
        $env:MAVEN_HOME = $userMavenHome
        Add-ToSessionPath (Join-Path $env:MAVEN_HOME "bin")
    }
}

function Try-InstallMavenWithWinget {
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if (-not $winget) {
        return $false
    }

    $candidates = @(
        "Apache.Maven",
        "Apache.Maven.3"
    )

    foreach ($candidateId in $candidates) {
        Write-Step "Trying Maven install via winget id: $candidateId"
        & $winget.Source install --id $candidateId --exact --source winget --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -eq 0) {
            Refresh-MavenFromEnvironment
            if (Get-Command mvn -ErrorAction SilentlyContinue) {
                return $true
            }
        }
    }

    return $false
}

function Install-MavenZipToUser([string]$Version) {
    $apacheRoot = Join-Path $env:LOCALAPPDATA "Programs\Apache"
    $mavenHome = Join-Path $apacheRoot "apache-maven-$Version"
    $mavenBin = Join-Path $mavenHome "bin"
    $mvnCmd = Join-Path $mavenBin "mvn.cmd"

    if (-not (Test-Path $mvnCmd)) {
        $zipUrl = "https://archive.apache.org/dist/maven/maven-3/$Version/binaries/apache-maven-$Version-bin.zip"
        $tempZip = Join-Path $env:TEMP "apache-maven-$Version-bin.zip"

        Write-Step "Downloading Maven $Version from Apache archive..."
        Invoke-WebRequest -Uri $zipUrl -OutFile $tempZip

        Write-Step "Extracting Maven to $apacheRoot"
        New-Item -ItemType Directory -Force -Path $apacheRoot | Out-Null
        Expand-Archive $tempZip -DestinationPath $apacheRoot -Force
    }
    else {
        Write-Step "Maven already present at $mavenHome"
    }

    if (-not (Test-Path $mvnCmd)) {
        throw "[TM-SETUP] Maven install failed. Missing: $mvnCmd"
    }

    [Environment]::SetEnvironmentVariable("MAVEN_HOME", $mavenHome, "User")
    Add-ToUserPath $mavenBin

    $env:MAVEN_HOME = $mavenHome
    Add-ToSessionPath $mavenBin

    return $mvnCmd
}

function Ensure-JavaPrerequisite {
    $javaHome = Resolve-JavaHome
    if (-not $javaHome) {
        throw @"
[TM-SETUP] Java JDK 21+ with jpackage was not found.
Install JDK and run this script again.

Example:
  winget install EclipseAdoptium.Temurin.21.JDK
"@
    }

    $major = Get-JavaMajorVersion -JavaHome $javaHome
    if ($null -eq $major -or $major -lt 21) {
        throw "[TM-SETUP] Java JDK 21+ is required. Detected JAVA_HOME: $javaHome"
    }

    $env:JAVA_HOME = $javaHome
    Write-Step "JAVA_HOME=$javaHome"
}

function Ensure-MavenPrerequisite([switch]$AllowAutoInstall, [string]$Version) {
    Refresh-MavenFromEnvironment

    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvnCmd) {
        Write-Step "Maven found: $($mvnCmd.Source)"
        return $mvnCmd.Source
    }

    if (-not $AllowAutoInstall) {
        throw @"
[TM-SETUP] Maven (mvn) not found in PATH.
Install Maven 3.9+ manually or rerun this script without -SkipMavenAutoInstall.
"@
    }

    if (Try-InstallMavenWithWinget) {
        $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
        if ($mvnCmd) {
            Write-Step "Maven installed via winget: $($mvnCmd.Source)"
            return $mvnCmd.Source
        }
    }

    $mvnPath = Install-MavenZipToUser -Version $Version
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvnCmd) {
        Write-Step "Maven installed: $($mvnCmd.Source)"
        return $mvnCmd.Source
    }

    Write-Step "Using Maven command path: $mvnPath"
    return $mvnPath
}

function Invoke-ReleaseBuild([string]$RepoRoot) {
    $buildScript = Join-Path $RepoRoot "scripts\windows\build_v2_release.ps1"
    if (-not (Test-Path $buildScript)) {
        throw "[TM-SETUP] Missing build script: $buildScript"
    }

    Write-Step "Running release build..."
    & $buildScript
}

function Try-LaunchBuiltApp([string]$RepoRoot) {
    $exePath = Join-Path $RepoRoot "dist\windows\TranscribeMate\TranscribeMate.exe"
    if (-not (Test-Path $exePath)) {
        throw "[TM-SETUP] Build finished but executable is missing: $exePath"
    }

    Write-Step "Starting app: $exePath"
    $process = Start-Process -FilePath $exePath -WorkingDirectory (Split-Path -Parent $exePath) -PassThru
    Start-Sleep -Seconds 6

    if ($process.HasExited) {
        Write-Warning "[TM-SETUP] App exited quickly (exit code: $($process.ExitCode)). Check runtime logs."
        return
    }

    Write-Step "App is running (PID: $($process.Id))."
}

if ($PSVersionTable.PSVersion.Major -lt 5) {
    throw "[TM-SETUP] PowerShell 5+ is required."
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$candidateRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
$fallbackRoot = Split-Path -Parent $scriptDir
$repoRoot = if (Test-Path (Join-Path $candidateRoot "transcribemate")) { $candidateRoot } elseif (Test-Path (Join-Path $fallbackRoot "transcribemate")) { $fallbackRoot } else { $scriptDir }
Push-Location $repoRoot
try {
    Write-Step "Repository root: $repoRoot"
    Ensure-JavaPrerequisite
    [void](Ensure-MavenPrerequisite -AllowAutoInstall:(-not $SkipMavenAutoInstall) -Version $MavenVersion)

    if (-not $SkipBuild) {
        Invoke-ReleaseBuild -RepoRoot $repoRoot
    }
    else {
        Write-Step "Build skipped."
    }

    if (-not $SkipRun) {
        Try-LaunchBuiltApp -RepoRoot $repoRoot
    }
    else {
        Write-Step "Run skipped."
    }

    Write-Step "Done."
}
finally {
    Pop-Location
}
