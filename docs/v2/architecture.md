# TranscribeMate V2 Architecture

## Goals
- Split UI from heavy ML/runtime dependencies.
- Keep speech pipeline in Python where existing code already works.
- Move desktop UX to JavaFX for stronger UI evolution and richer state handling.

## High-Level Design
- `javafx-client/` (Java 21 + JavaFX): Desktop frontend.
- `transcribemate/v2/backend/` (Python 3.12): JSON-line backend worker process.
- Transport: newline-delimited JSON over `stdin/stdout`.

## Frontend Shell (Phase 4)
- Main shell is split into independent sections:
  - `Dashboard`: active project status, recent outputs/transcripts, timeline tail.
  - `Projects`: workspace project lifecycle (create/select/delete/open).
  - `Files`: project file browser + in-app text preview/editor.
  - `Module`: module execution and module-scoped settings.
- Active module controls visibility of tabs/fields via backend-provided `ui_schema`.
- Module settings and presets are scoped per module and persisted in `config.json`.

## Runtime Flow
1. JavaFX app starts Python backend process:
   - preferred interpreter on Windows: managed runtime (`%LOCALAPPDATA%/TranscribeMate/runtime/python/python.exe`) when present
   - preferred interpreter on macOS: managed runtime (`~/Library/Application Support/TranscribeMate/runtime/python/bin/python3`) when present
   - preferred interpreter on Linux: managed runtime (`${XDG_DATA_HOME:-~/.local/share}/TranscribeMate/runtime/python/bin/python3`) when present
   - default fallback command: `python -m transcribemate.v2.backend.server --stdio`
   - first-launch bootstrap is platform-specific:
     - Windows: `scripts/windows/bootstrap_runtime.ps1`
     - macOS: `scripts/macos/bootstrap_runtime.sh`
     - Linux: `scripts/linux/bootstrap_runtime.sh`
2. Frontend sends request:
   - `run_pipeline`
3. Backend creates async job and immediately returns `job_id`.
4. Backend emits streaming events:
   - `job.log`
   - `job.progress`
   - `job.completed` / `job.failed` / `job.cancelled`
5. Frontend renders progress/logs and allows `cancel_job`.

## Workspace Persistence
- Workspace root:
  - Windows: `%LOCALAPPDATA%/TranscribeMate/workspace/`
  - macOS: `~/Documents/TranscribeMate/workspace/`
  - Linux: `${XDG_DATA_HOME:-~/.local/share}/TranscribeMate/workspace/`
- Metadata:
  - `workspace/workspace.json` (project registry + active project pointer)
- Per-project root:
  - `workspace/projects/<project_id>/`
- Project structure:
  - `input/`, `output/`, `transcripts/`, `jobs/`, `assets/`, `temp/`
  - `project.json` (project metadata)
- Timeline:
  - `jobs/timeline.jsonl` is used by Dashboard to render recent run activity.

## Backend Layers
- `protocol.py`: message envelope parsing/serialization.
- `models.py`: typed request validation + normalized defaults.
- `service.py`: job lifecycle, routing, event emission.
- `pipeline.py`: pipeline orchestration using existing modules:
  - `transcribe.py`, `translate.py`, `subtitles.py`, `diarize.py`, `transcripts.py`.
- `diarization.py`: backend abstraction:
  - `local_cluster_fast` (lower-latency local clustering)
  - `local_cluster_accurate` (higher-quality local clustering)

## Why This Is More Stable
- UI process is isolated from Python package churn.
- Backend jobs are cancellable and observable via explicit events.
- Diarization is fully local-only in V2 component mode (no HF token dependency).

## Build/Run
- Backend dev run:
  - `python -m transcribemate.v2.backend.server --stdio`
- JavaFX dev run:
  - `cd javafx-client`
  - `mvn -q javafx:run`
- macOS dev helpers:
  - `./scripts/macos/run_v2_backend.sh`
  - `./scripts/macos/run_v2_frontend.sh`
- Linux dev helpers:
  - `./scripts/linux/run_v2_backend.sh`
  - `./scripts/linux/run_v2_frontend.sh`

Environment variables used by frontend:
- `TM_BACKEND_CMD` (full custom command)
- `TM_BACKEND_PYTHON` (python executable override)
- `TM_PROJECT_ROOT` (working directory for backend process)
