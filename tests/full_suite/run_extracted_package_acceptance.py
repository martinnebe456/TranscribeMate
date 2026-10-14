#!/usr/bin/env python3
"""Accept a freshly extracted macOS/Linux release package through its backend and JavaFX UI."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
PROJECT_ID = "offline-fixtures"
FIXTURE_ID = "audio-sample-2"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--checksum-file", required=True, type=Path)
    parser.add_argument(
        "--platform",
        required=True,
        choices=("macos-arm64", "linux-debian-x64", "linux-arch-x64"),
    )
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--fixtures-dir", type=Path, default=ROOT / "tests" / "test_files")
    return parser.parse_args()


def run_stage(name: str, command: list[str], *, env: dict[str, str], cwd: Path, log_path: Path) -> None:
    print(f"[package-acceptance] {name}: {' '.join(command)}", flush=True)
    with log_path.open("w", encoding="utf-8") as log:
        completed = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=False,
        )
    if completed.returncode:
        raise RuntimeError(f"{name} failed ({completed.returncode}); see {log_path}")


def checksum_for_archive(archive: Path, checksum_file: Path) -> str:
    expected = None
    for line in checksum_file.read_text(encoding="utf-8").splitlines():
        fields = line.strip().split(maxsplit=1)
        if len(fields) != 2:
            continue
        name = fields[1].lstrip("* ")
        if Path(name).name == archive.name:
            expected = fields[0].lower()
            break
    if expected is None:
        raise ValueError(f"{archive.name} is not listed in {checksum_file}.")
    hasher = hashlib.sha256()
    with archive.open("rb") as archive_stream:
        for chunk in iter(lambda: archive_stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    if digest != expected:
        raise ValueError(f"SHA-256 mismatch for {archive.name}: expected {expected}, got {digest}.")
    return digest


def safe_text_logs(app_data: Path) -> str:
    logs_dir = app_data / "Logs"
    if not logs_dir.is_dir():
        return ""
    return "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in sorted(logs_dir.glob("frontend-*.log"))
    )


def stop_process(process: subprocess.Popen[bytes] | None) -> None:
    if process is None or process.poll() is not None:
        return
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGTERM)
        else:
            process.terminate()
        process.wait(timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        if os.name == "posix":
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except OSError:
                pass
        else:
            process.kill()
        process.wait(timeout=15)


def main() -> int:
    args = parse_args()
    archive = args.archive.resolve()
    checksum_file = args.checksum_file.resolve()
    artifact_dir = args.artifact_dir.resolve()
    fixtures_dir = args.fixtures_dir.resolve()
    artifact_dir.mkdir(parents=True, exist_ok=True)
    report_path = artifact_dir / "acceptance-report.json"
    report: dict[str, Any] = {
        "check": "Clean Extracted Package First Transcription Acceptance",
        "platform": args.platform,
        "archive": str(archive),
        "status": "running",
        "steps": {},
    }
    app_process: subprocess.Popen[bytes] | None = None

    try:
        if not archive.is_file() or not checksum_file.is_file():
            raise FileNotFoundError("Release archive or platform checksum file is missing.")
        archive_sha256 = checksum_for_archive(archive, checksum_file)
        report["archive_sha256"] = archive_sha256
        report["steps"]["archive_sha256"] = "passed"

        extracted = artifact_dir / "extracted"
        app_data = artifact_dir / "app-data"
        if extracted.exists() or app_data.exists():
            raise FileExistsError("Use a clean artifact directory; extracted/ and app-data/ must not exist.")
        extracted.mkdir()
        app_data.mkdir()
        report["steps"]["empty_app_data_before_first_launch"] = "passed"

        if args.platform == "macos-arm64":
            app_bundle = extracted / "TranscribeMate.app"
            subprocess.run(["ditto", "-x", "-k", str(archive), str(extracted)], check=True)
            backend = app_bundle / "Contents" / "app" / "backend"
            app_entry = app_bundle / "Contents" / "MacOS" / "TranscribeMate"
            bootstrap = backend / "bootstrap_runtime.sh"
            managed_python = app_data / "runtime" / "python" / "bin" / "python3"
        else:
            subprocess.run(["tar", "-xzf", str(archive), "-C", str(extracted)], check=True)
            app_image = extracted / "TranscribeMate"
            backend = app_image / "lib" / "app" / "backend"
            app_entry = app_image / "bin" / "TranscribeMate"
            bootstrap = backend / "bootstrap_runtime.sh"
            managed_python = app_data / "runtime" / "python" / "bin" / "python3"

        if not app_entry.is_file() or not bootstrap.is_file():
            raise FileNotFoundError(f"Extracted package is missing its JavaFX entry or bundled backend: {app_entry}")
        report["steps"]["clean_archive_extraction"] = "passed"

        env = os.environ.copy()
        env["TM_APP_DATA_DIR"] = str(app_data)
        env["PYTHONUTF8"] = "1"
        env["PYTHONIOENCODING"] = "utf-8"
        env["HF_HUB_DISABLE_TELEMETRY"] = "1"
        for key in ("TM_PROJECT_ROOT", "TM_BACKEND_ROOT", "TM_BACKEND_CMD", "TM_BACKEND_PYTHON"):
            env.pop(key, None)

        run_stage(
            "first-run runtime bootstrap",
            [
                "bash",
                str(bootstrap),
                "--backend-root",
                str(backend),
                # JavaFX requires the default model pack even for a transcript-only acceptance run.
                "--with-models",
                "--download-ffmpeg",
                "--log-file",
                str(artifact_dir / "runtime-bootstrap.log"),
                "--progress-file",
                str(artifact_dir / "runtime-progress.txt"),
            ],
            env=env,
            cwd=backend,
            log_path=artifact_dir / "runtime-bootstrap-command.log",
        )
        if not managed_python.is_file():
            raise FileNotFoundError(f"First-run bootstrap did not install managed Python: {managed_python}")
        report["steps"]["first_run_runtime_bootstrap"] = "passed"

        run_stage(
            "install fixture report dependency",
            [str(managed_python), "-m", "pip", "install", "pypdf>=6.8.0,<7"],
            env=env,
            cwd=backend,
            log_path=artifact_dir / "fixture-validator-install.log",
        )
        source_manifest = json.loads((fixtures_dir / "manifest.json").read_text(encoding="utf-8"))
        approved_fixture = next(
            (item for item in source_manifest["fixtures"] if item.get("id") == FIXTURE_ID),
            None,
        )
        if approved_fixture is None:
            raise ValueError(f"Approved fixture {FIXTURE_ID!r} is missing from the fixture manifest.")
        selected_manifest = dict(source_manifest)
        selected_manifest["fixtures"] = [approved_fixture]
        manifest_path = artifact_dir / "approved-fixture-manifest.json"
        manifest_path.write_text(json.dumps(selected_manifest, indent=2) + "\n", encoding="utf-8")

        projects_root = app_data / "workspace" / "projects"
        fixture_report_dir = artifact_dir / "fixture-transcription"
        run_stage(
            "transcribe approved fixture through packaged JSON-lines backend",
            [
                str(managed_python),
                str(ROOT / "tests" / "full_suite" / "run_fixture_regression.py"),
                "--fixtures-dir",
                str(fixtures_dir),
                "--manifest",
                str(manifest_path),
                "--artifact-dir",
                str(fixture_report_dir),
                "--app-data-dir",
                str(app_data),
                "--backend-root",
                str(backend),
                "--projects-root",
                str(projects_root),
                "--stdio-server",
                "--fixture-id",
                FIXTURE_ID,
                "--skip-video-smoke",
                "--skip-summary",
                "--transcript-only",
            ],
            env=env,
            cwd=ROOT,
            log_path=artifact_dir / "packaged-backend-transcription.log",
        )
        project_root = projects_root / PROJECT_ID
        transcripts = sorted(
            path for path in (project_root / "output").rglob("*.txt") if path.is_file() and path.stat().st_size > 0
        )
        if not transcripts:
            raise RuntimeError(f"Packaged backend completed without a transcript under {project_root / 'output'}.")
        report["steps"]["approved_fixture_import_and_transcription"] = "passed"
        report["fixture_id"] = FIXTURE_ID
        report["project_id"] = PROJECT_ID
        report["transcript"] = str(transcripts[0])

        now = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
        workspace_root = app_data / "workspace"
        project_json = {
            "version": 1,
            "active_project_id": PROJECT_ID,
            "projects": [
                {
                    "project_id": PROJECT_ID,
                    "name": "Packaged First Transcription Acceptance",
                    "root_dir": str(project_root),
                    "created_at": now,
                    "updated_at": now,
                    "last_opened_at": now,
                }
            ],
        }
        (workspace_root / "workspace.json").write_text(json.dumps(project_json, indent=2) + "\n", encoding="utf-8")

        env["TM_UI_TEST_MODE"] = "1"
        ui_command = [str(app_entry)]
        if args.platform.startswith("linux-"):
            ui_command = ["xvfb-run", "-a", "--server-args=-screen 0 1280x900x24", *ui_command]
        app_log = artifact_dir / "packaged-javafx-launch.log"
        with app_log.open("w", encoding="utf-8") as log:
            app_process = subprocess.Popen(
                ui_command,
                cwd=app_entry.parents[2] if args.platform == "macos-arm64" else app_entry.parents[1],
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=(os.name == "posix"),
            )

        deadline = time.monotonic() + 120
        ui_text = ""
        while time.monotonic() < deadline:
            if app_process.poll() is not None:
                raise RuntimeError(f"Packaged JavaFX app exited during startup ({app_process.returncode}); see {app_log}.")
            ui_text = safe_text_logs(app_data)
            if (
                "Backend process started (pid=" in ui_text
                and "Primary stage displayed." in ui_text
                and re.search(
                    rf"ui\.test\.files\.loaded.*project_id={PROJECT_ID}.*transcript_count=1.*visible_transcript_count=1.*visible_output_transcript_count=1",
                    ui_text,
                    re.DOTALL,
                )
            ):
                break
            time.sleep(2)
        else:
            raise RuntimeError("Packaged JavaFX did not display the project transcript within 120 seconds.")
        report["steps"]["packaged_javafx_displayed_completed_transcript"] = "passed"
        report["ui_log_excerpt"] = "\n".join(
            line for line in ui_text.splitlines()
            if "Backend process started" in line
            or "Primary stage displayed." in line
            or "ui.test.files.loaded" in line
        )
        report["status"] = "passed"
        report["manual_ui_actions"] = "Create/select/import button interaction remains a named manual check; CI confirms the packaged app displays the completed output."
        print(json.dumps(report, indent=2), flush=True)
        return 0
    except Exception as exc:
        report["status"] = "failed"
        report["error"] = str(exc)
        raise
    finally:
        stop_process(app_process)
        report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
