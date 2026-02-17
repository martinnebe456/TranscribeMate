"""Module pipeline adapters."""

from __future__ import annotations

from threading import Event

from ...models import PipelineRequest
from ...pipeline import PipelineOrchestrator, PipelineRunResult
from .base import LogCallback, ModulePipelineAdapter, ProgressCallback


class SharedPipelineAdapter(ModulePipelineAdapter):
    """Shared pipeline adapter used by module components."""

    def __init__(
        self,
        *,
        request: PipelineRequest,
        stop_flag: Event,
        log_cb: LogCallback,
        progress_cb: ProgressCallback,
    ):
        self._orchestrator = PipelineOrchestrator(
            request=request,
            stop_flag=stop_flag,
            log_cb=log_cb,
            progress_cb=progress_cb,
        )

    def run(self) -> PipelineRunResult:
        return self._orchestrator.run()
