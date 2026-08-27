# Changelog

## [Unreleased]

### Changed

- Isolate the unauthenticated emulator WebSocket server in the phone debug source set; release builds now contain only a no-op provider and cannot package the raw listener implementation.
- Move OpenClaw, glasses protocol, AI activation, HUD state synchronization, notification relay, photo capture, and staged voice callbacks out of Compose into one process-scoped bridge controller.
- Route both primary and final phone-microphone voice recognition through one generation-gated staged-voice coordinator so cancelled callbacks cannot affect a newer capture.
- Reuse the external PCM peak calculation for both privacy-safe diagnostics and local speech detection instead of scanning every glasses audio frame twice.
- Declare Gson directly and remove unused Retrofit, converter, logging-interceptor, duplicate Core KTX, and ViewBinding configuration from the phone module.

### Added

- Add a release-APK verification gate that fails the build if the emulator-only raw WebSocket listener is ever packaged again.
- Add a distinct source-verified `1.3.56 / Build 65` paired artifact for deferred phone/glasses hardware validation.
- Add a distinct source-verified `1.3.55 / Build 64` paired artifact for deferred phone/glasses hardware validation.

### Fixed


## 1.3.54 (build 63)

### Fixed

- Scope every glasses-hotspot callback and Android network request to one installer attempt, ignore stale or duplicate firmware advertisements, verify the Rokid upload service on port 8848 through the selected local network, and start the SDK upload immediately after the verified handoff.
- Stop an active Rokid upload before cancellation cleanup, await installation callbacks without cross-thread polling, and keep the complete hotspot, fallback, and installation timeout budget below the outer operation limit.


## 1.3.53 (build 62)

### Added

- Add a confirmed `Restart glasses` control to the phone's Glasses settings that stops transient audio work, preserves Talk Mode configuration, and sends the official CXR-M firmware reboot command.


## 1.3.52 (build 61)

### Changed

- Own the glasses CXR bridge in the application process instead of recreating it with the HUD Activity, and start the phone runtime from permission/service lifecycle boundaries instead of Compose.
- Isolate session, agent, and model catalog flows from the phone root composition and collect settings-only flows only while Settings is visible.
- Keep the active glasses response in a 100 ms streaming state instead of copying and searching the complete HUD history for every text chunk.
- Replace the service-wide unlimited partial wake lock with bounded leases for recognition, reconnect, and APK transfer work.

### Added

- Add lossless 500-chunk HUD streaming coverage, deterministic wake-lock lease tests, and a process-mailbox recreation test.
- Accept the current Rokid firmware's explicit long-press assistant broadcast in the HUD while retaining the AI-start broadcast for wakeword compatibility.
- Show a prominent listening/processing banner on the HUD and log privacy-safe external PCM byte/peak telemetry for hardware diagnosis.

### Fixed

- Preserve the glasses CXR bridge and queued phone messages across HUD Activity recreation, remove the forced process kill on HUD exit, and prevent release builds from starting the unauthenticated debug transport.
- Stream glasses microphone audio directly through CXR-M when the firmware does not retain Android HFP/SCO, and recover activation from scene-status or exit callbacks when the legacy AI-key callback is omitted.

## 1.3.48 (build 57)

### Added

- Add a firmware/capability snapshot from `getGlassInfo` and `checkGlassVersion`, with a hardware-verified firmware policy and runtime-probed installer transports.
- Add separate Hi Rokid-style Follow-up and Always Listening interaction modes. Follow-up requires explicit activation and closes after a 12-second silent follow-up window; it is enabled by default when no explicit preference exists.

### Changed

- Keep the current glasses-hotspot installer as the preferred path and use only CXR-M's vendor-managed Wi-Fi Direct implementation as the legacy fallback.
- Validate Rokid credential pairs during configuration and add a public-release verification task that rejects credential-bearing APK builds.

### Removed

- Remove the experimental parallel Android Wi-Fi Direct discovery and group-owner override that could not observe the firmware's vendor-only peer.
- Remove the obsolete phone-side page-step selector and `scroll_settings` wire message; every glasses swipe now moves exactly one fixed HUD page so content cannot be skipped.

## 1.3.45 (build 54)

### Fixed

- Route streamed glasses TTS over Rokid's stable SCO speech channel instead of relying on an accepted but inaudible A2DP route, while retaining fast PCM startup and Permanent Talk capture recovery.
- Reopen the Clawsses HUD after a Clawsses-initiated Rokid AI-scene exit even when the firmware omits the launcher foreground callback.

## 1.3.43 (build 52)

