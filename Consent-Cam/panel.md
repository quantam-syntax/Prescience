# Consent-Cam — Rahul Handoff Panel

## Where the project is now

`main` contains the merged Android app and the current privacy pipeline. This is
an offline-first iQOO Hackathon prototype: the recorder phone detects faces,
uses nearby BLE consent broadcasts, and applies a face-only mosaic to a
protected person in preview and intended capture output.

The app has two privacy modes:

1. **Normal Proximity Mode** — a nearby authenticated `PROTECT` BLE beacon
   causes broad protective treatment because RSSI alone cannot tell which
   visible person owns the phone.
2. **Precise Face-Match Mode** — after explicit face enrollment and an
   authenticated QR pairing step, the recorder compares on-device face
   embeddings and narrows protection to the uniquely matched protected person.
   Uncertain, ambiguous, lost, or multi-protected situations deliberately fall
   back to broad protection.

There are no cloud calls in the shipped logic. Face images, embeddings, pairing
secrets, BLE tokens, and captured media must never be logged or committed.

## What is implemented

### Camera and output privacy

- CameraX preview, photo capture, video recording, camera switching, capture
  feedback, thumbnail review, and native-gallery handoff.
- ML Kit on-device face detection and tracking, crop-aware coordinate mapping,
  front-camera mirroring, eye-line alignment, and short missed-frame smoothing.
- A GPU `CameraEffect` / GLES mosaic effect designed for preview, saved photos,
  and recorded video; masks are padded, clamped, motion-smoothed, and can be
  oriented with head roll.
- Fail-closed behavior: a protected recording must not silently be presented as
  protected if the output effect has failed.

### BLE consent and safety decisions

- Direct advertiser/scanner flow with a private service UUID, 10-byte
  authenticated advertisement, rotating HMAC token validation, replay/freshness
  checks, RSSI smoothing, hysteresis, stale-session handling, and Near/Medium/
  Far privacy-zone presets.
- `ALLOW` and `PROTECT` controls, visible token/advertising status, automatic
  protection master switch, and a foreground BLE service for reliability across
  Activity recreation.
- Deterministic protection coordinator that produces timestamped normalized
  `BlurRegion`s. The LLM cannot make identity or unblur decisions.

### Precise recognition and pairing

- Guided five-sample face enrollment, quality/pose checks, aligned in-memory
  crops, normalized 512-value FaceNet embeddings, cosine matching, ambiguity
  handling, per-tracking-ID caching, and backend/latency reporting.
- Owner profile encryption with a non-exportable Android Keystore key; explicit
  delete/re-enrol actions are available in Settings.
- QR-authenticated precise-profile exchange and encrypted, digest-checked GATT
  chunk transport. Normal consent broadcasting does **not** need QR pairing;
  pairing is only for transferring biometric-derived profile data safely.
- LiteRT FaceNet tries Qualcomm QNN/HTP where compatible, then GPU, then CPU;
  the UI reports the actual backend or fallback rather than claiming NPU.

### Local agent / voice (newly merged)

- Offline model import guard and model catalog for Gemma 3n E4B/E2B.
- Thermal governor, privacy-audit state, allow-listed commands, confirmation for
  privacy-reducing actions, English/Tamil narration hooks, and pinned offline
  Whisper command transcription plumbing.
- Gemma and Whisper model binaries are intentionally external and license-gated;
  no multi-GB model belongs in Git.

## Important code boundaries

