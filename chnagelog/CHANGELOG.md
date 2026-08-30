# Changelog

## [Unreleased]

## 1.3.120 (build 129)

### Changed

- Move bounded-rate Phone stream publication scheduling out of `OpenClawClient` into a directly
  tested publisher while retaining coroutine ownership in the client.
- Move HUD stream accumulation decisions and model soft-wrap normalization out of `HudActivity`
  into deterministic tested controllers while retaining lifecycle jobs in the Activity.

### Fixed

- Preserve line breaks inside fenced Markdown code blocks instead of treating code lines as model
  soft wraps.

## 1.3.119 (build 128)

### Added

- Generate one JaCoCo report for deterministic Phone, HUD, and shared release logic and require at
  least 70 percent aggregate line coverage.

### Changed

- Run HUD and shared release JVM tests, HUD/shared lint, and coverage verification from the paired
  release gate instead of relying on separate manual commands.
- Disable Android cloud backup and device transfer explicitly through Android 12 data-extraction
  rules in both applications.

### Fixed

- Persist HUD preferences asynchronously during Activity teardown instead of blocking the main
  thread on a synchronous preference commit.

## 1.3.118 (build 127)

### Changed

- Move HUD session, model, and agent picker transitions into a pure interaction controller that
  emits typed command intents while retaining transport and lifecycle ownership in `HudActivity`.
- Preserve picker command ordering and pagination semantics with direct gesture-decision tests,
  reducing `HudActivity.kt` from 1,706 to 1,513 lines.

## 1.3.117 (build 126)

### Changed

- Move active-session subscription, authoritative history reconciliation, and bounded pagination
  from `OpenClawClient` into a dedicated runtime behind the existing client API.
- Preserve session epochs, refresh claims, gateway methods, callbacks, and history limits while
  directly testing stale responses and subscription replacement.

## 1.3.116 (build 125)

### Fixed

- Queue one bounded trailing history reconciliation when transcript invalidation arrives while an
  authoritative history refresh is already running.
- Isolate refresh ownership with unique claims so reconnects and session switches reject stale
  refresh completions without clearing newer work.

## 1.3.115 (build 124)

### Fixed

- Read canonical message ID, idempotency key, and sequence from the gateway's persisted
  `message.__openclaw` metadata before using direct-message or envelope fallbacks.
- Preserve the same canonical message identity in live session events and authoritative history so
  an optimistic Glasses message can be replaced by its durable echo without a duplicate row.

## 1.3.114 (build 123)

### Added

- Subscribe to active-session transcript events and broad session metadata changes exposed by the
  OpenClaw gateway.
- Reconcile cross-client messages by canonical message ID, idempotency key, and message sequence.

### Fixed

- Refresh the active transcript after foreign terminal runs, reconnect gaps, and identity-only
  transcript invalidations so WebChat and Clawsses converge on one authoritative history.
- Reject stale-session events while preserving distinct messages that happen to have identical
  text.

## 1.3.113 (build 122)

### Changed

- Move gateway request IDs, pending-response correlation, late-response rejection, and
  disconnect failure into a dedicated request coordinator behind `OpenClawClient`.
- Preserve wire methods and timeouts while directly testing duplicate, stale, and failed request
  lifecycles.

## 1.3.112 (build 121)

### Changed

- Move primary HUD content, staged-input, and menu gesture decisions into a pure interaction
  planner while keeping scrolling, voice, transport, and menu side effects in `HudActivity`.
- Cover focus push-through, photo removal, clear behavior, scroll decisions, and bounded menu
  navigation with local unit tests.

## 1.3.111 (build 120)

### Changed

- Move HUD thumbnails, staged input actions, menu navigation, clock, and battery telemetry into a
  dedicated Compose input-surface file.
- Preserve visible staging and menu semantics with API-35 instrumentation coverage while reducing
  the primary `HudScreen` source below 1,250 lines.

## 1.3.110 (build 119)

### Changed

