"""Filesystem paths and tool discovery for TranscribeMate."""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path
from typing import Optional

from .i18n import APP_NAME

FROZEN = bool(getattr(sys, "frozen", False))


def project_root() -> Path:
    """Return the repository root in dev, or the EXE dir when frozen."""
    if FROZEN:
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


def app_root() -> Path:
    base = getattr(sys, "_MEIPASS", None)
    if base:
        return Path(base)
    return project_root()


def user_data_dir() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    path = base / APP_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


def user_site_packages_dir() -> Path:
    path = user_data_dir() / "site-packages"
    path.mkdir(parents=True, exist_ok=True)
    return path


def ensure_site_packages_on_path():
    site_dir = user_site_packages_dir()
    site_str = str(site_dir)
    if site_str not in sys.path:
        # Prefer per-user runtime packages over bundled copies.
        sys.path.insert(0, site_str)

    current_py_path = os.environ.get("PYTHONPATH", "")
    parts = current_py_path.split(os.pathsep) if current_py_path else []
    if site_str not in parts:
        os.environ["PYTHONPATH"] = (
            f"{site_str}{os.pathsep}{current_py_path}" if current_py_path else site_str
        )


def user_assets_dir() -> Path:
    path = user_data_dir() / "assets"
    path.mkdir(parents=True, exist_ok=True)
    return path


def config_path() -> Path:
    return user_data_dir() / "config.json"


def legacy_log_path() -> Path:
    return user_data_dir() / "bootstrap.log"


def log_path() -> Path:
    return user_data_dir() / "runtime.log"


def run_logs_dir() -> Path:
    path = user_data_dir() / "logs"
    path.mkdir(parents=True, exist_ok=True)
    return path


def bundled_bin(name: str) -> Optional[Path]:
    candidates = [
        user_assets_dir() / name,
        app_root() / "assets" / name,
        project_root() / "assets" / name,
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def ensure_assets_on_path():
    assets_dir = user_assets_dir()
    assets_str = str(assets_dir)
    current_path = os.environ.get("PATH", "")
    path_parts = current_path.split(os.pathsep) if current_path else []
    if assets_str not in path_parts:
        os.environ["PATH"] = f"{assets_str}{os.pathsep}{current_path}" if current_path else assets_str


def ffmpeg_path() -> Optional[str]:
    p = bundled_bin("ffmpeg.exe")
    if p:
        return str(p)
    p = bundled_bin("ffmpeg")
    if p:
        return str(p)
    return shutil.which("ffmpeg")


def ffprobe_path() -> Optional[str]:
    p = bundled_bin("ffprobe.exe")
    if p:
        return str(p)
    p = bundled_bin("ffprobe")
    if p:
        return str(p)
    return shutil.which("ffprobe")


def ensure_tools(is_youtube: bool):
    if not ffmpeg_path() or not ffprobe_path():
        raise RuntimeError("ffmpeg/ffprobe not found (assets/ or PATH).")
    if is_youtube:
        try:
            import yt_dlp  # noqa: F401
        except Exception as exc:  # pragma: no cover - environment issue
            raise RuntimeError("yt-dlp is not installed.") from exc


if FROZEN:
    ensure_assets_on_path()
    ensure_site_packages_on_path()
