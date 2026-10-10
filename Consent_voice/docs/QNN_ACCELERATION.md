# QNN acceleration baseline and acceptance gates

## Current working pipeline

The GitHub `main` implementation remains the reference behavior:

1. `AudioRecord` captures mono PCM at 16 kHz. Capture uses Android's
   `VOICE_RECOGNITION` audio source when the device permits it.
2. Guided enrollment records four camera-validated positions. Offline Whisper
   checks the displayed prompt, while ERes2Net creates one normalized
   192-dimensional reference for each accepted take.
3. `VoicePassportStore` encrypts the four embeddings with an Android Keystore
   AES-GCM key. Enrollment audio and camera frames are not persisted.
4. Silero VAD finds speech. An adaptive energy gate groups speech across pauses
   up to 500 ms and rejects candidate bursts shorter than 800 ms.
5. ERes2Net embeds every candidate burst. The highest cosine similarity across
   the four Voice Passport references becomes the match score.
6. Scores at or above `0.35` are `PROTECTED`; scores from `0.28` to `0.35` are
   `UNCERTAIN`; lower scores are `UNMATCHED`.
7. `PcmConsentRedactor` silences `PROTECTED` and `OVERLAP` ranges. It retains
   `UNCERTAIN` and `UNMATCHED` audio.

Sherpa-ONNX currently uses its CPU backend for Silero, ERes2Net, Moonshine and
Whisper. CameraX supplies frames to on-device ML Kit face detection.

## First acceleration target

The first QNN experiment is source separation, because it can add a capability
the current speaker verifier does not have: recovering individual sources from
simultaneous speech. The local SpeechBrain SepFormer WSJ0-2Mix checkpoint is a
research input, not yet an Android model. It must be reconstructed in a
compatible Python environment, exported with fixed-length 8 kHz inputs,
operator-checked, quantized, and compiled for the phone's HTP backend.

Do not replace the working CPU path merely because a QNN graph runs. The QNN
path is accepted only when all of these gates pass on held-out recordings:

- No regression in protected-speaker recall or friend-speech retention.
- CPU and accelerated outputs remain numerically and perceptually acceptable.
- End-to-end processing is faster, uses less sustained CPU, or reduces heat.
- The model initializes reliably after cold app starts.
- Unsupported devices automatically retain the CPU implementation.

After separation passes these gates, repeat the same process for ERes2Net.
Quantized ERes2Net embeddings require fresh threshold calibration even when
their dimensions are unchanged.

## Reproducing the CPU baseline

Build and install the app and instrumentation APK, then run:

```powershell
adb shell am instrument -w `
  -e class com.atreides.consentvoice.VoiceRegressionTest#testCpuPipelineBenchmark `
  com.atreides.consentvoice.test/android.test.InstrumentationTestRunner
adb logcat -d -s VoiceBenchmark:I *:S
```

Each `VoiceBenchmark` line reports model initialization time, enrollment time,
median processing time, real-time factor and decision count without logging raw
audio or embeddings.

Check workstation and device prerequisites with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check_qnn_readiness.ps1
```

The local ignored toolchain is Qualcomm AI Runtime Community
`2.50.40.260831`. The SDK, Python environments, converted models, calibration
audio and device-specific context binaries stay out of Git.

## Recorded CPU baseline: iQOO 15

Measured on the connected iQOO 15 (`SM8850`, Android 16) on 2026-10-10. The
instrumentation benchmark performs one warm-up run and reports the median of
three steady-state runs:

| Workload | Audio duration | Median processing | Real-time factor |
| --- | ---: | ---: | ---: |
| `test1` | 16.512 s | 401.2 ms | 0.0243 |
| `test3` | 19.872 s | 554.9 ms | 0.0279 |
| `test4` | 12.984 s | 413.7 ms | 0.0319 |

ERes2Net/Silero model initialization took 159.9 ms and enrollment embedding
creation took 129.2 ms. The CPU pipeline is already about 31–41 times faster
than real time on these fixtures. Moving this exact pipeline to QNN therefore
needs a power or thermal justification; source separation remains the first
performance-driven NPU target.

The available SepFormer checkpoint operates at 8 kHz, while the application
pipeline and ERes2Net operate at 16 kHz. Any experiment must make the 16→8 kHz
separation and 8→16 kHz verification resampling explicit and measure whether
the lost bandwidth harms speaker matching.

## SepFormer fixed-shape export: 2026-10-10

The local checkpoint was reconstructed with PyTorch 2.7.1 and SpeechBrain
1.0.3, then exported with a fixed four-second input:

| Property | Result |
| --- | --- |
| Input | `float32[1, 32000]` at 8 kHz |
| Output | `float32[1, 32000, 2]` |
| ONNX size | 106,188,775 bytes |
| PyTorch CPU time | 1,690.5 ms |
| ONNX Runtime CPU time | 2,085.1 ms |
| PyTorch/ONNX cosine agreement | 0.99999988 |
| Maximum absolute error | 0.00037009 |

The graph uses fixed shapes as required for an HTP-oriented QNN experiment.
Its operators are `Add`, `Cast`, `Concat`, `Constant`, `ConstantOfShape`,
`Conv`, `ConvTranspose`, `Div`, `Gather`, `Gemm`, `Identity`,
`InstanceNormalization`, `LayerNormalization`, `MatMul`, `Mod`, `Mul`, `Neg`,
`PRelu`, `Relu`, `Reshape`, `Shape`, `Sigmoid`, `Slice`, `Softmax`, `Squeeze`,
`Sub`, `Tanh`, `Transpose`, and `Unsqueeze`. Conversion and full HTP execution
were verified on the target as described below.

