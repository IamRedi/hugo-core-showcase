# H053 Gateway Client / Reducer

## Record

- Task: `H053 - Gateway client/reducer`
- Status: `DONE`
- Implementation date: 2026-09-02
- Prerequisites: H051 provider-neutral gateway contract and H052 gateway authentication
- Runtime impact: none; H053 supplies an unwired client/reducer and a provider-neutral transport port
- Deployment claim: none; no backend, endpoint, HTTP implementation, provider, or model is configured

## Client and transport boundary

`ai/gateway` consumes the exact H051 request, cancellation, event, usage, and terminal contracts. The
`GatewayHttpStreamPort` is the only transport seam. It accepts one exact H052 endpoint, a synchronous
short-lived bearer-token byte copy, and a bounded encoded request, then returns one pull-based stream
channel. H053 intentionally supplies no production HTTP library, URL, gateway server, retry policy,
provider SDK, or model adapter.

`GatewayClient.start` maps text, realtime, vision, and embedding operations to the matching H052
scope. H052 performs endpoint, audience, scope, expiry, revoke, and protected-storage checks before
the transport callback can run. Token and request byte arrays are zeroed after the synchronous open
call. Auth absence, expiry, revoke, endpoint denial, audience mismatch, insufficient scope, protected
storage failures, and typed transport-open failures remain distinct start results.

```text
STREAM STARTED / COMPLETED
!= MODEL OR PROVIDER TRUSTED
!= H014/H030 AUTHORIZED
!= SAFETY APPROVED
!= SKILL EXECUTED
!= CAPABILITY EXECUTED
```

Only one request may be active in a client instance. A second start returns `BUSY`; after a terminal
result the client may start a new request. This is lifecycle serialization, not model/provider
selection or conversation orchestration.

## Deterministic stream reducer

`GatewayStreamReducer` is a pure immutable reducer. It requires the H051 start event at sequence
zero, exact consecutive sequences, exact request/operation correlation, unique event IDs, at most
one start and usage event, and no event after terminal state. Tool proposals must name a skill
declared by the original H051 request. Embeddings must use a requested input index exactly once.

On a terminal event the complete stream is revalidated with H051 `GatewayStreamValidator`, so the
client cannot create a second terminal grammar or accept an incomplete operation-specific stream.
Completed, refused, failed, gateway-cancelled, and gateway-timed-out outcomes remain distinct. The
same request and event sequence produces the same immutable state, report, and redacted trace.

## Bounds and privacy

The client/reducer enforces:

- 64 KiB maximum per received frame;
- H051's 1,024-event stream maximum;
- 128 partial-text events and 64 KiB total UTF-8 partial text;
- 32 tool proposals and 32 KiB total canonical tool-argument data;
- 16,384 total embedding dimensions;
- the exact request timeout on a monotonic clock.

Frames, encoded requests, cancellation documents, and token callback copies are cleared after use.
Snapshots report operation, counts, byte/dimension totals, and terminal status. Diagnostic strings
and traces redact request correlation and all prompt, partial text, tool arguments, token, endpoint,
and vector content. H053 retains bounded response state only for the client lifecycle; it adds no
disk persistence, logs, conversation history, memory, raw media, provider key, user identity, body,
or transport address.

## Cancellation, timeout, and close

Client cancellation and exact-deadline timeout encode the H051 cancellation document and attempt to
deliver it through the active channel. They close the channel exactly once even if delivery or close
throws. Their reports use `CLIENT_CANCELLED` or `CLIENT_TIMED_OUT` and explicitly set
`gatewayTerminalConfirmed=false`: sending a cancellation request is not evidence that the gateway
confirmed cancellation. A later H051 `cancelled` or `timed_out` terminal event is instead reported
as a gateway-confirmed terminal outcome.

Malformed/non-UTF-8 frames, invalid correlation/order/terminal grammar, unexpected end-of-stream,
and typed read failure fail closed and close the channel. No retry is invented, and no transport
success is inferred.

## Tool and authority boundary

H050 tool events remain proposals only. H053 checks that their skill identities were declared and
buffers their bounded arguments; it does not validate H054 context, mediate them through H055,
create H007 requests, obtain H030 decisions, approve safety, or invoke a skill/capability. State,
snapshot, and terminal-report authority flags remain false.

The H019 dependency guard includes `ai/gateway` and rejects Android, feature, platform, vendor,
LOOI, capability runtime, authorization, safety, HTTP SDK, persistence, and logging coupling. No
Android or hardware verification is required because H053 is pure and unwired.

## Verification

Fake-stream integration tests cover:

- partial text, usage, and completion reduction;
- bounded declared tool proposals without authority;
- every H051 gateway terminal outcome;
- out-of-order, wrong-correlation, malformed, invalid UTF-8, duplicate-usage, undeclared-tool, and
  invalid-embedding rejection;
- exact text-buffer boundary and overflow failure;
- exact monotonic timeout, explicit cancellation, cancellation-document correlation, and
  exactly-once close;
- auth absence/expiry/scope/audience/endpoint failure without opening transport;
- typed open/read/end failures;
- overlapping-request rejection and restart after terminal;
- equivalent-input determinism and content-free diagnostics;
- absence of Android, network SDK, persistence, provider, LOOI, authority, or execution coupling.

Required documentation/evidence guards and architecture dependency guards pass. The focused H053
suite passes 13 tests; the full JVM suite passes 852 tests across 113 suites with zero failures,
errors, or skips. `lintDebug` passes with zero errors (13 warnings and one informational item),
`assembleDebug` and `git diff --check` pass. No gateway, model, account, media, provider, LOOI,
capability, or physical action is contacted or executed.

## Next boundary

H054 is eligible and has not started. H053 adds no bounded AI context packet, H037 context
serialization, memory port, tool mediation, model policy, production transport, backend, or real
model execution.
