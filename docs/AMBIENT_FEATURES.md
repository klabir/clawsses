# Ambient HUD features

Clawsses 1.3.0 adds five opt-in features to the phone/glasses pair.

## Proactive HUD cards

Assistant updates that arrive while the glasses are sleeping can appear as a short card. Swipe to select an action, tap to run it, or double-tap to dismiss it. Cards expire and the glasses retain at most five pending cards.

## Android notification cards

Notification forwarding is off by default and uses an exact package allowlist.

1. Open **Settings → Notifications** in the phone app.
2. Enter exact Android package names separated by commas, for example `com.google.android.gm`.
3. Tap **Grant notification access** and enable Clawsses.
4. Enable **Notification cards**.

Ongoing notifications, group summaries, and Clawsses' own notifications are ignored. Notification text is relayed only to the connected glasses and is not logged.

## Vision voice commands

The following exact commands capture a 1280×720 glasses photo and send it to the active OpenClaw session with a task-specific prompt:

- `read this` / `lies das`
- `translate this` / `übersetze das`
- `identify this` / `erkenne das`
- `remember this` / `merk dir das`

`remember this` asks the active OpenClaw agent to persist a concise note; whether it is retained depends on that agent's memory policy.

## Live captions and translation

Enable **Settings → Voice → Live captions** or use **More → Live Captions** on the glasses. Captions process consecutive short utterances. Optional translation uses the OpenAI API key already configured for voice recognition and therefore incurs API usage. Source text remains visible if translation fails.

Live captions and permanent Talk Mode are mutually exclusive because both need the microphone.

## Switch to Hi Rokid

Tap **Settings → Glasses → Switch to Hi Rokid**. Clawsses stops voice playback, releases its CXR connection, stops its connection service, and then opens the official app. If Hi Rokid is disabled or unavailable, Android opens its app details page instead.

Returning to Clawsses is manual: close or disconnect Hi Rokid, then open Clawsses and reconnect.
