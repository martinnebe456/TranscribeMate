"""Video dubbing helpers (translated speech -> replaced audio track)."""

from __future__ import annotations

import asyncio
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Iterable, List

from ..core.paths import ffmpeg_path, ffprobe_path
from ..core.process import safe_run

_SPEAKER_PREFIX_PATTERN = re.compile(r"^(?P<prefix>[^:\n]{1,80}):\s*(?P<body>.+)$", re.DOTALL)
_SRT_BLOCK_SPLIT = re.compile(r"\n{2,}")

_VOICE_BY_LANG = {
    "cs": "cs-CZ-VlastaNeural",
    "sk": "sk-SK-ViktoriaNeural",
    "de": "de-DE-KatjaNeural",
    "pl": "pl-PL-ZofiaNeural",
    "fr": "fr-FR-DeniseNeural",
    "es": "es-ES-ElviraNeural",
    "it": "it-IT-ElsaNeural",
    "ru": "ru-RU-SvetlanaNeural",
    "uk": "uk-UA-PolinaNeural",
    "pt": "pt-PT-RaquelNeural",
    "en": "en-US-AriaNeural",
}


def _strip_speaker_prefix(text: str) -> str:
    cleaned = str(text or "").strip()
    match = _SPEAKER_PREFIX_PATTERN.match(cleaned)
    if not match:
        return cleaned
    body = match.group("body").strip()
    return body or cleaned


def _parse_srt_lines(srt_path: Path) -> List[str]:
    content = srt_path.read_text(encoding="utf-8")
    blocks = _SRT_BLOCK_SPLIT.split(content.strip())
    lines: List[str] = []
    for block in blocks:
        parts = block.splitlines()
        if len(parts) < 3:
            continue
        text = " ".join(part.strip() for part in parts[2:] if part.strip()).strip()
        if not text:
            continue
        text = _strip_speaker_prefix(text)
        text = re.sub(r"<[^>]+>", "", text).strip()
        if text:
            lines.append(text)
    return lines


def _chunk_text(text: str, *, max_chars: int = 300) -> List[str]:
    cleaned = str(text or "").strip()
    if not cleaned:
        return []
    if len(cleaned) <= max_chars:
        return [cleaned]

    chunks: List[str] = []
    current = []
    current_len = 0
    sentences = re.split(r"(?<=[.!?])\s+", cleaned)
    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue
        sentence_len = len(sentence) + (1 if current else 0)
        if current and current_len + sentence_len > max_chars:
            chunks.append(" ".join(current).strip())
            current = [sentence]
            current_len = len(sentence)
            continue
        current.append(sentence)
        current_len += sentence_len

    if current:
        chunks.append(" ".join(current).strip())

    if not chunks:
        return [cleaned]

    # Safety pass for abnormally long single sentence.
    normalized: List[str] = []
    for chunk in chunks:
        if len(chunk) <= max_chars:
            normalized.append(chunk)
            continue
        start = 0
        while start < len(chunk):
            normalized.append(chunk[start:start + max_chars].strip())
            start += max_chars
    return [item for item in normalized if item]


async def _synthesize_chunks_async(
    *,
    text_chunks: Iterable[str],
    voice: str,
    out_dir: Path,
    log,
    stop_flag,
    set_step_progress,
) -> List[Path]:
    import edge_tts  # type: ignore

    chunks = [str(chunk or "").strip() for chunk in text_chunks if str(chunk or "").strip()]
    total = max(1, len(chunks))
    out_paths: List[Path] = []

    for index, chunk in enumerate(chunks, start=1):
        if stop_flag.is_set():
            raise RuntimeError("Stopped by user.")
        out_path = out_dir / f"dub_{index:05d}.mp3"
        communicator = edge_tts.Communicate(chunk, voice=voice)
        await communicator.save(str(out_path))
        out_paths.append(out_path)
        pct = (index / total) * 65.0
        set_step_progress(pct)
        log(f"[INFO] TTS chunk {index}/{total}\n")

    return out_paths


def _probe_duration_seconds(path: Path, ffprobe_exe: str) -> float:
    cmd = [
        ffprobe_exe,
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        str(path),
    ]
    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        check=False,
        timeout=30,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ffprobe failed for {path.name}: {result.stderr.strip()}")
    try:
        return max(0.0, float((result.stdout or "").strip()))
    except Exception as exc:
        raise RuntimeError(f"Cannot parse media duration for {path.name}.") from exc


