# TranscribeMate - Agent Guide

This guide describes the current repository state (V2-only stack).

## Project summary
- Desktop app architecture:
  - JavaFX frontend (`javafx-client/`)
  - Python backend (`transcribemate/v2/backend/`)
- Communication:
  - JSON lines over `stdin/stdout`
- Domain:
  - media transcription, translation, subtitle rendering, optional diarization

## Entry points
- Frontend (JavaFX):
  - `./run_v2_frontend.ps1`
  - or `cd javafx-client && mvn javafx:run`
- Backend (Python):
  - `./run_v2_backend.ps1`
  - or `python -m transcribemate.v2.backend.server --stdio`
- Installer build (Windows):
  - `./build_v2_installer.ps1`

## Code map
- `javafx-client/src/main/java/com/transcribemate/v2/fx/`
  - `TranscribeMateApp.java` - app bootstrap
  - `MainController.java` - UI orchestration, preflight, jobs, logs
  - `BackendClient.java` - backend subprocess + JSON IPC
  - `SystemMonitorWindow.java` - CPU/RAM/GPU/VRAM charts
- `javafx-client/src/main/resources/com/transcribemate/v2/fx/`
  - `main-view.fxml` - primary layout
  - `styles.css` - visual theme
- `transcribemate/v2/backend/`
  - `server.py` - JSON-line server loop
  - `service.py` - methods, job lifecycle, preflight/system metrics
  - `models.py` - request validation/defaulting
  - `pipeline.py` - orchestration using shared pipeline/core modules
  - `protocol.py` - request/response/event structures
  - `diarization.py` - backend abstraction (`stable_local`, `advanced_pyannote`)
- Shared backend dependencies:
  - `transcribemate/core/`
  - `transcribemate/pipeline/`

## Runtime data and outputs
- User data directory:
  - Windows: `%LOCALAPPDATA%/TranscribeMate`
  - Other: `$XDG_DATA_HOME` or `~/.local/share/TranscribeMate`
- Config: `config.json`
- Runtime log: `runtime.log`
- Cache: `cache/huggingface`, `cache/whisper`
- Runtime Python packages: `runtime/python/Lib/site-packages/`
- Online installer runtime interpreter: `runtime/python/python.exe`
- Output root: `<out_dir>/transcribemate_outputs/`

## Tests
- Run with:
  - `python -m pip install -r requirements-dev.txt`
  - `python -m pytest`
- Tests include:
  - V2 backend protocol/models/service
  - selected shared core/pipeline helper tests

## Build/packaging note
- Legacy Tkinter/PyInstaller flow was removed from this branch.
- V2 installer template is available at `installer/TranscribeMate.iss`.
- The ISS script expects a packaged app image in `dist/TranscribeMate/`.
- `build_v2_installer.ps1` builds Java app-image via `jpackage` and then runs `ISCC`.
- Installer includes optional task `online_runtime` to bootstrap embedded Python runtime + dependencies + default models + FFmpeg assets at install time.
- `online_runtime` runs inside installer with a dedicated progress page (live status from bootstrap script output).
- Successful `online_runtime` means end users do not need system Python installation.

## Legal
- `DISCLAIMER.md`
- `LICENSE`

## Skills
Always check `skills/` first and follow relevant `SKILL.md` when applicable.
