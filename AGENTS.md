> AI MEMORY DIRECTIVE: Treat this file as the canonical long-term working memory for this repository. Read it at the start of every user request before planning, coding, or answering. Use it to recover durable project context, constraints, conventions, and important user preferences. If you learn a new durable fact that will help future work, update this file succinctly before finishing. Never store secrets, temporary debugging noise, or anything that conflicts with higher-priority system, developer, or user instructions.

# TranscribeMate - Agent Guide

## AI memory protocol
- Re-read this file at the beginning of every turn.
- Treat the contents as repository-specific memory, not as a replacement for higher-priority instructions.
- Prefer short, high-signal updates when storing new memory.
- Store only durable facts:
  - stable architectural decisions
  - important workflow rules
  - persistent user preferences relevant to this repository
  - non-obvious project constraints that are likely to matter again
- Do not store:
  - secrets, tokens, passwords, or private data
  - temporary task notes, one-off debugging logs, or ephemeral errors
  - anything that is likely to become stale quickly unless clearly marked and worth preserving
- When adding memory, integrate it into the most relevant section instead of appending random notes when possible.

This guide describes the current repository state (V2-only stack).

## Project summary
- Product goal: deliver a reliable, distributable Windows desktop app as an open-source project; prioritize the complete install-to-first-transcription experience.
- Desktop app architecture:
  - JavaFX frontend (`javafx-client/`)
  - Python backend (`transcribemate/v2/backend/`)
- Communication:
  - JSON lines over `stdin/stdout`
- Domain:
  - media transcription, translation, subtitle rendering, optional diarization

## Entry points
- Frontend (JavaFX):
  - Windows: `./scripts/windows/run_v2_frontend.ps1`
  - macOS: `./scripts/macos/run_v2_frontend.sh`
  - Linux: `./scripts/linux/run_v2_frontend.sh`
  - compatibility wrappers: `./scripts/run_v2_frontend.ps1`, `./scripts/run_v2_frontend.sh`
  - or `cd javafx-client && mvn javafx:run`
- Backend (Python):
  - Windows: `./scripts/windows/run_v2_backend.ps1`
  - macOS: `./scripts/macos/run_v2_backend.sh`
  - Linux: `./scripts/linux/run_v2_backend.sh`
  - compatibility wrappers: `./scripts/run_v2_backend.ps1`, `./scripts/run_v2_backend.sh`
  - or `python -m transcribemate.v2.backend.server --stdio`
- Release build (Windows ZIP):
  - `./scripts/windows/build_v2_release.ps1`
- Release build (macOS arm64 ZIP):
  - `./scripts/macos/build_v2_release.sh`
- Release build (Linux x64 TAR.GZ):
  - `./scripts/linux/build_v2_release.sh --distro debian`
  - `./scripts/linux/build_v2_release.sh --distro arch`

## Code map
- `javafx-client/src/main/java/com/transcribemate/v2/fx/`
  - `TranscribeMateApp.java` - app bootstrap
  - `MainController.java` - UI orchestration, preflight, jobs, logs
  - `BackendClient.java` - backend subprocess + JSON IPC
  - `SystemMonitorWindow.java` - CPU/RAM/GPU/VRAM charts
- `javafx-client/src/main/java/com/transcribemate/v2/fx/modules/`
  - `core/` - shared frontend module contracts
  - `registry/` - frontend module registry
  - `<module_id>/` - one independent frontend component per module
- `javafx-client/src/main/resources/com/transcribemate/v2/fx/`
  - `main-view.fxml` - primary layout
  - `styles.css` - visual theme
- `transcribemate/v2/backend/`
  - `server.py` - JSON-line server loop
  - `service.py` - methods, job lifecycle, preflight/system metrics
  - `models.py` - request validation/defaulting
  - `pipeline.py` - orchestration using shared pipeline/core modules
  - `protocol.py` - request/response/event structures
  - `diarization.py` - backend abstraction (`local_cluster_fast`, `local_cluster_accurate`)
- `transcribemate/v2/backend/components/`
  - `core/` - backend component contracts/adapters
  - `registry.py` - backend module registry
  - `<module_id>/` - one independent backend component package per module
