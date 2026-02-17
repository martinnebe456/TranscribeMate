"""Diarization backend abstraction for V2 pipeline."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from ...core.types import SpeakerTurn, TranscriptSegment
from ...pipeline.diarize import assign_speakers_to_segments, diarize_media

BACKEND_STABLE_LOCAL = "stable_local"
BACKEND_ADVANCED_PYANNOTE = "advanced_pyannote"


@dataclass(slots=True)
class DiarizationOutcome:
    speaker_turns: list[SpeakerTurn]
    backend_used: str
    warnings: list[str]


def supported_backends() -> list[str]:
    return [BACKEND_STABLE_LOCAL, BACKEND_ADVANCED_PYANNOTE]


def stable_local_diarize(segments: Sequence[TranscriptSegment]) -> list[SpeakerTurn]:
    """Very stable fallback diarization with a single deterministic speaker track.

    This backend intentionally avoids heavyweight dependencies and always works
    as long as transcript segments are available.
    """

    if not segments:
        return []

    merged_turns: list[SpeakerTurn] = []
    turn_start = float(segments[0].start)
    turn_end = float(segments[0].end)

    for seg in segments[1:]:
        seg_start = float(seg.start)
        seg_end = float(seg.end)
        gap = seg_start - turn_end
        if gap <= 0.8:
            turn_end = max(turn_end, seg_end)
            continue
        merged_turns.append(
            SpeakerTurn(
                start=turn_start,
                end=turn_end,
                speaker_id="SPEAKER_00",
                confidence=0.75,
            )
        )
        turn_start = seg_start
        turn_end = seg_end

    merged_turns.append(
        SpeakerTurn(
            start=turn_start,
            end=turn_end,
            speaker_id="SPEAKER_00",
            confidence=0.75,
        )
    )
    return merged_turns


def run_diarization_backend(
    *,
    backend: str,
    media_path,
    segments: Sequence[TranscriptSegment],
    prefer_gpu: bool,
    min_speakers: int,
    max_speakers: int,
    hf_token: str,
    stop_flag,
    log,
    set_step_progress,
    fail_on_error: bool,
) -> DiarizationOutcome:
    warnings: list[str] = []
    selected_backend = (backend or BACKEND_STABLE_LOCAL).strip().lower()

    if selected_backend == BACKEND_STABLE_LOCAL:
        turns = stable_local_diarize(segments)
        assign_speakers_to_segments(segments, turns)
        log("[INFO] Diarization backend 'stable_local' finished (single-speaker fallback).\n")
        return DiarizationOutcome(speaker_turns=turns, backend_used=BACKEND_STABLE_LOCAL, warnings=warnings)

    if selected_backend != BACKEND_ADVANCED_PYANNOTE:
        warnings.append(
            f"Unknown diarization backend '{selected_backend}'. Falling back to '{BACKEND_STABLE_LOCAL}'."
        )
        turns = stable_local_diarize(segments)
        assign_speakers_to_segments(segments, turns)
        return DiarizationOutcome(speaker_turns=turns, backend_used=BACKEND_STABLE_LOCAL, warnings=warnings)

    try:
        turns = diarize_media(
            media_path=media_path,
            prefer_gpu=prefer_gpu,
            min_speakers=min_speakers,
            max_speakers=max_speakers,
            hf_token=hf_token,
            log=log,
            set_step_progress=set_step_progress,
            stop_flag=stop_flag,
        )
        assign_speakers_to_segments(segments, turns)
        return DiarizationOutcome(speaker_turns=turns, backend_used=BACKEND_ADVANCED_PYANNOTE, warnings=warnings)
    except Exception as exc:
        if fail_on_error:
            raise
        warnings.append(
            "Diarization backend 'advanced_pyannote' failed; "
            f"fallback to '{BACKEND_STABLE_LOCAL}' ({type(exc).__name__}: {exc})."
        )
        log(f"[WARN] {warnings[-1]}\n")
        turns = stable_local_diarize(segments)
        assign_speakers_to_segments(segments, turns)
        return DiarizationOutcome(speaker_turns=turns, backend_used=BACKEND_STABLE_LOCAL, warnings=warnings)
