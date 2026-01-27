# TranscribeMate - Agent Guide

This file orients contributors and other agents to the codebase, runtime behavior,
and build pipeline. It is intentionally concise and points to the most important
modules and flows.

## Project summary
- Windows desktop GUI for audio/video transcription and subtitles.
- Optional YouTube download (yt-dlp), speech-to-text (faster-whisper),
  subtitle translation (transformers), and subtitle embedding (ffmpeg).
- Outputs transcripts, SRTs, subtitled videos, and summary packs.

## Entry points
- `main.py` / `app_gui.py` / `bootstrap.py` -> `transcribemate.runtime.launch_app()`.
- `transcribemate/runtime/runtime.py`:
  - Handles startup logging, FFmpeg download, and PyTorch install/repair for frozen builds.
  - Launches the Tkinter UI and shows a startup splash when frozen.
- `transcribemate/ui/ui.py`:
  - Main GUI (Tkinter + ttkbootstrap + TkinterDnD).
  - Orchestrates the end-to-end pipeline in a worker thread.

## Code map (where to look)
- `transcribemate/ui/ui.py`:
  - UI state, settings, progress UI, and pipeline orchestration.
  - Look at the `worker()` function in `start()` for the full flow.
- `transcribemate/pipeline/`:
  - `download.py` - yt-dlp download helpers.
  - `transcribe.py` - faster-whisper transcription and SRT/TXT output.
  - `translate.py` - subtitle translation via transformers.
  - `subtitles.py` - ffmpeg soft/hard subtitle embedding.
- `transcribemate/core/`:
  - `config.py` - config persistence and defaults.
  - `paths.py` - user data paths, asset discovery, PATH/PYTHONPATH setup.
  - `transcripts.py` - transcript rendering, summary pack, Confluence templates.
  - `files.py` - filename handling, media discovery, temp cleanup.
  - `i18n.py` - labels, languages, translation model map.
  - `gpu.py` - GPU detection and auto model choice.
  - `models.py` - cache location helpers and environment defaults.

## Runtime data and outputs
- User data dir:
  - Windows: `%LOCALAPPDATA%/TranscribeMate`
  - Other: `$XDG_DATA_HOME` or `~/.local/share/TranscribeMate`
- Config: `config.json` in the user data dir.
- Logs: `runtime.log` in the user data dir (startup and runtime bootstrap).
- Cached models:
  - `cache/huggingface` and `cache/whisper` in user data dir.
- Torch install (frozen EXE):
  - Per-user `site-packages` under the user data dir.
- Output root:
  - `<out_dir>/transcribemate_outputs/`
  - Subfolders: `transcripts/`, `summaries/`, `subtitles_source/`,
    `subtitles_translated/`, `videos/`, `originals/` (if enabled).
- Temporary work dirs:
  - `_tm_work_*` inside `<out_dir>`, cleaned after each run.

## Pipeline flow (UI)
1. Validate source (YouTube or local).
2. Ensure tools (ffmpeg, yt-dlp for YouTube).
3. Optionally download media (yt-dlp).
4. Transcribe with faster-whisper.
5. Export transcripts + optional summary pack.
6. If translation needed:
   - Translate SRT via transformers.
   - Export SRTs.
   - Embed subtitles (soft or hard).

## Build and packaging
- Build EXE:
  - `build_exe.ps1` uses PyInstaller with `TranscribeMate.spec`.
  - Torch packages are removed before build so runtime installs them on first run.
- Installer:
  - `installer/TranscribeMate.iss` builds an Inno Setup installer from `dist/`.
  - Default install path: `%LOCALAPPDATA%/Programs/TranscribeMate`.

## Common dev tasks
- Run from source: see `README.md`.
- Update UI labels, language lists, output mode labels:
  - `transcribemate/core/i18n.py`.
- Update config defaults:
  - `transcribemate/core/config.py`.
- Adjust output naming or transcript formats:
  - `transcribemate/core/files.py` and `transcribemate/core/transcripts.py`.

## Testing
No automated test suite in this repo. Changes are typically verified manually
via the GUI flow.

## Legal
See `DISCLAIMER.md` and `LICENSE`.

## Skills (future)
Always check the `skills/` directory first to see if a relevant skill exists and follow its `SKILL.md`.
