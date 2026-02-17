"""Speaker diarization helpers and sidecar persistence."""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence

from ..core.files import split_segments
from ..core.paths import user_data_dir
from ..core.transcripts import (
    build_metadata,
    render_clean_transcript,
    render_raw_transcript,
    render_transcript_markdown,
)
from ..core.types import SpeakerTurn, TranscriptSegment, TranscriptionResult
from .transcribe import format_timestamp

LOCAL_BACKEND_FAST = "local_cluster_fast"
LOCAL_BACKEND_ACCURATE = "local_cluster_accurate"
LOCAL_SPEAKER_ENCODER_NAME = "speechbrain/spkrec-ecapa-voxceleb"
_DEFAULT_SPEAKER_ID = "SPEAKER_00"
_SPEAKER_PREFIX_PATTERN = re.compile(r"^(?P<prefix>[^:\n]{1,80}):\s*(?P<body>.+)$", re.DOTALL)

ACCURACY_PROFILE_LOW = "low"
ACCURACY_PROFILE_BALANCED = "balanced"
ACCURACY_PROFILE_HIGH = "high"
ACCURACY_PROFILE_MAXIMUM = "maximum"
_ACCURACY_PROFILES = {
    ACCURACY_PROFILE_LOW,
    ACCURACY_PROFILE_BALANCED,
    ACCURACY_PROFILE_HIGH,
    ACCURACY_PROFILE_MAXIMUM,
}

_ACCURACY_PROFILE_SETTINGS = {
    ACCURACY_PROFILE_LOW: {
        "target_duration_fast": 3.2,
        "target_duration_accurate": 3.6,
        "min_duration": 0.8,
        "max_duration_fast": 11.0,
        "max_duration_accurate": 13.0,
        "max_segments_fast": 5,
        "max_segments_accurate": 7,
        "max_gap": 1.25,
        "cluster_penalty_fast": 0.06,
        "cluster_penalty_accurate": 0.04,
        "auto_cap_fast": 3,
        "auto_cap_accurate": 5,
        "turn_merge_gap": 1.15,
    },
    ACCURACY_PROFILE_BALANCED: {
        "target_duration_fast": 1.7,
        "target_duration_accurate": 2.6,
        "min_duration": 0.5,
        "max_duration_fast": 7.0,
        "max_duration_accurate": 10.0,
        "max_segments_fast": 2,
        "max_segments_accurate": 4,
        "max_gap": 0.9,
        "cluster_penalty_fast": 0.04,
        "cluster_penalty_accurate": 0.02,
        "auto_cap_fast": 4,
        "auto_cap_accurate": 8,
        "turn_merge_gap": 0.9,
    },
    ACCURACY_PROFILE_HIGH: {
        "target_duration_fast": 1.4,
        "target_duration_accurate": 2.2,
        "min_duration": 0.45,
        "max_duration_fast": 5.8,
        "max_duration_accurate": 8.2,
        "max_segments_fast": 2,
        "max_segments_accurate": 3,
        "max_gap": 0.7,
        "cluster_penalty_fast": 0.03,
        "cluster_penalty_accurate": 0.015,
        "auto_cap_fast": 6,
        "auto_cap_accurate": 10,
        "turn_merge_gap": 0.72,
    },
    ACCURACY_PROFILE_MAXIMUM: {
        "target_duration_fast": 1.1,
        "target_duration_accurate": 1.8,
        "min_duration": 0.4,
        "max_duration_fast": 4.6,
        "max_duration_accurate": 6.6,
        "max_segments_fast": 1,
        "max_segments_accurate": 2,
        "max_gap": 0.52,
        "cluster_penalty_fast": 0.02,
        "cluster_penalty_accurate": 0.01,
        "auto_cap_fast": 8,
        "auto_cap_accurate": 12,
        "turn_merge_gap": 0.55,
    },
}


def normalize_accuracy_profile(value: str | None) -> str:
    profile = str(value or "").strip().lower()
    legacy_map = {
        "small": ACCURACY_PROFILE_LOW,
        "minimal": ACCURACY_PROFILE_LOW,
        "default": ACCURACY_PROFILE_BALANCED,
        "medium": ACCURACY_PROFILE_BALANCED,
        "normal": ACCURACY_PROFILE_BALANCED,
        "max": ACCURACY_PROFILE_MAXIMUM,
        "best": ACCURACY_PROFILE_MAXIMUM,
    }
    mapped = legacy_map.get(profile, profile)
    if mapped not in _ACCURACY_PROFILES:
        return ACCURACY_PROFILE_BALANCED
    return mapped

