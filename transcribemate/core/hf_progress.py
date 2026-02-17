"""Utilities for relaying Hugging Face download progress to app callbacks."""

from __future__ import annotations

import importlib
from contextlib import contextmanager
from typing import Callable, Iterator

ProgressCallback = Callable[[str, float | None], None]


@contextmanager
def huggingface_download_progress(callback: ProgressCallback) -> Iterator[None]:
    """Temporarily hook Hugging Face tqdm to report download progress."""
    patched_targets: list[tuple[object, object]] = []

    try:
        from tqdm.auto import tqdm as base_tqdm
    except Exception:
        yield
        return

    module_names = (
        "huggingface_hub.utils.tqdm",
        "huggingface_hub.utils",
        "huggingface_hub.file_download",
        "huggingface_hub._snapshot_download",
    )
    owners = []
    seen_names: set[str] = set()
    for module_name in module_names:
        try:
            owner = importlib.import_module(module_name)
        except Exception:
            continue
        owner_name = getattr(owner, "__name__", module_name)
        if owner_name in seen_names:
            continue
        seen_names.add(owner_name)
        owners.append(owner)

    class _ForwardingTqdm(base_tqdm):
        def __init__(self, *args, **kwargs):
            self._tm_desc = str(kwargs.get("desc") or "download").strip() or "download"
            self._tm_last_percent = -1
            super().__init__(*args, **kwargs)
            self._tm_emit(force=True)

        def update(self, n=1):
            result = super().update(n)
            self._tm_emit(force=False)
            return result

        def close(self):
            self._tm_emit(force=True)
            return super().close()

        def _tm_emit(self, *, force: bool):
            total = getattr(self, "total", None)
            current = getattr(self, "n", None)
            percent: float | None = None

            if isinstance(total, (int, float)) and total > 0 and isinstance(current, (int, float)):
                percent = max(0.0, min(100.0, (float(current) / float(total)) * 100.0))
                rounded = int(percent)
                if (not force) and rounded == self._tm_last_percent:
                    return
                self._tm_last_percent = rounded
            elif not force:
                return

            try:
                callback(self._tm_desc, percent)
            except Exception:
                # Callback must never interrupt model download flow.
                return

    try:
        for owner in owners:
            original_tqdm = getattr(owner, "tqdm", None)
            if not callable(original_tqdm):
                continue
            setattr(owner, "tqdm", _ForwardingTqdm)
            patched_targets.append((owner, original_tqdm))
        yield
    finally:
        for owner, original_tqdm in reversed(patched_targets):
            try:
                setattr(owner, "tqdm", original_tqdm)
            except Exception:
                continue
