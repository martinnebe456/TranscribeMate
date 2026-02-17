"""Registry of backend module components."""

from __future__ import annotations

from functools import lru_cache

from .core.base import ModuleComponent
from .conference_mode import ConferenceModeComponent
from .offline_transcribe import OfflineTranscribeComponent
from .speaker_transcribe import SpeakerTranscriptComponent
from .youtube_dub import YoutubeDubComponent
from .youtube_subtitles import YoutubeSubtitlesComponent
from .youtube_transcribe import YoutubeTranscriptComponent


@lru_cache(maxsize=1)
def _ordered_components() -> tuple[ModuleComponent, ...]:
    return (
        OfflineTranscribeComponent(),
        YoutubeTranscriptComponent(),
        SpeakerTranscriptComponent(),
        ConferenceModeComponent(),
        YoutubeSubtitlesComponent(),
        YoutubeDubComponent(),
    )


@lru_cache(maxsize=1)
def _by_id() -> dict[str, ModuleComponent]:
    return {component.runtime_profile.module_id: component for component in _ordered_components()}


def list_module_components() -> list[ModuleComponent]:
    return list(_ordered_components())


def supported_module_ids() -> list[str]:
    return [component.runtime_profile.module_id for component in _ordered_components()]


def get_module_component(module_id: str) -> ModuleComponent:
    module = str(module_id or "").strip().lower()
    return _by_id().get(module, _by_id()["offline_transcribe"])
