# speaker_transcribe (Backend)

- Entry: `SpeakerTranscriptComponent`
- Runtime profile: local media + diarization flow
- Contract: forces `source.mode=local`, `output.mode=conference`, diarization enabled
- Diarization backend is user-selectable (`local_cluster_accurate` recommended for multi-speaker detection).
- Diarization accuracy profile is user-selectable via `diarization.accuracy_profile` (`low`, `balanced`, `high`, `maximum`).
