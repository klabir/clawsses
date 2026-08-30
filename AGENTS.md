# Clawsses Project Instructions

These instructions are the authoritative agent guidance for this repository. They apply to
OpenClaw/Codex, Gemini in Android Studio, Claude Code, and other coding agents.

## Session startup and handoff

- Before answering about the current release or changing code, read `docs/CURRENT_STATE.md`, then
  verify mutable facts with `git status --short --branch` and the latest Git history.
- Treat committed source, current Git state, and fresh device evidence as authoritative over
  semantic memory, compacted conversations, old daily notes, or prior model conclusions.
- If `docs/CURRENT_STATE.md` disagrees with Git or hardware, stop and report the mismatch before
  changing source or devices.
- Update `docs/CURRENT_STATE.md` after a verified release, hardware gate, merge, or newly confirmed
  blocker. Do not put credentials, device serials, private endpoints, or unsanitized logs in it.

## Agent roles

- The primary coding agent is OpenClaw using the current approved latest OpenAI Codex model.
- Do not change the OpenAI model, provider, reasoning level, or OpenClaw routing unless Boss asks.
- Gemini in Android Studio is a secondary assistant for IDE-local Android work: Compose previews,
  inspections, Logcat, device inspection, and narrowly scoped edits.
- Do not let two agents modify the same Git worktree concurrently. Create a separate worktree per
  agent or task.
- OpenClaw is orchestration, not a build dependency. The repository must remain buildable with
  Gradle and Android SDK tooling alone.

## Stack

- Kotlin
- Android
- JDK 17
- Jetpack Compose where appropriate
- Gradle Kotlin DSL using the checked-in Gradle wrapper
- OpenClaw Gateway over private WSS
- Rokid CXR-M on the phone and CXR-S on the glasses

Modules:

- `phone-app`: Pixel companion app and OpenClaw/CXR-M bridge
- `glasses-app`: Rokid HUD application and CXR-S bridge
- `shared`: typed phone/HUD protocol
- `benchmark`: Android Macrobenchmark and baseline-profile support

## Rokid SDK rules

- Never invent a Rokid API, callback, constant, intent, service, or lifecycle guarantee.
- Before using an unfamiliar Rokid API, inspect in this order:
  1. local documentation under `docs/rokid-sdk/` or `docs/rokid-sdk-glasses/`;
  2. existing project usage and tests;
  3. the actual resolved AAR/JAR classes or vendor demo code.
- Keep the verified dependency boundary unless a dedicated hardware-tested change requires an
  upgrade:
  - CXR-M `com.rokid.cxr:client-m:1.2.2`
  - CXR-L `com.rokid.cxr:client-l:1.1.1` for the Hi Rokid installer fallback
  - CXR-S `com.rokid.cxr:cxr-service-bridge:1.0`
- Do not alter CXR ownership, reconnect ordering, installer phases, wake queues, audio ownership,
  or capability negotiation as a cosmetic refactor.
- Preserve vendor compatibility fallbacks unless a tested replacement exists.

## Hardware contracts

- Phone app: compile SDK 35, target SDK 35, minimum SDK 28.
- Glasses app: compile SDK 35, target SDK 34, minimum SDK 28.
- Do not raise the glasses target SDK above 34 without testing on the current Rokid firmware. The
  verified firmware returns API-35-targeted custom HUD applications to the launcher.
- Do not enable HUD minification/R8 without device-visible crash diagnostics and paired hardware
  verification. The current unminified HUD release is intentional.
- Phone and HUD are one compatible release pair. They must use the same `versionName` and
  `versionCode` from `gradle.properties`.
- Verify that the HUD APK embedded in the phone APK is the matching build before distribution.
- Do not install an APK, reboot a device, clear app data, unpair Bluetooth, or change Rokid firmware
  unless Boss authorizes that device mutation.

## Credentials and release isolation

- Never commit `local.properties`, signing keys, APKs, tokens, Rokid credentials, or unsanitized
  vendor logs.
- Public builds must contain neither private Rokid credentials nor unauthenticated debug transport.
- Private paired-device builds require an explicit local opt-in. Do not weaken the existing Gradle
  gates to make a build pass.
