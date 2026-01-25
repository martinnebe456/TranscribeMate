"""Speech-to-text transcription helpers."""

from __future__ import annotations

from pathlib import Path
from typing import List

from ..core.gpu import torch_device
from ..core.types import TranscriptionResult


def format_timestamp(seconds: float) -> str:
    ms_total = int(max(0, seconds) * 1000)
    h = ms_total // 3600000
    ms_total -= h * 3600000
    m = ms_total // 60000
    ms_total -= m * 60000
    s = ms_total // 1000
    ms = ms_total - s * 1000
    return f"{h:02}:{m:02}:{s:02},{ms:03}"


def faster_whisper_transcribe(
    media_path: Path,
    out_dir: Path,
    model: str,
    prefer_gpu: bool,
    language: str,
    log,
    set_step_progress,
    stop_flag,
) -> TranscriptionResult:
    from faster_whisper import WhisperModel

    out_dir.mkdir(parents=True, exist_ok=True)
    device = "cuda" if prefer_gpu and torch_device(prefer_gpu) == "cuda" else "cpu"
    compute_type = "float16" if device == "cuda" else "int8"

    log(f"[INFO] Transcription settings: model={model}, device={device}, compute={compute_type}\n")
    log(f"[INFO] Language hint: {language}\n")
    log(f"[INFO] Loading faster-whisper model '{model}'...\n")
    wmodel = WhisperModel(model, device=device, compute_type=compute_type)

    log(f"[INFO] Transcribing: {media_path.name}\n")

    whisper_lang = None if language == "auto" else language
    segments, info = wmodel.transcribe(str(media_path), language=whisper_lang, beam_size=5)
    segment_list: List = list(segments)

    detected_lang = info.language if info.language else "en"
    log(f"[INFO] Detected language: {detected_lang}\n")

    duration_value = info.duration if info.duration and info.duration > 0 else (
        float(segment_list[-1].end) if segment_list else 0.0
    )
    log(f"[INFO] Found {len(segment_list)} segments, duration: {duration_value:.1f}s\n")

    srt_lines = []
    txt_lines = []

    for idx, seg in enumerate(segment_list, 1):
        if stop_flag and stop_flag.is_set():
            raise RuntimeError("Stopped by user.")

        start_ts = format_timestamp(seg.start)
        end_ts = format_timestamp(seg.end)
        text = seg.text.strip()

        srt_lines.append(f"{idx}\n{start_ts} --> {end_ts}\n{text}\n")
        if text:
            txt_lines.append(text)

        progress = min(100.0, (idx / max(1, len(segment_list))) * 100)
        set_step_progress(progress)

    srt_path = out_dir / (media_path.stem + ".srt")
    srt_path.write_text("\n".join(srt_lines), encoding="utf-8")
    log(f"[OK] SRT saved: {srt_path}\n")

    txt_path = out_dir / (media_path.stem + ".txt")
    txt_path.write_text("\n".join(txt_lines).strip() + "\n", encoding="utf-8")
    log(f"[OK] Raw transcript saved: {txt_path}\n")

    return TranscriptionResult(
        srt_path=srt_path,
        raw_txt_path=txt_path,
        segments=segment_list,
        detected_lang=detected_lang,
        duration=float(duration_value),
        device=device,
        compute_type=compute_type,
    )
