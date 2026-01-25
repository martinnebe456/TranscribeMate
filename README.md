# TranscribeMate

TranscribeMate is a Windows desktop GUI for:
- downloading media from YouTube (optional, via `yt-dlp`),
- speech-to-text transcription (via `faster-whisper`),
- subtitle translation,
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

During installation, TranscribeMate starts GPU dependency setup (PyTorch). On every launch it re-checks Torch/CUDA and automatically repairs missing GPU dependencies while showing progress. This can take several minutes and requires internet access.

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

Temporary working folders named `_tm_work_*` are created inside your output directory and deleted automatically after each run.

## Developer Setup

### Requirements
- Python 3.12 (64-bit recommended)
- Inno Setup 6 (https://jrsoftware.org/isinfo.php)

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
