"""Shared component helpers."""

from __future__ import annotations

from dataclasses import replace
from threading import Event

from ...models import PipelineRequest
from .base import (
    LogCallback,
    ModuleComponent,
    ModulePipelineAdapter,
    ModuleRuntimeProfile,
    ProgressCallback,
)
from .pipelines import SharedPipelineAdapter


class StaticModuleComponent(ModuleComponent):
    """Module component with static source/output/runtime constraints."""

    def __init__(
        self,
        *,
        runtime_profile: ModuleRuntimeProfile,
        force_source_mode: str | None = None,
        force_output_mode: str | None = None,
        force_translation_enabled: bool | None = None,
        force_diarization_enabled: bool | None = None,
        force_diarization_backend: str | None = None,
    ):
        self._runtime_profile = runtime_profile
        self._force_source_mode = force_source_mode
        self._force_output_mode = force_output_mode
        self._force_translation_enabled = force_translation_enabled
        self._force_diarization_enabled = force_diarization_enabled
        self._force_diarization_backend = force_diarization_backend

    @property
    def runtime_profile(self) -> ModuleRuntimeProfile:
        return self._runtime_profile

    def configure_request(self, request: PipelineRequest) -> PipelineRequest:
        source = request.source
        output = request.output
        translation = request.translation
        diarization = request.diarization

        if self._force_source_mode:
            source = replace(source, mode=self._force_source_mode)
        if self._force_output_mode:
            output = replace(output, mode=self._force_output_mode)
        if self._force_translation_enabled is not None:
            translation = replace(translation, enabled=self._force_translation_enabled)
        if self._force_diarization_enabled is not None:
            diarization = replace(diarization, enabled=self._force_diarization_enabled)
        if self._force_diarization_backend:
            diarization = replace(diarization, backend=self._force_diarization_backend)

        return replace(
            request,
            module=self._runtime_profile.module_id,
            source=source,
            output=output,
            translation=translation,
            diarization=diarization,
        )

    def create_pipeline(
        self,
        *,
        request: PipelineRequest,
        stop_flag: Event,
        log_cb: LogCallback,
        progress_cb: ProgressCallback,
    ) -> ModulePipelineAdapter:
        return SharedPipelineAdapter(
            request=request,
            stop_flag=stop_flag,
            log_cb=log_cb,
            progress_cb=progress_cb,
        )
