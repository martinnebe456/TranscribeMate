# User Install (Windows + macOS + Linux)

## What You Get
- Desktop app for transcription, translation, subtitles and optional speaker diarization.
- Portable ZIP distributions (no installer required).
- Portable TAR.GZ distributions for Linux x64.
- Linux x64 builds are CPU-only in this release.
- On first launch, the app performs mandatory online runtime setup:
  - managed Python runtime
  - backend dependencies
  - default AI models
  - FFmpeg tools
- No system-wide Python installation or Homebrew setup is required.

## Install Steps (Windows)
1. Download the latest Windows ZIP.
2. Extract ZIP to a local folder, for example `C:\Apps\TranscribeMate\`.
3. Run `TranscribeMate.exe`.
4. Confirm first-launch setup and wait until it finishes.
5. Restart the app if prompted.

## Install Steps (macOS Apple Silicon)
1. Download the latest macOS arm64 ZIP.
2. Extract it to a local folder, for example `~/Applications/TranscribeMate/`.
3. Open `TranscribeMate.app`.
4. Confirm first-launch setup and wait until it finishes.
5. Restart the app if prompted.

## Install Steps (Linux x64 Debian / Arch)
1. Download the matching Linux x64 TAR.GZ build for your distro (`debian` or `arch`).
2. Extract it to a local folder, for example `~/Apps/TranscribeMate/`.
3. Run `TranscribeMate/bin/TranscribeMate`.
4. Confirm first-launch setup and wait until it finishes.
5. Restart the app if prompted.

## Requirements
- Windows x64.
- macOS Apple Silicon (`arm64`) for the current macOS build.
- Linux `x86_64` for the current Debian / Arch builds.
- Internet connection on first launch.
- Free disk space for runtime, dependencies and models (can be several GB).

## Verify Download Integrity
Release packages include `checksums.txt` (SHA256).

Windows example:
```powershell
Get-FileHash .\TranscribeMate-26.02.17.019-win-x64.zip -Algorithm SHA256
```

macOS example:
```bash
shasum -a 256 ./TranscribeMate-26.02.17.019-macos-arm64.zip
```

Linux example:
```bash
sha256sum ./TranscribeMate-26.02.17.019-linux-debian-x64.tar.gz
```

Compare the resulting hash with the matching line in `checksums.txt`.

## Troubleshooting
- Runtime setup log:
  - `%LOCALAPPDATA%\TranscribeMate\runtime-bootstrap.log`
  - `~/Library/Application Support/TranscribeMate/runtime-bootstrap.log`
  - `${XDG_DATA_HOME:-~/.local/share}/TranscribeMate/runtime-bootstrap.log`
- Application logs (frontend, errors, backend stderr relay):
  - `%LOCALAPPDATA%\TranscribeMate\Logs\frontend-*.log`
  - `~/Library/Application Support/TranscribeMate/Logs/frontend-*.log`
  - `${XDG_DATA_HOME:-~/.local/share}/TranscribeMate/Logs/frontend-*.log`

If first-launch setup fails:
1. Review `runtime-bootstrap.log`.
2. Check firewall, proxy, or network policy.
3. Start the app again and run runtime setup or repair.

## Runtime Data
- Root:
  - `%LOCALAPPDATA%\TranscribeMate`
  - `~/Library/Application Support/TranscribeMate`
  - `${XDG_DATA_HOME:-~/.local/share}/TranscribeMate`
- Default project workspace root:
  - Windows: `%LOCALAPPDATA%\TranscribeMate\workspace`
  - macOS: `~/Documents/TranscribeMate/workspace`
  - Linux: `${XDG_DATA_HOME:-~/.local/share}/TranscribeMate/workspace`
- Typical content:
  - `config.json`
  - `Logs/frontend-*.log`
  - `runtime-bootstrap.log`
  - `runtime/python/`
  - `cache/huggingface`
  - `cache/whisper`
  - `assets/` (`ffmpeg`, `ffprobe`)

## Output Location
- Outputs are written into:
  - `<out_dir>/transcribemate_outputs/`
