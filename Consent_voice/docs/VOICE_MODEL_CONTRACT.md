# Voice Model Contract

Contract version: 1. Owner: Rahul. Consumers: Yazeen and Ganesh.

## Frozen stack

| Task | Choice | Shared owner |
| --- | --- | --- |
| Android inference runtime | Sherpa-ONNX Android arm64 | Rahul |
| Speaker embedding and verification | WeSpeaker VoxCeleb ResNet34 ONNX | Rahul |
| Speech segmentation | Silero VAD ONNX | Rahul |
| Audio redaction/export | Yazeen's `Voice_redaction` engine | Yazeen |

No alternate speaker-embedding, VAD, diarization, or source-separation model is part of this MVP. English-only sensitive-information ASR is intentionally outside this first Voice Consent handoff.

## Input and profile invariants

- Capture and resample to 16 kHz, mono PCM before VAD and speaker embedding.
- Enrol with three clean, consented samples; retain only the normalized aggregate embedding as the profile.
- Keep model files, raw samples, embeddings, and logs private and out of Git.
- Pin the exact Sherpa-ONNX release, model download URLs, SHA-256 checksums, file sizes, and licences after the first successful iQOO 15 smoke test.
- Reject a received profile if its model ID, embedding dimension, preprocessing version, or contract version differs.

## Decision policy

- Match only VAD speech regions against an approved, nearby session profile.
- Calibrate protected and uncertainty thresholds using separate held-out recordings from the three teammates.
- Emit `PROTECTED`, `UNCERTAIN`, or `OVERLAP` ranges. The redaction engine mutes all three.
- Speaker embeddings identify a speaker in clear speech; they do not separate simultaneous speakers. Mute the entire unresolved overlap range and show a warning.

## Attribution

- Sherpa-ONNX: https://github.com/k2-fsa/sherpa-onnx
- WeSpeaker pretrained VoxCeleb model: https://github.com/wenet-e2e/wespeaker/blob/master/docs/pretrained.md
- Silero VAD model distributed by the selected Sherpa-ONNX release: record exact release and checksum before submission.
