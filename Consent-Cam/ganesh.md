# Ganesh — Camera App, Recognition Polish, and Local AI Agent

## How to use this file

This is the authoritative context for Ganesh's coding AI. Before changing code:

1. Read this file completely.
2. Read `progress.md` completely.
3. Work only on Ganesh's responsibilities unless a handoff explicitly expands them.
4. Update only Ganesh's block in `progress.md` before and after substantive work.
5. Commit the progress update with the code it describes.

## Product context

Consent-Cam is an Android privacy-camera app for the iQOO Hackathon 2026. Phone A records; Phone B broadcasts `ALLOW`; Phone C broadcasts `PROTECT`. Normal Proximity Mode broadly protects faces when an opt-out beacon is near. Precise Face-Match Mode selectively applies the active person's consent.

Sensitive inference must be on-device. Do not add cloud recognition, cloud LLM fallback, analytics, remote logging, or uploads. The deterministic BLE/vision pipeline owns privacy decisions; the LLM only controls allow-listed tools, explains state, and audits inconsistencies.

## Ganesh's ownership

Ganesh owns:

- Android application shell, navigation, permissions, and role selection.
- CameraX preview, photo capture, video capture, camera switching, and base settings.
- A real CameraEffect that protects preview, saved photos, and recorded video.
- Integration and polish of Yazeen's recognition module.
- LiteRT-LM initialization with Gemma 3n E4B and E2B fallback.
- NPU to GPU to CPU backend probing.
- Voice agent, allow-listed tool execution, TextToSpeech, privacy audits, and session summaries.
- English commands and concise English/Tamil narration.
- On-device AI metrics/status panel.

Ganesh does not own:

- Redesigning Rahul's BLE protocol or RSSI policy.
- Creating a second face detector or incompatible embedding pipeline.
- Allowing the LLM to decide identity or directly unblur a face.
- Adding cloud inference or calling remote APIs from shipped app behavior.

## Camera application

- Use CameraX with lifecycle-bound Preview, ImageAnalysis, ImageCapture, and VideoCapture use cases.
- Keep capture controls usable when BLE, recognition, or LLM modules are unavailable.
- Settings must include:
  - privacy protection on/off
  - Normal Proximity or Precise Face-Match mode
  - privacy-zone preset
  - voice-agent on/off
  - spoken narration on/off
  - debug/AI metrics panel
- Privacy-reducing changes require clear UI state; agent-triggered reductions require tap confirmation.
- Use the coordinate transforms supplied by CameraX so detector rectangles map correctly across rotation, mirroring, aspect-ratio crop, and front/back cameras.

## Protected output effect

- A simple Compose/View overlay is insufficient because it does not alter encoded output.
- Implement one GPU-backed CameraEffect/SurfaceProcessor or Media3 effect targeting `PREVIEW | VIDEO_CAPTURE | IMAGE_CAPTURE`.
- Consume Rahul's timestamped normalized `BlurRegion` list.
- Apply a strong mosaic or multi-tap blur only inside padded regions, with clamping at frame edges.
- Smooth short coordinate changes without allowing the mask to visibly lag behind the face.
- Define and test a maximum number of simultaneous regions; degrade by broadening protection rather than silently dropping a `PROTECT` region.
- When the effect fails, stop protected recording and show an explicit error instead of saving unprotected media under a protected label.

## Recognition polish and integration

- Use only Yazeen's `FaceEmbedder`/`FaceMatcher` interfaces and Rahul's `TrackedFace` flow.
- Run matching once for each new tracking ID and cache the result.
- Surface `UNKNOWN`, `AMBIGUOUS`, and `INSUFFICIENT_QUALITY` states to Rahul's decision coordinator.
- Add cancellation and lifecycle handling so stale recognition results cannot be applied to a reused/expired track.
- Display consent state and uncertainty without showing personal names.
- Do not use the LLM as a recognition tiebreaker.

## Local LLM runtime

