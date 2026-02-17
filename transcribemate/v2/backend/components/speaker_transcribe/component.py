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
                python_packages=("faster_whisper",),
            ),
            force_source_mode="local",
            force_output_mode="conference",
            force_translation_enabled=False,
            force_diarization_enabled=True,
            force_diarization_backend="stable_local",
        )
