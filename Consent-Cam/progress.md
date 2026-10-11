# Consent-Cam Progress Dashboard

## Update rules for every coding AI

1. Read your teammate context file and this file before changing code.
2. At task start, update `Current task`, `Status`, and `Last updated` in your owned block.
3. After work, update completed/in-progress items, exact test results, changed files/interfaces, blockers, and handoffs.
4. Use Asia/Kolkata timestamps in `YYYY-MM-DD HH:mm IST` format.
5. Modify only the content between your own `AI:<NAME>:START` and `AI:<NAME>:END` markers.
6. Never mark an item complete without recording how it was verified.
7. Commit the progress update alongside the code it describes.
8. Rahul alone maintains Shared Integration Status unless a handoff explicitly delegates it.

Allowed status values: `NOT_STARTED`, `IN_PROGRESS`, `BLOCKED`, `READY_TO_INTEGRATE`, `DONE`.

## Overall status

- **Project:** NOT_STARTED
- **Integration owner:** Rahul
- **Current milestone:** Repository and Android project scaffolding
- **Last updated:** 2026-09-12 12:53 IST

## Rahul — BLE, proximity, detection, privacy coordinator

<!-- AI:RAHUL:START -->
- **Branch:** `feature/rahul-ble-proximity`
- **Status:** IN_PROGRESS
- **Current task:** Complete the 06:30 physical-demo readiness pass: camera controls/layout, automatic zero-contact selective face consent, enrollment reliability, BLE range, tri-state consent, protected outputs, and device verification.
- **Last updated:** 2026-09-13 09:40 IST

### Background BLE and widget

- Added boot/package-replacement recovery for the existing sticky BLE consent foreground service; it restores the persisted consent state without starting camera or microphone capture.
- Added a lightweight ConsentCam home-screen widget showing persisted consent and opening the app. Camera/FaceNet remains activity-scoped because Android/iQOO background camera execution requires a visible camera foreground service and explicit user activation.
- `testDebugUnitTest assembleDebug lintDebug`: PASS.

### Voice Q&A extension

- Added `LocalAiController.answerQuestion()` using the existing initialized Gemma engine and generation mutex; no second model instance or face/consent pipeline changes. Unknown Whisper transcripts are answered from a bounded `PrivacyAuditSnapshot` only, with an explicit out-of-scope response policy. Answers surface through the existing assistant notice UI.
- `testDebugUnitTest assembleDebug lintDebug`: PASS. Hardware voice-question verification remains pending.


### Current demo repair checkpoint

- Saved-media investigation on the two connected I2501 / Android 16 phones: decoded the user's latest 9.055-second protected recording locally, then removed every temporary local copy. The mask was visibly offset from the protected person's face throughout the encoded video, confirming a renderer-coordinate bug rather than a threshold failure. `ProtectedOutputEffect` now maps each smoothed analysis rectangle through CameraX's documented analysis-buffer -> sensor -> exact `SurfaceOutput` transform independently for preview, JPEG, and encoder surfaces; this replaces the invalid assumption that normalized analysis coordinates equal video coordinates. The shared non-null ViewPort fallback remains in place for OriginOS early Compose layout. Added rotation/mirror inverse mapping coverage. `testDebugUnitTest assembleDebug lintDebug` PASS: 97 JVM tests, zero failures/errors; APK installed on both phones. Final two-person saved-video verification is blocked only by the recorder's currently open system notification shade.

- Enhanced matching is now strict-selective at the user's request: only a confirmed `MATCHED_PROTECT` face is blurred. `UNKNOWN`, `AMBIGUOUS`, and insufficient-quality faces remain visible immediately; the smoother is cleared rather than retaining a stale mask while association is unresolved. Nonselective `Protection` mode intentionally retains broad nearby-PROTECT behavior. Added mixed confirmed/unknown and missing-track decision coverage. `testDebugUnitTest assembleDebug lintDebug` PASS: 98 JVM tests, zero failures/errors; latest APK installed on both attached phones.

- Per user calibration request, the default exact cosine threshold is now `0.64` (ambiguity margin remains `0.08`). Scores below `0.64` are uncertain and therefore remain unblurred in Enhanced mode. Boundary tests cover `0.63` unknown and `0.64` confirmed match; verification is pending.

- Normal camera captures no longer require a protected renderer merely because a nearby PROTECT session exists. Photo, video, and voice-initiated video require protected output only while a confirmed protected region is active; this permits ordinary photography with no confirmed protected person in frame while retaining protected output for a confirmed match. `testDebugUnitTest assembleDebug lintDebug` PASS: 98 JVM tests, zero failures/errors; APK installed on both phones.

- Third iQOO `10BFBJ1547001GG` was authorized and provisioned with the latest APK, camera/audio/BLE permissions, Gemma E2B (3,655,827,456 bytes), and Whisper base.en (147,964,211 bytes). Its incompatible prior signature required uninstall/reinstall; it had no owner face profile, so no enrollment was lost. Model files were transferred in verified chunks and temporary staging files were removed. App process starts successfully; final UI/model-initialization check is pending dismissal of the phone's system notification shade.

- Two-iQOO validation: `10BFAX1C9F0010U` broadcasts `PROTECT`; recorder `10BFC41SRT001UZ` automatically discovers it, enters broad protection, and receives one encrypted temporary PROTECT profile without QR/Wi-Fi/internet. Live FaceNet reports CPU/XNNPACK at 23 ms after expected QNN/HTP DSP fallback. Hardware calibration observed genuine score 0.564/0.876 and impostor 0.064, so the release threshold is now 0.50 (unknown at 0.49), with the 0.08 ambiguity margin retained. An UNKNOWN tracking result now retries after 750 ms rather than being permanently cached. `testDebugUnitTest assembleDebug lintDebug` PASS: 96 JVM tests, zero failures/errors; calibrated APK installed on both phones with profiles/models preserved. A protected photo/video saved successfully without effect failure, but controlled saved-pixel alignment still needs a stationary matched participant plus an unprotected participant; temporary test media was deleted.