def ensure_torchaudio_legacy_backend_api():
    """Patch missing legacy torchaudio backend API required by older speechbrain code."""
    try:
        import torchaudio  # type: ignore
    except Exception as exc:
        raise RuntimeError(
            "Diarization requires local dependencies 'torch' and 'torchaudio'."
        ) from exc

    patched = False
    backend_holder = {"name": "ffmpeg"}

    if not hasattr(torchaudio, "list_audio_backends"):
        def _list_audio_backends():
            return ["ffmpeg", "soundfile"]

        setattr(torchaudio, "list_audio_backends", _list_audio_backends)
        patched = True

    if not hasattr(torchaudio, "set_audio_backend"):
        def _set_audio_backend(name):
            backend_holder["name"] = str(name or "").strip() or backend_holder["name"]
            return None

        setattr(torchaudio, "set_audio_backend", _set_audio_backend)
        patched = True

    if not hasattr(torchaudio, "get_audio_backend"):
        def _get_audio_backend():
            return backend_holder["name"]

        setattr(torchaudio, "get_audio_backend", _get_audio_backend)
        patched = True

    return torchaudio, patched


def ensure_local_diarization_runtime() -> dict:
    """Validate local diarization dependencies and apply runtime compatibility shims."""
    try:
        import sklearn  # noqa: F401
    except Exception as exc:
        raise RuntimeError(
            "Diarization requires local package 'scikit-learn'."
        ) from exc

    torchaudio, patched = ensure_torchaudio_legacy_backend_api()
    try:
        import soundfile  # noqa: F401
    except Exception as exc:
        raise RuntimeError(
            "Diarization requires local package 'soundfile'."
        ) from exc

    try:
        from speechbrain.inference.speaker import EncoderClassifier  # noqa: F401
    except Exception as exc:
        raise RuntimeError(
            "Diarization requires 'speechbrain' package for local speaker embeddings."
        ) from exc

    return {
        "torchaudio_compat_patched": bool(patched),
        "torchaudio_version": str(getattr(torchaudio, "__version__", "") or ""),
        "soundfile_version": str(getattr(soundfile, "__version__", "") or ""),
    }


@dataclass(slots=True)
class _EmbeddingWindow:
    start: float
    end: float
    segment_indexes: list[int]
    speaker_id: str = _DEFAULT_SPEAKER_ID
    confidence: float = 0.0
    embedding: Any = None


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


def _load_audio_waveform(media_path: Path):
    try:
        import torch  # type: ignore
    except Exception as exc:
        raise RuntimeError(
            "Diarization requires local dependency 'torch'."
        ) from exc
    target_sr = 16000

    # Prefer soundfile to avoid torchaudio+torchcodec decoder requirements.
    try:
        import numpy as np  # type: ignore
        import soundfile as sf  # type: ignore

        samples, sample_rate = sf.read(str(media_path), dtype="float32", always_2d=True)
        if samples.shape[1] > 1:
            samples = samples.mean(axis=1, keepdims=True)
        waveform = torch.from_numpy(np.ascontiguousarray(samples.T))
        if waveform.ndim == 1:
            waveform = waveform.unsqueeze(0)

        if int(sample_rate) != target_sr:
            torchaudio, _ = ensure_torchaudio_legacy_backend_api()
            waveform = torchaudio.functional.resample(waveform, int(sample_rate), target_sr)
        return waveform.contiguous(), target_sr, torch
    except Exception:
        # Fallback to torchaudio for formats unsupported by soundfile.
        torchaudio, _ = ensure_torchaudio_legacy_backend_api()
        try:
            waveform, sample_rate = torchaudio.load(str(media_path))
        except Exception as exc:
            raise RuntimeError(
                "Failed to load audio for diarization. Install 'soundfile' (preferred) "
                "or 'torchcodec' for torchaudio decoding support."
            ) from exc

        if waveform.ndim == 1:
            waveform = waveform.unsqueeze(0)
        if waveform.shape[0] > 1:
            waveform = waveform.mean(dim=0, keepdim=True)
        if int(sample_rate) != target_sr:
            waveform = torchaudio.functional.resample(waveform, int(sample_rate), target_sr)
        return waveform.contiguous(), target_sr, torch


