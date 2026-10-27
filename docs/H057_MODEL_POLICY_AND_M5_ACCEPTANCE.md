# H057 Model Policy and M5 Acceptance

## Record

- Task: `H057 - Model policy and M5 acceptance`
- Status: `DONE`; M5 accepted on the complete evidence chain (see M5 acceptance status)
- Completed: 2026-09-04
- Prerequisites: H051 gateway contract, H052 gateway authentication, H053 gateway client/reducer,
  H054 bounded context packet, H055 capability proposal validator, H056 conversation/barge-in
- Runtime impact: none in production paths; H057 is an unwired pure policy engine, an additive
  default-preserving hook in the H056 turn coordinator, and an androidTest-only loopback configured
  test gateway
- Hardware claim: redacted Samsung `SM-S928B` instrumented evidence for the configured loopback
  gateway acceptance suite and the H056 Android-audio regression (see Device evidence); no
  provider-backend or physical claim

## What model policy means here

Per ADR-005, task-to-model selection is gateway-side. The H051 request schema is closed and carries
no model field, so the client cannot and must not select models. H057 client policy therefore
governs only: task eligibility, per-request H051 budget envelopes, H054 context narrowing,
session/daily usage accounting, provider-health state, and fail-closed exhaustion. No provider,
model, vendor, endpoint allowlist entry, credential, or SDK type appears anywhere in the policy
boundary.

## Policy engine

`gateway/policy` adds the pure, synchronized `ModelPolicyEngine` behind the `ModelPolicyPort`
interface with typed model and result types in `ModelPolicyModel`. Configuration is validated
bounded at construction and has no defaults that relax a limit:

- per-task H051 `GatewayBudget` envelope and a maximum H054 context input-unit allowance;
- per-task session and daily request/output-unit caps;
- allowed context privacy classes (H054 `SENSITIVE`/`RESTRICTED` are rejected at construction);
- circuit: consecutive-failure threshold and monotonic cooldown.

All time is injected — monotonic milliseconds for circuit timing (ADR-007), an injected epoch-day
source for daily windows — so the engine holds no hidden clock. Evaluation order is fail-closed and
typed: task permitted, privacy permitted, circuit, session request cap, session output budget,
daily request cap, daily output budget. Every block happens before any transport open and surfaces
through the H056 coordinator as a typed local fallback (`POLICY_BLOCKED`); nothing is silently
downgraded and no retry loop exists.

Usage accounting accrues only from H051 `usage` events: measured/estimated units when present, and
an honest unknown-evidence request count that invents no units. `ModelPolicyUsageSnapshot` is a
bounded, content-free accounting record (data only, no UI) that grants no authority, executes
nothing, and writes no memory.

`ModelPolicyContextNarrower` narrows the H054 context budget to the policy allowance. Every
dimension can only decrease; it can never enlarge H054's item, input-unit, or byte ceilings.

## Circuit semantics (locked H057 decisions)

```text
CLOSED --(bounded consecutive provider-health failures)--> OPEN
OPEN:  AI requests fail fast to the typed local fallback; local STOP/routing unaffected
OPEN --(bounded monotonic cooldown)--> HALF_OPEN
HALF_OPEN: the next user-initiated eligible request is the single probe (no background probing)
probe COMPLETED/REFUSED --> CLOSED (failure count resets)
probe TRANSPORT_FAILURE/CLIENT_TIMED_OUT/GATEWAY_TIMED_OUT/FAILED/PROTOCOL_ERROR --> OPEN again
CLIENT_CANCELLED/GATEWAY_CANCELLED: neither failure nor recovery; releases an in-flight probe
auth/configuration/start rejections (H052/H054 domains): never counted as provider health
```

There is no automatic retry, backoff, failover, or provider fallback chain anywhere.

## H056 integration (additive, default-preserving)

`ConversationTurnCoordinator` accepts an optional `ModelPolicyPort`. When absent, behavior is
byte-for-byte the H056 checkpoint behavior: the fixed H051 envelope, no narrowing, no reporting.
When present, `startAiGeneration` evaluates policy before request construction; a blocked turn
never opens a socket and speaks the injected provider-unavailable fallback with typed
`POLICY_BLOCKED`; an allowed turn uses the policy envelope and the narrowed context budget; paths
that are allowed but never reach a stream terminal (request construction, context rejection, start
rejection) release any half-open probe via `onStartRejected` without a health verdict; and every
finished stream is reported through `onTerminal` for budget and health accounting. Local STOP, local
routing, clarification, and barge-in never consult the policy and remain exactly as shipped in
H056.

## Deterministic coverage

`ModelPolicyEngineTest` contributes 17 focused JVM tests: configuration fail-closed validation,
allow/envelope/allowance paths, forbidden privacy and unknown-task blocks, session request/output
exhaustion with typed results, unknown-usage honesty, daily exhaustion and day rollover, circuit
threshold and open transitions, half-open single-probe semantics, probe failure reopening, REFUSED
and both cancellation statuses never counting as health failures, client timeout counting, start
rejection releasing a probe without a verdict, narrowing that only decreases dimensions, and
content-free snapshot redaction.

H056 integration coverage extends `ConversationTurnCoordinatorTest` to 26 tests, adding: blocked
turn with zero transport opens and typed fallback; exhaustion after one turn blocking the next
pre-transport; open-circuit fail-fast with local stop still dominant; usage reporting into the
policy; and stop-priority turns leaving policy usage untouched.

## Verification matrix

