# H047 Audio Session Reducer

## Record

- Task: `H047 - Audio session reducer`
- Status: `DONE`
- Completed: 2026-09-01
- Prerequisite: accepted H045/M4 checkpoint
- Runtime impact: none; H047 is an unwired pure reducer and effect contract
- Hardware claim: none

## Model and reducer API

H047 adds the provider-neutral `voice/session` package. `AudioSessionReducer.reduce` accepts one
immutable `AudioSessionModel` and one correlated `AudioSessionEvent`, then returns an
`AudioSessionReduction` containing the next model, a bounded ordered effect list, and a typed
reduction status.

```text
IDLE -> ACTIVATING -> LISTENING -> THINKING -> SPEAKING -> COMPLETED
             \            \          \          \
              +------------+----------+-----------+-> CANCELLING -> CANCELLED
                                      failures/timeouts -----------> ERROR
```

The model exposes `IDLE`, `ACTIVATING`, `LISTENING`, `THINKING`, `SPEAKING`, `CANCELLING`,
`COMPLETED`, `CANCELLED`, and `ERROR`. Active phases carry one opaque redacted `AudioSessionId`,
monotonic start/phase/deadline evidence, and no content. Terminal states retain only a typed
completion, cancellation, failure, phase-timeout, or cancellation-timeout result. A correlated
explicit reset is required before another session starts.

## Cancellation-first semantics

Cancellation is evaluated before ordinary phase callbacks. A correlated cancel from any active
work phase moves immediately to `CANCELLING` and emits exactly one `CancelPhase` plus one
`ReleaseSession`. Repeated cancellation is idempotent and emits nothing. A cancellation
acknowledgement yields `CANCELLED`; if cancellation itself exceeds its bounded deadline, the reducer
terminates in `ERROR` with `CancellationTimedOut` and does not release a second time.

A new activation cannot replace, overwrite, or implicitly cancel an active or terminal session.
Wrong-session, stale-time, early-deadline, and out-of-order phase callbacks leave the current model
unchanged and produce no effects.

## Virtual-time and exit guarantees

All time is injected monotonic milliseconds. Default phase bounds are:

| Phase | Timeout |
|---|---:|
| Activating | 5 seconds |
| Listening | 15 seconds |
| Thinking | 30 seconds |
| Speaking | 30 seconds |
| Cancelling | 3 seconds |
| Whole session | 90 seconds |

Policy construction rejects zero, negative, or excessive durations. Work-phase deadlines are
clamped to the whole-session deadline. Once that deadline initiates cancellation, a separate bounded
cleanup window remains available so resource cancellation can still complete honestly.

At a work deadline the reducer first enters `CANCELLING`; a successful cancellation acknowledgement
then records the exact phase as `TimedOut`. Every work phase therefore has completion, explicit
cancel, typed failure, and timeout exits. `CANCELLING` exits through acknowledgement, failure, or its
own timeout. Terminal states exit only through correlated reset, and `IDLE` exits through explicit
activation.

## Effect and privacy boundary

`RequestActivation`, `RequestListening`, `RequestThinking`, `RequestSpeaking`, `CancelPhase`, and
`ReleaseSession` describe orchestration work only. The reducer calls no port and owns no microphone,
audio focus, recognizer, speech provider, model, network, Android resource, or output device.

The session model stores no transcript, audio buffer, text, provider identity, permission,
authorization, body state, capability request, AI state, or user data. Session IDs redact themselves
in diagnostics. H047 adds no capture, STT, TTS, local intent invocation, provider selection, trust,
permission, capability execution, persistence, UI, AI, memory, BLE, LOOI, or physical behavior.

The accepted FL009 First Life voice implementation is unchanged. H047 uses its lifecycle lessons but
does not rewire, replace, or generalize that production path during this task.

## Coverage evidence

`AudioSessionReducerTest` covers:

- the complete activation/listening/thinking/speaking/completed sequence and phase effects;
- cancellation priority from every active work phase, exactly-once release, acknowledgement, and
  repeated-cancel idempotency;
- virtual-time expiry of every active phase, exact timed-out-phase evidence, cancellation timeout,
  whole-session clamping, and bounded cleanup after the overall deadline;
- wrong-session, stale-time, early-deadline, out-of-order callback, active-session replacement, and
  terminal reset rejection;
- typed failures and at-most-once release;
- explicit exit coverage for every nonterminal and terminal state;
- session-ID and policy validation plus monotonic-overflow fail-closed behavior; and
- content-free/redacted models with no Android, audio implementation, provider, authority,
  capability execution, transport, or LOOI coupling.

The architecture dependency guard now protects `voice/session` as a pure V2 boundary. Required
completion checks pass: First Life documentation/order guard, HUGO V2 documentation guard, T032
baseline evidence guard, architecture dependency guard, focused H047 tests, full
`testDebugUnitTest` (778 tests across 107 suites; zero failures/errors/skips), `lintDebug`,
`assembleDebug`, and `git diff --check`. The focused H047 class contributes 14 passing tests.

No Android-device or real-LOOI verification is required: H047 is pure and unwired and changes no
audio implementation, application UI, provider connection, T032 safety, or physical runtime path.

## Next boundary

H048 is eligible and has not started. H047 adds no `AudioRecord`, microphone permission, local VAD,
wake word, speech adapter, conversation coordinator, AI gateway, or memory.
