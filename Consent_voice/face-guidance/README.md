# Face guidance handoff

This module guides a user through four camera positions during voice enrollment.
It uses CameraX frames and on-device ML Kit face detection only. It does not open
the microphone, evaluate audio, recognize identity, save frames, or create a face
profile.

`FaceGuidanceAnalyzer` is an `ImageAnalysis.Analyzer`. The host owns CameraX and
audio capture, calls `setTargetPose`, and observes `FaceGuidanceState`. Audio may
start when `state.canRecord` is true and should pause or ask for a retry when the
state leaves `READY`.

The demo app validates the four states independently. Left/right yaw signs should
be confirmed on the final front-camera preview because device mirroring conventions
can differ. The thresholds are deliberately centralized in the analyzer so they can
be calibrated on the target phone without changing Ganesh's recorder.
