# Offline local model setup

ConsentCam uses the pinned `com.google.ai.edge.litertlm:litertlm-android:0.16.1`
runtime. Model files are deliberately excluded from Git and the APK.

Supported files, in preference order:

1. `gemma-3n-E4B-it-int4.litertlm` — 4,919,541,760 bytes, source revision
   `297ed75955702dec3503e00c2c2ecbbf475300bc`.
2. `gemma-3n-E2B-it-int4.litertlm` — 3,655,827,456 bytes, source revision
   `ba9ca88da013b537b6ed38108be609b8db1c3a16`.

Download the accepted-license model from its official
`google/gemma-3n-*-it-litert-lm` Hugging Face repository. Install and open the
app once, then use **Settings → Import model** to copy it through Android's
system file picker. The importer checks the exact pinned byte size, available
space, and writes through a temporary file before making the model usable. ADB
can also provision the external app directory without storage permissions:

```powershell
adb push C:\path\to\gemma-3n-E2B-it-int4.litertlm /sdcard/Android/data/com.example.consent_cam/files/models/
```

Open Settings, tap **Initialize**, then **Run local audit**. The panel reports
the actual model, GPU/CPU backend, inference count, trigger, latency, thermal
state, fallback, and zero cloud calls. During recording, non-identifying audits
run every 10 seconds, slow to 20 seconds at moderate thermal load, and stop at
severe load. Critical load closes the LLM while deterministic protection stays active.

The camera voice button uses the pinned `whisper.cpp` Android runtime and the
English-only `ggml-base.en.bin` model, followed by an allow-listed deterministic
command parser. Download the official model from the `ggerganov/whisper.cpp`
Hugging Face repository, then use **Settings → Import Whisper → Initialize**.
The importer requires exactly 147,964,211 bytes and verifies upstream SHA-1
`137c40403d78fd54d454da0f9bd998f78703390c`. The model is kept in private app
storage; each command WAV is private and deleted immediately after local
transcription. This runtime targets ARM64 Android phones.

Spoken status and summaries use on-device TextToSpeech in English or Tamil.
Voice-started video is silent so its microphone remains available for a spoken
stop command. Manually started video retains audio, so stop it manually before
issuing another voice command. The APK has no Internet permission.

The generic E4B/E2B artifacts advertise GPU and CPU execution. Qualcomm
NPU use requires a compatible SoC-specific model plus QAIRT and LiteRT dispatch
libraries and is therefore not claimed by this build.
