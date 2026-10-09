# Local model asset lock

These are the models packaged in the Android app. They run entirely on-device.

| Asset | Purpose | Size (bytes) | SHA-256 |
| --- | --- | ---: | --- |
| `3dspeaker_eres2net_en_voxceleb_16k.onnx` | English enrolled-speaker verification for each VAD utterance | 26,485,263 | `C59158379255AD66E161679CCA6AF8D52D51E389E3224AB7D7A7BAAE295C2DB5` |
| `silero_vad.onnx` | Voice activity detection during enrollment | 643,854 | `9E2449E1087496D8D4CABA907F23E0BD3F78D91FA552479BB9C23AC09CBB1FD6` |

The active pipeline is Silero VAD utterance segmentation plus direct English
ERes2Net verification. Pyannote/TitaNet clustering was removed from the mute
decision after it collapsed several test4 speaker turns into one region. The
earlier WeSpeaker, CAM++, Chinese ERes2Net, Pyannote, TitaNet, and Reverb
experiments are retained under `models/archive/` and are not packaged in the
APK.

Changing any active model or preprocessing requires a new enrollment. Voice
embeddings from a different model are not compatible.

## Offline separation evaluation asset

`models/separation/speechbrain-sepformer-wsj02mix/` is ignored by Git and is
not shipped in the APK. It is the official SpeechBrain SepFormer WSJ0-2Mix
two-speaker checkpoint, downloaded for offline iQOO 15 feasibility work:

| File | Size (bytes) | SHA-256 |
| --- | ---: | --- |
| `masknet.ckpt` | 113,108,458 | `57DD5F49BF21C5A2101BB4E46648D05D34D517A59E26F0B06646D0BEBE8214C7` |
| `encoder.ckpt` | 17,267 | `3139BB880B29EA77AE8A168B8F2AD6E8EB5C2C0904289676C223D0E93CD2A35D` |
| `decoder.ckpt` | 17,202 | `ABEA1A2D41151331B4C36071D1B3205AED940A189721F008B12A703E9C63E7E4` |
| `hyperparams.yaml` | 1,515 | `939C86A8D36C52AE148859DE34A3E7B984F4B576213957BDBA09462CC88168BF` |

It is an Apache-2.0 research model. It has not been converted to ONNX/TFLite,
tested on Android, or accepted as a production dependency.
