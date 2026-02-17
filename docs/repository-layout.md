# Repository Layout

This document summarizes the current folder structure after the V2 component refactor.

## Top-level

- `javafx-client/`: JavaFX frontend sources, resources, Maven build
- `transcribemate/`: Python package (V2 backend + shared core/pipeline modules)
- `scripts/`: bootstrap and runtime helper scripts
- `tests/`: Python test suite and testing notes
- `docs/`: architecture, protocol, user/dev docs
- `.github/workflows/`: CI/CD workflows

## Frontend module components

Path: `javafx-client/src/main/java/com/transcribemate/v2/fx/modules/`

- `core/`: shared interfaces and base class
- `registry/`: component registration
- `offline_transcribe/`
- `youtube_transcribe/`
- `speaker_transcribe/`
- `conference_mode/`
- `youtube_subtitles/`
- `youtube_dub/`

Each module folder contains:
- the module component class
- `README.md` with module-specific defaults and UI flow

## Backend module components

Path: `transcribemate/v2/backend/components/`

- `core/`: component contracts, static component helper, shared pipeline adapter
- `registry.py`: component lookup/ordering
- `offline_transcribe/`
- `youtube_transcribe/`
- `speaker_transcribe/`
- `conference_mode/`
- `youtube_subtitles/`
- `youtube_dub/`

Each module folder contains:
- `component.py` as module entry point
- `__init__.py` export
- `README.md` with runtime profile and request contract
