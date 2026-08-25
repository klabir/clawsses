# Build 25 performance baseline

Build 25 establishes the pre-transport-refactor baseline:

- OpenClaw stream publications are losslessly coalesced to a 64 ms cadence.
- Phone chat and glasses telemetry use isolated lifecycle-aware Compose state.
- Talk Mode, recognition, TTS, OpenClaw, and Rokid managers are process-scoped.
- A 1,000-delta unit workload produces one scheduled publication while preserving the full text.
- Full Gradle check, debug/release compilation, unit tests, Android lint, paired APK signatures,
  embedded-glasses equality, and physical Rokid standby/wake validation passed.

The Build 26 transport adds content-free counters for queued, sent, acknowledged, retried,
coalesced, dropped, and failed messages plus queue depth/high-water. These counters are logged at
stream completion and provide the quantitative baseline for subsequent hardware comparisons.