### Fixed

- Invalidate the active recognition cycle when TTS begins so a late or echoed voice callback cannot immediately stop the newly routed glasses playback.

## 1.3.42 (build 51)

### Fixed

- Proactively request the glasses SCO route and wait up to 500 ms for Android to expose it before creating a Permanent-Talk-Mode TTS track, covering reconnects that resume directly in a waiting state.

## 1.3.41 (build 50)

### Fixed

- Preserve and explicitly target the glasses SCO route for TTS while Permanent Talk Mode is using the glasses microphone, avoiding the phone-speaker fallback while A2DP reconnects.

## 1.3.40 (build 49)

### Fixed

- Pin streamed PCM speech to the detected Rokid A2DP output instead of following Android's global media target, while retaining SCO only as a fallback when A2DP is unavailable.

## 1.3.39 (build 48)

### Changed

- Enable Permanent Talk Mode by default on fresh installs while preserving an existing explicit user choice.

### Fixed

- Prebuffer the first half-second before starting `AudioTrack` and preserve 16-bit PCM frame alignment across arbitrary HTTP response chunks, preventing zero-byte writes and immediate playback aborts on the Pixel/Rokid route.

## 1.3.38 (build 47)

### Fixed

- Restart and prefer the official glasses-hotspot transport before attempting Wi-Fi Direct so current firmware does not incur two guaranteed P2P timeouts and stale hotspot state is cleared before each installation.
- Fall back to the official Rokid glasses-hotspot transport when Android Wi-Fi Direct cannot discover the firmware's vendor-reported peer; connect ephemerally without logging or persisting hotspot credentials and upload through the SDK's IP-address overload.
- Prefer the updated Rokid glasses as Wi-Fi Direct group owner during APK transfer so their local upload server remains reachable with the August 2026 firmware; retain CXR-M discovery and connection-state callbacks.

## 1.3.33 (build 42)

- Restore the hardware-verified Rokid CXR-M 1.0.9 release after confirming that no glasses firmware update had occurred and rejecting the local 1.2.2 compatibility trial, which regressed Wi-Fi Direct group formation.
- Log only the non-sensitive glasses system and assistant versions after a successful CXR connection so future firmware compatibility decisions use direct evidence.
- Build 41 was an internal compatibility test and was not published.

## 1.3.31 (build 40)

- Stream OpenAI TTS as 24 kHz PCM directly into Android `AudioTrack` so playback can begin with the first audio bytes instead of waiting for a complete MP3 download; prefetch later chunks through a bounded queue while the first chunk plays.

## 1.3.30 (build 39)

- Start TTS with a sentence-aware 400-character maximum first chunk, retain 1,500-character follow-up chunks, and log privacy-safe synthesis/playback latency metrics.
- Bound the OpenAI Realtime connection pre-buffer to the latest two seconds of PCM so a stalled session cannot grow phone memory indefinitely.
- Decode queued-photo previews and chat attachments outside the Compose main thread to prevent large images from stalling the phone UI.
- Await model-selection completion through its StateFlow instead of polling every 50 ms.

## 1.3.29 (build 38)

- Recover the Clawsses HUD only after a Clawsses-initiated AI-scene exit, using a bounded one-shot recovery window.
- Respect manual launcher and third-party app foreground changes by cancelling any pending HUD recovery before it can reclaim the display.

## 1.3.28 (build 37)

- Increase the Rokid HUD bottom navigation safe inset from 24 dp to 32 dp while keeping the top status inset at 24 dp.
- Build 36 was used only for paired-device validation and was not published.

## 1.3.26 (build 35)

- Give the Rokid HUD bottom navigation the same 24 dp optical safe inset as the top status area, with automatic chat repagination for the reduced viewport.

## 1.3.25 (build 34)

- Present the Rokid model picker as a horizontal card row, with forward moving to the next model and back moving to the previous model across fixed page boundaries.

## 1.3.24 (build 33)

- Replace the primary Rokid `Agent` menu item with a paged `Model` picker for the active OpenClaw session.
- Keep Agent and Model semantics separate by showing Agent under `More` only when multiple agents are available.
- Validate model catalog, availability, active session, and idle run state on the phone before using the existing narrow `sessions.model.select` gateway route.
- Keep model pages and selection results within the reliable, acknowledged Rokid CXR transport limit.

## 1.3.23 (build 32)

