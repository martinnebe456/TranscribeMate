"""Job-oriented backend service for V2 frontend clients."""

from __future__ import annotations

import os
import platform
import re
import shutil
import subprocess
import json
import traceback
import uuid
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
import sys
from threading import Event, Lock, Thread
from typing import Callable, Deque

from ...core.models import force_refresh_models
from ...core.paths import config_path, ffmpeg_path, log_path, user_data_dir
from ...core.i18n import LANG_CODES, TRANSLATION_MODELS
from ...pipeline.diarize import apply_speaker_mapping_to_sidecar_file, ensure_local_diarization_runtime
from ...pipeline.summarize import summarize_transcript_file, supported_summary_tiers
from ...core.version import APP_VERSION
from .components import get_module_component, list_module_components, supported_module_ids
from .diarization import supported_backends
from .models import PipelineRequest, PipelineRunResult, ProjectContextSpec, RequestValidationError
from .pipeline import PipelineCancelledError
from .protocol import EventMessage

EventEmitter = Callable[[EventMessage], None]


@dataclass(slots=True)
class JobRecord:
    job_id: str
    request: PipelineRequest
    created_at: str
    status: str = "queued"
    started_at: str | None = None
    finished_at: str | None = None
    progress: dict = field(default_factory=dict)
    result: dict | None = None
    error: dict | None = None
    warnings: list[str] = field(default_factory=list)
    logs: Deque[str] = field(default_factory=lambda: deque(maxlen=500))
    stop_flag: Event = field(default_factory=Event)
    thread: Thread | None = None

    def snapshot(self) -> dict:
        return {
            "job_id": self.job_id,
            "status": self.status,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "request": self.request.to_dict(),
            "progress": dict(self.progress),
            "result": self.result,
            "error": self.error,
            "warnings": list(self.warnings),
            "logs_tail": list(self.logs),
        }


