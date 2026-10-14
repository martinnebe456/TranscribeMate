"""URL and connection-boundary checks for YouTube downloads."""

from __future__ import annotations

import ipaddress
import re
import select
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


_ALLOWED_YOUTUBE_HOSTS = frozenset(
    {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}
)
_DNS_NAME = re.compile(
    r"(?=.{1,253}\Z)"
    r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)"
    r"(?:\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*\Z"
)


def _public_addresses(host: str, port: int) -> list[tuple[int, tuple]]:
    """Resolve a hostname and return only globally routable socket addresses."""
    try:
        records = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise ValueError("Could not resolve the YouTube download destination.") from exc
    if not records:
        raise ValueError("The YouTube download destination has no addresses.")

    safe_records = []
    for family, _socktype, _proto, _canonname, sockaddr in records:
        if "%" in sockaddr[0]:
            raise ValueError("Scoped IP destinations are not allowed.")
        try:
            address = ipaddress.ip_address(sockaddr[0])
        except ValueError as exc:
            raise ValueError("The YouTube download destination resolved to an invalid address.") from exc
        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
            address = address.ipv4_mapped
        if not address.is_global:
            raise ValueError("The YouTube download destination resolved to a non-public address.")
        safe_records.append((family, sockaddr))
    return safe_records


def validate_youtube_url(url: str) -> str:
    """Validate the user-supplied YouTube URL and its initial DNS answers."""
    if not isinstance(url, str) or not url or any(ord(char) < 0x20 for char in url):
        raise ValueError("Enter a valid YouTube HTTPS URL.")
    try:
        parsed = urlsplit(url)
        host = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise ValueError("Enter a valid YouTube HTTPS URL.") from exc

    if (
        parsed.scheme.lower() != "https"
        or not parsed.netloc
        or not host
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
    ):
        raise ValueError("Only direct YouTube HTTPS URLs are supported.")

    host = host.lower()
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        raise ValueError("YouTube URLs must use a hostname, not an IP address.")

    if host.endswith(".") or not _DNS_NAME.fullmatch(host) or host not in _ALLOWED_YOUTUBE_HOSTS:
        raise ValueError("Only supported YouTube hostnames are allowed.")

    _public_addresses(host, 443)
    return url


class SafeHttpsProxy:
    """A local CONNECT proxy that validates and pins every HTTPS destination.

    yt-dlp sends HTTPS requests, redirects, and media CDN requests through this
    proxy. Each CONNECT is resolved once, all answers must be public, and the
    socket connects to one of those checked IP addresses directly.
    """

    def __init__(self) -> None:
        class ProxyHandler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, _format: str, *_args) -> None:
                return

            def do_CONNECT(self) -> None:
                host, separator, raw_port = self.path.rpartition(":")
                if not separator or not raw_port.isdigit() or int(raw_port) != 443:
                    self.send_error(403, "Only HTTPS port 443 is allowed")
                    return
                host = host.lower()
                try:
                    ipaddress.ip_address(host)
                except ValueError:
                    pass
                else:
                    self.send_error(403, "IP literal destinations are not allowed")
                    return
                if host.endswith(".") or not _DNS_NAME.fullmatch(host):
                    self.send_error(403, "Invalid HTTPS destination")
                    return
                try:
                    addresses = _public_addresses(host, 443)
                except ValueError:
                    self.send_error(403, "HTTPS destination is not public")
                    return

                upstream = None
                for family, sockaddr in addresses:
                    candidate = socket.socket(family, socket.SOCK_STREAM)
                    candidate.settimeout(15)
                    try:
                        candidate.connect(sockaddr)
                        upstream = candidate
                        break
                    except OSError:
                        candidate.close()
                if upstream is None:
                    self.send_error(502, "Could not connect to HTTPS destination")
                    return

                self.send_response(200, "Connection Established")
                self.end_headers()
                self.close_connection = True
                upstream.settimeout(None)
                try:
                    sockets = (self.connection, upstream)
                    while True:
                        readable, _, exceptional = select.select(sockets, (), sockets, 60)
                        if exceptional:
                            break
                        for source in readable:
                            data = source.recv(65536)
                            if not data:
                                return
                            destination = upstream if source is self.connection else self.connection
                            destination.sendall(data)
                except OSError:
                    pass
                finally:
                    upstream.close()

            def do_GET(self) -> None:
                self.send_error(403, "Plain HTTP destinations are not allowed")

            do_POST = do_GET
            do_HEAD = do_GET

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), ProxyHandler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        host, port = self._server.server_address
        return f"http://{host}:{port}"

    def __enter__(self) -> "SafeHttpsProxy":
        self._thread.start()
        return self

    def __exit__(self, *_exc) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=2)
