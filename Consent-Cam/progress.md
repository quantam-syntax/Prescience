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
- **Current task:** Hand off the merged camera, recognition, BLE, and local-AI state for two-phone iQOO validation.
- **Last updated:** 2026-09-13 01:47 IST

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
