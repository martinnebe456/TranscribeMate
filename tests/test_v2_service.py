import builtins

from transcribemate.v2.backend.protocol import EventMessage
from transcribemate.v2.backend.service import BackendService


def _payload(*, output_mode: str = "txt_only") -> dict:
    return {
        "module": "offline_transcribe",
        "source": {"mode": "local", "path": "tests"},
        "output": {"mode": output_mode, "out_dir": "."},
        "transcription": {
            "model": "large-v3",
            "auto_model": False,
            "prefer_gpu": False,
            "source_lang": "auto",
            "batch_size": 16,
        },
        "translation": {"enabled": output_mode in {"srt_only", "video_subs", "video_dub"}, "target_lang": "en→cs"},
        "subtitles": {"mode": "soft"},
        "text": {},
        "diarization": {},
    }


def test_service_ping_and_capabilities():
    events = []

    def emitter(event: EventMessage):
        events.append(event)

    svc = BackendService(emit_event=emitter)

    ping = svc.handle_request("ping", {})
    caps = svc.handle_request("get_capabilities", {})
    paths = svc.handle_request("get_paths", {})
    health = svc.handle_request("health", {})
    metrics = svc.handle_request("get_system_metrics", {})
    preflight = svc.handle_request("preflight_check", _payload())

    assert ping["service"] == "transcribemate-v2-backend"
    assert "version" in ping
    assert "run_pipeline" in caps["methods"]
    assert "refresh_model_cache" in caps["methods"]
    assert "preflight_check" in caps["methods"]
    assert "get_system_metrics" in caps["methods"]
    assert "offline_transcribe" in caps["modules"]
    assert "youtube_dub" in caps["modules"]
    assert "stable_local" in caps["diarization_backends"]
    assert "user_data_dir" in paths
    assert "python_executable" in health
    assert "nvidia_driver_model" in health
    assert "nvidia_cuda_runtime_version" in health
    assert "cpu_percent" in metrics
    assert "gpu_memory_percent" in metrics
    assert "gpu_compute_apps" in metrics
    assert isinstance(metrics["gpu_compute_apps"], list)
    assert "ok" in preflight


def test_preflight_allows_missing_torch_for_txt_only(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "torch":
            raise ModuleNotFoundError("No module named 'torch'")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", fake_import)

    svc = BackendService(emit_event=lambda _: None)
    result = svc.handle_request("preflight_check", _payload(output_mode="txt_only"))

    assert result["ok"] is True
    torch_checks = [item for item in result["checks"] if item["name"] == "torch"]
    assert torch_checks
    assert torch_checks[-1]["status"] == "warn"


def test_preflight_requires_torch_for_translation_modes(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "torch":
            raise ModuleNotFoundError("No module named 'torch'")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", fake_import)

    svc = BackendService(emit_event=lambda _: None)
    result = svc.handle_request("preflight_check", _payload(output_mode="srt_only"))

    assert result["ok"] is False
    torch_checks = [item for item in result["checks"] if item["name"] == "torch"]
    assert torch_checks
    assert torch_checks[-1]["status"] == "fail"
