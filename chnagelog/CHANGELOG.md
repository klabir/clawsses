# Changelog

## [Unreleased]

### Breaking Changes

- None.

### Added

- Encrypted Android preference storage for OpenClaw, Rokid, voice, and TTS credentials.
- Automatic recovery of the Rokid connection and HUD after app lifecycle restarts.

### Changed

- OpenClaw connections now require WSS.
- Realtime transcription uses the current GA transcription-session protocol.
- Diagnostic logs record metadata only and no longer include chat, voice, device, or credential payloads.

### Fixed

- Prevented competing reconnect jobs after Android activity recreation.
- Disabled Android backup for both companion applications.

### Removed

- None.
