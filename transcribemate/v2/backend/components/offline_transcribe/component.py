"""Offline transcription module component."""

from __future__ import annotations

from ..core.static import StaticModuleComponent
from ..core.base import ModuleRuntimeProfile


class OfflineTranscribeComponent(StaticModuleComponent):
    def __init__(self):
        super().__init__(
            runtime_profile=ModuleRuntimeProfile(
                module_id="offline_transcribe",
                display_name="Offline A/V Transcript",
                description="Local audio/video transcription without online source requirements.",
                source_mode="local",
                output_mode="txt_only",
                runtime_features=("local_media", "faster_whisper", "text_export"),
                python_packages=("faster_whisper", "safetensors", "transformers"),
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
                        "run.local_path",
                        "run.output_dir",
                        "run.output_prefix",
                        "settings.model",
                        "settings.model_options",
                        "settings.source_lang",
                        "settings.summary_lang",
                        "settings.summary_ai",
                        "settings.summary_model_tier",
                        "settings.batch_size",
                        "settings.text_options",
                        "settings.split_minutes",
                    ],
                    "jobs_filter_module": True,
                },
            ),
            force_source_mode="local",
        )
