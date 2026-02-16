"""Speaker diarization helpers and sidecar persistence."""

from __future__ import annotations

import json
import importlib
import os
import re
import subprocess
import sys
import traceback
from pathlib import Path
from typing import Dict, Iterable, List, Sequence

from ..core.paths import FROZEN, ensure_site_packages_on_path, user_site_packages_dir
from ..core.types import SpeakerTurn, TranscriptSegment, TranscriptionResult
from .transcribe import format_timestamp

DIARIZATION_MODEL_NAME = "pyannote/speaker-diarization-3.1"
_DEFAULT_SPEAKER_ID = "SPEAKER_00"
_SPEAKER_PREFIX_PATTERN = re.compile(r"^(?P<prefix>[^:\n]{1,80}):\s*(?P<body>.+)$", re.DOTALL)
_PYANNOTE_PIP_SPEC = "pyannote.audio>=3.1,<4"
_HF_HUB_PIP_SPEC = "huggingface_hub>=0.34,<1.0"


def _log_traceback(log, prefix: str, exc: Exception):
    lines = traceback.format_exception(type(exc), exc, exc.__traceback__)
    merged = "".join(lines).strip()
    if not merged:
        return
    log(f"{prefix}{type(exc).__name__}: {exc}\n")
    for line in merged.splitlines()[:24]:
        log(f"[TRACE] {line}\n")


def _reset_speaker_related_modules():
    prefixes = (
        "pyannote",
        "speechbrain",
        "lightning",
        "pytorch_lightning",
        "torchmetrics",
        "torch_audiomentations",
        "asteroid_filterbanks",
        "huggingface_hub",
        "torchaudio",
        "torch",
    )
    for name in list(sys.modules):
        if name.startswith(prefixes):
            sys.modules.pop(name, None)
    importlib.invalidate_caches()


def _prioritize_user_site_packages():
    ensure_site_packages_on_path()
    site_str = str(user_site_packages_dir())
    try:
        sys.path.remove(site_str)
    except ValueError:
        pass
    sys.path.insert(0, site_str)


def _run_setup_subprocess(command: Sequence[str], log) -> int:
    cmd = [str(part) for part in command]
    log(f"[INFO] Running dependency helper: {' '.join(cmd)}\n")
    try:
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
    except Exception as exc:
        raise RuntimeError("Could not start speaker dependency helper process.") from exc
    assert proc.stdout is not None
    try:
        for line in proc.stdout:
            text = line.rstrip()
            if text:
                log(f"[SETUP] {text}\n")
    finally:
        proc.stdout.close()
    return int(proc.wait())


def _ensure_speaker_backend_via_subprocess(log):
    if FROZEN:
        code = _run_setup_subprocess([sys.executable, "--ensure-speakers"], log)
        if code != 0:
            raise RuntimeError(
                "Speaker dependency setup helper failed. Check runtime.log for details."
            )
        return

    site_dir = user_site_packages_dir()
    site_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        sys.executable,
        "-m",
        "pip",
        "install",
        "--upgrade",
        "--no-warn-script-location",
        "--prefer-binary",
        "--no-deps",
        "--target",
        str(site_dir),
        _PYANNOTE_PIP_SPEC,
        _HF_HUB_PIP_SPEC,
    ]
    code = _run_setup_subprocess(cmd, log)
    if code != 0:
        raise RuntimeError("Speaker dependency setup helper failed (pip install).")


def _ensure_pyannote_available(log) -> tuple[object, object]:
    """Import pyannote backend and install it on-demand when missing."""
    _prioritize_user_site_packages()
    try:
        import torch  # type: ignore
        from pyannote.audio import Pipeline  # type: ignore
        pyannote_module = importlib.import_module("pyannote.audio")
        pyannote_src = str(getattr(pyannote_module, "__file__", "") or "")
        if pyannote_src:
            log(f"[INFO] pyannote.audio source: {pyannote_src}\n")

        return torch, Pipeline
    except Exception as exc:
        log("[WARN] Speaker diarization backend import failed, attempting repair.\n")
        _log_traceback(log, "[WARN] Initial pyannote import failed: ", exc)

    # Prefer a separate process for dependency setup. This avoids in-process
    # pip side effects and Windows file-lock issues with loaded extension modules.
    _ensure_speaker_backend_via_subprocess(log)
    _reset_speaker_related_modules()

    _prioritize_user_site_packages()
    try:
        import torch  # type: ignore
        from pyannote.audio import Pipeline  # type: ignore
        pyannote_module = importlib.import_module("pyannote.audio")
        pyannote_src = str(getattr(pyannote_module, "__file__", "") or "")
        if pyannote_src:
            log(f"[INFO] pyannote.audio source after repair: {pyannote_src}\n")

        log("[OK] Speaker diarization backend is ready.\n")
        return torch, Pipeline
    except Exception as exc:
        _log_traceback(log, "[ERROR] pyannote import still failing after setup: ", exc)
        raise RuntimeError(
            "Speaker diarization backend install completed but import still failed (pyannote.audio)."
        ) from exc


