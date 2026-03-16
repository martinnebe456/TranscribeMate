from transcribemate.core import paths as core_paths
from transcribemate.v2.backend import models as backend_models


def test_user_data_dir_uses_application_support_on_macos(monkeypatch, tmp_path):
    monkeypatch.setattr(core_paths.sys, "platform", "darwin")
    monkeypatch.delenv("XDG_DATA_HOME", raising=False)
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    monkeypatch.delenv("TM_APP_DATA_DIR", raising=False)
    monkeypatch.setattr(core_paths.Path, "home", lambda: tmp_path)

    result = core_paths.user_data_dir()

    assert result == tmp_path / "Library" / "Application Support" / "TranscribeMate"


def test_user_data_dir_honors_tm_app_data_dir_override(monkeypatch, tmp_path):
    override = tmp_path / "custom-app-data"
    monkeypatch.setenv("TM_APP_DATA_DIR", str(override))

    result = core_paths.user_data_dir()

    assert result == override


def test_user_data_dir_uses_xdg_data_home_on_linux(monkeypatch, tmp_path):
    monkeypatch.setattr(core_paths.sys, "platform", "linux")
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "xdg-data"))
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    monkeypatch.delenv("TM_APP_DATA_DIR", raising=False)
    monkeypatch.setattr(core_paths.Path, "home", lambda: tmp_path / "home")

    result = core_paths.user_data_dir()

    assert result == tmp_path / "xdg-data" / "TranscribeMate"


def test_transcription_spec_defaults_to_gpu_on_windows(monkeypatch):
    monkeypatch.setattr(backend_models.sys, "platform", "win32")

    spec = backend_models.TranscriptionSpec.from_payload({})

    assert spec.prefer_gpu is True


def test_transcription_spec_defaults_to_cpu_on_macos(monkeypatch):
    monkeypatch.setattr(backend_models.sys, "platform", "darwin")

    spec = backend_models.TranscriptionSpec.from_payload({})

    assert spec.prefer_gpu is False


def test_transcription_spec_forces_cpu_on_linux_even_when_gpu_requested(monkeypatch):
    monkeypatch.setattr(backend_models.sys, "platform", "linux")

    spec = backend_models.TranscriptionSpec.from_payload({"prefer_gpu": True})

    assert spec.prefer_gpu is False


def test_ffmpeg_path_finds_common_macos_binary_locations(monkeypatch, tmp_path):
    ffmpeg_file = tmp_path / "ffmpeg"
    ffmpeg_file.write_text("", encoding="utf-8")
    ffmpeg_file.chmod(0o755)

    monkeypatch.setattr(core_paths, "bundled_bin", lambda name: None)
    monkeypatch.setattr(core_paths, "_common_binary_candidates", lambda name: [ffmpeg_file] if name == "ffmpeg" else [])
    monkeypatch.setattr(core_paths.shutil, "which", lambda name: None)

    assert core_paths.ffmpeg_path() == str(ffmpeg_file)
