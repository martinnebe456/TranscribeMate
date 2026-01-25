"""Transcript rendering, summary prompts, and export helpers."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple

from .files import split_segments, timestamped_base_name, unique_path
from .i18n import CLEAN_PAUSE_SECONDS
from .types import TranscriptionResult

SUMMARY_TEMPLATE_KEYS: Sequence[str] = (
    "detailed",
    "executive",
    "actions",
    "qa",
    "confluence",
)


def format_hms(seconds: float) -> str:
    total = max(0, int(seconds))
    hours = total // 3600
    minutes = (total % 3600) // 60
    secs = total % 60
    if hours:
        return f"{hours:02}:{minutes:02}:{secs:02}"
    return f"{minutes:02}:{secs:02}"


def format_duration(seconds: float) -> str:
    return format_hms(seconds)


def render_raw_transcript(segments: Iterable) -> str:
    lines: List[str] = []
    for seg in segments:
        text = seg.text.strip()
        if text:
            lines.append(text)
    body = "\n".join(lines).strip()
    return body + ("\n" if body else "")


def render_clean_transcript(segments: Iterable, pause_threshold: float = CLEAN_PAUSE_SECONDS) -> str:
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


def _clean_paragraphs_with_timestamps(
    segments: Iterable,
    pause_threshold: float = CLEAN_PAUSE_SECONDS,
) -> List[Tuple[float, str]]:
    paragraphs: List[Tuple[float, str]] = []
    current_parts: List[str] = []
    current_start: float | None = None
    prev_norm = ""
    prev_end = None

    for seg in segments:
        text = seg.text.strip()
        if not text:
            continue
        norm = " ".join(text.lower().split())
        if prev_norm == norm:
            continue

        seg_start = float(seg.start)
        gap = (seg_start - prev_end) if prev_end is not None else 0.0
        if prev_end is not None and gap >= pause_threshold and current_parts:
            paragraphs.append((current_start or seg_start, " ".join(current_parts).strip()))
            current_parts = []
            current_start = None

        if current_start is None:
            current_start = seg_start
        current_parts.append(text)
        prev_end = float(seg.end)
        prev_norm = norm

    if current_parts:
        paragraphs.append((current_start or 0.0, " ".join(current_parts).strip()))

    return [(ts, text) for ts, text in paragraphs if text]


def _raw_lines_with_timestamps(segments: Iterable) -> List[Tuple[float, str]]:
    lines: List[Tuple[float, str]] = []
    for seg in segments:
        text = seg.text.strip()
        if not text:
            continue
        lines.append((float(seg.start), text))
    return lines


def render_timestamped_transcript(
    segments: Iterable,
    clean_text: bool,
    pause_threshold: float = CLEAN_PAUSE_SECONDS,
) -> str:
    entries = (
        _clean_paragraphs_with_timestamps(segments, pause_threshold)
        if clean_text
        else _raw_lines_with_timestamps(segments)
    )
    lines: List[str] = []
    for ts, text in entries:
        lines.append(f"[{format_hms(ts)}] {text}")
    body = "\n\n".join(lines).strip()
    return body + ("\n" if body else "")


def build_metadata(
    media_path: Path,
    result: TranscriptionResult,
    model_name: str,
    output_mode: str,
    clean_text: bool,
    export_md: bool,
    split_minutes: int,
    part_idx: int,
    part_total: int,
) -> Dict[str, str]:
    generated = datetime.now().isoformat(timespec="minutes")
    device_label = result.device
    if result.compute_type:
        device_label = f"{device_label} ({result.compute_type})"

    metadata: Dict[str, str] = {
        "Title": media_path.stem,
        "Source file": media_path.name,
        "Generated": generated,
        "Detected language": result.detected_lang,
        "Duration": format_duration(result.duration),
        "Whisper model": model_name,
        "Device": device_label,
        "Mode": output_mode,
        "Clean text": "yes" if clean_text else "no",
        "Markdown export": "yes" if export_md else "no",
        "Split minutes": str(split_minutes),
    }
    if part_total > 1:
        metadata["Part"] = f"{part_idx}/{part_total}"
    return metadata


def render_metadata_block(metadata: Dict[str, str]) -> str:
    lines = ["## Metadata", ""]
    for key, value in metadata.items():
        lines.append(f"- {key}: `{value}`")
    lines.append("")
    return "\n".join(lines)


def render_transcript_markdown(
    media_path: Path,
    metadata: Dict[str, str],
    body: str,
) -> str:
    header_lines = [
        f"# {media_path.stem}",
        "",
        render_metadata_block(metadata).strip(),
        "",
        "## Transcript",
        "",
        body.strip(),
        "",
    ]
    return "\n".join(header_lines)


def _summary_instructions(style: str) -> str:
    common = (
        "Rules:\n"
        "- Use only information from the transcript. If something is unclear, say so.\n"
        "- Do not hallucinate names, companies, numbers, or decisions.\n"
        "- When referencing important claims, numbers, decisions, or quotes, include a timestamp like [12:34].\n"
        "- Keep the structure clean and easy to paste into Confluence.\n"
    )

    if style == "executive":
        return (
            "Task: Create an executive summary for colleagues who were not in the room.\n"
            "Focus on the key message, outcomes, and why it matters.\n"
            "Structure:\n"
            "1. Overview\n"
            "2. Key takeaways (5-10 bullets)\n"
            "3. Decisions & commitments\n"
            "4. Action items\n\n"
            f"{common}"
        )
    if style == "actions":
        return (
            "Task: Extract decisions, commitments, risks, and action items.\n"
            "Structure:\n"
            "1. Decisions\n"
            "2. Action items (table: owner | task | due date | timestamp)\n"
            "3. Risks / open questions\n"
            "4. Follow-ups\n\n"
            f"{common}"
        )
    if style == "qa":
        return (
            "Task: Summarize the Q&A and discussion moments.\n"
            "Structure:\n"
            "1. Key questions\n"
            "2. Answers (paired with questions)\n"
            "3. Debates / disagreements\n"
            "4. Unanswered questions\n\n"
            f"{common}"
        )
    if style == "confluence":
        return (
            "Task: Produce Confluence-ready meeting notes in Markdown.\n"
            "Use this exact structure:\n"
            "# Title\n"
            "## Overview\n"
            "## Key Points\n"
            "## Detailed Notes (chronological)\n"
            "## Decisions\n"
            "## Action Items\n"
            "## Q&A Highlights\n"
            "## References (timestamps)\n\n"
            f"{common}"
        )

    # detailed (default)
    return (
        "Task: Create detailed conference notes as if the reader attended the session.\n"
        "Preserve the flow of ideas and the technical detail.\n"
        "Structure:\n"
        "1. Overview\n"
        "2. Detailed notes (chronological)\n"
        "3. Key insights\n"
        "4. Memorable quotes\n"
        "5. Action items / follow-ups\n"
        "6. Open questions\n\n"
        f"{common}"
    )


def render_summary_prompt(
    style: str,
    media_path: Path,
    metadata: Dict[str, str],
    transcript_ts: str,
) -> str:
    instructions = _summary_instructions(style)
    metadata_block = render_metadata_block(metadata)
    return (
        f"# Summary Prompt — {style}\n\n"
        f"## Instructions\n\n{instructions}\n\n"
        f"{metadata_block}\n"
        "## Transcript (Timestamped)\n\n"
        f"{transcript_ts.strip()}\n"
    )


def render_confluence_template(media_path: Path, metadata: Dict[str, str]) -> str:
    metadata_block = render_metadata_block(metadata)
    lines = [
        f"# {media_path.stem}",
        "",
        metadata_block.strip(),
        "",
        "## Overview",
        "",
        "_Add executive overview here._",
        "",
        "## Key Points",
        "",
        "- ",
        "",
        "## Detailed Notes (chronological)",
        "",
        "- ",
        "",
        "## Decisions",
        "",
        "- ",
        "",
        "## Action Items",
        "",
        "| Owner | Task | Due date | Timestamp |",
        "| --- | --- | --- | --- |",
        "|  |  |  |  |",
        "",
        "## Q&A Highlights",
        "",
        "- ",
        "",
        "## References (timestamps)",
        "",
        "- [00:00] ",
        "",
    ]
    return "\n".join(lines)


def export_summary_pack(
    media_path: Path,
    metadata: Dict[str, str],
    transcript_ts: str,
    summaries_dir: Path,
    base_name: str,
    part_suffix: str,
    log,
) -> List[Path]:
    outputs: List[Path] = []
    prompts_dir = summaries_dir / "prompts"
    confluence_dir = summaries_dir / "confluence"
    prompts_dir.mkdir(parents=True, exist_ok=True)
    confluence_dir.mkdir(parents=True, exist_ok=True)

    timestamped_path = unique_path(summaries_dir, f"{base_name}{part_suffix}.timestamped.md")
    timestamped_body = render_transcript_markdown(media_path, metadata, transcript_ts)
    timestamped_path.write_text(timestamped_body, encoding="utf-8")
    outputs.append(timestamped_path)
    log(f"[OK] Timestamped transcript saved: {timestamped_path}\n")

    for style in SUMMARY_TEMPLATE_KEYS:
        prompt_path = unique_path(prompts_dir, f"{base_name}{part_suffix}.summary_{style}.md")
        prompt_body = render_summary_prompt(style, media_path, metadata, transcript_ts)
        prompt_path.write_text(prompt_body, encoding="utf-8")
        outputs.append(prompt_path)
        log(f"[OK] Summary prompt saved: {prompt_path}\n")

    confluence_path = unique_path(confluence_dir, f"{base_name}{part_suffix}.confluence_template.md")
    confluence_body = render_confluence_template(media_path, metadata)
    confluence_path.write_text(confluence_body, encoding="utf-8")
    outputs.append(confluence_path)
    log(f"[OK] Confluence template saved: {confluence_path}\n")

    return outputs


def export_transcripts(
    media_path: Path,
    result: TranscriptionResult,
    transcripts_dir: Path,
    clean_text: bool,
    export_md: bool,
    split_minutes: int,
    generate_summary_pack: bool,
    summaries_dir: Path,
    output_mode: str,
    model_name: str,
    log,
) -> List[Path]:
    part_seconds = int(split_minutes) * 60 if int(split_minutes) > 0 else 0
    parts = split_segments(result.segments, part_seconds)
    base_name = timestamped_base_name(media_path)
    outputs: List[Path] = []

    for idx, segs in enumerate(parts, start=1):
        part_suffix = f"_part{idx:02d}" if len(parts) > 1 else ""
        metadata = build_metadata(
            media_path=media_path,
            result=result,
            model_name=model_name,
            output_mode=output_mode,
            clean_text=clean_text,
            export_md=export_md,
            split_minutes=split_minutes,
            part_idx=idx,
            part_total=len(parts),
        )

        transcript_body = render_clean_transcript(segs) if clean_text else render_raw_transcript(segs)
        txt_path = unique_path(transcripts_dir, f"{base_name}{part_suffix}.txt")
        txt_path.write_text(transcript_body, encoding="utf-8")
        outputs.append(txt_path)
        log(f"[OK] Transcript saved: {txt_path}\n")

        if export_md:
            md_path = unique_path(transcripts_dir, f"{base_name}{part_suffix}.md")
            md_body = render_transcript_markdown(media_path, metadata, transcript_body)
            md_path.write_text(md_body, encoding="utf-8")
            outputs.append(md_path)
            log(f"[OK] Markdown saved: {md_path}\n")

        if generate_summary_pack:
            transcript_ts = render_timestamped_transcript(segs, clean_text=clean_text)
            outputs.extend(
                export_summary_pack(
                    media_path=media_path,
                    metadata=metadata,
                    transcript_ts=transcript_ts,
                    summaries_dir=summaries_dir,
                    base_name=base_name,
                    part_suffix=part_suffix,
                    log=log,
                )
            )

    return outputs
