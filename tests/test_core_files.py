from transcribemate.core.files import sanitize_filename, split_segments
from transcribemate.core.files import timestamped_base_name, unique_path


class _Seg:
    def __init__(self, start: float):
        self.start = start


def test_sanitize_filename_replaces_invalid_chars():
    name = 'inv:alid<>name*'
    cleaned = sanitize_filename(name)
    assert ":" not in cleaned
    assert "<" not in cleaned
    assert ">" not in cleaned
    assert "*" not in cleaned


def test_split_segments_respects_part_seconds():
    segments = [_Seg(0.0), _Seg(10.0), _Seg(25.0)]
    parts = split_segments(segments, part_seconds=20)
    assert len(parts) == 2
    assert len(parts[0]) == 2
    assert len(parts[1]) == 1


def test_unique_path_appends_suffix_when_exists(tmp_path):
    existing = tmp_path / "demo.txt"
    existing.write_text("x", encoding="utf-8")
    candidate = unique_path(tmp_path, "demo.txt")
    assert candidate.name == "demo_02.txt"


def test_timestamped_base_name_includes_prefix_and_stem(tmp_path):
    media = tmp_path / "File Name.mp3"
    media.write_text("x", encoding="utf-8")
    base = timestamped_base_name(media, prefix="My Prefix")
    assert base.startswith("My_Prefix_")
    assert "File_Name" in base
