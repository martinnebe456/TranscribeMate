# Testing Guide

This document describes the current Python test suite for TranscribeMate V2.

## Runner
We use `pytest`.

Install dependencies and run all tests:

```powershell
python -m pip install -r requirements-dev.txt
python -m pytest -q
```

Run only backend V2 contract tests:

```powershell
python -m pytest -q tests/test_v2_protocol.py tests/test_v2_models.py tests/test_v2_service.py tests/test_v2_components.py
```

Run only shared core/pipeline helper tests:

```powershell
python -m pytest -q tests/test_core_files.py tests/test_core_process.py tests/test_i18n_summary_lang.py tests/test_pipeline_helpers.py tests/test_diarization_helpers.py tests/test_speaker_srt_helpers.py tests/test_transcripts_metadata.py
```

## Test inventory

### Shared core helpers
File: `tests/test_core_files.py`
- filename sanitization
- segment splitting by duration
- unique output path generation
- timestamped output name generation

File: `tests/test_core_process.py`
- subprocess wrapper behavior (`safe_run`)
- stdout capture and non-zero exit handling

File: `tests/test_i18n_summary_lang.py`
- summary language normalization
- label mapping for `auto` and round-trip key conversion

### Shared pipeline and diarization helpers
File: `tests/test_pipeline_helpers.py`
- YouTube quality mapping
- subtitle ASS color conversion
- transcript timestamp formatter
- speaker prefix parsing helper

File: `tests/test_diarization_helpers.py`
- speaker assignment by overlap
- default speaker fallback
- diarization sidecar JSON roundtrip

File: `tests/test_speaker_srt_helpers.py`
- speakerized SRT generation
- SRT prefix rewriting behavior with/without unmapped speakers

### Transcript metadata rendering
File: `tests/test_transcripts_metadata.py`
- metadata block formatting
- summary prompt language line behavior
- transcript rendering with speaker prefixes
- conference metadata fields in output metadata

### Backend V2 contracts
File: `tests/test_v2_protocol.py`
- JSON-RPC request parsing and validation
- response error payload shape

File: `tests/test_v2_models.py`
- request payload parsing/validation
- module/output constraints
- translation target normalization
- conference metadata extension parsing

File: `tests/test_v2_service.py`
- service capabilities and health/preflight surface
- preflight behavior when `torch` is missing (warn vs fail by mode)
- `list_jobs` module filter behavior (`module_id` / `module`)

File: `tests/test_v2_components.py`
- backend component registry integrity
- per-module request normalization/contract enforcement
- component metadata exposure in capabilities response
- module `ui_schema` exposure in capabilities response

## Current scope boundaries
The suite is intentionally fast and deterministic.

Not covered by unit tests:
- end-to-end JavaFX UI flows
- full runtime bootstrap/download integration (models, FFmpeg, Python runtime)
- real GPU/CUDA environment execution paths

## Guidelines for new tests
- Keep tests offline and deterministic.
- Prefer pure-function and contract tests over long integration tests.
- Mock external tools and heavyweight runtime dependencies.
- Add/adjust tests with each protocol/model/service contract change.
