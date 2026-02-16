"""Shared dataclasses for pipeline results."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List


@dataclass
class SpeakerTurn:
    start: float
    end: float
    speaker_id: str
    confidence: float = 0.0


@dataclass
class TranscriptSegment:
    start: float
    end: float
    text: str
    speaker_id: str = ""
    speaker_name: str = ""
    speaker_confidence: float = 0.0


@dataclass
class TranscriptionResult:
    srt_path: Path
    raw_txt_path: Path
    segments: List[TranscriptSegment]
    detected_lang: str
    duration: float
    device: str = "cpu"
    compute_type: str = "int8"
    speaker_turns: List[SpeakerTurn] = field(default_factory=list)
    speaker_map: Dict[str, str] = field(default_factory=dict)
