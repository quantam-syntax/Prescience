# iQOO Finale — Privacy Suite project context for Codex

Context date: 9 October 2026. Team: Atreides. Product name is provisional.

Read this before helping with this work folder. This file records planning context, not proof that the repository or implementation already exists. Inspect the actual folder, Git history, README and any AGENTS.md before making changes. Update this context as decisions are verified.

## 1. Who I am and how to help me

I am Rahul R Nair, a BTech CSE student learning Android/Kotlin. I understand programming fundamentals and basic OOP, but I want to understand the code I build. Act as a technical mentor and pair programmer: explain important choices and guide me through manageable steps instead of dumping a large unexplained app. When I explicitly request an implementation, implement the requested scope and explain how to run and verify it.

This is a three-person hackathon project. My immediate focus is the Voice Consent module and researching the voice model with Yazeen. Ganesh is building the repository, main app and camera feature in parallel. Do not assume this folder is Ganesh's full repository; it may initially contain only research or my module.

## 2. Event and background

- We won the student category at the iQOO RESKILL Chennai City Battle with our earlier Consent Cam concept.
- We are preparing for the Bengaluru finale, planned around a 48-hour build.
- Our understanding is that finale evaluation focuses on work built during the finale. Verify current event rules before claiming reused Chennai functionality as new work.
- Consent Cam already exists in some form, but this conversation did not inspect its code, architecture or completeness.
- We want to extend the concept into one coherent privacy and consent suite.

## 3. Product story

Give people a way to express and manage consent for their face, voice and sensitive information, with compatible capture tools applying those preferences.

The story connects three identity/privacy surfaces:

1. **Face:** Consent Cam replaces opted-out faces with blur, emojis or selected avatars.
2. **Voice:** a person enrolls their voice and signals a consent preference; a cooperating recorder identifies protected speech and redacts it.
3. **Documents:** a future/secondary feature redacts sensitive information before sharing.

The central app manages face/voice enrollment, avatars, consent settings and the related tools. A privacy dashboard can show actual local events and status, but should not invent statistics or claim protection across arbitrary apps.

## 4. Decisions the team has made

| Item | Decision |
| --- | --- |
| Main finale feature | Voice Consent |
| Ganesh | Main app, repository scaffold, Consent Cam and creator experience |
| Rahul | Voice Consent, BLE signalling/discovery, enrollment processing and profile/session exchange |
| Yazeen | Voice redaction: speaker matching, protected speech segments, audio processing and sanitized export |
| Creator extension | Emojis or uploaded avatars instead of only blur |
| Integration goal | All features managed through one main app |
| Parallel work | Ganesh starts the scaffold while Rahul and Yazeen research and choose compatible voice technology |

Earlier brainstorming included call-time reactions and voice recording blocking. These are not the immediate implementation scope. Yazeen's current redaction responsibility is **voice/audio redaction**, not document OCR/redaction. Sensitive-document redaction remains a supporting idea after the voice flow works.

## 5. Proposed implementation choices — not all finalized

The previous handoff recommended:

- One Kotlin/Compose Android app and one installable APK for the first integrated demo.
- Camera as a feature/library module inside that app, with the existing camera source preserved during integration.
- The same app runs on both phones: controller/protected-person role on Phone A, cooperating recorder role on Phone B.
- Record first, then redact before export for the voice MVP. Real-time suppression is a later capability requiring separate validation.
- Muting as the first voice-redaction method.
- Model-independent interfaces and fake implementations let Ganesh build screens before the voice model is selected.

These are engineering recommendations. Reconcile them with the actual repository and the team's latest decisions before restructuring an existing project. Do not silently turn a recommended option into a completed implementation.

## 6. Core technical limits and demo claims

BLE carries discovery/consent signals. It cannot disable another phone's microphone or prevent arbitrary third-party apps from recording. Protection requires a cooperating camera/recorder that enforces consent.

Speaker recognition, segmentation and source separation are different problems:

- Speaker embeddings help match a segment to an enrolled person.
- VAD locates speech; it does not identify the speaker.
- Diarization estimates who spoke when; assigning clusters to enrolled identities still needs matching.
- Separating simultaneous speakers requires additional capability. Matching alone cannot remove one voice from mixed audio while preserving the other.

