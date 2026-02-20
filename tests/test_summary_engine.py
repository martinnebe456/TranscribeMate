from pathlib import Path

import pytest

from transcribemate.pipeline.summarize import (
    split_transcript_text,
    summarize_transcript_file,
)


def _mock_markdown_summary() -> str:
    return (
        "## TL;DR\n\n"
        "Short summary.\n\n"
        "## Key Points\n\n"
        "- Point A\n\n"
        "## Action Items\n\n"
        "- Action A\n\n"
        "## Open Questions\n\n"
        "- Question A\n\n"
        "## Risks\n\n"
        "- Risk A\n"
    )


def test_split_transcript_text_creates_multiple_chunks():
    text = "\n".join(f"Line {idx} " + ("x" * 120) for idx in range(300))
    chunks = split_transcript_text(text, max_chars=3000)
    assert len(chunks) > 1
    assert all(chunk.strip() for chunk in chunks)


def test_summarize_transcript_file_single_pass_prompt_structure(tmp_path):
    transcript = tmp_path / "meeting.txt"
    transcript.write_text("Hello world. This is a short transcript.", encoding="utf-8")
    prompts: list[str] = []

    def fake_generate(prompt: str, max_tokens: int) -> str:
        prompts.append(prompt)
        assert max_tokens >= 700
        return _mock_markdown_summary()

    output = summarize_transcript_file(
        transcript_path=transcript,
        output_dir=tmp_path / "out",
        summary_lang="cs",
        summary_ai_tier="low",
        generate_text_fn=fake_generate,
    )

    assert output.is_file()
    rendered = output.read_text(encoding="utf-8")
    assert "## TL;DR" in rendered
    assert "## Key Points" in rendered
    assert "## Action Items" in rendered
    assert "## Open Questions" in rendered
    assert "## Risks" in rendered
    assert len(prompts) == 2
    assert "Output language requirement (MANDATORY)" in prompts[0]
    assert "Target language: Czech (code `cs`)." in prompts[1]


def test_summarize_transcript_file_hierarchical_synthesis(tmp_path):
    transcript = tmp_path / "long_meeting.txt"
    transcript.write_text("\n".join(f"Segment {idx} " + ("content " * 40) for idx in range(800)), encoding="utf-8")
    prompts: list[str] = []

    def fake_generate(prompt: str, max_tokens: int) -> str:
        prompts.append(prompt)
        return _mock_markdown_summary()

    output = summarize_transcript_file(
        transcript_path=transcript,
        output_dir=tmp_path / "out",
        summary_lang="auto",
        summary_ai_tier="medium",
        generate_text_fn=fake_generate,
    )

    assert output.is_file()
    assert len(prompts) >= 2
    assert any("Chunk:" in prompt for prompt in prompts[:-1])
    assert "Chunk summaries:" in prompts[-1]


def test_summarize_transcript_file_rejects_invalid_tier(tmp_path):
    transcript = tmp_path / "meeting.txt"
    transcript.write_text("Hello world.", encoding="utf-8")

    with pytest.raises(ValueError):
        summarize_transcript_file(
            transcript_path=transcript,
            output_dir=tmp_path / "out",
            summary_lang="auto",
            summary_ai_tier="ultra",
            generate_text_fn=lambda *_: _mock_markdown_summary(),
        )
