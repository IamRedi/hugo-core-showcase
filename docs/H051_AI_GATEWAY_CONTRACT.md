# H051 AI Gateway Contract

## Record

- Task: `H051 - AI gateway contract`
- Status: `DONE`
- Completed: 2026-09-02
- Prerequisites: H036 context contract, H049 speech contracts, H050 skill registry
- Runtime impact: none; H051 is an unwired wire-contract artifact and pure validator
- Hardware claim: none

## Contract artifact and API

H051 adds `gateway-contract/v1/gateway-message.schema.json`, a matching OpenAPI 3.1 description,
portable outcome examples, and the Android-free `gateway/contract` Kotlin boundary. The Kotlin model
uses project-owned redacted UUID references, strict factories, a canonical JSON codec, and typed
failures. Documents reject missing, unexpected, malformed, contradictory, oversized, non-finite, or
unsupported-version data.

Schema version `1.0` uses strict major compatibility. A different major is incompatible; a consumer
requiring a later minor rejects an older minor; later same-major minors are explicitly identifiable
as additive compatibility candidates. The `1.0` codec remains strict and rejects fields it does not
understand rather than silently changing semantics.

## Operation requests

One bounded request envelope distinguishes four exact shapes:

| Operation | Input carried by H051 |
|---|---|
| `text` | one bounded prompt and optional declared H050 skill references |
| `realtime` | non-empty input/output modality declarations only |
| `vision` | one bounded question and one opaque request-scoped attachment reference |
| `embedding` | one to 64 bounded text inputs |

Every request carries a hard output-unit and timeout budget. Vision does not carry image bytes;
realtime does not carry raw audio. H051 defines neither media upload nor transport. Skill references
are sorted and duplicate-free. No request contains provider identity/type, endpoint, key, secret,
robot packet, device address, authority, or execution state.

## Streaming, cancellation, errors, and usage

Every stream begins with `started` at sequence zero, uses consecutive sequence numbers and unique
event IDs under one request/operation correlation, and contains exactly one final event at the end:
`completed`, `refusal`, `failed`, `cancelled`, or `timed_out`. Partial text, an H050 tool proposal,
embedding vectors, and usage evidence are nonterminal. Usage can occur at most once and distinguishes
measured, estimated, and unknown evidence. Optional cost is a non-negative integer number of micros
with an ISO-style currency code; it is evidence, not billing authority.

Cancellation has its own redacted correlation ID and typed user, barge-in, background, deadline, or
client-shutdown reason. A cancellation request is not a terminal confirmation; the terminal stream
event remains required. Failures are typed as invalid request, unauthenticated, rate limited,
unavailable, internal, or protocol error and explicitly state retryability. H051 performs no retry.

## Tool and authority boundary

A `tool_proposal` carries only an exact H050 `SkillReference`, a redacted proposal ID, and a bounded
map of typed scalar arguments. It is untrusted model output:

```text
TOOL PROPOSED
!= CAPABILITY REQUEST CREATED
!= PROVIDER SELECTED
!= TRUSTED
!= AUTHORIZED
!= SAFE
!= EXECUTED
```

H055 will validate and mediate proposals. H051 does not call H050 execution, H030 authorization,
provider runtime, safety, or any adapter.

## Provider, privacy, and runtime separation

The machine contract has no concrete model/vendor name or SDK type, provider secret, credential,
stable device identifier, Android type, raw transport detail, raw audio, image bytes, transcript
store, context snapshot, or memory. Opaque attachment references and content values are redacted by
diagnostic `toString` methods. The codec serializes content only when explicitly used as the wire
codec; it does not log, persist, upload, or retain it.

H052 owns authentication. H053 owns the network client/reducer and bounded buffering. H054 owns
privacy-budgeted AI context. H055 owns proposal validation. H056 owns conversation/barge-in, and H057
owns model selection and cost policy. No backend is deployed or selected in H051.

## Portable examples and coverage

Seven checked-in event-stream examples cover partial text plus measured usage/cost, an H050 tool
proposal, privacy refusal, typed retryable failure, cancellation, timeout, and embedding plus unknown
usage. `GatewayContractTest` validates:

- canonical request/cancel/event round trips for all four operation types;
- every portable example and every required outcome;
- consecutive ordering, exact correlation, unique IDs, one usage event, and one final event;
- refusal, cancellation, timeout, failure, usage/cost, tool proposal, and embedding bounds;
- strict missing/unexpected/malformed field rejection and unsupported major versions;
- deterministic ordering of equivalent skill declarations and proposal arguments;
- redacted identifiers/content and absence of authority fields; and
- no Android, provider implementation, secret, LOOI, raw transport, or runtime coupling.

Required completion checks pass: First Life documentation/order guard, HUGO V2 documentation guard,
T032 baseline evidence guard, architecture dependency guard, focused H051 tests, full
`testDebugUnitTest`, `lintDebug`, `assembleDebug`, JSON artifact parsing, and `git diff --check`.

No Android-device or LOOI verification is required or claimed because H051 changes no production,
audio, provider, connection, safety, manual-control, or physical path.

## Next boundary

H052 is eligible and has not started. H051 adds no authentication token, credential storage,
endpoint policy, gateway client, backend, model adapter, AI context, conversation runtime, or tool
execution.
