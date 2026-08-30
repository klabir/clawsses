# Clawsses Current Engineering State

This file is the canonical handoff for a new agent session or model. Read it together with
`AGENTS.md`, then verify every mutable fact against Git and the connected devices before acting.
Committed source and fresh runtime evidence override conversational memory and historical notes.

Last verified: 2026-08-30

## Current release

- Version: `1.3.116` / Build `125` source release
- Release commit: `ed9e605`; merged to `main` by `f91cbd4`
- Base main before release: `7b6ec26`
- Release branch: `build/124-session-identity` (cumulative Builds 124–125 source)
- Publication status: Build 125 source is merged and pushed on `main`; all private APKs remain
  unpublished.
- Public release policy: source only; never publish Phone/HUD APKs, signing material, Rokid
  credentials, private endpoints, or unsanitized vendor logs.

## Release commit stack

- Builds `119`–`123` are integrated on `main` by merge commit `7b6ec26`; the Build-123 source commit
  is `3243648` and includes the cumulative Builds 119–122 commits.
- Builds `124`–`125` are integrated by source commit `ed9e605` and merge commit `f91cbd4`.
- The complete 220-task public paired-release source gate passed for Build 125. No Build-125 APK
  was installed because device mutation was not authorized for this source release.
- The complete 220-task public paired-release gate passed for Build 123. Its private Phone/HUD
  artifacts have matching v2 signers, and the embedded HUD APK is byte-identical to the standalone
  private HUD APK.
- The official Build-123 CXR-L install callback returned success and launched the HUD. Final
  release verification is still pending a fresh matching `123/123` runtime handshake after the
  known firmware deep-sleep state is physically cleared.

## Verified hardware state

- The connected Pixel phone runs private hardware-test build `1.3.114` / Build `123`; installation,
  cold launch, resumed Activity, and live process are verified.
- The official Hi Rokid/CXR-L HUD upload and install returned `onInstallAppResult:true`, and the
  installer launched the bundled Build-123 HUD. The subsequent Clawsses ownership handoff is
  blocked on the glasses' known deep-sleep beacon state, so the fresh `123/123` handshake is not
  yet verified.
- Hi Rokid was restored to its original disabled state; Clawsses reclaimed the active glasses
  connection and the HUD foreground package is `com.clawsses.glasses`.
- The unrelated `com.rokidapks` utility also remains disabled.
- Build 125 source is merged and published on `main`; private APKs remain unpublished.
- The Build-119–125 commits contain no APKs or credentials.
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

## Current code rating

- Overall: **9.0/10** for the Build-125 source release. Cross-client identity and refresh races are
  contained behind typed, directly tested reconciliation boundaries, with 316 checked-in tests
  and the full public paired source gate green. Deployment confidence remains tied to Build 123
  until Build 125 receives a separately authorized paired-device gate.
- Architecture: **8.7/10**. Session subscription, message identity, and refresh coalescing now live
  in a tested coordinator, but `HudActivity` and the 1,480-line `OpenClawClient` remain the principal
  orchestration hotspots.
- Maintainability: **8.6/10**. Cross-client edge cases are explicit and directly tested; remaining
  risk is concentrated in lifecycle-heavy Activity and client orchestration.
- Performance confidence: **8.4/10**. Hot-path allocations and invalidation boundaries have
  improved over earlier releases, but Builds 119–122 were not followed by a fresh Macrobenchmark.
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

Build `125` has passed the full public source gate and is merged on `main`. Build `123` remains the
latest installed hardware-test pair and passed private artifact checks, Phone deployment, and the
official CXR-L HUD installation callback. A fresh matching runtime handshake is still required
after physically waking the known deep-sleep CXR beacon; Build 125 has not been installed.

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
