# TranscribeMate

Desktop transcription application with:
- JavaFX frontend (`javafx-client/`)
- Python backend worker (`transcribemate/v2/backend/`)
- JSON-line IPC over `stdin/stdout`

> Important legal and licensing info:
> - Read `DISCLAIMER.md` before use: [DISCLAIMER.md](DISCLAIMER.md)
> - Project license: [LICENSE](LICENSE)

This repository is V2-only (JavaFX + Python). Legacy Tkinter/PyInstaller flow is not part of this branch.

## For Users (Windows)

### What You Get
- Desktop app for transcription, translation, subtitles, and optional speaker diarization.
- Installer that also prepares backend runtime automatically:
  - embedded Python runtime in `%LOCALAPPDATA%\TranscribeMate\runtime\python`
  - Python dependencies
  - default AI models
  - FFmpeg binaries
- No system-wide Python installation required after successful install.

### Installation
1. Download installer `.exe` from project releases.
2. Run installer.
3. Wait for the mandatory "Online Runtime Setup" step to finish.
4. Launch TranscribeMate.

### Installation Requirements
- Windows x64
- Internet connection during installation (online runtime bootstrap is mandatory)
- Enough free disk space for runtime, dependencies, and models (can be several GB)

### Troubleshooting
- Online setup log:
  - `%LOCALAPPDATA%\TranscribeMate\runtime-bootstrap.log`
- Application runtime log:
  - `%LOCALAPPDATA%\TranscribeMate\runtime.log`
- If installation fails during online setup:
  1. Check `runtime-bootstrap.log`.
  2. Verify internet/proxy/firewall policy.
  3. Re-run installer.

### Runtime Data
- User data root:
  - `%LOCALAPPDATA%\TranscribeMate`
- Typical content:
  - `config.json`
  - `runtime.log`
  - `runtime-bootstrap.log`
  - `runtime\python\`
  - `cache\huggingface`
  - `cache\whisper`
  - `assets\` (ffmpeg/ffprobe)

### Output Location
- Outputs are written under:
  - `<out_dir>\transcribemate_outputs\`

---

## For Developers

### Stack
- Frontend: Java 21 + JavaFX + Maven (`javafx-client/`)
- Backend: Python 3.12 (`transcribemate/v2/backend/`)
- Protocol: JSON lines over stdio

### Prerequisites
- Python 3.12+
- Java JDK 21+ (with `jpackage`)
- Maven 3.9+
- PowerShell (for helper scripts on Windows)

### Local Setup
```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

Optional diarization extras:
```powershell
python -m pip install -r requirements-diarization.txt
```

### Run Backend (Dev)
```powershell
./run_v2_backend.ps1
```

or directly:
```powershell
python -m transcribemate.v2.backend.server --stdio
```

### Run Frontend (Dev)
```powershell
./run_v2_frontend.ps1
```

or:
```powershell
cd javafx-client
mvn javafx:run
```

### Tests
```powershell
python -m pip install -r requirements-dev.txt
python -m pytest
```

### Build Installer (Windows)
One-command build:
```powershell
./build_v2_installer.ps1
```

Useful options:
- `-SkipFrontendBuild` reuse existing `javafx-client/target` artifacts
- `-SkipInstaller` build app image only (no ISCC run)
- `-RequireInstaller` fail if `ISCC.exe` is not found
- `-AppVersion 26.02.17.004` manually set version (also writes `version.txt`)

Versioning:
- On each `./build_v2_installer.ps1` run, `version.txt` is auto-updated at start to format `yy.MM.dd.NNN`.
- Example: `26.02.17.015` means year `2026`, month `02`, day `17`, build `15` for that day.

Build outputs:
- App image: `dist/TranscribeMate/`
- Installer: `dist_installer/TranscribeMate-Setup.exe`

Manual ISS compile entry:
- `installer/TranscribeMate.iss`

### Environment Variables
- `TM_BACKEND_CMD` full backend command override
- `TM_BACKEND_PYTHON` Python interpreter override
- `TM_PROJECT_ROOT` backend working directory override

### Architecture and Protocol Docs
- `docs/v2/architecture.md`
- `docs/v2/protocol.md`
- `docs/v2/protocol.schema.json`

### Repository Map
- `javafx-client/` JavaFX UI
- `transcribemate/v2/backend/` backend service and protocol handlers
- `transcribemate/core/` shared runtime/helpers
- `transcribemate/pipeline/` transcription/translation/subtitle/diarization orchestration
- `scripts/bootstrap_runtime.ps1` installer online runtime bootstrap
- `installer/TranscribeMate.iss` Inno Setup installer

---

## Legal
- Disclaimer: [DISCLAIMER.md](DISCLAIMER.md)
- License: [LICENSE](LICENSE)
