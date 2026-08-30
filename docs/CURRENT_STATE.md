# Clawsses Current Engineering State

This file is the canonical handoff for a new agent session or model. Read it together with
`AGENTS.md`, then verify every mutable fact against Git and the connected devices before acting.
Committed source and fresh runtime evidence override conversational memory and historical notes.

Last verified: 2026-08-30

## Current release

- Version: `1.3.120` / Build `129`, source-published and paired-device verified
- Build 130: `1.3.121` / source commit `2c8544c` on `build/130-dictation-kws-spike`;
  source-gated, locally committed, not pushed, and verified with a fresh temporary `130/130`
  Phone/HUD deployment and runtime handshake
- Current candidate: `1.3.122` / Build `131` on `build/131-local-wake-validation`;
  source-gated and paired-device verified, but not merged into `main` or released
- Release commits: Build 128 `c8d3a1a`; Build 129 `96a8192`; main integration merge `602f484`
- Base main before integration: `d06ed1d`
- Release branch: `build/129-orchestrator-boundaries`, integrated into local and remote `main`
- Publication status: Builds 128-129 are merged and pushed on `main`; all private APKs remain
  unpublished. GitHub Actions source/release and API-35 instrumentation jobs passed on `602f484`.
- GitHub release: source-only `v1.3.120` / Build 129, published from exact source merge `602f484`
  with no binary assets.
- Public release policy: source only; never publish Phone/HUD APKs, signing material, Rokid
  credentials, private endpoints, or unsanitized vendor logs.

## Release commit stack

- Builds `119`–`123` are integrated on `main` by merge commit `7b6ec26`; the Build-123 source commit
  is `3243648` and includes the cumulative Builds 119–122 commits.
- Builds `124`–`125` are integrated by source commit `ed9e605` and merge commit `f91cbd4`.
- Build `126` is source commit `9f7927f`; Build `127` is source commit `1fa8c14`; both are integrated
  into local `main` by merge commit `f1f8102`.
- Build `128` is source commit `c8d3a1a`; Build `129` is source commit `96a8192`; both are integrated
  and pushed on `main` by merge commit `602f484`.
- The complete 220-task public paired-release source gate passed for Build 125. No Build-125 APK
  was installed because device mutation was not authorized for this source release.
- The complete 220-task public paired-release gate passed for Build 123. Its private Phone/HUD
  artifacts have matching v2 signers, and the embedded HUD APK is byte-identical to the standalone
  private HUD APK.
- Build 127 passed the complete 220-task source gate and both Pixel Startup Macrobenchmarks. Its
  private Phone/HUD artifacts have matching v2 signers and a byte-identical embedded HUD APK.

## Verified hardware state

- The connected Pixel phone currently runs private hardware-test build `1.3.122` / Build `131`;
  data-preserving installation, resumed Activity, live process, and restored OpenClaw connection
  are verified. The HUD also runs Build `131`; the official CXR-L installer returned successful
  install and launch callbacks, and Clawsses verified the matching live peer build.
- Build 130 was installed on both Phone and HUD through the official CXR-L path, returned true
  install and launch callbacks, restored Clawsses ownership, and produced a fresh matching
  `130/130` state handshake before the Phone advanced to Build 131.
- Build 131's minified Phone release loads its pinned sherpa-onnx JNI runtime and model on the Pixel,
  enters `listening`, opens a 16 kHz `VOICE_RECOGNITION` capture, and releases that capture when
  disabled. Wake-phrase accuracy, false accepts, soak behavior, and power cost remain unverified.
- After Build 131 installation, Hi Rokid was restored to `disabled-user`, Clawsses reconnected,
  the SDK compatibility check passed, and the HUD foreground package was `com.clawsses.glasses`.
- After a full firmware reboot, the standalone `com.rokidapks` helper completed the Build-129 HUD
  upload and installation through the official Hi Rokid/CXR-L CUSTOMAPP path. The successful run
  formed a fresh Wi-Fi Direct group, completed DHCP DISCOVER/OFFER/REQUEST/ACK, and returned a true
  install callback.
- Hi Rokid was restored to its original disabled state; Clawsses reclaimed the active glasses
  connection and the HUD foreground package is `com.clawsses.glasses`.
