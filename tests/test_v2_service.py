import builtins
import json
from pathlib import Path

import pytest

from transcribemate.core.types import SpeakerTurn, TranscriptSegment, TranscriptionResult
from transcribemate.pipeline.diarize import build_speaker_sidecar, load_speaker_sidecar, save_speaker_sidecar
from transcribemate.v2.backend.models import PipelineRequest, RequestValidationError
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
        "project": {
            "project_id": "project-default",
            "name": "Project Default",
            "root_dir": ".",
        },
    }


def _youtube_payload(*, output_mode: str = "txt_only") -> dict:
    payload = _payload(output_mode=output_mode)
    payload["module"] = "youtube_transcribe" if output_mode == "txt_only" else payload["module"]
    payload["source"] = {
        "mode": "youtube",
        "url": "https://www.youtube.com/watch?v=test123",
        "is_playlist": False,
        "quality": "best",
    }
    return payload


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
    assert "summarize_transcript" in caps["methods"]
    assert "get_system_metrics" in caps["methods"]
    assert "offline_transcribe" in caps["modules"]
    assert "youtube_dub" in caps["modules"]
    assert "local_cluster_fast" in caps["diarization_backends"]
    assert "local_cluster_accurate" in caps["diarization_backends"]
    assert caps["summary_ai_tiers"] == ["low", "medium", "high"]
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


