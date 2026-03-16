"""YouTube download helpers using yt-dlp."""

from __future__ import annotations

import logging
from pathlib import Path

from ..core.paths import ffmpeg_path

LOGGER = logging.getLogger(__name__)


def quality_to_format(quality: str, *, allow_merge: bool = True, audio_only: bool = False) -> str:
    if audio_only:
        return "bestaudio[ext=m4a]/bestaudio[acodec!=none]/bestaudio/best[acodec!=none]/best"

    if quality == "best":
        if allow_merge:
            return "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
        return "best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best"

    height = quality.replace("p", "")
    if allow_merge:
        return f"bestvideo[height<={height}][ext=mp4]+bestaudio[ext=m4a]/best[height<={height}][ext=mp4]/best"
    return (
        f"best[height<={height}][ext=mp4][vcodec!=none][acodec!=none]/"
        f"best[height<={height}][vcodec!=none][acodec!=none]/best"
    )


def download_single_or_playlist(
    url: str,
    workdir: Path,
    is_playlist: bool,
    quality: str,
    output_mode: str,
    log,
    set_step_progress,
    stop_flag,
):
    import yt_dlp
    from yt_dlp.utils import DownloadError

    outdir = workdir / "downloads"
    outdir.mkdir(parents=True, exist_ok=True)

    output_tpl = str(outdir / "%(playlist_index|000)03d - %(title)s [%(id)s].%(ext)s")
    ff = ffmpeg_path()
    ffmpeg_available = bool(ff)
    prefer_audio_only = not ffmpeg_available and output_mode not in {"video_subs", "video_dub"}
    fmt = quality_to_format(quality, allow_merge=ffmpeg_available, audio_only=prefer_audio_only)
    ffmpeg_dir = str(Path(ff).parent) if ff else None

    log(f"[INFO] Download settings: quality={quality}, playlist={is_playlist}\n")
    log(f"[INFO] Download directory: {outdir}\n")
    if not ffmpeg_available:
        if prefer_audio_only:
            log("[WARN] ffmpeg not found; using no-merge audio-first YouTube download fallback.\n")
        else:
            log("[WARN] ffmpeg not found; using single-file no-merge YouTube download fallback.\n")

    last_logged_pct = -10.0

    def progress_hook(d: dict):
        nonlocal last_logged_pct

        if stop_flag and stop_flag.is_set():
            raise DownloadError("Stopped by user.")

        status = d.get("status")
        if status == "finished":
            filename = d.get("filename") or d.get("info_dict", {}).get("_filename")
            if filename:
                log(f"[OK] Download finished: {filename}\n")
            set_step_progress(100.0)
            return

        if status != "downloading":
            return

        total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
        downloaded = d.get("downloaded_bytes") or 0
        if not total:
            return

        pct = (downloaded / total) * 100.0
        set_step_progress(pct)
        if pct - last_logged_pct >= 10.0:
            last_logged_pct = pct
            log(f"[INFO] Download progress: {pct:.0f}%\n")

    ydl_opts = {
        "format": fmt,
        "outtmpl": output_tpl,
        "noplaylist": not is_playlist,
        "extractor_args": {"youtube": {"player_client": ["default"]}},
        "progress_hooks": [progress_hook],
        "logger": None,
        "quiet": True,
        "no_warnings": True,
    }
    if ffmpeg_dir:
        ydl_opts["ffmpeg_location"] = ffmpeg_dir

    log("[INFO] Downloading via yt-dlp...\n")
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
    except DownloadError as exc:
        LOGGER.exception("yt-dlp download failed")
        if stop_flag and stop_flag.is_set():
            raise RuntimeError("Stopped by user.") from exc
        raise RuntimeError(str(exc)) from exc

    set_step_progress(100)
    return outdir
