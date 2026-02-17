"""Conference mode module component."""

from __future__ import annotations

from ...models import PipelineRequest
from ..core.base import ModuleRuntimeProfile, PreflightCheckAdder
from ..core.static import StaticModuleComponent


class ConferenceModeComponent(StaticModuleComponent):
    def __init__(self):
        super().__init__(
            runtime_profile=ModuleRuntimeProfile(
                module_id="conference_mode",
                display_name="Conference Mode",
                description="Multi-file conference processing with per-file metadata.",
                source_mode="local",
                output_mode="conference",
                runtime_features=("local_media", "conference_metadata", "text_export"),
                python_packages=("faster_whisper",),
            ),
            force_source_mode="local",
            force_output_mode="conference",
            force_translation_enabled=False,
        )

    def augment_preflight_checks(self, request: PipelineRequest, add_check: PreflightCheckAdder) -> None:
        if request.conference_meta:
            return
        add_check(
            "conference_meta",
            "warn",
            "Conference mode is selected but no per-file conference metadata rows were provided.",
            "warn",
        )
