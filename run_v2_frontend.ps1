$ErrorActionPreference = "Stop"

function Test-JavaHome([string]$HomePath) {
    if ([string]::IsNullOrWhiteSpace($HomePath)) {
        return $false
    }
    $javaExe = Join-Path $HomePath "bin\\java.exe"
    $javacExe = Join-Path $HomePath "bin\\javac.exe"
    return (Test-Path $javaExe) -and (Test-Path $javacExe)
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

$resolvedJavaHome = Resolve-JavaHome
if (-not $resolvedJavaHome) {
    throw @"
[TM] Java JDK (21+) nebyla nalezena nebo je neplatny JAVA_HOME.
Nainstaluj JDK a spust skript znovu.

Priklad (winget):
  winget install EclipseAdoptium.Temurin.21.JDK

Pak otevri novy PowerShell a over:
  java -version
  javac -version
"@
}

$env:JAVA_HOME = $resolvedJavaHome
Write-Host "[TM] JAVA_HOME=$($env:JAVA_HOME)"

$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCmd) {
    throw @"
[TM] Maven (mvn) nebyl nalezen v PATH.
Nainstaluj Maven nebo pouzij Maven wrapper.
"@
}

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontend = Join-Path $root "javafx-client"

if (-not (Test-Path $frontend)) {
    throw "javafx-client folder not found."
}

$env:TM_PROJECT_ROOT = $root
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
Write-Host "[TM] TM_PROJECT_ROOT=$($env:TM_PROJECT_ROOT)"

Push-Location $frontend
try {
    mvn javafx:run
}
finally {
    Pop-Location
}
