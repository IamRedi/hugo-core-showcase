# H030 Authorization Gate Runtime

## Record

- Task: `H030 - Authorization gate runtime`
- Status: `DONE`
- Completed: 2026-08-26
- Prerequisite: H029 commit `6a8ecf68640b4435e87b037d892f14d3bf37eb17`
- Runtime impact: none; the gate is not wired to provider dispatch or the production LOOI runtime
- Hardware claim: none

## API and authority boundary

H030 adds the provider-neutral `trust/runtime` package and `AuthorizationGate`. The gate accepts one
concrete H007 `CapabilityRequest`, caller-supplied decision correlation, exact H004 provider identity
and current generation/session context, a current H029 negotiation report, H014 authority records,
bounded requested scope, current host/origin/presence/embodiment context, policy references, and a
monotonic evaluation time.

The gate performs no authority lookup by opaque string. H007 `authorizationReference` remains only a
correlation key: H014 must receive a real, matching, live `PermissionGrant`. Discovery, manifest,
adapter, connection, registration, and negotiation facts are passed to H014 only as non-authority
evidence and cannot replace trust, permission, or approval records.

The typed runtime model includes:

- `AuthorizationGateInput` and immutable bounded authority/runtime contexts;
- `AuthorizationDecisionId`, bounded evidence references, and policy references;
- `AuthorizationDecisionStatus` and detailed `AuthorizationDecisionReason` values;
- canonical `AuthorizationAuditRecord`, checks, dimensions, and evidence sources; and
- `ApprovalConsumptionPort` with correlated attempts, receipts, and fail-closed results.

Model factories reject malformed IDs, invalid evidence freshness, empty/excessive policy lists,
excessive requested scope, more than 16 connection sessions, more than 64 embodiment evidence items,
more than 64 revocations, and duplicate revocation IDs. The public boundary contains no credential,
token, endpoint, platform, vendor, transport, UI, AI, or LOOI type.

## Request and negotiated-capability validation

Before H014 evaluation, H030 requires:

1. a request whose monotonic creation/deadline interval contains the current time;
2. an exact H007 provider target equal to the current H004 provider instance;
3. an H029 `NEGOTIATED` or `PARTIALLY_NEGOTIATED` report;
4. exactly one negotiated record for the requested H005 capability/version;
5. matching report, record, provider-instance, provider-generation, and negotiation IDs;
6. a fresh negotiated record at the evaluation timestamp;
7. exact current H013 session correlation when the negotiated record requires sessions;
8. matching request actor kind and semantic capability; and
9. exact accepted H006 input-contract correlation.

Failed or stale negotiation evidence never reaches authority evaluation. An H013 channel, H015
registry record, active-body selection, or authorization reference cannot synthesize a negotiated
capability. H030 does not select a provider; H007 must already target one exact instance.

## H014 evaluation and decisions

After semantic validation, `AuthorizationGate` delegates trust, grant, scope, lifetime, revocation,
approval, and contextual restriction semantics to the accepted H014 `TrustPermissionEvaluator`.
It does not create a second privilege hierarchy: `OBSERVE`, `ACT`, and `MODIFY` continue to match
exactly. Observe cannot satisfy Act, and Act cannot satisfy Modify.

H014 denials map to explicit runtime decisions including `TRUST_REQUIRED`, `PERMISSION_REQUIRED`,
`APPROVAL_REQUIRED`, `EXPIRED`, `REVOKED`, `SCOPE_MISMATCH`, `ACTOR_MISMATCH`,
`PROVIDER_MISMATCH`, `CAPABILITY_MISMATCH`, `SESSION_MISMATCH`, `HOST_RESTRICTED`,
`PRESENCE_REQUIRED`, `MOUNTED_HOST_REQUIRED`, `PROXIMITY_REQUIRED`, `ACTIVE_BODY_REQUIRED`,
`PROHIBITED`, and `REPLAY_REJECTED`. Invalid negotiation or consumption evidence yields
`STALE_NEGOTIATION`, `INVALID`, or `FAILED`; no denial is converted into an optimistic default.

