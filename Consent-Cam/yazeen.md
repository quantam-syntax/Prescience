# Yazeen — Face Enrollment and Recognition Foundation

## How to use this file

This is the authoritative context for Yazeen's coding AI. Before changing code:

1. Read this file completely.
2. Read `progress.md` completely.
3. Work only on Yazeen's responsibilities unless a handoff explicitly expands them.
4. Update only Yazeen's block in `progress.md` before and after substantive work.
5. Commit the progress update with the code it describes.

## Product context

Consent-Cam is an Android privacy-camera app for the iQOO Hackathon 2026. Phone A records; Phone B represents a person who allows appearance; Phone C represents a person who requests protection. The app provides Normal Proximity Mode and Precise Face-Match Mode.

Face recognition is opt-in, local, and used only to associate an active consent session with a face. It is not an account identity system. No recognition request, embedding, crop, prompt, or image may be sent to a cloud service.

The profile owner may keep a reusable encrypted embedding on their own broadcaster phone. Phone A receives only a session copy and must delete it when the session ends.

## Yazeen's ownership

Yazeen owns the initial, independently testable recognition implementation:

- FaceNet-512 LiteRT/TFLite model integration.
- Guided 3–5-sample enrollment.
- Face alignment, resizing, normalization, and embedding generation.
- Embedding quality checks, aggregation, and L2 normalization.
- Encrypted persistent storage on the profile owner's phone.
- Session-only in-memory representation on the recorder.
- Exact cosine-similarity comparison.
- Match confidence states and a calibration screen/test harness.
- Fake implementations that let other modules integrate before the model is ready.

Yazeen does not own:

- BLE scanning, advertisements, GATT framing, or RSSI decisions.
- Continuous ML Kit tracking.
- CameraX photo/video UI or CameraEffect rendering.
- LLM-based identity decisions.
- A database, account, name label, or cross-device identity history.

## Enrollment pipeline

1. Run face detection locally and accept exactly one face.
2. Collect 3–5 guided samples covering frontal and small left/right variations under adequate light.
3. Align/crop consistently, pad without including excessive background, resize to the model's required input, and apply the model's documented normalization.
4. Generate one FaceNet-512 embedding per accepted sample.
5. Reject non-finite vectors, low-quality samples, and embedding outliers inconsistent with the other enrollment samples.
6. L2-normalize each accepted vector, average them, and L2-normalize the aggregate again.
7. Persist only the aggregate embedding, model ID/version, dimensions, and creation time on the owner's phone.
8. Immediately release image buffers after all synchronous/asynchronous consumers finish. Never write enrollment images to storage.

The Gemma enrollment coach is advisory. Deterministic checks decide whether a frame is accepted.

## Persistent owner-side profile

- Encrypt the aggregate profile with AES-GCM using a non-exportable Android Keystore key.
- Use a fresh random nonce on every write and store the nonce alongside ciphertext.
- Bind associated data to the profile schema and model version.
- Store no person's name or account identifier.
- Provide `hasProfile`, `loadProfile`, and `deleteProfile` operations.
- A corrupt, mismatched-version, or undecryptable profile must fail closed and request re-enrollment.
- `deleteProfile` removes ciphertext/metadata and the dedicated Keystore alias.

On Phone A, the same profile format is constructed only in memory after Rahul's authenticated GATT layer decrypts it. It must expose an explicit `close/clear` lifecycle that overwrites mutable float buffers where practical and drops references at session end.

## Recognition pipeline

- Run recognition only for a new `trackingId`, not every frame.
- Select a sufficiently large, well-oriented frame; return `INSUFFICIENT_QUALITY` rather than forcing a weak match.
- Produce a normalized FaceNet-512 query embedding.
- Compare it with every active session profile using exact cosine similarity; the demo has too few profiles to justify approximate vector search or a database.
- Return top score, second-best score, model ID, and match state.
- Cache the result outside the embedder using the tracking ID.
- Re-run when the track disappears/re-enters, quality changes substantially, or active enrollment sessions change.

Match states:

- `MATCHED_ALLOW`
- `MATCHED_PROTECT`
- `UNKNOWN`
- `AMBIGUOUS`
- `INSUFFICIENT_QUALITY`

Do not hard-code a threshold copied from another model. Provide a configurable threshold and ambiguity margin, then calibrate them on the actual model build and teammates using genuine and impostor samples across expected demo lighting and poses. Record the chosen values and evidence in `progress.md`.

The LLM must never select a matching identity, resolve a close score, or authorize an unblur decision.

## Interfaces Yazeen provides

```kotlin
data class EnrollmentProfile(
    val modelId: String,
    val dimensions: Int,
    val embedding: FloatArray,
)

interface FaceEmbedder : AutoCloseable {
    suspend fun embed(alignedFace: android.graphics.Bitmap): Result<FloatArray>
}

enum class FaceMatchState {
    MATCHED_ALLOW,
    MATCHED_PROTECT,
    UNKNOWN,
    AMBIGUOUS,
    INSUFFICIENT_QUALITY,
}

data class FaceMatch(
    val trackingId: Int,
    val sessionId: UInt?,
    val similarity: Float?,
    val secondBestSimilarity: Float?,
    val state: FaceMatchState,
)

interface FaceMatcher {
    suspend fun match(
        trackingId: Int,
        alignedFace: android.graphics.Bitmap,
        activeProfiles: List<SessionProfile>,
    ): FaceMatch
}
```

Coordinate any unavoidable signature adjustment through a handoff entry before changing shared types.

## Model and attribution

- Start from the Apache-2.0 `shubham0204/OnDevice-Face-Recognition-Android` FaceNet-512 implementation/model provenance referenced by the project plan.
- Copy only the minimum necessary implementation patterns rather than forking the entire app.
- Pin model/runtime versions and add the required license/model attribution to the notices supplied to the camera app.
- Keep the model bundled or pre-positioned so recognition works fully offline.

## Acceptance checks

- Enrollment accepts one clear face and rejects zero/multiple faces and unusable frames.
- Repeated enrollment samples for the same teammate cluster more closely than impostor samples.
- Output embeddings are finite, 512-dimensional, and L2-normalized.
- Exact cosine tests cover identical, near, different, zero, malformed, and non-finite vectors.
- Owner profile survives app restart but cannot be decrypted as plaintext outside the app.
- Owner deletion removes the profile and key alias.
- Recorder profiles disappear after session termination/process restart.
- Recognition returns every documented state and never throws on malformed input.
- Model latency and memory are measured on an actual iQOO phone and recorded in `progress.md`.
- No image or embedding appears in logs, cache files, screenshots created by the app, or network traffic.

## Progress update contract

Before work, set Yazeen's `Current task` and status in `progress.md`. After work, record completed items, exact tests and results, interfaces/files changed, blockers, handoffs, threshold evidence, and an Asia/Kolkata timestamp. Modify nothing between another teammate's ownership markers. Never mark work complete without verification.
