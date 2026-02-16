# TranscribeMate

TranscribeMate is a Windows desktop GUI for:
- downloading media from YouTube (optional, via `yt-dlp`),
- speech-to-text transcription (via `faster-whisper`),
- subtitle translation,
- optional speaker diarization with post-process speaker mapping (`*.diarization.json` sidecar),
- optional speaker-labeled SRT subtitles and batch speaker post-processing from sidecars,
- conference-style folder transcription to `.txt` / `.md`,
- exporting video with subtitles, `.srt`, timestamped transcripts, and summary packs for ChatGPT / Confluence.

## Important Legal Notice
Use this tool only with content you are legally allowed to process.

- The user is solely responsible for how the software is used.
- Any downloading and transcription must be done with the author’s / rights holder’s permission when required.
- Users must follow the laws of their country and the terms of the platforms involved (e.g., YouTube).

See `DISCLAIMER.md` for the full legal disclaimer (EN + CZ).

## End User Install (Recommended)
No Python installation is required for end users.

1. Download `TranscribeMate-Setup.exe` from Releases.
1. Run the installer.
1. Launch TranscribeMate from the Desktop or Start Menu shortcut.

During installation, setup installs speaker diarization dependencies automatically and can optionally install core deps/model prefetch so first launch is faster.
- On a fresh install, optional dependency tasks are preselected.
- On upgrades, optional dependency tasks default to off to avoid unnecessary redownloads.
- On every launch the app still validates dependencies and repairs missing parts if needed.

On first launch the app may:
- download FFmpeg,
- download speech/translation models,
- take longer than usual.

A startup window explains what is happening.

## Where Data Lives
The installed EXE stores writable data here:
- `%LOCALAPPDATA%/TranscribeMate`

This includes:
- `assets/` (FFmpeg),
- `cache/` (models),
- `runtime.log` (startup log),
- `config.json` (settings).
- A legacy `config.json` next to the EXE is migrated automatically.

There is a built-in button: **Open app data folder**.

## Outputs
The app saves outputs into:
- `transcribemate_outputs/transcripts`
- `transcribemate_outputs/summaries` (timestamped transcripts, summary prompts, Confluence templates)
- `transcribemate_outputs/subtitles_source`
- `transcribemate_outputs/subtitles_translated`
- `transcribemate_outputs/videos`
- `transcribemate_outputs/originals` (only if enabled)

When speaker diarization is enabled, transcript outputs include speaker prefixes and the app stores a diarization sidecar in `transcripts/` for later speaker-name post-processing (single file or batch folder mode).

Temporary working folders named `_tm_work_*` are created inside your output directory and deleted automatically after each run.

## Developer Setup

### Requirements
- Python 3.12 (64-bit recommended)
- Inno Setup 6 (https://jrsoftware.org/isinfo.php)

Optional speaker diarization requires:
- `pyannote.audio` Python package (included in `requirements.txt`; can also be installed via `requirements-diarization.txt`)
- `HF_TOKEN` with access to `pyannote/speaker-diarization-3.1`

If speaker diarization is enabled and `pyannote.audio` is missing, the app will attempt an automatic on-demand install into the per-user app data environment.

### Testing
```powershell
python -m pip install -r requirements-dev.txt
python -m pytest
```
Tests currently cover core helpers, i18n summary language helpers, pipeline utility functions, runtime version parsing, and transcript metadata formatting.

### Run From Source
```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1

python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python main.py
```

## Build EXE (Windows)
```powershell
./build_exe.ps1
```

If `requirements-diarization.txt` is present, the build script also attempts to install optional diarization dependencies so the speaker feature is available in the packaged EXE.

Output:
- `dist/TranscribeMate/TranscribeMate.exe`

Notes:
- The build script removes `assets/*.exe` before packaging to avoid bundling FFmpeg.
- FFmpeg will be downloaded on first run into `%LOCALAPPDATA%/TranscribeMate/assets`.

## Build Installer (Inno Setup)
1. Build the EXE first:
```powershell
./build_exe.ps1
```
1. Open `installer/TranscribeMate.iss` in Inno Setup and click **Build**.

Installer output:
- `dist_installer/TranscribeMate-Setup.exe`

The installer defaults to:
- `%LOCALAPPDATA%/Programs/TranscribeMate`

This avoids admin-rights issues and works well with per-user app data.

## Project Structure
The codebase is organized into typed components under `transcribemate/`:
- `runtime/` handles EXE startup, splash screen, and FFmpeg setup (`transcribemate/runtime/runtime.py`).
- `ui/` contains the GUI (`transcribemate/ui/ui.py`).
- `pipeline/` implements the processing pipeline (`transcribemate/pipeline/*.py`).
- `core/` contains shared services, paths, config, i18n, and file helpers (`transcribemate/core/*.py`).

## License
PolyForm Noncommercial 1.0.0 — see `LICENSE`.
