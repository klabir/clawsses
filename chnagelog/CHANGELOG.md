# Changelog

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

### Changed

- OpenClaw connections now require WSS.
- Realtime transcription uses the current GA transcription-session protocol.
- Diagnostic logs record metadata only and no longer include chat, voice, device, or credential payloads.
- Rokid camera captures now target 1280×720 and only small thumbnails cross the glasses command channel.
- Phone and glasses chat views preserve the reader's position and reach the true end of long messages.
- Glasses releases now use explicit build versions and report the running version to the phone for post-install verification.

### Fixed

- Prevented competing reconnect jobs after Android activity recreation.
- Disabled Android backup for both companion applications.
- Updated Rokid CXR-M to 1.2.2 after a 1.0.8 WiFi-P2P failure crashed inside the vendor SDK, moved its Handler-based transfer lifecycle to the Android main thread, added a bounded retry, restored Bluetooth UI state after failures, and ignored non-JSON vendor status commands on the app protocol channel.

### Removed

- None.