- Plan deterministic Phone-to-HUD state effects outside `HudActivity` while retaining streaming,
  voice, camera, wake, and lifecycle work in the Activity.
- Preserve transport ACK and replay behavior while directly testing session, model, card, and
  runtime-owned message decisions.

## 1.3.109 (build 118)

### Changed

- Split session, model, and agent picker overlays from the primary HUD chat surface into a
  dedicated Compose component file.
- Preserve picker layout, focus, navigation labels, and visible state contracts while reducing
  the primary `HudScreen` source by more than four hundred lines.

## 1.3.108 (build 117)

### Changed

- Route gateway chat events through a tested chat-run planner before mutating streaming state.
- Make stale-run rejection, inactive-session unread handling, abort races, and terminal cleanup
  explicit decisions owned by the chat-run component.

## 1.3.107 (build 116)

### Changed

- Move session-page projection, agent catalog parsing, model fallback selection, and agent-list
  projection into the catalog/session component behind the stable `OpenClawClient` facade.
- Keep gateway methods and callback payloads unchanged while making catalog decisions directly
  testable without a WebSocket.

## 1.3.106 (build 115)

### Changed

- Move Phone-to-HUD decoding, bounded transaction replay detection, and transport
  acknowledgments behind a tested ingress controller.
- Keep typed HUD effects injected into the controller so failed effects remain replayable and are
  never acknowledged as successfully applied.

## 1.3.105 (build 114)

### Added

- Run Phone and HUD Compose instrumentation contracts on the API-35 CI emulator.
- Cover updater success, retry, pending-verification, and cancellation surfaces with UI tests.

### Changed

- Run branch validation through pull requests or explicit workflow dispatch while retaining the
  post-merge `main` gate, avoiding duplicate push and pull-request executions for the same branch.

## 1.3.104 (build 113)

### Changed

- Keep `OpenClawClient` as the stable public facade while moving mutable transport/auth,
  chat-run, and catalog/session state into dedicated internal components.
- Centralize active-run cleanup and session-operation invalidation in their owning components.

### Security

- Preserve the existing TLS-only endpoint policy, bounded reconnect state, and request correlation
  while isolating transport credentials from chat and catalog state.

## 1.3.103 (build 112)

### Changed

- Decode every production Phone-to-HUD message into a sealed typed message before Activity state
  or effects are touched.
- Replace the duplicate legacy `JSONObject` switch in `HudActivity` with one exhaustive typed
  dispatcher while retaining transport acknowledgements for unknown future messages.

### Security

- Reject malformed catalog, voice, photo, wake, TTS, card, and caption fields at the protocol
  boundary instead of silently coercing wrong primitive types inside the Activity.

## 1.3.102 (build 111)

### Added

- Retain exactly one hash-verified HUD artifact only after its matching live build handshake.
- Keep an independently bounded candidate across process restart until verification promotes it to
  last-known-good state.

### Security

- Explicitly reject automatic rollback for both CXR-M and Hi Rokid because neither verified vendor
  API exposes a safe downgrade contract. No uninstall-based fallback is attempted.

## 1.3.101 (build 110)

### Added

- Generate a manifest for the exact HUD APK bundled into every Phone variant.
- Verify the HUD hash, package, version, and signer against that manifest and the running Phone
  before either production installer can transfer the APK.

### Security

- Reject missing, tampered, unsigned, differently signed, or version-mismatched HUD artifacts
  before handing them to CXR-M or Hi Rokid.

## 1.3.100 (build 109)

### Changed

- Move the unused Wi-Fi ADB HUD installer and its DADB dependency into the debug-only build graph.
- Disable app-owned cleartext network traffic while retaining the vendor-managed CXR-M transport
  and the bound Hi Rokid service route.

### Security

- Fail release verification if DADB or another development-only transport is packaged in the
  production Phone APK.

## 1.3.99 (build 108)

### Added

- Add a durable installer transaction record that restores an explicit interrupted phase after
  process death without persisting authorization tokens or hotspot credentials.
