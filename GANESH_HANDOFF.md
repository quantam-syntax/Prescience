# Privacy Suite — Ganesh's implementation handoff

Prepared: 9 October 2026. Team: Atreides. Working product/repository name: `privacy-suite`.

## 1. Goal and team ownership

Build one Android app where a person enrolls their identity, chooses consent preferences, uses a consent-aware camera, and records/exports audio through compatible privacy features.

| Owner | Owns now | Must coordinate with |
| --- | --- | --- |
| Ganesh | Repository, Gradle project, main app, enrollment/settings UI, Consent Cam integration, emoji/avatar creator experience, integration builds | Rahul for enrollment/BLE; Yazeen for audio processing |
| Rahul | Voice enrollment implementation, BLE discovery/signalling, session/profile exchange | Yazeen for exact speaker model and preprocessing |
| Yazeen | Speaker matching, protected speech timestamps, redaction and audio export engine | Rahul for profile format; Ganesh for recorder integration |

Voice Consent is the main finale feature. The camera is a supporting extension of the Chennai concept. Document/sensitive-information redaction is deferred until the voice path works. Yazeen's current redaction task is **audio redaction**, not document redaction.

Ganesh can begin with fake service implementations. Model selection must not block app navigation, settings, camera work, or repository setup.

## 2. Application decision

Use **one installable APK** for the first integrated build, with a Camera screen inside it. Treat the camera as a feature/library module. If the Chennai camera is currently a separate Android app, preserve its source/history and gradually extract its reusable code; avoid two competing application modules for the initial integrated demo.

On Phone A, the app acts as the protected person's controller/broadcaster. On Phone B, the same app acts as the cooperating camera/audio recorder. These are roles, not separate apps. One person can select either role.

This custom camera/recorder must enforce the policy. The suite cannot modify the stock iQOO camera, arbitrary recorders, calls, or recordings already held by other apps. BLE announces consent; it does not interfere with microphones or identify which visible face belongs to a nearby phone.

## 3. Repository and Gradle layout

Create one repository and one root Gradle build:

```text
privacy-suite/
  app/                         # Only com.android.application module
  core/consent/                # Shared domain types and interfaces
  core/storage/                # Profile/settings persistence implementations
  feature/camera/              # Consent Cam + creator effects
  feature/voice-ui/            # Enrollment/recorder/redaction screens
  modules/voice-consent/       # Rahul's Android library
  modules/voice-redaction/     # Yazeen's Android library
  docs/
    GANESH_HANDOFF.md
    VOICE_DECISIONS.md
    INTEGRATION_CONTRACT.md
  gradle/libs.versions.toml
  settings.gradle.kts
  build.gradle.kts
  README.md
  .gitignore
```

Gradle paths: `:app`, `:core:consent`, `:core:storage`, `:feature:camera`, `:feature:voice-ui`, `:modules:voice-consent`, `:modules:voice-redaction`. Include these in `settings.gradle.kts`. Empty feature modules should compile with stubs rather than contain broken placeholder imports.

Dependencies: app assembles the modules; voice UI and both voice implementations depend on core consent. Neither voice implementation depends on the other. Shared interfaces stay in core consent; storage implements core interfaces. Avoid circular module dependencies. Camera identity/matching internals may remain camera-specific until reviewed.

Start from Android Studio's current stable Empty Activity/Compose template. Use Kotlin, Compose Material 3, ViewModel, coroutines/StateFlow, Navigation Compose, DataStore for small settings, and CameraX for camera work. Use app-private files for media. Prefer manual constructor injection for the sprint. Add Room only if record/session queries actually need it.

Choose and record an explicit package ID, e.g. `com.atreides.privacysuite`. Suggested minimum SDK: 26; confirm all three demo phones meet it. Use the installed stable compile/target SDK and template-compatible Kotlin/AGP/Compose versions; commit their exact versions, Gradle wrapper, and version catalog. Do not independently upgrade dependencies on feature branches.

### Git workflow

- Ganesh makes the initial compiling scaffold on `main` before everyone branches.
- Branches: `feature/app-camera`, `feature/voice-consent`, `feature/voice-redaction`.
- Put Consent Cam in a module/folder, not only a long-lived branch. Branches are temporary workspaces; merged source must remain in the main repository.
- Ganesh coordinates edits to root Gradle files, version catalog, navigation and shared contracts. Rahul/Yazeen primarily edit their modules.
- Use small pull requests and merge a compiling integration build frequently. No microphone recordings, enrolled identities, secrets, signing keys, `local.properties`, or build outputs in Git. Add model weights only after checking redistribution permission and repository size; otherwise document a pinned download/checksum.
- Record the Chennai baseline commit and what was newly built during the finale. Confirm the event's reuse rules before counting pre-existing work as a finale deliverable.

