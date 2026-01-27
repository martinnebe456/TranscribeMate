from pathlib import Path

from transcribemate.runtime.runtime import _torch_version_from_metadata, _torch_version_from_package


def test_torch_version_from_metadata(tmp_path: Path):
    (tmp_path / "torch-2.5.1.dist-info").mkdir()
    (tmp_path / "torch-2.4.0.dist-info").mkdir()
    assert _torch_version_from_metadata(tmp_path) == "2.5.1"


def test_torch_version_from_package(tmp_path: Path):
    version_file = tmp_path / "torch" / "version.py"
    version_file.parent.mkdir(parents=True, exist_ok=True)
    version_file.write_text("__version__ = '2.5.1'\n", encoding="utf-8")
    assert _torch_version_from_package(tmp_path) == "2.5.1"