def _build_embedding_windows(
    segments: Sequence[TranscriptSegment],
    *,
    backend: str,
    accuracy_profile: str,
) -> list[_EmbeddingWindow]:
    if not segments:
        return []

    profile = normalize_accuracy_profile(accuracy_profile)
    settings = _ACCURACY_PROFILE_SETTINGS[profile]

    target_duration = (
        settings["target_duration_fast"]
        if backend == LOCAL_BACKEND_FAST
        else settings["target_duration_accurate"]
    )
    min_duration = settings["min_duration"]
    max_duration = (
        settings["max_duration_fast"]
        if backend == LOCAL_BACKEND_FAST
        else settings["max_duration_accurate"]
    )
    max_segments = (
        settings["max_segments_fast"]
        if backend == LOCAL_BACKEND_FAST
        else settings["max_segments_accurate"]
    )
    max_gap = settings["max_gap"]

    windows: list[_EmbeddingWindow] = []
    current_indexes: list[int] = []
    current_start = 0.0
    current_end = 0.0

    def flush_window():
        nonlocal current_indexes, current_start, current_end
        if not current_indexes:
            return
        windows.append(
            _EmbeddingWindow(
                start=float(current_start),
                end=float(current_end),
                segment_indexes=list(current_indexes),
            )
        )
        current_indexes = []
        current_start = 0.0
        current_end = 0.0

    for idx, seg in enumerate(segments):
        seg_start = float(seg.start)
        seg_end = max(seg_start + 0.02, float(seg.end))

        if not current_indexes:
            current_indexes = [idx]
            current_start = seg_start
            current_end = seg_end
            continue

        gap = seg_start - current_end
        projected_duration = seg_end - current_start
        should_split = (
            gap > max_gap
            or projected_duration > max_duration
            or len(current_indexes) >= max_segments
        )
        if should_split:
            flush_window()
            current_indexes = [idx]
            current_start = seg_start
            current_end = seg_end
            continue

        current_indexes.append(idx)
        current_end = seg_end
        current_duration = current_end - current_start
        if current_duration >= target_duration:
            flush_window()

    flush_window()

    merged: list[_EmbeddingWindow] = []
    for window in windows:
        duration = max(0.0, window.end - window.start)
        if duration >= min_duration or not merged:
            merged.append(window)
            continue
        prev = merged[-1]
        prev.end = max(prev.end, window.end)
        prev.segment_indexes.extend(window.segment_indexes)

    return merged


def _slice_audio_chunk(
    waveform,
    sample_rate: int,
    start: float,
    end: float,
    torch_mod,
):
    total = int(waveform.shape[-1])
    a = max(0, min(total, int(start * sample_rate)))
    b = max(a + 1, min(total, int(end * sample_rate)))
    chunk = waveform[:, a:b]

    min_samples = int(0.6 * sample_rate)
    if int(chunk.shape[-1]) < min_samples:
        pad = min_samples - int(chunk.shape[-1])
        chunk = torch_mod.nn.functional.pad(chunk, (0, pad))

    return chunk


def _load_speaker_encoder(prefer_gpu: bool, log):
    _, compat_patched = ensure_torchaudio_legacy_backend_api()
    try:
        import torch  # type: ignore
        from speechbrain.inference.speaker import EncoderClassifier  # type: ignore
        from speechbrain.utils.fetching import LocalStrategy  # type: ignore
    except Exception as exc:
        raise RuntimeError(
            "Diarization requires 'speechbrain' package for local speaker embeddings."
        ) from exc

    if compat_patched:
        log("[INFO] Applied torchaudio compatibility shim for speechbrain backend API.\n")

    device = "cuda" if (prefer_gpu and torch.cuda.is_available()) else "cpu"
    cache_dir = user_data_dir() / "cache" / "speechbrain" / "spkrec-ecapa-voxceleb"
    cache_dir.mkdir(parents=True, exist_ok=True)
    hf_cache_dir = user_data_dir() / "cache" / "huggingface"
    hf_cache_dir.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HF_HOME", str(hf_cache_dir))
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")

    log(
        "[INFO] Loading local speaker encoder: "
        f"{LOCAL_SPEAKER_ENCODER_NAME} (device={device})\n"
    )
    log("[INFO] Speaker encoder fetch strategy: COPY (Windows-safe, no symlink required)\n")
    classifier = EncoderClassifier.from_hparams(
        source=LOCAL_SPEAKER_ENCODER_NAME,
        savedir=str(cache_dir),
        run_opts={"device": device},
        local_strategy=LocalStrategy.COPY,
        huggingface_cache_dir=str(hf_cache_dir),
    )
    return classifier, torch


