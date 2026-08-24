# Changelog

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
- Phone and glasses are versioned together as 1.3.7 (build 16).

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