- The unrelated `com.rokidapks` utility also remains disabled.
- Build 129 source is merged and pushed on `main`; private APKs remain unpublished. Build 129 Phone
  and HUD are installed. After Clawsses reclaimed ownership, the HUD foreground package was
  `com.clawsses.glasses` and a fresh `129/129` state handshake plus SDK compatibility check passed.
- The Build-119–129 commits contain no APKs or credentials.
- The public paired-release evidence records a byte-identical embedded and standalone HUD APK.
- The verified Rokid firmware line is `1.24.012`; paired behavior remains firmware-sensitive.

Runtime state is not permanent evidence. Before a new install or hardware claim, resolve devices
with `adb devices -l` and collect a fresh version handshake.

## Build 106–108 changes

- Build 106 sets CXR-M vendor logging to `WARN` before SDK initialization so credential-bearing
  vendor INFO calls are not enabled in production-capable processes.
- Build 107 isolates the Android hotspot request and process binding behind tested contracts.
- API 35 and newer explicitly request `NET_CAPABILITY_LOCAL_NETWORK`; API 29–34 retain the
  existing request behavior.
- Build 108 replaces separate production updater controls with one action that prefers the
  official Hi Rokid bridge and falls back to a connected CXR-M route.
- Build 108 persists a credential-free installer transaction, restores interrupted state after
  process death, and requires a matching live HUD handshake after installation.
- Installer jobs and vendor callbacks are disposed with the process-scoped runtime.

## Build 109–113 changes

- Build 109 moves DADB and the Wi-Fi ADB installer to debug-only source/dependency graphs, disables
  app-owned cleartext traffic, and adds a release APK isolation check.
- Build 110 adds a bundled HUD manifest and rejects hash, package, version, or signer mismatches
  before either production installer route transfers the artifact.
- Build 111 retains one hash-verified last-known-good HUD artifact only after a matching live peer
  handshake. Automatic rollback remains disabled because neither verified vendor API exposes a
  safe downgrade contract.
- Build 112 completes the sealed typed Phone-to-HUD decoder and removes the legacy `JSONObject`
  production dispatcher.
- Build 113 keeps the `OpenClawClient` API stable while separating transport/auth, chat-run, and
  catalog/session state into tested internal components.
- Release commits, in order: `0d0a60f`, `cf6ba5c`, `010bc9a`, `47c5fdc`, `76e00ef`.
- The private Build-113 Phone APK contains a HUD artifact whose SHA-256 matches the standalone
  private HUD APK, and its embedded manifest reports package `com.clawsses.glasses`, Build `113`,
  version `1.3.104`, with a match-host signer policy. The matching runtime handshake was confirmed
  after the official CXR-L installation.

## Build 114–118 release changes

- Build 114 adds API-35 instrumentation regression coverage for both Phone and HUD and runs both
  suites in the Android CI emulator job. Both suites pass on the API-35 CI emulator; locally they
  compile, while only the physical release-test phone is connected.
- Build 115 moves typed Phone-to-HUD decoding, replay detection, ACK policy, and malformed-message
  handling out of `HudActivity` into a tested `HudPhoneMessageController`.
- Build 116 moves OpenClaw agent/model catalog and session-page projection into the tested
  `OpenClawCatalogSessionComponent`.
- Build 117 makes OpenClaw chat-event decisions explicit and tested in `OpenClawChatRunComponent`,
  including stale runs, inactive-session unread state, abort races, and terminal cleanup.
- Build 118 moves the session/model/agent picker overlays from `HudScreen.kt` into
  `HudPickerOverlays.kt`; `HudScreen.kt` is now 1,595 lines and `OpenClawClient.kt` is 1,345 lines.
- The release has 280 checked-in `@Test` cases across JVM and instrumentation sources. The full
  public `verifyPairedRelease` gate passed at every candidate stage.

## Build 119–122 release changes

- Build 119 moves deterministic Phone-to-HUD state effects out of `HudActivity` into the tested
  `HudPhoneMessageEffectPlanner`; streaming, voice, camera, wake, ACK, and replay ownership remain
  at their established runtime boundaries.
- Build 120 moves thumbnails, staged input, menu navigation, clock, and battery telemetry into
  `HudInputSurfaces.kt` with API-35 HUD instrumentation coverage.