| Check | Result |
|---|---|
| `ModelPolicyEngineTest` policy matrix | PASS, 17 tests |
| `ConversationTurnCoordinatorTest` H056 + H057 integration | PASS, 26 tests |
| Focused H046-H056 prerequisite suites + architecture guard | PASS |
| Full `testDebugUnitTest` | PASS, 920 tests in 117 suites; zero failures/errors/skips |
| `lintDebug` | PASS, 0 errors, 13 warnings (unchanged count) |
| `assembleDebug` and `assembleDebugAndroidTest` | PASS |
| HUGO V2 / First Life order / T032 baseline documentation guards | PASS |
| `git diff --check` | PASS |
| Samsung configured-gateway instrumentation | PASS, 3 tests (2026-09-04) |
| Samsung H056 Android-audio regression | PASS, 3 tests (2026-09-04) |
| Temporary `RECORD_AUDIO` grant revoked after run | PASS; verified `granted=false` |

## Device and configured-gateway evidence

On 2026-09-04 the prepared debug and androidTest APKs (commit `e07cd65`) were installed on the
authorized Samsung `SM-S928B` (Android 16, API 36) and the focused
`LoopbackConfiguredGatewayAcceptanceInstrumentedTest` ran through `am instrument` against the
androidTest-only loopback `127.0.0.1` configured test gateway, authorized through the real H052
DEBUG loopback endpoint policy with a real stored scoped `TEXT` token. The one-event-per-line
framing is test-only and is not a production transport contract. Speech ports are hermetic fakes;
the H056 audio class carries the real microphone/TTS evidence. Result: **OK (3 tests)**.

Content-free evidence lines printed by the test:

```text
H057_DEVICE_GATEWAY_ROUNDTRIP=outcome=AI_COMPLETED generationMs=81 usageOutputUnits=9 bargeInCancelMs=6 cancellationsServed=1
H057_DEVICE_CIRCUIT=failures=2 open=true failFast=true probeRecovered=true recoveryGenerationMs=90 cooldownMs=3000
H057_DEVICE_EXHAUSTION=blocked=POLICY_BLOCKED transportOpensAvoided=true totalMs=217 localStopStillWorks=true
```

Honest reading: the full turn generated through the real socket transport to the loopback gateway
(`generationMs=81`) with the measured H051 usage (9 output units) accrued in the policy engine, and
the subsequent barge-in over the same live socket cancelled the turn in 6 ms (limit 2,000 ms) with
the H053 cancellation genuinely reaching the gateway's cancellation path (exactly once).
Two scripted post-start provider-health streams (valid start handshake, then a prematurely severed
accepted stream, honestly classified by H053 as `PROTOCOL_ERROR`) opened the circuit; the next AI
turn then failed fast to the typed `POLICY_BLOCKED` fallback with zero gateway operations served;
after the 3,000 ms monotonic cooldown the single user-initiated half-open probe recovered the
circuit to `CLOSED` (`recoveryGenerationMs=90`). With the session request cap at 1, the second
eligible turn was blocked before any transport open (`transportOpensAvoided=true`), produced the
typed `POLICY_BLOCKED` local fallback (`totalMs=217`), and a user cancellation during an active
listening turn still succeeded (`localStopStillWorks=true`).

The open/start-failure neutrality invariant was verified on-device in the first run of this suite:
a scripted connection drop before any HTTP response produced an honest `START_REJECTED` fallback
and did not advance the circuit, before the failure script was corrected to a genuine post-start
health failure. One transient laptop-to-phone ADB interruption occurred before installation (the
device returned as `device` and both installs then succeeded); it did not affect any result. Two
test-infrastructure corrections (loopback server concurrency for the cancellation path, and
session-scoped record awaits) were committed separately as `e07cd65`; no production code,
classification, bound, or assertion was changed.

The H056 Android-audio regression suite then ran on the same device with `RECORD_AUDIO` granted
only for the run and revoked afterward, verified `granted=false`: **OK (3 tests)** —
`H056_DEVICE_FULL_TURN=outcome=AI_COMPLETED totalMs=1917 listeningMs=861 generationMs=139
speakingMs=917 frames=0 micReleased=true`, `H056_DEVICE_BARGE_IN=cancelLatencyMs=78
outcome=CANCELLED reason=INTERRUPTED`, `H056_DEVICE_PROVIDER_LOSS=outcome=AI_FALLBACK
reason=TRANSPORT_FAILURE fallbackSpokenLocally=true totalMs=3061`. JVM fake-timing results are
never presented as device evidence; the levels are reported separately above.

## M5 acceptance status

**M5 is accepted.** The complete evidence chain is satisfied and every level is reported
separately: the H057 policy matrix and H056+H057 integration suites pass on the JVM (920 tests
across 117 suites, zero failures/errors/skips); the H057 configured-loopback acceptance suite
passes 3/3 on the authorized Samsung `SM-S928B` with real socket, cancellation, circuit, and
exhaustion evidence; the H056 Android-audio regression passes 3/3 on the same device with real
microphone and TTS evidence; lint, builds, architecture and documentation guards, and
`git diff --check` pass; no provider secret, model name, or memory write exists anywhere in the M5
boundary; local STOP remained dominant in every scenario. Measured latencies above are documented
operating evidence within the existing H051/H053/H047 bounds, not marketing claims. Known residual
risks remain documented below and do not affect acceptance.

## Next boundary

M6/H058 is eligible and has not started. H057 adds no backend, provider SDK, model name, credential,
memory write, retry chain, failover, production transport, UI, motion tool, or physical action.