- Replace Rokid's continuously scrolling chat list with measured, fixed HUD pages based on the active display height, width, font size, message padding, and thumbnails.
- Fill the current page without moving existing lines, then advance exactly once when streaming creates a new page.
- Navigate previous/next pages by gesture, freeze the current page while reading older content, show a new-text indicator, and resume live following only at the latest page.
- Preserve a message-and-character anchor across history prepends, font changes, HUD position changes, and state replay.
- Rename the phone setting to `Pages per gesture` while retaining the legacy CXR field for staged-update compatibility.
- Build 31 was not distributed; its per-delta scroll stabilization is incorporated into this release.

## 1.3.21 (build 30)

- Start long TTS responses after the first bounded MP3 chunk is synthesized, produce the remaining chunks in the background, and play the valid files sequentially instead of byte-concatenating independent MP3 streams. Stop Voice cancels and cleans the complete synthesis/playback queue.

## 1.3.20 (build 29)

- Add privacy-safe JankStats monitoring for the phone and Rokid HUD without recording chat, identifiers, paths, or credentials.
- Add reproducible Pixel startup macrobenchmarks with recorded cold- and warm-start results and Perfetto traces.
- Generate and package phone Baseline and Startup Profiles from the permission-complete startup journey.

## 1.3.19 (build 28)

- Coordinate phone and Rokid chat scrolling through one policy per surface.
- Preserve stable message and pixel-offset anchors when older history is prepended.
- Follow streaming output only while the reader is already at the tail, and replace unbounded scroll distances with bounded, cancellable jobs.

## 1.3.18 (build 27)

- Decode attachment bytes directly instead of performing a ByteArray-to-Base64-to-ByteArray round trip.
- Cache only bounded compressed HUD thumbnails and deduplicate concurrent thumbnail work.
- Derive camera previews and HUD thumbnails from the existing capture bitmap instead of decoding the JPEG twice.

## 1.3.17 (build 26)

- Serialize phone-to-glasses CXR traffic through a bounded FIFO transport while preserving dependent message order.
- Coalesce safe transient updates and add acknowledged retry delivery for final state when both peers support the Build 26 transport protocol.
- Segment long final answers and history snapshots into bounded UTF-8 packets so reliability metadata cannot exceed the Rokid command limit.

## 1.3.16 (build 25)

- Coalesce high-frequency OpenClaw stream deltas into bounded 64 ms phone/HUD publications while preserving immediate lossless final delivery.
- Keep Rokid, gateway, voice, Talk Mode, and TTS managers in one process-scoped runtime instead of recreating them with Compose.
- Isolate phone chat streaming and Rokid battery/time telemetry into lifecycle-aware Compose state boundaries.

## 1.3.15 (build 24)

- Normalize punctuation around polite stop phrases such as `Stop the voice output, please.` so they remain local TTS controls instead of becoming chat prompts.
- Verify that stopping spoken output does not abort the OpenClaw run, disable Talk Mode, change the TTS preference, or discard the response text.

## 1.3.14 (build 23)

- Expand local TTS-stop recognition with `stop voice output`, `stop TTS output`, `stop playback`, and optional English or German polite words.
- Keep a bare `stop` excluded so existing Talk Mode and run controls remain unambiguous.

## 1.3.13 (build 22)

- Add explicit English and German voice commands that stop only the current spoken chat answer.
- Allow a deliberate Rokid AI-key activation during TTS to open a short recognition window while automatic recognition restarts remain blocked.

## 1.3.12 (build 21)

- Pause glasses-sourced Talk Mode when the Rokid display enters standby and resume with a fresh recognition cycle after confirmed wake activity.
- Ignore stale OpenAI Realtime and Android recognizer callbacks after standby, cancellation, or restart.

## 1.3.11 (build 20)

- Add a transient Rokid HUD progress panel for privacy-filtered reasoning phases, tool activity, and active plan steps until visible answer streaming begins.
- Exclude private reasoning, tool arguments and results, paths, secrets, and raw gateway call IDs from the glasses protocol.

## 1.3.10 (build 19)

- Split complete Rokid history snapshots into bounded CXR packets and apply them atomically on the HUD.
- Preserve full long-answer text after state replay or session changes instead of truncating it to a short compact-history preview.

## 1.3.9 (build 18)

- Synchronize the effective OpenClaw session model to the Rokid display after model selection, session changes, reconnects, and HUD state requests.
- Route TTS through the active Rokid SCO voice path and prevent pending Talk Mode restarts from reacquiring the microphone during playback.

## 1.3.8 (build 17)

- Add a phone-side Model selector matching OpenClaw WebChat's configured model catalog, with unavailable models visibly disabled.
- Keep model selection separate from the persistent Agent selector and use the narrow write-scoped `sessions.model.select` gateway method.

