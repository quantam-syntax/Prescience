# BLE feature

This package keeps deterministic BLE behavior separate from Android framework adapters.

- `api`: stable values consumed by other features.
- `protocol`: the fixed 10-byte service-data wire format and consent flags.
- `security`: rotating HMAC token creation and replay-aware verification.
- `proximity`: per-session RSSI filtering, bands, hysteresis, and stale timeouts.
- `session`: verified in-memory session ownership and authentication expiry.
- `broadcast`: complete session state that produces ready-to-advertise service data.
- `scan`: raw service-data processing into verified proximity observations.
- `enrollment`: QR secrets, AES-GCM payload protection, and MTU-independent GATT chunks.
- `com.example.consent_cam.ble`: Android scanner/advertiser, encrypted GATT profile transport,
  privacy-zone presets, multi-profile session lifetime, and the connected-device foreground service.

The version-1 service UUID is `83c3b46e-0aef-4a4b-8b9e-44865c70a931`. Its service-data
payload is exactly 10 bytes and all multi-byte integers use big-endian/network byte order.

No package in `protocol`, `security`, or `proximity` may import Android APIs. Tests mirror
the production folder structure under `app/src/test/java/com/consentcam/ble`.

Normal ALLOW/PROTECT broadcasting and receiving is automatic, offline, and needs no QR or prior
contact. Precise biometric-profile exchange supports multiple temporary ALLOW/PROTECT profiles,
but retains QR authentication: removing every trust step would expose the face profile to an
active BLE man-in-the-middle. Profiles and session secrets remain memory-only on the recorder.
