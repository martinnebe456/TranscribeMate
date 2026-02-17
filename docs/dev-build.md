# Developer Build Guide

## Stack
- Frontend: Java 21 + JavaFX + Maven (`javafx-client/`)
- Backend: Python 3.12 (`transcribemate/v2/backend/`)
- IPC: JSON lines over stdio

## Prerequisites
- Python 3.12+
- Java JDK 21+ (must include `jpackage`)
- Maven 3.9+
- PowerShell (Windows scripts)

## Local Setup
```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

Optional diarization dependencies:
```powershell
python -m pip install -r requirements-diarization.txt
```

## Run (Dev)
- Backend:
```powershell
./run_v2_backend.ps1
```
or
```powershell
python -m transcribemate.v2.backend.server --stdio
```

- Frontend:
```powershell
./run_v2_frontend.ps1
```
or
```powershell
cd javafx-client
mvn javafx:run
```

## Tests
```powershell
python -m pip install -r requirements-dev.txt
python -m pytest
```

## Release Build (Windows ZIP)
```powershell
./build_v2_release.ps1
```

Useful options:
- `-SkipFrontendBuild` reuse `javafx-client/target` artifacts.
- `-AppVersion 26.02.17.004` manually set version (also writes `version.txt`).

Versioning:
- Each run auto-increments `version.txt` in format `yy.MM.dd.NNN`.
- Example: `26.02.17.015` = year `2026`, month `02`, day `17`, build `15`.

Build outputs:
- App image: `dist/TranscribeMate/`
- Release ZIP: `dist_release/TranscribeMate-<version>-win-x64.zip`
- SHA256 file: `dist_release/checksums.txt`

## CI Release Job
- Workflow: `.github/workflows/release-windows.yml`
- Produces:
  - Windows app-image + ZIP release package
  - `checksums.txt`
  - uploaded build artifacts
