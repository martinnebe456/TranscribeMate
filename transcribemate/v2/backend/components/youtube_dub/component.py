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
            ),
            force_source_mode="youtube",
            force_output_mode="video_dub",
            force_translation_enabled=True,
            force_diarization_enabled=False,
        )
