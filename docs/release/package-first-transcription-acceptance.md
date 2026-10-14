# Clean Extracted Package First Transcription Acceptance

Run this named manual check for release packages on Windows x64, macOS arm64,
Debian 12 x64, and Arch x64 when validating the interactive first-run path.
Release CI runs `tests/full_suite/run_extracted_package_acceptance.py` for the
macOS and Linux packages. That check extracts the archive, bootstraps isolated
app data, transcribes the approved `audio-sample-2` fixture through the packaged
JSON-lines backend, and confirms the packaged JavaFX app displays the completed
transcript. Windows release CI builds the package, and the scheduled Windows
full-suite covers its backend and files view, but it bootstraps separately and
prepares project metadata itself. Run this manual check on Windows to cover
first setup and user-driven project, import, and job actions.

The manual check covers the user actions that hosted CI cannot click through:
runtime setup in the app, project creation/selection, fixture import, and
starting/observing a transcription in the app.

## Run

1. Download the platform archive and its matching `checksums.txt` from the
   release CI artifact or release report. Record the archive filename and
   SHA-256 in the evidence report.
2. Extract the archive into a new, empty directory outside any source checkout.
   Create a separate, empty app-data directory and set `TM_APP_DATA_DIR` to its
   absolute path before launching the packaged entry point. On Windows, extract
   the ZIP so the `TranscribeMate` app-image directory is visible, then launch
   `TranscribeMate\TranscribeMate.exe`. Do not run the package from inside the
   repository: development Python environments in parent directories can mask
   the release first-launch flow. Clear development overrides (`TM_PROJECT_ROOT`,
   `TM_BACKEND_ROOT`, `TM_BACKEND_CMD`, and `TM_BACKEND_PYTHON`) for the launch.
   Other packaged entry points are:
   - macOS: `TranscribeMate.app/Contents/MacOS/TranscribeMate`
   - Linux: `TranscribeMate/bin/TranscribeMate`
3. Confirm the app-data directory is empty before launch. Complete runtime
   setup in the app by accepting **Start setup** in the first-launch dialog;
   when setup reports that a restart is required, close the app and launch the
   packaged entry point again. Wait until the main window reports the backend
   connected.
4. Create a project named `Manual First Transcription Acceptance`, then select
   it as the active project through the app's Projects UI. Confirm the project
   appears in the project list/gallery and do not edit `workspace.json` yourself.
5. In the project workflow, choose **Offline A/V Transcript** and import
   `tests/test_files/audio-sample-2.mp3` from the repository. This file
   is listed in `tests/test_files/manifest.json` as an approved fixture.
6. Start transcription from the app's workflow and accept the required
   preflight. Do not submit a backend request or invoke a separate fixture
   runner. In **Operations**, wait for the job to show completed and capture
   the result in the app.
7. Open **Project Files**, confirm the generated `.txt` transcript is present,
   then open the project's output folder from the app. Record the transcript's
   path and non-zero size. It must be under that project's `output` directory.
8. Save the evidence below. Include a screenshot showing the completed job and
   transcript listing in the JavaFX app, plus the frontend, runtime bootstrap,
   and backend job logs from the isolated app-data directory.

## Evidence report

Save one report per platform beside its logs and screenshots. Include:

- Check name: `Clean Extracted Package First Transcription Acceptance`
- Result: `PASS` or `FAIL`
- OS version, architecture, package filename, and SHA-256
- Confirmation that extraction and app data were clean before launch
- Runtime setup result, project name/ID, imported fixture, and job completion
- Transcript path under the project `output` directory and file size
- Paths to the app screenshot, frontend log, bootstrap log, and backend job log
- Any failure details or deviations

Keep reports under `artifacts/package-acceptance/<platform>/` and attach them
to the corresponding release acceptance run or issue. A platform passes only
when all assertions above are evidenced.
