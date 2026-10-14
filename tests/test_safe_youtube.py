import socket
import sys
import types
from urllib.parse import urlsplit

import pytest

from transcribemate.pipeline import download


def _install_mock_downloader(monkeypatch, on_download=None):
    calls = {"created": 0, "urls": None, "options": None}

    class FakeYoutubeDL:
        def __init__(self, options):
            calls["created"] += 1
            calls["options"] = options
            self.params = options

        def __enter__(self):
            return self

        def __exit__(self, *_exc):
            return None

        def download(self, urls):
            calls["urls"] = urls
            if on_download:
                on_download(self, urls)

    yt_dlp = types.ModuleType("yt_dlp")
    yt_dlp.YoutubeDL = FakeYoutubeDL
    utils = types.ModuleType("yt_dlp.utils")
    utils.DownloadError = type("DownloadError", (Exception,), {})
    yt_dlp.utils = utils
    monkeypatch.setitem(sys.modules, "yt_dlp", yt_dlp)
    monkeypatch.setitem(sys.modules, "yt_dlp.utils", utils)
    monkeypatch.setattr(download, "ffmpeg_path", lambda: None)
    return calls


def _dns_answers(monkeypatch, answers):
    original_getaddrinfo = socket.getaddrinfo

    def fake_getaddrinfo(host, port, *args, **kwargs):
        if host in answers:
            address = answers[host]
            family = socket.AF_INET6 if ":" in address else socket.AF_INET
            sockaddr = (address, port, 0, 0) if family == socket.AF_INET6 else (address, port)
            return [(family, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", sockaddr)]
        return original_getaddrinfo(host, port, *args, **kwargs)

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)


def _run_download(url, tmp_path, *, monkeypatch):
    return download.download_single_or_playlist(
        url=url,
        workdir=tmp_path,
        is_playlist=False,
        quality="best",
        output_mode="txt_only",
        log=lambda _message: None,
        set_step_progress=lambda _value: None,
        stop_flag=None,
    )


def test_accepts_youtube_https_and_uses_guarded_proxy(monkeypatch, tmp_path):
    _dns_answers(monkeypatch, {"www.youtube.com": "8.8.8.8"})
    calls = _install_mock_downloader(monkeypatch)

    _run_download("https://www.youtube.com/watch?v=example", tmp_path, monkeypatch=monkeypatch)

    assert calls["created"] == 1
    assert calls["urls"] == ["https://www.youtube.com/watch?v=example"]
    assert urlsplit(calls["options"]["proxy"]).hostname == "127.0.0.1"


@pytest.mark.parametrize(
    "url",
    [
        "http://www.youtube.com/watch?v=example",
        "file://www.youtube.com/watch?v=example",
        "https://user:pass@www.youtube.com/watch?v=example",
        "https://127.0.0.1/watch?v=example",
    ],
)
def test_rejects_non_https_credentials_and_ip_literals_before_downloader(
    monkeypatch, tmp_path, url
):
    calls = _install_mock_downloader(monkeypatch)

    with pytest.raises(ValueError):
        _run_download(url, tmp_path, monkeypatch=monkeypatch)

    assert calls["created"] == 0


def test_rejects_youtube_hostname_resolving_to_private_address_before_downloader(
    monkeypatch, tmp_path
):
    _dns_answers(monkeypatch, {"www.youtube.com": "192.168.1.12"})
    calls = _install_mock_downloader(monkeypatch)

    with pytest.raises(ValueError, match="non-public"):
        _run_download("https://www.youtube.com/watch?v=example", tmp_path, monkeypatch=monkeypatch)

    assert calls["created"] == 0


def test_redirect_connect_to_private_resolution_is_blocked(monkeypatch, tmp_path):
    _dns_answers(
        monkeypatch,
        {"www.youtube.com": "8.8.8.8", "private.googlevideo.com": "10.0.0.7"},
    )

    def mock_redirecting_download(ydl, _urls):
        proxy = urlsplit(ydl.params["proxy"])
        with socket.create_connection((proxy.hostname, proxy.port), timeout=2) as connection:
            connection.sendall(
                b"CONNECT private.googlevideo.com:443 HTTP/1.1\r\n"
                b"Host: private.googlevideo.com:443\r\n\r\n"
            )
            response = connection.recv(1024)
        assert response.startswith(b"HTTP/1.1 403")

    calls = _install_mock_downloader(monkeypatch, on_download=mock_redirecting_download)
    _run_download("https://www.youtube.com/watch?v=example", tmp_path, monkeypatch=monkeypatch)

    assert calls["created"] == 1
