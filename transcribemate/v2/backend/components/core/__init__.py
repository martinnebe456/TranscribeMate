"""Core contracts and shared adapters for backend module components."""

from .base import ModuleComponent, ModulePipelineAdapter, ModuleRuntimeProfile
from .static import StaticModuleComponent

__all__ = [
    "ModuleComponent",
    "ModulePipelineAdapter",
    "ModuleRuntimeProfile",
    "StaticModuleComponent",
]
