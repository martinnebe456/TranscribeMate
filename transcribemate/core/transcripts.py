"""Transcript rendering and export helpers."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import List

from .files import split_segments, timestamped_base_name, unique_path
from .i18n import CLEAN_PAUSE_SECONDS
from .types import TranscriptionResult


def render_raw_transcript(segments: List) -> str:
    lines = []
    for seg in segments:
        text = seg.text.strip()
        if text:
            lines.append(text)
    body = "\n".join(lines).strip()
    return body + ("\n" if body else "")


def render_clean_transcript(segments: List, pause_threshold: float = CLEAN_PAUSE_SECONDS) -> str:
    paragraphs: List[str] = []
    current_parts: List[str] = []
    prev_norm = ""
    prev_end = None

    for seg in segments:
        text = seg.text.strip()
        if not text:
            continue
        norm = " ".join(text.lower().split())
        if prev_norm == norm:
            continue

        gap = (float(seg.start) - prev_end) if prev_end is not None else 0.0
        if prev_end is not None and gap >= pause_threshold and current_parts:
            paragraphs.append(" ".join(current_parts).strip())
            current_parts = []

        current_parts.append(text)
        prev_end = float(seg.end)
        prev_norm = norm

    if current_parts:
        paragraphs.append(" ".join(current_parts).strip())

    body = "\n\n".join(p for p in paragraphs if p).strip()
    return body + ("\n" if body else "")


def render_markdown(media_path: Path, detected_lang: str, body: str, part_idx: int, part_total: int) -> str:
    generated = datetime.now().isoformat(timespec="minutes")
    header_lines = [
        f"# {media_path.stem}",
        "",
        f"- Source: `{media_path.name}`",
        f"- Language: `{detected_lang}`",
        f"- Generated: `{generated}`",
    ]
    if part_total > 1:
        header_lines.append(f"- Part: `{part_idx}/{part_total}`")
    header_lines.extend(["", body.strip(), ""])
    return "\n".join(header_lines)


def export_transcripts(media_path: Path, result: TranscriptionResult, transcripts_dir: Path,
                       clean_text: bool, export_md: bool, split_minutes: int, log) -> List[Path]:
    part_seconds = int(split_minutes) * 60 if int(split_minutes) > 0 else 0
    parts = split_segments(result.segments, part_seconds)
    base_name = timestamped_base_name(media_path)
    outputs: List[Path] = []

    for idx, segs in enumerate(parts, start=1):
        part_suffix = f"_part{idx:02d}" if len(parts) > 1 else ""
        transcript_body = render_clean_transcript(segs) if clean_text else render_raw_transcript(segs)
        txt_path = unique_path(transcripts_dir, f"{base_name}{part_suffix}.txt")
        txt_path.write_text(transcript_body, encoding="utf-8")
        outputs.append(txt_path)
        log(f"[OK] Transcript saved: {txt_path}\n")

        if export_md:
            md_path = unique_path(transcripts_dir, f"{base_name}{part_suffix}.md")
            md_body = render_markdown(media_path, result.detected_lang, transcript_body, idx, len(parts))
            md_path.write_text(md_body, encoding="utf-8")
            outputs.append(md_path)
            log(f"[OK] Markdown saved: {md_path}\n")

    return outputs
