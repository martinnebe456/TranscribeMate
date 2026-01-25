"""Shared dataclasses for pipeline results."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, List


@dataclass
class TranscriptionResult:
    srt_path: Path
    raw_txt_path: Path
    segments: List[Any]
    detected_lang: str
    duration: float
