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

The project compiles the domain contracts and a Compose demo UI once an Android SDK and Gradle wrapper/toolchain are available. Model inference, BLE transport, microphone capture, and redaction are deliberately represented by interfaces, not hidden fakes.

Read [the model contract](docs/VOICE_MODEL_CONTRACT.md) and [the redaction contract](docs/REDACTION_INTEGRATION_CONTRACT.md) before changing any interfaces.

## Workspace boundary

- `Consent_voice`: Rahul's voice enrolment, BLE session, recording, VAD, and speaker-decision work.
- `Voice_redaction`: Yazeen's separate redaction engine.
- Exchange only the versioned redaction contract, consented test fixtures, and a versioned engine artifact.

No raw recordings, embeddings, model files, or credentials belong in Git.