def _normalize_speaker_id(value: str | None) -> str:
    text = str(value or "").strip().upper().replace(" ", "_")
    if not text:
        return _DEFAULT_SPEAKER_ID
    if text.startswith("SPEAKER_"):
        suffix = text.removeprefix("SPEAKER_")
        if suffix.isdigit():
            return f"SPEAKER_{int(suffix):02d}"
        return text
    if text.isdigit():
        return f"SPEAKER_{int(text):02d}"
    return text


def _speaker_sort_key(label: str) -> tuple[int, str]:
    match = re.search(r"(\d+)$", label)
    if match:
        return int(match.group(1)), label
    return 10_000, label


def _overlap_seconds(start_a: float, end_a: float, start_b: float, end_b: float) -> float:
    return max(0.0, min(end_a, end_b) - max(start_a, start_b))


def _closest_turn_label(seg_start: float, seg_end: float, turns: Sequence[SpeakerTurn]) -> str:
    if not turns:
        return _DEFAULT_SPEAKER_ID
    center = (seg_start + seg_end) / 2.0
    closest = min(turns, key=lambda turn: abs(center - ((turn.start + turn.end) / 2.0)))
    return _normalize_speaker_id(closest.speaker_id)


def speaker_label(seg: TranscriptSegment, include_unmapped_speakers: bool = True) -> str:
    speaker_name = str(seg.speaker_name or "").strip()
    if speaker_name:
        return speaker_name
    if include_unmapped_speakers:
        speaker_id = str(seg.speaker_id or "").strip()
        if speaker_id:
            return speaker_id
    return ""


def speaker_prefixed_text(seg: TranscriptSegment, include_unmapped_speakers: bool = True) -> str:
    text = str(seg.text or "").strip()
    if not text:
        return ""
    label = speaker_label(seg, include_unmapped_speakers=include_unmapped_speakers)
    return f"{label}: {text}" if label else text


def strip_speaker_prefix(text: str) -> str:
    match = _SPEAKER_PREFIX_PATTERN.match(str(text or "").strip())
    if not match:
        return str(text or "").strip()
    return match.group("body").strip()


def apply_speaker_prefix_to_text(text: str, seg: TranscriptSegment, include_unmapped_speakers: bool) -> str:
    clean_body = strip_speaker_prefix(text)
    if not clean_body:
        return ""
    label = speaker_label(seg, include_unmapped_speakers=include_unmapped_speakers)
    return f"{label}: {clean_body}" if label else clean_body


