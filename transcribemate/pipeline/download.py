"""YouTube download helpers using yt-dlp."""

from __future__ import annotations

from pathlib import Path

from ..core.paths import ffmpeg_path


def quality_to_format(quality: str) -> str:
    if quality == "best":
        return "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
    height = quality.replace("p", "")
    return f"bestvideo[height<={height}][ext=mp4]+bestaudio[ext=m4a]/best[height<={height}][ext=mp4]/best"


def download_single_or_playlist(
    url: str,
    workdir: Path,
    is_playlist: bool,
    quality: str,
    log,
    set_step_progress,
    stop_flag,
):
    import yt_dlp
    from yt_dlp.utils import DownloadError

    outdir = workdir / "downloads"
    outdir.mkdir(parents=True, exist_ok=True)

    output_tpl = str(outdir / "%(playlist_index|000)03d - %(title)s [%(id)s].%(ext)s")
    fmt = quality_to_format(quality)

    ff = ffmpeg_path()
    ffmpeg_dir = str(Path(ff).parent) if ff else None

    log(f"[INFO] Download settings: quality={quality}, playlist={is_playlist}\n")
    log(f"[INFO] Download directory: {outdir}\n")

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
        if stop_flag and stop_flag.is_set():
            raise RuntimeError("Stopped by user.") from exc
        raise RuntimeError(str(exc)) from exc

    set_step_progress(100)
    return outdir
