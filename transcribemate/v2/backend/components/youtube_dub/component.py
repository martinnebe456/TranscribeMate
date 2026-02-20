"""YouTube dubbing module component."""

from __future__ import annotations

from ..core.static import StaticModuleComponent
from ..core.base import ModuleRuntimeProfile


class YoutubeDubComponent(StaticModuleComponent):
    def __init__(self):
        super().__init__(
            runtime_profile=ModuleRuntimeProfile(
                module_id="youtube_dub",
                display_name="YouTube Dub",
                description="YouTube download, translation and full dubbed audio render.",
                source_mode="youtube",
                output_mode="video_dub",
                runtime_features=("yt_dlp_download", "translation", "tts_dubbing", "ffmpeg"),
                python_packages=("yt_dlp", "transformers", "edge_tts"),
                ui_schema={
                    "show_tabs": ["run", "logs", "jobs", "settings"],
                    "show_sections": [
                        "run_source_card",
                        "run_output_card",
                        "simple_hint_card",
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
                        "settings.summary_lang",
                        "settings.summary_ai",
                        "settings.summary_model_tier",
                        "settings.target_lang",
                        "settings.batch_size",
                        "settings.text_options",
                        "settings.split_minutes",
                    ],
                    "jobs_filter_module": True,
                },
            ),
            force_source_mode="youtube",
            force_output_mode="video_dub",
            force_translation_enabled=True,
            force_diarization_enabled=False,
        )