- Shared backend dependencies:
  - `transcribemate/core/`
  - `transcribemate/pipeline/`
  - `transcribemate/pipeline/safe_youtube.py` - validates YouTube HTTPS source URLs and checks/pins public destinations for yt-dlp HTTPS requests and redirects
- Cancellation contract: shared helpers raise `OperationCancelledError` from `transcribemate/core/cancellation.py`; backend job cancellation and terminal assignment are serialized under the service lock, and subprocess output is drained on a reader thread so stop requests do not wait for a blocking `readline()`.
- Backend concurrency: one pipeline job may be queued/running at a time per backend process; additional `run_pipeline` requests fail with `job_limit_reached` (including across projects).

## Runtime data and outputs
- User data directory:
  - Windows: `%LOCALAPPDATA%/TranscribeMate`
  - macOS: `~/Library/Application Support/TranscribeMate`
  - Other: `$XDG_DATA_HOME` or `~/.local/share/TranscribeMate`
- Default workspace root:
  - Windows: `%LOCALAPPDATA%/TranscribeMate/workspace`
  - macOS: `~/Documents/TranscribeMate/workspace`
- Config: `config.json`
- Runtime log: `runtime.log`
- Cache: `cache/huggingface`, `cache/whisper`
- Runtime Python packages:
  - Windows: `runtime/python/Lib/site-packages/`
  - macOS: `runtime/python/lib/python*/site-packages/`
  - Linux: `runtime/python/lib/python*/site-packages/`
- Managed runtime interpreter:
  - Windows: `runtime/python/python.exe`
  - macOS: `runtime/python/bin/python3`
  - Linux: `runtime/python/bin/python3`
- Output root: `<out_dir>/transcribemate_outputs/`

## Tests
- Run with:
  - `python -m pip install -r requirements-dev.txt`
  - `python -m pytest`
- Full macOS validation runner:
  - `./scripts/macos/test_all.sh`
- Full Windows release-to-transcription runner:
  - `./scripts/windows/test_all.ps1`
- Tests include:
  - V2 backend protocol/models/service
  - selected shared core/pipeline helper tests
  - macOS-only heavy fixture regression over `tests/test_files/*.mp3` + paired PDF transcripts
  - manual macOS UI verification is separate; `scripts/macos/test_all.sh` does not automate GUI clicks

## Build/packaging note
- Legacy Tkinter/PyInstaller flow was removed from this branch.
- Inno installer flow is removed.
- Platform scripts are organized under `scripts/windows/`, `scripts/macos/`, `scripts/linux/` and `scripts/shared/`; top-level `scripts/*` entrypoints are compatibility wrappers.
- `scripts/windows/build_v2_release.ps1` builds the Windows app-image via `jpackage` and then creates a distributable ZIP package.
- `scripts/macos/build_v2_release.sh` builds the macOS arm64 `.app` via `jpackage`, bundles pinned runtime assets, and then creates a distributable ZIP package.
- `scripts/linux/build_v2_release.sh --distro debian|arch` builds Linux x64 app-images via `jpackage`, bundles pinned runtime assets, and then creates distro-scoped TAR.GZ packages.
- Runtime policy is now platform-scoped: Windows release keeps CUDA/NVIDIA GPU provisioning; macOS release stays CPU-focused; Linux release is CPU-only and normalizes `prefer_gpu` to `false`.
- CI/release automation is host-OS scoped: the release workflow builds Windows artifacts on `windows-latest`, macOS artifacts on `macos-15`, and Linux Debian/Arch artifacts on `ubuntu-latest` via distro containers.
- Scheduled/manual Windows full-suite builds and extracts the release ZIP, runs its first-launch runtime bootstrap, validates real audio/video outputs through the packaged backend's JSON-lines server, and confirms the packaged UI loads transcript files from the app workspace.
- Release CI acceptance for macOS arm64, Debian 12 x64, and Arch x64 must extract the actual archive, verify its SHA-256, bootstrap isolated app data, transcribe the approved `audio-sample-2` fixture through the packaged JSON-lines backend, launch packaged JavaFX, and upload per-platform reports/logs; interactive first-run clicks have a named manual checklist in `docs/release/package-first-transcription-acceptance.md`.
- Package acceptance bootstrap must pass `--with-models`: JavaFX requires Whisper `large-v3` and `Helsinki-NLP/opus-mt-en-cs` even when the fixture uses `small` and skips translation/summary. A skipped video smoke run is represented by JSON `null` in the fixture report.
- PR/manual/nightly CI now includes backend pytest on Windows/macOS/Linux, frontend Maven tests plus package build on Windows/macOS/Linux, and a reusable cross-OS backend stdio smoke workflow.
- Path-gated PR CI must still report every branch-protection-required check: always invoke reusable workflows and conditionally skip their matrix jobs through `workflow_call` inputs, since a skipped job reports success but a skipped workflow leaves required checks pending.
- Linux container packaging jobs need distro-native GUI/runtime libraries plus `binutils`/`objcopy` installed before `actions/setup-java`; the workflows now verify `java -version` and `jpackage --version` explicitly so minimal-container failures surface early.
- GitHub releases are merge-driven now: merged PRs into `DEV` create automatic prereleases tagged `dev-<yy.MM.dd.NNN>`, and merged PRs into `main` create automatic official releases tagged `v<yy.MM.dd.NNN>`.
- Release CI generates one shared version per merge from the PR `merged_at` timestamp in UTC plus the GitHub Actions `run_number`, then passes it explicitly into all 4 OS build scripts so artifact versions stay aligned.
- Release/version filenames stay on the shared `yy.MM.dd.NNN` format; the macOS build script derives a separate `jpackage`-compatible 3-segment app metadata version internally.
- Build outputs are OS-scoped under a single root: `dist/windows`, `dist/macos`, `dist/linux`.
- Each local build writes a platform-local `checksums.txt`; CI release publishing additionally writes a combined top-level `dist/checksums.txt`.
- ZIP distribution entry points:
  - Windows: `TranscribeMate.exe`
  - macOS: `TranscribeMate.app`
