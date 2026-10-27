# FL005 - Universal Runtime Composition Harness

## Record

- Task: `FL005 - Universal runtime composition harness`
- Status: **DONE**
- Implementation date: 2026-08-26
- Accepted base: FL004 commit `f41b66d48fa79f202f1551a90df9540deb40ad61`
- Runtime mode: deterministic fake/provider-neutral JVM composition only
- Android/device verification: not required
- Production LOOI, BLE, GATT, protocol, safety, and execution impact: none
- H036: not started

## Composition architecture

`firstlife/runtime` composes already accepted evidence into a narrow immutable projection:

```text
H027 observation/preparation
  -> H015 registered H008 runtime evidence
  -> H013 channel lifecycle evidence
  -> H029 negotiated capability evidence
  -> explicit First Life demo consent
  -> H030 authorization decisions
  -> H031 calibration readiness
  -> H016 body availability/selection
  -> FirstLifeRuntimeSnapshot
       -> semantic FL004 signals
       -> FL003 presentation intents
```

`FirstLifeRuntimeCoordinator` owns only ephemeral application-session correlation. It does not own a
provider port and has no operation that discovers, connects, selects, authorizes, dispatches, or
executes. The accepted H003 `HugoIdentity` is supplied once and is retained unchanged across provider
loss, negotiation expiry, authorization loss, and runtime-session reset.

The coordinator accepts envelopes carrying an exact First Life runtime session, positive monotonic
sequence, non-regressing monotonic time, and typed accepted HUGO evidence. It privately correlates the
known provider instance, provider generation, H013 connection session, H029 negotiation, and H030
decision audit. Raw provider identity and connection-session values never enter the outward snapshot.

## Narrow runtime snapshot

The snapshot exposes only H003 identity, First Life runtime session, last accepted sequence/time,
H027 phase/prepared state, H015 registration fact, redacted provider alias/family/generation, H008
runtime availability, H013 connection readiness, H029 negotiation reference/status and sorted
semantic capabilities, H030 status per semantic capability, explicit-consent presence, H031 status,
H016 body availability/selection, H033 blocked projection, and an H034 diagnostic reference.

The following remain independent facts:

```text
provider prepared
!= registered
!= channel established
!= negotiated
!= authorized
!= selected active body
!= calibration ready
!= safe to move
```

`bodyReady` requires a prepared and registered BODY provider, current available H008 evidence, an
established H013 channel, every configured First Life semantic capability in the current H029 report,
H016 availability, and no blocker. It does not require or imply authorization or selection.
`actionAuthorizationReady` requires explicit current H030 `AUTHORIZED` decisions for the configured
action subset after an explicit demo-consent event. `actionReady` additionally requires selection and
H031 readiness, but remains proposal evidence only. `safeToMove` and `executesCapabilities` are hard
false.

## Interaction, presentation, and blocked-state boundaries

FL004-facing output is restricted to `RuntimeReady`, `BodyAvailable`, `BodyUnavailable`,
`ProviderBlocked`, `CapabilityReady`, and `RuntimeLost`. These values contain no registry, manifest,
adapter, connection internals, provider-instance identity, or transport facts. FL003-facing output is
only the existing `FirstLifePresentationIntent` vocabulary (`IDLE`, `BODY_READY`, or `BLOCKED` in this
harness); the coordinator never touches visual state or animation.

The coordinator accepts blocker data only as `BlockedStateDiagnostic`, the canonical H033-to-H034
projection. The fake missing-adapter scenario is produced by `ProviderBlockedStateMapper` and
`toDiagnosticSection`, preserving the exact `ADAPTER_MISSING` and resolution-availability codes.
H034 `ProviderDiagnosticId` is the only outward diagnostic correlation. No raw failure text or private
identifier is accepted by the snapshot.

## Fake scenarios and fail-closed behavior

The deterministic fake BODY trace covers `OBSERVED -> PREPARED/REGISTERED -> CHANNEL_ESTABLISHED ->
NEGOTIATED -> explicit consent -> AUTHORIZED -> H031 READY -> H016 available/selected`. It represents
the provider-neutral semantic references `mobility.stop@1.0`, `mobility.move@1.0`,
`body.head.position.control@1.0`, `body.light.control@1.0`, and
`provider.connection.status@1.0`. It proposes no H007 request and invokes no provider.

Additional fixtures prove unavailable runtime evidence, missing adapter, unsupported negotiation,
missing authorization, stale generation, provider loss, negotiation expiry, authorization loss,
runtime reset, and BODY/DEVICE/SERVICE family composition without inheritance. Provider loss clears
all provider-derived readiness while HUGO remains runtime-ready with no body. Negotiation expiry
clears capability, consent, authorization, and calibration evidence. Authorization loss clears only
consent/authorization readiness. Reset changes the runtime session and clears all ephemeral state but
preserves the exact HUGO identity.

Wrong-session, stale/equal sequence, regressed time, stale/future generation, wrong provider,
connection, negotiation, decision, calibration, and body correlation are ignored without mutation.
Capability output and signals are deterministically sorted. `SessionApprovalConsumption` implements
the H030 atomic consume-once port in memory: replay returns `AlreadyConsumed`, conflicting binding
returns `BindingConflict`, and explicit runtime reset can clear it. It issues no H014 approval and
persists no authority.

## Tests and checks

`FirstLifeRuntimeCoordinatorTest` covers the complete fake BODY trace; no-provider startup; H033/H034
missing-adapter blocking; unavailable runtime; incomplete negotiation; missing authorization; full
body/action readiness; provider loss; stale generation; duplicate and wrong session; negotiation
expiry; authorization loss; reset/identity continuity; DEVICE/SERVICE compatibility; deterministic
signal order; outward privacy shape; forbidden coupling scan; and consume-once replay/conflict/reset.

The H019 architecture guard now treats `firstlife/runtime` as a pure boundary and forbids Android,
platform, feature/UI, LOOI, network-client, vendor, and protocol dependencies/references.

| Check | Result |
|---|---|
| First Life documentation/order guard | PASS |
| HUGO V2 documentation guard | PASS |
| T032 baseline evidence guard | PASS |
| Architecture dependency guards | PASS |
| Relevant H015/H016/H027/H029/H030/H033/H034/FL004/FL005 JVM tests | PASS |
| Full `testDebugUnitTest` | PASS |
| `lintDebug` | PASS |
| `git diff --check` | PASS |
| Android/LOOI/device run | Not required; pure fake composition only |

## Risks and residual gates

- FL006 must translate real LOOI evidence into these universal inputs without importing this fake
  fixture data or adding a second transport/protocol path.
- Explicit demo consent is a slice-local prerequisite, not an H014 trust/grant/approval record. FL006
  and FL007 must still construct real current H014/H030 evidence before any dispatch.
- `actionReady` is deliberately not safety or physical-feasibility evidence. FL007 must preserve the
  full H018-to-T032 safety/runtime route and cannot use the snapshot as execution authority.
- FL008 owns user-facing active-body selection and diagnostics; FL005 performs no UI selection.

FL006 is the next eligible First Life task. It remains unstarted. H036 remains unstarted.
