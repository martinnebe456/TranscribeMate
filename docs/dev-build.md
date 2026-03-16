# Developer Build Guide

## Stack
- Frontend: Java 21 + JavaFX + Maven (`javafx-client/`)
- Backend: Python 3.12 (`transcribemate/v2/backend/`)
- IPC: JSON lines over stdio

## Prerequisites
- Python 3.12+
- Java JDK 21+ (must include `jpackage`)
- Maven 3.9+
- PowerShell 7+ for Windows scripts
- Apple Silicon (`arm64`) host for the macOS release build

## Local Setup
Windows:
```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

macOS / Linux:
```bash
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

Optional diarization dependencies:
```bash
python -m pip install -r requirements-diarization.txt
```

## Run (Dev)
- Backend:
```powershell
./scripts/windows/run_v2_backend.ps1
```
or
```bash
./scripts/macos/run_v2_backend.sh
```
or
```bash
python -m transcribemate.v2.backend.server --stdio
```

- Frontend:
```powershell
./scripts/windows/run_v2_frontend.ps1
```
or
```bash
./scripts/macos/run_v2_frontend.sh
```
or
```bash
cd javafx-client
mvn javafx:run
```

Plain `mvn javafx:run` from `javafx-client/` now auto-detects the repository backend and project `.venv`. The helper scripts are still the most predictable option because they export `TM_PROJECT_ROOT` explicitly.
Top-level `scripts/*` wrappers remain for compatibility, but OS-specific subfolders are the preferred entrypoints.

## Tests
```bash
python -m pip install -r requirements-dev.txt
python -m pytest
```

## Full macOS Validation Runner
```bash
chmod +x ./scripts/macos/test_all.sh
./scripts/macos/test_all.sh
```

What it does:
- prepares or reuses repo `.venv`
- installs Python runtime + dev dependencies
- bootstraps bundled FFmpeg into isolated `TM_APP_DATA_DIR`
- runs fast `pytest -q`
- runs real fixture regression over `tests/test_files/*.mp3` + paired PDF transcripts
- runs frontend Maven tests/build checks without GUI automation
- runs the macOS release build and verifies generated ZIP + `checksums.txt`

Artifacts:
- stored under `artifacts/test_all/macos/<timestamp>/`
- backend regression runs use isolated `TM_APP_DATA_DIR` rooted inside that artifact folder

Manual UI verification stays separate:
```bash
./scripts/macos/run_v2_frontend.sh
```
Then click through the UI manually on macOS after the automated suite passes.

## Release Build (Windows ZIP)
```powershell
./scripts/windows/build_v2_release.ps1
```

Useful options:
- `-SkipFrontendBuild` reuse `javafx-client/target` artifacts.
- `-AppVersion 26.02.17.004` manually set version (also writes `version.txt`).

## Release Build (macOS arm64 ZIP)
```bash
chmod +x ./scripts/macos/build_v2_release.sh
./scripts/macos/build_v2_release.sh
```

Useful options:
- `--skip-frontend-build` reuse `javafx-client/target` artifacts.
- `--app-version 26.02.17.004` manually set version (also writes `version.txt`).
- `--skip-zip-creation` keep only the unpacked `.app` image under `dist/macos/`.

macOS release notes:
- The script must run on an Apple Silicon macOS host.
- It bundles a pinned Python standalone archive plus pinned FFmpeg/FFprobe archives into the app payload.
- First-launch bootstrap expands those bundled assets into `~/Library/Application Support/TranscribeMate`.

Versioning:
- Each run auto-increments `version.txt` in format `yy.MM.dd.NNN`.
- Example: `26.02.17.015` = year `2026`, month `02`, day `17`, build `15`.
- macOS keeps that same release version for ZIP names, but derives a separate `jpackage`-compatible internal app metadata version automatically.

Build outputs:
- Windows app image: `dist/windows/TranscribeMate/`
- macOS app image: `dist/macos/TranscribeMate.app`
- Release ZIP: `dist/windows/TranscribeMate-<version>-win-x64.zip`
- Release ZIP: `dist/macos/TranscribeMate-<version>-macos-arm64.zip`
- Platform SHA256 files: `dist/windows/checksums.txt`, `dist/macos/checksums.txt`
- Combined CI release SHA256 file: `dist/checksums.txt`

## CI Release Job
- Workflow: `.github/workflows/release.yml`
- Produces:
  - Windows app-image + ZIP release package
  - macOS arm64 app-image + ZIP release package
  - `checksums.txt`
  - uploaded build artifacts