- Require a live matching HUD build handshake after either production installation route.

### Changed

- Replace separate CXR-M and Hi Rokid update controls with one production install action that
  prefers the official Hi Rokid bridge and automatically falls back to a connected CXR-M route.
- Route both production methods through one tested transport-selection policy and generic
  post-install verification state.

### Fixed

- Dispose installer jobs and vendor callbacks with the process-scoped runtime.

## 1.3.98 (build 107)

### Changed

- Isolate the Rokid hotspot request and process-binding lifecycle behind tested app-owned contracts.
- Require Android's local-network capability explicitly on API 35 and newer without changing the target SDK or permission model.

### Fixed

- Release hotspot callbacks and process bindings exactly once after failures, cancellation, timeout, or request replacement.
- Prevent callbacks from replaced hotspot requests from rebinding the process after a newer attempt has started.

## 1.3.97 (build 106)

### Changed

- Restrict CXR-M vendor logging to warnings and errors before initializing the SDK.
- Update the README for Build 105 recovery and source-only release behavior.

### Security

- Prevent CXR-M 1.2.2 from writing credential-bearing account and Bluetooth arguments to Logcat at INFO level.

## 1.3.96 (build 105)

### Added

- Add privacy-safe HUD runtime counters for commands, gestures, inbound packets, malformed packets, duplicate transactions, reconnect syncs, and stream publications.
- Add a 10,000-event soak regression that verifies exact counters and the bounded 64-entry transport acknowledgment window.

### Changed

- Isolate transaction replay retention behind a synchronized bounded tracker instead of Activity-owned deque mutation.

## 1.3.95 (build 104)

### Changed

- Split ambient cards, live captions, utility menus, exit confirmation, and the HUD color palette into focused Compose surface files.
- Keep the primary chat surface isolated from transient status overlays so each area can evolve and compile independently.

## 1.3.94 (build 103)

### Changed

- Move typed HUD command encoding behind a dedicated transport dispatcher.
- Move gesture precedence, hardware-key normalization, and reconnect state-sync decisions out of `HudActivity` into tested orchestration components.

## 1.3.93 (build 102)

### Added

- Add a bounded deep-sleep recovery state with reconnect, wake-timeout, detection, and successful-recovery counters.
- Add an opt-in Always Ready policy that keeps the Rokid display/CXR path awake independently of Talk Mode and warns about higher glasses battery use.
- Add explicit Phone UI guidance that preserves Android pairing and instructs users to wake the proprietary CXR beacon with a triple press before retrying.

### Changed

- Limit automatic CXR rediscovery to five attempts per recovery window instead of retrying indefinitely against deeply sleeping firmware.
- Keep Talk Mode and Always Ready as independent persistent-wake reasons so disabling one does not silently disable the other.

### Fixed

- Detect a stale connected-but-unresponsive glasses session after a bounded wake probe instead of continuing to label it healthy.
- Separate firmware deep sleep from lost Android pairing and offer one explicit recovery action without clearing bond data.

## 1.3.92 (build 101)

### Changed

- Keep the Rokid display and CXR input path awake with a 20-second hardware timeout refresh while glasses Talk Mode is explicitly enabled.
- Preserve normal battery-saving standby whenever glasses Talk Mode is disabled or the glasses disconnect.

### Fixed

- Prevent firmware 1.24 from entering a 30-second sleep state in Talk Mode where neither the AI key nor wake word can revive Clawsses without pairing mode.

## 1.3.91 (build 100)

### Changed

- Treat direct CXR microphone packets as rate-limited proof that the glasses are awake.
- Refresh Rokid's hardware display timeout during active direct-audio capture without issuing a wake call for every packet.

### Fixed

- Prevent the 25-second inactivity detector from pausing Talk Mode while microphone audio is still arriving from the glasses.
- Prevent active AI capture from falling into a false standby state that previously required renewed Rokid discoverability to recover.

## 1.3.90 (build 99)

