from transcribemate.pipeline.download import quality_to_format
from transcribemate.pipeline.subtitles import hex_to_ass_color
from transcribemate.pipeline.transcribe import format_timestamp


def test_quality_to_format_best():
    fmt = quality_to_format("best")
    assert "bestvideo" in fmt


def test_quality_to_format_height():
    fmt = quality_to_format("720p")
    assert "height<=720" in fmt


def test_hex_to_ass_color_rgb_order():
    assert hex_to_ass_color("#112233") == "&H332211&"


def test_format_timestamp_zero():
    assert format_timestamp(0.0) == "00:00:00,000"
