# Audio regression: 2026-10-10

## Root cause and correction

The locked WeSpeaker model lacks `feature_normalize_type` metadata. Its graph
does not center the input features, while the official reference performs
per-feature temporal mean subtraction before inference:
https://github.com/wenet-e2e/wespeaker/blob/master/wespeaker/bin/infer_onnx.py

Sherpa-ONNX v1.13.8 supports this via `feature_normalize_type=global-mean`.
Run `python scripts/prepare_speaker_model.py` before building. This verifies
the upstream SHA-256 and generates a separate CMN-enabled asset; weights and
the original model are unchanged. Derived SHA-256:
`6928c40c3b5c695352998c261cb6f2250c4e52a991e324c0f63c2e10fe7c31f8`.
The model ID is versioned accordingly. Enroll again after installing; old
embeddings are not compatible with this preprocessing.

Removed blind backward propagation of protected labels to preceding windows.
Uncertain or unavailable embeddings are withheld rather than silently allowed.
Final padded VAD frames are clipped to the actual audio length.

## Local device regression

Fixture: user-supplied `test1.mp3`, converted to 16 kHz mono PCM16 WAV under
`app/src/androidTest/assets/test1.wav` (ignored by Git, not in the main APK).
Reference enrollment is the last 13.5 seconds onward of the same recording.
This is a diagnostic regression, **not independent enrollment validation**.

On the iQOO, friend windows scored 0.06–0.26 after CMN, versus 0.62–0.92
before. Protected reference windows scored approximately 0.80–0.85 after.
No thresholds were changed (protected 0.70, uncertain 0.55).

Tests assert the first 10 seconds remain sample-identical; detected protected
speech from 13.3125 seconds to the end is silent; decision bounds are valid;
uncertain/overlap decisions are withheld; input PCM is not mutated.
Both instrumentation tests passed on the final installed build. FFmpeg
inspection of the resulting WAV confirms muting from 13.25–16.512 seconds.
The 11–13.25-second interval is not labeled protected by this VAD pipeline;
the user must review that interval against their approximate 10–11-second
speaker-change label before treating this example as fully redacted.

Run after installing debug and androidTest APKs:

```
adb shell am instrument -w -e class com.atreides.consentvoice.VoiceRegressionTest com.atreides.consentvoice.test/android.test.InstrumentationTestRunner
```

The test writes `regression-cmn.wav` into the app's external-files directory
for local comparison. It does not write source audio or embeddings to logs.
New independent enrollment, different rooms, and multiple speakers still
need validation. VAD can miss speech; overlapping speakers are not separated
or automatically detected. This is not a fail-safe privacy guarantee.

## Follow-up failure: test3

The user supplied `test3.mp3` and `consent-sanitized_2026-10-10_02-45-23.wav`.
The export is 19.85 seconds; a -70 dB silence scan finds only the initial
0–0.147-second silence, no later sustained muted regions. The earlier fixture
test did not establish successful independent enrollment.

Diagnostic `testCrossRecordingScores` uses the previous test1 reference with
test3, not the user's unavailable in-memory enrollment. Test3 windows from
11.938–15.942 seconds score 0.448–0.583; other detected windows score
0.156–0.340. This is insufficient for the 0.70 protected threshold and some
windows also fall below the 0.55 uncertainty threshold. Speaker labels for
test3 must be confirmed before drawing accuracy conclusions or calibrating
thresholds. This diagnostic logs scores only and deliberately has no accuracy
assertions: a passing invocation is not a redaction-performance pass.

## Follow-up correction after user confirmed speaker order

User confirms fresh enrollment and friend -> protected middle sentence -> friend.
Provisional CMN thresholds are now protected >=0.45, uncertain >=0.40 (both
withheld). These are calibrated on two supplied recordings, not a validated
population-wide operating point. No clip timestamps are used in production.
Enrollment pools up to 12 seconds of detected speech and rejects less than
2 seconds, instead of choosing only the longest phrase. Zero-redaction
exports now carry an explicit warning in the UI.

The earlier cross-recording test asserted that the detected middle interval
11.938–15.942 seconds was entirely zero. All four device tests passed (cross-clip,
original fixture, silent-enrollment rejection, redaction semantics).
`consent-fixed-test3.wav` is generated on the phone and copied to Downloads.
This still uses a test1 reference, not the lost in-memory user enrollment;
a fresh user-enrollment end-to-end check is still required. Lower thresholds
can increase false matches on new speakers, and missed VAD boundaries remain
a risk. Do not describe this as fail-safe or universally calibrated.

## Final correction: direct per-utterance verification

The original test4 export muted 0.03-9.97 seconds as one protected region and
retained only the final 3.00 seconds. Pyannote had merged several rapid speaker
turns into one cluster. The final pipeline removes cluster identity from the
mute decision: Silero VAD finds speech, acoustic pauses split it into
utterances, and the English VoxCeleb ERes2Net model compares every utterance
directly with the enrolled profile. CAM++ was evaluated and rejected because
its same-speaker and different-speaker scores overlapped on the supplied clips.

Only `PROTECTED` and `OVERLAP` decisions are muted. `UNCERTAIN` is retained and
reported for review, matching the product rule that a voice must be confirmed
before it is removed. The test4 regression requires both confirmed and retained
utterances inside the formerly collapsed first ten seconds. Isolated
sub-second words remain the least reliable case for speaker verification.

## Superseded continuation experiment

The user clarified that the enrolled protected speaker says “Subash” three
times after the correctly muted middle sentence, then the friend says “Rahul
Rajeev.” Those short repetitions produce weak standalone speaker embeddings,
and Silero VAD places both speakers in one 17.506–19.872-second segment.

A bounded conservative continuation rule labels the start of a short VAD
segment as uncertain when it resumes within 2.5 seconds of a confirmed
protected run. The continuation is limited to 1.7 seconds so a rapid handoff
inside the same VAD segment is not muted indefinitely. The test3 regression
requires the protected repetitions through 19.15 seconds to be silent and the
friend's phrase from 19.25 seconds to remain sample-identical. This is a
demo-calibrated compromise, not general speaker diarization; faster handoffs or
longer protected phrases can still fail.

All five device tests pass with the revised boundary. The generated WAV is
muted at 11.000–16.500 and 17.506–19.206 seconds; audio after 19.25 seconds is
retained.