### Changed

- Route HUD `start_voice` commands and Rokid AI callbacks through one process-scoped activation gate before either path can acquire the audio session.
- Keep HUD recovery armed across the vendor's intermediate foreground callback while an AI scene is active.

### Fixed

- Prevent one physical wake/AI gesture from closing and immediately reopening the direct glasses microphone stream.
- Recover the Clawsses HUD once after a matching AI scene exits, while ignoring unrelated launcher visits and stray exit callbacks.

## 1.3.89 (build 98)

### Changed

- Reconnect to bonded glasses through full BLE/CXR rediscovery instead of alternating with a persisted session-UUID fast path.
- Persist only the bonded glasses address; retain rotating socket identifiers and the Rokid account only for the active process handshake.

### Fixed

- Reject connection-info, connected, disconnected, adopted-link, and failure callbacks from superseded CXR attempts.
- Scope pending connection and SN-recovery state to the attempt that created it.
- Keep the immediate reconnect coroutine under the same single-job ownership as background retries.

## 1.3.88 (build 97)

### Changed

- Coalesce duplicate Rokid AI-key and scene-edge activations and wait for firmware audio teardown before automatic glasses follow-up capture.
- Continue an interrupted direct CXR recognition attempt once through the Phone microphone instead of repeatedly reopening the glasses audio stream.

### Fixed

- Prevent an AI-scene exit callback from starting a new voice capture.
- Treat an empty direct-glasses transcription as a capture failure and fall back once to Android speech recognition.
- Circuit-break direct glasses audio after a mid-capture CXR disconnect until the reconnected session has remained stable.

## 1.3.87 (build 96)

### Added

- Add dependency locks for every Gradle module and paired public-release evidence containing source state, Phone/HUD hashes, versions, and embedded-HUD identity.
- Add typed HUD command encoding with round-trip coverage and a narrow application-facing Rokid device facade around the unchanged vendor adapter.

### Changed

- Split the HUD Compose state into stable chat, input, picker, and status slices and replace retained raw bitmaps with bounded thumbnail handles.
- Split OpenClaw frame decoding, chat-event parsing, and authentication payload construction out of the WebSocket client.
- Make attachment storage content-addressed from decoded bytes and defer original-file reads until a thumbnail-cache miss.
- Retain immutable gateway streaming text directly instead of copying the full response into a mutable buffer for every delta.

### Fixed

- Prevent unrelated HUD state changes from invalidating broad chat and picker state.
- Prevent repeated HUD synchronization of the same attachment from reopening and decoding its original file.

### Security

- Make CI run the paired public-release artifact gate and record whether evidence was produced from a clean or dirty source tree.

## 1.3.86 (build 95)

### Changed

- Bind the emulator-only Phone WebSocket debug server to loopback instead of every network interface.
- Make debug mode self-expire after 30 minutes and label that boundary explicitly in settings.

### Fixed

- Bound debug WebSocket handshake and frame sizes, reject unmasked client frames, and handle truncated length/mask fields safely.

### Security

- Prevent an explicitly enabled debug build from exposing its unauthenticated emulator transport to the LAN.

## 1.3.85 (build 94)

### Added

- Add a bounded app-owned chat-attachment file store with content deduplication, path containment, age/size pruning, and eviction cleanup.
- Add regression tests for file-backed decoding, oversized/external input rejection, eviction, and wire-format isolation.

### Changed

- Materialize gateway and locally sent image attachments at the transport boundary instead of retaining full Base64 strings in long-lived chat state.
- Render Phone images and derive HUD thumbnails from bounded local files, while retaining legacy Base64 fallback compatibility.

### Fixed

- Prevent large embedded chat images from multiplying retained heap usage across history, Compose state, and HUD synchronization.
- Ensure local attachment paths and byte metadata never enter the Phone/HUD or OpenClaw JSON protocol.

## 1.3.84 (build 93)

### Added

