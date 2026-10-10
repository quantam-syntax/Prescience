# Prescience Voice Lab

This is an isolated Android laboratory for frame-level target-speaker detection.
It does not import, modify, or share storage with `Consent_voice`.

## Current status

The Android decision layer is present, but no model is bundled. The evaluated
NomoPVAD candidate ships PyTorch weights and requires a separate ModelScope
ERes2NetV2 enrollment model, so it cannot be represented honestly as an
Android/on-device ONNX deployment yet.

Do not add a model unless all of these are verified:

1. Its license permits the intended hackathon/demo use.
2. Its frame input/output contract is documented and reproducible on Android.
3. It accepts a locally derived enrollment vector without cloud enrollment.
4. It produces target probabilities on a connected phone using local assets.

The frame gate starts mute after two frames at >= 0.70 and releases after
three frames below 0.45. Those are conservative starting values and require
real-device calibration once a model exists.
