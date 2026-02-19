$ErrorActionPreference = "Stop"

# Prefer project venv interpreter when available, otherwise fallback to PATH Python.
$python = ".\.venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    $python = "python"
}

# Force UTF-8 stdio so JSON-line protocol stays stable across console locales.
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

Write-Host "[TM] Backend command: $python -m transcribemate.v2.backend.server --stdio"
& $python -m transcribemate.v2.backend.server --stdio
