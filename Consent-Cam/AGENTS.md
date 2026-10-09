# Repository Guidelines

## Project Structure & Module Organization

This repository currently contains planning and ownership documents; the Android project has not yet been scaffolded. Read `README.md` for the project entry point, then read the role guide relevant to your work: `rahul.md`, `yazeen.md`, or `ganesh.md`. Team status, blockers, interfaces, and handoffs belong in `progress.md`.

When scaffolding Android, keep application code under `app/src/main/java/`, resources under `app/src/main/res/`, bundled models under `app/src/main/assets/`, local unit tests under `app/src/test/`, and device tests under `app/src/androidTest/`. Prefer feature-oriented packages such as `ble`, `vision`, `camera`, `privacy`, and `agent`; place shared contracts in a dependency-neutral `core` package.

## Build, Test, and Development Commands

Run commands from the repository root after the Gradle wrapper is added:

- `./gradlew.bat assembleDebug` — build a debug APK on Windows.
- `./gradlew.bat testDebugUnitTest` — run JVM unit tests.
- `./gradlew.bat connectedDebugAndroidTest` — run instrumentation tests on an attached phone.
- `./gradlew.bat lintDebug` — run Android lint without rewriting sources.
- `adb devices` — confirm that the iQOO test phone is connected.

Record meaningful test results and the device/model backend used in `progress.md`.

## Coding Style & Naming Conventions

Use Kotlin with four-space indentation and standard Android/Kotlin formatting. Use `UpperCamelCase` for types and Compose functions, `lowerCamelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Name tests by behavior, for example `protectBeacon_withMultipleFaces_blursAll()`.

Keep BLE, recognition, camera effects, and LLM orchestration behind small interfaces. Avoid blocking the main thread; expose changing state with `StateFlow` and discrete events with `SharedFlow`.

## Testing Guidelines

Use JUnit for deterministic logic tests and AndroidX Test/Espresso or Compose UI tests for device behavior. Cover RSSI hysteresis, token validation, cosine matching, coordinate transforms, lifecycle cancellation, and backend fallback. Privacy-critical output must be verified in preview, saved photos, and recorded video on real hardware.

## Commit & Pull Request Guidelines

History currently contains only `Initial commit`, so use concise imperative commits such as `Add BLE proximity hysteresis`. Work on the assigned `feature/<name>-<area>` branch. Pull requests must describe behavior, tests, privacy impact, interface changes, and device results; include screenshots or recordings for camera/UI changes.

## Security & Agent Instructions

Never log or commit face images, embeddings, keys, model credentials, or captured media. Keep shipped inference offline. Before coding, read your role guide and `progress.md`; update only your owned progress block before and after substantive work.
