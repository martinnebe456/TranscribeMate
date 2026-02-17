$ErrorActionPreference = "Stop"

$python = ".\.venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    $python = "python"
}

$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

Write-Host "[TM] Backend command: $python -m transcribemate.v2.backend.server --stdio"
& $python -m transcribemate.v2.backend.server --stdio