- Hardware resume validation on `10BFC41SRT001UZ` (I2501 / Android 16): installed the current APK over the existing app data; 3 non-destructive instrumentation checks PASS (app context, offline QR render/decode, bundled FaceNet 512-value normalized inference). The profile-destruction instrumentation check was intentionally not run because the phone has a participant owner profile. Rear preview launches without app/effect errors; a front-camera switch reaches camera ID 1. Normal capture saved two 742x1632 photos and one 582x1280 3.999-second video; the exact temporary MediaStore URIs 263, 265, and 266 were deleted and confirmed absent. Privacy protection was restored after that normal-capture test. Owner encrypted profile, 3,655,827,456-byte Gemma E2B, and 147,964,211-byte Whisper base.en remain present. The second scripted lens-switch tap did not observe a return to camera ID 0; repeat that manually before demo. Controlled protected-media pixel alignment and a three-phone ALLOW/PROTECT rehearsal remain pending.

- Resume audit on the merged `main` checkout: `testDebugUnitTest`, `assembleDebug`, and `lintDebug` pass locally with 95 JVM tests and zero failures/errors. Fresh debug APK SHA-256 is `BCD4004AEE22D9CEB0626BEB65B79678DB3D7397E2A5478B47D5F228D768A463`. The Android SDK toolchain is present, but no ADB device is currently attached, so physical BLE, protected-media, voice, and live-recognition validation cannot be rerun on this workstation.

- Failed capture cleanup implemented: video finalize errors or renderer failures delete only that capture's returned MediaStore URI; a photo completion following renderer failure uses the same cleanup. Cleanup errors are surfaced. `assembleDebug lintDebug` PASS; APK installed on attached phone. Induced capture-failure and saved-pixel hardware checks remain pending.

- Protected-renderer lifecycle audit after checkpoint `00f6acf`: guarded delayed surface-return callbacks so an old camera cannot release the replacement camera input. `assembleDebug lintDebug` PASS; installed and repeated front/back switch opens camera 1 then camera 0 on the attached phone. Saved-pixel validation remains pending.
- Device instrumentation cleanup removed app-private models/permissions. Restored Gemma E2B and Whisper from the preserved device-local copies and re-granted camera/audio/BLE permissions. Avoid repeating connected tests on participant-enrolled installations without preserving app data.

- Camera-first navigation and default selective mode implemented; OFF/ALLOW/PROTECT now appears in Settings and voice parsing. Consent button padding and local-audit layout repaired.
- Camera 2x capture request verified at 2.0 on phone `10BFBJ1547001GG`; rear/front switching opens camera 0/1. Flash control completes and reports ON. Physical flash illumination and protected saved pixels still need observation.
- 0.6x remains unresolved: vendor HAL camera 2 advertises 0.6–100x, but CameraX currently enumerates only rear camera 0 and front camera 1; camera 0 exposes 1–10x. Do not claim ultra-wide works.
- Replaced active QR workflow with automatic ephemeral ECDH/AES-GCM GATT exchange. No bonding or prior contact required. Passive encryption is provided; peer identity is not authenticated against an active intermediary. Three-phone transport validation remains pending.
- Fixed mirrored selfie enrollment versus rear-camera recognition input orientation; enrollment now preserves completed sample count and exposes model errors. Poor-quality/ambiguous recognition retries after 750 ms rather than remaining cached indefinitely.
- Room proximity preset is default. RSSI remains approximate; stale profiles are discarded after the existing eight-second signal-loss grace rather than a further thirty seconds.
- `testDebugUnitTest assembleDebug lintDebug`: PASS, 95 JVM tests, zero failures; lint zero errors. `connectedDebugAndroidTest`: PASS, four tests on I2501 Android 16, including synthetic FaceNet inference and Android Keystore round-trip. These are not live accuracy or protected-media tests.
- Device FaceNet log shows QNN DSP load failure followed by CPU/XNNPACK inference; NPU execution is not validated.
- Latest APK installed on `10BFBJ1547001GG`; incompatible previous debug signature required replacement. Gemma E2B and Whisper files were preserved and restored; no owner enrollment existed at replacement. Other two phones/participants will be available shortly according to Rahul.
- Remaining: enrollment/live matching, automatic BLE across all three phones, voice microphone actions, protected photo/video pixel checks, camera ultra-wide access, and final demo rehearsal.

### Completed

- [x] Defined the private version-1 service UUID and fixed 10-byte big-endian advertisement codec.
- [x] Implemented 30-second HMAC-SHA256 token rotation, current/previous-window validation, and replay-aware freshness.
- [x] Implemented per-session RSSI outlier rejection, rolling median, exponential smoothing, proximity bands, entry/exit hysteresis, and stale timeout handling.
- [x] Implemented in-memory verified-session registration, authentication expiry, raw scan processing, and ready-to-advertise broadcast sessions.
- [x] Implemented deterministic proximity/precise protection decisions and padded, clamped, timestamped normalized blur regions.
- [x] Implemented QR session-secret encoding, AES-256-GCM enrollment protection, and digest-verified MTU-independent GATT chunk reassembly.
- [x] Integrated the core into Ganesh's Android app with simultaneous direct BLE advertising/scanning, runtime permissions, token-status UI, consent controls, and proximity protection state.
- [x] Rebased the BLE integration onto Ganesh's capture-control polish while preserving thumbnails, gallery handoff, shutter feedback, and recording timer behavior.
- [x] Added bundled offline ML Kit face detection, CameraX keep-latest analysis, tracked normalized observations, front-camera mirroring, and live verification boxes.
- [x] Connected direct BLE proximity decisions to padded, motion-smoothed, opaque face mosaics in the live preview with a short missed-detection hold.
- [x] Corrected captured-media UI so raw saved output is not described as protected before the encoded-output effect exists.
- [x] Added multi-profile in-memory precise matching for simultaneous ALLOW/PROTECT sessions and multiple protected tracking IDs; any unresolved PROTECT profile retains broad fallback protection.
- [x] Carried ML Kit head roll through timestamped blur regions and the smoother into a rotated GLES oval mask for tighter, orientation-aware face-only protection.
- [x] Added configurable Near/Medium/Far RSSI privacy-zone presets plus a visible automatic-protection master switch.
- [x] Added an in-app verified-size Gemma 3n E4B/E2B importer, storage guard, atomic temporary copy, real fallback/trigger/thermal metrics, periodic audits, and final summaries.
- [x] Replaced vendor speech recognition with pinned whisper.cpp `base.en` transcription, verified private model import, ephemeral command audio, allow-listed commands, privacy-reduction confirmation, and deterministic English/Tamil TextToSpeech narration.
- [x] Moved process ownership of BLE advertising/scanning to a connected-device foreground service for Activity recreation/background reliability.
- [x] Added Qualcomm QNN 2.49 Hexagon HTP execution for FaceNet with deterministic NPU-to-GPU-to-CPU fallback, invocation-failure recovery, private compile caching, and visible backend/failure reporting.

