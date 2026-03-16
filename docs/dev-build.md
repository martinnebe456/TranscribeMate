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
- Linux `x86_64` host for Linux release builds (`debian`, `arch`)

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
./scripts/linux/run_v2_backend.sh
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
./scripts/linux/run_v2_frontend.sh
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

## Release Build (Linux x64 TAR.GZ)
```bash
chmod +x ./scripts/linux/build_v2_release.sh
./scripts/linux/build_v2_release.sh --distro debian
./scripts/linux/build_v2_release.sh --distro arch
```

Useful options:
- `--skip-frontend-build` reuse `javafx-client/target` artifacts.
- `--app-version 26.02.17.004` manually set version (also writes `version.txt`).
- `--skip-archive-creation` keep only the unpacked app-image under `dist/linux/<distro>/`.

Linux release notes:
- The script must run on a Linux `x86_64` host.
- Linux release builds are CPU-only in this release.
- It bundles a pinned Python standalone archive plus pinned FFmpeg/FFprobe archives into the backend payload.
- First-launch bootstrap expands those bundled assets into `${XDG_DATA_HOME:-~/.local/share}/TranscribeMate`.

Versioning:
- Local release script runs auto-increment `version.txt` in format `yy.MM.dd.NNN` unless you pass an explicit version.
- Example: `26.02.17.015` = year `2026`, month `02`, day `17`, build `15`.
- CI release runs do not rely on per-runner auto-incrementing.
- When a PR merge closes into `DEV` or `main`, `.github/workflows/release.yml` generates one shared version from the PR `merged_at` timestamp in UTC plus the GitHub Actions `run_number`, then passes that version explicitly into all 4 OS build scripts.
- macOS keeps that same release version for ZIP names, but derives a separate `jpackage`-compatible internal app metadata version automatically.

Build outputs:
- Windows app image: `dist/windows/TranscribeMate/`
- macOS app image: `dist/macos/TranscribeMate.app`
- Linux Debian app image: `dist/linux/debian/TranscribeMate/`
- Linux Arch app image: `dist/linux/arch/TranscribeMate/`
- Release ZIP: `dist/windows/TranscribeMate-<version>-win-x64.zip`
- Release ZIP: `dist/macos/TranscribeMate-<version>-macos-arm64.zip`
- Release TAR.GZ: `dist/linux/TranscribeMate-<version>-linux-debian-x64.tar.gz`
- Release TAR.GZ: `dist/linux/TranscribeMate-<version>-linux-arch-x64.tar.gz`
- Platform SHA256 files: `dist/windows/checksums.txt`, `dist/macos/checksums.txt`, `dist/linux/checksums.txt`
- Combined CI release SHA256 file: `dist/checksums.txt`

## CI Release Job
- Workflow: `.github/workflows/release.yml`
- Trigger:
  - merged PR into `DEV` -> automatic GitHub pre-release with tag `dev-<version>`
  - merged PR into `main` -> automatic GitHub official release with tag `v<version>`
  - direct branch pushes do not publish releases
  - manual tag pushes are no longer the release entrypoint
- Produces:
  - Windows app-image + ZIP release package
  - macOS arm64 app-image + ZIP release package
  - Linux Debian x64 app-image + TAR.GZ release package
  - Linux Arch x64 app-image + TAR.GZ release package
  - `checksums.txt`
  - uploaded build artifacts
- Notes:
  - The workflow checks out the exact PR merge commit SHA for every OS build job.
  - Re-running the same failed workflow keeps the same generated version/tag and updates the existing GitHub release assets.
