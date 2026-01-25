"""User configuration persistence."""

from __future__ import annotations

import json
import shutil
from dataclasses import asdict, dataclass
from pathlib import Path

from .i18n import LANGUAGES, normalize_output_mode, normalize_quick_model, normalize_theme
from .paths import FROZEN, config_path, project_root


@dataclass
class AppConfig:
    out_dir: str = ""
    lang: str = "en"
    theme: str = "light"
    use_gpu: bool = True
    auto_model: bool = True
    whisper_model: str = "medium"
    source_lang: str = "auto"
    target_lang: str = "en→cs"
    batch_size: int = 16
    subtitle_mode: str = "soft"
    video_quality: str = "best"
    output_mode: str = "video_subs"
    quick_model: str = "medium"
    clean_text: bool = True
    export_md: bool = True
    summary_pack: bool = True
    split_minutes: int = 0
    keep_originals: bool = False
    sub_font: str = "Arial"
    sub_size: int = 24
    sub_color: str = "#FFFFFF"
    sub_outline_color: str = "#000000"
    sub_outline_width: int = 2

    def __post_init__(self):
        if not self.out_dir:
            self.out_dir = str(Path.home() / "Downloads")
        if self.lang not in LANGUAGES:
            self.lang = "en"
        self.theme = normalize_theme(self.theme)
        self.output_mode = normalize_output_mode(self.output_mode)
        self.quick_model = normalize_quick_model(self.quick_model)
        try:
            self.split_minutes = max(0, int(self.split_minutes))
        except Exception:
            self.split_minutes = 0
        self.clean_text = bool(self.clean_text)
        self.export_md = bool(self.export_md)
        self.summary_pack = bool(self.summary_pack)
        self.keep_originals = bool(self.keep_originals)


def load_config() -> AppConfig:
    cfg_path = config_path()
    legacy_path = project_root() / "config.json"

    try:
        cfg_path.parent.mkdir(parents=True, exist_ok=True)
        if FROZEN and not cfg_path.exists() and legacy_path.exists() and legacy_path != cfg_path:
            try:
                shutil.copy2(legacy_path, cfg_path)
            except Exception:
                pass

        if cfg_path.exists():
            data = json.loads(cfg_path.read_text(encoding="utf-8"))
            filtered = {k: v for k, v in data.items() if k in AppConfig.__dataclass_fields__}
            return AppConfig(**filtered)
    except Exception:
        pass
    return AppConfig()


def save_config(cfg: AppConfig):
    cfg_path = config_path()
    try:
        cfg_path.parent.mkdir(parents=True, exist_ok=True)
        cfg_path.write_text(json.dumps(asdict(cfg), indent=2, ensure_ascii=False), encoding="utf-8")
    except Exception:
        pass
