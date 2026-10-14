$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$bootstrapPath = Join-Path $PSScriptRoot "bootstrap_runtime.ps1"
$tokens = $null
$parseErrors = $null
$bootstrapAst = [System.Management.Automation.Language.Parser]::ParseFile(
    $bootstrapPath,
    [ref]$tokens,
    [ref]$parseErrors
)
if ($parseErrors.Count -gt 0) {
    throw "Bootstrap script has PowerShell parse errors: $($parseErrors -join '; ')"
}

foreach ($functionName in @("Assert-FileSha256", "Expand-VerifiedArchive", "Clear-StaleRuntimeReadyMarker")) {
    $functionAst = $bootstrapAst.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true)
    if (-not $functionAst) {
        throw "Required integrity function was not found in bootstrap: $functionName"
    }
    . ([scriptblock]::Create($functionAst.Extent.Text))
}

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("TranscribeMate-bootstrap-integrity-" + [guid]::NewGuid().ToString("N"))
$runtimeRoot = Join-Path $testRoot "runtime"
$archivePath = Join-Path $testRoot "corrupt-artifact.zip"
$destinationPath = Join-Path $runtimeRoot "extract"
$validSourcePath = Join-Path $testRoot "valid-source"
$validArchivePath = Join-Path $testRoot "valid-pip-test.whl"
$validDestinationPath = Join-Path $runtimeRoot "valid-site-packages"
$markerPath = Join-Path $runtimeRoot "runtime-ready.json"
New-Item -Path $runtimeRoot -ItemType Directory -Force | Out-Null
New-Item -Path $validSourcePath -ItemType Directory -Force | Out-Null
Set-Content -LiteralPath (Join-Path $validSourcePath "pip-fixture.txt") -Value "valid pinned archive" -Encoding ASCII
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($validSourcePath, $validArchivePath)
Set-Content -LiteralPath $markerPath -Value '{"bootstrap_complete":true}' -Encoding UTF8
Set-Content -LiteralPath $archivePath -Value "intentionally corrupt artifact" -Encoding ASCII

try {
    $validSha256 = (Get-FileHash -LiteralPath $validArchivePath -Algorithm SHA256).Hash
    Expand-VerifiedArchive `
        -ArchivePath $validArchivePath `
        -DestinationPath $validDestinationPath `
        -ExpectedSha256 $validSha256 `
        -Description "valid pip wheel test archive"
    if ((Get-Content -LiteralPath (Join-Path $validDestinationPath "pip-fixture.txt") -Raw).Trim() -ne "valid pinned archive") {
        throw "Verified valid wheel archive did not extract its expected content."
    }

    Clear-StaleRuntimeReadyMarker -RuntimeRoot $runtimeRoot
    $rejected = $false
    try {
        Expand-VerifiedArchive `
            -ArchivePath $archivePath `
            -DestinationPath $destinationPath `
            -ExpectedSha256 ("0" * 64) `
            -Description "corrupt test artifact"
    }
    catch [System.Security.SecurityException] {
        $rejected = $true
    }

    if (-not $rejected) {
        throw "Corrupt artifact was not rejected by its pinned SHA-256 check."
    }
    if (Test-Path -LiteralPath $destinationPath) {
        throw "Corrupt artifact created an extraction destination before rejection."
    }
    if (Test-Path -LiteralPath $markerPath) {
        throw "Stale runtime-ready.json remained after bootstrap initialization rejected corrupt input."
    }

    Write-Host "Bootstrap integrity checks passed: valid wheel extracted; corrupt artifact rejected before extraction; runtime-ready.json absent."
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
