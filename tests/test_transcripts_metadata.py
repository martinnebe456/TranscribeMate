from pathlib import Path

from transcribemate.core.transcripts import render_metadata_block, render_summary_prompt


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
