"""Speaker-aware transcription module component."""

from __future__ import annotations

from ..core.static import StaticModuleComponent
from ..core.base import ModuleRuntimeProfile


class SpeakerTranscriptComponent(StaticModuleComponent):
    def __init__(self):
        super().__init__(
            runtime_profile=ModuleRuntimeProfile(
                module_id="speaker_transcribe",
                display_name="Speaker Transcript",
                description="Local transcription with diarization-focused output.",
                source_mode="local",
                output_mode="conference",
                runtime_features=("local_media", "faster_whisper", "diarization"),
                python_packages=("faster_whisper", "speechbrain", "torchaudio", "scikit-learn"),
                ui_schema={
                    "show_tabs": ["run", "diarization", "logs", "jobs", "settings"],
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
                        "settings.batch_size",
                        "settings.speaker_hint",
                        "diarization.backend",
                        "diarization.accuracy",
                        "diarization.minmax",
                        "diarization.options",
                        "diarization.runtime",
                        "diarization.profiles",
                        "diarization.apply_mapping",
                    ],
                    "jobs_filter_module": True,
                },
            ),
            force_source_mode="local",
            force_output_mode="conference",
            force_translation_enabled=False,
            force_diarization_enabled=True,
        )