- Keep debug transport out of production phone and HUD APKs.
- Do not print credentials or full authentication payloads to Logcat, tests, CI, or chat.

## Architecture and lifecycle

- Prefer Kotlin coroutines, structured concurrency, `StateFlow`, and explicit ownership.
- Handle Android Activity, Service, process recreation, Bluetooth, Wi-Fi, audio focus, microphone,
  TTS, and CXR lifecycles explicitly.
- Make asynchronous callbacks generation-aware or otherwise reject stale callbacks.
- Keep phone/HUD protocol messages typed and versioned in `shared`.
- Preserve bounded queues, bounded history, attachment budgets, timeouts, cancellation, and
  backpressure.
- Never expose raw chain-of-thought. UI reasoning/status output must remain privacy-filtered.

## Build and verification

Use the checked-in wrapper, not a system Gradle installation.

Common commands:

```bash
# Targeted compilation or test while iterating
./gradlew :phone-app:testDebugUnitTest --no-daemon
./gradlew :glasses-app:testDebugUnitTest --no-daemon
./gradlew :shared:testDebugUnitTest --no-daemon

# Build both applications
./gradlew assembleDebug --no-daemon

# Required full gate after code changes
./gradlew :phone-app:verifyPublicReleaseHasNoRokidCredentials check --no-daemon
```

- Run the narrowest relevant test after each meaningful change.
- Before handoff or commit, run the full gate above and fix every failure.
- Test files that are created or modified must be run explicitly before the full gate.
- Documentation-only changes require link/format checks and `git diff --check`, not an Android
  build unless documentation changes executable samples or build configuration.
- Performance claims require before/after measurements on the same device and build variant.
- Macrobenchmarks must be run on a representative Android device; a successful APK build alone is
  not a performance result.
- CXR, HUD, audio, installer, camera, gesture, or lifecycle changes require controlled paired Pixel
  and Rokid hardware verification before release.

## Device tooling

- Prefer the official Android CLI for SDK inspection, documentation lookup, emulator management,
  layout dumps, screenshots, and repeatable agent workflows. Use `android info` before relying on
  an assumed SDK or device state.
- Use `android docs search` for current Android platform guidance. Rokid APIs remain governed by
  the separate vendor-documentation rules above and must not be inferred from generic Android/XR
  guidance.
- Resolve devices with `adb devices -l` before every install or device command.
- Use an explicit `-s <serial>` whenever more than one device or emulator is visible.
- `scrcpy` is appropriate for Pixel mirroring and interaction. It is not proof that the Rokid HUD
  path works.
- Use the phone application's verified CXR/hotspot/Hi Rokid installer flow for the glasses. Treat
  direct glasses ADB or mirroring as optional and firmware-dependent.
- Inspect filtered Logcat after device crashes, disconnects, installer failures, audio failures, or
  unexpected lifecycle transitions.
- The GitHub `phone-instrumentation` job is the baseline emulator gate. It validates Phone and HUD
  Compose instrumentation tests; paired Pixel/Rokid behavior still requires controlled hardware
  verification.

## Git and concurrent work

- Preserve changes from other agents and sessions.
- Inspect `git status`, branches, worktrees, and device versions before starting.
- Work in an isolated branch/worktree for each task.
- Stage only explicit files changed by the current task. Never use `git add .` or `git add -A`.
- Never use destructive cleanup commands, `git reset --hard`, `git clean`, or `git stash` to work
  around another session.
- Do not commit, merge, push, tag, publish, or install unless Boss explicitly authorizes it.
- A source commit is not a verified release until CI and the required paired-device checks pass.

## Documentation

- `README.md` is user-facing documentation.
- `AGENTS.md` is the canonical coding-agent instruction file.
- `docs/CURRENT_STATE.md` is the canonical cross-session engineering handoff.
- `CLAUDE.md` is only a compatibility pointer to this file and must not duplicate instructions.
- Update `VERSIONING.md` and the existing changelog structure when a distributable release changes.
- Do not alter released changelog sections retroactively.