- Baseline model: Gemma 3n E4B int4.
- Reliability fallback: Gemma 3n E2B.
- Pin the tested LiteRT-LM and model versions; do not ship `latest.release`.
- Initialize and warm the engine on a background dispatcher when entering a role.
- Probe execution backends in this order:
  1. Qualcomm NPU, only if compatible runtime/model artifacts initialize successfully.
  2. GPU.
  3. CPU.
- Display and log the backend actually used; never describe GPU/CPU execution as NPU.
- Allow one generation at a time and use a conflated, priority-ordered request queue.
- No artificial prompts or dummy inference loops. Every call must correspond to a visible feature or audit event.

Priority order:

1. Interactive voice command.
2. Consent or protection-state transition.
3. Enrollment coaching.
4. User-requested explanation.
5. Periodic structured audit.

## Voice agent and tools

Supported English commands:

- Start or stop protected recording.
- Switch between proximity and precise protection.
- Select a privacy-zone preset.
- Explain why a face/region is blurred.
- Announce nearby consent states.
- Generate the current or final session summary.

Expose only typed, allow-listed tools:

```kotlin
sealed interface AgentCommand {
    val requiresConfirmation: Boolean

    data object StartRecording : AgentCommand
    data object StopRecording : AgentCommand
    data class SetPrivacyMode(val mode: PrivacyMode) : AgentCommand
    data class SetPrivacyZone(val preset: PrivacyZonePreset) : AgentCommand
    data class ExplainTrack(val trackingId: Int) : AgentCommand
    data object GetSessionStatus : AgentCommand
    data object GenerateSessionSummary : AgentCommand
}
```

- Validate parsed arguments independently of model output.
- Require tap confirmation for disabling protection, widening visibility, or changing to a less protective mode.
- Generate short English and Tamil output from the same structured facts.
- Speak only meaningful changes; do not narrate every frame or BLE advertisement.
- Do not put names, face crops, embeddings, secrets, or raw BLE tokens in prompts.

## Privacy audits

While recording, provide the model a compact structured snapshot containing counts and states only:

- active tracked faces
- active `ALLOW`/`PROTECT` sessions
- proximity-zone occupancy
- matched/unmatched session counts
- applied blur-region counts
- recognition confidence category
- active mode and fallback status

Run audits every 10 seconds in normal/light thermal conditions. The result may warn about inconsistencies but cannot mutate privacy decisions. Generate an ephemeral, non-identifying summary when recording stops; persist/export it only after explicit user action.

## Thermal and reliability governor

- Normal/light: E4B, multimodal enrollment help, and 10-second audits.
- Moderate: text-only output, short generations, and 20-second audits.
- Severe: suspend periodic/multimodal calls; retain manual camera controls and essential interactive commands if safe.
- Critical/emergency: close the LLM engine while deterministic camera/BLE protection continues.
- If E4B initialization or sustained use threatens the demo, switch to E2B and display that fact.
- Keep narration around 40–60 generated tokens.

The AI panel displays model, actual backend, meaningful inference count, last trigger, latency, thermal state, fallback state, and `Cloud calls: 0`.

## Acceptance checks

- Preview, photo, and video all contain the same protected regions.
- No protected recording is saved unblurred after an effect error.
- Rotation, front-camera mirroring, aspect crop, and camera switching retain correct blur alignment.
- App remains usable when BLE, recognition, E4B, NPU, GPU, microphone, or TTS is unavailable.
- Backend fallback is tested by forcing each initialization failure.
- Every agent tool rejects invalid arguments and confirmations work.
- English commands and English/Tamil narration work offline.
- Audits never change protection state and contain no biometric data.
- E4B/E2B latency, memory, thermals, and camera smoothness are measured during a 10-minute rehearsal.
- The complete Phone A/B/C demo works without network connectivity.

## Progress update contract

Before work, set Ganesh's `Current task` and status in `progress.md`. After work, record completed items, exact tests and results, interfaces/files changed, blockers, handoffs, actual LLM backend/model, and an Asia/Kolkata timestamp. Modify nothing between another teammate's ownership markers. Never mark work complete without verification.
