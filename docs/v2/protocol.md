# V2 IPC Protocol (JSON Lines)

Transport is newline-delimited JSON (`\n`-terminated objects).

## Envelope Types
- `request`
- `response`
- `event`

## Request
```json
{
  "type": "request",
  "id": "d2d8e2f3-3b3f-4cf5-9d5f-17b679a2ca1a",
  "method": "run_pipeline",
  "params": {}
}
```

## Response (success)
```json
{
  "type": "response",
  "id": "d2d8e2f3-3b3f-4cf5-9d5f-17b679a2ca1a",
  "ok": true,
  "ts": "2026-02-16T23:55:20+00:00",
  "result": {
    "job_id": "7b7f5b3f4b9c4cf3bd8c4a4f1977339a",
    "status": "queued"
  }
}
```

## Response (error)
```json
{
  "type": "response",
  "id": "d2d8e2f3-3b3f-4cf5-9d5f-17b679a2ca1a",
  "ok": false,
  "ts": "2026-02-16T23:55:20+00:00",
  "error": {
    "code": "invalid_request",
    "message": "source.mode must be one of: local, youtube",
    "details": {"source.mode": "ftp"}
  }
}
```

## Event
```json
{
  "type": "event",
  "event": "job.progress",
  "job_id": "7b7f5b3f4b9c4cf3bd8c4a4f1977339a",
  "ts": "2026-02-16T23:56:10+00:00",
  "payload": {
    "step": "transcribe",
    "overall_pct": 33.5,
    "step_pct": 62.0,
    "item_index": 1,
    "total_items": 3,
    "indeterminate": false
  }
}
```

## Methods
- `ping`
- `get_capabilities`
- `run_pipeline`
- `cancel_job`
- `get_job`
- `list_jobs`
- `remove_job`
- `health`
- `shutdown` (server-level command)

## `get_capabilities` notes
- `module_components[]` includes backend-driven metadata for each module.
- `module_components[].ui_schema` is used by frontend to show only module-relevant UI.
  - `show_tabs`: visible tabs for module context
  - `show_sections`: visible cards/sections
  - `show_fields`: visible field-level controls
  - `jobs_filter_module`: whether job list should be filtered to active module

## `list_jobs` params (optional)
```json
{
  "module_id": "speaker_transcribe",
  "project_id": "hrapp_brainstorming"
}
```
- `module_id` (or alias `module`) filters returned jobs to one module.
- `project_id` filters returned jobs to one workspace project.

## `run_pipeline` params shape (simplified)
```json
{
  "source": {
    "mode": "local",
    "path": "C:/media/session.wav",
    "files": [],
    "url": "",
    "is_playlist": false,
    "quality": "best"
  },
  "output": {
    "mode": "txt_only",
    "out_dir": "C:/Users/name/Downloads",
    "output_prefix": "meeting",
    "keep_originals": false
  },
  "transcription": {
    "model": "large-v3",
    "auto_model": false,
    "prefer_gpu": true,
    "source_lang": "auto",
    "batch_size": 16
  },
  "translation": {
    "target_lang": "en→cs"
  },
  "subtitles": {
    "mode": "soft",
    "font": "Arial",
    "size": 24,
    "color": "#FFFFFF",
    "outline_color": "#000000",
    "outline_width": 2
  },
  "text": {
    "clean_text": false,
    "export_md": false,
    "summary_pack": false,
    "split_minutes": 0,
    "summary_lang": "auto",
    "speaker": "",
    "topic": ""
  },
  "diarization": {
    "enabled": false,
    "backend": "local_cluster_accurate",
    "accuracy_profile": "balanced",
    "min_speakers": 0,
    "max_speakers": 0,
    "include_unmapped_speakers": true,
    "speaker_prefix_in_srt": true
  },
  "project": {
    "project_id": "hrapp_brainstorming",
    "name": "HRAPP Brainstorming",
    "root_dir": "C:/Users/name/AppData/Local/TranscribeMate/workspace/projects/hrapp_brainstorming",
    "input_dir": "C:/Users/name/AppData/Local/TranscribeMate/workspace/projects/hrapp_brainstorming/input",
    "output_dir": "C:/Users/name/AppData/Local/TranscribeMate/workspace/projects/hrapp_brainstorming/output",
    "jobs_dir": "C:/Users/name/AppData/Local/TranscribeMate/workspace/projects/hrapp_brainstorming/jobs",
    "timeline_path": "C:/Users/name/AppData/Local/TranscribeMate/workspace/projects/hrapp_brainstorming/jobs/timeline.jsonl"
  },
  "conference_meta": {}
}
```
- `transcription.prefer_gpu` is platform-normalized by the backend:
  - Windows releases may honor `true` when CUDA/NVIDIA runtime is ready.
  - macOS releases stay CPU-focused by default, but preserve current fallback behavior.
  - Linux releases force `prefer_gpu=false` and run CPU-only in this release.

## Status Values
- `queued`
- `running`
- `completed`
- `failed`
- `cancelled`