| Concern | Main location | Contract / continuation point |
|---|---|---|
| App screens and controls | `ConsentCam/app/src/main/java/com/example/consent_cam/MainActivity.kt` | Keep UI descriptive; do not let it bypass the privacy coordinator. |
| Camera and analysis | `camera/ConsentCameraController.kt` | CameraX preview/photo/video and face-crop requests. |
| Face detection / geometry | `vision/` | `MlKitFaceDetector`, `FaceCoordinateMapper`, `FaceAlignment`. |
| Recognition | `recognition/` | Use `FaceEmbedder` / `FaceMatcher`; retain fail-closed match states. |
| BLE transport | `ble/` and `com/consentcam/ble/` | `BleConsentController` connects UI to authenticated advertising/scanning. |
| Privacy decision | `privacy/` and `com/consentcam/privacy/` | `ProtectionDecisionCoordinator` owns whether a face is protected. |
| GPU mask | `privacy/rendering/ProtectedOutputEffect.kt` | Receives `BlurRegion`; must affect encoded output as well as preview. |
| Local assistant | `agent/`, `voice/`, `hardware/` | Counts/states only; never provide biometric data to an LLM. |

## Recommended two-phone demo

1. Install the same latest debug APK on both iQOO phones and grant Camera,
   Bluetooth/Nearby Devices, and notifications as prompted.
2. On the protected person's phone, choose `PROTECT` and start BLE advertising.
   This demonstrates Normal Proximity Mode without pairing.
3. On the recorder, open the camera, enable protection, select the desired
   privacy-zone preset, and confirm the BLE / token status changes when the
   protected phone is nearby.
4. For Precise Face-Match Mode, enrol the protected person's face with the five
   guided samples. Do not use fake embeddings or save images.
5. Show the protected phone's pairing QR code. On the recorder choose `Pair
   phone` and scan it. This authenticates the encrypted profile transfer.
6. Return to camera view with both phones advertising/scanning. The badge should
   move from resolving/broad fallback to a matched protected track only when
   there is one clear match. Add another face to demonstrate that it remains
   visible while the matched `PROTECT` face is mosaicked.
7. Capture a photo and a short video, then inspect the saved media—not only the
   preview—to validate output protection.

## What needs real-device validation next

- Run the precise QR/GATT transfer across two iQOO phones after real enrollment.
- Calibrate FaceNet similarity threshold and ambiguity margin using genuine and
  impostor samples under the actual demo lighting. The provisional threshold is
  not release evidence.
- Check preview, saved photo, and saved video mask alignment for front/back
  camera, rotation, aspect crop, multiple faces, and movement.
- Confirm real QNN/HTP graph delegation; otherwise verify the visible GPU/CPU
  fallback, inference latency, thermals, and sustained recording stability.
- Import an accepted-license Gemma model and Whisper model only onto the test
  device, then validate offline commands, Tamil narration, and thermal fallback.
- Perform the final three-phone rehearsal: recorder + `ALLOW` broadcaster +
  `PROTECT` broadcaster, entirely offline.

## Verification already recorded

- The merged project previously passed JVM tests, debug assembly, and lint with
  no lint errors; the dashboard records the exact historical commands/counts in
  `progress.md`.
- The original precise-recognition instrumentation checks cover bundled FaceNet
  embedding output, Android Keystore profile round-trip, and offline QR decode.
- Physical iQOO end-to-end BLE, face-match calibration, output-media inspection,
  QNN delegation, and external model workflows remain device-validation work;
  do not represent them as complete before running them.
- A post-merge build attempted on 2026-09-13 could not reach Maven Central or
  Google's Android repository to download newly merged Whisper, QNN, and LiteRT
  artifacts. This is a network/dependency-cache limitation of this workstation,
  not a verified source failure; rerun the command below on a connected machine
  before demo use.

## First actions for Rahul

1. Pull `main`, open `ConsentCam/` in Android Studio, and select Android
   Studio's bundled JDK if the terminal lacks `JAVA_HOME`.
2. With network access or a warmed Gradle cache, run
   `./gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`
   from `ConsentCam/`, then install `app/build/outputs/apk/debug/app-debug.apk`
   on both phones.
3. Use the two-phone checklist above and record device model, backend selected,
   latency, mask alignment, and failures in Rahul's block in `progress.md`.
4. Keep privacy fallbacks conservative: uncertainty must broaden protection,
   never expose a person who asked for `PROTECT`.
