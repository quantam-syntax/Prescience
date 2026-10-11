# Retained-voice clarity comparison

The app now uses the two-second 16 kHz QAIRT W16A16 model on the tested iQOO
HTP. The earlier 8 kHz W8A16 path below is the comparison baseline. The
Voice Passport and conservative mute fallback remain; the new export is
explicitly review-before-sharing because protected-voice leakage is possible.

## Local comparison, 2026-10-11

The [SpeechBrain `sepformer-whamr16k` checkpoint](https://huggingface.co/speechbrain/sepformer-whamr16k)
(Apache-2.0, trained for two-speaker noisy/reverberant separation) was kept under
the ignored `models/separation/` directory. The same 5.461-second phone
recording (87,381 samples at 16 kHz) was compared against the latest 8 kHz NPU
app export. All recordings and candidate WAVs are ignored by Git.

Checkpoint SHA-256 (`hyperparams.yaml`, `encoder.ckpt`, `decoder.ckpt`,
`masknet.ckpt`, respectively): `EEEF8D1450E1E06417EDA07AA87DED71C6D9F3394852A92805E6AAF5229A50C6`,
`31D23D395A408B887B8F6AC01E477F00BCBA27F95785426359FB52D52F1DC6ED`,
`2D959CB46DE5B15F008BF5476CF7C19BC680B310C7E10883E3EDDFDBDE533CB8`,
`E5FB8C690668E5D1BBBC9A8256974577093A09CD08E845E9F30024FDD33472CE`.

`scripts/compare_separator_clarity.py` runs the 16 kHz float checkpoint in
overlapping, end-aligned four-second blocks and writes both full-length source
WAVs. Source 2 is the *likely* retained speaker: its waveform correlation to
the existing export is -0.721 (polarity has no effect on listening). Correlation
does not establish that the protected voice is absent.

Both candidate WAVs have the expected duration and no PCM clipping. At the
second block's start, the likely retained candidate's sample-to-sample jump
is 404 PCM counts, below its 99th-percentile ordinary jump of 6,250; this
does not suggest a stitching click. The
original phone recording itself contains only about 0.21% of its spectral
energy above 4 kHz, so a 16 kHz separator cannot be assumed clearer merely
because it accepts a higher sample rate. The user reports clearer friend speech
in the 16 kHz candidate, **but also brief faint protected-voice leakage**.
That failed the original privacy gate. The user later chose the clearer
two-second 16 kHz NPU version despite residual leakage, with a mandatory
review-before-sharing warning rather than a guarantee of voice removal.

Two further local-only candidates (`gentle` and `strong`) use short-time
cross-stream coherence to attenuate material shared with the other separated
output. A synthetic two-tone check shows the stronger setting reduces its
known coherent interference component without materially attenuating the
independent component. That is not evidence of safe voice removal on this
recording. Both versions require another human listening/leakage check.
The user preferred the gentle version for friend clarity and reported residual
protected speech after 3 seconds. A third clip crossfades from gentle to
strong over 80 ms starting at 3.000 seconds, solely to test that reported
interval. This clip-specific blend is not a general leakage detector or an app
change. It is exactly 87,381 samples, has no PCM clipping, and has no jump at
the crossfade larger than typical sample-to-sample changes in the recording.

For convenient phone listening, these files were copied to Downloads:

- `clarity-ab-8k-current.wav` — current sanitized app export.
- `clarity-ab-16k-experimental-NOT-SANITIZED.wav` — likely retained 16 kHz
  candidate; **not privacy-verified and not safe to share as a sanitized file**.
- `clarity-ab-16k-gentle-EXPERIMENTAL.wav` and
  `clarity-ab-16k-strong-EXPERIMENTAL.wav` — experimental leakage postfilters;
  **neither is a sanitized export**.
- `clarity-ab-16k-gentle-then-strong-after-3s-EXPERIMENTAL.wav` — clip-specific
  A/B candidate for the reported leak after 3 seconds; **not sanitized**.

## Reproduce without committing private assets

Use the Python environment in `scripts/requirements-separation.txt`, put the
official 16 kHz checkpoint's `hyperparams.yaml`, `encoder.ckpt`, `masknet.ckpt`
and `decoder.ckpt` under ignored `models/separation/speechbrain-sepformer-whamr16k/`,
and provide 16 kHz mono PCM16 WAVs of the original mixture and its current app
export. Run from `Consent_voice/`:

```powershell
.\.venv-separation\Scripts\python.exe scripts\compare_separator_clarity.py `
  --mixture-wav .tmp\clarity-comparison\source-16k.wav `
  --baseline-wav .tmp\clarity-comparison\current-8k.wav `
  --output-dir .tmp\clarity-comparison `
  --reported-leak-after-seconds 3.0
```

The fixed four-second 16 kHz ONNX experiment was exported to ignored
`models/qnn-export/sepformer-whamr16k-4s.onnx`. Its shape is
`[1, 64000] -> [1, 64000, 2]`; ONNX Runtime versus PyTorch cosine agreement
on the real recording's first block was 0.99999994. This validates the float
export, **not** QAIRT quantization or NPU execution. The local QAIRT Windows
converter's documentation specifies Python 3.10, but this local model converted
and quantized under the isolated Python 3.12 environment.

## NPU feasibility follow-up

The four-second 16 kHz model converted to a 27.55 MB W8A16 DLC using 38 local
calibration windows. The iQOO 15 HTP **did not complete initialization**:
`qairt-net-run` reached about 8 GB resident memory, then aborted after 195
seconds with `Scudo ERROR: internal unmap failure (Out of memory)`. It never
ran an inference. This DLC must not be staged into the app.

A fixed two-second 16 kHz float graph was exported successfully as
`[1, 32000] -> [1, 32000, 2]` (ONNX/PyTorch random-input cosine 0.99999845).
On the same 5.461-second clip, end-aligned two-second windows produced five
blocks, and source 2 remained the likely retained voice (correlation magnitude
0.678 versus 0.721 for four-second blocks). This numerical difference is not a
perceptual quality verdict. The local float audition file
`clarity-ab-16k-2s-gentle-EXPERIMENTAL.wav` is in phone Downloads; it is not a
sanitized export. The two-second W8A16 model completed one block on the iQOO
HTP, but failed the existing output-fidelity gate against float ONNX: channel
cosines 0.9588/0.9652 and SI-SDR 10.57/11.34 dB (required: cosine at least
0.98 and SI-SDR at least 15 dB). It must not replace the app model.

The same two-second graph quantized to W16A16 (53.10 MB DLC) completed a
single HTP block and passed that gate: channel cosines 0.99999177/0.99999332,
SI-SDR 47.75/48.75 dB, and output RMSE 0.00802 against float ONNX. This
establishes one-block numerical fidelity, **not** privacy or app suitability.
The first uncached initialization took roughly three minutes. A high-level
QAIRT run embedded an HTP cache into the DLC, increasing it to 161.70 MB. That
runner silently consumed only half of each float32 input block, so its audio
outputs were rejected. The cached DLC was then run through the low-level
runner, which consumed all 128,000 bytes per block and completed all five
blocks in about 7.4 seconds total on the iQOO HTP. The reconstructed 5.461 s
candidate and float reference agreed closely: PCM WAV cosine 0.99999344 and
0.99999040 for sources 1 and 2, and 0.99999064 for the gentle postfilter.
That confirms NPU numerical agreement with this two-second float pipeline;
it does **not** establish that the protected voice is absent. The NPU preview
is in phone Downloads as
`clarity-ab-16k-2s-NPU-gentle-EXPERIMENTAL-NOT-SANITIZED.wav` for listening.
All test `.raw` files under `/data/local/tmp/prescience16k/` were removed
after pulling the results; the cached private model remains there.

The user chose to wait for NPU verification before app integration, and
accepts that a clearer 16 kHz export may still leak the protected voice.
The user approved the two-second NPU gentle preview for app integration after
listening. The installed iQOO app passed a native HTP smoke test and an
end-to-end private rehearsal using its existing four-sample Voice Passport:
one protected-only range muted, one overlap separated, zero safety mutes,
and a full-length 16 kHz export in about 9 seconds. That WAV remains a
review-before-sharing artifact, not evidence that leakage is absent.

The app's 16 kHz HTP, reconstruction, cleanup, and WAV-writing instrumentation
checks passed on the iQOO. A full legacy Android test-suite run was not clean:
several `VoiceRegressionTest` cases require private `test1.wav`, `test3.wav`,
and `test4.mp3` fixtures that are absent from this checkout. Those failures
do not establish a regression in the 16 kHz path; they also mean the entire
legacy suite cannot be claimed green.