- Add a bidirectional Phone/HUD protocol-version and capability handshake with bounded, validated capability names.
- Add codec and transport regression tests for explicit capabilities, legacy peers, and future message compatibility.

### Changed

- Negotiate reliable transport acknowledgments from the HUD's declared capability instead of inferring support only from its build number.
- Preserve the historical build-number fallback when an older HUD omits the new protocol contract.

### Fixed

- Prevent a newer or malformed peer contract from silently inheriting unsupported legacy transport behavior.

## 1.3.83 (build 92)

### Added

- Add one process-scoped audio-session coordinator with generation-safe capture/playback leases and transient Android speech-output focus.
- Add regression coverage for exclusive ownership, delayed lease release, denied focus, focus loss, and capture-to-playback exclusion.

### Changed

- Route Talk Mode, staged glasses voice, Live Captions, TTS, replay, and lifecycle cleanup through one explicit audio owner.
- Stop Live Captions before automatic or replayed TTS instead of allowing recognition and playback to overlap.

### Fixed

- Stop and release TTS deterministically when Android revokes audio focus.
- Prevent a delayed capture callback or second recognition path from releasing or replacing the current audio session.

## 1.3.82 (build 91)

### Added

- Add production-path regression replays covering 500 parsed gateway messages, 1,000 streaming deltas, bounded Phone retention, compact CXR packets, typed HUD decoding, and lossless finalization.
- Add interleaved history-snapshot and reconnect replacement scenarios so delayed frames and stale streaming tails remain measurable in CI.

### Changed

- Move multipart HUD history assembly out of `HudActivity` into one synchronized, attempt-scoped component shared by typed and compatibility paths.

### Fixed

- Invalidate delayed history chunks and end markers as soon as a newer snapshot begins, preventing an old response from contaminating visible HUD history.

## 1.3.81 (build 90)

### Added

- Add an isolated benchmark-only chat workload with 500 retained messages and 1,000 incoming streaming deltas, rendered at the production coalescing cadence and measured by Android `FrameTimingMetric`.
- Record the initial five-iteration Pixel 9 Pro reference: CPU frame time P50 3.3 ms, P90 5.9 ms, P95 6.7 ms, P99 8.3 ms; frame-overrun P95 -3.4 ms and P99 5.7 ms.
- Add a deterministic integration budget covering gateway-history parsing, bounded retention, streaming replacement, and finalization.

### Changed

- Extend release artifact verification so benchmark workload code cannot leak into production Phone APKs.

### Performance

- Enforce a generous two-second CI ceiling for the pure 500-message/1,000-update integration workload while preserving the 500-message store limit.

## 1.3.80 (build 89)

### Changed

- Move OpenClaw history JSON parsing, embedded-image filtering, stable identity generation, and prepend merging out of the network client.
- Keep `OpenClawClient` responsible for transport and operation epochs while pure history components own content interpretation.

### Fixed

- Use one parser for initial and expanded history so supported roles, IDs, timestamps, text blocks, and attachment limits cannot drift between paths.

### Added

- Add regression coverage for embedded/remote images, malformed history entries, supported roles, and stable prepend merging.

## 1.3.79 (build 88)

### Changed

- Route Phone chat history, local echoes, and completed assistant messages through one synchronized bounded store.
- Keep the active assistant stream as a single replaceable tail until finalization instead of repeatedly searching and mutating durable history.

### Fixed

- Bound retained chat state to 500 messages, four attachments per message, and 16 MiB of decoded attachment data.
- Discard stale streaming tails atomically when a session or history snapshot is replaced.

### Added

- Add regression coverage for message eviction, attachment budgets, streaming finalization, and history replacement.

## 1.3.78 (build 87)

### Changed

- Separate the official Hi Rokid install receipt, CXR ownership handoff, and Clawsses peer-build verification into explicit installer phases.
- Isolate the private CXR-L 1.1.1 service-connection compatibility lookup behind a small versioned adapter.

### Fixed

