"""Subtitle embedding helpers."""

from __future__ import annotations

from pathlib import Path

from ..core.paths import ffmpeg_path
from ..core.process import safe_run


def hex_to_ass_color(hex_color: str) -> str:
    hex_color = hex_color.lstrip("#")
    r, g, b = int(hex_color[0:2], 16), int(hex_color[2:4], 16), int(hex_color[4:6], 16)
    return f"&H{b:02X}{g:02X}{r:02X}&"


def hard_subtitles(video_path: Path, srt_path: Path, out_mp4: Path,
                   font: str, size: int, color: str, outline_color: str, outline_width: int,
                   log, set_step_indeterminate, stop_flag):
    ff = ffmpeg_path()
    if not ff:
        raise RuntimeError("ffmpeg not found (bundled or PATH).")

    sub = str(srt_path).replace("\\", "\\\\").replace(":", "\\:")

    primary_color = hex_to_ass_color(color)
    border_color = hex_to_ass_color(outline_color)
    style = (
        f"FontName={font},FontSize={size},PrimaryColour={primary_color},"
        f"OutlineColour={border_color},Outline={outline_width},BorderStyle=1"
    )

    cmd = [
        ff,
        "-y",
        "-i",
        str(video_path),
        "-vf",
        f"subtitles={sub}:force_style='{style}'",
        "-c:a",
        "copy",
        str(out_mp4),
    ]
    set_step_indeterminate(True)
    safe_run(cmd, on_line=log, stop_flag=stop_flag)
    set_step_indeterminate(False)


def soft_subtitles(video_path: Path, srt_path: Path, out_mp4: Path, lang_code: str,
                   log, set_step_indeterminate, stop_flag):
    ff = ffmpeg_path()
    if not ff:
        raise RuntimeError("ffmpeg not found (bundled or PATH).")

    cmd = [
        ff,
        "-y",
        "-i",
        str(video_path),
        "-i",
        str(srt_path),
        "-map",
        "0:v:0",
        "-map",
        "0:a?",
        "-map",
        "1:0",
        "-c:v",
        "copy",
        "-c:a",
        "copy",
        "-c:s",
        "mov_text",
        "-metadata:s:s:0",
        f"language={lang_code}",
        str(out_mp4),
    ]
    set_step_indeterminate(True)
    safe_run(cmd, on_line=log, stop_flag=stop_flag)
    set_step_indeterminate(False)
