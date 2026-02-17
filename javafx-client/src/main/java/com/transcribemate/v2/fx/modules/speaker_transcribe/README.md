# speaker_transcribe (Frontend)

- Class: `SpeakerTranscriptModuleComponent`
- Purpose: speaker-aware transcript flow
- Enforced: `source.mode=local`, `output.mode=conference`, `diarization=true`
- Default diarization backend: `local_cluster_accurate`
- Default diarization accuracy profile: `maximum` (user can switch to `low|balanced|high|maximum`)
- UI flow target: Diarization tab
