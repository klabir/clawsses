# Clawsses Current Engineering State

This file is the canonical handoff for a new agent session or model. Read it together with
`AGENTS.md`, then verify every mutable fact against Git and the connected devices before acting.
Committed source and fresh runtime evidence override conversational memory and historical notes.

Last verified: 2026-08-30

## Current release

- Version: `1.3.104` / Build `113`
- Release commit: `76e00ef`
- Handoff commit: `32c81f6`
- Main merge: `649cc06047ee3d93c965d248b762f3e8842bf0ed`
- Release branch: `build/113-openclaw-components` (pushed)
- Publication status: source pushed and merged into `main`; private APKs remain unpublished.
- Public release policy: source only; never publish Phone/HUD APKs, signing material, Rokid
  credentials, private endpoints, or unsanitized vendor logs.

## Verified hardware state

- The connected Pixel phone and Rokid HUD both run the private hardware-test build `1.3.104` /
  Build `113` from local commit `76e00ef`.
- The Phone installation, cold launch, resumed Activity, live process, official Hi Rokid/CXR-L HUD
  upload, install, launch, and fresh matching `113/113` peer handshake were verified.
- Hi Rokid was restored to its original disabled state; Clawsses reclaimed the active glasses
  connection and the HUD foreground package is `com.clawsses.glasses`.
- Build 113 source is pushed and merged into `main`; private APKs remain unpublished.
- The Build-113 commits contain no APKs or credentials.
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
