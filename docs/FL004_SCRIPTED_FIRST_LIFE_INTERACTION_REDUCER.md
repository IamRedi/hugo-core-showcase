# FL004 - Scripted First-Life Interaction Reducer

## Record

- Task: `FL004 - Scripted first-life interaction reducer`
- Status: **DONE**
- Implementation date: 2026-08-26
- Accepted base: FL003 commit `2a20cb259c10c3580172af9c5fc330585d672b2b`
- FL001 interaction/privacy decision: reused unchanged
- FL002 HUGO identity semantics: unchanged
- FL003 visual specifications: unchanged
- Android/device verification: not required; implementation is pure JVM/Kotlin
- Production body/transport/runtime impact: none
- H036: not started

## Interaction model and reducer

`firstlife/interaction` is an Android-free state-machine boundary. `FirstLifeInteractionModel` owns
only one current interaction session, the unchanged H003 `HugoIdentity` reference when available,
monotonic correlation, the current scripted stage, one active request, an optional bounded
session-only name, and a semantic body-status fact supplied by later composition.

The canonical FL001 stages remain recognizable while asynchronous request readiness is explicit:

```text
READY
 -> INTRODUCTION_READY
 -> INTRO_SPEAKING
 -> AWAITING_LISTEN_START
 -> NAME_INPUT_REQUESTED
 -> LISTENING
 -> GREETING_READY
 -> GREETING_SPEAKING
 -> READY_FOR_NEXT_PHASE

any active state -> CANCELLING -> READY
recoverable failure -> BLOCKED
unrecoverable failure -> FAILED
```

`INTRODUCTION_READY` means a declarative speech request exists; only a matching `SpeechStarted`
callback establishes `INTRO_SPEAKING`. Likewise, `NAME_INPUT_REQUESTED` is not listening;
`LISTENING` begins only after a matching `NameInputStarted` callback. This preserves the FL003 truth
boundary and never turns an effect request into claimed I/O success.

## Deterministic dialogue and temporary name

The reducer owns two fixed project constants and typed resource keys, with no LLM or planner:

- `INTRODUCTION_SQ_AL`: `Përshëndetje. Unë jam HUGO. Kush je ti?`
- `NAME_GREETING_SQ_AL`: `Gëzohem që të njoh, %s.`

`FirstLifeNameInputResult` distinguishes on-device STT or manual-fallback success, no speech,
ambiguous input, cancellation, and timeout. The source is input evidence only and is not retained in
the interaction model.

`FirstLifeNameValidator` applies NFC normalization and accepts one 1-40-code-point display value,
with no more than three components. Each component contains Unicode letters; only internal ordinary
space, hyphen, straight apostrophe, or curly apostrophe separators are accepted. Input is capped at
80 code points before normalization. Blank input, controls, digits, markup, edge/doubled separators,
overlength input, and more than three components are rejected without correction or guessing.

The accepted value is a `FirstLifeSessionName` held only in the immutable current model. It has no
codec, storage port, Android parceling, persistence interface, diagnostic export, or HUGO-identity
field. Cancellation and reset clear it. A deliberate reset creates a supplied fresh interaction
session ID while preserving the exact `HugoIdentity`. Body loss changes only `bodyStatus` and cannot
clear the name or identity while the interaction session remains active.

## Declarative effects

The reducer emits project-owned values only:

- `SpeakText(request, speech)`;
- `RequestNameInput(request, deadline)`;
- `CancelNameInput(request)`;
- `StopSpeaking(request)`;
- `PresentState(intent)`;
- `NameRetryRequired(reason)`; and
- `ProceedToObservationPhase(sessionId)`.

No effect calls a microphone, recognizer, TTS engine, camera, provider, body, capability, Android
service, network, or AI. FL009/FL010 may later bind the voice effects to real adapters. The
observation effect is a future phase request only and does not open a camera.

## Correlation, deadlines, and cancellation

Every event contains the exact `FirstLifeSessionId`, a positive monotonic sequence, and non-negative
monotonic occurrence time. Each asynchronous request carries a deterministic
`FirstLifeRequestReference` composed of session, request kind, and originating event ordinal.

