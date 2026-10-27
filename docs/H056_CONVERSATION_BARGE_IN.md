# H056 Conversation and Barge-in

## Record

- Task: `H056 - Conversation/barge-in`
- Status: `DONE`
- Completed: 2026-09-02
- Prerequisites: H046 local intent router, H047 audio session reducer, H048 explicit capture/local
  VAD, H049 speech adapter contracts, H050 skill registry, H051 gateway contract, H052 gateway
  authentication, H053 gateway client/reducer, H054 bounded context packet, H055 proposal validator
- Runtime impact: none in production paths; H056 is an unwired pure coordinator plus an isolated
  instrumented Android-audio verification test
- Hardware claim: redacted Samsung `SM-S928B` instrumented Android-audio evidence only (see Device
  evidence); no LOOI, provider-backend, or physical claim

## Turn coordinator

H056 adds the Android-free `conversation/runtime` package with
`ConversationTurnCoordinator` and its model types. One activation-gated turn at a time is
coordinated by composing the existing boundaries instead of duplicating them:

- the H047 `AudioSessionReducer` remains the authoritative phase machine
  (`ACTIVATING -> LISTENING -> THINKING -> SPEAKING -> terminal`) with its cancellation-first,
  deadline, and release semantics; the coordinator only interprets the reducer's effects;
- the H049 `LocalSpeechInteractionCoordinator` retains recognition/synthesis operation ownership,
  audio-focus handling, one-shot `EphemeralSpeechTranscript` consumption, and typed interruption;
- the H046 route report decides local-first routing; the coordinator never calls the router or
  executes a capability;
- the H054 `ConversationContextBuilder` is the bounded pre-call admission step for every AI turn;
  a rejected packet cancels the model call before it starts;
- the H053 `GatewayClient` performs generation through the injected stream port; no network,
  endpoint, backend, or model selection is configured (H057 owns policy).

Activation is explicit (press-to-talk style). A new activation while a turn is still active is the
H056 barge-in: the coordinator first drives the H047 cancellation path, cancels the H053 stream
with the typed `barge_in` reason (exactly-once close), interrupts H049 output with `NEW_SPEECH`,
auto-resets the terminal session, and only then starts the new listen. No wake word, continuous
cloud streaming, VAD-during-playback, or echo path is added.

```text
TURN COORDINATED
!= PROVIDER SELECTED
!= CAPABILITY REQUEST CREATED
!= AUTHORIZED
!= SKILL EXECUTED
!= MEMORY WRITTEN
```

## Local-first routing

The H046 report is applied with the locked turn policy:

| Route | Turn result |
|---|---|
| `PROPOSED` with STOP priority | `LOCAL_STOP`; the turn ends immediately, any in-flight generation is defensively cancelled, and no gateway dependency exists — local stop always wins |
| `PROPOSED` non-stop | `LOCAL_PROPOSED`; a typed, unexecuted route outcome only; no dispatch, no gateway call |
| `AMBIGUOUS` | `CLARIFICATION_REQUESTED`; the injected bounded clarification is spoken locally and the turn ends waiting for the user; the gateway is never escalated on ambiguity |
| `UNSUPPORTED_LOCALE` / `INVALID_INPUT` | `LOCAL_FALLBACK` with the typed local reason and injected bounded message |
| `NO_MATCH` | the only route that starts AI generation |

Host-supplied `ConversationFallbackMessages` carry every spoken fallback as a validated bounded
`SpeechText`. H056 defines no product copy, and diagnostics redact all message and transcript
content.

## Cancellation, barge-in, and fallback

Cancellation propagates through every owned boundary: the H047 reducer enters `CANCELLING` and
emits its cancellation/release effects, the H053 stream is cancelled with typed reasons
(`barge_in`, `user`, `background`, `deadline`) and closes exactly once, and H049 interrupts the
active speech operation with session/operation correlation. User cancellation, backgrounding,
audio-focus loss, phase deadlines, and re-activation all follow the same path; repeated
cancellation is idempotent, and stale, wrong-session, or late callbacks cannot revive or complete
work.

Provider loss degrades gracefully: transport failure, gateway/client timeout, refusal, failure,
protocol error, rejected start (including absent/expired H052 credentials), rejected bounded
context, empty output, and output exceeding the H049 `SpeechText` bound each produce a typed
`ConversationTurnFallbackReason`, a locally spoken injected fallback, and a completed turn. The
coordinator acquires no body, motion, or robot lease at any point, so provider loss trivially
releases "any robot lease": there is none to release, and this is stated rather than implemented
away. Local stop, cancellation, and fallback never depend on gateway availability.

Tool proposals observed in a completed stream remain unexecuted, unvalidated counts in the
content-free trace. Wiring H055 validation and H050 execution into conversation requires
pre-existing H030 authorization evidence that no conversation path creates; that mediation is a
later boundary and is not claimed here.

## Latency evidence

Every turn records `ConversationTurnLatency`: injected monotonic timestamps at activation,
listening start, route decision, generation start/end, speaking start/end, and turn end, exposed
as content-free `totalMs`, `listeningMs`, `generationMs`, and `speakingMs` durations plus the raw
marks. `ConversationTurnRecord.redactedTrace()` emits a stable two-line outcome/latency trace
with no transcript, synthesis text, request content, or identifiers. Equivalent runs produce
equivalent traces.

## Transcript hand-off

