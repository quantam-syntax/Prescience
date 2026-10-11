# Consent-Cam

## Combined Prescience app

`ConsentCam/` now builds one Android APK with three choices on its opening
screen:

- **Consent Cam:** the existing face-protected photo/video camera.
- **Consent Audio:** the existing four-position Voice Passport enrollment and
  offline 16 kHz NPU voice-removal export.
- **Protected:** video with active face masking plus post-recording voice
  removal. Use Consent Cam for face-protected photos.

Protected video is recorded to an app-private temporary MP4. After CameraX
finishes, the audio is decoded in memory, matched against this APK's Voice
Passport, separated on the iQOO HTP, and replaced with the retained source.
The original video track and newly encoded AAC track are remuxed into a new
MP4. Only after that completes is the result published to `Movies/ConsentCam`.
If enrollment, matching, NPU inference, encoding, or muxing fails, no original
video is published. The app deletes its exact temporary capture/output files.
Protected recording will not start until a face-protection region is active.
Protected voice may still faintly leak through successful separation; review
both picture and sound before sharing.

Consent Cam video deliberately keeps its original microphone audio; only the
Protected choice replaces audio. Normal captures are named `CONSENT_…`, while
completed combined exports are named `PROTECTED_…`. An interrupted capture or
app process can leave a raw file in app-private cache; do not mistake it for a
sanitized export or share it.

The original `Consent_voice` app remains independently buildable. The combined
APK reuses its source through `voice-feature` and the `voice-consent` and
`face-guidance` modules, rather than copying the models or implementation into
Git. Build from the repository's sibling-project layout after locally staging
the private QAIRT SDK and cached 16 kHz DLC with
`Consent_voice/scripts/stage_qairt_sepformer.ps1`. The private model, runtime
libraries, recordings, and embeddings remain Git-ignored.

Android encrypts each app's Voice Passport separately. A Passport enrolled in
the standalone Consent Voice APK is **not** available to the combined app;
enroll again through **Consent Audio** before using Protected video.

Verification: the combined APK builds, Consent Cam JVM tests pass, targeted
device tests run the bundled 16 kHz model on HTP and decrypt the existing face
profile without changing it, and two connected iQOOs completed a real Protected
video test. The recorder matched one protected face (score 0.836); the user
confirmed the saved `PROTECTED_20261011_083125.mp4` kept that face blurred and
the other speaker audible while mostly removing the enrolled voice. Brief
protected-voice leakage remains, so every export still requires review before
sharing. A BLE scanner-registration failure on the recorder required a
Bluetooth off/on cycle; app retry alone did not recover it.