class BackendService:
    """In-process service API used by the JSON-line server."""

    def __init__(self, *, emit_event: EventEmitter):
        self._emit_event = emit_event
        self._jobs: dict[str, JobRecord] = {}
        self._lock = Lock()

    def handle_request(self, method: str, params: dict) -> dict:
        route = method.strip().lower()
        if route == "ping":
            return self._ping()
        if route == "get_capabilities":
            return self._get_capabilities()
        if route == "run_pipeline":
            return self._run_pipeline(params)
        if route == "cancel_job":
            return self._cancel_job(params)
        if route == "get_job":
            return self._get_job(params)
        if route == "list_jobs":
            return self._list_jobs(params)
        if route == "remove_job":
            return self._remove_job(params)
        if route == "health":
            return self._health()
        if route == "refresh_model_cache":
            return self._refresh_model_cache()
        if route == "get_paths":
            return self._get_paths()
        if route == "preflight_check":
            return self._preflight_check(params)
        if route == "apply_speaker_mapping":
            return self._apply_speaker_mapping(params)
        if route == "summarize_transcript":
            return self._summarize_transcript(params)
        if route == "get_system_metrics":
            return self._get_system_metrics()
        raise RequestValidationError(
            f"Unknown method '{method}'.",
            code="method_not_found",
            details={"method": method},
        )

    def _ping(self) -> dict:
        return {
            "service": "transcribemate-v2-backend",
            "version": APP_VERSION,
            "time_utc": _utc_now(),
        }

    def _get_capabilities(self) -> dict:
        module_components = list_module_components()
        return {
            "service": "transcribemate-v2-backend",
            "version": APP_VERSION,
            "methods": [
                "ping",
                "get_capabilities",
                "run_pipeline",
                "cancel_job",
                "get_job",
                "list_jobs",
                "remove_job",
                "health",
                "refresh_model_cache",
                "get_paths",
                "preflight_check",
                "apply_speaker_mapping",
                "summarize_transcript",
                "get_system_metrics",
            ],
            "output_modes": ["conference", "video_subs", "video_dub", "srt_only", "txt_only"],
            "modules": supported_module_ids(),
            "module_components": [component.to_capability_dict() for component in module_components],
            "translation_targets": sorted(TRANSLATION_MODELS.keys()),
            "translation_lang_codes": dict(LANG_CODES),
            "diarization_backends": supported_backends(),
            "summary_ai_tiers": list(supported_summary_tiers()),
        }

    def _health(self) -> dict:
        gpu_diag = _gather_gpu_runtime_diagnostics()
        torch_ok = bool(gpu_diag.get("torch_ok", False))
        torch_version = str(gpu_diag.get("torch_version", "") or "")
        torch_cuda = bool(gpu_diag.get("torch_cuda_available", False))

        return {
            "time_utc": _utc_now(),
            "torch_ok": torch_ok,
            "torch_version": torch_version,
            "torch_cuda": torch_cuda,
            "torch_cuda_build": gpu_diag.get("torch_cuda_build"),
            "ctranslate2_version": gpu_diag.get("ctranslate2_version"),
            "ctranslate2_cuda_devices": gpu_diag.get("ctranslate2_cuda_devices"),
            "nvidia_smi_detected": bool(gpu_diag.get("nvidia_smi_path")),
            "nvidia_gpu_detected": bool(gpu_diag.get("nvidia_gpu_detected", False)),
            "nvidia_gpu_name": gpu_diag.get("nvidia_gpu_name", ""),
            "nvidia_driver_version": gpu_diag.get("nvidia_driver_version", ""),
            "nvidia_driver_model": gpu_diag.get("nvidia_driver_model", ""),
            "nvidia_cuda_runtime_version": gpu_diag.get("nvidia_cuda_runtime_version", ""),
            "python_executable": sys.executable,
            "active_jobs": len([job for job in self._jobs.values() if job.status in {"queued", "running"}]),
            "total_jobs": len(self._jobs),
        }

    def _refresh_model_cache(self) -> dict:
        lines: list[str] = []

        def _log(line: str):
            text = str(line or "").strip()
            if text:
                lines.append(text)

        force_refresh_models(_log)
        return {
            "refreshed": True,
            "messages": lines,
        }

    def _get_paths(self) -> dict:
        return {
            "user_data_dir": str(user_data_dir()),
            "runtime_log": str(log_path()),
            "config_path": str(config_path()),
        }

    def _preflight_check(self, params: dict) -> dict:
        checks: list[dict] = []

        def add_check(name: str, status: str, message: str, *, level: str = "info"):
            checks.append(
                {
                    "name": name,
                    "status": status,
                    "level": level,
                    "message": message,
                }
            )

        request: PipelineRequest | None
        try:
            request = PipelineRequest.from_payload(params or {})
            add_check("request_schema", "pass", "Request payload is valid.")
        except RequestValidationError as exc:
            add_check("request_schema", "fail", str(exc), level="error")
            return {
                "ok": False,
                "checks": checks,
            }

        assert request is not None
        module_component = get_module_component(request.module)
        request = module_component.configure_request(request)
        module_component.augment_preflight_checks(
            request,
            lambda name, status, message, level: add_check(name, status, message, level=level),
        )

        if request.source.mode == "local":
            if request.source.path:
                source_path = os.path.expanduser(request.source.path)
                if os.path.exists(source_path):
                    add_check("source_local", "pass", f"Input exists: {source_path}")
                else:
                    add_check("source_local", "fail", f"Input path does not exist: {source_path}", level="error")
            else:
                add_check("source_local", "warn", "No local source path selected.", level="warn")
        else:
            if request.source.url:
                add_check("source_youtube_url", "pass", "YouTube URL provided.")
            else:
                add_check("source_youtube_url", "fail", "YouTube URL is empty.", level="error")
            try:
                import yt_dlp  # noqa: F401

                add_check("yt_dlp", "pass", "yt-dlp is installed.")
            except Exception as exc:
                add_check("yt_dlp", "fail", f"yt-dlp is missing: {type(exc).__name__}: {exc}", level="error")

        out_dir = os.path.expanduser(request.output.out_dir)
        try:
            os.makedirs(out_dir, exist_ok=True)
            test_file = os.path.join(out_dir, f".tm_v2_write_test_{uuid.uuid4().hex}")
            with open(test_file, "w", encoding="utf-8") as fh:
                fh.write("ok")
            add_check("output_dir", "pass", f"Output directory writable: {out_dir}")
            try:
                os.remove(test_file)
            except Exception as cleanup_exc:
                add_check(
                    "output_dir_cleanup",
                    "warn",
                    f"Output probe file cleanup failed: {cleanup_exc}",
                    level="warn",
                )
        except Exception as exc:
            add_check("output_dir", "fail", f"Output directory not writable: {out_dir} ({exc})", level="error")

        ffmpeg = ffmpeg_path()
        youtube_download_can_run_without_ffmpeg = (
            request.source.mode == "youtube"
            and request.output.mode in {"txt_only", "srt_only"}
        )
        if ffmpeg:
            add_check("ffmpeg", "pass", f"ffmpeg detected: {ffmpeg}")
        elif request.output.mode in {"video_subs", "video_dub"}:
            add_check(
                "ffmpeg",
                "fail",
                f"ffmpeg is required for {request.output.mode} mode.",
                level="error",
            )
        elif youtube_download_can_run_without_ffmpeg:
            add_check(
                "ffmpeg",
                "pass",
                "ffmpeg not found. Current YouTube mode will use a no-merge download fallback.",
            )
        else:
            add_check("ffmpeg", "warn", "ffmpeg not found. Some output modes may fail.", level="warn")

        if request.output.mode == "video_dub":
            try:
                import edge_tts  # noqa: F401

                add_check("edge_tts", "pass", "edge-tts is installed.")
            except Exception as exc:
                add_check(
                    "edge_tts",
                    "fail",
                    f"edge-tts is required for video_dub mode: {type(exc).__name__}: {exc}",
                    level="error",
                )

        torch_required = bool(
            request.translation_needed
            or request.diarization.enabled
            or request.text_export.summary_ai_enabled
        )
        gpu_diag = _gather_gpu_runtime_diagnostics()
        torch_ok = bool(gpu_diag.get("torch_ok", False))
        torch_error = str(gpu_diag.get("torch_error", "") or "")
        torch_version = str(gpu_diag.get("torch_version", "") or "")
        torch_cuda = bool(gpu_diag.get("torch_cuda_available", False))
        torch_cuda_build = str(gpu_diag.get("torch_cuda_build", "") or "")

        if torch_ok:
            details = f"Torch import OK ({torch_version}), cuda={torch_cuda}"
            if torch_cuda_build:
                details += f", cuda_build={torch_cuda_build}"
            add_check("torch", "pass", details)
        elif torch_required:
            add_check(
                "torch",
                "fail",
                (
                    "Torch import failed but is required for this run "
                    "(translation, local diarization and/or AI summary): "
                    f"{torch_error or 'unknown error'}"
                ),
                level="error",
            )
        else:
            add_check(
                "torch",
                "warn",
                (
                    "Torch is not installed. This is OK for current mode "
                    "(transcription-only without translation/diarization)."
                ),
                level="warn",
            )

        if request.transcription.prefer_gpu:
            gpu_issues: list[str] = []
            if not bool(gpu_diag.get("nvidia_gpu_detected", False)):
                if gpu_diag.get("nvidia_smi_path"):
                    gpu_issues.append("NVIDIA driver is present but no usable GPU was detected.")
                else:
                    gpu_issues.append("NVIDIA GPU was not detected (nvidia-smi missing).")

            if not torch_ok:
                gpu_issues.append("Torch is unavailable.")
            elif not torch_cuda:
                if torch_cuda_build:
                    gpu_issues.append(
                        f"Torch CUDA build={torch_cuda_build}, but CUDA runtime is unavailable."
                    )
                else:
                    gpu_issues.append("Installed torch build is CPU-only.")
                driver_model = str(gpu_diag.get("nvidia_driver_model", "") or "")
                if driver_model:
                    gpu_issues.append(f"NVIDIA driver model={driver_model}.")
                cuda_runtime = str(gpu_diag.get("nvidia_cuda_runtime_version", "") or "")
                if cuda_runtime:
                    gpu_issues.append(f"NVIDIA CUDA runtime version={cuda_runtime}.")

            ctranslate2_error = str(gpu_diag.get("ctranslate2_error", "") or "")
            ctranslate2_devices_raw = gpu_diag.get("ctranslate2_cuda_devices")
            if ctranslate2_devices_raw is None:
                if ctranslate2_error:
                    gpu_issues.append(f"CTranslate2 check failed: {ctranslate2_error}")
            else:
                ctranslate2_devices = int(ctranslate2_devices_raw)
                if ctranslate2_devices <= 0:
                    gpu_issues.append("CTranslate2 does not detect a CUDA device for faster-whisper.")

            if gpu_issues:
                add_check(
                    "gpu_request",
                    "warn",
                    "GPU requested but runtime is not ready: " + " ".join(gpu_issues),
                    level="warn",
                )
            else:
                add_check(
                    "gpu_request",
                    "pass",
                    (
                        "GPU runtime ready "
                        f"(torch_cuda={torch_cuda}, ctranslate2_cuda_devices={gpu_diag.get('ctranslate2_cuda_devices')}, "
                        f"driver_model={gpu_diag.get('nvidia_driver_model', '')}, "
                        f"cuda_runtime={gpu_diag.get('nvidia_cuda_runtime_version', '')})."
                    ),
                )

        if request.diarization.enabled:
            try:
                diag = ensure_local_diarization_runtime()
                details = [f"Local diarization runtime ready ({request.diarization.backend})."]
                if diag.get("torchaudio_version"):
                    details.append(f"torchaudio={diag.get('torchaudio_version')}")
                if diag.get("soundfile_version"):
                    details.append(f"soundfile={diag.get('soundfile_version')}")
                if diag.get("torchaudio_compat_patched"):
                    details.append("torchaudio_compat=applied")

                add_check(
                    "diarization_runtime",
                    "pass",
                    ", ".join(details),
                )
            except Exception as exc:
                add_check(
                    "diarization_runtime",
                    "fail",
                    f"Local diarization runtime unavailable: {type(exc).__name__}: {exc}",
                    level="error",
                )

        has_fail = any(item["status"] == "fail" for item in checks)
        return {
            "ok": not has_fail,
            "checks": checks,
        }

    def _apply_speaker_mapping(self, params: dict) -> dict:
        params = params or {}
        sidecar_raw = str(params.get("sidecar_path") or "").strip()
        if not sidecar_raw:
            raise RequestValidationError(
                "apply_speaker_mapping requires 'sidecar_path'.",
                details={"sidecar_path": sidecar_raw},
            )

        sidecar_path = Path(os.path.expanduser(sidecar_raw)).expanduser().resolve()
        if not sidecar_path.is_file():
            raise RequestValidationError(
                "Speaker sidecar file was not found.",
                code="not_found",
                details={"sidecar_path": str(sidecar_path)},
            )

        mapping_raw = params.get("speaker_map") or {}
        if not isinstance(mapping_raw, dict):
            raise RequestValidationError(
                "apply_speaker_mapping requires 'speaker_map' object.",
                details={"speaker_map_type": type(mapping_raw).__name__},
            )

        speaker_map_updates: dict[str, str] = {}
        for key, value in mapping_raw.items():
            label = str(key or "").strip()
            if not label:
                continue
            speaker_map_updates[label] = str(value or "").strip()

        result = apply_speaker_mapping_to_sidecar_file(sidecar_path, speaker_map_updates)
        speaker_map = dict(result.get("speaker_map") or {})
        rewritten_paths = list(result.get("rewritten_paths") or [])

        return {
            "sidecar_path": str(sidecar_path),
            "speaker_map": speaker_map,
            "rewritten_paths": rewritten_paths,
            "rewritten_count": len(rewritten_paths),
        }

    def _summarize_transcript(self, params: dict) -> dict:
        params = params or {}
        operation_id = str(params.get("operation_id") or "").strip()
        if not operation_id:
            raise RequestValidationError(
                "summarize_transcript requires 'operation_id'.",
                details={"operation_id": operation_id},
            )

        transcript_raw = str(params.get("transcript_path") or "").strip()
        if not transcript_raw:
            raise RequestValidationError(
                "summarize_transcript requires 'transcript_path'.",
                details={"transcript_path": transcript_raw},
            )

        summary_lang = str(params.get("summary_lang") or "auto").strip() or "auto"
        summary_ai_tier = str(params.get("summary_ai_tier") or "medium").strip().lower() or "medium"
        if summary_ai_tier not in supported_summary_tiers():
            raise RequestValidationError(
                "summary_ai_tier must be one of: low, medium, high",
                details={
                    "summary_ai_tier": summary_ai_tier,
                    "supported": list(supported_summary_tiers()),
                },
            )

        project_raw = params.get("project") or {}
        if not isinstance(project_raw, dict):
            raise RequestValidationError("project must be an object.")
        project = ProjectContextSpec.from_payload(project_raw)

        with self._lock:
            active = self._first_active_job_locked()
        if active is not None:
            raise RequestValidationError(
                "Cannot run summarize_transcript while pipeline job is active.",
                code="job_limit_reached",
                details={
                    "active_job_id": active.job_id,
                    "active_job_status": active.status,
                },
            )

        transcript_path = Path(os.path.expanduser(transcript_raw)).expanduser().resolve()
        if not transcript_path.is_file():
            raise RequestValidationError(
                "Transcript file not found.",
                code="not_found",
                details={"transcript_path": str(transcript_path)},
            )
        if transcript_path.suffix.lower() != ".txt":
            raise RequestValidationError(
                "summarize_transcript supports only '.txt' transcript files.",
                details={"transcript_path": str(transcript_path)},
            )

        output_root = Path(project.output_dir).expanduser().resolve()
        if not self._path_within_root(transcript_path, output_root):
            raise RequestValidationError(
                "summarize_transcript accepts only files from project output directory.",
                details={
                    "transcript_path": str(transcript_path),
                    "project_output_dir": str(output_root),
                },
            )

        summary_output_dir = output_root / "summaries"
        self._emit_event(
            EventMessage(
                event="summary.started",
                payload={
                    "operation_id": operation_id,
                    "transcript_path": str(transcript_path),
                    "summary_lang": summary_lang,
                    "summary_ai_tier": summary_ai_tier,
                },
            )
        )

        def _emit_summary_log(line: str):
            text = str(line or "").strip()
            if not text:
                return
            self._emit_event(
                EventMessage(
                    event="summary.log",
                    payload={
                        "operation_id": operation_id,
                        "line": text,
                    },
                )
            )

        def _emit_summary_progress(
            step: str,
            overall_pct: float | None,
            step_pct: float | None,
            indeterminate: bool,
        ):
            self._emit_event(
                EventMessage(
                    event="summary.progress",
                    payload={
                        "operation_id": operation_id,
                        "step": str(step or ""),
                        "overall_pct": None if overall_pct is None else round(float(overall_pct), 2),
                        "step_pct": None if step_pct is None else round(float(step_pct), 2),
                        "indeterminate": bool(indeterminate),
                    },
                )
            )

        try:
            output_path = summarize_transcript_file(
                transcript_path=transcript_path,
                output_dir=summary_output_dir,
                summary_lang=summary_lang,
                summary_ai_tier=summary_ai_tier,
                log=_emit_summary_log,
                progress=_emit_summary_progress,
                stop_flag=None,
            )
        except Exception as exc:
            message = str(exc) or f"{type(exc).__name__}"
            self._emit_event(
                EventMessage(
                    event="summary.failed",
                    payload={
                        "operation_id": operation_id,
                        "error": message,
                    },
                )
            )
            raise RequestValidationError(
                f"AI summary failed: {message}",
                code="summary_failed",
                details={
                    "operation_id": operation_id,
                    "transcript_path": str(transcript_path),
                    "summary_ai_tier": summary_ai_tier,
                },
            ) from exc

        self._emit_event(
            EventMessage(
                event="summary.completed",
                payload={
                    "operation_id": operation_id,
                    "output_path": str(output_path),
                },
            )
        )
        return {
            "operation_id": operation_id,
            "output_path": str(output_path),
            "status": "completed",
        }

    def _get_system_metrics(self) -> dict:
        cpu_percent: float | None = None
        ram_percent: float | None = None
        ram_used_gb: float | None = None
        ram_total_gb: float | None = None
        gpu_percent: float | None = None
        gpu_memory_percent: float | None = None
        vram_used_gb: float | None = None
        vram_free_gb: float | None = None
        vram_total_gb: float | None = None
        gpu_temp_c: float | None = None
        gpu_power_w: float | None = None
        gpu_name: str = ""
        nvidia_driver_model: str = ""
        gpu_compute_apps: list[dict] = []

        try:
            import psutil  # type: ignore

            cpu_percent = float(psutil.cpu_percent(interval=None))
            vm = psutil.virtual_memory()
            ram_percent = float(vm.percent)
            ram_used_gb = float(vm.used / (1024**3))
            ram_total_gb = float(vm.total / (1024**3))
        except Exception:
            pass

        def _parse_float(raw: str) -> float | None:
            text = str(raw or "").strip()
            if not text or text.lower() in {"n/a", "[n/a]"}:
                return None
            try:
                return float(text)
            except Exception:
                return None

        nvidia_smi = _detect_nvidia_smi_path()

        if nvidia_smi:
            query = (
                "name,utilization.gpu,utilization.memory,memory.used,memory.free,memory.total,"
                "temperature.gpu,power.draw,driver_model.current"
            )
            cmd = [
                nvidia_smi,
                f"--query-gpu={query}",
                "--format=csv,noheader,nounits",
            ]
            try:
                result = subprocess.run(cmd, capture_output=True, text=True, check=False, timeout=3)
                if result.returncode == 0 and result.stdout.strip():
                    first = result.stdout.strip().splitlines()[0]
                    parts = [item.strip() for item in first.split(",")]
                    if len(parts) >= 9:
                        gpu_name = parts[0]
                        gpu_percent = _parse_float(parts[1])
                        gpu_memory_percent = _parse_float(parts[2])
                        mem_used_mb = _parse_float(parts[3])
                        mem_free_mb = _parse_float(parts[4])
                        mem_total_mb = _parse_float(parts[5])
                        gpu_temp_c = _parse_float(parts[6])
                        gpu_power_w = _parse_float(parts[7])
                        nvidia_driver_model = parts[8]

                        if mem_used_mb is not None:
                            vram_used_gb = mem_used_mb / 1024.0
                        if mem_free_mb is not None:
                            vram_free_gb = mem_free_mb / 1024.0
                        if mem_total_mb is not None:
                            vram_total_gb = mem_total_mb / 1024.0
                    elif len(parts) >= 4:
                        # Fallback for older nvidia-smi variants with shorter query support.
                        gpu_name = parts[0]
                        gpu_percent = _parse_float(parts[1])
                        mem_used_mb = _parse_float(parts[2])
                        mem_total_mb = _parse_float(parts[3])
                        if mem_used_mb is not None:
                            vram_used_gb = mem_used_mb / 1024.0
                        if mem_total_mb is not None:
                            vram_total_gb = mem_total_mb / 1024.0
            except Exception:
                pass

            compute_cmd = [
                nvidia_smi,
                "--query-compute-apps=pid,process_name,used_memory",
                "--format=csv,noheader,nounits",
            ]
            try:
                compute_result = subprocess.run(
                    compute_cmd,
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=3,
                )
                if compute_result.returncode == 0 and compute_result.stdout.strip():
                    for row in compute_result.stdout.strip().splitlines():
                        parts = [item.strip() for item in row.split(",", 2)]
                        if len(parts) < 3:
                            continue
                        try:
                            pid = int(parts[0])
                        except Exception:
                            continue

                        process_name = parts[1]
                        used_mb = _parse_float(parts[2])
                        used_gb = (used_mb / 1024.0) if used_mb is not None else None
                        gpu_compute_apps.append(
                            {
                                "pid": pid,
                                "process_name": process_name,
                                "used_memory_mb": used_mb,
                                "used_memory_gb": used_gb,
                            }
                        )
                    gpu_compute_apps.sort(
                        key=lambda item: float(item.get("used_memory_mb") or 0.0),
                        reverse=True,
                    )
            except Exception:
                pass

        return {
            "time_utc": _utc_now(),
            "platform": platform.platform(),
            "cpu_percent": cpu_percent,
            "ram_percent": ram_percent,
            "ram_used_gb": ram_used_gb,
            "ram_total_gb": ram_total_gb,
            "nvidia_smi_path": nvidia_smi,
            "gpu_percent": gpu_percent,
            "gpu_memory_percent": gpu_memory_percent,
            "vram_used_gb": vram_used_gb,
            "vram_free_gb": vram_free_gb,
            "vram_total_gb": vram_total_gb,
            "gpu_temp_c": gpu_temp_c,
            "gpu_power_w": gpu_power_w,
            "gpu_name": gpu_name,
            "nvidia_driver_model": nvidia_driver_model,
            "gpu_compute_apps": gpu_compute_apps,
        }

    def _run_pipeline(self, params: dict) -> dict:
        request = PipelineRequest.from_payload(params)
        module_component = get_module_component(request.module)
        request = module_component.configure_request(request)
        job_id = uuid.uuid4().hex
        record = JobRecord(
            job_id=job_id,
            request=request,
            created_at=_utc_now(),
            progress={"step": "queued", "overall_pct": 0.0, "step_pct": 0.0},
        )

        thread = Thread(target=self._run_job_thread, args=(record,), daemon=True, name=f"tm-v2-job-{job_id[:8]}")
        record.thread = thread

        with self._lock:
            active = self._first_active_job_locked()
            if active is not None:
                raise RequestValidationError(
                    "Only one pipeline job can run at a time. Wait for the active job to finish.",
                    code="job_limit_reached",
                    details={
                        "active_job_id": active.job_id,
                        "active_job_status": active.status,
                        "active_project_id": str(active.request.project.project_id or "").strip(),
                    },
                )
            self._jobs[job_id] = record

        self._persist_project_job_state(record, event="job.queued", payload={"status": record.status})
        thread.start()
        return {"job_id": job_id, "status": record.status}

    def _first_active_job_locked(self) -> JobRecord | None:
        for record in self._jobs.values():
            if record.status in {"queued", "running"}:
                return record
        return None

    def _cancel_job(self, params: dict) -> dict:
        job_id = str((params or {}).get("job_id") or "").strip()
        if not job_id:
            raise RequestValidationError("cancel_job requires 'job_id'.")

        with self._lock:
            record = self._jobs.get(job_id)

        if not record:
            raise RequestValidationError("Job not found.", code="not_found", details={"job_id": job_id})

        if record.status in {"completed", "failed", "cancelled"}:
            return {"job_id": job_id, "status": record.status, "cancel_requested": False}

        record.stop_flag.set()
        self._emit_job_event("job.cancel_requested", job_id, {"status": record.status})
        return {"job_id": job_id, "status": record.status, "cancel_requested": True}

    def _get_job(self, params: dict) -> dict:
        job_id = str((params or {}).get("job_id") or "").strip()
        if not job_id:
            raise RequestValidationError("get_job requires 'job_id'.")

        with self._lock:
            record = self._jobs.get(job_id)
        if not record:
            raise RequestValidationError("Job not found.", code="not_found", details={"job_id": job_id})
        return record.snapshot()

    def _list_jobs(self, params: dict | None = None) -> dict:
        params = params or {}
        module_filter = str(params.get("module_id") or params.get("module") or "").strip().lower()
        if module_filter and module_filter not in supported_module_ids():
            module_filter = ""
        project_filter = str(params.get("project_id") or "").strip()
        if not project_filter:
            project_node = params.get("project")
            if isinstance(project_node, dict):
                project_filter = str(project_node.get("project_id") or "").strip()

        with self._lock:
            snapshots = [record.snapshot() for record in self._jobs.values()]

        if module_filter:
            snapshots = [
                snapshot
                for snapshot in snapshots
                if str(snapshot.get("request", {}).get("module", "")).strip().lower() == module_filter
            ]
        if project_filter:
            snapshots = [
                snapshot
                for snapshot in snapshots
                if str(snapshot.get("request", {}).get("project", {}).get("project_id", "")).strip() == project_filter
            ]

        snapshots.sort(key=lambda item: item.get("created_at", ""), reverse=True)
        return {"jobs": snapshots}

    def _remove_job(self, params: dict) -> dict:
        job_id = str((params or {}).get("job_id") or "").strip()
        if not job_id:
            raise RequestValidationError("remove_job requires 'job_id'.")

        with self._lock:
            record = self._jobs.get(job_id)
            if not record:
                raise RequestValidationError("Job not found.", code="not_found", details={"job_id": job_id})
            if record.status in {"queued", "running"}:
                raise RequestValidationError(
                    "Cannot remove a running job. Cancel it first.",
                    code="invalid_state",
                    details={"job_id": job_id, "status": record.status},
                )
            self._jobs.pop(job_id, None)

        return {"job_id": job_id, "removed": True}

    def _run_job_thread(self, record: JobRecord):
        module_component = get_module_component(record.request.module)
        record.status = "running"
        record.started_at = _utc_now()
        self._emit_job_event(
            "job.started",
            record.job_id,
            {
                "status": record.status,
                "request": record.request.to_dict(),
                "runtime_profile": module_component.to_capability_dict(),
            },
        )

        pipeline = module_component.create_pipeline(
            request=record.request,
            stop_flag=record.stop_flag,
            log_cb=lambda line: self._on_job_log(record, line),
            progress_cb=lambda payload: self._on_job_progress(record, payload),
        )

        try:
            result: PipelineRunResult = pipeline.run()
            if record.stop_flag.is_set():
                record.status = "cancelled"
                record.finished_at = _utc_now()
                self._emit_job_event(
                    "job.cancelled",
                    record.job_id,
                    {
                        "status": record.status,
                        "result": result.to_dict(),
                    },
                )
                return

            record.status = "completed"
            record.result = result.to_dict()
            record.warnings = list(result.warnings)
            record.finished_at = _utc_now()
            self._emit_job_event(
                "job.completed",
                record.job_id,
                {
                    "status": record.status,
                    "result": record.result,
                },
            )
        except PipelineCancelledError as exc:
            record.status = "cancelled"
            record.finished_at = _utc_now()
            record.error = {
                "code": "cancelled",
                "message": str(exc),
            }
            self._emit_job_event(
                "job.cancelled",
                record.job_id,
                {
                    "status": record.status,
                    "error": record.error,
                },
            )
        except Exception as exc:
            record.status = "failed"
            record.finished_at = _utc_now()
            record.error = {
                "code": "pipeline_failed",
                "message": str(exc),
                "traceback": traceback.format_exc(),
            }
            self._emit_job_event(
                "job.failed",
                record.job_id,
                {
                    "status": record.status,
                    "error": record.error,
                },
            )

    def _on_job_log(self, record: JobRecord, line: str):
        cleaned = str(line or "").strip()
        if not cleaned:
            return
        record.logs.append(cleaned)
        self._persist_project_log_line(record, cleaned)
        self._emit_job_event("job.log", record.job_id, {"line": cleaned})

    def _on_job_progress(self, record: JobRecord, payload: dict):
        record.progress = dict(payload or {})
        self._emit_job_event("job.progress", record.job_id, dict(record.progress))

    def _emit_job_event(self, event: str, job_id: str, payload: dict):
        normalized_payload = dict(payload or {})
        self._emit_event(EventMessage(event=event, payload=normalized_payload, job_id=job_id))
        with self._lock:
            record = self._jobs.get(job_id)
        if record is not None:
            persist_timeline = event != "job.log"
            persist_snapshot = event in {
                "job.queued",
                "job.started",
                "job.completed",
                "job.failed",
                "job.cancelled",
                "job.cancel_requested",
            }
            if persist_timeline or persist_snapshot:
                self._persist_project_job_state(
                    record,
                    event=event,
                    payload=normalized_payload,
                    write_snapshot=persist_snapshot,
                    write_timeline=persist_timeline,
                )

    def _persist_project_job_state(
        self,
        record: JobRecord,
        *,
        event: str,
        payload: dict | None = None,
        write_snapshot: bool = True,
        write_timeline: bool = True,
    ) -> None:
        project = record.request.project
        if not project.enabled:
            return

        try:
            jobs_dir = self._resolve_project_jobs_dir(record)
            if jobs_dir is None:
                return
            jobs_dir.mkdir(parents=True, exist_ok=True)

            if write_snapshot:
                snapshot_path = jobs_dir / f"{record.job_id}.json"
                snapshot_path.write_text(
                    json.dumps(record.snapshot(), ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )

            if write_timeline:
                timeline_path = self._resolve_project_timeline_path(record, jobs_dir)
                timeline_entry: dict[str, object] = {
                    "ts": _utc_now(),
                    "event": str(event or ""),
                    "job_id": record.job_id,
                    "status": str((payload or {}).get("status") or record.status),
                    "module": record.request.module,
                    "project_id": project.project_id,
                    "project_name": project.name,
                    "source_mode": record.request.source.mode,
                    "output_mode": record.request.output.mode,
                }
                if payload:
                    step = payload.get("step")
                    if step is not None:
                        timeline_entry["step"] = step
                    overall_pct = payload.get("overall_pct")
                    if overall_pct is not None:
                        timeline_entry["overall_pct"] = overall_pct
                    line = str(payload.get("line") or "").strip()
                    if line:
                        timeline_entry["line"] = line[:4096]
                    error_payload = payload.get("error")
                    if isinstance(error_payload, dict) and error_payload:
                        timeline_entry["error"] = error_payload

                with timeline_path.open("a", encoding="utf-8") as handle:
                    handle.write(json.dumps(timeline_entry, ensure_ascii=False))
                    handle.write("\n")
        except Exception:
            # Timeline persistence must never fail the active job.
            return

    @staticmethod
    def _resolve_project_jobs_dir(record: JobRecord) -> Path | None:
        project = record.request.project
        jobs_dir = str(project.jobs_dir or "").strip()
        if jobs_dir:
            return Path(jobs_dir).expanduser()
        root_dir = str(project.root_dir or "").strip()
        if root_dir:
            return Path(root_dir).expanduser() / "jobs"
        return None

    @staticmethod
    def _resolve_project_timeline_path(record: JobRecord, jobs_dir: Path) -> Path:
        timeline_path = str(record.request.project.timeline_path or "").strip()
        if timeline_path:
            return Path(timeline_path).expanduser()
        return jobs_dir / "timeline.jsonl"

    def _persist_project_log_line(self, record: JobRecord, line: str) -> None:
        project = record.request.project
        if not project.enabled:
            return

        try:
            logs_dir = self._resolve_project_logs_dir(record)
            if logs_dir is None:
                return
            logs_dir.mkdir(parents=True, exist_ok=True)
            timestamp = _utc_now()
            message = str(line or "").strip()
            if not message:
                return

            job_log_path = logs_dir / f"{record.job_id}.log"
            project_log_path = logs_dir / "project.log"
            with job_log_path.open("a", encoding="utf-8") as handle:
                handle.write(f"[{timestamp}] {message}\n")
            with project_log_path.open("a", encoding="utf-8") as handle:
                handle.write(f"[{timestamp}] [{record.job_id}] {message}\n")
        except Exception:
            # Log persistence must never fail the active job.
            return

    @staticmethod
    def _resolve_project_logs_dir(record: JobRecord) -> Path | None:
        project = record.request.project
        logs_dir = str(project.logs_dir or "").strip()
        if logs_dir:
            return Path(logs_dir).expanduser()
        root_dir = str(project.root_dir or "").strip()
        if root_dir:
            return Path(root_dir).expanduser() / "logs"
        return None

    @staticmethod
    def _path_within_root(path: Path, root: Path) -> bool:
        try:
            resolved_path = Path(path).expanduser().resolve()
            resolved_root = Path(root).expanduser().resolve()
            resolved_path.relative_to(resolved_root)
            return True
        except Exception:
            return False


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _detect_nvidia_smi_path() -> str:
    for exe_name in ("nvidia-smi.exe", "nvidia-smi"):
        path = shutil.which(exe_name)
        if path:
            return path

    if os.name == "nt":
        system_root = os.environ.get("SystemRoot") or os.environ.get("WINDIR") or r"C:\Windows"
        program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
        program_w6432 = os.environ.get("ProgramW6432", program_files)
        program_files_x86 = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
        candidates = [
            os.path.join(system_root, "System32", "nvidia-smi.exe"),
            os.path.join(system_root, "Sysnative", "nvidia-smi.exe"),
            os.path.join(program_w6432, "NVIDIA Corporation", "NVSMI", "nvidia-smi.exe"),
            os.path.join(program_files, "NVIDIA Corporation", "NVSMI", "nvidia-smi.exe"),
            os.path.join(program_files_x86, "NVIDIA Corporation", "NVSMI", "nvidia-smi.exe"),
        ]
        for candidate in candidates:
            if os.path.exists(candidate):
                return candidate
    return ""


def _read_nvidia_smi_cuda_version(nvidia_smi_path: str) -> str:
    if not nvidia_smi_path:
        return ""
    try:
        result = subprocess.run(
            [nvidia_smi_path],
            capture_output=True,
            text=True,
            check=False,
            timeout=3,
        )
    except Exception:
        return ""

    if result.returncode != 0:
        return ""

    match = re.search(r"CUDA Version:\s*([0-9]+(?:\.[0-9]+)?)", result.stdout or "")
    if not match:
        return ""
    return match.group(1)


def _gather_gpu_runtime_diagnostics() -> dict:
    diag = {
        "nvidia_smi_path": "",
        "nvidia_gpu_detected": False,
        "nvidia_gpu_name": "",
        "nvidia_driver_version": "",
        "nvidia_driver_model": "",
        "nvidia_cuda_runtime_version": "",
        "torch_ok": False,
        "torch_version": "",
        "torch_cuda_available": False,
        "torch_cuda_build": "",
        "torch_error": "",
        "ctranslate2_version": "",
        "ctranslate2_cuda_devices": None,
        "ctranslate2_error": "",
    }

    nvidia_smi = _detect_nvidia_smi_path()
    diag["nvidia_smi_path"] = nvidia_smi
    diag["nvidia_cuda_runtime_version"] = _read_nvidia_smi_cuda_version(nvidia_smi)
    if nvidia_smi:
        try:
            query_cmd = [
                nvidia_smi,
                "--query-gpu=name,driver_version,driver_model.current",
                "--format=csv,noheader",
            ]
            result = subprocess.run(query_cmd, capture_output=True, text=True, check=False, timeout=3)
            if result.returncode == 0 and result.stdout.strip():
                first = result.stdout.strip().splitlines()[0]
                parts = [item.strip() for item in first.split(",")]
                diag["nvidia_gpu_detected"] = True
                if parts:
                    diag["nvidia_gpu_name"] = parts[0]
                if len(parts) > 1:
                    diag["nvidia_driver_version"] = parts[1]
                if len(parts) > 2:
                    diag["nvidia_driver_model"] = parts[2]
        except Exception:
            # Best effort diagnostics only.
            pass

    try:
        import torch  # type: ignore

        diag["torch_ok"] = True
        diag["torch_version"] = str(getattr(torch, "__version__", "unknown"))
        diag["torch_cuda_available"] = bool(torch.cuda.is_available())
        cuda_build = getattr(getattr(torch, "version", None), "cuda", None)
        diag["torch_cuda_build"] = str(cuda_build or "")
        if diag["torch_cuda_available"] and not diag["nvidia_gpu_detected"]:
            diag["nvidia_gpu_detected"] = True
            try:
                diag["nvidia_gpu_name"] = str(torch.cuda.get_device_name(0))
            except Exception:
                if not diag["nvidia_gpu_name"]:
                    diag["nvidia_gpu_name"] = "CUDA device (torch)"
    except Exception as exc:
        diag["torch_error"] = f"{type(exc).__name__}: {exc}"

    try:
        import ctranslate2  # type: ignore

        diag["ctranslate2_version"] = str(getattr(ctranslate2, "__version__", ""))
        if hasattr(ctranslate2, "get_cuda_device_count"):
            diag["ctranslate2_cuda_devices"] = int(ctranslate2.get_cuda_device_count())
    except Exception as exc:
        diag["ctranslate2_error"] = f"{type(exc).__name__}: {exc}"

    return diag
