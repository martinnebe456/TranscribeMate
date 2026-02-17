"""YouTube transcript module component."""

from __future__ import annotations

from ..core.static import StaticModuleComponent
from ..core.base import ModuleRuntimeProfile


class YoutubeTranscriptComponent(StaticModuleComponent):
    def __init__(self):
        super().__init__(
            runtime_profile=ModuleRuntimeProfile(
                module_id="youtube_transcribe",
                display_name="YouTube Transcript",
                description="YouTube download and transcript export.",
                source_mode="youtube",
                output_mode="txt_only",
                runtime_features=("yt_dlp_download", "faster_whisper", "text_export"),
                python_packages=("yt_dlp", "faster_whisper"),
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
                        "run.keep_originals",
                        "run.output_prefix",
                        "settings.model",
                        "settings.model_options",
                        "settings.source_lang",
                        "settings.summary_lang",
                        "settings.batch_size",
                        "settings.text_options",
                        "settings.split_minutes",
                    ],
                    "jobs_filter_module": True,
                },
            ),
            force_source_mode="youtube",
            force_output_mode="txt_only",
            force_translation_enabled=False,
            force_diarization_enabled=False,
        )