def diarize_media(
    media_path: Path,
    prefer_gpu: bool,
    min_speakers: int,
    max_speakers: int,
    hf_token: str,
    log,
    set_step_progress,
    stop_flag,
) -> List[SpeakerTurn]:
    """Run pyannote diarization and return normalized speaker turns."""
    torch, Pipeline = _ensure_pyannote_available(log)

    token = (
        hf_token.strip()
        if hf_token and hf_token.strip()
        else (
            os.environ.get("HF_TOKEN")
            or os.environ.get("HUGGINGFACE_TOKEN")
            or os.environ.get("HF_API_TOKEN")
            or ""
        ).strip()
    )
    if not token:
        raise RuntimeError(
            "Speaker diarization model access requires Hugging Face token. "
            "Set HF_TOKEN and accept access for pyannote/speaker-diarization-3.1."
        )

    log(f"[INFO] Loading diarization model: {DIARIZATION_MODEL_NAME}\n")
    try:
        pipeline = Pipeline.from_pretrained(DIARIZATION_MODEL_NAME, use_auth_token=token)
    except Exception as exc:
        raise RuntimeError(
            "Failed to load diarization model. Verify HF_TOKEN access to "
            "pyannote/speaker-diarization-3.1."
        ) from exc

    device = "cuda" if (prefer_gpu and torch.cuda.is_available()) else "cpu"
    try:
        pipeline.to(torch.device(device))
    except Exception:
        log(f"[WARN] Could not move diarization model to {device}, using default device.\n")

    kwargs = {}
    if int(min_speakers) > 0:
        kwargs["min_speakers"] = int(min_speakers)
    if int(max_speakers) > 0:
        kwargs["max_speakers"] = int(max_speakers)

    log(
        "[INFO] Diarization settings: "
        f"device={device}, min_speakers={kwargs.get('min_speakers', 'auto')}, "
        f"max_speakers={kwargs.get('max_speakers', 'auto')}\n"
    )
    set_step_progress(3.0)

    if stop_flag and stop_flag.is_set():
        raise RuntimeError("Stopped by user.")

    diarization = pipeline(str(media_path), **kwargs)

    turns: List[SpeakerTurn] = []
    for turn, _track, speaker_label_val in diarization.itertracks(yield_label=True):
        if stop_flag and stop_flag.is_set():
            raise RuntimeError("Stopped by user.")
        turns.append(
            SpeakerTurn(
                start=float(turn.start),
                end=float(turn.end),
                speaker_id=_normalize_speaker_id(str(speaker_label_val)),
                confidence=1.0,
            )
        )

    turns.sort(key=lambda t: (t.start, t.end, t.speaker_id))
    set_step_progress(100.0)

    speaker_ids = sorted({turn.speaker_id for turn in turns}, key=_speaker_sort_key)
    log(f"[INFO] Speaker turns found: {len(turns)}\n")
    if speaker_ids:
        log(f"[INFO] Speakers detected: {', '.join(speaker_ids)}\n")

    return turns


def assign_speakers_to_segments(
    segments: Sequence[TranscriptSegment],
    speaker_turns: Sequence[SpeakerTurn],
) -> List[str]:
    """Attach speaker labels to transcript segments using overlap matching."""
    turns = sorted(speaker_turns, key=lambda t: (t.start, t.end, t.speaker_id))

    for seg in segments:
        seg_start = float(seg.start)
        seg_end = float(seg.end)
        if seg_end < seg_start:
            seg_start, seg_end = seg_end, seg_start
        seg_duration = max(0.001, seg_end - seg_start)

        best_label = _DEFAULT_SPEAKER_ID
        best_overlap = 0.0
        for turn in turns:
            overlap = _overlap_seconds(seg_start, seg_end, float(turn.start), float(turn.end))
            if overlap > best_overlap:
                best_overlap = overlap
                best_label = _normalize_speaker_id(turn.speaker_id)

        if best_overlap <= 0.0:
            best_label = _closest_turn_label(seg_start, seg_end, turns)

        seg.speaker_id = best_label
        seg.speaker_confidence = min(1.0, best_overlap / seg_duration) if best_overlap > 0 else 0.0

    if not turns and segments:
        for seg in segments:
            seg.speaker_id = _DEFAULT_SPEAKER_ID
            seg.speaker_confidence = 0.0

    return speaker_ids_in_order(segments)


def speaker_ids_in_order(segments: Sequence[TranscriptSegment]) -> List[str]:
    seen: set[str] = set()
    ordered: List[str] = []
    for seg in segments:
        label = _normalize_speaker_id(seg.speaker_id)
        seg.speaker_id = label
        if label not in seen:
            seen.add(label)
            ordered.append(label)
    ordered.sort(key=_speaker_sort_key)
    return ordered


def apply_speaker_mapping(
    segments: Sequence[TranscriptSegment],
    speaker_map: Dict[str, str],
) -> Dict[str, str]:
    """Apply user mapping (SPEAKER_XX -> Name) to every segment."""
    normalized: Dict[str, str] = {}
    for key, value in (speaker_map or {}).items():
        label = _normalize_speaker_id(key)
        normalized[label] = str(value or "").strip()

    for seg in segments:
        label = _normalize_speaker_id(seg.speaker_id)
        seg.speaker_id = label
        seg.speaker_name = normalized.get(label, "")

    for label in speaker_ids_in_order(segments):
        normalized.setdefault(label, "")

    return normalized


