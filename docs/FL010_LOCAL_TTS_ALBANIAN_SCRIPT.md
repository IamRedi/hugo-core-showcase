# FL010 - Local TTS and Albanian script

## Status

`DONE` as of 2026-08-27. The authorized Samsung SM-S928B reported an exact installed local
Albanian `sq-AL` voice, completed the fixed introduction and sanitized session-name greeting through
real Android TTS callbacks, and passed human audibility/quality acceptance. FL011 is next; H036
remains unstarted.

## Output architecture

- `firstlife/voice` owns the Android-free speech-output request, model, port, listener, and
  coordinator. Requests carry opaque IDs, monotonically increasing ordinals, one of two fixed
  script purposes, the exact `sq-AL` locale, and bounded text.
- The model distinguishes idle, support checking, queued, callback-confirmed speaking, completed,
  and typed blocked states. Only Android's real `onStart` projects `SPEAKING`; `onDone` is required
  for completion. Wrong-request and late callbacks cannot advance the current request.
- `platform/voice/android/AndroidLocalTextToSpeech` is the only Android TTS boundary. It initializes
  the installed engine, calls `isLanguageAvailable`, enumerates voices, requires an exact `sq-AL`
  locale, rejects network-required and not-installed voices, selects deterministically by voice
  name, requests transient audio focus, and queues exactly one utterance.
- Terminal completion, error, cancellation, interruption, and lifecycle loss stop and shut down the
  engine and abandon audio focus. Repeated release is idempotent.

## Script and session-name boundary

The only accepted resources are:

```text
Përshëndetje. Unë jam HUGO. Kush je ti?
Gëzohem që të njoh, %1$s.
```

Both default and Albanian resource sets contain those exact templates. The greeting accepts only a
session-only value returned by the accepted FL004 `FirstLifeNameValidator`; it does not speak an
unvalidated transcript or arbitrary input. Text is limited to 96 Unicode code points and its domain
diagnostics redact content and request identity.

The microphone start control remains disabled until the introduction receives its terminal TTS
completion callback. Starting another introduction resets that gate. Background clears the gate and
the session display name. FL010 does not create conversation history, memory, an account, a cloud
speech route, generated dialogue, voice cloning, or a provider/body dependency.

## Typed failure behavior

Support and runtime failures remain distinct: missing engine, unavailable exact Albanian language,
missing local voice data, denied audio focus, synthesis error, cancellation, background, and audio
interruption. Missing language/data exposes Android's explicit voice-data setup action; it never
substitutes another locale or a network voice and never reports `SPEAKING` before the engine does.

## Automated evidence

- Coordinator tests cover supported ordering, callback-confirmed speaking/completion, missing data,
  cancellation/background, stale callbacks, exact release, Unicode bounds, and redacted content.
- Privacy/architecture tests keep the domain Android-free, reject cloud/LOOI/Bluetooth coupling, and
  verify the exact resource templates.
- Compose instrumentation verifies explicit introduction, disabled greeting without a sanitized
  session name, microphone gating, active cancellation, and the no-cloud/no-arbitrary-speech label.
- Android adapter instrumentation verifies exact support disposition, fixed introduction and
  greeting completion, cancellation without a false completed callback, and idempotent release.
- Full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` pass.

## Samsung acceptance

Target: authorized Samsung SM-S928B, API 36, on 2026-08-27. LOOI remained disconnected.

```text
adb=authorized model=SM-S928B api=36
locale=sq-AL support=SUPPORTED exact=true local=true network-required=false data=installed
introduction=start-callback,done-callback
sanitized-session-greeting=start-callback,done-callback
cancel=release-after-start,no-completed-callback
background=typed-background-state,engine-released
focused-ui-identity-instrumentation=14/14-pass
human-audibility=clear-and-acceptable
looi-connected=false body-required=false
```

The installed Google speech-services package loaded its on-device `sq-al` resources. The typed
acceptance log contains locale, support, purpose, and terminal outcome only; it contains no private
session name or arbitrary spoken text. The user confirmed both fixed utterances were audible, clear,
and acceptably pronounced. This is a local Albanian TTS acceptance claim for this Samsung state, not
a general cloud or multilingual voice claim.

## Checks and next boundary

First Life documentation/order, HUGO V2 documentation, T032 evidence, architecture dependency, and
`git diff --check` guards pass. FL011 is now eligible. FL011 and H036 were not started by FL010.