## 1.3.7

- Label the canonical OpenClaw main session `Home` and pin it to the first glasses session page.
- Suppress Home's derived-title duplicate on later pages without skipping server pagination offsets.

## 1.3.6

- Reopen the Clawsses HUD when Rokid's launcher unexpectedly takes foreground while CXR remains connected.
- Throttle duplicate launcher callbacks so one firmware transition produces at most one recovery request.
- Keep one HUD activity/bridge instance and request a compact state replay when that instance resumes.

## 1.3.5

- Send a bounded recent-history snapshot after session switches instead of an oversized full-history payload.
- Use compact history fields and UTF-8 byte limits so Rokid CXR commands remain valid.
- Keep complete history on the phone and disable glasses-side automatic history expansion for this stable branch.

## Historical feature set

### Added

- Encrypted Android preference storage for OpenClaw, Rokid, voice, and TTS credentials.
- Automatic recovery of the Rokid connection and HUD after app lifecycle restarts.
- Photo-only OpenClaw messages with image thumbnails retained in chat history.
- Optional gallery storage for captured photos under `Pictures/Clawsses`.
- Voice commands to capture a photo or capture and send it immediately.
- Privacy-preserving thinking/reasoning phase indicators on the Rokid HUD.
- OpenAI `gpt-4o-mini-tts` alongside ElevenLabs, with selectable voices and shared encrypted OpenAI credentials.
- TTS stop and replay controls on both the phone and glasses.
- Agent selection on the phone and Rokid HUD using the read-only `agents.list` gateway method.
- Targeted cancellation of the active OpenClaw run from the phone or glasses.
- Persistent hands-free Talk Mode with immediate send, automatic listen-after-reply, and AI-key interruption.
- Actionable, expiring HUD cards for proactive OpenClaw updates.
- Privacy-first Android notification cards with an exact package allowlist and notification-listener opt-in.
- Live microphone captions with optional OpenAI translation and a configurable target language.
- Vision commands `read this`, `translate this`, `identify this`, and `remember this` (plus German equivalents) that capture and send a photo with a task-specific prompt.
- A safe Clawsses-to-Hi-Rokid handoff that releases the CXR connection before opening the official app.
- A persistent phone setting for one to five chat messages per Rokid scroll gesture, defaulting to one.

### Changed

- OpenClaw connections now require WSS.
- Realtime transcription uses the current GA transcription-session protocol.
- Diagnostic logs record metadata only and no longer include chat, voice, device, or credential payloads.
- Rokid camera captures now target 1280×720 and only small thumbnails cross the glasses command channel.
- Phone and glasses chat views preserve the reader's position and reach the true end of long messages.
- Glasses releases now use explicit build versions and report the running version to the phone for post-install verification.
- Phone and glasses releases now share one version source, and the phone settings show both packaged app versions.
- Raw private reasoning, tool arguments, tool results, paths, and error payloads remain excluded from the glasses protocol.
- Phone build variants now consume generated matching HUD assets instead of mutating `src/main/assets`; release-derived variants bundle the minified release HUD rather than the debug APK.

### Fixed

- Prevented competing reconnect jobs after Android activity recreation.
- Disabled Android backup for both companion applications.
- Updated Rokid CXR-M from 1.0.8 to hardware-verified 1.0.9, moved its Handler-based transfer lifecycle to the Android main thread, added a bounded retry, restored Bluetooth UI state after failures, and ignored non-JSON vendor status commands on the app protocol channel. CXR-M 1.2.2 was rejected because it consistently tore down P2P group negotiation with the current glasses firmware.
- The Rokid session picker now opens immediately and reports loading or gateway failures instead of appearing unresponsive.
- New Session now uses the least-privilege `sessions.create` gateway method instead of the admin-only `sessions.reset` method.
- Session pages sent to the glasses are compact and limited to three entries, with a `More...` row for paging through the remaining sessions.
- `+ New Session` remains actionable while the first session page is loading, so a delayed list request cannot lock the picker.
- Oversized CXR commands are rejected by UTF-8 byte size instead of being truncated into invalid JSON.
- Added a top safe inset to prevent the Rokid optical compositor from reflecting the status row below the intended HUD area.
- Enforced the phone-to-glasses CXR queue's hard capacity under critical-message saturation, while coalescing supersedable state and reporting rejected ordered packets.
- Serialized OpenClaw reconnect attempts with connection generations, one cancellable reconnect job, and bounded exponential backoff so stale WebSocket callbacks cannot replace newer connection state.

### Removed

- None.