def build_speakerized_srt(
    segments: Sequence[TranscriptSegment],
    out_srt: Path,
    include_unmapped_speakers: bool,
):
    blocks: List[str] = []
    idx = 1
    for seg in segments:
        text = speaker_prefixed_text(seg, include_unmapped_speakers=include_unmapped_speakers)
        if not text:
            continue
        start_ts = format_timestamp(float(seg.start))
        end_ts = format_timestamp(float(seg.end))
        blocks.append(f"{idx}\n{start_ts} --> {end_ts}\n{text}\n")
        idx += 1

    out_srt.parent.mkdir(parents=True, exist_ok=True)
    out_srt.write_text("\n".join(blocks), encoding="utf-8")


def _parse_srt_blocks(srt_text: str) -> List[dict]:
    blocks = re.split(r"\n{2,}", srt_text.strip())
    parsed: List[dict] = []
    for raw in blocks:
        lines = [line.rstrip("\n") for line in raw.splitlines()]
        if len(lines) < 3:
            continue
        idx_text = lines[0].strip()
        ts_line = lines[1].strip()
        if "-->" not in ts_line:
            continue
        text = "\n".join(lines[2:]).strip()
        parsed.append({"idx": idx_text, "ts": ts_line, "text": text})
    return parsed


def _render_srt_blocks(blocks: Sequence[dict]) -> str:
    lines: List[str] = []
    for block in blocks:
        lines.append(str(block.get("idx", "")).strip())
        lines.append(str(block.get("ts", "")).strip())
        lines.append(str(block.get("text", "")).strip())
        lines.append("")
    return "\n".join(lines).strip() + "\n"


def rewrite_srt_speaker_prefixes(
    inp_srt: Path,
    out_srt: Path,
    segments: Sequence[TranscriptSegment],
    include_unmapped_speakers: bool,
):
    parsed = _parse_srt_blocks(inp_srt.read_text(encoding="utf-8"))
    if not parsed:
        out_srt.write_text(inp_srt.read_text(encoding="utf-8"), encoding="utf-8")
        return

    for idx, block in enumerate(parsed):
        if idx >= len(segments):
            break
        block["text"] = apply_speaker_prefix_to_text(
            str(block.get("text") or ""),
            segments[idx],
            include_unmapped_speakers=include_unmapped_speakers,
        )

    out_srt.parent.mkdir(parents=True, exist_ok=True)
    out_srt.write_text(_render_srt_blocks(parsed), encoding="utf-8")


def serialize_segments(segments: Sequence[TranscriptSegment]) -> List[dict]:
    return [
        {
            "start": float(seg.start),
            "end": float(seg.end),
            "text": str(seg.text),
            "speaker_id": _normalize_speaker_id(seg.speaker_id),
            "speaker_name": str(seg.speaker_name or "").strip(),
            "speaker_confidence": float(seg.speaker_confidence or 0.0),
        }
        for seg in segments
    ]


def deserialize_segments(payload: Iterable[dict]) -> List[TranscriptSegment]:
    segments: List[TranscriptSegment] = []
    for item in payload:
        if not isinstance(item, dict):
            continue
        segments.append(
            TranscriptSegment(
                start=float(item.get("start", 0.0) or 0.0),
                end=float(item.get("end", 0.0) or 0.0),
                text=str(item.get("text") or ""),
                speaker_id=_normalize_speaker_id(str(item.get("speaker_id") or "")),
                speaker_name=str(item.get("speaker_name") or "").strip(),
                speaker_confidence=float(item.get("speaker_confidence", 0.0) or 0.0),
            )
        )
    return segments


def serialize_speaker_turns(turns: Sequence[SpeakerTurn]) -> List[dict]:
    return [
        {
            "start": float(turn.start),
            "end": float(turn.end),
            "speaker_id": _normalize_speaker_id(turn.speaker_id),
            "confidence": float(turn.confidence or 0.0),
        }
        for turn in turns
    ]


def deserialize_speaker_turns(payload: Iterable[dict]) -> List[SpeakerTurn]:
    turns: List[SpeakerTurn] = []
    for item in payload:
        if not isinstance(item, dict):
            continue
        turns.append(
            SpeakerTurn(
                start=float(item.get("start", 0.0) or 0.0),
                end=float(item.get("end", 0.0) or 0.0),
                speaker_id=_normalize_speaker_id(str(item.get("speaker_id") or "")),
                confidence=float(item.get("confidence", 0.0) or 0.0),
            )
        )
    turns.sort(key=lambda t: (t.start, t.end, t.speaker_id))
    return turns


