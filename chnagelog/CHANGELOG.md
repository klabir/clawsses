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

### Changed

- OpenClaw connections now require WSS.
- Realtime transcription uses the current GA transcription-session protocol.
- Diagnostic logs record metadata only and no longer include chat, voice, device, or credential payloads.
- Rokid camera captures now target 1280×720 and only small thumbnails cross the glasses command channel.
- Phone and glasses chat views preserve the reader's position and reach the true end of long messages.

### Fixed

- Prevented competing reconnect jobs after Android activity recreation.
- Disabled Android backup for both companion applications.

### Removed

- None.