- Build 121 moves primary content/input/menu gesture decisions into the pure, tested
  `HudInteractionPlanner`, leaving hardware and transport side effects in `HudActivity`.
- Build 122 moves gateway request IDs, pending-response correlation, late-response rejection, and
  disconnect failure into the tested `OpenClawRequestCoordinator` behind the stable client API.
- `HudActivity.kt` is now 1,706 lines, `HudScreen.kt` 1,215 lines, and `OpenClawClient.kt` 1,335
  lines. The repository has 310 checked-in `@Test` cases across JVM and instrumentation sources.
- These builds are structural refactors with behavior gates; no runtime performance improvement is
  claimed without a same-device benchmark.

## Build 123 release changes

- Advertise session-scoped event support, subscribe to broad session changes, and subscribe to the
  active session transcript before taking an authoritative history snapshot.
- Reconcile live messages by canonical ID, idempotency key, and monotonic message sequence; stale
  sessions are rejected and identical text with distinct IDs remains distinct.
- Refresh authoritative history after terminal runs, sequence gaps, reconnects, and identity-only
  transcript invalidations while coalescing duplicate refresh triggers.
- The repository has 312 checked-in `@Test` cases across JVM and instrumentation sources.

## Build 124 release changes

- Read persisted transcript identity, idempotency, and sequence from the gateway's canonical
  `message.__openclaw` metadata before using legacy direct fields or envelope fallbacks.
- Preserve one canonical ID across live events and history so optimistic Glasses messages can be
  replaced without a transient duplicate.
- Test against the gateway's actual nested metadata shape instead of a simplified local fixture.

## Build 125 release changes

- Retain exactly one trailing authoritative-history refresh when one or more invalidations arrive
  during an active refresh.
- Use unique refresh claims so stale completion after reconnect or session replacement cannot
  release or suppress newer reconciliation work.
- Cover the real gateway payload through parsing, optimistic correlation, and bounded-store
  replacement in one regression test.

## Build 126 candidate changes

- Move active-session subscription, authoritative history reconciliation, refresh ownership, and
  bounded pagination into `OpenClawActiveSessionRuntime` behind the stable client API.
- Preserve session epochs and gateway contracts while directly testing subscription replacement,
  stale history rejection, and bounded expanded-history claims.
- Reduce `OpenClawClient.kt` from 1,485 to 1,342 lines without changing Phone/HUD protocol or
  Rokid ownership.

## Build 127 candidate changes

- Move HUD session, model, and agent picker transitions and typed command intents into the pure,
  tested `HudCatalogInteractionController`.
- Preserve command/state ordering, bounded session and model pagination, and busy/error picker
  behavior while retaining transport, CXR, camera, audio, and lifecycle ownership in the Activity.
- Reduce `HudActivity.kt` from 1,706 to 1,513 lines and raise checked-in test coverage to 331 tests.

## Build 128 candidate changes

- Make the paired-release gate run HUD and shared release JVM tests plus HUD/shared lint directly,
  instead of requiring separate manual verification commands.
- Generate a JaCoCo report for deterministic Phone, HUD, and shared release logic and fail below
  70 percent aggregate line coverage. The Build-128 candidate measures 1,236 covered of 1,735
  selected lines, or 71.2 percent.
- Add explicit Android 12 cloud-backup and device-transfer exclusions to both apps and make HUD
  preference persistence asynchronous during Activity teardown.
- Add direct coverage for content, input, and menu gesture branches. The expanded paired-release
  gate passes 265 tasks and retains credential, debug-transport, HUD-isolation, and embedded-HUD
  hash verification.

## Build 129 candidate changes

- Move bounded-rate Phone stream scheduling and publication out of `OpenClawClient` into the
  directly tested `OpenClawStreamPublisher`; the client retains its existing coroutine scope and
  callback ownership.
- Move HUD stream accumulation decisions out of `HudActivity` into `HudStreamController`; the
  Activity still owns the delayed lifecycle job, visible stream flow, metrics, and state reducer.
- Move model soft-wrap handling into `HudContentNormalizer` and preserve fenced Markdown code
  blocks, closing an uncovered formatting edge case found by the new tests.
