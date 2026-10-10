# Consent Voice

Standalone Android scaffold for the iQOO Hackathon Voice Consent module. This project is Rahul's isolated workspace; it does not modify Consent Cam.

The intended offline flow is:

1. Enrol a speaker into a private voice profile.
2. Advertise a compact BLE consent session.
3. Obtain an explicitly approved profile exchange over BLE GATT.
4. Record audio in this participating app.
5. Produce protected, uncertain, and overlap timestamp decisions locally.
6. Give those decisions to Yazeen's separately maintained redaction engine.

## Current state

The Android app records and imports audio, creates a local voice profile, and
writes a sanitized WAV. Tapping **Enroll protected voice** opens the front
camera and guides the user through four positions. At each valid position it
records one in-memory take, displays a transparent word-following prompt, and
uses the packaged Whisper tiny.en INT8 model to validate the sentence fully
offline. The four accepted takes are retained as separate ERes2Net reference
embeddings, allowing recognition to use the closest microphone position
without averaging away useful voice detail. Neither camera frames nor
enrollment recordings are saved.

The active offline engine uses Silero VAD and the English VoxCeleb ERes2Net
speaker-verification model. Each detected utterance is compared directly with
the enrolled profile; only a confirmed match is muted. Uncertain speech is
retained and reported for review.

This handles speakers taking turns. It does not yet separate simultaneous
voices. The repository contains an ignored offline evaluation copy of
SpeechBrain SepFormer (`models/separation/speechbrain-sepformer-wsj02mix`), a
two-speaker separation model. It is not packaged into the APK or integrated
until it is exported to an Android-compatible runtime and benchmarked on the
iQOO 15.

Read [the model contract](docs/VOICE_MODEL_CONTRACT.md) and [the redaction contract](docs/REDACTION_INTEGRATION_CONTRACT.md) before changing any interfaces.

## Workspace boundary

- `Consent_voice`: Rahul's voice enrolment, BLE session, recording, VAD, and speaker-decision work.
- `Voice_redaction`: Yazeen's separate redaction engine.
- Exchange only the versioned redaction contract, consented test fixtures, and a versioned engine artifact.

No raw recordings, embeddings, model files, experimental checkpoints, or
credentials belong in Git.