def _extract_embeddings(
    windows: list[_EmbeddingWindow],
    waveform,
    sample_rate: int,
    classifier,
    torch_mod,
    log,
    set_step_progress,
    stop_flag,
):
    import numpy as np  # type: ignore

    total = max(1, len(windows))
    for idx, window in enumerate(windows, start=1):
        if stop_flag and stop_flag.is_set():
            raise RuntimeError("Stopped by user.")

        chunk = _slice_audio_chunk(
            waveform,
            sample_rate,
            float(window.start),
            float(window.end),
            torch_mod,
        )
        signal = chunk.to(dtype=torch_mod.float32)
        if signal.ndim != 2:
            signal = signal.squeeze(0)
            if signal.ndim == 1:
                signal = signal.unsqueeze(0)

        with torch_mod.no_grad():
            emb_tensor = classifier.encode_batch(signal)
        emb = emb_tensor.squeeze().detach().cpu().numpy()
        emb = np.asarray(emb, dtype=np.float32).reshape(-1)
        norm = float(np.linalg.norm(emb))
        if norm > 0:
            emb = emb / norm
        window.embedding = emb

        progress = 20.0 + (float(idx) / float(total)) * 50.0
        set_step_progress(progress)
        if idx == 1 or idx == total or idx % 8 == 0:
            log(f"[INFO] Speaker embedding windows: {idx}/{total}\n")


def _cluster_window_embeddings(
    windows: list[_EmbeddingWindow],
    *,
    backend: str,
    accuracy_profile: str,
    min_speakers: int,
    max_speakers: int,
):
    import numpy as np  # type: ignore
    from sklearn.cluster import AgglomerativeClustering  # type: ignore
    from sklearn.metrics import silhouette_score  # type: ignore

    vectors = []
    for window in windows:
        if window.embedding is None:
            continue
        vectors.append(window.embedding)

    if not vectors:
        return [0] * len(windows), [0.0] * len(windows), 1

    emb = np.vstack(vectors).astype(np.float32)
    count = int(emb.shape[0])
    if count == 1:
        return [0], [1.0], 1

    profile = normalize_accuracy_profile(accuracy_profile)
    settings = _ACCURACY_PROFILE_SETTINGS[profile]

    min_count = max(1, int(min_speakers or 0))
    min_count = min(min_count, count)
    if max_speakers and int(max_speakers) > 0:
        max_count = min(int(max_speakers), count)
    else:
        auto_cap = (
            settings["auto_cap_fast"]
            if backend == LOCAL_BACKEND_FAST
            else settings["auto_cap_accurate"]
        )
        max_count = min(auto_cap, count)
    if max_count < min_count:
        max_count = min_count

    def run_cluster(k: int):
        if k <= 1:
            return np.zeros(count, dtype=int)
        try:
            model = AgglomerativeClustering(
                n_clusters=k,
                metric="cosine",
                linkage="average",
            )
        except TypeError:
            model = AgglomerativeClustering(  # pragma: no cover
                n_clusters=k,
                affinity="cosine",
                linkage="average",
            )
        return model.fit_predict(emb)

    best_labels = np.zeros(count, dtype=int)
    best_clusters = 1
    best_score = -999.0

    for k in range(min_count, max_count + 1):
        labels = run_cluster(k)
        unique = int(len(set(int(x) for x in labels.tolist())))
        if unique <= 1:
            raw_score = -0.25
        else:
            if count <= unique:
                raw_score = -0.1
            else:
                raw_score = float(silhouette_score(emb, labels, metric="cosine"))

        penalty_multiplier = (
            settings["cluster_penalty_fast"]
            if backend == LOCAL_BACKEND_FAST
            else settings["cluster_penalty_accurate"]
        )
        penalty = penalty_multiplier * k
        score = raw_score - penalty
        if score > best_score:
            best_score = score
            best_labels = labels
            best_clusters = unique

    centroids: dict[int, Any] = {}
    for cluster in sorted(set(int(x) for x in best_labels.tolist())):
        members = emb[best_labels == cluster]
        centroid = members.mean(axis=0)
        norm = float(np.linalg.norm(centroid))
        if norm > 0:
            centroid = centroid / norm
        centroids[cluster] = centroid

    confidences: list[float] = []
    for idx, cluster_raw in enumerate(best_labels.tolist()):
        cluster = int(cluster_raw)
        centroid = centroids.get(cluster)
        if centroid is None:
            confidences.append(0.0)
            continue
        similarity = float(np.dot(emb[idx], centroid))
        confidence = max(0.0, min(1.0, (similarity + 1.0) * 0.5))
        confidences.append(confidence)

    return [int(x) for x in best_labels.tolist()], confidences, best_clusters