- Preserve a successful HUD installation as pending verification instead of reporting a false install failure when the glasses handshake is delayed.
- Restore pending peer verification after a Phone process restart and complete it automatically when the expected HUD build reconnects.

### Added

- Add a manual verification retry that does not upload the APK again, plus regression tests for stale/matching builds and the reflection boundary.

## 1.3.77 (build 86)

### Changed

- Keep Rokid credentials out of ordinary debug and release APKs unless a private hardware build explicitly opts in.
- Strip Clawsses-owned Android log calls from public minified releases while preserving hardware-test diagnostics.
- Run Android checks on build and refactor branches and install the declared API 35 compile platform in CI.

### Added

- Verify the public release artifact itself does not contain locally configured Rokid credential values.

## 1.3.76 (build 85)

### Changed

- Move the HUD emulator WebSocket client and its network permission into the debug source set while keeping the production CXR-S transport unchanged.
- Route debug transport construction through a build-variant provider so release builds expose only a no-op provider.

### Fixed

- Prevent the unminified glasses release from shipping the unauthenticated emulator socket client or declaring an unused internet capability.

### Added

- Add a release artifact gate that fails the build if the HUD debug client or `android.permission.INTERNET` reappears in the production APK.

## 1.3.75 (build 84)

### Changed

- Rebuild only the final two measured HUD pages when text is appended to a long streaming response, preserving older page layouts by character anchor.
- Keep a two-page reflow window so a growing final word can still wrap safely across the preceding page boundary.

### Fixed

- Make streaming pagination cost proportional to the live tail instead of repeatedly measuring every page of a long single response.

### Added

- Add regression scenarios for 100-page append-only responses with and without a preceding completed message.

## 1.3.74 (build 83)

### Changed

- Decode chat, history, streaming, agent, connection, and run updates through one strict typed Phone-to-HUD protocol boundary before mutating HUD state.
- Preserve the legacy compatibility handler only for unknown future message types.

### Fixed

- Reject missing IDs and incorrectly typed booleans or arrays instead of silently turning malformed Phone messages into actionable HUD defaults.
- Acknowledge malformed reliable packets after rejecting them so one invalid payload cannot remain in an infinite transport retry loop.

### Added

- Add decoder regression coverage for compact history, transport envelopes, malformed actionable fields, unknown future messages, and missing streaming identity.

## 1.3.73 (build 82)

### Changed

- Start the text and OpenClaw application shell even when optional Bluetooth, Wi-Fi, location, or microphone capabilities are denied.
- Store queued camera photos in a bounded four-item, 16 MiB file-backed repository instead of retaining unbounded Base64 payloads in Compose state.

### Fixed

- Allow only one active glasses photo request, reject overlapping captures, ignore stale callbacks, and fail a capture after a bounded 20-second timeout.
- Consume queued photo files atomically when sending so rapid repeated actions cannot attach the same image twice.

### Added

- Add regression coverage for photo queue count/byte budgets and generation-safe camera capture attempts.

## 1.3.72 (build 81)

### Fixed

- Discard delayed history responses after a session switch so one session can no longer overwrite another session's chat.
- Preserve gateway message IDs and derive deterministic fallback IDs that remain stable when older history is prepended.
- Deduplicate the HUD's optimistic user echo by client message ID instead of dropping legitimate repeated text.
- Load real three-message history pages on the HUD with a stable oldest-message cursor and an accurate `hasMore` state.

### Added

- Add regression coverage for stable history identity, session-operation invalidation, cursor-based pages, prepend anchoring, and repeated user messages.

## 1.3.71 (build 80)

### Fixed

- Preserve the sole CXR outbound worker when Bluetooth disconnects while a reliable packet is awaiting its transport acknowledgment.
- Keep wake delivery open after the wake feature is disabled by cancelling pending wake and standby timers and using monotonic elapsed time for rate limits.
- Stop low-latency BLE discovery after its bounded 15-second attempt instead of scanning indefinitely.

### Added

