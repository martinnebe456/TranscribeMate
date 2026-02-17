"""Diarization backend abstraction for V2 pipeline."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from ...core.types import SpeakerTurn, TranscriptSegment
from ...pipeline.diarize import (
    LOCAL_BACKEND_ACCURATE,
    LOCAL_BACKEND_FAST,
    assign_speakers_to_segments,
    diarize_media,
)

BACKEND_LOCAL_FAST = LOCAL_BACKEND_FAST
BACKEND_LOCAL_ACCURATE = LOCAL_BACKEND_ACCURATE


@dataclass(slots=True)
class DiarizationOutcome:
    speaker_turns: list[SpeakerTurn]
    backend_used: str
    warnings: list[str]


def supported_backends() -> list[str]:
    return [BACKEND_LOCAL_FAST, BACKEND_LOCAL_ACCURATE]


def run_diarization_backend(
    *,
    backend: str,
    accuracy_profile: str,
    media_path,
    segments: Sequence[TranscriptSegment],
    prefer_gpu: bool,
    min_speakers: int,
    max_speakers: int,
    stop_flag,
    log,
    set_step_progress,
) -> DiarizationOutcome:
    selected_backend = (backend or BACKEND_LOCAL_ACCURATE).strip().lower()
    if selected_backend not in supported_backends():
        raise RuntimeError(f"Unknown diarization backend '{selected_backend}'.")

    turns = diarize_media(
        media_path=media_path,
        segments=segments,
        prefer_gpu=prefer_gpu,
        backend=selected_backend,
        accuracy_profile=accuracy_profile,
        min_speakers=min_speakers,
        max_speakers=max_speakers,
        log=log,
        set_step_progress=set_step_progress,
        stop_flag=stop_flag,
    )
    assign_speakers_to_segments(segments, turns)
    log(f"[INFO] Diarization backend '{selected_backend}' finished.\n")
    return DiarizationOutcome(
        speaker_turns=turns,
        backend_used=selected_backend,
        warnings=[],
    )
