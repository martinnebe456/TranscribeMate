# TranscribeMate

Desktop transcription app with:
- JavaFX frontend (`javafx-client/`)
- Python backend (`transcribemate/v2/backend/`)
- JSON-line IPC over `stdin/stdout`

This branch is V2-only (JavaFX + Python). Legacy Tkinter/PyInstaller and Inno installer flow are removed.

## Documentation
- User installation and first launch setup: [`docs/user-install.md`](docs/user-install.md)
- Developer setup, run, tests and release build: [`docs/dev-build.md`](docs/dev-build.md)
- Architecture: [`docs/v2/architecture.md`](docs/v2/architecture.md)
- Protocol: [`docs/v2/protocol.md`](docs/v2/protocol.md)
- Protocol schema: [`docs/v2/protocol.schema.json`](docs/v2/protocol.schema.json)

## Quick Start
- Users (Windows):
  - Download release ZIP, extract, run `TranscribeMate.exe`, complete mandatory first-launch runtime setup.
- Developers:
  - Run frontend: `./run_v2_frontend.ps1`
  - Run backend: `./run_v2_backend.ps1`
  - Build release ZIP: `./build_v2_release.ps1`

## Legal
- Disclaimer: [`DISCLAIMER.md`](DISCLAIMER.md)
- License: [`LICENSE`](LICENSE)