- Add regression coverage for reliable delivery after an acknowledgment-time disconnect and for disabling wake gating while a standby timer is pending.

## 1.3.70 (build 79)

### Changed

- Move frequent Phone-to-HUD message, history, stream, connection, session, agent, run, talk, caption, and progress transitions into one pure state reducer.
- Keep JSON decoding, bitmap handling, lifecycle timers, and transport acknowledgements as explicit Activity-side effects.

### Fixed

- Preserve the transport acknowledgment when a completed user message duplicates the HUD's optimistic local echo.
- Restore history anchors, session selections, and agent identity through deterministic reducer transitions instead of scattered Activity mutations.
- Keep the Phone foreground service alive during Hi Rokid installation handoff, separate external handoff from user disconnect, and prevent runtime callbacks from attempting forbidden background foreground-service restarts.

### Added

- Add regression coverage for message replacement, history prepend/end detection, stream completion, session/agent selection, and independent run/talk/caption state.

## 1.3.69 (build 78)

### Fixed

- Close the active camera session, camera device, image reader, and handler thread through one idempotent terminal cleanup path.
- Recycle the previous captured thumbnail before starting another photo capture.

### Changed

- Select and decode camera images through tested bounded sizing math while retaining the existing output dimensions, JPEG quality, and thumbnail format.

### Added

- Add regression coverage for capture-size selection, power-of-two decode sampling, aspect-ratio preservation, and no-upscale behavior.

## 1.3.68 (build 77)

### Changed

- Suspend OpenClaw WebSocket retries while Android has no default internet-capable network and reconnect immediately when connectivity returns.
- Keep network transitions generation-safe by invalidating the old socket, cancelling its retry job, and allowing only one restored connection attempt.

### Added

- Add deterministic transition coverage for duplicate, lost, and restored network availability events.

## 1.3.67 (build 76)

### Changed

- Publish one immutable HUD streaming snapshot per coalesced display update instead of advancing the visible revision for every network chunk.
- Keep the active streaming accumulator synchronized so callback-thread delivery and HUD publication cannot observe partially updated text.

### Fixed

- Ignore empty chunks and blank message IDs, and avoid rebuilding the complete growing response when no new characters have arrived.

## 1.3.66 (build 75)

### Changed

- Decode every HUD-to-phone command through one typed shared protocol boundary while retaining the existing JSON wire format and legacy optional-field defaults.

### Fixed

- Reject malformed or incorrectly typed glasses commands explicitly instead of silently converting invalid fields to action-triggering default values.

### Added

- Add regression coverage for valid commands, legacy defaults, unknown future commands, malformed JSON, and missing or incorrectly typed required fields.

## 1.3.65 (build 74)

### Fixed

- Restore the hardware-verified unminified HUD release contract after the first R8-minified glasses release reproducibly returned to the Sprite launcher immediately after every successful start request.

### Changed

- Keep Phone target API 35 with R8 optimization while isolating the firmware workaround to the HUD: target API 34 and no release minification.

### Verified

- Require the HUD to remain foreground and complete a fresh `1.3.65 / 74` version handshake; a successful CXR-L install or `openApp` callback alone is not accepted.

## 1.3.64 (build 73, validation only)

### Fixed

- Restore the Rokid HUD runtime contract to target API 34 after Sprite firmware 1.24 reproducibly returned an API-35-targeted custom HUD to the system launcher immediately after every successful start request.

### Changed

- Keep the phone and benchmark applications on target API 35 while the glasses continue compiling against API 35 with their hardware-compatible target API 34.

### Verified

- Hardware validation disproved target API 35 as the sole cause: the target-34 HUD installed and opened successfully but still returned immediately to the Sprite launcher, isolating release minification as the remaining packaging change from the last stable HUD.

## 1.3.63 (build 72)

### Changed

- Target Android 15 / API 35 across the phone, glasses, and benchmark applications after the isolated API-35 compile migration.
- Opt the phone UI into explicit edge-to-edge rendering with dark system-bar icon treatment while retaining Compose system-inset handling.

