# AI Architecture

## Principles

- Provider-neutral application contracts; providers are adapters.
- Production provider secrets never ship in the APK.
- Local deterministic commands work without cloud AI.
- AI produces text, structured proposals, or typed tool arguments—not raw BLE.
- Task policy selects models by capability, privacy, latency, reliability, and cost.
- Every external request is observable, cancellable, budgeted, and subject to data minimization.

## Boundary

```text
Android                                      Controlled gateway
-------                                      ------------------
Conversation orchestrator -- authenticated -> Session/auth policy
Task request DTO                             Task/model router
Selected memory snippets                     Provider adapters
Optional compressed media                    Cost/rate limits
Tool schemas              <- streamed events- Text/realtime/vision APIs

AI tool proposal -> local Tool Registry -> Robot Core -> Safety Controller
```

The gateway may run locally during development or on controlled infrastructure. Release builds trust only configured HTTPS endpoints and short-lived scoped client credentials. Provider API keys are gateway secrets.

## Interfaces

- `TextModel`: messages plus constraints to streamed text/tool proposals.
- `RealtimeSession`: negotiated bidirectional audio/text events with cancellation.
- `VisionModel`: selected image plus question/policy to structured result.
- `EmbeddingModel`: text to versioned vector for retrieval; optional/local adapter.
- `SpeechRecognizer`, `SpeechSynthesizer`, `WakeDetector`, `VoiceActivityDetector`: independent adapters so realtime-provider audio is not mandatory.
- `ModelPolicy`: chooses adapter/model for task, budget, privacy, availability, and latency.
- `ToolRegistry`: versioned typed schemas, authorization class, timeouts, audit behavior.

No provider SDK types cross these interfaces.

## Gateway protocol contract 1.0

H051 fixes the provider-neutral wire boundary in `gateway-contract/v1`. One strict message family
represents bounded text, realtime-modality, opaque-reference vision, and embedding requests; exact
cancellation; and correlated streamed events. Streams start at sequence zero, advance consecutively,
and end in exactly one completion, refusal, failure, cancellation, or timeout event. Usage identifies
measured, estimated, or unknown evidence and never invents cost.

Tool output from this boundary is only an H050 skill proposal. It cannot create a capability request,
grant authorization, approve safety, or execute anything. Authentication, transport, gateway client
state, bounded context assembly, proposal mediation, model policy, and backend deployment remain the
separate H052-H057 boundaries.

H052 defines the client-side authentication boundary without choosing a deployment. A short-lived
token is bound to one ephemeral app session reference, an exact HTTPS origin, and explicit gateway
operation scopes. Token storage or validity does not claim that a gateway session currently exists.
Release endpoint policy is exact-origin HTTPS allowlisting; debug cleartext is limited to loopback.
Provider keys remain server-only, and no endpoint, token acquisition flow, or network client is
configured by H052.

H053 adds the provider-neutral client state machine and immutable event reducer behind an unwired
HTTP/stream port. The reducer requires exact H051 correlation and consecutive ordering, revalidates
the complete terminal stream, and bounds frames, partial text, tool proposals/arguments, embeddings,
events, and request time. H052 authenticates the exact operation scope before transport open.
Cancellation and timeout close the channel exactly once and remain locally reported until a real
H051 gateway terminal event confirms the outcome. Diagnostics are content-free, and streamed skill
objects remain proposals without trust, authorization, safety, or execution authority. H053 does
not configure a backend, endpoint, network implementation, provider, model, context packet, or
conversation runtime.

H054 builds the canonical bounded input packet before any model call. It combines exactly one
current H051 turn, at most one supplied summary, privacy-filtered H037 current-state items, an empty
initial memory port, and only the H050 tool definitions declared by the request. Current turn and
tool schemas must fit; optional items are admitted deterministically as whole records under item,
canonical-input, and UTF-8 limits. `SENSITIVE` and `RESTRICTED` data are forbidden from this packet,
and adversarial text remains a JSON value rather than structure. Tool schemas carry no authority.
H054 does not read history, retrieve or write memory, call H053, choose a model, or invoke a tool.

H055 validates streamed tool proposals without executing them. A proposal must be present in the
exact H053 stream and H054 context, match one exact-version H050 definition and fixed-input schema,
bind to a current exact-provider H007 request and matching negotiated descriptor, and carry an
existing correlated `AUTHORIZED` H030 decision. Elevated/high/safety-critical/prohibited, motion,
raw protocol, e-stop/limit mutation, authority mutation, and install lifecycle semantics are
rejected. A bounded non-evicting replay ledger covers proposal/request/execution IDs. Validation
creates no authority or safety evidence and calls no skill executor or provider.

