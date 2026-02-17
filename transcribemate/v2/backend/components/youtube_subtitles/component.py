"""YouTube subtitles module component."""

from __future__ import annotations

from ..core.static import StaticModuleComponent
from ..core.base import ModuleRuntimeProfile


class YoutubeSubtitlesComponent(StaticModuleComponent):
    def __init__(self):
        super().__init__(
            runtime_profile=ModuleRuntimeProfile(
                module_id="youtube_subtitles",
                display_name="YouTube Subtitles",
                description="YouTube download with subtitle rendering (translated or source).",
                source_mode="youtube",
                output_mode="video_subs",
                runtime_features=("yt_dlp_download", "subtitle_rendering", "ffmpeg"),
                python_packages=("yt_dlp", "faster_whisper", "transformers"),
                ui_schema={
                    "show_tabs": ["run", "advanced", "logs", "jobs", "settings"],
                    "show_sections": [
                        "run_source_card",
                        "run_output_card",
                        "simple_hint_card",
                        "advanced_subtitles_card",
                        "settings_appearance_card",
                        "settings_runtime_card",
                        "settings_core_card",
                        "settings_module_flow_card",
                        "settings_module_scope_row",
                        "settings_preset_row",
                    ],
                    "show_fields": [
                        "run.youtube_url",
                        "run.playlist",
                        "run.quality",
                        "run.output_dir",
                        "run.output_prefix",
                        "settings.model",
                        "settings.model_options",
                        "settings.source_lang",
                        "settings.target_lang",
                        "settings.batch_size",
                    ],
                    "jobs_filter_module": True,
                },
            ),
            force_source_mode="youtube",
            force_output_mode="video_subs",
            force_diarization_enabled=False,
        )
