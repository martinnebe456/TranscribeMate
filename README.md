# TranscribeMate

Desktop transcription app with:
- JavaFX frontend (`javafx-client/`)
- Python backend (`transcribemate/v2/backend/`)
- JSON-line IPC over `stdin/stdout`

This branch is V2-only (JavaFX + Python). Legacy Tkinter/PyInstaller and Inno installer flow are removed.

## Modules (V2)
- `offline_transcribe` - offline A/V transcript from local files
- `youtube_transcribe` - transcript from YouTube video/playlist
- `speaker_transcribe` - transcript with speaker diarization
- `conference_mode` - multi-file conference workflow with per-file metadata
- `youtube_subtitles` - YouTube download + subtitle rendering (translated or source)
- `youtube_dub` - YouTube download + translated dubbing

## Module UX Isolation
- Frontend reads module `ui_schema` from backend `get_capabilities` and shows only relevant tabs/sections/fields for the active module.
- Settings are module-scoped and persist independently in `config.json`.
- Module presets can be saved/loaded/deleted per module in Settings.
- Job history is filtered by active module and supports replay of selected job request.
- Output folders are separated by module:
  - `<out_dir>/transcribemate_outputs/<module_id>/...`

## Component-Based Architecture
- Frontend modules are component-based and isolated:
  - `javafx-client/src/main/java/com/transcribemate/v2/fx/modules/`
  - shared contracts in `core/`, registration in `registry/`, one folder per module
- Backend modules are component-based and isolated:
  - `transcribemate/v2/backend/components/`
  - shared contracts/adapters in `core/`, registration in `registry.py`, one package per module
- Each module folder/package contains its own `README.md` with module-specific behavior.

## Documentation
- User installation and first launch setup: [`docs/user-install.md`](docs/user-install.md)
- Developer setup, run, tests and release build: [`docs/dev-build.md`](docs/dev-build.md)
- Repository structure map: [`docs/repository-layout.md`](docs/repository-layout.md)
- Testing guide: [`tests/TESTING.md`](tests/TESTING.md)
- Architecture: [`docs/v2/architecture.md`](docs/v2/architecture.md)
- Protocol: [`docs/v2/protocol.md`](docs/v2/protocol.md)
- Protocol schema: [`docs/v2/protocol.schema.json`](docs/v2/protocol.schema.json)

## Quick Start
- Users (Windows):
  - Download release ZIP, extract, run `TranscribeMate.exe`, complete mandatory first-launch runtime setup.
- Developers:
  - Run frontend: `./run_v2_frontend.ps1`
  - Run backend: `./run_v2_backend.ps1`
  - Run tests: `python -m pytest -q`
  - Build release ZIP: `./build_v2_release.ps1`

## Release & CI
- Distribution format: Windows ZIP (portable), no installer.
- Build outputs:
  - `dist/TranscribeMate/` (app-image)
  - `dist_release/TranscribeMate-<version>-win-x64.zip`
  - `dist_release/checksums.txt` (SHA256)
- Main workflows:
  - `.github/workflows/ci.yml`
  - `.github/workflows/lint-and-sanity.yml`
  - `.github/workflows/docs-and-contract.yml`
  - `.github/workflows/security.yml`
  - `.github/workflows/nightly-smoke.yml`
  - `.github/workflows/release-windows.yml`

## Legal
- Disclaimer: [`DISCLAIMER.md`](DISCLAIMER.md)
- License: [`LICENSE`](LICENSE)
