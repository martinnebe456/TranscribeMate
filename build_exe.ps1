$ErrorActionPreference = "Stop"

# Prefer the repo venv, fall back to the Python launcher.
$pyExe = "py"
$pyBaseArgs = @("-3.12")
if (Test-Path ".\.venv\Scripts\python.exe") {
  $pyExe = ".\.venv\Scripts\python.exe"
  $pyBaseArgs = @()
}

function Run-Py {
  param([string[]]$PyArgs)
  & $pyExe @pyBaseArgs @PyArgs
  if ($LASTEXITCODE -ne 0) {
    throw "Python command failed with exit code $LASTEXITCODE"
  }
}

Write-Host "[BUILD] Using Python: $pyExe $pyBaseArgs"
Run-Py -PyArgs @("-m", "pip", "install", "--upgrade", "pip", "pyinstaller")

# Clean build artifacts
Remove-Item -Recurse -Force .\build -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force .\dist  -ErrorAction SilentlyContinue

# Ensure we do NOT bundle large binaries from assets/
Get-ChildItem .\assets -Filter *.exe -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

# Build from spec (bootstrap is the entrypoint)
Run-Py -PyArgs @("-m", "PyInstaller", "--noconfirm", "--clean", "TranscribeMate.spec")

Write-Host "[BUILD] Done. EXE: .\dist\TranscribeMate\TranscribeMate.exe"
