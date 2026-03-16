from transcribemate.pipeline.download import quality_to_format
from transcribemate.pipeline.subtitles import hex_to_ass_color
from transcribemate.pipeline.transcribe import format_timestamp
from transcribemate.pipeline.translate import _split_speaker_prefix


def test_quality_to_format_best():
    fmt = quality_to_format("best")
    assert "bestvideo" in fmt


def test_quality_to_format_height():
    fmt = quality_to_format("720p")
    assert "height<=720" in fmt


def test_quality_to_format_best_without_merge_prefers_single_file():
    fmt = quality_to_format("best", allow_merge=False)

    assert "bestvideo" not in fmt
    assert "[acodec!=none]" in fmt


def test_quality_to_format_audio_only_prefers_audio_streams():
    fmt = quality_to_format("best", allow_merge=False, audio_only=True)

    assert "bestaudio" in fmt
    assert "bestvideo" not in fmt


def test_hex_to_ass_color_rgb_order():
    assert hex_to_ass_color("#112233") == "&H332211&"


def test_format_timestamp_zero():
    assert format_timestamp(0.0) == "00:00:00,000"


def test_split_speaker_prefix_parses_prefix_and_body():
    prefix, body = _split_speaker_prefix("Alice: Hello there")
    assert prefix == "Alice: "
    assert body == "Hello there"
