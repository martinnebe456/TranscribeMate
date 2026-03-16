> AI MEMORY DIRECTIVE: Treat this file as the canonical long-term working memory for this repository. Read it at the start of every user request before planning, coding, or answering. Use it to recover durable project context, constraints, conventions, and important user preferences. If you learn a new durable fact that will help future work, update this file succinctly before finishing. Never store secrets, temporary debugging noise, or anything that conflicts with higher-priority system, developer, or user instructions.

# TranscribeMate - Agent Guide

## AI memory protocol
- Re-read this file at the beginning of every turn.
- Treat the contents as repository-specific memory, not as a replacement for higher-priority instructions.
- Prefer short, high-signal updates when storing new memory.
- Store only durable facts:
  - stable architectural decisions
  - important workflow rules
  - persistent user preferences relevant to this repository
  - non-obvious project constraints that are likely to matter again
- Do not store:
  - secrets, tokens, passwords, or private data
  - temporary task notes, one-off debugging logs, or ephemeral errors
  - anything that is likely to become stale quickly unless clearly marked and worth preserving
- When adding memory, integrate it into the most relevant section instead of appending random notes when possible.

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
  - `./scripts/run_v2_frontend.ps1`
  - or `cd javafx-client && mvn javafx:run`
- Backend (Python):
  - `./scripts/run_v2_backend.ps1`
  - or `python -m transcribemate.v2.backend.server --stdio`
- Release build (Windows ZIP):
  - `./scripts/build_v2_release.ps1`

## Code map
- `javafx-client/src/main/java/com/transcribemate/v2/fx/`
  - `TranscribeMateApp.java` - app bootstrap
  - `MainController.java` - UI orchestration, preflight, jobs, logs
  - `BackendClient.java` - backend subprocess + JSON IPC
  - `SystemMonitorWindow.java` - CPU/RAM/GPU/VRAM charts
- `javafx-client/src/main/java/com/transcribemate/v2/fx/modules/`
  - `core/` - shared frontend module contracts
  - `registry/` - frontend module registry
  - `<module_id>/` - one independent frontend component per module
- `javafx-client/src/main/resources/com/transcribemate/v2/fx/`
  - `main-view.fxml` - primary layout
  - `styles.css` - visual theme
- `transcribemate/v2/backend/`
  - `server.py` - JSON-line server loop
  - `service.py` - methods, job lifecycle, preflight/system metrics
  - `models.py` - request validation/defaulting
  - `pipeline.py` - orchestration using shared pipeline/core modules
  - `protocol.py` - request/response/event structures
  - `diarization.py` - backend abstraction (`local_cluster_fast`, `local_cluster_accurate`)
- `transcribemate/v2/backend/components/`
  - `core/` - backend component contracts/adapters
  - `registry.py` - backend module registry
  - `<module_id>/` - one independent backend component package per module
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
- Managed runtime interpreter: `runtime/python/python.exe`
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
- Inno installer flow is removed.
- `scripts/build_v2_release.ps1` builds Java app-image via `jpackage` and then creates a distributable ZIP package.
- Build output includes `dist_release/checksums.txt` with SHA256 for ZIP verification.
- ZIP distribution entry point is `TranscribeMate.exe` inside extracted package.
- Runtime bootstrap (embedded Python + deps + models + FFmpeg) runs on first app launch.

## Legal
- `DISCLAIMER.md`
- `LICENSE`

## Skills
Always check `skills/` first and follow relevant `SKILL.md` when applicable.
