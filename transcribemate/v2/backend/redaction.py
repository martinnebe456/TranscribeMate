"""Redaction helpers for values that cross backend persistence or IPC boundaries."""

from __future__ import annotations

import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

_URL_RE = re.compile(r"https?://[^\s<>\"'`]+", re.IGNORECASE)
_SENSITIVE_KEY_SUFFIXES = (
    "accesstoken",
    "refreshtoken",
    "token",
    "clientsecret",
    "secret",
    "password",
    "passwd",
    "apikey",
    "accesskey",
    "privatekey",
    "authorization",
    "auth",
    "credential",
    "signature",
    "sig",
    "jwt",
    "sessionid",
    "session",
)
_TRACEBACK_KEYS = {"traceback", "stacktrace", "stack_trace"}
_TRAILING_URL_PUNCTUATION = ".,;:!?)]}"
_REDACTED = "[REDACTED]"


def _is_sensitive_key(key: str) -> bool:
    normalized = re.sub(r"[^a-z0-9]", "", str(key).lower())
    return normalized == "key" or normalized.endswith(_SENSITIVE_KEY_SUFFIXES)


def redact_url(url: str) -> str:
    """Remove URL credentials and sensitive query values while retaining diagnostics."""
    try:
        parts = urlsplit(str(url))
        netloc = parts.netloc
        if "@" in netloc:
            netloc = f"{_REDACTED}@{netloc.rsplit('@', 1)[1]}"
        query = [
            (key, _REDACTED if _is_sensitive_key(key) else value)
            for key, value in parse_qsl(parts.query, keep_blank_values=True)
        ]
        return urlunsplit((parts.scheme, netloc, parts.path, urlencode(query, safe="[]"), parts.fragment))
    except (TypeError, ValueError):
        # Malformed URLs still get best-effort credential/query scrubbing.
        text = re.sub(r"(https?://)[^/@\s]+@", rf"\1{_REDACTED}@", str(url), flags=re.IGNORECASE)
        return re.sub(
            r"([?&][^=&#]*(?:token|secret|password|passwd|api[_-]?key|access[_-]?key|auth|credential|signature|sig|jwt|session)[^=&#]*=)[^&#\s]*",
            rf"\1{_REDACTED}",
            text,
            flags=re.IGNORECASE,
        )


def redact_text(value: str) -> str:
    """Redact credentials in URLs embedded in exception and log text."""

    def replace(match: re.Match[str]) -> str:
        candidate = match.group(0)
        suffix = ""
        while candidate and candidate[-1] in _TRAILING_URL_PUNCTUATION:
            suffix = candidate[-1] + suffix
            candidate = candidate[:-1]
        return redact_url(candidate) + suffix

    return _URL_RE.sub(replace, str(value))


def redact_data(value: Any) -> Any:
    """Recursively sanitize structured values before IPC or persistence."""
    if isinstance(value, dict):
        return {
            str(key): _REDACTED if _is_sensitive_key(str(key)) else redact_data(item)
            for key, item in value.items()
            if str(key).lower() not in _TRACEBACK_KEYS
        }
    if isinstance(value, list):
        return [redact_data(item) for item in value]
    if isinstance(value, tuple):
        return [redact_data(item) for item in value]
    if isinstance(value, str):
        return redact_text(value)
    return value


def write_runtime_diagnostic(diagnostic: str) -> None:
    """Append a sanitized traceback to the per-user runtime log on a best-effort basis."""
    from ...core.paths import log_path

    try:
        path = Path(log_path())
        path.parent.mkdir(parents=True, exist_ok=True)
        is_new = not path.exists()
        with path.open("a", encoding="utf-8") as handle:
            handle.write(f"[{datetime.now(timezone.utc).isoformat(timespec='seconds')}] ")
            handle.write(redact_text(diagnostic).rstrip())
            handle.write("\n")
        if is_new and os.name != "nt":
            try:
                path.chmod(0o600)
            except OSError:
                pass
    except Exception:
        # A diagnostic failure must not mask the original backend error.
        return
