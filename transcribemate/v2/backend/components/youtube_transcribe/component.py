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
            ),
            force_source_mode="youtube",
            force_output_mode="txt_only",
            force_translation_enabled=False,
            force_diarization_enabled=False,
        )