def _apply_labels_to_windows(
    windows: list[_EmbeddingWindow],
    labels: Sequence[int],
    confidences: Sequence[float],
):
    cluster_first_index: dict[int, int] = {}
    for idx, cluster in enumerate(labels):
        cluster_first_index.setdefault(int(cluster), idx)

    ordered_clusters = sorted(cluster_first_index.keys(), key=lambda c: cluster_first_index[c])
    cluster_map = {
        cluster: f"SPEAKER_{index:02d}"
        for index, cluster in enumerate(ordered_clusters)
    }

    for idx, window in enumerate(windows):
        cluster = int(labels[idx]) if idx < len(labels) else 0
        window.speaker_id = cluster_map.get(cluster, _DEFAULT_SPEAKER_ID)
        if idx < len(confidences):
            window.confidence = max(0.0, min(1.0, float(confidences[idx])))
        else:
            window.confidence = 0.0


def _build_speaker_turns_from_windows(
    windows: list[_EmbeddingWindow],
    segments: Sequence[TranscriptSegment],
    *,
    accuracy_profile: str,
) -> list[SpeakerTurn]:
    if not windows or not segments:
        return []

    profile = normalize_accuracy_profile(accuracy_profile)
    merge_gap = float(_ACCURACY_PROFILE_SETTINGS[profile]["turn_merge_gap"])

    segment_speakers = [_DEFAULT_SPEAKER_ID for _ in segments]
    segment_conf = [0.0 for _ in segments]
    for window in windows:
        for seg_idx in window.segment_indexes:
            if seg_idx < 0 or seg_idx >= len(segments):
                continue
            segment_speakers[seg_idx] = window.speaker_id
            segment_conf[seg_idx] = max(segment_conf[seg_idx], float(window.confidence))

    turns: list[SpeakerTurn] = []
    current_label: str | None = None
    current_start = 0.0
    current_end = 0.0
    confidence_sum = 0.0
    confidence_count = 0

    for idx, seg in enumerate(segments):
        start = float(seg.start)
        end = max(start, float(seg.end))
        label = _normalize_speaker_id(segment_speakers[idx])
        conf = float(segment_conf[idx])

        if current_label is None:
            current_label = label
            current_start = start
            current_end = end
            confidence_sum = conf
            confidence_count = 1
            continue

        gap = start - current_end
        if label == current_label and gap <= merge_gap:
            current_end = max(current_end, end)
            confidence_sum += conf
            confidence_count += 1
            continue

        turns.append(
            SpeakerTurn(
                start=current_start,
                end=current_end,
                speaker_id=current_label,
                confidence=(confidence_sum / float(max(1, confidence_count))),
            )
        )
        current_label = label
        current_start = start
        current_end = end
        confidence_sum = conf
        confidence_count = 1

    if current_label is not None:
        turns.append(
            SpeakerTurn(
                start=current_start,
                end=current_end,
                speaker_id=current_label,
                confidence=(confidence_sum / float(max(1, confidence_count))),
            )
        )

    return turns


