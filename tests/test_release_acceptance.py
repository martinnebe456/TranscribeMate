import hashlib
import json
from pathlib import Path
import sys
from unittest.mock import Mock

import pytest

from tests.full_suite import run_extracted_package_acceptance as acceptance
from tests.full_suite import run_fixture_regression as regression
from transcribemate.v2.backend.models import PipelineRunResult


@pytest.mark.parametrize("stdio_server", [False, True], ids=["in-process", "json-lines"])
@pytest.mark.parametrize("skip_video", [False, True], ids=["with-video", "skip-video"])
def test_fixture_report_handles_optional_video(tmp_path, monkeypatch, stdio_server, skip_video):
    fixtures = tmp_path / "fixtures"
    fixtures.mkdir()
    (fixtures / "sample.mp3").write_bytes(b"audio")
    manifest = fixtures / "manifest.json"
    manifest.write_text(json.dumps({"fixtures": [{"id": "sample", "source": "sample.mp3"}]}), encoding="utf-8")
    artifacts = tmp_path / "artifacts"
    argv = [
        "run_fixture_regression.py",
        "--fixtures-dir", str(fixtures),
        "--manifest", str(manifest),
        "--artifact-dir", str(artifacts),
        "--app-data-dir", str(tmp_path / "app-data"),
        "--skip-summary", "--transcript-only",
    ]
    if stdio_server:
        argv.append("--stdio-server")
    if skip_video:
        argv.append("--skip-video-smoke")
    monkeypatch.setattr(regression.sys, "argv", argv)
    monkeypatch.setattr(regression.sys, "path", list(regression.sys.path))
    monkeypatch.setenv("TM_APP_DATA_DIR", str(tmp_path / "app-data"))
    client = Mock(spec=regression.JsonLineBackendClient)
    client_factory = Mock(return_value=client)
    monkeypatch.setattr(regression, "JsonLineBackendClient", client_factory)
    generate_video = Mock()
    monkeypatch.setattr(regression, "generate_smoke_video", generate_video)
    results = []

    def fake_pipeline(payload, *, log, progress_path, backend_client):
        assert backend_client is (client if stdio_server else None)
        output = Path(payload["output"]["out_dir"])
        if payload["output"]["mode"] == "txt_only":
            (output / "sample.txt").write_text("Completed transcript.", encoding="utf-8")
        else:
            (output / "sample-video-smoke.mp4").write_bytes(b"video")
            (output / "sample-video-smoke.srt").write_text("Subtitles", encoding="utf-8")
        result = PipelineRunResult(str(output), str(output), processed_items=1, total_items=1)
        results.append(result.to_dict())
        return result.to_dict() if stdio_server else result

    monkeypatch.setattr(regression, "run_pipeline", fake_pipeline)

    assert regression.main() == 0

    report = json.loads((artifacts / "fixture-regression-report.json").read_text(encoding="utf-8"))
    assert report["offline_run"] == results[0]
    assert report["offline_validation"]["fixture_count"] == 1
    assert report["backend_transport"] == ("json-lines" if stdio_server else "in-process")
    if skip_video:
        assert len(results) == 1
        assert report["video_run"] is None
        assert report["video_validation"] is None
        generate_video.assert_not_called()
    else:
        assert len(results) == 2
        assert report["video_run"] == results[1]
        assert Path(report["video_validation"]["video_output"]).is_file()
        assert Path(report["video_validation"]["srt_output"]).is_file()
        generate_video.assert_called_once()
    if stdio_server:
        client.close.assert_called_once()
    else:
        client_factory.assert_not_called()


@pytest.mark.parametrize("platform", ["macos-arm64", "linux-debian-x64", "linux-arch-x64"])
def test_package_acceptance_bootstraps_models_before_transcription(tmp_path, monkeypatch, platform):
    archive = tmp_path / "release.archive"
    archive.write_bytes(b"packaged app")
    checksum = tmp_path / "checksums.txt"
    checksum.write_text(f"{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}\n", encoding="utf-8")
    artifacts = tmp_path / "artifacts"
    app_data = artifacts / "app-data"
    extracted = artifacts / "extracted"
    if platform == "macos-arm64":
        backend = extracted / "TranscribeMate.app" / "Contents" / "app" / "backend"
        app_entry = extracted / "TranscribeMate.app" / "Contents" / "MacOS" / "TranscribeMate"
    else:
        backend = extracted / "TranscribeMate" / "lib" / "app" / "backend"
        app_entry = extracted / "TranscribeMate" / "bin" / "TranscribeMate"
    monkeypatch.setattr(sys, "argv", [
        "run_extracted_package_acceptance.py",
        "--archive", str(archive),
        "--checksum-file", str(checksum),
        "--platform", platform,
        "--artifact-dir", str(artifacts),
    ])

    def fake_extract(command, *, check):
        assert command[0] == ("ditto" if platform == "macos-arm64" else "tar")
        backend.mkdir(parents=True)
        (backend / "bootstrap_runtime.sh").touch()
        app_entry.parent.mkdir(parents=True)
        app_entry.touch()

    monkeypatch.setattr(acceptance.subprocess, "run", fake_extract)

    class BootstrapChecked(Exception):
        """Stop at the subprocess boundary without installing a real runtime."""

    def check_bootstrap(name, command, *, env, cwd, log_path):
        assert command[:2] == ["bash", str(backend / "bootstrap_runtime.sh")]
        assert "--with-models" in command
        assert "--download-ffmpeg" in command
        assert command[command.index("--backend-root") + 1] == str(backend)
        assert cwd == backend
        assert env["TM_APP_DATA_DIR"] == str(app_data)
        assert list(app_data.iterdir()) == []
        raise BootstrapChecked

    monkeypatch.setattr(acceptance, "run_stage", check_bootstrap)

    with pytest.raises(BootstrapChecked):
        acceptance.main()
