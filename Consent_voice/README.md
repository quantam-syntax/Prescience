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
speaker-verification model. After recording, the iQOO HTP runs a cached
two-speaker 16 kHz SepFormer model in overlapping two-second blocks. ERes2Net
matches the separated streams to the enrolled Voice Passport, and the app
replaces a matched overlap with the other stream. Protected-only speech and
unresolved overlap are muted. The retained stream receives a gentle
cross-stream cleanup filter. Raw separated sources remain in memory; the app
does not save a source-preview recording.

The clearer 16 kHz path is a review-before-sharing mode, not guaranteed voice
removal: faint protected speech was audible in a rehearsal clip. The app warns
that leakage is possible and the entire exported WAV must be listened to
before sharing. Uncertain unmatched speech is retained and reported for review.

The consent pipeline also has a source-aware boundary: only a successful QAIRT
HTP result may be used to retain the unprotected separated stream. If the
runtime, model, identity decision, timing, or source quality is unavailable,
the overlap is muted rather than exporting the original mix.

Read [the model contract](docs/VOICE_MODEL_CONTRACT.md) and [the redaction contract](docs/REDACTION_INTEGRATION_CONTRACT.md) before changing any interfaces.

## Workspace boundary

- `Consent_voice`: Rahul's voice enrolment, BLE session, recording, VAD, and speaker-decision work.
- `Voice_redaction`: Yazeen's separate redaction engine.
- Exchange only the versioned redaction contract, consented test fixtures, and a versioned engine artifact.

No raw recordings, embeddings, model files, experimental checkpoints, or
credentials belong in Git.

The cached 16 kHz DLC and QAIRT SDK/runtime are private, ignored build inputs.
After placing them at the paths documented in
[the clarity evaluation](docs/SEPARATION_CLARITY_EVAL.md), run
`powershell -ExecutionPolicy Bypass -File scripts/stage_qairt_sepformer.ps1`
before building the Android app. The locally staged DLC is about 162 MB and
targets the tested iQOO HTP; an APK built without that asset cannot run the
separator and must use the mute fallback.
