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

## Workspace & Project Management
- V2 UI now includes a shell with dedicated sections:
  - `Dashboard` (project stats, recent artifacts, timeline tail)
  - `Projects` (create/select/delete/open project)
  - `Files` (import, drag & drop, search, preview/edit/save text artifacts, sidecar preview, history snapshots)
  - `Modules` (module-specific run + advanced options with guided run card)
  - `Operations` (unified jobs queue + live logs + diagnostics)
  - `Settings` (module-scoped configuration + presets)
- Workspace metadata is stored in:
  - Windows: `%LOCALAPPDATA%/TranscribeMate/workspace/workspace.json`
  - macOS: `~/Documents/TranscribeMate/workspace/workspace.json`
  - Linux: `${XDG_DATA_HOME:-~/.local/share}/TranscribeMate/workspace/workspace.json`
- Project data root:
  - Windows: `%LOCALAPPDATA%/TranscribeMate/workspace/projects/<project_id>/`
  - macOS: `~/Documents/TranscribeMate/workspace/projects/<project_id>/`
  - Linux: `${XDG_DATA_HOME:-~/.local/share}/TranscribeMate/workspace/projects/<project_id>/`
- Each project keeps separated folders:
  - `input/`, `output/`, `transcripts/`, `jobs/`, `assets/`, `temp/`
- Job timeline events are persisted to:
  - `jobs/timeline.jsonl`

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
- Users:
  - Windows: download the Windows ZIP, extract it, run `TranscribeMate.exe`, then complete mandatory first-launch runtime setup.
  - macOS (Apple Silicon): download the macOS ZIP, extract it, open `TranscribeMate.app`, then complete mandatory first-launch runtime setup.
  - Linux x64 (Debian / Arch): download the matching Linux TAR.GZ, extract it, run `TranscribeMate/bin/TranscribeMate`, then complete mandatory first-launch runtime setup.
- Developers:
  - Run frontend on Windows: `./scripts/windows/run_v2_frontend.ps1`
  - Run frontend on macOS: `./scripts/macos/run_v2_frontend.sh`
  - Run frontend on Linux: `./scripts/linux/run_v2_frontend.sh`
  - Run backend on Windows: `./scripts/windows/run_v2_backend.ps1`
  - Run backend on macOS: `./scripts/macos/run_v2_backend.sh`
  - Run backend on Linux: `./scripts/linux/run_v2_backend.sh`
  - Run tests: `python -m pytest -q`
  - Run full macOS validation suite without GUI automation: `./scripts/macos/test_all.sh`
  - Build Windows release ZIP: `./scripts/windows/build_v2_release.ps1`
  - Build macOS arm64 release ZIP: `./scripts/macos/build_v2_release.sh`
  - Build Linux Debian release TAR.GZ: `./scripts/linux/build_v2_release.sh --distro debian`
  - Build Linux Arch release TAR.GZ: `./scripts/linux/build_v2_release.sh --distro arch`
  - Compatibility wrappers remain available in top-level `scripts/`

## Release & CI
- Distribution format:
  - Windows ZIP (portable, no installer)
  - macOS arm64 ZIP containing unsigned `.app`
  - Linux x64 TAR.GZ containing CPU-only app-image (`debian`, `arch`)
- Release trigger:
  - Merge a PR into `DEV` to produce an automatic GitHub pre-release.
  - Merge a PR into `main` to produce an automatic GitHub official release.
  - No manual tag push is required; `.github/workflows/release.yml` creates the release tag itself.
- Release versioning:
  - CI generates one shared `yy.MM.dd.NNN` version from the merged PR UTC timestamp plus the GitHub Actions `run_number`.
  - The generated version is passed explicitly to the Windows, macOS, Linux Debian and Linux Arch build scripts so all four artifacts share the same version.
- Build outputs:
  - `dist/windows/TranscribeMate/` (Windows app-image)
  - `dist/macos/TranscribeMate.app` (macOS app-image)
  - `dist/linux/debian/TranscribeMate/` (Linux Debian app-image)
  - `dist/linux/arch/TranscribeMate/` (Linux Arch app-image)
  - `dist/windows/TranscribeMate-<version>-win-x64.zip`
  - `dist/macos/TranscribeMate-<version>-macos-arm64.zip`
  - `dist/linux/TranscribeMate-<version>-linux-debian-x64.tar.gz`
  - `dist/linux/TranscribeMate-<version>-linux-arch-x64.tar.gz`
  - `dist/windows/checksums.txt` and `dist/macos/checksums.txt`
  - `dist/linux/checksums.txt`
  - `dist/checksums.txt` (combined SHA256, generated by release workflow)
- Main workflows:
  - `.github/workflows/ci.yml`
  - `.github/workflows/ci-stdio-smoke.yml`
  - `.github/workflows/ci-linux-packaging.yml`
  - `.github/workflows/lint-and-sanity.yml`
  - `.github/workflows/docs-and-contract.yml`
  - `.github/workflows/security.yml`
  - `.github/workflows/nightly-smoke.yml`
  - `.github/workflows/full-suite-macos.yml`
  - `.github/workflows/release.yml`

## Legal
- Disclaimer: [`DISCLAIMER.md`](DISCLAIMER.md)
- License: [`LICENSE`](LICENSE)
