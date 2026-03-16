#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from threading import Event
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run real macOS fixture regression over MP3+PDF fixture pairs.")
    parser.add_argument("--fixtures-dir", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--artifact-dir", required=True)
    parser.add_argument("--app-data-dir", default="")
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


def run_pipeline(payload: dict[str, Any], *, log, progress_path: Path):
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


def main() -> int:
    args = parse_args()
    if args.app_data_dir:
        os.environ["TM_APP_DATA_DIR"] = args.app_data_dir

    from tests.full_suite.fixture_validation import load_manifest, validate_output_tree

    fixtures_dir = Path(args.fixtures_dir).expanduser().resolve()
    manifest_path = Path(args.manifest).expanduser().resolve()
    artifact_dir = Path(args.artifact_dir).expanduser().resolve()
    artifact_dir.mkdir(parents=True, exist_ok=True)

    manifest = load_manifest(manifest_path)
    logger = make_logger(artifact_dir / "fixture-regression.log")

    offline_project = artifact_dir / "projects" / "offline-fixtures"
    video_project = artifact_dir / "projects" / "video-smoke"
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
        summary_enabled=True,
    )
    logger("[fixture-regression] Running offline transcript + summary regression.")
    offline_result = run_pipeline(
        offline_payload,
        log=logger,
        progress_path=artifact_dir / "offline-progress.json",
    )

    offline_validation = validate_output_tree(
        output_root=offline_project / "output",
        fixtures_dir=fixtures_dir,
        manifest_path=manifest_path,
    )

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
    )
    video_validation = validate_video_smoke(video_project / "output", smoke_video_path.stem)

    report = {
        "offline_run": offline_result.to_dict(),
        "offline_validation": offline_validation,
        "video_run": video_result.to_dict(),
        "video_validation": video_validation,
        "artifact_dir": str(artifact_dir),
        "app_data_dir": os.environ.get("TM_APP_DATA_DIR", ""),
    }
    report_path = artifact_dir / "fixture-regression-report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
