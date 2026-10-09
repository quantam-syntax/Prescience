# Vision feature

This package owns Android face detection and camera-to-display coordinate mapping only.

- `MlKitFaceDetector` runs the bundled offline ML Kit detector on one CameraX frame at a time.
- `FaceCoordinateMapper` converts upright detector boxes into normalized display coordinates and
  mirrors front-camera results.
- `FaceDetectionState` carries the latest frame geometry and dependency-neutral face observations.

Recognition, BLE consent decisions, and output rendering remain separate features. ML Kit tracking
IDs are temporary motion tracks and must never be described as recognized identities.