- Reduce `HudActivity.kt` from 1,513 to 1,470 lines, raise checked-in coverage to 343 tests, and
  measure 1,297 covered of 1,794 selected deterministic lines, or 72.3 percent.
- The full 265-task public paired-release source gate passes with 231 Phone debug tests, 229 Phone
  release tests, 85 HUD release tests, and 10 shared release tests. Build 129 is now installed and
  paired-device verified; no new runtime-performance claim is made without a same-device benchmark.

## Build 130 candidate changes

- Add an explicit Phone-microphone long-dictation mode behind the existing OpenAI Voice setting;
  Talk Mode, live captions, and direct Rokid audio retain their established realtime path.
- Bound recordings to five minutes and 9.6 MB of PCM, stream capture to an app-cache WAV file,
  upload from disk, and remove temporary audio on all terminal paths.
- Keep mode selection and WAV framing as deterministic tested policy, while microphone, lifecycle,
  secure-key access, and network ownership stay in their existing Android runtime boundaries.
- Advance both applications to `1.3.121` / Build `130`. The complete 265-task public paired-release
  source gate passes, including lint, JVM tests, the 70-percent selected-logic coverage threshold,
  public-artifact isolation, matching embedded HUD, and shared version evidence.
- A separate Apache-2.0 sherpa-onnx wake-word spike validates dependency provenance, an arm64-only
  package, the compiled model-construction path, and a standalone APK build. It remains outside
  production because runtime construction, device accuracy, false-accept rate, microphone
  coexistence, and power cost are not yet measured.

## Build 131 candidate changes

- Integrate the official Apache-2.0 sherpa-onnx `1.13.6` Android runtime and selected int8
  GigaSpeech 3.3M KWS assets with fixed SHA-256 provenance checks; no fork binary is used.
- Add an opt-in, default-off `HEY CLAWSSES` validation path owned by a process-scoped coordinator.
- Extend the existing audio-session coordinator with a lowest-priority wake-word lease. Foreground
  capture and playback preempt KWS, while KWS can never preempt Talk Mode, captions, dictation,
  ordinary recognition, or TTS.
- After detection, release the KWS microphone before starting the existing Realtime recognition
  path; send non-empty recognized text through the existing OpenClaw client.
- Expose phase, detection count, and fail-closed errors in Phone voice settings. Runtime model
  construction, accuracy, false accepts, battery cost, and long-soak coexistence remain hardware
  gates before the feature can graduate from experimental.
- Advance both applications to `1.3.122` / Build `131`. The complete 266-task paired source gate
  passes with pinned KWS provenance, credential/debug-transport isolation, matching Phone/HUD
  versions, and a byte-identical embedded HUD. The release Phone APK is arm64-only and 41.8 MB;
  debug remains multi-ABI for emulator CI. Private Phone/HUD artifacts have matching v2 signers;
  the official CXR-L install, launch, foreground ownership, SDK check, and live Build-131 peer
  verification all pass. The candidate remains unmerged and unreleased.

## Current code rating

- Overall: **9.2/10** from the latest detailed assessment. Build 129 is now paired-device verified;
  its active-session, HUD catalog, and stream orchestration boundaries have dedicated tested owners,
  with 343 checked-in tests and a matching live `129/129` deployment.
- Architecture: **9.0/10**. Session subscription, history reconciliation, and HUD picker decisions
  now live in tested coordinators; `HudActivity` and `OpenClawClient` remain the principal, but
  substantially smaller, orchestration hotspots.
- Maintainability: **8.9/10**. Cross-client and picker edge cases are explicit and directly tested;
  remaining risk is concentrated in lifecycle-heavy Activity and client orchestration.
- Performance confidence: **8.6/10**. A fresh Build-127 Pixel 9 Pro Macrobenchmark measured median
  time-to-initial-display of 64.4 ms warm and 261.0 ms cold. No improvement percentage is claimed
  because the Build-125 baseline disconnected before producing a valid complete result.
- Security/release discipline: **9.4/10**. Public artifacts remain credential-free, private
  artifacts stay unpublished, signer/hash/version gates match, and temporary Hi Rokid ownership is
  restored after deployment.

## Platform contracts