Recreate the ignored ONNX experiment with:

```powershell
py -3.11 -m venv .venv-separation
.\.venv-separation\Scripts\python.exe -m pip install -r scripts\requirements-separation.txt
.\.venv-separation\Scripts\python.exe scripts\export_sepformer_onnx.py
```

## SepFormer HTP result: iQOO 15

The fixed graph was converted to a generic HTP DLC with QAIRT Community
`2.50.40.260831` and calibrated with 17 four-second windows from the local
`test1` and `test3` fixtures. The target was the iQOO 15 (`SM8850`, Android 16,
HTP V81). Execution stayed offline and used all eight reported HVX threads.

| Model | Size | HTP time for 4 s | Speed vs real time | Channel cosine vs float | SI-SDR vs float |
| --- | ---: | ---: | ---: | --- | --- |
| W8A8 | 27.26 MB | 0.499 s | 8.0x | 0.7265 / 0.9692 | 0.55 / 11.95 dB |
| W8A16 | 27.51 MB | 1.242 s | 3.2x | 0.9865 / 0.9984 | 15.66 / 25.11 dB |
| W8A16, cached | 110.52 MB | 1.202 s | 3.3x | 0.9865 / 0.9984 | 15.66 / 25.11 dB |

W8A8 is rejected because one separated channel changes too much. W8A16 is the
accepted integration candidate: it preserves both outputs closely enough for
downstream speaker verification while remaining faster than real time.

An uncached W8A16 DLC required 237.4 seconds to prepare on first load. Embedding
the compiled HTP initialization cache increased the DLC to 110.52 MB but cut
model initialization to 0.651 seconds. The cached run produced the same output
as the uncached W8A16 run. A distributable build must generate and package the
cache for the supported HTP/SoC family and retain a capability check and CPU
fallback for other devices.

The high-level QAIRT cache-building runner reported a 16-bit native input
buffer, whereas the low-level runner accepts the float input and performs the
correct conversion. The validated app integration path is therefore: build the
cache once through the high-level API, then load and execute that cached DLC
through the low-level API. The bridge reads tensor metadata and converts 8 kHz
float audio to the model's native 16-bit quantized tensors before execution.

Use `scripts/make_sepformer_calibration.py` to create calibration windows and
`scripts/compare_sepformer_outputs.py` to compare a device output with the
float ONNX reference. Generated `.dlc` files, SDK runtimes and all local audio
remain ignored.

## Android HTP preview: 2026-10-10

The app now contains a QAIRT C++ JNI bridge and a locally staged V81 HTP
runtime. A real iQOO 15 instrumentation test passed: the cached W8A16 DLC
initializes inside the installed APK in 0.879 seconds. The app also exposes
**Test HTP voice separation (first 4 seconds)** for imported audio. It
downsamples the first four seconds from 16 kHz to 8 kHz, runs the HTP model,
normalizes each source only for private listening, and plays Source A or B.

This is deliberately separate from redaction. Next:

1. Listen to both sources with solo voice, alternating speakers, and overlap.
2. Confirm source order and amplitude on representative recordings.
3. Score both reconstructed sources with the enrolled ERes2Net profile.
4. Add overlapping windows and source continuity for recordings over four seconds.
5. Compare recall and friend-speech retention with the CPU baseline.

The SDK, runtime `.so` files, cached DLC, generated previews and native build
output remain ignored. Run `scripts/stage_qairt_sepformer.ps1` locally before
an Android build that includes the native bridge.

## Integration decision

Source separation may be added as an experimental overlap path after the app
implements all of these steps together:

1. Resample 16 kHz mono input to 8 kHz and split it into overlapping four-second
   windows.
2. Run the cached W8A16 DLC on HTP and maintain speaker-channel continuity
   across adjacent windows before overlap-add reconstruction.
3. Resample each separated source to 16 kHz, score both with the existing four
   ERes2Net enrollment embeddings, and suppress only the source identified as
   the protected speaker.
4. Fall back to the current Silero + ERes2Net redaction when QAIRT, HTP V81, the
   cached context, or source reconstruction is unavailable.
5. Pass the protected-speaker recall and friend-retention recordings before the
   option becomes the default.

Moving the existing ERes2Net graph to HTP is not expected to improve recognition
accuracy by itself. Its current CPU pipeline already runs 31-41 times faster
than real time. A larger speaker model is worth converting only after it beats
the current model on the same enrollment and test recordings; any quantized
embedding model also needs new cosine thresholds and a new Voice Passport.

## Android API smoke test

The Qualcomm `snpe-release.aar` Java API was also tested inside the installed
debug application. On this consumer build, DSP discovery requires both
`libcdsprpc.so` as an optional `<uses-library>` and extracted native libraries;
after those changes SNPE successfully opened an unsigned V81 HTP session.

SNPE cannot be used as the SepFormer bridge, however. It loads the QAIRT-cached
DLC without exposing any graph input tensors, because the QAIRT and SNPE cache
formats are not interchangeable. Building the uncached W8A16 DLC through SNPE
then stalled for more than ten minutes without completing or creating a usable
cache. The temporary AAR, manifest changes and smoke test were removed.

The supported integration direction is a small native bridge over the QAIRT
low-level C/C++ API, loading the already validated cached DLC. This preserves
the measured 0.651-second initialization and float tensor behavior. The heavy
model is deliberately not wired into the production redactor until that bridge,
window reconstruction and end-to-end voice-retention tests are complete.
