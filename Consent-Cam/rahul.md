# Rahul — BLE, Proximity, Detection, and Privacy Decisions

## How to use this file

This is the authoritative context for Rahul's coding AI. Before changing code:

1. Read this file completely.
2. Read `progress.md` completely.
3. Work only on Rahul's responsibilities unless a handoff explicitly expands them.
4. Update only the Rahul-owned block in `progress.md` before and after substantive work.
5. Commit the progress update with the code it describes.

## Product context

Consent-Cam is an Android privacy-camera app for the iQOO Hackathon 2026. It detects consent signals broadcast by nearby phones and protects people who do not consent to appear. Everything sensitive must run on-device. The shipped app must not upload faces, embeddings, prompts, audio, or consent state to a cloud service.

The three-phone demonstration is:

- Phone A records and applies privacy protection.
- Phone B broadcasts `ALLOW` and should remain visible.
- Phone C broadcasts `PROTECT` and should be blurred.

The app supports two independent protection modes:

- **Normal Proximity Mode:** no face enrollment is required. A nearby `PROTECT` beacon activates broad protection.
- **Precise Face-Match Mode:** session enrollment maps consent state to a specific face for selective blur.

Unknown faces remain visible by product decision except when Normal Proximity Mode must blur all detected faces because it cannot associate a beacon with one face. Describe this honestly as selective opt-out enforcement, not guaranteed bystander anonymization.

## Rahul's ownership

Rahul owns:

- Custom BLE advertising, scanning, and GATT session transport.
- Consent flags, ephemeral sessions, rotating-token creation and validation.
- RSSI filtering, proximity bands, privacy-zone calibration, hysteresis, and stale-device timeouts.
- Normal Proximity Mode.
- Bundled ML Kit face detection and tracking.
- QR session-secret intake and AES-GCM protection of enrollment data in transit.
- The deterministic protection-decision coordinator.
- Timestamped blur-region generation.
- Final integration coordination and three-phone demo readiness.

Rahul does not own:

- FaceNet inference, enrollment aggregation, or similarity scoring.
- CameraX photo/video UI and camera lifecycle.
- The Gemma voice agent or TextToSpeech.
- Changes to another member's interface without first recording a handoff in `progress.md`.

## BLE protocol

Use custom BLE service data, not Eddystone. Eddystone does not provide the application-specific consent and enrollment flow required here.

Legacy-compatible advertisement payload:

| Field | Size | Meaning |
|---|---:|---|
| Protocol version | 1 byte | Start at version 1 |
| Ephemeral session ID | 4 bytes | Random for each broadcast session |
| Consent flags | 1 byte | `ALLOW_APPEARANCE`, `PRECISE_MATCH_AVAILABLE` |
| Rotating token | 4 bytes | Truncated HMAC-SHA256 |

Requirements:

- Use a private 128-bit service UUID and exclude device name/extra advertisement fields.
- Rotate the token every 30 seconds using the session secret, session ID, time counter, and flags.
- After secure enrollment, accept only the current or immediately previous time window.
- Treat pre-enrollment advertisements as unverified discovery information.
- Never place an embedding, name, account identifier, or persistent device identifier in advertisements.

## Secure enrollment transport

- The recorder obtains a random 256-bit session secret from a QR code shown by the broadcaster.
- Use that secret with AES-256-GCM to protect the embedding payload transferred through GATT.
- Do not depend on Bluetooth bonding; the session must not leave a persistent recorder-side bond solely for enrollment.
- Do not assume a particular GATT MTU. Chunk transfers with session ID, sequence index, total count, and final payload digest.
- Use a fresh 96-bit GCM nonce for every encrypted message and never reuse a nonce with the same key.
- Phone A holds session secrets and received embeddings only in memory. Clear mutable buffers and references when a session ends.
- Never log keys, embeddings, decrypted chunks, raw face crops, or full rotating tokens.

## Normal Proximity Mode

RSSI is noisy and must not be displayed as an exact physical distance. Convert it to stable `NEAR`, `MID`, `FAR`, and `LOST` bands.

Processing rules:

1. Maintain samples separately for every ephemeral session.
2. Reject impossible/outlier samples, apply a short rolling median, then exponential smoothing.
3. Use a closer enter threshold and a farther exit threshold.
4. Require multiple consecutive samples before entering or exiting the privacy zone.
5. Mark a broadcaster `LOST` after a configurable stale timeout.
6. Expose raw and smoothed RSSI in debug UI but drive behavior only from the stable state.

Protection behavior:

- No nearby verified `PROTECT` session: proximity mode adds no blur.
- One detected face plus a nearby `PROTECT` session: blur that face.
- Multiple detected faces plus any nearby `PROTECT` session: blur every detected face because RSSI cannot identify the owner.
- No detected face while protection is active: retain protection until the exit/timeout rule fires.
- `ALLOW` sessions never activate proximity blur.

## Face detection and tracking

- Use the bundled ML Kit face detector so the first run works offline.
- Configure continuous analysis for FAST mode, tracking enabled, and contours/classification disabled.
- Use CameraX `KEEP_ONLY_LATEST`; never allow an analysis queue to grow.
- Close every `ImageProxy` exactly once in a completion/finally path.
- Convert detector coordinates through the camera transform supplied by the camera module rather than assuming preview coordinates.
- Emit stable `TrackedFace` values. Do not perform recognition inside the detector.
- Pad blur rectangles sufficiently to cover hairline, chin, and detector jitter, then clamp to frame bounds.
- Expire stale tracking IDs and request recognition again when a face re-enters with a new ID.

## Protection-decision precedence

The coordinator is deterministic:

1. Privacy disabled: emit no automatic blur.
2. Precise mode and confident `MATCHED_PROTECT`: blur that tracking ID.
3. Precise mode and confident `MATCHED_ALLOW`: leave that tracking ID visible.
4. Precise mode unavailable or unresolved while proximity protection is active: apply the Normal Proximity Mode rules.
5. Otherwise: leave the face visible and emit an uncertainty event where appropriate.

The LLM may explain these decisions but cannot create or override them.

## Interfaces Rahul provides

```kotlin
data class ConsentAdvertisement(
    val sessionId: UInt,
    val flags: UByte,
    val rotatingToken: UInt,
    val rssi: Int,
)

enum class ProximityBand { NEAR, MID, FAR, LOST }

data class ProximityState(
    val sessionId: UInt,
    val smoothedRssi: Double,
    val band: ProximityBand,
    val insidePrivacyZone: Boolean,
    val lastSeenElapsedRealtimeMs: Long,
)

data class TrackedFace(
    val trackingId: Int,
    val sensorRect: android.graphics.RectF,
    val timestampNs: Long,
    val quality: Float,
)

enum class ProtectionSource { PRECISE_MATCH, PROXIMITY, NONE }

data class ProtectionDecision(
    val trackingId: Int,
    val source: ProtectionSource,
    val shouldBlur: Boolean,
    val reason: String,
)

data class BlurRegion(
    val trackingId: Int,
    val normalizedRect: android.graphics.RectF,
    val timestampNs: Long,
    val enabled: Boolean,
)
```

Expose changing state through `StateFlow` and discrete events through `SharedFlow`. Keep Bluetooth, detection, decision, and UI layers independently testable.

## Acceptance checks

- A `PROTECT` phone entering/leaving the privacy zone changes protection without visible RSSI flicker.
- One-face and multi-face proximity rules behave exactly as documented.
- An `ALLOW` phone alone never activates proximity blur.
- Invalid/replayed tokens cannot extend a verified session.
- Interrupted GATT transfers cannot produce a partial usable embedding.
- Precise match decisions override proximity broadening for confidently matched faces.
- BLE or recognition failure cannot crash camera analysis.
- No secret, embedding, or face image is written to disk or logs by Rahul's modules.
- Unit tests use deterministic recorded RSSI sequences and synthetic timestamps rather than real delays.

## Progress update contract

Before work, set Rahul's `Current task` and status in `progress.md`. After work, record completed items, exact tests and results, interfaces/files changed, blockers, handoffs, and an Asia/Kolkata timestamp. Modify nothing between another teammate's ownership markers. Never mark work complete without verification.
