# Testing Guide

This document describes the current test suite, what it covers, and how to run it.

## Test runner
We use `pytest`.

Install dev requirements and run the suite:
```powershell
python -m pip install -r requirements-dev.txt
python -m pytest
```

## What is covered
The tests focus on fast, deterministic helpers that do not require external tools
or large model downloads. This keeps the suite lightweight and reliable.

### Core helpers
File: `tests/test_core_files.py`
- `sanitize_filename` replaces invalid characters.
- `split_segments` respects the requested segment duration.
- `unique_path` appends a suffix when the target exists.
- `timestamped_base_name` includes sanitized prefix/stem.

File: `tests/test_core_process.py`
- `safe_run` captures stdout and raises on non-zero exit codes.

### I18n summary language helpers
File: `tests/test_i18n_summary_lang.py`
- Normalization of supported/unsupported values.
- Label generation for the "auto" language option.
- Round-trip label-to-key conversion for "auto".

### Pipeline helper utilities
File: `tests/test_pipeline_helpers.py`
- `quality_to_format` output for "best" and specific resolutions.
- `hex_to_ass_color` RGB → ASS color conversion.
- `format_timestamp` formatting at zero duration.

### Transcript metadata and prompts
File: `tests/test_transcripts_metadata.py`
- Metadata rendering for multi-line "Topic".
- Summary prompt language instruction (explicit and auto/detected).

## What is not covered (yet)
- End-to-end JavaFX UI flows and process integration behavior.
- Integration with FFmpeg, yt-dlp, Whisper, or translation models.
- GPU detection and CUDA environment-specific setup behaviors.

## Tips for adding tests
- Keep tests fast and deterministic (no network, no external binaries).
- Prefer unit tests for helper functions and pure formatting logic.
- If you need to mock external tools, isolate behavior behind a small helper
  and test that helper in isolation.