def diarize_media(
    media_path: Path,
    segments: Sequence[TranscriptSegment],
    prefer_gpu: bool,
    backend: str,
    accuracy_profile: str,
    min_speakers: int,
    max_speakers: int,
    log,
    set_step_progress,
    stop_flag,
) -> List[SpeakerTurn]:
    """Run local-only speaker diarization and return normalized speaker turns."""
    selected_backend = str(backend or LOCAL_BACKEND_ACCURATE).strip().lower()
    if selected_backend not in {LOCAL_BACKEND_FAST, LOCAL_BACKEND_ACCURATE}:
        raise RuntimeError(f"Unsupported local diarization backend: {selected_backend}")
    selected_accuracy = normalize_accuracy_profile(accuracy_profile)

    if not segments:
        return []

    set_step_progress(5.0)
    waveform, sample_rate, torch_mod = _load_audio_waveform(media_path)
    classifier, _ = _load_speaker_encoder(prefer_gpu=prefer_gpu, log=log)
    set_step_progress(18.0)

    windows = _build_embedding_windows(
        segments,
        backend=selected_backend,
        accuracy_profile=selected_accuracy,
    )
    if not windows:
        return []

    log(
        "[INFO] Local diarization settings: "
        f"backend={selected_backend}, windows={len(windows)}, "
        f"accuracy={selected_accuracy}, "
        f"min_speakers={min_speakers or 'auto'}, max_speakers={max_speakers or 'auto'}\n"
    )

    _extract_embeddings(
        windows,
        waveform,
        sample_rate,
        classifier,
        torch_mod,
        log,
        set_step_progress,
        stop_flag,
    )

    labels, confidences, cluster_count = _cluster_window_embeddings(
        windows,
        backend=selected_backend,
        accuracy_profile=selected_accuracy,
        min_speakers=min_speakers,
        max_speakers=max_speakers,
    )
    _apply_labels_to_windows(windows, labels, confidences)
    turns = _build_speaker_turns_from_windows(
        windows,
        segments,
        accuracy_profile=selected_accuracy,
    )
    turns.sort(key=lambda turn: (turn.start, turn.end, turn.speaker_id))

    speaker_ids = sorted({turn.speaker_id for turn in turns}, key=_speaker_sort_key)
    log(f"[INFO] Speaker turns found: {len(turns)}\n")
    log(f"[INFO] Speakers detected: {', '.join(speaker_ids) if speaker_ids else _DEFAULT_SPEAKER_ID}\n")
    log(f"[INFO] Local diarization clusters: {cluster_count}\n")
    set_step_progress(100.0)

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
    lecture_description: str = "",
    lecture_date: str = "",
    conference_title: str = "",
    conference_date: str = "",
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
        "lecture_description": str(lecture_description or "").strip(),
        "lecture_date": str(lecture_date or "").strip(),
        "conference_title": str(conference_title or "").strip(),
        "conference_date": str(conference_date or "").strip(),
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
        "lecture_description": str(data.get("lecture_description") or "").strip(),
        "lecture_date": str(data.get("lecture_date") or "").strip(),
        "conference_title": str(data.get("conference_title") or "").strip(),
        "conference_date": str(data.get("conference_date") or "").strip(),
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


def _coerce_int(value, default: int = 0) -> int:
    try:
        return int(value)
    except Exception:
        return int(default)


def _optional_path(value: str | None) -> Path | None:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        return Path(text)
    except Exception:
        return None


