#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from queue import Empty, Queue
from threading import Event, Thread
import time
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run real audio/video fixture regression over MP3+PDF fixture pairs.")
    parser.add_argument("--fixtures-dir", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--artifact-dir", required=True)
    parser.add_argument("--app-data-dir", default="")
    parser.add_argument("--backend-root", default=str(ROOT))
    parser.add_argument("--projects-root", default="", help="Optional root for project input/output trees.")
    parser.add_argument("--fixture-id", default="", help="Run only this fixture ID from the approved manifest.")
    parser.add_argument("--skip-video-smoke", action="store_true", help="Skip the generated video_subs smoke run.")
    parser.add_argument("--skip-summary", action="store_true", help="Disable optional AI summary generation.")
    parser.add_argument("--transcript-only", action="store_true", help="Validate only non-empty transcript outputs, not PDF similarity or summaries.")
    parser.add_argument(
        "--stdio-server",
        action="store_true",
        help="Run jobs through the bundled backend's JSON-lines server instead of calling its pipeline in-process.",
    )
    return parser.parse_args()


def make_logger(log_path: Path):
    log_path.parent.mkdir(parents=True, exist_ok=True)

    def _log(message: str) -> None:
        text = str(message).rstrip()
        line = text if text else ""
        if line:
            print(line)
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")

    return _log


class JsonLineBackendClient:
    """Small synchronous client for the desktop app's packaged JSON-lines backend."""

    def __init__(self, *, backend_root: Path, app_data_dir: Path, stderr_path: Path, log):
        self.backend_root = backend_root
        self.log = log
        self.lines: Queue[str | None] = Queue()
        self.request_index = 0
        self.stderr_handle = stderr_path.open("w", encoding="utf-8")
        env = os.environ.copy()
        env["TM_APP_DATA_DIR"] = str(app_data_dir)
        env["PYTHONUTF8"] = "1"
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONPATH"] = str(backend_root)
        self.process = subprocess.Popen(
            [sys.executable, "-m", "transcribemate.v2.backend.server", "--stdio"],
            cwd=backend_root,
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.stderr_handle,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        self.reader = Thread(target=self._read_stdout, name="tm-json-line-reader", daemon=True)
        self.reader.start()
        try:
            self.request("ping", {})
        except Exception:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait()
            self.stderr_handle.close()
            raise

    def _read_stdout(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            self.lines.put(line)
        self.lines.put(None)

    def request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        if self.process.poll() is not None:
            raise RuntimeError(f"Packaged backend exited with code {self.process.returncode}.")
        assert self.process.stdin is not None
        self.request_index += 1
        request_id = f"fixture-{self.request_index}"
        request = {"type": "request", "id": request_id, "method": method, "params": params}
        self.process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
        self.process.stdin.flush()

        deadline = time.monotonic() + 180
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"Timed out waiting for backend method '{method}'.")
            try:
                line = self.lines.get(timeout=min(remaining, 5))
            except Empty:
                if self.process.poll() is not None:
                    raise RuntimeError(f"Packaged backend exited with code {self.process.returncode} during '{method}'.")
                continue
            if line is None:
                raise RuntimeError(f"Packaged backend closed stdout during '{method}'.")
            try:
                message = json.loads(line)
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Packaged backend wrote non-JSON stdout: {line[:300]!r}") from exc
            if message.get("type") == "event":
                event = str(message.get("event") or "")
                payload = message.get("payload") or {}
                if event == "job.log":
                    self.log(f"[backend] {payload.get('line', '')}")
                elif event in {"job.started", "job.completed", "job.failed", "job.cancelled"}:
                    self.log(f"[backend event] {event} job_id={message.get('job_id', '')}")
                continue
            if message.get("type") != "response" or message.get("id") != request_id:
                raise RuntimeError(f"Unexpected backend response while waiting for '{method}': {message!r}")
            if not message.get("ok"):
                error = message.get("error") or {}
                raise RuntimeError(
                    f"Packaged backend method '{method}' failed: "
                    f"{error.get('code', 'unknown_error')}: {error.get('message', 'no message')}; "
                    f"details={error.get('details', {})!r}"
                )
            return message.get("result") or {}

    def run_pipeline(self, payload: dict[str, Any], *, progress_path: Path) -> dict[str, Any]:
        preflight = self.request("preflight_check", payload)
        if not preflight.get("ok"):
            failures = [
                f"{item.get('name', 'check')}: {item.get('message', '')}"
                for item in preflight.get("checks", [])
                if item.get("status") == "fail"
            ]
            raise RuntimeError("Packaged backend preflight failed: " + "; ".join(failures))
        self.log("[backend] preflight passed")
        started = self.request("run_pipeline", payload)
        job_id = str(started.get("job_id") or "")
        if not job_id:
            raise RuntimeError(f"Packaged backend accepted a job without returning its ID: {started!r}")
        self.log(f"[backend] job accepted: {job_id}")
        progress_path.parent.mkdir(parents=True, exist_ok=True)
        deadline = time.monotonic() + 4 * 60 * 60
        while time.monotonic() < deadline:
            snapshot = self.request("get_job", {"job_id": job_id})
            progress_path.write_text(json.dumps(snapshot.get("progress") or {}, indent=2), encoding="utf-8")
            status = str(snapshot.get("status") or "")
            if status == "completed":
                return snapshot.get("result") or {}
            if status in {"failed", "cancelled"}:
                raise RuntimeError(f"Packaged backend job {job_id} ended as {status}: {snapshot.get('error')}")
            time.sleep(2)
        raise TimeoutError(f"Packaged backend job {job_id} did not finish within four hours.")

    def close(self) -> None:
        if self.process.poll() is None:
            try:
                self.request("shutdown", {})
                self.process.wait(timeout=15)
            except Exception:
                self.process.terminate()
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait()
        self.stderr_handle.close()


def copy_manifest_fixtures(manifest: dict[str, Any], fixtures_dir: Path, target_dir: Path) -> list[Path]:
    copied: list[Path] = []
    target_dir.mkdir(parents=True, exist_ok=True)
    for fixture in manifest["fixtures"]:
        source_name = str(fixture["source"])
        source_path = fixtures_dir / source_name
        target_path = target_dir / source_path.name
        shutil.copy2(source_path, target_path)
        copied.append(target_path)
    return copied


def build_project_payload(*, project_root: Path, source_path: Path, output_mode: str, summary_enabled: bool) -> dict[str, Any]:
    return {
        "module": "offline_transcribe",
        "source": {
            "mode": "local",
            "path": str(source_path),
        },
        "output": {
            "mode": output_mode,
            "out_dir": str(project_root / "output"),
            "output_prefix": "",
            "keep_originals": False,
        },
        "transcription": {
            "model": "small",
            "auto_model": False,
            "prefer_gpu": False,
            "source_lang": "auto",
            "batch_size": 8,
        },
        "translation": {
            "enabled": False,
            "target_lang": "en->cs",
        },
        "subtitles": {
            "mode": "soft",
            "font": "Arial",
            "size": 24,
            "color": "#FFFFFF",
            "outline_color": "#000000",
            "outline_width": 2,
        },
        "text": {
            "clean_text": False,
            "export_md": False,
            "summary_pack": False,
            "split_minutes": 0,
            "summary_lang": "cs",
            "summary_ai_enabled": summary_enabled,
            "summary_ai_tier": "low",
            "speaker": "",
            "topic": "",
        },
        "diarization": {
            "enabled": False,
            "backend": "local_cluster_accurate",
            "accuracy_profile": "balanced",
            "min_speakers": 0,
            "max_speakers": 0,
            "review_after_file": False,
            "include_unmapped_speakers": True,
            "speaker_prefix_in_srt": True,
            "profile_prefill": True,
            "speaker_profiles": {},
        },
        "project": {
            "project_id": project_root.name,
            "name": project_root.name,
            "root_dir": str(project_root),
            "input_dir": str(project_root / "input"),
            "output_dir": str(project_root / "output"),
            "jobs_dir": str(project_root / "jobs"),
            "logs_dir": str(project_root / "logs"),
            "timeline_path": str(project_root / "jobs" / "timeline.jsonl"),
        },
    }


def run_pipeline(
    payload: dict[str, Any],
    *,
    log,
    progress_path: Path,
    backend_client: JsonLineBackendClient | None = None,
):
    if backend_client is not None:
        return backend_client.run_pipeline(payload, progress_path=progress_path)

    from transcribemate.v2.backend.models import PipelineRequest
    from transcribemate.v2.backend.pipeline import PipelineOrchestrator

    progress_path.parent.mkdir(parents=True, exist_ok=True)

    def _progress(data: dict[str, Any]) -> None:
        progress_path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    request = PipelineRequest.from_payload(payload)
    orchestrator = PipelineOrchestrator(
        request=request,
        stop_flag=Event(),
        log_cb=log,
        progress_cb=_progress,
    )
    return orchestrator.run()


def generate_smoke_video(*, audio_path: Path, output_path: Path) -> None:
    from transcribemate.core.paths import ffmpeg_path

    ffmpeg = ffmpeg_path()
    if not ffmpeg:
        raise RuntimeError("ffmpeg is not available for local video smoke generation.")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    command = [
        ffmpeg,
        "-y",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=1280x720:r=30:d=600",
        "-i",
        str(audio_path),
        "-shortest",
        "-c:v",
        "mpeg4",
        "-q:v",
        "5",
        "-c:a",
        "aac",
        "-movflags",
        "+faststart",
        str(output_path),
    ]
    completed = subprocess.run(command, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        raise RuntimeError(
            "Failed to generate local MP4 smoke fixture.\n"
            f"Command: {' '.join(command)}\n"
            f"stdout:\n{completed.stdout}\n"
            f"stderr:\n{completed.stderr}"
        )


def validate_video_smoke(output_root: Path, source_stem: str) -> dict[str, str]:
    matching_mp4 = sorted(
        [
            path for path in output_root.rglob("*.mp4")
            if source_stem.lower() in path.name.lower()
        ],
        key=lambda item: item.stat().st_mtime,
        reverse=True,
    )
    matching_srt = sorted(
        [
            path for path in output_root.rglob("*.srt")
            if source_stem.lower() in path.name.lower()
        ],
        key=lambda item: item.stat().st_mtime,
        reverse=True,
    )
    if not matching_mp4:
        raise RuntimeError(f"No generated subtitled MP4 found for video smoke source '{source_stem}'.")
    if not matching_srt:
        raise RuntimeError(f"No generated SRT found for video smoke source '{source_stem}'.")
    return {
        "video_output": str(matching_mp4[0]),
        "srt_output": str(matching_srt[0]),
    }


def validate_transcripts_only(output_root: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    results: list[dict[str, str]] = []
    for fixture in manifest["fixtures"]:
        source_stem = Path(str(fixture["source"])).stem.lower()
        matches = sorted(
            (
                path for path in output_root.rglob("*.txt")
                if path.is_file()
                and ".summary." not in path.name.lower()
                and source_stem in path.name.lower()
                and path.stat().st_size > 0
            ),
            key=lambda item: item.stat().st_mtime,
            reverse=True,
        )
        if not matches:
            raise RuntimeError(f"No non-empty transcript found for '{source_stem}' under {output_root}.")
        results.append({"fixture_id": str(fixture["id"]), "transcript_path": str(matches[0])})
    return {"fixture_count": len(results), "output_root": str(output_root), "results": results}


def main() -> int:
    args = parse_args()
    if args.app_data_dir:
        os.environ["TM_APP_DATA_DIR"] = args.app_data_dir

    backend_root = Path(args.backend_root).expanduser().resolve()
    if str(backend_root) in sys.path:
        sys.path.remove(str(backend_root))
    sys.path.insert(0, str(backend_root))

    from tests.full_suite.fixture_validation import load_manifest, validate_output_tree

    fixtures_dir = Path(args.fixtures_dir).expanduser().resolve()
    manifest_path = Path(args.manifest).expanduser().resolve()
    artifact_dir = Path(args.artifact_dir).expanduser().resolve()
    artifact_dir.mkdir(parents=True, exist_ok=True)

    manifest = load_manifest(manifest_path)
    if args.fixture_id:
        manifest["fixtures"] = [
            fixture for fixture in manifest["fixtures"]
            if str(fixture.get("id", "")) == args.fixture_id
        ]
        if not manifest["fixtures"]:
            raise ValueError(f"Fixture ID '{args.fixture_id}' was not found in the approved manifest.")
    logger = make_logger(artifact_dir / "fixture-regression.log")

    projects_root = Path(args.projects_root).expanduser().resolve() if args.projects_root else artifact_dir / "projects"
    offline_project = projects_root / "offline-fixtures"
    video_project = projects_root / "video-smoke"
    for directory in (
        offline_project / "input",
        offline_project / "output",
        offline_project / "jobs",
        offline_project / "logs",
        video_project / "input",
        video_project / "output",
        video_project / "jobs",
        video_project / "logs",
    ):
        directory.mkdir(parents=True, exist_ok=True)

    copied_audio = copy_manifest_fixtures(manifest, fixtures_dir, offline_project / "input")
    offline_payload = build_project_payload(
        project_root=offline_project,
        source_path=offline_project / "input",
        output_mode="txt_only",
        summary_enabled=not args.skip_summary,
    )
    logger("[fixture-regression] Running offline transcript + summary regression.")
    backend_client = None
    if args.stdio_server:
        app_data_value = args.app_data_dir or os.environ.get("TM_APP_DATA_DIR", "")
        if not app_data_value:
            raise ValueError("--stdio-server requires --app-data-dir or TM_APP_DATA_DIR.")
        backend_client = JsonLineBackendClient(
            backend_root=backend_root,
            app_data_dir=Path(app_data_value).expanduser().resolve(),
            stderr_path=artifact_dir / "packaged-backend-stderr.log",
            log=logger,
        )
    try:
        offline_result = run_pipeline(
            offline_payload,
            log=logger,
            progress_path=artifact_dir / "offline-progress.json",
            backend_client=backend_client,
        )

        if args.transcript_only:
            offline_validation = validate_transcripts_only(offline_project / "output", manifest)
        else:
            offline_validation = validate_output_tree(
                output_root=offline_project / "output",
                fixtures_dir=fixtures_dir,
                manifest_path=manifest_path,
            )

        video_result = None
        video_validation = None
        if not args.skip_video_smoke:
            smallest_audio = min(copied_audio, key=lambda item: item.stat().st_size)
            smoke_video_path = video_project / "input" / f"{smallest_audio.stem}-video-smoke.mp4"
            logger(f"[fixture-regression] Generating local MP4 smoke fixture from {smallest_audio.name}.")
            generate_smoke_video(audio_path=smallest_audio, output_path=smoke_video_path)

            video_payload = build_project_payload(
                project_root=video_project,
                source_path=video_project / "input",
                output_mode="video_subs",
                summary_enabled=False,
            )
            logger("[fixture-regression] Running local video_subs FFmpeg smoke.")
            video_result = run_pipeline(
                video_payload,
                log=logger,
                progress_path=artifact_dir / "video-progress.json",
                backend_client=backend_client,
            )
            video_validation = validate_video_smoke(video_project / "output", smoke_video_path.stem)
    finally:
        if backend_client is not None:
            backend_client.close()

    report = {
        "offline_run": offline_result if isinstance(offline_result, dict) else offline_result.to_dict(),
        "offline_validation": offline_validation,
        "video_run": (
            video_result
            if video_result is None or isinstance(video_result, dict)
            else video_result.to_dict()
        ),
        "video_validation": video_validation,
        "artifact_dir": str(artifact_dir),
        "app_data_dir": os.environ.get("TM_APP_DATA_DIR", ""),
        "backend_transport": "json-lines" if backend_client is not None else "in-process",
    }
    report_path = artifact_dir / "fixture-regression-report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
