$ErrorActionPreference = "Stop"

# Prefer project venv interpreter when available, otherwise fallback to PATH Python.
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$candidateRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
$fallbackRoot = Split-Path -Parent $scriptDir
$root = if (Test-Path (Join-Path $candidateRoot "transcribemate")) { $candidateRoot } elseif (Test-Path (Join-Path $fallbackRoot "transcribemate")) { $fallbackRoot } else { $scriptDir }

$python = Join-Path $root ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    $python = "python"
}

# Force UTF-8 stdio so JSON-line protocol stays stable across console locales.
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

Write-Host "[TM] Backend command: $python -m transcribemate.v2.backend.server --stdio"
Push-Location $root
try {
    & $python -m transcribemate.v2.backend.server --stdio
}
finally {
    Pop-Location
}