def rewrite_sidecar_exports(sidecar: dict) -> List[Path]:
    """Rewrite transcript/SRT files from a loaded sidecar after speaker remap."""
    segments = sidecar.get("segments") or []
    if not segments:
        return []

    base_name = str(sidecar.get("base_name") or "").strip()
    if not base_name:
        return []

    transcripts_dir_raw = str(sidecar.get("transcripts_dir") or "").strip()
    if not transcripts_dir_raw:
        return []
    transcripts_dir = Path(transcripts_dir_raw)
    transcripts_dir.mkdir(parents=True, exist_ok=True)

    media_path_raw = str(sidecar.get("media_path") or "").strip()
    media_path = Path(media_path_raw) if media_path_raw else Path(base_name)

    result = TranscriptionResult(
        srt_path=Path(str(sidecar.get("srt_source_path") or "")),
        raw_txt_path=Path(""),
        segments=list(segments),
        detected_lang=str(sidecar.get("detected_lang") or "auto"),
        duration=float(sidecar.get("duration", 0.0) or 0.0),
        device=str(sidecar.get("device") or "cpu"),
        compute_type=str(sidecar.get("compute_type") or "int8"),
        speaker_turns=list(sidecar.get("speaker_turns") or []),
        speaker_map=dict(sidecar.get("speaker_map") or {}),
    )

    clean_text = bool(sidecar.get("clean_text", False))
    export_md = bool(sidecar.get("export_md", False))
    include_unmapped_speakers = bool(sidecar.get("include_unmapped_speakers", True))
    split_minutes = max(0, _coerce_int(sidecar.get("split_minutes"), 0))
    part_seconds = split_minutes * 60 if split_minutes > 0 else 0
    parts = split_segments(result.segments, part_seconds)
    total_parts = len(parts) or 1

    model_name = str(sidecar.get("model_name") or "medium")
    output_mode = str(sidecar.get("output_mode") or "txt_only")

    speaker = str(sidecar.get("speaker") or "").strip()
    topic = str(sidecar.get("topic") or "").strip()
    lecture_description = str(sidecar.get("lecture_description") or "").strip()
    lecture_date = str(sidecar.get("lecture_date") or "").strip()
    conference_title = str(sidecar.get("conference_title") or "").strip()
    conference_date = str(sidecar.get("conference_date") or "").strip()

    rewritten: List[Path] = []
    for idx, segs in enumerate(parts, start=1):
        part_suffix = f"_part{idx:02d}" if total_parts > 1 else ""
        transcript_body = (
            render_clean_transcript(segs, include_unmapped_speakers=include_unmapped_speakers)
            if clean_text
            else render_raw_transcript(segs, include_unmapped_speakers=include_unmapped_speakers)
        )
        txt_path = transcripts_dir / f"{base_name}{part_suffix}.txt"
        txt_path.write_text(transcript_body, encoding="utf-8")
        rewritten.append(txt_path)

        if export_md:
            metadata = build_metadata(
                media_path=media_path,
                result=result,
                model_name=model_name,
                output_mode=output_mode,
                clean_text=clean_text,
                export_md=export_md,
                split_minutes=split_minutes,
                part_idx=idx,
                part_total=total_parts,
                speaker=speaker,
                topic=topic,
                lecture_description=lecture_description,
                lecture_date=lecture_date,
                conference_title=conference_title,
                conference_date=conference_date,
                speaker_map=result.speaker_map,
            )
            md_path = transcripts_dir / f"{base_name}{part_suffix}.md"
            md_path.write_text(
                render_transcript_markdown(media_path, metadata, transcript_body),
                encoding="utf-8",
            )
            rewritten.append(md_path)

    if bool(sidecar.get("speaker_prefix_in_srt", False)):
        srt_source_path = _optional_path(sidecar.get("srt_source_path"))
        if srt_source_path and srt_source_path.is_file():
            rewrite_srt_speaker_prefixes(
                srt_source_path,
                srt_source_path,
                result.segments,
                include_unmapped_speakers=include_unmapped_speakers,
            )
            rewritten.append(srt_source_path)

        srt_translated_path = _optional_path(sidecar.get("srt_translated_path"))
        if srt_translated_path and srt_translated_path.is_file():
            rewrite_srt_speaker_prefixes(
                srt_translated_path,
                srt_translated_path,
                result.segments,
                include_unmapped_speakers=include_unmapped_speakers,
            )
            rewritten.append(srt_translated_path)

    deduped: List[Path] = []
    seen: set[str] = set()
    for path in rewritten:
        key = str(path)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(path)
    return deduped


def apply_speaker_mapping_to_sidecar_file(sidecar_path: Path, speaker_map: Dict[str, str]) -> dict:
    """Apply mapping to sidecar file and rewrite transcript artifacts in-place."""
    sidecar = load_speaker_sidecar(sidecar_path)
    segments = sidecar.get("segments") or []
    current_map = {
        _normalize_speaker_id(str(key)): str(value or "").strip()
        for key, value in (sidecar.get("speaker_map") or {}).items()
        if str(key or "").strip()
    }
    for key, value in (speaker_map or {}).items():
        label = _normalize_speaker_id(str(key))
        if not label:
            continue
        current_map[label] = str(value or "").strip()

    normalized_map = apply_speaker_mapping(segments, current_map)
    sidecar["speaker_map"] = normalized_map
    rewritten_paths = rewrite_sidecar_exports(sidecar)
    sidecar["segments"] = serialize_segments(segments)
    sidecar["speaker_turns"] = serialize_speaker_turns(sidecar.get("speaker_turns") or [])
    save_speaker_sidecar(sidecar_path, sidecar)
    return {
        "sidecar": sidecar,
        "speaker_map": normalized_map,
        "rewritten_paths": [str(path) for path in rewritten_paths],
    }