## 4. First screens and their behavior

| Screen | Build now | Integration later |
| --- | --- | --- |
| Home | Face/voice enrollment status, consent controls, Camera and Voice Recorder actions | Real service health and session acknowledgement |
| Identity | Face enrollment entry point, voice enrollment record/retry/delete, emoji selection, avatar picker/preview | Model-based enrollment quality checks |
| Consent settings | Face mode: allow/blur/emoji/avatar; voice mode: allow/mute; persist selections | Publish updated policy through Rahul's service |
| Nearby/session | Start/stop, mock peer list, pending/accepted/expired states | BLE discovery + explicit authorized profile exchange |
| Camera | Existing camera integration, creator effects, capture/export | Real identity-policy association |
| Voice recorder | Start/stop, playback, run redaction, processing/result/error state | Yazeen's engine + Rahul's received profiles |
| Activity | Local events with clear mock/real labels | Completed redaction/export events |

Expose enrollment and consent separately: enrolling a voice does not automatically permit sharing its profile. Show microphone/camera/Bluetooth permissions at the action that needs them. Handle denial and a permanently denied state without crashes.

The controller's voice-protection mode does not need continuous microphone recording. Recording enrollment or recording on Phone B does. Show `Preference enabled`, `Broadcasting`, `Peer acknowledged`, and `Export redacted` as different states. Do not display `Protected everywhere` or count discovered peers as prevented recordings.

## 5. Shared contracts Ganesh can scaffold now

These are **proposed domain contracts**, to freeze together before integration. They are not a BLE wire format or final Kotlin implementation.

| Contract | Required fields/behavior |
| --- | --- |
| Local profile | App-local opaque ID, face enrollment reference, optional voice profile reference, avatar reference, creation/version metadata |
| Consent policy | Revision, face mode, voice mode, expiry/session scope; no model-specific fields |
| Voice profile descriptor | Schema version, opaque profile ID, model ID/version, weights checksum, preprocessing version, sample rate, embedding dimensions/dtype/normalization, private embedding file reference |
| Session peer | Ephemeral session ID, capability flags, discovery state, acknowledgement revision, expiry; discovery alone is not authenticated identity |
| Audio asset | Input URI, duration, channels, sample rate/encoding metadata; decoded analysis audio is generated separately |
| Speech match | Start/end milliseconds on original recording timeline, candidate profile ID, similarity score, decision: protected/unprotected/uncertain/overlap |
| Redaction result | New sanitized output URI, applied segments, warnings/unresolved regions, engine/model version, processing status |

Proposed service boundaries:

```kotlin
interface VoiceEnrollmentService {
    suspend fun enroll(sampleUri: Uri): EnrollmentResult
}

interface ConsentSessionService {
    val state: StateFlow<ConsentSessionState>
    suspend fun start(policy: ConsentPolicy): SessionStartResult
    suspend fun stop()
}

interface VoiceRedactionEngine {
    suspend fun redact(request: RedactionRequest): RedactionResult
}
```

Ganesh should define the referenced result/request/state types in core consent with success/error/cancel states. The redaction request contains an audio asset, authorized protected profiles, the captured session policy, and uncertainty/overlap handling options. Session start means the service started, not that every peer received or enforced it.

Keep these interfaces model-independent. Do not hardcode 192-dimensional embeddings, cosine thresholds, sample rate, or ONNX tensors into UI code. The exact metadata values are filled after model selection. Profiles from different weights/preprocessing versions must be rejected as incompatible.

### Required mocks

Implement `FakeVoiceEnrollmentService`, `FakeConsentSessionService`, and `FakeVoiceRedactionEngine`. Keep them switchable through a single app-level dependency provider.

- Enrollment returns `MOCK_PROFILE` metadata with no usable biometric vector.
- Session fake has one acknowledged and one expired peer, visibly labeled simulated.
- Redaction fake returns a fixture/result preview and is labeled simulated; it must not claim an unmodified recording is sanitized.
- Make loading, empty, error, retry, incompatible-profile and cancellation states exercisable.
- Release/integration builds must never silently fall back to fake protection when real processing fails.

## 6. Consent Cam and creator experience

