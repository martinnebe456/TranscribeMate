# TranscribeMate Frontend (JavaFX)

## Requirements
- Java 21+
- Maven 3.9+
- Python backend available from repository root

## Run
```powershell
mvn javafx:run
```

From repository root you can also run:
```powershell
./scripts/run_v2_frontend.ps1
```

## UI structure (V2 shell)
- `Dashboard`: project overview, recent outputs, timeline.
- `Projects`: create/select/delete project workspace folders.
- `Files`: in-app project file browser/editor with search and sidecar preview.
- `Modules`: guided run and module-specific controls.
- `Operations`: unified jobs + logs + diagnostics.
- `Settings`: module-scoped settings and presets.

## Backend command resolution
The frontend starts Python backend automatically.

Defaults:
- `python -m transcribemate.v2.backend.server --stdio`

Runtime resolution order:
- `TM_BACKEND_CMD` (full override)
- `TM_BACKEND_PYTHON` (interpreter override)
- project `.venv` (dev mode)
- managed runtime `%LOCALAPPDATA%\TranscribeMate\runtime\python\python.exe` (packaged ZIP mode)
- fallback `python`

Optional overrides:
- `TM_BACKEND_CMD` full command override
- `TM_BACKEND_PYTHON` python executable override
- `TM_PROJECT_ROOT` backend working directory
