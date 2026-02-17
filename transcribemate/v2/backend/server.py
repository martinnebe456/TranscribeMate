"""JSON-lines stdio server for the V2 backend."""

from __future__ import annotations

import argparse
import sys
import traceback
from threading import Lock
from typing import TextIO

from .models import RequestValidationError
from .protocol import (
    EventMessage,
    ProtocolError,
    ResponseMessage,
    RpcError,
    dumps_line,
    parse_request_line,
)
from .service import BackendService


class JsonLineBackendServer:
    """Line-oriented stdio server used by JavaFX frontend."""

    def __init__(self, *, stdin: TextIO, stdout: TextIO):
        self._stdin = stdin
        self._stdout = stdout
        self._write_lock = Lock()
        self._running = True
        self._service = BackendService(emit_event=self._emit_event)

    def run(self) -> int:
        while self._running:
            line = self._stdin.readline()
            if not line:
                break

            try:
                request = parse_request_line(line)
            except ProtocolError as exc:
                self._write_response(
                    ResponseMessage(
                        request_id="",
                        ok=False,
                        error=RpcError(code="protocol_error", message=str(exc)),
                    )
                )
                continue

            if request.method.strip().lower() == "shutdown":
                self._write_response(
                    ResponseMessage(
                        request_id=request.request_id,
                        ok=True,
                        result={"accepted": True},
                    )
                )
                self._running = False
                continue

            try:
                result = self._service.handle_request(request.method, request.params)
                self._write_response(
                    ResponseMessage(
                        request_id=request.request_id,
                        ok=True,
                        result=result,
                    )
                )
            except RequestValidationError as exc:
                self._write_response(
                    ResponseMessage(
                        request_id=request.request_id,
                        ok=False,
                        error=RpcError(code=exc.code, message=str(exc), details=exc.details),
                    )
                )
            except Exception as exc:
                self._write_response(
                    ResponseMessage(
                        request_id=request.request_id,
                        ok=False,
                        error=RpcError(
                            code="internal_error",
                            message=str(exc),
                            details={"traceback": traceback.format_exc()},
                        ),
                    )
                )
        return 0

    def _emit_event(self, event: EventMessage):
        self._write_payload(event.to_dict())

    def _write_response(self, response: ResponseMessage):
        self._write_payload(response.to_dict())

    def _write_payload(self, payload: dict):
        line = dumps_line(payload)
        with self._write_lock:
            try:
                self._stdout.write(line)
                self._stdout.flush()
                return
            except UnicodeEncodeError:
                # Windows console code pages (e.g. cp1250) cannot represent some protocol
                # characters. Force UTF-8 bytes when text-mode write fails.
                pass

            buffer = getattr(self._stdout, "buffer", None)
            if buffer is not None:
                buffer.write(line.encode("utf-8", errors="replace"))
                buffer.flush()
            else:
                self._stdout.write(line.encode("utf-8", errors="replace").decode("utf-8"))
                self._stdout.flush()


def _configure_stdio_utf8():
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            # Best effort on older/wrapped streams.
            pass


def parse_args(argv: list[str] | None = None):
    parser = argparse.ArgumentParser(description="TranscribeMate backend server")
    parser.add_argument(
        "--stdio",
        action="store_true",
        help="Use stdin/stdout JSON-line transport (default).",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    _configure_stdio_utf8()
    _ = parse_args(argv)
    server = JsonLineBackendServer(stdin=sys.stdin, stdout=sys.stdout)
    return server.run()


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
