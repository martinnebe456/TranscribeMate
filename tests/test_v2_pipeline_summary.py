from pathlib import Path
from threading import Event

import pytest

from transcribemate.core.types import TranscriptSegment, TranscriptionResult
from transcribemate.v2.backend.models import PipelineRequest
from transcribemate.v2.backend.pipeline import PipelineOrchestrator


def _payload(tmp_path: Path, *, summary_enabled: bool) -> dict:
    project_root = tmp_path / "project"
    return {
        "module": "offline_transcribe",
        "source": {"mode": "local", "path": str(tmp_path / "source")},
        "output": {"mode": "txt_only", "out_dir": str(project_root / "output")},
        "transcription": {
            "model": "large-v3",
            "auto_model": False,
            "prefer_gpu": False,
            "source_lang": "auto",
            "batch_size": 16,
        },
        "translation": {"enabled": False, "target_lang": "en->cs"},
        "subtitles": {"mode": "soft"},
        "text": {
            "summary_ai_enabled": summary_enabled,
            "summary_ai_tier": "low",
            "summary_lang": "cs",
        },
        "diarization": {"enabled": False},
        "project": {
            "project_id": "project-001",
            "name": "Project 001",
            "root_dir": str(project_root),
        },
    }


@pytest.mark.parametrize("summary_enabled", [True, False])
def test_pipeline_summary_artifacts_follow_toggle(tmp_path, monkeypatch, summary_enabled):
    summary_calls: list[dict] = []

    def fake_collect_inputs(self, downloads_dir):  # noqa: ANN001
        media = tmp_path / "source.wav"
        media.write_text("stub", encoding="utf-8")
        return [media], []

    def fake_transcribe(*args, **kwargs):  # noqa: ANN002
        media_path = args[0]
        return TranscriptionResult(
            srt_path=tmp_path / f"{media_path.stem}.srt",
            raw_txt_path=tmp_path / f"{media_path.stem}.txt",
            segments=[TranscriptSegment(start=0.0, end=1.0, text="Hello world")],
            detected_lang="en",
            duration=1.0,
        )

    def fake_export_transcripts(**kwargs):
        txt_path = kwargs["transcripts_dir"] / "meeting.txt"
        txt_path.parent.mkdir(parents=True, exist_ok=True)
        txt_path.write_text("Hello world", encoding="utf-8")
        return [txt_path]

    def fake_summarize_transcript_file(**kwargs):
        summary_calls.append(kwargs)
        summary_path = kwargs["output_dir"] / "meeting.summary.cs.md"
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        summary_path.write_text("## TL;DR\n\n- done", encoding="utf-8")
        return summary_path

    monkeypatch.setattr("transcribemate.v2.backend.pipeline.configure_model_environment", lambda: None)
    monkeypatch.setattr("transcribemate.v2.backend.pipeline.ensure_site_packages_on_path", lambda: None)
    monkeypatch.setattr("transcribemate.v2.backend.pipeline.PipelineOrchestrator._collect_inputs", fake_collect_inputs)
    monkeypatch.setattr("transcribemate.v2.backend.pipeline.faster_whisper_transcribe", fake_transcribe)
    monkeypatch.setattr("transcribemate.v2.backend.pipeline.export_transcripts", fake_export_transcripts)
    monkeypatch.setattr("transcribemate.v2.backend.pipeline.summarize_transcript_file", fake_summarize_transcript_file)

    request = PipelineRequest.from_payload(_payload(tmp_path, summary_enabled=summary_enabled))
    orchestrator = PipelineOrchestrator(
        request=request,
        stop_flag=Event(),
        log_cb=lambda _: None,
        progress_cb=lambda _: None,
    )

    result = orchestrator.run()
    summary_artifacts = [artifact for artifact in result.artifacts if artifact.path.endswith(".summary.cs.md")]
    if summary_enabled:
        assert len(summary_calls) == 1
        assert len(summary_artifacts) == 1
        assert Path(summary_artifacts[0].path).is_file()
    else:
        assert not summary_calls
        assert not summary_artifacts