For an MVP, overlap may require muting the whole mixed region and warning that another speaker was also removed. Simple pitch shifting is not a guarantee that speech or identity is hidden. Do not claim a sample is privacy-safe just because it sounds different.

For faces, detection/tracking is not recognition. BLE proximity does not reveal which face in the frame belongs to a broadcaster. Inspect and reuse the actual Chennai identity association mechanism if it works; otherwise disclose a manual-association demo limitation.

Camera overlays on the screen are not proof that saved photos/video contain the effect. Verify exported media independently. Background BLE and screen-off recording also require real-device testing and SDK-appropriate permissions/service handling.

## 7. Intended end-to-end voice MVP

1. Phone A records enrollment samples with explicit microphone permission.
2. The chosen encoder/preprocessing produces a usable voice profile.
3. The person selects a voice consent policy and starts a consent session.
4. Phone B discovers Phone A through BLE and completes an explicitly authorized profile/session exchange.
5. Phone B records audio inside our cooperating app.
6. After Stop, the engine locates and matches speech segments against authorized protected profiles.
7. The engine mutes protected regions, handles uncertainty/overlap according to the agreed policy, and writes a new sanitized file.
8. Phone B previews and shares the sanitized export, showing any unresolved limitations.

Enrollment, broadcasting, peer acknowledgement and successful redaction must appear as distinct states. A consent toggle or discovered peer alone does not mean voice protection has been enforced.

## 8. Suggested repository structure

```text
privacy-suite/
  app/                         # Ganesh; only application module
  core/consent/                # Shared domain types/contracts
  core/storage/                # Profile/settings persistence
  feature/camera/              # Ganesh; Consent Cam and effects
  feature/voice-ui/            # Ganesh; enrollment/recorder/results UI
  modules/voice-consent/       # Rahul
  modules/voice-redaction/     # Yazeen
  docs/
    GANESH_HANDOFF.md
    VOICE_DECISIONS.md
    INTEGRATION_CONTRACT.md
  gradle/libs.versions.toml
  settings.gradle.kts
  build.gradle.kts
  README.md
```

Use temporary feature branches for app/camera, voice-consent and voice-redaction work; merge source into permanent folders/modules. A separate long-lived Consent Cam branch is not a substitute for integrating it.

Ganesh owns initial shared Gradle setup/navigation. Both voice modules depend on shared contracts, not on each other's implementation. Commit exact tool/runtime versions, avoid independent dependency upgrades, and integrate compiling changes regularly.

## 9. What Rahul and Yazeen must research and freeze

The active clear-turn model is Sherpa-ONNX English VoxCeleb ERes2Net at 16 kHz,
with Silero VAD. It runs locally in the Android app. Its threshold and
short-utterance behaviour are still calibrated only on the supplied clips; they
are not population-wide accuracy claims. BLE profile transport remains
unfinalized.

| Research area | Deliverable |
| --- | --- |
| Speaker encoder | Active English ERes2Net model: exact weights/version, license, source and checksum are in `docs/MODEL_ASSET_LOCK.md` |
| Android execution | Working proof on a demo phone; supported operators, runtime/dependencies, model size, memory and latency |
| Preprocessing | Sample rate, channel conversion, input scaling, features/windowing and normalization; one version shared by enrollment and matching |
| Enrollment | Sample count/duration, quality checks, rejection conditions, aggregation and deletion behavior |
| Segmentation | VAD/window/diarization strategy, minimum usable segment, padding and timestamps aligned to the original recording |
| Matching | Calibrated threshold, uncertain band and results from same/different speakers across phones |
| Overlap | Conservative whole-region mute or validated SepFormer separation; explicit output warnings |
| Session protocol | BLE payload, capability/version, authorized profile transfer, authentication, policy revision, acknowledgement and expiry |
| Engine contract | Input audio/profile metadata, processing/cancellation/error states, output file and applied segment schema |
| Data handling | Private storage, enrollment/session deletion, original retention and sanitized sharing |

Rahul leads enrollment and BLE/session exchange. Yazeen leads matching, segmentation, redaction and inference feasibility. Both approve the exact encoder/preprocessing so their profiles are compatible. Ganesh coordinates the UI-facing contract.

