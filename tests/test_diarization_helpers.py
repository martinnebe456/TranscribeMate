from pathlib import Path

from transcribemate.core.types import SpeakerTurn, TranscriptSegment, TranscriptionResult
from transcribemate.pipeline.diarize import (
    apply_speaker_mapping,
    assign_speakers_to_segments,
    build_speaker_sidecar,
    load_speaker_sidecar,
    save_speaker_sidecar,
)


def test_assign_speakers_to_segments_uses_overlap():
    segments = [
        TranscriptSegment(start=0.0, end=1.0, text="Hello"),
        TranscriptSegment(start=1.1, end=2.0, text="World"),
    ]
    turns = [
        SpeakerTurn(start=0.0, end=1.2, speaker_id="SPEAKER_00"),
        SpeakerTurn(start=1.2, end=2.2, speaker_id="SPEAKER_01"),
    ]

    labels = assign_speakers_to_segments(segments, turns)

    assert labels == ["SPEAKER_00", "SPEAKER_01"]
    assert segments[0].speaker_id == "SPEAKER_00"
    assert segments[1].speaker_id == "SPEAKER_01"


def test_assign_speakers_to_segments_defaults_when_no_turns():
    segments = [TranscriptSegment(start=2.0, end=3.0, text="Only one line")]

    labels = assign_speakers_to_segments(segments, [])

    assert labels == ["SPEAKER_00"]
    assert segments[0].speaker_id == "SPEAKER_00"
    assert segments[0].speaker_confidence == 0.0


def test_sidecar_roundtrip_preserves_speaker_mapping(tmp_path):
    segments = [
        TranscriptSegment(start=0.0, end=1.0, text="Ahoj", speaker_id="SPEAKER_00"),
        TranscriptSegment(start=1.0, end=2.0, text="Cau", speaker_id="SPEAKER_01"),
    ]
    speaker_map = apply_speaker_mapping(
        segments,
        {
            "SPEAKER_00": "Martin",
            "SPEAKER_01": "Eva",
        },
    )

    result = TranscriptionResult(
        srt_path=Path("demo.srt"),
        raw_txt_path=Path("demo.txt"),
        segments=segments,
        detected_lang="cs",
        duration=2.0,
        speaker_turns=[
            SpeakerTurn(start=0.0, end=1.0, speaker_id="SPEAKER_00"),
            SpeakerTurn(start=1.0, end=2.0, speaker_id="SPEAKER_01"),
        ],
        speaker_map=speaker_map,
    )

    payload = build_speaker_sidecar(
        media_path=Path("meeting.wav"),
        base_name="meeting_2026",
        result=result,
        model_name="medium",
        output_mode="conference",
        output_prefix="",
        clean_text=True,
        export_md=True,
        split_minutes=0,
        generate_summary_pack=False,
        summary_lang="auto",
        speaker="",
        topic="",
    )

    sidecar_path = tmp_path / "meeting_2026.diarization.json"
    save_speaker_sidecar(sidecar_path, payload)
    loaded = load_speaker_sidecar(sidecar_path)

    assert loaded["base_name"] == "meeting_2026"
    assert loaded["speaker_map"]["SPEAKER_00"] == "Martin"
    assert loaded["speaker_map"]["SPEAKER_01"] == "Eva"
    assert loaded["segments"][0].speaker_name == "Martin"
    assert loaded["segments"][1].speaker_name == "Eva"