def _build_atempo_filters(factor: float) -> List[str]:
    value = max(0.01, float(factor))
    filters: List[str] = []
    while value > 2.0:
        filters.append("atempo=2.0")
        value /= 2.0
    while value < 0.5:
        filters.append("atempo=0.5")
        value /= 0.5
    filters.append(f"atempo={value:.6f}")
    return filters


def _write_concat_file(paths: Iterable[Path], concat_path: Path):
    lines: List[str] = []
    for item in paths:
        resolved = item.resolve().as_posix().replace("'", r"'\''")
        lines.append(f"file '{resolved}'")
    concat_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _voice_for_target_lang(target_lang: str) -> str:
    code = str(target_lang or "").strip().lower()
    return _VOICE_BY_LANG.get(code, _VOICE_BY_LANG["en"])


def dub_video_with_edge_tts(
    *,
    video_path: Path,
    translated_srt: Path,
    out_mp4: Path,
    target_lang: str,
    log,
    set_step_progress,
    set_step_indeterminate,
    stop_flag,
):
    ff = ffmpeg_path()
    ffprobe = ffprobe_path()
    if not ff:
        raise RuntimeError("ffmpeg not found (bundled or PATH).")
    if not ffprobe:
        raise RuntimeError("ffprobe not found (bundled or PATH).")

    subtitle_lines = _parse_srt_lines(translated_srt)
    if not subtitle_lines:
        raise RuntimeError("No translated subtitle lines available for dubbing.")

    voice = _voice_for_target_lang(target_lang)
    log(f"[INFO] Dubbing settings: voice={voice}, language={target_lang}\n")

    text_chunks: List[str] = []
    for line in subtitle_lines:
        text_chunks.extend(_chunk_text(line))
    if not text_chunks:
        raise RuntimeError("No text chunks available for TTS dubbing.")

    with tempfile.TemporaryDirectory(prefix="_tm_dub_") as tmp:
        tmp_dir = Path(tmp)
        tts_dir = tmp_dir / "tts"
        tts_dir.mkdir(parents=True, exist_ok=True)

        tts_chunks = asyncio.run(
            _synthesize_chunks_async(
                text_chunks=text_chunks,
                voice=voice,
                out_dir=tts_dir,
                log=log,
                stop_flag=stop_flag,
                set_step_progress=set_step_progress,
            )
        )
        if not tts_chunks:
            raise RuntimeError("TTS did not produce any audio chunks.")

        merged_mp3 = tmp_dir / "dub_merged.mp3"
        concat_file = tmp_dir / "tts_concat.txt"
        _write_concat_file(tts_chunks, concat_file)
        set_step_indeterminate(True)
        safe_run(
            [
                ff,
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(concat_file),
                "-c:a",
                "libmp3lame",
                "-q:a",
                "2",
                str(merged_mp3),
            ],
            on_line=log,
            stop_flag=stop_flag,
        )
        set_step_indeterminate(False)
        set_step_progress(72.0)

        video_duration = _probe_duration_seconds(video_path, ffprobe)
        merged_duration = _probe_duration_seconds(merged_mp3, ffprobe)
        if video_duration <= 0.0:
            raise RuntimeError(f"Video duration is invalid for dubbing: {video_path.name}")
        if merged_duration <= 0.0:
            raise RuntimeError("Generated TTS audio has invalid duration.")

        speed_factor = merged_duration / max(video_duration, 0.001)
        filter_parts = _build_atempo_filters(speed_factor)
        filter_parts.append(f"apad=pad_dur={video_duration:.3f}")
        filter_parts.append(f"atrim=duration={video_duration:.3f}")
        audio_filter = ",".join(filter_parts)

        adjusted_audio = tmp_dir / "dub_adjusted.wav"
        set_step_indeterminate(True)
        safe_run(
            [
                ff,
                "-y",
                "-i",
                str(merged_mp3),
                "-filter:a",
                audio_filter,
                "-ac",
                "2",
                "-ar",
                "48000",
                str(adjusted_audio),
            ],
            on_line=log,
            stop_flag=stop_flag,
        )
        set_step_indeterminate(False)
        set_step_progress(85.0)

        out_mp4.parent.mkdir(parents=True, exist_ok=True)
        set_step_indeterminate(True)
        safe_run(
            [
                ff,
                "-y",
                "-i",
                str(video_path),
                "-i",
                str(adjusted_audio),
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-c:v",
                "copy",
                "-c:a",
                "aac",
                "-shortest",
                str(out_mp4),
            ],
            on_line=log,
            stop_flag=stop_flag,
        )
        set_step_indeterminate(False)
        set_step_progress(100.0)
        log(f"[OK] Dubbed video saved: {out_mp4}\n")
