"""GPU detection and model sizing."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class GpuInfo:
    available: bool
    name: str = ""
    vram_gb: float = 0.0


def get_gpu_info() -> GpuInfo:
    try:
        import torch

        if not torch.cuda.is_available():
            return GpuInfo(False)
        prop = torch.cuda.get_device_properties(0)
        vram_gb = prop.total_memory / (1024**3)
        return GpuInfo(True, prop.name, float(vram_gb))
    except Exception:
        return GpuInfo(False)


def auto_whisper_model(vram_gb: float) -> str:
    if vram_gb >= 11.5:
        return "large-v3"
    if vram_gb >= 7.5:
        return "medium"
    return "small"


def torch_device(prefer_gpu: bool) -> str:
    if not prefer_gpu:
        return "cpu"
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"
