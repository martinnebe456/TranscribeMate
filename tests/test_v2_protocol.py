import pytest

from transcribemate.v2.backend.protocol import ProtocolError, ResponseMessage, RpcError, parse_request_line


def test_parse_request_line_ok():
    msg = parse_request_line('{"type":"request","id":"1","method":"ping","params":{}}')
    assert msg.request_id == "1"
    assert msg.method == "ping"
    assert msg.params == {}


def test_parse_request_line_rejects_non_request():
    with pytest.raises(ProtocolError):
        parse_request_line('{"type":"event","event":"x"}')


def test_parse_request_line_requires_id_and_method():
    with pytest.raises(ProtocolError):
        parse_request_line('{"type":"request","method":"ping"}')
    with pytest.raises(ProtocolError):
        parse_request_line('{"type":"request","id":"1"}')


def test_response_message_error_payload():
    resp = ResponseMessage(
        request_id="abc",
        ok=False,
        error=RpcError(code="invalid_request", message="Bad request", details={"x": 1}),
    ).to_dict()

    assert resp["type"] == "response"
    assert resp["id"] == "abc"
    assert resp["ok"] is False
    assert resp["error"]["code"] == "invalid_request"
    assert resp["error"]["details"] == {"x": 1}