### Verified

- Preserve typed connected-device and microphone foreground-service declarations, immutable notification intents, and explicit dynamic-receiver export flags under the Android 15 target behavior changes.

## 1.3.62 (build 71)

### Added

- Add an in-app installer fallback through the official Hi Rokid CXR-L bridge when the CXR-M hotspot or P2P transport is unavailable.
- Require both the Hi Rokid service and glasses Bluetooth callbacks before starting exactly one APK upload.

### Changed

- Keep Hi Rokid authorization tokens in memory only, release CXR-M ownership during installation, and restore the Clawsses connection afterward.
- Launch the newly installed HUD through CXR-L before returning Bluetooth ownership to Clawsses.
- Report installation success only after the matching glasses build reconnects and completes its version handshake.
- Apply the explicit local hardware-test signing switch to both paired release APKs while keeping normal public release outputs unsigned.

## 1.3.61 (build 70)

### Changed

- Enable R8 optimization and resource shrinking for the phone release while retaining the generated baseline and startup profiles.
- Preserve the Rokid JNI bridge, Gson field models, and Android component entry points across release optimization.

### Added

- Add an explicit local hardware-test signing switch so the exact minified release can be installed data-preservingly without embedding a publishing signer.

## 1.3.60 (build 69)

### Changed

- Generate baseline and startup profiles from isolated benchmark-only app variants so profiling cannot overwrite production Clawsses data or Keystore-backed settings.
- Regenerate startup profiles with a stable Kotlin module name and remove D8-generated synthetic-lambda rules that cannot remain valid across build variants.

### Added

- Add a benchmark runtime guard that refuses to manipulate a target package unless it uses the dedicated `.benchmark` application-ID suffix.

### Fixed

- Prevent benchmark launches from initializing Rokid, OpenClaw, foreground-service, and wake-lock lifecycles that belong only to the production package.

## 1.3.59 (build 68)

### Changed

- Compile all Android modules against API 35 while retaining target API 34, avoiding a runtime behavior or permission-model change in this release.
- Replace the numeric Android 15 local-network capability compatibility value with the official `NET_CAPABILITY_LOCAL_NETWORK` platform constant.


## 1.3.58 (build 67)

### Changed

- Reuse completed HUD pages during streaming and remeasure only the first affected page plus the growing tail, while retaining full reflow for history prepends, display-size changes, and font changes.

### Added

- Add pagination-cache regression coverage for tail growth, appended messages, cross-page message fragments, history prepends, and layout invalidation.


## 1.3.57 (build 66)

### Changed

- Route every phone-to-HUD payload through one bounded CXR queue; the wake coordinator now controls only the hardware/display delivery gate, while wake-control packets bypass that gate through the same queue.

### Fixed

- Reset a rejected wake acknowledgment to an eligible retry state instead of suppressing the scheduled retry as a duplicate wake attempt.


## 1.3.56 (build 65)

### Changed

- Isolate the unauthenticated emulator WebSocket server in the phone debug source set; release builds now contain only a no-op provider and cannot package the raw listener implementation.

### Added

- Add a release-APK verification gate that fails the build if the emulator-only raw WebSocket listener is ever packaged again.


## 1.3.55 (build 64)

### Changed

- Move OpenClaw, glasses protocol, AI activation, HUD state synchronization, notification relay, photo capture, and staged voice callbacks out of Compose into one process-scoped bridge controller.
- Route both primary and final phone-microphone voice recognition through one generation-gated staged-voice coordinator so cancelled callbacks cannot affect a newer capture.
- Reuse the external PCM peak calculation for both privacy-safe diagnostics and local speech detection instead of scanning every glasses audio frame twice.
- Declare Gson directly and remove unused Retrofit, converter, logging-interceptor, duplicate Core KTX, and ViewBinding configuration from the phone module.

### Added

- Add distinct source-verified paired artifacts for deferred phone/glasses hardware validation.

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
