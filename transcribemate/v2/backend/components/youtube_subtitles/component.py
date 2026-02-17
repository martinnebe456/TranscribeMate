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
            ),
            force_source_mode="youtube",
            force_output_mode="video_subs",
            force_diarization_enabled=False,
        )