Shortlist at most two models using their official model cards/repositories. Test before choosing. A Python notebook proves an algorithmic path, not Android integration. A laptop/backend fallback is acceptable only if explicitly agreed and disclosed; do not silently send biometric/audio data to a server.

### Minimum experiment

Use consented samples from the three teammates. Separate enrollment, calibration and held-out evaluation recordings. Test fresh phrases on the actual phones, different distances, silence/noise, turns, short speech and overlapping speech. Compare same-speaker/different-speaker scores. Measure missed protected speech, false redaction and boundary leakage; listen to the exported audio. Record inference/processing time, memory and model size. Do not present a three-person test as broad accuracy validation.

Freeze results in `docs/VOICE_DECISIONS.md` before building model-dependent enrollment or matching modules. Keep uncertain decisions explicitly marked pending.

## 10. Contracts Ganesh can implement with mocks

- Consent policy: face mode, voice mode, revision and session scope/expiry.
- Voice profile descriptor: model/weights/preprocessing versions, sample rate, embedding dimensions/dtype/normalization and a private file reference.
- Session state: ephemeral peer ID, capabilities, acknowledgement, revision and expiry.
- Audio asset: private URI, duration and audio format metadata.
- Speech decision: original-timeline start/end, profile candidate, score, protected/unprotected/uncertain/overlap state.
- Redaction result: new sanitized URI, applied regions, warnings and engine version.

Proposed interfaces: `VoiceEnrollmentService`, `ConsentSessionService`, `VoiceRedactionEngine`. Keep biometric/model details out of Compose screens. Reject incompatible profile versions. Fakes must be visibly labeled and never silently substitute for failed real protection.

## 11. Privacy and implementation expectations

- BLE advertisements should contain compact discovery/policy data, not raw recordings or biometric embeddings.
- Enrollment and sharing authorization are separate user actions.
- Keep recordings/identity files private and out of Git/logs. Treat embeddings as sensitive identity data.
- Share sanitized media through content URIs; verify the share action does not expose the original.
- Do not allow a processing failure to produce an apparently successful protected export.
- Start hardware work in visible screens. Add background/screen-off capability only after checking current Android requirements on the selected SDK and devices.
- Prefer a small, understandable MVP over unnecessary services/infrastructure.
- Do not overwrite teammates' work or restructure modules blindly. Inspect existing state and explain material interface changes.

## 12. Actual implementation status at this handoff

Completed in this workspace: Android/Kotlin app scaffold; microphone capture and
audio import; local enrollment; local VAD; English VoxCeleb ERes2Net direct
utterance matching; WAV redaction/export; playback/download UI; and device
instrumentation regressions for the supplied test clips. The current policy
mutes only confirmed profile matches and overlap decisions. Uncertain speech is
retained and reported, because muting it caused unacceptable false redaction.

The active voice path is strictly offline. It does not send recordings,
embeddings, or transcripts to a service.

The current limitation is simultaneous speech. `models/separation/` contains
an ignored evaluation download of SpeechBrain SepFormer WSJ0-2Mix, a 113 MB
two-speaker source-separation checkpoint. It is not yet an Android asset and
must be exported to ONNX/TFLite/QNN, operator-checked, memory-benchmarked, and
validated on the iQOO 15 before it can affect an export. It is an experiment,
not a completed feature.

`GANESH_HANDOFF.md` was created separately and contains Ganesh's detailed tasks. If it is absent from this folder, this README still supplies the essential project context; do not assume the other file is available.

## 13. What Codex should help me do next

1. Inspect this work folder and identify whether it is the shared Android repository or a research workspace.
2. Establish the actual demo-phone models/Android versions and available development environment; do not assume them.
3. Benchmark the active offline clear-turn model using longer enrollment on the actual iQOO 15.
4. Export the SepFormer separation experiment to an Android-compatible format and measure offline latency, memory, and output quality on the iQOO 15.
5. Record measurements and decisions, then agree on the shared preprocessing/profile/engine contract with Yazeen and Ganesh.
6. Integrate only validated offline separation into the voice-consent flow.

When using current Android/model documentation, verify the official sources. Clearly distinguish a suggestion, an untested assumption, an experimental result and a finalized decision. This README is context for continuing the project, not permission to publish, message teammates, or modify external repositories without the relevant user instruction.
