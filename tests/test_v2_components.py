from transcribemate.v2.backend.components import get_module_component, list_module_components, supported_module_ids
from transcribemate.v2.backend.models import PipelineRequest
from transcribemate.v2.backend.service import BackendService


def _base_payload(module: str = "offline_transcribe") -> dict:
    return {
        "module": module,
        "source": {"mode": "local", "path": "tests", "url": "https://example.com/watch?v=abc"},
        "output": {"mode": "txt_only", "out_dir": "."},
        "transcription": {
            "model": "large-v3",
            "auto_model": False,
            "prefer_gpu": False,
            "source_lang": "auto",
            "batch_size": 16,
        },
        "translation": {"enabled": False, "target_lang": "en→cs"},
        "subtitles": {"mode": "soft"},
        "text": {},
        "diarization": {"enabled": False, "backend": "local_cluster_accurate"},
        "project": {
            "project_id": "project-default",
            "name": "Project Default",
            "root_dir": ".",
        },
    }


def test_component_registry_lists_all_supported_modules():
    modules = supported_module_ids()
    assert modules == [
        "offline_transcribe",
        "youtube_transcribe",
        "speaker_transcribe",
        "conference_mode",
        "youtube_subtitles",
        "youtube_dub",
    ]
    assert len(list_module_components()) == len(modules)


def test_component_request_configuration_forces_module_contracts():
    component = get_module_component("youtube_dub")
    payload = _base_payload(module="youtube_dub")
    payload["source"]["mode"] = "youtube"
    payload["output"]["mode"] = "video_dub"
    payload["translation"]["enabled"] = True
    req = PipelineRequest.from_payload(payload)
    configured = component.configure_request(req)

    assert configured.source.mode == "youtube"
    assert configured.output.mode == "video_dub"
    assert configured.translation.enabled is True
    assert configured.diarization.enabled is False


def test_speaker_component_keeps_requested_diarization_backend():
    component = get_module_component("speaker_transcribe")
    payload = _base_payload(module="speaker_transcribe")
    payload["source"]["mode"] = "local"
    payload["output"]["mode"] = "conference"
    payload["diarization"] = {"enabled": True, "backend": "local_cluster_fast"}

    req = PipelineRequest.from_payload(payload)
    configured = component.configure_request(req)

    assert configured.diarization.enabled is True
    assert configured.diarization.backend == "local_cluster_fast"


def test_service_capabilities_include_component_metadata():
    svc = BackendService(emit_event=lambda _: None)
    caps = svc.handle_request("get_capabilities", {})

    assert "module_components" in caps
    assert isinstance(caps["module_components"], list)
    assert caps["module_components"]
    first = caps["module_components"][0]
    assert "module_id" in first
    assert "runtime_features" in first
    assert "ui_schema" in first
    assert isinstance(first["ui_schema"], dict)
    assert isinstance(first["ui_schema"].get("show_tabs", []), list)
