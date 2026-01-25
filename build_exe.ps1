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

Run-Py -PyArgs @("-m", "pip", "install", "-r", "requirements.txt")

# Build-time dependency for icon conversion
Run-Py -PyArgs @("-m", "pip", "install", "pillow")

# Generate icon.ico from icon.png if available
if (Test-Path ".\icon.png") {
  Run-Py -PyArgs @("-c", "from PIL import Image; from pathlib import Path; img=Image.open('icon.png').convert('RGBA'); img.save('icon.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])")
  Write-Host "[BUILD] Generated icon.ico from icon.png"
}

# Remove torch packages from the build environment to avoid bundling GPU deps
Write-Host "[BUILD] Removing torch packages from build environment (runtime will install them)"
& $pyExe @pyBaseArgs -m pip uninstall -y torch torchvision torchaudio | Out-Null
if ($LASTEXITCODE -ne 0) {
  Write-Host "[BUILD] torch uninstall exit code $LASTEXITCODE (ignored)"
}

# Clean build artifacts
Remove-Item -Recurse -Force .\build -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force .\dist  -ErrorAction SilentlyContinue

# Ensure we do NOT bundle large binaries from assets/
Get-ChildItem .\assets -Filter *.exe -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

# Build from spec (bootstrap is the entrypoint)
Run-Py -PyArgs @("-m", "PyInstaller", "--noconfirm", "--clean", "TranscribeMate.spec")

Write-Host "[BUILD] Done. EXE: .\dist\TranscribeMate\TranscribeMate.exe"
