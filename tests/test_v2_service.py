import builtins
from pathlib import Path

from transcribemate.core.types import SpeakerTurn, TranscriptSegment, TranscriptionResult
from transcribemate.pipeline.diarize import build_speaker_sidecar, load_speaker_sidecar, save_speaker_sidecar
from transcribemate.v2.backend.models import PipelineRequest
from transcribemate.v2.backend.protocol import EventMessage
from transcribemate.v2.backend.service import BackendService, JobRecord


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
    assert "local_cluster_fast" in caps["diarization_backends"]
    assert "local_cluster_accurate" in caps["diarization_backends"]
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


def test_preflight_requires_local_diarization_runtime(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name.startswith("speechbrain"):
            raise ModuleNotFoundError("No module named 'speechbrain'")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", fake_import)

    payload = _payload(output_mode="txt_only")
    payload["module"] = "speaker_transcribe"
    payload["diarization"] = {"enabled": True, "backend": "local_cluster_accurate"}

    svc = BackendService(emit_event=lambda _: None)
    result = svc.handle_request("preflight_check", payload)

    assert result["ok"] is False
    diarization_checks = [item for item in result["checks"] if item["name"] == "diarization_runtime"]
    assert diarization_checks
    assert diarization_checks[-1]["status"] == "fail"


def test_apply_speaker_mapping_updates_sidecar_and_transcripts(tmp_path):
    transcripts_dir = tmp_path / "transcripts"
    transcripts_dir.mkdir(parents=True, exist_ok=True)

    result = TranscriptionResult(
        srt_path=tmp_path / "source.srt",
        raw_txt_path=tmp_path / "raw.txt",
        segments=[
            TranscriptSegment(start=0.0, end=0.8, text="Hello", speaker_id="SPEAKER_00"),
            TranscriptSegment(start=1.0, end=1.8, text="World", speaker_id="SPEAKER_01"),
        ],
        detected_lang="en",
        duration=2.0,
        speaker_turns=[
            SpeakerTurn(start=0.0, end=0.9, speaker_id="SPEAKER_00", confidence=0.9),
            SpeakerTurn(start=0.9, end=2.0, speaker_id="SPEAKER_01", confidence=0.9),
        ],
        speaker_map={"SPEAKER_00": "", "SPEAKER_01": ""},
    )
    sidecar_payload = build_speaker_sidecar(
        media_path=Path("meeting.wav"),
        base_name="meeting_2026",
        result=result,
        model_name="large-v3",
        output_mode="conference",
        output_prefix="",
        clean_text=False,
        export_md=True,
        split_minutes=0,
        generate_summary_pack=False,
        summary_lang="auto",
        speaker="",
        topic="",
        transcripts_dir=transcripts_dir,
    )
    sidecar_path = tmp_path / "meeting_2026.diarization.json"
    save_speaker_sidecar(sidecar_path, sidecar_payload)

    svc = BackendService(emit_event=lambda _: None)
    response = svc.handle_request(
        "apply_speaker_mapping",
        {
            "sidecar_path": str(sidecar_path),
            "speaker_map": {
                "SPEAKER_00": "Karel",
                "SPEAKER_01": "Marketa",
            },
        },
    )

    assert response["rewritten_count"] >= 2
    loaded = load_speaker_sidecar(sidecar_path)
    assert loaded["speaker_map"]["SPEAKER_00"] == "Karel"
    assert loaded["speaker_map"]["SPEAKER_01"] == "Marketa"
    assert loaded["segments"][0].speaker_name == "Karel"
    assert loaded["segments"][1].speaker_name == "Marketa"

    txt_path = transcripts_dir / "meeting_2026.txt"
    md_path = transcripts_dir / "meeting_2026.md"
    assert txt_path.is_file()
    assert md_path.is_file()
    assert "Karel: Hello" in txt_path.read_text(encoding="utf-8")
    assert "Marketa: World" in txt_path.read_text(encoding="utf-8")


def test_list_jobs_supports_module_filter():
    svc = BackendService(emit_event=lambda _: None)

    offline_payload = _payload(output_mode="txt_only")
    offline_payload["module"] = "offline_transcribe"
    offline_request = PipelineRequest.from_payload(offline_payload)

    youtube_payload = _payload(output_mode="txt_only")
    youtube_payload["module"] = "youtube_transcribe"
    youtube_payload["source"] = {
        "mode": "youtube",
        "url": "https://example.com/watch?v=abc",
        "is_playlist": False,
        "quality": "best",
    }
    youtube_request = PipelineRequest.from_payload(youtube_payload)

    svc._jobs["job-a"] = JobRecord(job_id="job-a", request=offline_request, created_at="2026-02-17T10:00:00+00:00")
    svc._jobs["job-b"] = JobRecord(job_id="job-b", request=youtube_request, created_at="2026-02-17T10:01:00+00:00")

    all_jobs = svc.handle_request("list_jobs", {})["jobs"]
    offline_jobs = svc.handle_request("list_jobs", {"module_id": "offline_transcribe"})["jobs"]
    youtube_jobs = svc.handle_request("list_jobs", {"module": "youtube_transcribe"})["jobs"]

    assert len(all_jobs) == 2
    assert len(offline_jobs) == 1
    assert len(youtube_jobs) == 1
    assert offline_jobs[0]["request"]["module"] == "offline_transcribe"
    assert youtube_jobs[0]["request"]["module"] == "youtube_transcribe"