def build_speaker_sidecar(
    *,
    media_path: Path,
    base_name: str,
    result: TranscriptionResult,
    model_name: str,
    output_mode: str,
    output_prefix: str,
    clean_text: bool,
    export_md: bool,
    split_minutes: int,
    generate_summary_pack: bool,
    summary_lang: str,
    speaker: str,
    topic: str,
    include_unmapped_speakers: bool = True,
    speaker_prefix_in_srt: bool = False,
    transcripts_dir: Path | None = None,
    summaries_dir: Path | None = None,
    srt_source_path: Path | None = None,
    srt_translated_path: Path | None = None,
) -> dict:
    speaker_map = {k: str(v or "").strip() for k, v in (result.speaker_map or {}).items()}
    for label in speaker_ids_in_order(result.segments):
        speaker_map.setdefault(label, "")

    return {
        "version": 2,
        "media_path": str(media_path),
        "base_name": str(base_name),
        "model_name": str(model_name),
        "output_mode": str(output_mode),
        "output_prefix": str(output_prefix),
        "summary_lang": str(summary_lang),
        "clean_text": bool(clean_text),
        "export_md": bool(export_md),
        "split_minutes": int(split_minutes),
        "generate_summary_pack": bool(generate_summary_pack),
        "speaker": str(speaker or "").strip(),
        "topic": str(topic or "").strip(),
        "detected_lang": str(result.detected_lang),
        "duration": float(result.duration),
        "device": str(result.device),
        "compute_type": str(result.compute_type),
        "include_unmapped_speakers": bool(include_unmapped_speakers),
        "speaker_prefix_in_srt": bool(speaker_prefix_in_srt),
        "transcripts_dir": str(transcripts_dir or ""),
        "summaries_dir": str(summaries_dir or ""),
        "srt_source_path": str(srt_source_path or ""),
        "srt_translated_path": str(srt_translated_path or ""),
        "speaker_map": speaker_map,
        "speaker_turns": serialize_speaker_turns(result.speaker_turns),
        "segments": serialize_segments(result.segments),
    }


def save_speaker_sidecar(path: Path, payload: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def load_speaker_sidecar(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise RuntimeError(f"Failed to read sidecar file: {path}") from exc

    if not isinstance(data, dict):
        raise RuntimeError("Invalid sidecar format.")

    sidecar = {
        "version": int(data.get("version", 1) or 1),
        "media_path": str(data.get("media_path") or ""),
        "base_name": str(data.get("base_name") or ""),
        "model_name": str(data.get("model_name") or "medium"),
        "output_mode": str(data.get("output_mode") or "txt_only"),
        "output_prefix": str(data.get("output_prefix") or ""),
        "summary_lang": str(data.get("summary_lang") or "auto"),
        "clean_text": bool(data.get("clean_text", True)),
        "export_md": bool(data.get("export_md", True)),
        "split_minutes": int(data.get("split_minutes", 0) or 0),
        "generate_summary_pack": bool(data.get("generate_summary_pack", False)),
        "speaker": str(data.get("speaker") or "").strip(),
        "topic": str(data.get("topic") or "").strip(),
        "detected_lang": str(data.get("detected_lang") or "en"),
        "duration": float(data.get("duration", 0.0) or 0.0),
        "device": str(data.get("device") or "cpu"),
        "compute_type": str(data.get("compute_type") or "int8"),
        "include_unmapped_speakers": bool(data.get("include_unmapped_speakers", True)),
        "speaker_prefix_in_srt": bool(data.get("speaker_prefix_in_srt", False)),
        "transcripts_dir": str(data.get("transcripts_dir") or ""),
        "summaries_dir": str(data.get("summaries_dir") or ""),
        "srt_source_path": str(data.get("srt_source_path") or ""),
        "srt_translated_path": str(data.get("srt_translated_path") or ""),
        "speaker_map": {},
        "speaker_turns": deserialize_speaker_turns(data.get("speaker_turns") or []),
        "segments": deserialize_segments(data.get("segments") or []),
    }

    map_payload = data.get("speaker_map")
    if isinstance(map_payload, dict):
        for key, value in map_payload.items():
            sidecar["speaker_map"][_normalize_speaker_id(str(key))] = str(value or "").strip()

    for label in speaker_ids_in_order(sidecar["segments"]):
        sidecar["speaker_map"].setdefault(label, "")

    apply_speaker_mapping(sidecar["segments"], sidecar["speaker_map"])
    return sidecar
