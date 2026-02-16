"""Per-run log file helpers with retention."""

from __future__ import annotations

import os
from datetime import datetime
from pathlib import Path

from .paths import run_logs_dir


def _line_timestamp() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _file_timestamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def _prune_logs(directory: Path, *, keep: int, prefix: str):
    pattern = f"{prefix}-*.log"
    files = sorted(
        directory.glob(pattern),
        key=lambda p: p.stat().st_mtime if p.exists() else 0.0,
        reverse=True,
    )
    for stale in files[max(0, int(keep)) :]:
        try:
            stale.unlink(missing_ok=True)
        except Exception:
            continue


def create_run_log(*, prefix: str = "pipeline", keep: int = 10) -> Path:
    directory = run_logs_dir()
    pid = os.getpid()
    base = f"{prefix}-{_file_timestamp()}-{pid}"

    candidate = directory / f"{base}.log"
    suffix = 1
    while candidate.exists():
        candidate = directory / f"{base}-{suffix}.log"
        suffix += 1

    candidate.parent.mkdir(parents=True, exist_ok=True)
    candidate.touch(exist_ok=False)
    _prune_logs(directory, keep=keep, prefix=prefix)
    return candidate


def append_run_log(log_file: Path, message: str):
    text = str(message or "")
    if not text:
        return

    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    with log_file.open("a", encoding="utf-8") as fh:
        for line in lines:
            if line:
                fh.write(f"[{_line_timestamp()}] {line}\n")
