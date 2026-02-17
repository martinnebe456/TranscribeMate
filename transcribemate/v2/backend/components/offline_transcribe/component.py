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
                python_packages=("faster_whisper", "safetensors"),
            ),
            force_source_mode="local",
        )