The H049 coordinator consumes the one-shot transcript envelope internally for routing, so the
coordinator installs a forwarding recognition port that observes the alternatives exactly once,
re-wraps them in a fresh one-shot envelope for H049, and uses the first alternative as the H051
prompt. The observed alternatives are dropped immediately after prompt construction, cancellation,
or turn end. The same immutable-JVM-string caveat as H049 applies.

## Purity and boundary checks

The H019 architecture dependency guard now protects `conversation/runtime` as a pure V2 boundary
and permits only its deliberate inward dependencies on `voice/session`, `voice/api`, and
`ai/gateway` (H046 routing arrives through H049 events; H054/H051/H052 types are not forbidden
prefixes). The package contains no Android, platform, LOOI, feature, trust, skills-executor,
H055-validator, credential, memory, or raw-protocol reference, verified by test. First Life,
T032, and the LOOI runtime path are untouched.

The coordinator is single-lock and callback-driven: hosts must drive `activate`, `cancel`, `poll`,
and adapter callbacks from one dispatch thread, as with the existing deterministic orchestration
style. H056 adds no threads, schedulers, or clocks; all time is injected.

## Coverage and checks

`ConversationTurnCoordinatorTest` contributes 21 focused JVM tests with fully virtual time
covering: the complete explicit turn through real H054/H053 fake streams to spoken bounded output
with exact latency marks; local stop winning with zero gateway/synthesis interaction; the
unexecuted local-proposal outcome; AMBIGUOUS clarification without gateway escalation; unsupported
locale fallback; barge-in during THINKING (exactly-once generation cancel, no output, fresh
listen) and during SPEAKING (output interrupted, focus released); transport failure, missing
credentials, refusal, timeout, oversized and empty output, and rejected context fallbacks; user
cancellation; audio-focus loss; phase-deadline fail-closed cancellation; recognition/synthesis
failure terminals; stale/late event rejection; terminal auto-reset; deterministic redaction; and
source-level absence of authority/execution/memory/platform coupling.

- focused H056 JVM tests: 21/21 PASS
- architecture dependency guard (`ArchitectureDependencyGuardTest`): PASS
- full `:app:testDebugUnitTest`: PASS — 898 tests across 116 suites, zero failures, errors, or
  skips
- `:app:lintDebug`: PASS — 0 errors, 13 warnings (unchanged count from H055; none in H056 files)
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: PASS
- documentation guards (First Life order, HUGO V2, T032 baseline evidence): PASS
- `git diff --check`: PASS

## Device evidence

The Android-audio verification is implemented as
`ConversationTurnAndroidAudioInstrumentedTest` (three scenarios: full turn with a real bounded
`AudioRecord` listen window and real local TTS output through the H049 synthesis port; barge-in
during real speech with measured cancellation latency; provider-loss fallback spoken locally),
using only the fake H053 stream port — no network.

On 2026-09-03 the prepared debug and androidTest APKs (commit `e0451c9`) were installed on the
authorized Samsung `SM-S928B` (Android 16, API 36; serial intentionally redacted from evidence
narrative) and the focused class was run through `am instrument`. `RECORD_AUDIO` was granted only
for the run and revoked afterward, verified `granted=false`.

Result: **OK (3 tests)** — all three scenarios passed on device. The content-free evidence lines
printed by the test:

```text
H056_DEVICE_FULL_TURN=outcome=AI_COMPLETED totalMs=1780 listeningMs=855 generationMs=137 speakingMs=788 frames=0 micReleased=true
H056_DEVICE_BARGE_IN=cancelLatencyMs=21 outcome=CANCELLED reason=INTERRUPTED
H056_DEVICE_PROVIDER_LOSS=outcome=AI_FALLBACK reason=TRANSPORT_FAILURE fallbackSpokenLocally=true totalMs=2778
```

Honest reading: the full turn listened through a real 800 ms `AudioRecord` capture window
(`listeningMs=855`), generated through the fake stream (`generationMs=137`, local only), and spoke
the bounded reply through real local TTS (`speakingMs=788`), releasing the microphone afterward.
`frames=0` counts local-VAD voice transitions, and the quiet ambient room produced none — the same
honest caveat as the H048 device run; the capture window itself demonstrably ran. Barge-in during
real speech cancelled the turn in 21 ms wall time (test limit 2,000 ms) with outcome
`CANCELLED`/`INTERRUPTED` and TTS actually stopped. Provider loss degraded to
`AI_FALLBACK`/`TRANSPORT_FAILURE` with the local fallback genuinely spoken by TTS.

Two test-fixture defects were found and corrected during device bring-up, each committed
separately and limited to the androidTest file: a synthetic H052 token lifetime outside the
900-second policy bound (`a3487b3`), and a wrong expected-fallback-text assertion plus a bounded
(3,000 ms) microphone-release retry replacing an instantaneous reacquisition assumption
(`e0451c9`). No production code, assertion limit, cancellation bound, or architecture boundary was
changed. The 21 ms barge-in cancellation, the honest `LISTENING_FAILED`-on-microphone-busy
behavior the retry exercises, and all routing/cancellation semantics behaved as specified.

JVM fake-timing results are never presented as device evidence; the levels are reported separately
above.

## Next boundary

H057 is eligible and has not started. H056 adds no model policy, budgets, circuit breaker,
configured gateway/backend, production STT/TTS wiring, wake word, memory write, skill execution,
provider selection, motion tool, or physical action.
