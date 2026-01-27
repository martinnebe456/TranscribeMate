"""Application version resolution."""

from __future__ import annotations

import os
import sys
from importlib import metadata
from pathlib import Path

ENV_KEY = "TRANSCRIBEMATE_VERSION"
VERSION_FILE = "version.txt"


def _read_version_file(path: Path) -> str | None:
    try:
        text = path.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    return text or None


def _candidate_roots() -> list[Path]:
    roots: list[Path] = []
    base = getattr(sys, "_MEIPASS", None)
    if base:
        roots.append(Path(base))
    if getattr(sys, "frozen", False):
        roots.append(Path(sys.executable).parent)
    roots.append(Path(__file__).resolve().parents[2])
    return roots


def get_app_version() -> str:
    env_version = os.getenv(ENV_KEY, "").strip()
    if env_version:
        return env_version
    for root in _candidate_roots():
        version = _read_version_file(root / VERSION_FILE)
        if version:
            return version
    try:
        return metadata.version("TranscribeMate")
    except metadata.PackageNotFoundError:
        return ""


APP_VERSION = get_app_version()