First inspect the existing camera architecture, identity enrollment/matching, policy transport and saved-media path. Preserve working behavior before changing it. Face detection/tracking is not face recognition; an ML Kit tracking ID is not a durable enrolled-person identity. BLE proximity alone also cannot select a face. Reuse the validated Chennai matching mechanism if available; otherwise explicitly label a manually selected-face effect as a demo limitation.

Implementation order:

1. Integrate existing camera preview and current blur behavior.
2. Route each matched face's consent policy into a renderer.
3. Add emoji replacement with coordinate rotation/mirroring/crop handled correctly.
4. Add user-selected avatar images; copy them into private storage or retain valid URI access. A static image avatar is sufficient for MVP.
5. Apply effects to exported photos, then attempt exported video only after photos are verified.
6. Add an explicit fallback to blur when an opted-out face's avatar cannot load.

Do not cover only the preview and export original media. A Compose overlay above CameraX preview is not automatically burned into captured photos or CameraX Recorder video. Photo export needs actual compositing; video needs a validated frame-processing/encoding path. Verify exported files in a separate viewer. If transformed video cannot be finished, ship verified photos and call video a preview prototype.

Keep detection/processing off the main thread. Start with conservative analysis resolution and latest-frame processing. Do not block camera buffers or create overlapping analysis jobs. Reuse render assets rather than decoding an avatar every frame.

Defer animated avatars, advanced face mesh, whole-body effects and third-party camera integration.

## 7. Voice recorder flow and privacy boundaries

Recommended MVP: Phone A enrolls and explicitly authorizes a session with Phone B; Phone B records using this app; redaction runs after Stop; the app previews and shares the sanitized export. Broadcast and processing are independent stages.

The provisional BLE advertisement should contain only compact version/capability/policy/session discovery data. Do not broadcast raw voice samples, face photos or biometric embeddings. Rahul chooses an explicit authorized profile transfer channel after testing payload limits and hardware support. It may use a controlled GATT connection or another deliberate session transport; it is not yet fixed.

Enrollment and processing must use the same exact speaker encoder and preprocessing. Audio decoding/resampling and timestamp conversion belong behind services, not in Compose screens. A speaker encoder matches identities; it does not by itself produce reliable speaker boundaries or separate simultaneous speech.

Use muting as the first implemented voice mode. Pitch shifting/distortion alone may leave intelligible speech or recognizable identity. If two people overlap, muting the entire mixed segment removes both speakers; preserving the other speaker needs a separately validated separation model. Explain this in result warnings.

Fail visibly if processing is incomplete. Keep originals private; make sanitized output the default share target using a content URI/FileProvider. If an uncertain segment remains, require review or conservatively redact it according to the chosen policy. Store only necessary local events; omit embeddings and raw speech from diagnostic logs. Enrollment deletion should remove its local artifacts and authorized session copies where controlled by the app; it cannot retract data already exported to uncontrolled devices.

## 8. Android requirements to implement carefully

- Android 12+: request relevant Nearby devices permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) for the operations used. Supporting older versions may add location/legacy permission requirements. Verify the actual supported OS range.
- Request `CAMERA` for camera actions and `RECORD_AUDIO` for enrollment/recording. Use system media pickers for avatars instead of broad media access.
- Start with visible foreground screens. Background BLE operation is a separately tested capability, not an assumption.
- If recording continues with screen off, use a correctly declared microphone foreground service, notification and explicit user start/stop. Modern Android restricts starting microphone services from the background; initiate recording while the app is visible. BLE foreground-service type/permissions depend on the chosen operation.
- Verify BLE advertising support on the real phones. Test scanning/advertising, revoked permissions, Bluetooth off, reconnect, process death and expiry handling. Do not promise perpetual background execution.
- Check SDK-specific foreground-service and notification requirements against the chosen target SDK rather than copying an old manifest.

## 9. Work sequence and completion checks

### Stage A — Repository and shell

- Create the root project, wrapper, modules, README/setup steps and initial commit.
- Provide a compiling APK, navigation, role selection and persistent settings.
- Share the clone URL and exact Android Studio/SDK/JDK setup with Rahul/Yazeen.
- All three can clone and run the same `debug` app before model work is integrated.

### Stage B — UI with contracts and fakes

- Build the screens above and request permissions only where needed.
- Add selectable fakes and all error/processing states.
- Document interfaces in `docs/INTEGRATION_CONTRACT.md`; freeze changes together.
- Add enrollment sample capture without claiming a real enrolled profile until the service succeeds.

### Stage C — Creator camera