- Phone: compile SDK 35, target SDK 35, minimum SDK 28.
- HUD: compile SDK 35, target SDK 34, minimum SDK 28.
- `NET_CAPABILITY_LOCAL_NETWORK` is already requested on API 35+.
- Do not add `ACCESS_LOCAL_NETWORK` for the current target; reassess it only with the Android 17 /
  target-37 permission migration.
- Do not raise the HUD target above 34 or enable HUD minification without paired Rokid testing.

## Known limitations

- Rokid firmware can put the proprietary CXR beacon into deep sleep while Android still retains a
  valid Bluetooth bond. A triple press can re-advertise the CXR beacon; do not delete Android
  pairing as the first recovery step.
- The same deep-sleep behavior also occurs with the original Hi Rokid app, so it is not considered
  fully solvable in Clawsses alone.
- Hi Rokid must be enabled only for the verified CXR-L installer handoff and disabled again before
  Clawsses resumes ownership.
- HUD rollback is intentionally unavailable until a vendor-supported signed downgrade contract is
  verified; Clawsses does not use uninstall as a recovery shortcut.

## Parallel-work integration

- Do not merge the old `refactor/typed-protocol-controllers` worktree wholesale. It is based on an
  obsolete release line and must be selectively ported onto the current release branch.
- Preserve Build 108 release metadata when porting parallel work. Reconcile overlapping UI files
  manually and run the complete paired-release gate afterward.
- Review the separate README consolidation work before accepting its SDK-document deletions, and
  preserve the link to this handoff in the final README.

## Required verification

For source changes, run the narrowest modified tests and then:

```bash
./gradlew :phone-app:verifyPairedRelease --no-daemon
```

For a paired release, additionally require matching Phone/HUD versions, signatures, embedded-HUD
hash equality, install completion, fresh HUD launch, and a matching runtime state handshake.

Build `126` passed the full public source gate before local intermediate commit `9f7927f`. Build
`127` passed the 220-task public source gate, both Startup Macrobenchmark tests, and private paired
artifact version, v2 signer, and embedded-HUD hash checks. Its Phone APK is installed and running;
the official CXR-L HUD installation, launch, Clawsses ownership restoration, and matching `127/127`
handshake are verified. Hi Rokid is restored to disabled and Tailscale remains active. Build `128`
passes the expanded 265-task public source gate and its 70-percent deterministic-logic coverage
threshold; it has not been installed or paired-device verified. Build `129` also passes the
expanded gate with 72.3-percent selected coverage. Its matching private Phone/HUD artifacts passed
version, v2-signer, and embedded-HUD hash checks. Both apps are installed, the official CXR-L path
returned a successful install callback after a full glasses reboot, and a fresh matching `129/129`
runtime handshake is verified. No runtime-performance improvement is claimed without a same-device
benchmark. Build `130` passes the 265-task public source gate with matching `130` / `1.3.121`
Phone/HUD evidence and a byte-identical embedded HUD artifact; source commit `2c8544c` is local and
not pushed. Its private Phone/HUD artifacts passed matching signer and embedded-HUD checks, the
official CXR-L install and launch callbacks succeeded, and a fresh `130/130` runtime handshake was
observed. Build `131` passes its expanded 266-task public source gate with matching `131` /
`1.3.122` evidence, pinned official KWS artifacts, and a byte-identical embedded HUD. Its corrected
minified Phone build is installed and initializes the local KWS engine on Pixel hardware. The HUD
install and launch succeeded through the official CXR-L path, Clawsses verified the matching live
Build-131 peer, and Hi Rokid was restored to disabled. Build 131 remains unmerged and unreleased.

## New-session resume prompt

Use this when changing models or starting a new session:

> Resume Clawsses from the repository root. Read `AGENTS.md` and `docs/CURRENT_STATE.md`, then run
> `git status --short --branch` and inspect the latest Git history. Search memory only for decisions
> relevant to the current task. Treat committed source, this state file, and fresh device evidence
> as authoritative over old daily notes or session transcripts. Report any mismatch before making
> changes.

## Updating this file

Update this checkpoint only after a meaningful verified change such as a versioned release,
hardware gate, merge, or newly confirmed blocker. Keep historical narrative in the changelog or
daily memory; keep this file limited to the current operational truth.
