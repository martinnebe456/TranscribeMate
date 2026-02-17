"""JSON-lines IPC protocol for the V2 backend."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping


class ProtocolError(ValueError):
    """Raised when a protocol message is malformed."""


@dataclass(slots=True)
class RpcError:
    """Structured error payload returned to clients."""

    code: str
    message: str
    details: Any = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "code": str(self.code),
            "message": str(self.message),
        }
        if self.details is not None:
            payload["details"] = self.details
        return payload


@dataclass(slots=True)
class RequestMessage:
    """Parsed request sent by the frontend."""

    request_id: str
    method: str
    params: dict[str, Any]


@dataclass(slots=True)
class ResponseMessage:
    """Backend response envelope."""

    request_id: str
    ok: bool
    result: dict[str, Any] | None = None
    error: RpcError | None = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "type": "response",
            "id": self.request_id,
            "ok": bool(self.ok),
            "ts": now_utc_iso(),
        }
        if self.ok:
            payload["result"] = self.result or {}
        else:
            payload["error"] = self.error.to_dict() if self.error else RpcError(
                code="internal_error",
                message="Unknown backend error.",
            ).to_dict()
        return payload


@dataclass(slots=True)
class EventMessage:
    """Asynchronous event envelope emitted by backend jobs."""

    event: str
    payload: dict[str, Any]
    job_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "type": "event",
            "event": str(self.event),
            "payload": self.payload,
            "ts": now_utc_iso(),
        }
        if self.job_id:
            data["job_id"] = self.job_id
        return data


def now_utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def dumps_line(payload: Mapping[str, Any]) -> str:
    return json.dumps(dict(payload), ensure_ascii=False, separators=(",", ":")) + "\n"


def parse_request_line(raw_line: str) -> RequestMessage:
    line = (raw_line or "").strip()
    if not line:
        raise ProtocolError("Empty message line.")

    try:
        data = json.loads(line)
    except json.JSONDecodeError as exc:
        raise ProtocolError(f"Invalid JSON: {exc}") from exc

    if not isinstance(data, dict):
        raise ProtocolError("Protocol message must be a JSON object.")

    msg_type = str(data.get("type") or "request").strip().lower()
    if msg_type != "request":
        raise ProtocolError("Only request messages are accepted on stdin.")

    request_id = str(data.get("id") or "").strip()
    method = str(data.get("method") or "").strip()
    params = data.get("params")

    if not request_id:
        raise ProtocolError("Request field 'id' is required.")
    if not method:
        raise ProtocolError("Request field 'method' is required.")

    if params is None:
        params_dict: dict[str, Any] = {}
    elif isinstance(params, dict):
        params_dict = dict(params)
    else:
        raise ProtocolError("Request field 'params' must be an object.")

    return RequestMessage(request_id=request_id, method=method, params=params_dict)
