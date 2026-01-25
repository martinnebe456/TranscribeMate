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
- Python 3.10–3.12 (recommended: 3.12)
- Windows (primarily tested)
- FFmpeg + FFprobe (must be available in PATH)

## Install FFmpeg (Required)
Windows (recommended):
```powershell
winget install Gyan.FFmpeg
```
Then restart the terminal/PC so `ffmpeg` and `ffprobe` are in PATH.

## Quick Start
The simplest option is the bootstrap script:

```bash
python bootstrap.py
```

What it does:
- creates `.venv`,
- installs dependencies from `requirements.txt`,
- launches the GUI.

## Manual Run

```bash
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/macOS:
source .venv/bin/activate

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

```powershell
./build_exe.ps1
```

## Notes
- On first run, models will be downloaded (Whisper / translation models).
- Some models require significant RAM/VRAM.
- Do not commit local configuration; use `config.example.json` as a reference.
- You can switch the UI language (EN/CZ) in the top-right corner.
- Conference mode is ideal for “record → transcribe everything in a folder”.
- Filenames are timestamped automatically, e.g., `2026-01-26_09-30_lecture.txt`.

## License
PolyForm Noncommercial 1.0.0 — see `LICENSE`.

---

# TranscribeMate (Česky)

TranscribeMate je desktopová aplikace pro:
- stažení médií z YouTube (volitelně, přes `yt-dlp`),
- přepis řeči do textu (přes `faster-whisper`),
- překlad titulků,
- export videa s titulky, `.srt` nebo čistého `.txt` přepisu.

## Důležité právní upozornění
Používejte pouze na obsah, ke kterému máte práva a souhlas.

- Odpovědnost za použití nese vždy pouze uživatel.
- Jakékoliv stahování a přepisování musí být se souhlasem autora / držitele práv, pokud je vyžadován.
- Uživatelé musí dodržovat zákony své země a podmínky platforem (např. YouTube).

Podrobněji viz `DISCLAIMER.md`.

## Výstupy
Aplikace ukládá výsledky do:
- `transcribemate_outputs/transcripts`
- `transcribemate_outputs/subtitles_source`
- `transcribemate_outputs/subtitles_translated`
- `transcribemate_outputs/videos`
- `transcribemate_outputs/originals` (jen pokud je zapnuto)

Dočasné pracovní složky `_tm_work_*` se po dokončení automaticky smažou.
