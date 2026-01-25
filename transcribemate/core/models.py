"""Model cache configuration and maintenance."""

from __future__ import annotations

import os
import shutil
from pathlib import Path

from .paths import user_data_dir


def _default_cache_dir(name: str) -> Path:
    base = user_data_dir() / "cache"
    base.mkdir(parents=True, exist_ok=True)
    path = base / name
    path.mkdir(parents=True, exist_ok=True)
    return path


def huggingface_cache_dir() -> Path:
    env = os.environ.get("HF_HOME")
    path = Path(env) if env else _default_cache_dir("huggingface")
    path.mkdir(parents=True, exist_ok=True)
    return path


def whisper_cache_dir() -> Path:
    env = os.environ.get("WHISPER_CACHE_DIR")
    path = Path(env) if env else _default_cache_dir("whisper")
    path.mkdir(parents=True, exist_ok=True)
    return path


def configure_model_environment():
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS", "1")
    os.environ.setdefault("HF_HOME", str(_default_cache_dir("huggingface")))
    os.environ.setdefault("WHISPER_CACHE_DIR", str(_default_cache_dir("whisper")))


def force_refresh_models(log):
    hf = huggingface_cache_dir()
    wh = whisper_cache_dir()
    if hf.exists():
        shutil.rmtree(hf, ignore_errors=True)
        log(f"[INFO] Removed cache: {hf}\n")
    if wh.exists():
        shutil.rmtree(wh, ignore_errors=True)
        log(f"[INFO] Removed cache: {wh}\n")
