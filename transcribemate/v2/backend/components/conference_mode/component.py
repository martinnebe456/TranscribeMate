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
                ui_schema={
                    "show_tabs": ["run", "advanced", "logs", "jobs", "settings"],
                    "show_sections": [
                        "run_source_card",
                        "run_output_card",
                        "simple_hint_card",
                        "advanced_conference_card",
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
                        "settings.batch_size",
                        "settings.text_options",
                        "settings.split_minutes",
                    ],
                    "jobs_filter_module": True,
                },
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