- TAR.GZ distribution entry points:
  - Linux: `TranscribeMate/bin/TranscribeMate`
- Runtime bootstrap is platform-specific on first app launch:
  - Windows: `scripts/windows/bootstrap_runtime.ps1`
  - macOS: `scripts/macos/bootstrap_runtime.sh`
  - Linux: `scripts/linux/bootstrap_runtime.sh`
- Runtime dependency constraint: keep PyAV below 19 until faster-whisper stops passing the removed `metadata_errors` argument to `av.open`.
- Keep Transformers at 5.10.0 or newer with `huggingface_hub>=1.5,<2` to avoid known vulnerabilities; keep pytest at 9.0.3 or newer for the Unix tmpdir fix.
- Windows acceptance reruns can pass `-AppDataDir <existing path>` to reuse a previously bootstrapped runtime and model cache.
- Windows first-launch bootstrap pins the embedded Python, pip bootstrap wheel, and FFmpeg archive by version and repository-recorded SHA-256; update each version, URL, and digest together. The Windows acceptance runner checks corrupt-artifact rejection and the clean bootstrap runtime marker.
- macOS dev-mode note:
  - when `TM_PROJECT_ROOT` is set and bundled runtime assets are absent, `scripts/macos/bootstrap_runtime.sh` now uses a development fallback (project `.venv` + app-data FFmpeg install) instead of requiring release-bundled assets.
  - direct `mvn javafx:run` from `javafx-client/` should now auto-detect the repository backend and `.venv`; helper scripts still set `TM_PROJECT_ROOT` explicitly for predictable dev runs.
  - `TM_APP_DATA_DIR` is now a shared override honored by Python path resolution, JavaFX path resolution, and runtime bootstrap scripts; the new macOS full-suite uses it to isolate app-data/workspace/logs per run.

## Code Review Rules

### Release package acceptance
- Do not treat a successful backend transcription alone as proof that a release package is ready. The first-run bootstrap and packaged JavaFX transcript display are part of the same acceptance flow.
  Safe path: verify bootstrap installs the default models required by JavaFX, then confirm the packaged app displays the generated transcript in `tests/full_suite/run_extracted_package_acceptance.py`.

## Legal
- Project source license: MIT (`LICENSE`); release app images include `LICENSE.txt` at their root.
- `DISCLAIMER.md`
- `LICENSE`

## Skills
Always check `skills/` first and follow relevant `SKILL.md` when applicable.
