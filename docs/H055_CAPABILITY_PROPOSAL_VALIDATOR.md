# H055 Capability Proposal Validator

## Record

- Task: `H055 - Capability proposal validator`
- Status: `DONE`
- Implementation date: 2026-09-02
- Prerequisites: H030 authorization, H050 skill registry, H053 stream state, and H054 context packet
- Runtime impact: none; H055 is an unwired pure validator with a bounded in-memory replay ledger
- Execution claim: none; no skill registry executor, capability dispatch, provider, or physical path is invoked

## Validation chain

`ai/tools` accepts one already typed H051 `GatewayToolProposal` and validates it through the existing
boundaries in this order:

1. the exact proposal occurs exactly once in the supplied H053 stream;
2. the H054 context has the same gateway request/operation and contains that exact tool schema;
3. proposal, H007 request, and H050 execution IDs are new in the validator ledger;
4. H050 contains the exact `SkillReference` version and its definition equals the H054 projection;
5. proposal arguments exactly equal the H050 fixed-input schema, with no extra or changed field;
6. semantic policy rejects motion, raw transport/protocol, e-stop/reset, limit, authority, and install lifecycle controls;
7. the existing H007 request has the exact capability, actor, fixed input, current deadline, and exact provider target;
8. the negotiated capability record matches the H030 negotiation/provider/generation, definition contracts, and current time;
9. the negotiated descriptor is informational/low risk and not prohibited or safety-critical;
10. every-request confirmation has an H030 atomic approval-consumption receipt; and
11. the existing H030 decision is `AUTHORIZED` and passes H050 `AuthorizedSkillInvocation.create` correlation.

The accepted result contains the existing H050 `AuthorizedSkillInvocation` as proof of correlation,
not new authority. H055 does not call `CapabilityBackedSkillExecutor`, an
`AuthorizedSkillDispatchPort`, H030 `AuthorizationGate`, an approval-consumption port, or any
provider/runtime adapter.

```text
AI TOOL PROPOSAL
!= VALIDATED PROPOSAL
!= NEW AUTHORITY
!= SAFETY APPROVAL
!= SKILL STARTED
!= CAPABILITY EXECUTED
!= OBSERVED SUCCESS
```

## Schema, version, and injection boundary

H051 remains the sole wire schema. Its strict codec rejects unsupported versions, missing fields,
unknown scalar types, extra proposal/scalar fields, and noncanonical skill versions before H055 can
receive a typed proposal. H055 then compares the complete typed scalar map with the exact H050
fixed input. Prompt text, a field named `authorization`, or another apparent instruction cannot
extend the schema or affect H030 state.

The registry lookup is exact-version only. A known skill ID with another version yields
`UNSUPPORTED_SKILL_VERSION`; an absent ID yields `UNKNOWN_SKILL`. Presence in H054 or H050 does not
mean installed, enabled, authorized, safe, available, or executing.

## Target, risk, approval, and authority

H055 does not create a provider target. The H007 request must already carry one exact provider
instance matching the H030 audit and negotiated capability record. Any-compatible target, stale
request, wrong actor/capability/input, stale/wrong negotiation, missing negotiated capability, or
authorization correlation mismatch is rejected.

Only H050 `OBSERVATION`/`NON_MOTION_CONTROL` definitions with informational/low negotiated risk are
eligible in H055. Elevated, high, safety-critical, and prohibited descriptors fail closed. Motion
(`mobility`/move/turn), raw BLE/GATT/packet/protocol identifiers, e-stop reset/override, safety-limit
changes, trust/permission/authorization/grant/approval mutation, and adapter install/enable/reset
semantics are hard denied even if a bad definition is inserted into a synthetic registry.

H030 status remains authoritative. H055 reports trust required, grant required, approval required,
authorization replay, or other denial separately. It cannot upgrade those results. For
`EVERY_REQUEST`, an authorized-looking decision without H030 approval-consumption evidence is still
rejected as `CONFIRMATION_REQUIRED`.

## Idempotency and audit

The synchronized process-local ledger tracks proposal ID, H007 request ID, and H050 execution ID.
Every stream-correlated attempt reserves all three before registry/schema/authority evaluation, so
an unauthorized or malformed proposal cannot later be replayed under new evidence. The default
capacity is 256 and the hard maximum is 1,024. Entries are never evicted; exhaustion rejects new
work rather than forgetting replay evidence.

This ledger is not durable cross-process idempotency. H050 independently retains its executor
request/execution ledger, and H030 atomically consumes every-request approvals. A future runtime must
preserve all three layers; H055 does not replace either H030 or H050.

Every result contains a deterministic, content-free audit of stream, context, idempotency, registry,
schema, semantic policy, request, negotiation, risk, approval, and authorization checks. Proposal
content, scalar values, provider instance, authorization reference, and prompt are absent from
diagnostic strings.

## Rejection matrix

| Boundary | Representative typed reasons |
|---|---|
| H053 stream | `PROPOSAL_NOT_IN_STREAM` |
| H054 context | `STREAM_CONTEXT_MISMATCH`, `SKILL_NOT_IN_CONTEXT`, `CONTEXT_SCHEMA_MISMATCH` |
| Idempotency | `PROPOSAL_REPLAYED`, `REQUEST_REPLAYED`, `EXECUTION_REPLAYED`, `IDEMPOTENCY_CAPACITY_EXHAUSTED` |
| H050 registry/schema | `UNKNOWN_SKILL`, `UNSUPPORTED_SKILL_VERSION`, `ARGUMENT_SCHEMA_MISMATCH` |
| Semantic policy | `FORBIDDEN_SEMANTIC`, `MOTION_NOT_ALLOWED` |
| H007 request | capability/actor/input mismatch, exact-provider required, request stale |
| Negotiation | correlation mismatch, missing capability, stale evidence |
| Risk/confirmation | `RISK_NOT_ALLOWED`, `CAPABILITY_PROHIBITED`, `CONFIRMATION_REQUIRED` |
| H030 | `TRUST_REQUIRED`, `GRANT_REQUIRED`, `APPROVAL_REQUIRED`, `AUTHORIZATION_REPLAYED`, denial/correlation mismatch |

## Verification

`ToolProposalValidatorTest` contributes 13 focused tests covering exact acceptance without dispatch,
fixed light-off input, stream/context correlation, allowlist/version handling, H030 trust/grant/
approval/replay mapping, H007 request and provider binding, negotiation freshness, risk/prohibition/
confirmation, motion/raw/e-stop/limit/install red-team definitions, proposal/request/execution replay,
fail-closed capacity, multi-case H051 schema fuzzing, deterministic redacted audit, and source-level
absence of executor, Android, provider implementation, raw protocol, safety, or approval-consumption coupling.

Required documentation/evidence and architecture dependency guards pass. The focused H055 suite
passes 13 tests; the full JVM suite passes 877 tests across 115 suites with zero failures, errors,
or skips. `lintDebug` passes with zero errors (13 warnings and one informational item),
`assembleDebug` and `git diff --check` pass. No Android/device/LOOI verification is required because
H055 is pure, unwired, and performs no action.

## Next boundary

H056 is eligible and has not started. H055 adds no conversation/barge-in runtime, AI action mapper,
skill dispatch, provider execution, audio integration, model policy, memory write, backend, or motion tool.