def test_preflight_requires_torch_for_ai_summary(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "torch":
            raise ModuleNotFoundError("No module named 'torch'")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", fake_import)

    payload = _payload(output_mode="txt_only")
    payload["text"] = {"summary_ai_enabled": True, "summary_ai_tier": "medium"}
    svc = BackendService(emit_event=lambda _: None)
    result = svc.handle_request("preflight_check", payload)

    assert result["ok"] is False
    torch_checks = [item for item in result["checks"] if item["name"] == "torch"]
    assert torch_checks
    assert torch_checks[-1]["status"] == "fail"


def test_preflight_allows_youtube_txt_only_without_ffmpeg(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "yt_dlp":
            return object()
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", fake_import)
    monkeypatch.setattr("transcribemate.v2.backend.service.ffmpeg_path", lambda: None)
    monkeypatch.setattr(
        "transcribemate.v2.backend.service._gather_gpu_runtime_diagnostics",
        lambda: {
            "torch_ok": True,
            "torch_version": "test",
            "torch_cuda_available": False,
            "torch_cuda_build": "",
            "nvidia_gpu_detected": False,
            "nvidia_smi_path": "",
        },
    )

    svc = BackendService(emit_event=lambda _: None)
    result = svc.handle_request("preflight_check", _youtube_payload(output_mode="txt_only"))

    assert result["ok"] is True
    ffmpeg_checks = [item for item in result["checks"] if item["name"] == "ffmpeg"]
    assert ffmpeg_checks
    assert ffmpeg_checks[-1]["status"] == "pass"
    assert "fallback" in ffmpeg_checks[-1]["message"].lower()


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


def test_summarize_transcript_emits_summary_events(tmp_path, monkeypatch):
    events = []
    svc = BackendService(emit_event=lambda event: events.append(event))

    project_root = tmp_path / "project"
    output_dir = project_root / "output"
    output_dir.mkdir(parents=True, exist_ok=True)
    transcript = output_dir / "meeting.txt"
    transcript.write_text("Hello world", encoding="utf-8")

    def fake_summarize_transcript_file(**kwargs):
        progress = kwargs.get("progress")
        log = kwargs.get("log")
        if progress is not None:
            progress("prepare", 20.0, 20.0, False)
            progress("synthesize", 80.0, 80.0, False)
        if log is not None:
            log("[INFO] fake summary log")
        output_path = kwargs["output_dir"] / "meeting.summary.cs.md"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text("## TL;DR\n\n- Done", encoding="utf-8")
        if progress is not None:
            progress("done", 100.0, 100.0, False)
        return output_path

    monkeypatch.setattr(
        "transcribemate.v2.backend.service.summarize_transcript_file",
        fake_summarize_transcript_file,
    )

    result = svc.handle_request(
        "summarize_transcript",
        {
            "operation_id": "op-001",
            "transcript_path": str(transcript),
            "summary_lang": "cs",
            "summary_ai_tier": "low",
            "project": {
                "project_id": "project-001",
                "name": "Project 001",
                "root_dir": str(project_root),
            },
        },
    )

    assert result["status"] == "completed"
    assert result["operation_id"] == "op-001"
    assert Path(result["output_path"]).is_file()
    event_names = [event.event for event in events]
    assert "summary.started" in event_names
    assert "summary.progress" in event_names
    assert "summary.log" in event_names
    assert "summary.completed" in event_names


def test_summarize_transcript_validates_path_extension_and_active_job(tmp_path):
    svc = BackendService(emit_event=lambda _: None)
    project_root = tmp_path / "project"
    output_dir = project_root / "output"
    output_dir.mkdir(parents=True, exist_ok=True)

    valid_txt = output_dir / "valid.txt"
    valid_txt.write_text("hello", encoding="utf-8")
    invalid_ext = output_dir / "invalid.md"
    invalid_ext.write_text("hello", encoding="utf-8")
    outside_path = project_root / "input" / "outside.txt"
    outside_path.parent.mkdir(parents=True, exist_ok=True)
    outside_path.write_text("hello", encoding="utf-8")

    base_params = {
        "operation_id": "op-validate",
        "summary_lang": "auto",
        "summary_ai_tier": "medium",
        "project": {
            "project_id": "project-001",
            "name": "Project 001",
            "root_dir": str(project_root),
        },
    }

    with pytest.raises(RequestValidationError):
        svc.handle_request(
            "summarize_transcript",
            {
                **base_params,
                "transcript_path": str(invalid_ext),
            },
        )

    with pytest.raises(RequestValidationError):
        svc.handle_request(
            "summarize_transcript",
            {
                **base_params,
                "transcript_path": str(outside_path),
            },
        )

    active_request = PipelineRequest.from_payload(_payload(output_mode="txt_only"))
    svc._jobs["active"] = JobRecord(
        job_id="active",
        request=active_request,
        created_at="2026-02-17T10:00:00+00:00",
        status="running",
    )
    with pytest.raises(RequestValidationError) as exc:
        svc.handle_request(
            "summarize_transcript",
            {
                **base_params,
                "transcript_path": str(valid_txt),
            },
        )
    assert exc.value.code == "job_limit_reached"


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


def test_list_jobs_supports_project_filter(tmp_path):
    svc = BackendService(emit_event=lambda _: None)

    alpha_root = tmp_path / "alpha"
    beta_root = tmp_path / "beta"

    alpha_payload = _payload(output_mode="txt_only")
    alpha_payload["project"] = {
        "project_id": "project-alpha",
        "name": "Project Alpha",
        "root_dir": str(alpha_root),
    }
    alpha_request = PipelineRequest.from_payload(alpha_payload)

    beta_payload = _payload(output_mode="txt_only")
    beta_payload["project"] = {
        "project_id": "project-beta",
        "name": "Project Beta",
        "root_dir": str(beta_root),
    }
    beta_request = PipelineRequest.from_payload(beta_payload)

    svc._jobs["job-alpha"] = JobRecord(
        job_id="job-alpha",
        request=alpha_request,
        created_at="2026-02-17T10:00:00+00:00",
    )
    svc._jobs["job-beta"] = JobRecord(
        job_id="job-beta",
        request=beta_request,
        created_at="2026-02-17T10:01:00+00:00",
    )

    alpha_jobs = svc.handle_request("list_jobs", {"project_id": "project-alpha"})["jobs"]
    beta_jobs = svc.handle_request("list_jobs", {"project_id": "project-beta"})["jobs"]

    assert len(alpha_jobs) == 1
    assert len(beta_jobs) == 1
    assert alpha_jobs[0]["request"]["project"]["project_id"] == "project-alpha"
    assert beta_jobs[0]["request"]["project"]["project_id"] == "project-beta"


def test_project_job_timeline_is_persisted(tmp_path):
    captured = []
    svc = BackendService(emit_event=lambda event: captured.append(event))

    payload = _payload(output_mode="txt_only")
    payload["project"] = {
        "project_id": "project-alpha",
        "name": "Project Alpha",
        "root_dir": str(tmp_path),
    }
    request = PipelineRequest.from_payload(payload)

    record = JobRecord(
        job_id="job-alpha",
        request=request,
        created_at="2026-02-17T10:00:00+00:00",
        status="running",
    )
    svc._jobs["job-alpha"] = record

    svc._emit_job_event(
        "job.progress",
        "job-alpha",
        {"status": "running", "step": "transcribe", "overall_pct": 42.0},
    )
    svc._emit_job_event("job.started", "job-alpha", {"status": "running"})

    jobs_dir = tmp_path / "jobs"
    snapshot_path = jobs_dir / "job-alpha.json"
    timeline_path = jobs_dir / "timeline.jsonl"

    assert captured
    assert snapshot_path.is_file()
    assert timeline_path.is_file()

    timeline_rows = [
        json.loads(line)
        for line in timeline_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    assert timeline_rows
    assert timeline_rows[0]["event"] == "job.progress"
    assert timeline_rows[0]["project_id"] == "project-alpha"
    assert timeline_rows[0]["overall_pct"] == 42.0


def test_run_pipeline_rejects_parallel_job(tmp_path):
    svc = BackendService(emit_event=lambda _: None)
    active_payload = _payload(output_mode="txt_only")
    active_payload["project"] = {
        "project_id": "project-alpha",
        "name": "Project Alpha",
        "root_dir": str(tmp_path / "alpha"),
    }
    active_request = PipelineRequest.from_payload(active_payload)
    svc._jobs["active-job"] = JobRecord(
        job_id="active-job",
        request=active_request,
        created_at="2026-02-17T10:00:00+00:00",
        status="running",
    )

    queued_payload = _payload(output_mode="txt_only")
    queued_payload["project"] = {
        "project_id": "project-beta",
        "name": "Project Beta",
        "root_dir": str(tmp_path / "beta"),
    }

    with pytest.raises(RequestValidationError) as exc:
        svc.handle_request("run_pipeline", queued_payload)

    assert exc.value.code == "job_limit_reached"


def test_project_job_logs_are_persisted(tmp_path):
    svc = BackendService(emit_event=lambda _: None)
    payload = _payload(output_mode="txt_only")
    payload["project"] = {
        "project_id": "project-logs",
        "name": "Project Logs",
        "root_dir": str(tmp_path),
    }
    request = PipelineRequest.from_payload(payload)
    record = JobRecord(
        job_id="job-logs",
        request=request,
        created_at="2026-02-17T10:00:00+00:00",
        status="running",
    )

    svc._on_job_log(record, "[INFO] Testing project log persistence.")

    project_log = tmp_path / "logs" / "project.log"
    job_log = tmp_path / "logs" / "job-logs.log"
    assert project_log.is_file()
    assert job_log.is_file()
    assert "job-logs" in project_log.read_text(encoding="utf-8")
    assert "Testing project log persistence" in job_log.read_text(encoding="utf-8")
