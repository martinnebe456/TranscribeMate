import pytest

from transcribemate.v2.backend.models import PipelineRequest, RequestValidationError


def _base_payload():
    return {
        "module": "offline_transcribe",
        "source": {"mode": "local", "path": "tests"},
        "output": {"mode": "txt_only", "out_dir": "."},
        "transcription": {"model": "large-v3", "auto_model": False, "prefer_gpu": False, "source_lang": "auto", "batch_size": 16},
        "translation": {"enabled": False, "target_lang": "en→cs"},
        "subtitles": {"mode": "soft", "font": "Arial", "size": 24, "color": "#fff", "outline_color": "#000", "outline_width": 2},
        "text": {"clean_text": False, "export_md": False, "summary_pack": False, "split_minutes": 0, "summary_lang": "auto"},
        "diarization": {
            "enabled": False,
            "backend": "local_cluster_accurate",
            "accuracy_profile": "balanced",
            "min_speakers": 0,
            "max_speakers": 0,
        },
    }


def test_pipeline_request_parses_valid_payload():
    req = PipelineRequest.from_payload(_base_payload())
    assert req.module == "offline_transcribe"
    assert req.source.mode == "local"
    assert req.output.mode == "txt_only"
    assert req.translation.target_lang == "en→cs"
    assert req.translation.enabled is False
    assert req.translation_needed is False


def test_pipeline_request_rejects_invalid_output_mode():
    payload = _base_payload()
    payload["output"]["mode"] = "broken"
    with pytest.raises(RequestValidationError):
        PipelineRequest.from_payload(payload)


def test_pipeline_request_rejects_missing_local_source():
    payload = _base_payload()
    payload["source"] = {"mode": "local", "path": "", "files": []}
    with pytest.raises(RequestValidationError):
        PipelineRequest.from_payload(payload)


def test_pipeline_request_normalizes_translation_target_variants():
    payload = _base_payload()
    payload["translation"]["target_lang"] = "en->cs"
    req = PipelineRequest.from_payload(payload)
    assert req.translation.target_lang == "en→cs"

    payload["translation"]["target_lang"] = "enâ†’cs"
    req = PipelineRequest.from_payload(payload)
    assert req.translation.target_lang == "en→cs"


def test_pipeline_request_rejects_unknown_module():
    payload = _base_payload()
    payload["module"] = "unknown_module"
    with pytest.raises(RequestValidationError):
        PipelineRequest.from_payload(payload)


def test_pipeline_request_maps_legacy_diarization_backends():
    payload = _base_payload()
    payload["diarization"]["backend"] = "advanced_pyannote"
    req = PipelineRequest.from_payload(payload)
    assert req.diarization.backend == "local_cluster_accurate"

    payload["diarization"]["backend"] = "stable_local"
    req = PipelineRequest.from_payload(payload)
    assert req.diarization.backend == "local_cluster_fast"


def test_pipeline_request_normalizes_diarization_accuracy_profile():
    payload = _base_payload()
    payload["diarization"]["accuracy_profile"] = "max"
    req = PipelineRequest.from_payload(payload)
    assert req.diarization.accuracy_profile == "maximum"

    payload["diarization"]["accuracy_profile"] = "small"
    req = PipelineRequest.from_payload(payload)
    assert req.diarization.accuracy_profile == "low"


def test_pipeline_request_rejects_invalid_diarization_accuracy_profile():
    payload = _base_payload()
    payload["diarization"]["accuracy_profile"] = "ultra"
    with pytest.raises(RequestValidationError):
        PipelineRequest.from_payload(payload)


def test_pipeline_request_requires_translation_for_video_dub():
    payload = _base_payload()
    payload["module"] = "youtube_dub"
    payload["source"] = {"mode": "youtube", "url": "https://example.com"}
    payload["output"]["mode"] = "video_dub"
    payload["translation"]["enabled"] = False
    with pytest.raises(RequestValidationError):
        PipelineRequest.from_payload(payload)


def test_pipeline_request_parses_conference_metadata_extensions():
    payload = _base_payload()
    payload["module"] = "conference_mode"
    payload["output"]["mode"] = "conference"
    payload["conference_defaults"] = {
        "conference_title": "PyCon Prague",
        "conference_date": "2026-02-17",
    }
    payload["conference_meta"] = {
        "session01.wav": {
            "speaker": "Alice",
            "description": "Keynote",
            "date": "2026-02-17",
            "conference_title": "PyCon Prague",
        }
    }

    req = PipelineRequest.from_payload(payload)
    item = req.conference_meta["session01.wav"]
    assert req.conference_defaults.conference_title == "PyCon Prague"
    assert item["speaker"] == "Alice"
    assert item["lecture_description"] == "Keynote"
    assert item["lecture_date"] == "2026-02-17"
