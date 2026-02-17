"""Component contracts for backend module isolation."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from threading import Event
from typing import Callable

from ...models import PipelineRequest
from ...models import PipelineRunResult

LogCallback = Callable[[str], None]
ProgressCallback = Callable[[dict], None]
PreflightCheckAdder = Callable[[str, str, str, str], None]


@dataclass(frozen=True, slots=True)
class ModuleRuntimeProfile:
    """Describes independent runtime requirements for one module component."""

    module_id: str
    display_name: str
    description: str
    source_mode: str
    output_mode: str
    runtime_features: tuple[str, ...] = ()
    python_packages: tuple[str, ...] = ()


class ModulePipelineAdapter(ABC):
    """Runs one module-specific pipeline."""

    @abstractmethod
    def run(self) -> PipelineRunResult:
        raise NotImplementedError


class ModuleComponent(ABC):
    """Independent module component with own runtime/profile/pipeline surface."""

    @property
    @abstractmethod
    def runtime_profile(self) -> ModuleRuntimeProfile:
        raise NotImplementedError

    @abstractmethod
    def configure_request(self, request: PipelineRequest) -> PipelineRequest:
        """Normalize request for this module component."""
        raise NotImplementedError

    @abstractmethod
    def create_pipeline(
        self,
        *,
        request: PipelineRequest,
        stop_flag: Event,
        log_cb: LogCallback,
        progress_cb: ProgressCallback,
    ) -> ModulePipelineAdapter:
        """Create component-specific pipeline adapter."""
        raise NotImplementedError

    def augment_preflight_checks(self, request: PipelineRequest, add_check: PreflightCheckAdder) -> None:
        """Optional component-specific preflight checks."""
        _ = request
        _ = add_check

    def to_capability_dict(self) -> dict:
        profile = self.runtime_profile
        return {
            "module_id": profile.module_id,
            "label": profile.display_name,
            "description": profile.description,
            "source_mode": profile.source_mode,
            "output_mode": profile.output_mode,
            "runtime_features": list(profile.runtime_features),
            "python_packages": list(profile.python_packages),
        }