One-time, session, and persistent grant bindings remain H014 concepts. Persistent means only that
the grant is not request/session-bound; expiry and revocation still apply. Session grants require the
exact current H014 authority-session ID, and one-time grants require the exact H007 request ID.

## Contextual authority evidence

Host role, execution origin, and authority-session correlation come from `AuthorizationHostContext`.
Physical presence requires explicit, fresh, positive `PhysicalPresenceEvidence`; absence, false,
future-dated, or expired evidence evaluates as not present.

When H014 restrictions require mounting, proximity, or active-body status, H030 derives those values
from the accepted H016 `ActiveBodyState` for the same host and exact provider instance:

- mounted requires H016 `MOUNTED` plus fresh `MOUNTING_VERIFIED` evidence;
- nearby requires the H016 nearby observation plus fresh `PROXIMITY_OBSERVED` evidence; and
- active body requires an unexpired H016 selection for the exact provider.

Non-body providers or absent, wrong-host, stale, or mismatched H016 evidence evaluate false. Active
body is checked only when the grant requires it; otherwise it is not a prerequisite for capability
authorization.

## Atomic approval consumption

H014 remains a pure evaluator. Its permitted decision identifies one-time approval consumption, and
H030 also recognizes an H014-validated `EVERY_REQUEST` approval as request-bound. Before returning
`AUTHORIZED`, H030 calls `ApprovalConsumptionPort.consumeOnce` for either form.

The port contract requires atomic compare-and-consume by approval ID. At most one concurrent caller
may receive `Consumed`; later callers receive `AlreadyConsumed` and H030 returns `REPLAY_REJECTED`.
H030 validates approval ID, authorization reference, request ID, decision ID, and monotonic receipt
time before accepting a consumption result. A conflicting receipt returns `INVALID`; storage failure
returns `FAILED`.

H030 defines no durable approval repository. The tests use a synchronized in-memory fake to prove
the runtime atomicity contract. A future persistent implementation must provide the same atomic
semantics across its supported process/crash boundary and must not treat a pre-consumption H014
snapshot as reusable authority.

## Audit and security boundaries

Every decision carries a bounded, canonical audit record with request/audit/decision correlation,
exact semantic provider and capability binding, generation and negotiation correlation, time,
ordered policy references, authority records considered, ordered dimension checks, and an optional
approval-consumption receipt. Equivalent inputs produce equivalent ordering independent of policy
or revocation input order. Diagnostic `toString` omits the private provider-instance ID and authority
record values.

Audit evidence contains project-owned opaque references only. It contains no credentials, tokens,
provider secrets, transport addresses, raw protocol data, UI state, or vendor packets.

```text
AUTHORIZED != SAFE != PHYSICALLY_FEASIBLE != EXECUTED != SUCCESSFUL
```

`AuthorizationDecision` explicitly reports that it grants no safety authority, proves no physical
feasibility, dispatches no capability, and records no execution success. For future LOOI motion, an
authorized semantic request must still pass through the existing deterministic T032 dead-man,
E-stop, sensor, and limit safety path before adapter execution. H030 imports and calls no LOOI,
Android, BLE, GATT, provider execution, capability router, planner, or AI implementation.

## Completion evidence

`AuthorizationGateTest` covers valid Observe/Act/Modify decisions, exact privilege separation,
missing/expired/revoked trust and grants, authority-reference non-authority, wrong actor/provider/
capability/resource, host and remote-origin restrictions, physical presence, mounting, proximity,
active-body evidence, session and one-time grant binding, one-time approval success/replay,
32-caller concurrent consumption, every-request confirmation and consumption, expired/revoked/
denied/consumed approvals, prohibited capability, unsuccessful/expired/missing H029 evidence,
wrong generation/session, bounded invalid inputs, deterministic audit ordering, and strict
safety/execution/vendor/transport/LOOI separation.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H007/H014/H016/H029/H030 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Next boundary

H031 has not started. H030 adds no calibration contract, provider selection, capability router,
physical safety execution, approval UI, planner, AI, dashboard, memory, Home Assistant, Developer
Mode, autonomous body switching, LOOI runtime rewiring, or new physical behavior.