H056 coordinates one activation-gated conversation turn in the pure `conversation/runtime` package.
It reuses the H047 session reducer for phase and cancellation semantics, the H049 speech
coordinator for recognition/synthesis/focus ownership, the H046 report for local-first routing, the
H054 packet as the bounded pre-call context admission step, and the H053 client for generation.
Local stop wins: a STOP-priority route ends the turn locally without any gateway dependency, and
non-stop local matches remain unexecuted typed outcomes. AMBIGUOUS asks an injected bounded
clarification locally instead of escalating to the model; only NO_MATCH generates. Barge-in is
explicit re-activation: it cancels in-flight generation through the H053 exactly-once close and
interrupts output through H049. Provider loss, refusal, timeout, and rejected starts degrade to a
locally spoken injected fallback; audio ownership is released on every terminal path, and no body
or robot lease is ever acquired. Tool proposals in a completed stream remain unexecuted counts in
a content-free trace, and every turn records redacted monotonic latency evidence.

H057 adds the bounded client-side model policy in the pure `gateway/policy` package. Per ADR-005
task-to-model selection remains gateway-side and the H051 request schema stays closed: policy
governs task eligibility, H051 budget envelopes, H054 context narrowing (never enlargement),
session/daily usage accounting from H051 usage evidence, and provider health. Evaluation is
fail-closed and precedes every transport open; exhaustion and an OPEN circuit block AI turns to the
typed local fallback while local stop, local routing, and barge-in never consult the policy. The
locked circuit semantics count transport, timeout, failed, and protocol terminals as provider-health
failures, treat refusals and cancellations as non-health, and recover only through a single
user-initiated half-open probe. There is no automatic retry, failover, or fallback chain, and no
provider, model, credential, or SDK type exists anywhere in the boundary.

## Voice pipeline

Default posture is activation-gated, not continuous cloud streaming:

1. Local explicit button or local wake detector opens a session.
2. Local VAD detects speech and end-of-turn.
3. Audio goes to local STT or a time-bounded gateway/realtime session according to policy.
4. Transcript and a small retrieved context enter conversation orchestration.
5. Output streams to TTS; new speech cancels playback and in-flight generation (barge-in).
6. Session closes after inactivity; raw audio is discarded unless explicit consent/policy says otherwise.

Simple commands such as stop, quiet, light off, battery query, and manual modes should be locally recognized where feasible. Wake-word false accepts/rejects and on-device CPU/battery cost require measurement.

## Tool/action mediation

Each tool has a schema, permission, risk class, timeout, idempotency policy, and result contract. A robot tool accepts a semantic envelope such as “rotate slowly for at most 300 ms,” not bytes. Validation flow:

1. Parse strictly; reject extra/invalid fields.
2. Check session/user/tool authorization and current mode.
3. Translate into typed `ActionRequest` with source `AI_AGENT` and short lease.
4. Robot Core checks ownership; Safety Controller validates current state.
5. Execute through body adapter; return observed result, never assumed success.

High-risk or ambiguous actions require confirmation or are unavailable.

## Cost, reliability, and fallback

- Per-session token/audio/image/time budgets and global daily caps.
- Task-specific context limits; image resizing/cropping before upload.
- Timeout/circuit breaker and explicit provider-health state.
- Retry only idempotent requests, with bounded backoff.
- Local fallback for stop/control, activation, VAD, simple commands, cached responses, and optional local STT/TTS/models where practical.
- Loss of gateway/provider must cancel conversation gracefully and must not affect deterministic stop/control.

## Data handling

Send only the current request, bounded conversation summary, explicitly selected memories, tool schemas needed for this turn, and selected media. H054 initially supplies an empty memory adapter and hard-forbids sensitive/restricted context; later retrieval and provider policy may narrow further but cannot enlarge its bounds. Mark each field's purpose and retention class. Do not upload continuous microphone or camera streams by default. Redact secrets and exclude private memories from provider calls when their privacy policy forbids it.

## Open decisions

- Gateway deployment/identity technology and offline LAN option.
- Initial STT/TTS/realtime providers and local model feasibility on target phones.
- Exact confirmation UX and skill risk taxonomy.
- Whether voice biometrics are appropriate; default is no until a privacy decision.
