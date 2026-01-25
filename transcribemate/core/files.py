"""Filename, directory, and media collection helpers."""

from __future__ import annotations

import re
import shutil
from datetime import datetime
from pathlib import Path
from typing import Any, List, Optional

from .i18n import AUDIO_EXTS, VIDEO_EXTS


def sanitize_filename(stem: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", stem)
    cleaned = re.sub(r"\s+", "_", cleaned).strip("._")
    return cleaned[:80] if cleaned else "media"


def timestamp_for_path(path: Path) -> datetime:
    try:
        ts = path.stat().st_mtime
        return datetime.fromtimestamp(ts)
    except Exception:
        return datetime.now()


def timestamped_base_name(path: Path) -> str:
    ts = timestamp_for_path(path).strftime("%Y-%m-%d_%H-%M")
    return f"{ts}_{sanitize_filename(path.stem)}"


def unique_path(directory: Path, filename: str) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    candidate = directory / filename
    if not candidate.exists():
        return candidate

    stem = candidate.stem
    suffix = candidate.suffix
    for idx in range(2, 1000):
        candidate = directory / f"{stem}_{idx:02d}{suffix}"
        if not candidate.exists():
            return candidate
    return directory / f"{stem}_{datetime.now().strftime('%H%M%S')}{suffix}"


def split_segments(segments: List[Any], part_seconds: int) -> List[List[Any]]:
    if part_seconds <= 0 or not segments:
        return [segments]

    parts: List[List[Any]] = [[]]
    current_start = float(segments[0].start)

    for seg in segments:
        if float(seg.start) - current_start >= part_seconds and parts[-1]:
            parts.append([])
            current_start = float(seg.start)
        parts[-1].append(seg)

    return parts


def copy_originals_to_final(original_files: List[Path], originals_dir: Path, log):
    originals_dir.mkdir(parents=True, exist_ok=True)
    for src in original_files:
        target = unique_path(originals_dir, src.name)
        shutil.copy2(src, target)
        log(f"[OK] Original kept: {target}\n")


def cleanup_workdir(workdir: Optional[Path], log):
    if not workdir:
        return
    try:
        if workdir.exists() and workdir.name.startswith("_tm_work_"):
            shutil.rmtree(workdir, ignore_errors=True)
            log(f"[INFO] Cleaned workdir: {workdir}\n")
    except Exception:
        pass


def list_videos(folder: Path) -> List[Path]:
    media_exts = VIDEO_EXTS | AUDIO_EXTS
    return sorted([p for p in folder.iterdir() if p.suffix.lower() in media_exts])


def is_audio_file(path: Path) -> bool:
    return path.suffix.lower() in AUDIO_EXTS