Wrong-session and wrong-request events are ignored without mutation or effects. Equal/older
sequences, regressed timestamps, duplicates, and callbacks after an active input deadline cannot
advance the interaction. A deadline event is accepted only at or after its exact deadline. A typed
timeout result remains an explicit timeout even when delivered at the deadline boundary.

Cancellation emits exactly one cleanup effect for the active request, enters `CANCELLING`, clears the
temporary name, and requires a correlated completion before returning to `READY`. Repeated cancel or
completion commands are idempotent and emit no duplicate cleanup. Fatal failure also emits the
applicable cleanup once, clears session working data, and enters terminal `FAILED`.

Recoverable name/input/speech failures enter `BLOCKED` with a typed reason. Retry is not automatic:
an explicit `ResetSession` supplies a different session ID, clears session data, and starts from
`READY` (or remains identity-blocked when no HUGO identity exists).

## Body/provider and presentation separation

`FirstLifeBodyStatus` accepts only semantic `UNAVAILABLE`, `AVAILABLE`, `SELECTED`, `LOST`, or
`PROVIDER_BLOCKED` facts. The reducer does not inspect or select providers and has no provider ID,
registry, trust, connection, transport, or capability field. Body absence or provider blockage does
not prevent HUGO from entering the introduction flow.

`FirstLifePresentationProjection` produces intent vocabulary only: `IDLE`, `LISTENING`, `THINKING`,
`SPEAKING`, `BODY_READY`, `BLOCKED`, or `ERROR`. It defines no color, eye geometry, animation, Compose
state, or FL003 visual specification. Later composition can translate these intents into the FL003
presentation reducer. `BODY_READY` is emitted only from a semantic available/selected body fact and
still conveys no trust, authorization, connection, safety, or execution authority.

## Tests and checks

`FirstLifeInteractionReducerTest` covers:

- the complete introduction/name/greeting flow and exact Albanian text;
- STT and manual-name success;
- conservative valid/invalid Unicode name cases;
- invalid, ambiguous, no-speech, cancelled, and timed-out input;
- exact deadline behavior and invalid deadlines;
- speech unavailable and introduction cancelled;
- explicit reset with temporary-name clearing and identity preservation;
- identity-unavailable fail-closed startup;
- body unavailable/provider blocked without preventing introduction;
- body loss without name or identity loss;
- stale sequence/timestamp, duplicate, wrong-session, wrong-request, and late callback rejection;
- speaking/listening cancellation cleanup and idempotency;
- recoverable `BLOCKED` versus fatal `FAILED`; and
- presentation-intent mapping and absence of persistence/runtime authority fields.

The H019 architecture guard includes `firstlife/interaction` as a pure boundary and rejects Android,
platform, feature, LOOI, network client, vendor, or AI imports/references.

| Check | Result |
|---|---|
| First Life documentation/order guard | PASS |
| HUGO V2 documentation guard | PASS |
| T032 baseline evidence guard | PASS |
| Architecture dependency guards | PASS |
| Relevant FL002/FL003/FL004 JVM tests | PASS |
| Full `testDebugUnitTest` | PASS |
| `lintDebug` | PASS |
| `git diff --check` | PASS |
| Android/LOOI/device run | Not required; no production path changed |

## Risks and residual gates

- FL009/FL010 must preserve request/session/deadline correlation when binding real STT/TTS and must
  not claim `LISTENING` or `SPEAKING` before adapter confirmation.
- The bounded name grammar intentionally rejects legitimate complex names outside its small demo
  envelope; future expansion requires an explicit privacy and ambiguity review, not NLU guessing.
- `ProceedToObservationPhase` is declarative only. FL011 and later composition must still require an
  explicit visible camera session and its own permission/lifecycle evidence.
- Body status is supplied evidence, not provider selection or authority. Later composition must use
  H029/H030/H018/T032 boundaries before any physical action.

FL005 is the next eligible First Life task. It remains unstarted. H036 remains unstarted.
