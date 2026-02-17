from pathlib import Path

from transcribemate.core.transcripts import (
    build_metadata,
    render_metadata_block,
    render_raw_transcript,
    render_summary_prompt,
    render_timestamped_transcript,
)
from transcribemate.core.types import TranscriptSegment
from transcribemate.core.types import TranscriptionResult


def test_render_metadata_block_multiline_topic():
    metadata = {
        "Title": "Demo",
        "Topic": "Line one\nLine two",
    }
    block = render_metadata_block(metadata)
    assert "- Topic:" in block
    assert "  Line one" in block
    assert "  Line two" in block


def test_render_summary_prompt_language_line_explicit():
    prompt = render_summary_prompt(
        style="detailed",
        media_path=Path("demo.mp3"),
        metadata={"Title": "Demo"},
        transcript_ts="[00:00] Hello",
        summary_lang="cs",
        detected_lang="en",
    )
    assert "Write the output in `cs`." in prompt


def test_render_summary_prompt_language_line_auto_detected():
    prompt = render_summary_prompt(
        style="detailed",
        media_path=Path("demo.mp3"),
        metadata={"Title": "Demo"},
        transcript_ts="[00:00] Hello",
        summary_lang="auto",
        detected_lang="en",
    )
    assert "detected language (`en`)" in prompt


def test_render_raw_transcript_includes_speaker_prefix():
    body = render_raw_transcript(
        [
            TranscriptSegment(start=0.0, end=0.8, text="Hello", speaker_id="SPEAKER_00", speaker_name="Alice"),
            TranscriptSegment(start=1.0, end=1.8, text="Hi", speaker_id="SPEAKER_01"),
        ]
    )
    assert "Alice: Hello" in body
    assert "SPEAKER_01: Hi" in body


def test_render_timestamped_transcript_includes_speaker_prefix():
    body = render_timestamped_transcript(
        [
            TranscriptSegment(start=0.0, end=1.0, text="First line", speaker_id="SPEAKER_00", speaker_name="Bob"),
        ],
        clean_text=False,
    )
    assert "[00:00] Bob: First line" in body


def test_render_transcript_hides_unmapped_labels_when_disabled():
    body = render_raw_transcript(
        [
            TranscriptSegment(start=0.0, end=0.5, text="No name", speaker_id="SPEAKER_03", speaker_name=""),
        ],
        include_unmapped_speakers=False,
    )
    assert body.strip() == "No name"


def test_build_metadata_includes_conference_fields():
    result = TranscriptionResult(
        srt_path=Path("x.srt"),
        raw_txt_path=Path("x.txt"),
        segments=[],
        detected_lang="en",
        duration=12.0,
        device="cpu",
        compute_type="int8",
    )
    metadata = build_metadata(
        media_path=Path("session01.wav"),
        result=result,
        model_name="large-v3",
        output_mode="conference",
        clean_text=True,
        export_md=True,
        split_minutes=0,
        part_idx=1,
        part_total=1,
        speaker="Alice",
        topic="Keynote",
        lecture_description="Opening talk",
        lecture_date="2026-02-17",
        conference_title="PyCon Prague",
        conference_date="2026-02-17",
    )

    assert metadata["Conference title"] == "PyCon Prague"
    assert metadata["Lecture description"] == "Opening talk"
