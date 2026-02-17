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
./run_v2_frontend.ps1
```

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
