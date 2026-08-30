# Clawsses Current Engineering State

This file is the canonical handoff for a new agent session or model. Read it together with
`AGENTS.md`, then verify every mutable fact against Git and the connected devices before acting.
Committed source and fresh runtime evidence override conversational memory and historical notes.

Last verified: 2026-08-30

## Current release

- Version: `1.3.113` / Build `122`
- Release commit: `c269283`
- Base main: `f472f28`
- Release branch: `build/122-openclaw-request-coordinator` (local only)
- Publication status: Builds `119`–`122` are committed locally but are not pushed or merged;
  private APKs remain unpublished.
- Public release policy: source only; never publish Phone/HUD APKs, signing material, Rokid
  credentials, private endpoints, or unsanitized vendor logs.

## Release commit stack

- Builds `119`–`122` are cumulative release commits: `277f4f7`, `d87cfb2`, `a9885b5`,
  `c269283`.
- The complete 220-task public paired-release gate passed at every candidate stage. Final private
  Phone/HUD artifacts have matching v2 signers, and the embedded HUD APK is byte-identical to the
  standalone private HUD APK.
- The final official CXR-L install callbacks, HUD launch, and fresh matching `122/122` runtime
  handshake are verified. The release branch remains local and unmerged.

## Verified hardware state

- The connected Pixel phone and Rokid HUD both run private hardware-test build `1.3.113` / Build
  `122` from local commit `c269283`.
- The Phone installation, cold launch, resumed Activity, live process, official Hi Rokid/CXR-L HUD
  upload, install, launch, and fresh matching `122/122` peer handshake were verified.
- Hi Rokid was restored to its original disabled state; Clawsses reclaimed the active glasses
  connection and the HUD foreground package is `com.clawsses.glasses`.
- The unrelated `com.rokidapks` utility also remains disabled.
- Build 122 source is committed locally but not pushed or merged; private APKs remain unpublished.
- The Build-119–122 commits contain no APKs or credentials.
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

## Current code rating

- Overall: **8.8/10**. Correctness and release safety are strong: typed protocol boundaries,
  deterministic planners, public/private artifact isolation, 310 tests, the complete paired gate,
  and a verified physical `122/122` deployment.
- Architecture: **8.6/10**. The new planners and coordinator reduce mixed UI/transport state, but
  `HudActivity` and `OpenClawClient` remain the principal orchestration hotspots.
- Maintainability: **8.5/10**. Compose and interaction responsibilities are materially smaller and
  directly testable; remaining risk is concentrated in lifecycle-heavy Activity and client flows.
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

Build `122` has passed the final official CXR-L HUD installation and fresh matching `122/122`
runtime handshake. Its release branch is committed locally but is not pushed or merged.

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
