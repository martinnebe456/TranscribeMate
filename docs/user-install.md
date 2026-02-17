# User Install (Windows)

## What You Get
- Desktop app for transcription, translation, subtitles and optional speaker diarization.
- Portable ZIP distribution (no installer required).
- On first launch, the app performs mandatory online runtime setup:
  - embedded Python runtime
  - backend dependencies
  - default AI models
  - FFmpeg tools
- No system-wide Python installation is required.

## Install Steps
1. Download the latest release ZIP.
2. Extract ZIP to a local folder, for example `C:\Apps\TranscribeMate\`.
3. Run `TranscribeMate.exe`.
4. Confirm first-launch setup and wait until it finishes.
5. Restart app if prompted.

## Requirements
- Windows x64.
- Internet connection on first launch.
- Free disk space for runtime, dependencies and models (can be several GB).

## Verify Download Integrity
Release packages include `checksums.txt` (SHA256).

PowerShell example:
```powershell
Get-FileHash .\TranscribeMate-26.02.17.019-win-x64.zip -Algorithm SHA256
```

Compare the resulting hash with the line in `checksums.txt`.

## Troubleshooting
- Runtime setup log:
  - `%LOCALAPPDATA%\TranscribeMate\runtime-bootstrap.log`
- Application runtime log:
  - `%LOCALAPPDATA%\TranscribeMate\runtime.log`

If first-launch setup fails:
1. Review `runtime-bootstrap.log`.
2. Check firewall/proxy/network policy.
3. Start `TranscribeMate.exe` again and run runtime setup/repair.

## Runtime Data
- Root:
  - `%LOCALAPPDATA%\TranscribeMate`
- Typical content:
  - `config.json`
  - `runtime.log`
  - `runtime-bootstrap.log`
  - `runtime\python\`
  - `cache\huggingface`
  - `cache\whisper`
  - `assets\` (`ffmpeg`, `ffprobe`)

## Output Location
- Outputs are written into:
  - `<out_dir>\transcribemate_outputs\`
