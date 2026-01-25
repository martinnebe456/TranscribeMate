# TranscribeMate

TranscribeMate is a desktop GUI for:
- downloading media from YouTube (optional, via `yt-dlp`),
- speech-to-text transcription (via `faster-whisper`),
- subtitle translation,
- conference-style folder transcription to `.txt` / `.md`,
- exporting video with subtitles, `.srt`, and timestamped transcripts.

Czech documentation is included below.

## Important Legal Notice
Use this tool only with content you are legally allowed to process.

- The user is solely responsible for how the software is used.
- Any downloading and transcription must be done with the author’s / rights holder’s permission when required.
- Users must follow the laws of their country and the terms of the platforms involved (e.g., YouTube).

See `DISCLAIMER.md` for the full legal disclaimer (EN + CZ).

## Requirements
- Python 3.10–3.12 (recommended: 3.12; Python 3.13+ is not supported)
- Windows (primarily tested)

For building the installer:
- Inno Setup 6 (https://jrsoftware.org/isinfo.php)

## FFmpeg
On Windows, `bootstrap.py` can download FFmpeg automatically into `assets/`.

Manual install (optional):
```powershell
winget install Gyan.FFmpeg
```
Then restart the terminal/PC so `ffmpeg` and `ffprobe` are in PATH.

## Quick Start
The simplest option is the bootstrap script:

```powershell
py -3.12 bootstrap.py
```

Useful options:
```powershell
py -3.12 bootstrap.py --doctor
py -3.12 bootstrap.py --no-cuda
py -3.12 bootstrap.py --no-ffmpeg-download
```

`bootstrap.log` is written in the project root for easier troubleshooting.

What it does:
- creates `.venv` when missing,
- installs dependencies from `requirements.txt` (even if `.venv` already exists),
- ensures FFmpeg is available,
- launches the GUI.

## Manual Run

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1

python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python app_gui.py
```

## Outputs
The app saves outputs into:
- `transcribemate_outputs/transcripts`
- `transcribemate_outputs/subtitles_source`
- `transcribemate_outputs/subtitles_translated`
- `transcribemate_outputs/videos`
- `transcribemate_outputs/originals` (only if enabled)

Temporary working folders named `_tm_work_*` are created inside your output directory and deleted automatically after each run.

## Build EXE (Windows)

Build the onedir EXE (bootstrap is the entrypoint):

```powershell
./build_exe.ps1
```

Output:
- `dist/TranscribeMate/TranscribeMate.exe`

Notes:
- The build script removes `assets/*.exe` before packaging to avoid bundling FFmpeg.
- FFmpeg will be downloaded on first run.

## Build Installer (Inno Setup)

1. Build the EXE first:
```powershell
./build_exe.ps1
```
2. Open `installer/TranscribeMate.iss` in Inno Setup and click **Build**.

Installer output:
- `dist_installer/TranscribeMate-Setup.exe`

The installer creates:
- a Start Menu entry,
- an optional Desktop shortcut,
- and can launch the app right after installation.

## Notes
- Installed EXE stores config, logs, and FFmpeg in `%LOCALAPPDATA%/TranscribeMate`.
- The installer defaults to `%LOCALAPPDATA%/Programs/TranscribeMate` (no admin required).
- First launch can take longer; a startup window explains what is happening.
- On first run, models will be downloaded (Whisper / translation models).
- Some models require significant RAM/VRAM.
- Do not commit local configuration; use `config.example.json` as a reference.
- You can switch the UI language (EN/CZ) in the top-right corner.
- Conference mode is ideal for “record → transcribe everything in a folder”.
- Filenames are timestamped automatically, e.g., `2026-01-26_09-30_lecture.txt`.

## License
PolyForm Noncommercial 1.0.0 — see `LICENSE`.

---
