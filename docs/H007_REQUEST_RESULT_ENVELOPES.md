# H007 Request and Result Envelopes Completion

## Record

- Task: `H007 - Request/result envelopes`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H006 commit `3d67370bacff5777081ae8ec9efc2337644f9ab8`
- Runtime impact: none; no router, registry, provider, or executor uses these envelopes
- Hardware claim: none

## Request contract

`CapabilityRequest` carries an opaque request ID, versioned semantic capability reference, exact or any-compatible provider target, actor kind, execution epoch/sequence, monotonic creation/deadline, optional authorization-decision reference, audit ID, and optional bounded typed semantic input. The authorization reference is correlation metadata only and never grants authority.

Deadlines use monotonic milliseconds and must be strictly later than creation. Argument maps are copied and bounded; values are project-owned text/integer/finite-decimal/boolean scalars tied to a versioned contract reference. No byte/raw-packet argument exists.

## Result and failure contract

`CapabilityResult` echoes request ID, capability reference, exact provider instance, execution context, audit ID, and monotonic occurrence time. States represent every required lifecycle outcome:

- nonterminal: `ACCEPTED`, `STARTED`;
- terminal success: `COMPLETED` with output;
- terminal rejection/fault: `REJECTED`, `FAILED`;
- terminal control/time: `CANCELLED`, `TIMED_OUT`;
- terminal uncertainty: `UNKNOWN`.

Typed failure codes distinguish invalid request, unsupported capability, incompatible version, unavailable provider, permission denial, approval requirement, safety rejection, timeout, cancellation, transport/provider failure, malformed result, and unknown outcome. Factory validation rejects state/failure/output/evidence contradictions. Request acceptance cannot masquerade as observed completion, and unknown outcomes require explicit unknown evidence.

## Correlation and stale readiness

`correlationWith` deterministically checks request ID, capability reference, execution epoch/sequence, audit ID, and exact provider target. Mismatches remain typed, allowing later reducers to reject stale-generation, late, misrouted, or incompatible results without H007 implementing a router.

## Boundaries

The version `1.0` JSON codec is strict, deterministic, and rejects unknown fields and malformed identifiers. Envelopes contain no Android/Bluetooth type, vendor packet, raw LOOI data, transport address, UI state, adapter implementation, credential, connection state, physical-feasibility claim, permission grant, or provider-availability truth.

`CapabilityDescriptor` continues to define semantic meaning. A request is one invocation attempt; a result is the observed lifecycle/outcome record. Provider state, authorization, and World/Affordance feasibility remain owned by their respective future domains.

## Completion evidence

- Valid request/result construction and deterministic serialization round trips pass.
- Strict codec tests reject extra UI fields and malformed IDs.
- Correlation tests distinguish stale context, capability, provider, and audit mismatches.
- Failure corpus covers every canonical failure class.
- Cancel, timeout, unknown, nonterminal, completed, output, and evidence pairings are validated.
- Semantic data tests enforce finite values, bounds, and immutable copies.
- Capability API coupling audit: PASS
- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Device/hardware verification: not required and not claimed

## Scope boundary

H008 has not started. H007 adds no routing, execution engine, provider port, registry, manifests, permissions, planner, dashboard, memory, integration, AI, World Model runtime, or LOOI behavior.
