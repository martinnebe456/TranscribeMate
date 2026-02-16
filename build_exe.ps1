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

function Stop-TranscribeMateProcesses {
  $procs = Get-Process -Name "TranscribeMate" -ErrorAction SilentlyContinue
  if (-not $procs) { return }
  Write-Host "[BUILD] Stopping running TranscribeMate processes"
  foreach ($p in $procs) {
    try {
      Stop-Process -Id $p.Id -Force -ErrorAction Stop
    } catch {
      Write-Host "[BUILD] Could not stop PID $($p.Id): $($_.Exception.Message)"
    }
  }
  Start-Sleep -Milliseconds 400
}

function Remove-PathWithRetry {
  param(
    [Parameter(Mandatory = $true)][string]$PathToRemove,
    [int]$Attempts = 8,
    [int]$DelayMs = 700
  )

  for ($i = 1; $i -le $Attempts; $i++) {
    try {
      if (Test-Path $PathToRemove) {
        Remove-Item -Recurse -Force $PathToRemove -ErrorAction Stop
      }
      if (-not (Test-Path $PathToRemove)) {
        return
      }
    } catch {
      if ($i -eq $Attempts) {
        throw "Could not remove '$PathToRemove' after $Attempts attempts: $($_.Exception.Message)"
      }
    }
    Start-Sleep -Milliseconds $DelayMs
  }
}

Write-Host "[BUILD] Using Python: $pyExe $pyBaseArgs"
Run-Py -PyArgs @("-m", "pip", "install", "--upgrade", "pip", "pyinstaller")

Run-Py -PyArgs @("-m", "pip", "install", "-r", "requirements.txt")

if (Test-Path ".\\requirements-diarization.txt") {
  Write-Host "[BUILD] Installing optional diarization dependencies"
  try {
    Run-Py -PyArgs @("-m", "pip", "install", "-r", "requirements-diarization.txt")
  } catch {
    Write-Host "[BUILD] Optional diarization dependencies could not be installed. Speaker diarization will be unavailable in this build."
  }
}

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

# Remove speaker stack from the build environment so PyInstaller does not bundle
# partially working pyannote/lightning modules from the build venv.
Write-Host "[BUILD] Removing speaker packages from build environment (runtime/installer will install them)"
& $pyExe @pyBaseArgs -m pip uninstall -y `
  pyannote.audio pyannote.core pyannote.database pyannote.metrics pyannote.pipeline `
  lightning lightning-fabric pytorch-lightning speechbrain `
  torchmetrics torch-audiomentations asteroid-filterbanks pytorch-metric-learning | Out-Null
if ($LASTEXITCODE -ne 0) {
  Write-Host "[BUILD] speaker package uninstall exit code $LASTEXITCODE (ignored)"
}

# Clean build artifacts
Stop-TranscribeMateProcesses
Remove-PathWithRetry -PathToRemove ".\build"
Remove-PathWithRetry -PathToRemove ".\dist"

# Ensure we do NOT bundle large binaries from assets/
Get-ChildItem .\assets -Filter *.exe -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

# Build from spec (bootstrap is the entrypoint)
Run-Py -PyArgs @("-m", "PyInstaller", "--noconfirm", "--clean", "TranscribeMate.spec")

Write-Host "[BUILD] Done. EXE: .\dist\TranscribeMate\TranscribeMate.exe"