### In progress

- [ ] Install the debug APK on two iQOO phones and record direct `ALLOW`/`PROTECT` advertisement results.
- [x] Add the ML Kit detector adapter, CameraX analyzer, coordinate mapping, and device-visible detection overlay.
- [x] Connect proximity decisions to a low-latency preview privacy-mask renderer.

### Next

- [x] Define custom BLE service UUID and codec tests.
- [x] Implement scanner/advertiser permission and capability checks.
- [x] Implement RSSI filter, hysteresis, proximity bands, and timeouts.
- [x] Implement ML Kit detection/tracking adapter.
- [x] Implement protection-decision coordinator and blur-region flow.
- [ ] Wire the verified QR/AES-GCM/chunking core into Android GATT client/server transport.

### Tests and results

- `kotlinc` production compilation: PASS.
- Direct JUnit invocation through `BleCoreTestRunner`: PASS, 45 core tests passed and 0 failed.
- `ConsentCam\\gradlew.bat testDebugUnitTest assembleDebug`: PASS; 46 tests passed, 0 failed, and debug APK assembled.
- `ConsentCam\\gradlew.bat lintDebug`: PASS after adding an internal audio-permission guard and explicit optional camera hardware declaration; 0 lint errors.
- Post-sync `ConsentCam\\gradlew.bat lintDebug testDebugUnitTest assembleDebug`: PASS; combined camera and BLE app rebuilt successfully.
- ML Kit integration `ConsentCam\\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --console=plain`: PASS; 48 tests passed, 0 failed, debug APK assembled, and lint completed with 0 errors.
- Preview-mask integration `ConsentCam\\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --console=plain`: PASS; 53 tests passed, 0 failed, debug APK assembled, and lint completed with 0 errors.
- Non-avatar integration `ConsentCam\\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --console=plain`: PASS on 2026-09-13 00:24 IST; 87 JVM tests passed, 0 failures/errors, debug APK assembled, and lint completed with 27 warnings/0 errors.
- Whisper integration `ConsentCam\\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --console=plain`: PASS on 2026-09-13 00:51 IST; 89 JVM tests passed, 0 failures/errors, debug APK assembled, and lint completed with 27 warnings/0 errors.
- Final APK SHA-256: `E2E03FEECEF95D8B026F15531BEE50C16F97E177591535019D32AE2FA26EF906` (179,688,352 bytes); packaged `libwhisper.so` is ARM64 and the merged manifest has no Internet or network-state permission.
- QNN/HTP integration `ConsentCam\\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --console=plain`: PASS on 2026-09-13 01:14 IST; 92 JVM tests passed, 0 failures/errors, debug APK assembled, and lint completed with 30 warnings/0 errors.
- QNN APK SHA-256: `9FF5B833A89A203E3D1351981240BAF3949F98F3B6FD6A72A15029CFD77B6E44` (138,555,755 bytes); APK is ARM64-only, packages QNN HTP V79/V81 plus delegate JNI, and the merged manifest has no Internet or network-state permission.
- Device tests not run because `adb devices -l` reports no attached devices.

### Files and interfaces changed

- Added `ConsentCam/app/src/main/java/com/consentcam/ble/api` public BLE/proximity contracts.
- Added isolated `protocol`, `security`, `proximity`, `session`, `broadcast`, `scan`, and `enrollment` production packages under `com.consentcam.ble`.
- Added dependency-neutral privacy contracts, decision coordinator, and blur-region generator under `com.consentcam.privacy`.
- Added matching protocol, security, proximity, session, enrollment, privacy-decision, blur-region, and end-to-end core pipeline tests.
- Added `ConsentCam/app/src/main/java/com/example/consent_cam/ble/BleConsentController.kt` and wired its state/controls into the Compose dashboard and camera badge.
- Added Android BLE permissions/capability declarations; all BLE/privacy core sources and tests now live in the canonical `ConsentCam/app` module.
- Added `com.example.consent_cam.vision` with the offline ML Kit adapter, normalized coordinate mapper/state, mapper tests, and a live CameraX detection overlay.
- Added the bundled `com.google.mlkit:face-detection:16.1.7` dependency and connected `ImageAnalysis` to the camera controller.
- Added `com.example.consent_cam.privacy` integration and `privacy.rendering` preview-mask state, smoothing, mosaic renderer, documentation, and deterministic tests.
- Updated Rahul's block in `progress.md`.
- Added multi-profile BLE/recognition contracts, rotated-mask geometry/shader propagation, privacy-zone presets, foreground BLE runtime/service, model SAF importer, thermal governor, voice command parser, narration, and their deterministic JVM tests.
- Added `com.example.consent_cam.voice` with ARM64 whisper.cpp capture/transcription, strict `ggml-base.en.bin` size/SHA-1 validation, private import and temporary-audio cleanup, Settings status/controls, and model metadata tests.
- Added `com.example.consent_cam.hardware.AcceleratorPolicy`, Qualcomm QNN runtime/delegate packaging, FaceNet HTP initialization and runtime fallback, QNN license notice, Settings accelerator reporting, and deterministic fallback-policy tests.

### Blockers

