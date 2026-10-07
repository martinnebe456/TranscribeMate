"""JSON-lines server regression tests for request-boundary failures."""

import json
from io import StringIO

from transcribemate.v2.backend.server import JsonLineBackendServer
from transcribemate.v2.backend.service import BackendService


def _pipeline_payload(tmp_path):
    return {
        "module": "offline_transcribe",
        "source": {"mode": "local", "path": str(tmp_path)},
        "output": {"mode": "txt_only", "out_dir": str(tmp_path)},
        "transcription": {},
        "translation": {"enabled": False},
        "subtitles": {},
        "text": {},
        "diarization": {},
        "project": {"project_id": "server-test", "root_dir": str(tmp_path)},
    }


def test_invalid_pipeline_request_is_structured_and_server_recovers(monkeypatch, tmp_path):
    # Keep the accepted job queued so this protocol test does not start media processing.
    monkeypatch.setattr(BackendService, "_run_job_thread", lambda self, record: None)
    monkeypatch.setattr(BackendService, "_persist_project_job_state", lambda *args, **kwargs: None)

    malformed = _pipeline_payload(tmp_path)
    malformed["source"] = [["mode", "local"], ["path", str(tmp_path)]]
    valid = _pipeline_payload(tmp_path)
    requests = [
        {"type": "request", "id": "bad", "method": "run_pipeline", "params": malformed},
        {"type": "request", "id": "good", "method": "run_pipeline", "params": valid},
    ]
    stdin = StringIO("".join(json.dumps(request) + "\n" for request in requests))
    stdout = StringIO()
    server = JsonLineBackendServer(stdin=stdin, stdout=stdout)

    assert server.run() == 0

    responses = [json.loads(line) for line in stdout.getvalue().splitlines()]
    assert len(responses) == 2
    assert responses[0]["id"] == "bad"
    assert responses[0]["ok"] is False
    assert responses[0]["error"]["code"] == "invalid_request"
    assert responses[0]["error"]["details"] == {"field": "source", "expected": "object"}
    assert "traceback" not in responses[0]["error"]
    assert responses[1]["id"] == "good"
    assert responses[1]["ok"] is True
    assert responses[1]["result"]["status"] == "queued"
    assert len(server._service._jobs) == 1
