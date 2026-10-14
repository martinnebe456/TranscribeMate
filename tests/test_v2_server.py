"""JSON-line server safety regression tests."""

import json
from io import StringIO

from transcribemate.v2.backend.server import JsonLineBackendServer


def test_internal_error_response_is_stable_and_traceback_stays_in_redacted_runtime_log(
    tmp_path, monkeypatch
):
    canary = "TRA19_RPC_FAKE_TOKEN"
    app_data = tmp_path / "app-data"
    monkeypatch.setenv("TM_APP_DATA_DIR", str(app_data))

    stdin = StringIO('{"type":"request","id":"req-1","method":"explode","params":{}}\n')
    stdout = StringIO()
    server = JsonLineBackendServer(stdin=stdin, stdout=stdout)

    def fail(_method, _params):
        raise RuntimeError(f"request failed for https://user:pass@example.test/?token={canary}")

    server._service.handle_request = fail
    assert server.run() == 0

    response = json.loads(stdout.getvalue())
    assert response["ok"] is False
    assert response["error"]["code"] == "internal_error"
    assert response["error"]["message"] == "An internal backend error occurred."
    assert "traceback" not in response["error"]
    assert canary not in stdout.getvalue()

    diagnostics = (app_data / "runtime.log").read_text(encoding="utf-8")
    assert "RuntimeError" in diagnostics
    assert "example.test" in diagnostics
    assert canary not in diagnostics
    assert "user:pass" not in diagnostics
