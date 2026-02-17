"""Component-based backend module architecture."""

from .core.base import ModuleComponent, ModuleRuntimeProfile
from .registry import get_module_component, list_module_components, supported_module_ids

__all__ = [
    "ModuleComponent",
    "ModuleRuntimeProfile",
    "get_module_component",
    "list_module_components",
    "supported_module_ids",
]