- Integrate the existing camera, emoji/avatar effects and verified photo export.
- Verify a saved image outside the app, multiple faces, front/back camera mirroring and missing-avatar fallback.
- Keep video export optional until its transformation path is verified.

### Stage D — Real voice integration

- Replace fakes after Rahul/Yazeen freeze their decision document.
- Demonstrate two phones: authorized session → matched voice → muted export → unaffected non-overlapping second speaker.
- Handle incompatible profiles, BLE loss, session expiry, uncertainty, processing failure and cancellation visibly.

Run `./gradlew :app:assembleDebug` and `./gradlew :app:lintDebug` (Windows: `gradlew.bat`) on integration changes. Add focused tests for policy/state transitions and output routing when those behaviors exist. Hardware camera/BLE/audio checks remain necessary; emulator success is insufficient.

The first handoff is complete when the repository builds on all three machines, fakes are clearly labeled, settings survive relaunch, the camera exports a transformed photo, and each voice developer has an agreed interface to implement.

## 10. Rahul and Yazeen — decisions required before implementation

Write results in `docs/VOICE_DECISIONS.md`. Research may begin immediately; Ganesh's scaffold can proceed in parallel. Freeze the following **before committing to a model-dependent engine or enrollment format**.

| Decision | Research/test | Freeze as output |
| --- | --- | --- |
| Scope | Record-then-redact vs live; required recording length; one/many protected people | One concrete demo flow and explicit limitations |
| Model/weights | Speaker verification model candidates; license; Android runtime/export availability; dependencies | Exact model ID, weights checksum, license and source |
| Preprocessing | Required rate, mono conversion, PCM/float scale, windowing, feature extraction, normalization | One shared preprocessing implementation/version |
| Enrollment | Sample duration/count, noise/silence checks, aggregation and cross-phone capture differences | Enrollment procedure + quality rejection rules |
| Speaker segments | VAD, window scoring vs diarization, minimum speech length, boundary padding | Segmentation approach + original-timeline timestamps |
| Similarity | Same/different speaker trials across demo phones, distance, languages and noise | Calibrated threshold, uncertain band and error results |
| Overlap | How mixed voices behave and whether separation is feasible | Mute-whole-region MVP or validated separation plan |
| Mobile inference | Run exact weights on a physical demo phone; supported ops, memory, dependencies, duration | Working runtime proof + latency/size measurements |
| Consent session | BLE payload/capability; profile transfer; explicit approval; authentication, expiry, policy revision | Versioned session protocol and failure behavior |
| Export | Audio encoding, redaction boundaries, original retention, sanitized share target | Request/result schema + verified output fixture |

Rahul leads enrollment, session protocol and exchange. Yazeen leads segmentation, matching, redaction and runtime feasibility. Both approve the exact encoder/preprocessing choice; Ganesh approves the UI-facing interfaces.

### Minimum model-selection experiment

Use consented samples from the three teammates. Collect separate enrollment and evaluation recordings, on both actual phones, with fresh speech, silence, background noise, turns and overlap. Keep calibration clips separate from held-out evaluation clips. Compare same-speaker and different-speaker scores; measure missed protected speech, false redaction and boundary leakage on exported audio. Also measure load/inference time, peak memory, APK/model size and total processing time for a representative clip.

Shortlist at most two models from their official model cards/repositories. A runnable Android export is more valuable for this sprint than a model with better paper results but an untested port. A Python-only prototype is useful research but does not prove Android integration. If mobile processing fails, explicitly agree on a disclosed laptop/backend demo fallback before building its UI; do not silently introduce cloud processing.

### Go/no-go gate

Begin model-dependent implementation once one candidate produces repeatable cross-phone matching, a usable boundary/redaction strategy, a runnable mobile inference proof, and compatible enrollment/engine metadata. If overlap remains unresolved, freeze the conservative whole-segment mute limitation. Do not advertise real-time universal recording prevention.

## 11. Official technical references

Checked on 9 October 2026. Recheck when SDK or runtime choices change.

- Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Background BLE: https://developer.android.com/develop/connectivity/bluetooth/ble/background
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground service background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- CameraX analysis: https://developer.android.com/media/camera/camerax/analyze
- CameraX ML Kit analyzer: https://developer.android.com/media/camera/camerax/mlkitanalyzer
- ML Kit face detection: https://developers.google.com/ml-kit/vision/face-detection/android

The app architecture, interfaces and milestones above are team implementation recommendations, not claims made by these Android references. No voice model or biometric threshold is finalized by this handoff.
