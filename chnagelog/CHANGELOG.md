# Changelog

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

## [Unreleased]

### Breaking Changes

- None.

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

### Removed

- None.
