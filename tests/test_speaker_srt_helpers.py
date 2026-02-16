from pathlib import Path

from transcribemate.core.types import TranscriptSegment
from transcribemate.pipeline.diarize import build_speakerized_srt, rewrite_srt_speaker_prefixes


def test_build_speakerized_srt_includes_labels(tmp_path):
    out_path = tmp_path / "speaker.srt"
    segments = [
        TranscriptSegment(start=0.0, end=1.0, text="Hello", speaker_id="SPEAKER_00", speaker_name="Alice"),
        TranscriptSegment(start=1.1, end=2.0, text="World", speaker_id="SPEAKER_01"),
    ]

    build_speakerized_srt(segments, out_path, include_unmapped_speakers=True)
    body = out_path.read_text(encoding="utf-8")

    assert "Alice: Hello" in body
    assert "SPEAKER_01: World" in body


def test_rewrite_srt_speaker_prefixes_respects_unmapped_toggle(tmp_path):
    inp = tmp_path / "in.srt"
    out = tmp_path / "out.srt"
    inp.write_text(
        "1\n00:00:00,000 --> 00:00:01,000\nfoo\n\n"
        "2\n00:00:01,000 --> 00:00:02,000\nbar\n",
        encoding="utf-8",
    )
    segments = [
        TranscriptSegment(start=0.0, end=1.0, text="foo", speaker_id="SPEAKER_00", speaker_name="Martin"),
        TranscriptSegment(start=1.0, end=2.0, text="bar", speaker_id="SPEAKER_01", speaker_name=""),
    ]

    rewrite_srt_speaker_prefixes(inp, out, segments, include_unmapped_speakers=False)
    body = out.read_text(encoding="utf-8")

    assert "Martin: foo" in body
    assert "SPEAKER_01" not in body
    assert "\nbar\n" in body