- Real two-phone validation requires both iQOO phones to be attached or the APK to be installed manually.
- Face-box alignment, detector latency, camera switching, and multi-face behavior require validation on the iQOO hardware.
- Gemma binaries remain external because their license must be accepted and the E2B/E4B files are 3.7/4.9 GB; runtime initialization cannot be validated until a model is imported on a phone.
- Generic Gemma 3n LiteRT-LM artifacts provide GPU/CPU, not Qualcomm NPU. NPU needs the target SoC's model plus QAIRT and LiteRT dispatch artifacts.
- QNN HTP library packaging is build-verified, but full FaceNet graph delegation, latency, thermals, and the actual V79/V81 selection require a physical iQOO 15 and Qualcomm profiling; the app reports fallback instead of claiming NPU after initialization/invocation failure.
- ML Kit does not expose a force-NPU or backend-reporting API. Replacing it with a controllable NPU detector would require a separately integrated detector, tracking, pose estimation, and post-processing pipeline.
- Whisper's current `ggml-base.en.bin` is a whisper.cpp CPU/NEON artifact; NPU speech requires a Qualcomm-converted encoder/decoder/tokenizer package and cannot be enabled by a runtime switch.
- The 147,964,211-byte Whisper `ggml-base.en.bin` model is intentionally external; microphone capture, transcription latency, command accuracy, and silent voice-started recording control require ARM64 iQOO hardware validation after import.
- Authenticated precise biometric exchange still requires the QR trust step. Ordinary BLE consent is zero-contact/no-QR; silently removing authentication would make face-profile transfer vulnerable to active BLE interception.

### Handoffs

- Rahul is integrating the outstanding Ganesh/Yazeen-facing app features on this branch at the product owner's request; their deterministic privacy and recognition constraints remain authoritative.
- Ganesh needs normalized, timestamped `BlurRegion` values.
- Yazeen needs aligned face inputs associated with a stable `trackingId`.
- Yazeen's eventual `FaceMatchState` must map to Rahul's dependency-neutral `FaceConsentResolution` at the integration boundary.
- Hardware owner should run two/three-phone BLE, rotated face-mask, protected photo/video, offline speech/TTS, model import, and ten-minute thermal checks before demo sign-off.
<!-- AI:RAHUL:END -->

## Yazeen — enrollment and recognition foundation

<!-- AI:YAZEEN:START -->
- **Branch:** `feature/yazeen-recognition`
- **Status:** READY_FOR_DEVICE_VALIDATION
- **Current task:** Validate FaceNet latency, GPU selection, thresholds, enrollment, and selective protection on the two iQOO phones.
- **Last updated:** 2026-09-12 23:43 IST

### Completed

- [x] Bundled and hash-pinned the offline FaceNet-512 model with Apache-2.0 source/reference attribution.
- [x] Implemented aligned in-memory crops, FaceNet preprocessing, normalized 512-float embeddings, and immediate crop/buffer clearing.
- [x] Implemented guided five-pose enrollment with deterministic quality/yaw/roll checks and sample-consistency rejection.
- [x] Implemented AES-GCM owner-profile storage using a non-exportable Android Keystore key and explicit profile deletion.
- [x] Implemented exact cosine matching with all confidence states; ambiguity and errors remain fail-closed.
- [x] Implemented one-decision-per-ML-Kit-tracking-ID caching, eviction on track exit, and invalidation on session change.
- [x] Added LiteRT GPU delegate execution on a dedicated thread with XNNPACK CPU fallback and an in-app backend/latency indicator.
- [x] Added an on-camera similarity score readout for hardware threshold calibration without logging embeddings or images.

### In progress

- [ ] Run the instrumentation suite and live multi-face flows on iQOO hardware.
- [ ] Collect genuine/impostor similarity evidence under demo lighting and replace the provisional 0.80 threshold if required.
- [ ] Measure FaceNet latency/memory and confirm Settings reports GPU or the documented CPU fallback.

### Next

- [ ] Decide whether secure precise-profile exchange keeps QR authentication or adopts a separately designed authenticated no-QR protocol; normal BLE consent already requires neither QR nor internet.
- [ ] Calibrate on the actual teammates and phones before treating any face match as release-ready.

### Tests and results

- `./gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --console=plain` — PASS (71 JVM tests, 0 failures/errors; debug APK built; lint completed with 25 non-blocking pre-existing/version/resource warnings).
- Device instrumentation tests are present for the bundled model, normalized 512-vector output, Android Keystore round-trip, and offline QR decode, but were not run because no phone is attached in this environment.

### Files and interfaces changed

- Recognition: `LiteRtFaceEmbedder`, `ExactFaceMatcher`, `FaceRecognitionController`, `TrackingMatchCache`, `FaceEnrollmentController`, `EnrollmentPosePolicy`, and `EmbeddingMath`.
- Integration/UI: Settings now reports actual FaceNet backend and last inference latency; camera status exposes the match score for calibration.
- Build/assets/tests: LiteRT GPU dependency, FaceNet model notice update, deterministic cache/pose/decision unit tests, and ignored Gradle Kotlin session artifacts.

### Blockers

- Actual iQOO GPU compatibility, FaceNet latency/memory, camera alignment, and recognition threshold evidence require physical-device testing.
- Secure precise face-profile transfer currently uses QR authentication; removing all prior contact requires an explicit authentication/privacy design decision and is not equivalent to normal unauthenticated BLE consent broadcasting.

### Handoffs

- Rahul/Ganesh can install `app-debug.apk` and run the existing device checks; the live camera remains broadly protected until a unique `MATCHED_PROTECT` track is resolved.
- Do not add Gemma/LLM identity decisions: close matches remain `AMBIGUOUS` and protected by deterministic policy.
<!-- AI:YAZEEN:END -->

## Ganesh — camera, recognition polish, and local AI

<!-- AI:GANESH:START -->
- **Branch:** `feature/ganesh-camera-agent`
- **Status:** IN_PROGRESS
- **Current task:** Restore two-phone BLE voice discovery, allow ordinary recording when no face or voice protection is active, and assess overlap voice-leak reduction without losing the retained speaker.
- **Last updated:** 2026-10-11 10:47 IST

