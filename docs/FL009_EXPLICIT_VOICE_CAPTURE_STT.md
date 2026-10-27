# FL009 - Explicit voice capture and STT

## Status

`DONE` as of 2026-08-27. Samsung SM-S928B acceptance passed for the explicit microphone lifecycle,
typed local-Albanian unsupported result, separately disclosed/consented English Android system
fallback, bounded capture, cancellation, background, and process/identity continuity. FL010 is next;
H036 remains unstarted.

## Voice-session architecture

- `firstlife/voice` owns an Android-free reducer, correlated request model, capture port, and effect
  coordinator. One explicit user action creates one opaque request with a hard eight-second deadline.
- The reducer distinguishes permission waiting, support checking, listening, bounded processing,
  completed evidence, and typed blocked outcomes. Wrong-request callbacks cannot advance a current
  session, and repeated terminal/cancel callbacks are idempotent.
- Permission is requested just in time. The HiHugo face says `LISTENING` only while the current voice
  model owns the recognizer; support checking and result processing project to `THINKING`.
- FL009 exposes an ephemeral transcript handoff for the accepted FL004/FL014 composition. The
  disclosed system fallback requires a separate review action and a second per-session consent
  action before permission/capture. Its eight-second clock begins only after consent. FL009 does not
  start FL010, speak the introduction, or automatically open a microphone after any unrelated event.

## Android adapter and offline policy

`platform/voice/android/AndroidOnDeviceSpeechRecognizer` is the only Android speech boundary. It:

- requires API 31+ `SpeechRecognizer.isOnDeviceRecognitionAvailable` and uses
  `createOnDeviceSpeechRecognizer` for the first tier; the default `createSpeechRecognizer` path is
  reachable only from the separately disclosed/consented fallback;
- requests the exact `sq-AL` locale and offline preference for the first tier, no partial results, at
  most three alternatives, and no app-owned audio buffer or file;
- on API 33+ checks `RecognitionSupport.installedOnDeviceLanguages` and accepts only the exact
  Albanian locale;
- distinguishes installed, downloadable, and unavailable exact-language support; maps no-match,
  speech timeout, busy, audio, network, client-cancel, and unknown errors to typed
  domain failures; a network error cannot become an offline success; and
- cancels/destroys its current recognizer at most once, with request correlation protecting a newer
  session from late callbacks.

No default/system recognizer fallback is automatic. The Samsung reported that `sq-AL` is neither
installed nor present in the on-device downloadable/supported lists. With the user's explicit scope
approval, the FL001 `SYSTEM_NETWORK_ASSISTED` tier was exercised using the installed Android system
recognizer with declared `en-US`: the UI first states that Albanian is unavailable and network audio
processing may occur, then requires a separate consent action. This is a system-fallback proof, not
an Albanian/offline acceptance claim. No third-party SDK, API key, account, or app backend was added.

## Privacy and retention

The app declares `RECORD_AUDIO` and recognition-service visibility only. Raw audio remains owned by
the active Android recognizer. There is no `AudioRecord`, `MediaRecorder`, audio file, database,
preference, backup, export, network SDK, or transcript log path.

Input alternatives are limited to three and each is truncated to 80 Unicode code points. The
terminal model contains only redacted evidence: selected tier, declared locale, alternative count,
and accepted code-point count. The ephemeral transcript wrapper redacts `toString`; it is delivered once
to the future interaction composition and is not retained by the FL009 UI/model. Process death,
background, cancellation, timeout, failure, and explicit reset retain no transcript or name.

## HiHugo presentation

The existing screen adds one visible `Voice / shqip` panel with an explicit start button, active
cancel button, current typed status, and a permanent privacy statement. It never displays the spoken
transcript or implies identity, memory, provider selection, authorization, body readiness, safety,
or execution authority. HUGO remains usable with no LOOI/body connected.

## Automated evidence

- Reducer tests cover just-in-time permission, exact local support, bounded results, empty/no-match,
  timeout, cancel, background, network rejection, stale request callbacks, redacted terminal state,
  and exactly-once release effects.
- Coordinator/fake-adapter tests cover effect ordering, ephemeral delivery, stale callbacks, and
  resource release.
- Architecture/privacy tests keep `firstlife/voice` Android-free and reject persistence, raw audio,
  logging, cloud, LOOI, BLE, GATT, and protocol coupling.
- Compose instrumentation covers explicit start/cancel, active/completed presentation, privacy text,
  and absence of transcript/authority presentation.
- Samsung instrumentation checks explicit microphone permission, exact `sq-AL` on-device support
  disposition, system-recognizer start, and idempotent release; all 14 focused device tests pass.
- Full JVM tests, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` pass. Final
  documentation/evidence guards and `git diff --check` pass after acceptance.

## Samsung acceptance

Target: authorized Samsung SM-S928B on 2026-08-27. LOOI remained disconnected. The accepted trace was:

```text
adb=authorized model=SM-S928B
permission=just-in-time denial=typed grant=typed
on-device-locale=sq-AL disposition=ALBANIAN_MODEL_UNAVAILABLE
system-fallback=en-US disclosure=visible consent=per-session
system-result=COMPLETED transcript-retained=false audio-file=false
cancel=release-confirmed background=release-confirmed timeout=fail-closed
identity-continuity=true body-provider-restored-as-identity=false looi-connected=false
instrumentation=14/14-pass
```

The first assisted attempts produced no match while the phone was locked, correctly retaining no
result. With the display held awake, investigation then found and fixed a deterministic fallback
deadline defect: the eight-second budget had started at disclosure review instead of consent. After
the deadline was moved to the consent/capture boundary, the English system-assisted utterance
completed. Evidence contains only tier/locale/outcome/count semantics; it contains neither the spoken
words nor raw audio.

Permission denial via dismissal produced a typed denied state and retained no recognizer. Explicit
cancel and Home/background each released the active recognizer and surfaced their typed terminal
state. Repeated release was idempotent. Force-stop/relaunch restored no voice session or transcript;
the accepted H003 identity remained exact across repository/activity recreation.

Normal app/test output and app-private file inspection expose no transcript, name, audio buffer/file,
provider identity, transport address, or LOOI protocol detail. Android's microphone privacy indicator
remained system-owned and visible during active capture.

## Remaining product decision

The user approved future use of a higher-quality, potentially paid, multilingual online speech
provider. FL009 does not select or integrate one: credentials, consent, retention, regional routing,
cost limits, network failure, provider contracts, and fallback policy require their owning later
architecture/task. Until that work exists, First Life must honestly label the Samsung `sq-AL`
on-device tier unavailable; FL015 cannot claim Albanian offline STT from this device evidence.
