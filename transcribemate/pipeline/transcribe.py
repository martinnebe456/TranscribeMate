"""Speech-to-text transcription helpers."""

from __future__ import annotations

import logging
import os
import re
from pathlib import Path

from ..core.hf_progress import huggingface_download_progress
from ..core.models import whisper_cache_dir
from ..core.gpu import torch_device
from ..core.types import TranscriptSegment, TranscriptionResult

LOGGER = logging.getLogger(__name__)


def format_timestamp(seconds: float) -> str:
    ms_total = int(max(0, seconds) * 1000)
    h = ms_total // 3600000
    ms_total -= h * 3600000
    m = ms_total // 60000
    ms_total -= m * 60000
    s = ms_total // 1000
    ms = ms_total - s * 1000
    return f"{h:02}:{m:02}:{s:02},{ms:03}"


def _safe_model_dir_name(model_name: str) -> str:
    text = re.sub(r"[^A-Za-z0-9._-]+", "-", str(model_name or "").strip())
    text = text.strip(".-")
    return text or "whisper-model"


def _resolve_whisper_model_path(model_name: str, log, set_step_progress) -> str:
    from faster_whisper.utils import download_model as download_faster_whisper_model

    cache_dir = os.environ.get("WHISPER_CACHE_DIR")
    if not cache_dir:
        cache_dir = str(whisper_cache_dir())
    model_output_dir = Path(cache_dir) / "models" / _safe_model_dir_name(model_name)
    model_output_dir.mkdir(parents=True, exist_ok=True)

    progress_state = {"last_bucket": -10, "saw_progress": False}

    def _on_download_progress(desc: str, percent: float | None):
        progress_state["saw_progress"] = True
        if percent is None:
            return

        bounded = max(0.0, min(100.0, float(percent)))
        # Reserve 35% of transcribe step for model availability/download.
        set_step_progress((bounded / 100.0) * 35.0)

        bucket = int(bounded // 10) * 10
        if bucket > progress_state["last_bucket"]:
            progress_state["last_bucket"] = bucket
            log(f"[INFO] Model download: {bucket}% ({desc})\n")

    log(f"[INFO] Resolving faster-whisper model '{model_name}' files...\n")
    log(f"[INFO] Whisper model target directory: {model_output_dir}\n")
    with huggingface_download_progress(_on_download_progress):
        model_path = download_faster_whisper_model(
            model_name,
            output_dir=str(model_output_dir),
            local_files_only=False,
            cache_dir=cache_dir,
        )

    if progress_state["saw_progress"]:
        log("[INFO] Model download/check complete.\n")
    else:
        log("[INFO] Model already available in local cache.\n")

    set_step_progress(35.0)
    return model_path


def _load_whisper_model(model_path: str, prefer_gpu: bool, log):
    from faster_whisper import WhisperModel

    primary_device = "cuda" if prefer_gpu and torch_device(prefer_gpu) == "cuda" else "cpu"
    if prefer_gpu and primary_device != "cuda":
        log("[WARN] GPU requested but CUDA runtime is unavailable. Falling back to CPU.\n")

    candidates: list[tuple[str, str]] = []
    if primary_device == "cuda":
        candidates.append(("cuda", "float16"))
        candidates.append(("cpu", "int8"))
    else:
        candidates.append(("cpu", "int8"))

    last_error: Exception | None = None
    for device, compute_type in candidates:
        try:
            log(f"[INFO] Initializing faster-whisper runtime: device={device}, compute={compute_type}\n")
            model = WhisperModel(
                model_path,
                device=device,
                compute_type=compute_type,
                local_files_only=True,
            )
            if device == "cpu" and primary_device == "cuda":
                log("[WARN] CUDA initialization failed. Using CPU runtime for this job.\n")
            return model, device, compute_type
        except Exception as exc:
            last_error = exc
            if device == "cuda":
                detail = str(exc).strip() or type(exc).__name__
                log(f"[WARN] CUDA runtime initialization failed: {type(exc).__name__}: {detail}\n")
                continue
            raise

    raise RuntimeError("Unable to initialize faster-whisper runtime.") from last_error


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
    out_dir.mkdir(parents=True, exist_ok=True)
    preferred_device = "cuda" if prefer_gpu else "cpu"

    log(f"[INFO] Transcription request: model={model}, preferred_device={preferred_device}\n")
    log(f"[INFO] Language hint: {language}\n")
    log(f"[INFO] Loading faster-whisper model '{model}'...\n")
    model_path = _resolve_whisper_model_path(model, log, set_step_progress)
    log(f"[INFO] Whisper model path: {model_path}\n")
    wmodel, device, compute_type = _load_whisper_model(model_path, prefer_gpu, log)
    log(f"[INFO] Transcription settings: model={model}, device={device}, compute={compute_type}\n")

    log(f"[INFO] Transcribing: {media_path.name}\n")

    whisper_lang = None if language == "auto" else language
    segments, info = wmodel.transcribe(str(media_path), language=whisper_lang, beam_size=5)
    segment_list: list[TranscriptSegment] = []

    detected_lang = info.language if info.language else "en"
    log(f"[INFO] Detected language: {detected_lang}\n")

    duration_hint = float(info.duration) if info.duration and info.duration > 0 else 0.0

    srt_lines = []
    txt_lines = []
    last_end = 0.0

    for idx, seg in enumerate(segments, 1):
        if stop_flag and stop_flag.is_set():
            raise RuntimeError("Stopped by user.")

        start_ts = format_timestamp(seg.start)
        end_ts = format_timestamp(seg.end)
        text = seg.text.strip()
        segment_list.append(
            TranscriptSegment(
                start=float(seg.start),
                end=float(seg.end),
                text=text,
            )
        )

        last_end = float(seg.end)
        srt_lines.append(f"{idx}\n{start_ts} --> {end_ts}\n{text}\n")
        if text:
            txt_lines.append(text)

        if duration_hint > 0:
            # First ~35% is reserved for model resolution/download.
            progress = min(99.0, 35.0 + ((last_end / duration_hint) * 64.0))
            set_step_progress(progress)

    duration_value = duration_hint if duration_hint > 0 else last_end
    log(f"[INFO] Found {len(segment_list)} segments, duration: {duration_value:.1f}s\n")
    set_step_progress(100.0)

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