### Combined Cam + Voice integration (2026-10-11)

- The sender accidentally cleared its combined-app Voice Passport, explaining why the recorder saw a BLE voice request but no received profile and published a silent safety video. The user re-enrolled; read-only device checks found the new encrypted Passport on the sender, and the recorder's Protected camera now shows `Voice requests: 1 · received profiles: 1` after reinstall/relaunch. The recorder currently sees no face in frame, so saved face masking still needs a person-in-frame check.
- Protected capture now rejects missing/invalid or multiple voice profiles before recording; failed NPU, matching, or audio processing no longer publishes a silent `PROTECTED_` video. It still never publishes the original microphone track as a fallback. Added four readiness tests, fixed an exact-capacity mux-buffer check, and clear the processed PCM even if AAC encoding throws. `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-configuration-cache` and `git diff --check` passed; latest APK installed in place on the recorder without clearing data. A new private real-voice clip and listener review remain necessary: this change addresses silent export, not residual protected-voice leakage during overlap.
- Added BLE v2 voice-request/profile flags and versioned encrypted GATT payload carrying only four Voice Passport embeddings (no raw audio). Nearby sender can select MUTE VOICE or FACE + VOICE; face-only v1 remains accepted. Receiver keeps speaker profiles in RAM and snapshots in-zone consent at shutter time.
- Normal camera video now stages privately when nearby voice consent is active. Protected mode accepts a nearby Voice Passport without requiring enrollment on the recording phone. Two voice-protected identities, a missing profile, failed NPU/match, or invalid audio yield a completely silent replacement AAC track, never the original microphone track. Face protection is frozen at recording start and face-required capture waits for a matched mask.
- Added a confirmation-gated Remove Voice Passport action in Consent Audio. It deletes only the encrypted passport and Android Keystore key; existing sanitized exports remain. BLE advertising refreshes after returning to the combined app.
- `:app:assembleDebug :app:testDebugUnitTest` and `:app:lintDebug` passed after the local-enrollment gate fix. Updated sender/receiver APKs installed in place on two iQOOs; after the gate fix, recorder `10BFAT1S0E000Z9` was updated again. It has no local Passport; sender `10BFAT1U06000Z9` has one, and both have FACE + VOICE selected. Waiting for the user's two-phone recording check and received-profile counts. No private media, models, or embeddings were committed.
- BLE scanner now automatically restarts after scan failure or 45 seconds without a valid advertisement, with a retry cooldown. The BLE dashboard scrolls and shows request/profile counts and encrypted-transfer status. Rapid gyroscope/linear-acceleration motion triggers 1.2 seconds of whole-frame pixelation; if a face-required recording temporarily loses its protected region, whole-frame pixelation remains until a region is reacquired. This intentionally hides non-protected faces briefly rather than exposing a protected face.
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --no-configuration-cache` passed. APK installed in place on both iQOOs without clearing app data. Recorder dashboard showed scanning and advertising ON, one nearby face and voice request, one received face and voice profile. Protected camera showed one protected-person match and one received voice profile; no shader/camera error was observed on opening. Intermittent third-party GATT failure status was also seen while the valid profile remained received. A private, real-motion saved-video review is still required before claiming the shake leak fixed.
- At the user's request, removed the motion sensor guard, whole-frame shader path, and BLE auto-retry to restore the previous face-only mask behavior. Rebuilt with `:app:assembleDebug :app:testDebugUnitTest --offline --no-configuration-cache` (passed) and installed in place on both iQOOs. Enrolled app data was not cleared. The voice separator/redaction code was unchanged by this rollback, so the user's newly reported severe audio distortion remains unverified/unresolved. Do not treat Protected exports as share-ready until a new private clip is listened to; rapid camera movement can again reveal the protected face briefly.
- Found source-aware redaction allowed the retained separated stream to replace protected overlap even when that stream itself matched or was uncertain against the Voice Passport. Changed this gate to safety-mute such spans, and to withhold uncertain protected-source/mixed decisions. Added deterministic gate tests; `:app:assembleDebug :app:testDebugUnitTest --offline --no-configuration-cache` passed and recorder `10BFAT1S0E000Z9` was installed in place. This is not yet validated against a new real recording; severe NPU separation distortion and below-threshold leakage remain possible. Do not share a Protected export until listened to privately.
- User tested `PROTECTED_20261011_092251.mp4` and reported Rahul's voice was not audible. The broad residual/uncertain gate above was too aggressive and has been removed, restoring the prior 16 kHz gentle retained-speaker path. `:app:assembleDebug :app:testDebugUnitTest --offline --no-configuration-cache` passed and recorder APK installed in place. This again prioritizes Rahul audibility over a zero-leak guarantee; protected-voice residue is unresolved. Do not describe this as sanitized or share-ready until separately validated.
- User reports distorted protected-voice residue in the first two seconds of `PROTECTED_20261011_092548.mp4` (7,872 ms), with both speakers talking simultaneously. Code review confirms the app estimates two streams from one mixed microphone input and selects the stronger Passport stream globally; its gentle coherent-leakage suppressor does not guarantee zero residue in the retained stream. The broad residual-match gate previously removed Rahul too. No new code change was made for this diagnosis; investigate selective overlap cleanup/model quality with a new private capture before claiming both privacy and retained-speaker intelligibility.
- A new `PROTECTED_20261011_092952.mp4` (10,368 ms) shows the same user-reported first-two-second protected-voice residue, with later audio acceptable. The initial 2-second QAIRT block and whole-clip source assignment are plausible causes, but the published MP4 contains only the final mix, not the transient A/B streams or per-window scores, so the exact mechanism cannot be established from this export alone. No further mute rule was applied because the last such rule suppressed Rahul; a new in-memory per-window diagnostic/controlled A/B test is needed before changing the separator path.
- Later both iQOOs showed Bluetooth ON and FACE + VOICE with advertised/scanning ON, yet zero nearby sessions; the sender's encrypted Passport file remained present. Restarting each app process (no data clearing) restored advertisements and reception: recorder subsequently showed one nearby voice request and one received voice profile. Added BLE-only recovery after scan failure or 45 s without valid advertisements, plus a real dashboard retry that restarts the controller even when scanning incorrectly reports ON. Camera blur, NPU separation, and audio replacement remain unchanged. `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --no-configuration-cache` passed; updated APK installed in place on both iQOOs. Automatic recovery after a real future stall remains to be observed.
- User reports Rahul's face intermittently blurred in both preview and saved output, including when Rahul is alone in frame; broadcaster's enrolled face is reported to be the protected user's, and user confirmed no third PROTECT broadcaster. Recorder in Enhanced mode sampled one-face statuses from `Match uncertain - score 0.705` through `0.873` and `no face is blurred`. Code audit found that the camera keeps old received profiles across BLE gaps, while sender restarts create new session IDs: duplicate copies of one face make the selector expect multiple distinct protected faces. Added exact model/embedding duplicate detection (not score-based) and atomically removed prior face/voice session copies when the new face profile arrives. No threshold, blur, or audio changes. Two new unit tests passed (same enrollment/new session deduped; close-but-different enrollment retained). `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-configuration-cache` passed; updated APK installed in place on recorder `10BFAT1S0E000Z9` without clearing profiles; `git diff --check` passed with line-ending warnings only. Rahul-alone and two-face physical preview/saved-video verification is still pending; high impostor scores might indicate an additional false-match issue, so do not claim this fully fixes selective blur or share exports yet.
- User reports the retained friend's speech is clearer but their protected voice remains audibly distorted in overlap. The 16 kHz coherent-leakage suppressor was fixed at gentle 0.45 strength; stronger blanket source muting previously erased the friend. Added a constructor-controlled suppression strength and set Protected video alone to 0.85, retaining standalone Consent Voice's approved 0.45 default. Reduced the suppressor's minimum per-bin gain from 0.20 to 0.08 so strongly coherent leaked components can be attenuated further; the old 0.45 path cannot reach this floor. New synthetic two-speaker test passed: stronger cleanup reduces the shared component while keeping the independent component above 0.9 projection. `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-configuration-cache` passed; recorder APK installed in place without clearing enrollment. This is an experimental improvement, not proof of protected-voice removal or real retained-speaker quality. Require a fresh private two-person Protected recording and listening before sharing; do not claim the previous clips were repaired.
- Real clip after the stronger cleanup failed privacy validation: the user still hears their voice only during overlap in the latest `PROTECTED_20261011_095203.mp4` (8,128 ms). MediaStore confirms it is a Protected export. Code audit of the muxer confirms it copies only the staged video track and writes a newly encoded sanitized AAC track, so the original microphone audio is not accidentally retained. The residual voice is separator bleed in the kept stream; the synthetic tone test was insufficient to predict human overlap quality. No further blind gain/threshold change was made. Requested a decision among fail-safe muting of risky overlap (Rahul may go quiet there), retaining Rahul with explicit review warning (leak remains), or blocking export pending a better model. Do not share this clip or claim the protected voice is removed.
- User clarified that Rahul must remain audible throughout, while the lower-level distorted protected residue during overlap should be attenuated. Added an opt-in spectral-dominance term to the 16 kHz cleanup mask: Protected video now attenuates bins dominated by the removed stream even when phase distortion lowers cross-stream coherence. Standalone Consent Voice remains on the previous coherence-only default; no whole-overlap mute or BLE/camera changes. Added a synthetic phase-flipped leakage test plus retained-tone bound; `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-configuration-cache` passed. Installed recorder APK in place without clearing enrollment. This is not real-voice validation: a new private two-person Protected capture and listener review are mandatory, and the model may still bleed voice or degrade Rahul.
- User reports their louder protected speech is suppressed more effectively, implying quiet-source leakage remains. Added opt-in, capped (2.5x) per-STFT-frame upward level matching in Protected video cleanup, leaving standalone Consent Voice unchanged. The first synthetic quiet-source test showed reduced protected-tone projection but a 5% increase in total quiet-frame error; test now requires lower protected-tone projection, retained-speaker projection >0.85, and total error no more than 10% above the prior fixed-level cleanup. All three synthetic cleanup tests pass. Full `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-configuration-cache` passed; APK installed in place on recorder without clearing profiles. This is a bounded experiment, not demonstrated real voice privacy; a new private quiet-protected-voice plus Rahul overlap clip must be listened to before sharing.

- Added a three-choice landing screen: Consent Cam, Consent Audio, and Protected video. The Voice UI/model/native runtime are reused in the combined APK through `voice-feature`, `voice-consent`, and `face-guidance`; the standalone voice app remains separate.
- Protected recording now requires an active face mask and a Voice Passport enrolled in this APK. CameraX stages the original MP4 in app-private cache; the exporter runs offline QAIRT/HTP separation and Passport matching, replaces AAC audio, and publishes only the remuxed video. If matching, NPU processing, or muxing fails, no original is published. Review shows protected-only, separated-overlap, and safety-muted counts plus a leakage warning.
- `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache` passed. Combined NPU smoke test passed on `10BFAT1S0E000Z9` (1 test); FaceNet synthetic embedding test passed on `10BFAT1U06000Z9` (1 test). The updated APK was installed in place on both phones; both pre-existing face-profile files remain present, and the first phone's newly enrolled Voice Passport remains present. The second phone has no Voice Passport yet.
- The first phone published an 8-second Protected MP4 at 08:14 IST (`PROTECTED_20261011_081418.mp4`, 7,150,357 bytes, 582x1280). The user reports their audio is muted and the video looks all right. Saved-pixel face-mask placement and retained-friend audio still need a controlled two-person check. A later private staged file exists in the first phone cache; its origin and whether the corresponding export completed need confirmation before touching it.
- Added a read-only instrumentation check for an existing owner profile. Both connected iQOOs passed decryption and normalized-embedding validation (1 targeted test each); no face re-enrollment is required for these app installs. The first iQOO with a Voice Passport will record; the second must broadcast the same person's enrolled face in PROTECT mode for the combined face/voice test.
- Live two-phone BLE/face check: both phones retained PROTECT, Bluetooth, and BLE runtime permissions. Second phone advertised/scanned and accepted 25 PROTECT tokens. Recorder initially reported `BLE scan failed (2)` and advertising/scanning OFF after process restart; app retry alone did not recover. With user approval, Bluetooth was cycled on the recorder and BLE retried. Its Protected camera then reported `Enhanced protection active • 1 face(s)` and `1 protected person matched - score 0.836`. Saved Protected face pixels still require the new clip and human inspection. The extra 08:15 private raw cache file must remain untouched at the user's request.
- User reported face blur but unmuted audio in the latest video. MediaStore confirmed the two 08:27 captures were `CONSENT_20261011_082704.mp4` and `CONSENT_20261011_082738.mp4` (normal Consent Cam mode), not `PROTECTED_` exports. The assistant's BLE-dashboard navigation left the recorder in the normal camera. No audio replacement is expected for these two clips; a new recording explicitly from Protected mode is needed. Do not delete the user's normal recordings.
- Three subsequent Protected exports were published at 08:30, 08:30, and 08:31 IST. For `PROTECTED_20261011_083125.mp4`, MediaStore reports an 8,000 ms, 4,957,822-byte finalized 582x1280 MP4. The user confirmed the saved face remains blurred, the other speaker audible, and the enrolled voice mostly removed; brief leakage is present but acceptable for a reviewed demo. The recorder cache retained only the earlier 08:15 private raw file after these exports; user explicitly requested keeping it. The UI now labels normal Consent Cam video as “audio unchanged,” and shows protection counts on Protected review. Latest APK installed in place on both phones, preserving app data. `:app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` passed after the UI change; `git diff --check` passed with only line-ending warnings.

### Completed

- [x] Kotlin/Compose dashboard, privacy settings, iQOO-inspired camera shell, CameraX preview/photo/video controller, and local media-save paths added under the untracked `ConsentCam/` Android Studio project.
- [x] Capture feedback, saved-media thumbnail, and in-app local media preview added to the CameraX shell.
- [x] Video recording timer, saved-video thumbnail, and native-gallery handoff from the camera thumbnail added.
- [x] Added BLE consent status feedback plus a Start / retry BLE action, and installed the updated debug build on the connected iQOO 15.
- [x] Replaced OriginOS-incompatible UUID-only BLE scan filtering with an unfiltered scan plus strict local service-data and protocol validation.
- [x] Added a CameraX protected-output effect targeting preview, saved photos, and recorded videos; protected capture fails closed when no mask is available.
- [x] Replaced the opaque overlay with a custom GLES `SurfaceProcessor` shader that samples and pixelates original camera pixels only within active face regions.
- [x] Tuned ML Kit for accurate 960x720 multi-face analysis with smaller-face detection, made tracking resilient to ID churn and short misses, and changed the shader to a transformed oval face mask with face-relative pixel blocks.
- [x] Unified CameraX preview/analysis/photo/video under the PreviewView `ViewPort` and normalized detections against the actual rotated crop rectangle for WYSIWYG masks.
- [x] Improved FaceNet crop alignment using the detected eye line with deterministic mirrored-front-camera handling and ML Kit pose fallback.
- [x] Pinned LiteRT-LM Android 0.16.1 plus the official Gemma 3n E4B/E2B filenames, byte sizes, and source revisions without committing multi-gigabyte models.
- [x] Added an offline-only E4B-to-E2B, GPU-to-CPU runtime, serialized privacy-audit inference, visible model/backend/latency metrics, and `Cloud calls: 0` status.
- [x] Pulled Rahul's merged BLE, QNN fallback, voice, and recognition refinements into `main` and added `panel.md` as the current device-validation handoff.

### In progress

- [x] Compile the CameraX baseline with `:app:assembleDebug`.
- [ ] Run the CameraX baseline on the iQOO 15 and verify permissions, photo capture, video capture, and front/back switching.
- [x] Add the output-protecting CameraEffect after Rahul provides timestamped `BlurRegion` values.
- [ ] Inspect saved protected photo/video pixels and mask alignment on the iQOO 15; no device was attached when the APK installation was attempted.
- [ ] Verify real GPU shader compilation, front/back alignment, saved photos, and saved video on iQOO hardware; no physical iQOO is currently attached.
- [ ] Validate the accuracy-tuned build on the iQOO 15; the connected phone is currently unauthorized for USB debugging.
- [ ] Copy an accepted-license E4B or E2B `.litertlm` file into the Settings-displayed phone folder and run the local audit.

### Next

- [ ] Verify CameraX preview/photo/video/settings app on an Android 16/iQOO 15 device.
- [x] Implement protected CameraEffect for every output.
- [x] Integrate recognition flows with cancellation.
- [x] Pin LiteRT-LM and E4B/E2B model identities; provision the licensed binary separately on the phone.
- [x] Implement honest GPU/CPU probing and serialized inference; NPU requires separate Qualcomm artifacts.
- [ ] Implement voice tools, confirmations, TTS, audits, and AI panel.

### Tests and results

- `:app:assembleDebug --no-daemon --console=plain` passed on 2026-09-12 16:21 IST: 36 actionable tasks, 7 executed, 29 up to date.
- `:app:assembleDebug --no-daemon --console=plain` passed on 2026-09-12 16:32 IST: 36 actionable tasks, 9 executed, 27 up to date. Updated APK installed to the connected iQOO device through ADB.
- `:app:assembleDebug --no-daemon --console=plain` passed on 2026-09-12 16:38 IST: 36 actionable tasks, 9 executed, 27 up to date. Updated APK installed to the connected iQOO device through ADB.
- `:app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` passed on 2026-09-12 18:28 IST: 42 actionable tasks, 7 executed, 35 up to date. Updated APK installed to the connected iQOO device through ADB.
- `:app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` passed on 2026-09-12 18:35 IST: 42 actionable tasks, 13 executed, 29 up to date. Updated APK installed to the connected iQOO device through ADB.
- `:app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` passed on 2026-09-12 21:58 IST: 42 actionable tasks, 12 executed, 30 up to date. Protected-output APK assembled; physical install was not possible because no iQOO device was attached.
- `:app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` passed on 2026-09-12 22:14 IST: 42 actionable tasks, 13 executed, 29 up to date. GPU shader source compiled into the debug APK; runtime shader compilation remains pending iQOO hardware validation.
- `:app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` passed on 2026-09-12 22:24 IST: 42 actionable tasks, 13 executed, 29 up to date; 54 unit tests passed with zero failures. Accuracy, tracking, and face-mask improvements assembled into the debug APK.
- The optimized APK installed and opened its CameraX screen on the Android 16 emulator on 2026-09-12 22:27 IST with no app crash or shader compile/link error; real-camera alignment and performance still require the iQOO 15.
- Android SDK Platform 36 was partially installed without `android.jar`. The project now compiles against the valid local API 37 platform while retaining `targetSdk = 36` for the iQOO 15 / Android 16 runtime target.
- `./gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon --console=plain` passed on 2026-09-13 00:02 IST: 78 unit tests, zero failures/errors, APK assembled with LiteRT-LM JNI, and lint completed with 26 non-blocking dependency/resource/API warnings.
- Post-merge `:app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain` was attempted on 2026-09-13 01:47 IST but could not resolve newly merged Whisper/QNN/LiteRT dependencies because this workstation had no route/DNS access to Maven Central and Google's Android Maven repository. This is not a source-validation result; rerun with network access or a warmed Gradle cache.

### Files and interfaces changed

- `ConsentCam/app/src/main/java/com/example/consent_cam/MainActivity.kt`
- `ConsentCam/app/src/main/java/com/example/consent_cam/camera/ConsentCameraController.kt`
- `ConsentCam/app/src/main/java/com/example/consent_cam/privacy/rendering/ProtectedOutputEffect.kt`
- `ConsentCam/app/src/main/java/com/example/consent_cam/privacy/rendering/FaceRegionSmoother.kt`
- `ConsentCam/app/src/main/java/com/example/consent_cam/privacy/rendering/README.md`
- `ConsentCam/app/src/main/java/com/example/consent_cam/vision/MlKitFaceDetector.kt`
- `ConsentCam/app/src/test/java/com/example/consent_cam/privacy/rendering/FaceRegionSmootherTest.kt`
- `ConsentCam/app/build.gradle.kts`, `ConsentCam/gradle/libs.versions.toml`, `ConsentCam/app/src/main/AndroidManifest.xml`
- Added `com.example.consent_cam.agent` model catalog, privacy snapshot, LiteRT-LM controller, setup guide, and catalog/prompt tests.
- Updated `FaceCoordinateMapper`, `MlKitFaceDetector`, and `ConsentCameraController` for crop-aware WYSIWYG geometry; added eye-line alignment and deterministic geometry tests.
- Settings now contains a local-AI initialization/audit panel reporting the real model/backend/inference metrics and no cloud calls.

### Blockers

- Gemma 3n E4B/E2B model binaries are license-gated and 4.9/3.7 GB respectively; one must be downloaded after accepting the model license and provisioned to the phone.
- Qualcomm NPU execution is not part of the generic Gemma 3n package: it needs an SoC-compatible model, QAIRT libraries, and LiteRT dispatch build before it can be tested or claimed.
- The connected iQOO 15 must authorize this computer for USB debugging before the optimized APK can be installed and exercised.

### Handoffs

- Needs Rahul's `BlurRegion` and `PrivacyEvent` flows.
- Needs Yazeen's fake and real `FaceMatcher` implementations.
- The LLM consumes counts/modes only and cannot alter recognition or blur decisions; ambiguity remains deterministically protected.
- `panel.md` is the practical resume guide: it documents the two modes, current code boundaries, two-phone demo steps, and validation still required.
<!-- AI:GANESH:END -->

## Shared Integration Status — Rahul-owned

<!-- AI:INTEGRATION:START -->
### Interface compatibility

- [x] Shared Kotlin types exist in a dependency-neutral module/package.
- [x] Rahul's detector output can feed Yazeen's recognition input.
- [x] Yazeen's `FaceMatch` can feed Rahul's decision coordinator.
- [x] Rahul's `BlurRegion` can feed Ganesh's output effect.
- [x] Counts-only privacy snapshots can feed Ganesh's agent without biometric data.

### Pending merges

- None.

### End-to-end readiness

- [ ] Camera baseline
- [ ] BLE discovery
- [ ] Rotating-token verification
- [ ] Normal Proximity Mode
- [ ] Face enrollment
- [ ] Precise face matching
- [ ] Protected preview
- [ ] Protected saved photo
- [ ] Protected recorded video
- [ ] E4B initialization
- [ ] E2B fallback
- [ ] Voice agent
- [ ] English/Tamil narration
- [ ] Structured privacy audit
- [ ] Three-phone selective-blur demonstration
- [ ] Ten-minute thermal rehearsal
- [ ] Offline demonstration rehearsal

### Phone readiness

| Phone | Role | App ready | BLE ready | Recognition ready | LLM model/backend | Demo ready |
|---|---|---|---|---|---|---|
| A | Recorder | No | No | No | Not tested | No |
| B | `ALLOW` broadcaster | No | No | No | Not tested | No |
| C | `PROTECT` broadcaster | No | No | No | Not tested | No |

### Known demo risks

- E4B and Qualcomm NPU compatibility are unverified on the issued iQOO hardware.
- Regional blur must affect encoded photos/video, not only preview UI.
- RSSI cannot associate a protect beacon with one person in a multi-face scene.
- Unknown faces remain visible by explicit product choice.

### Integration blockers

- No phone is attached; real BLE, GPU shader, saved-media, voice/TTS, model backend, thermal, and threshold validation remain outstanding.
- Licensed E4B/E2B and Qualcomm-specific NPU artifacts are not present in the repository.
- Precise profile transfer remains QR-authenticated; normal BLE consent has no pairing or QR requirement.
<!-- AI:INTEGRATION:END -->
