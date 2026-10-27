# H018 - LOOI compatibility facade

## Status

**DONE** - 2026-08-25

## Facade contract

H018 adds `body/looi/capability/LooiCompatibilityFacade`, an adapter-side implementation of the H008
`CapabilityProvider` port. It accepts only the six H017 semantic capability references and creates a
typed `LooiRuntimeInvocation` containing H007 request ID, exact/compatible provider target,
execution epoch/sequence, audit ID, provider generation, semantic input, mapped implementation
boundary, and stop/normal priority. No protocol, BLE, GATT, encoder, motor-polarity, connection,
credential, or Android type crosses this facade.

`LooiVerifiedRuntimePort` is the one composition point to existing LOOI behavior. Its production
implementation must delegate to the verified T032 path, including the local safety controller,
dead-man, E-stop, authority lease, directional sensor gate, limits, and connection lifecycle. H018
does not implement or wire that port into manual control: the existing manual runtime remains the
authoritative path unchanged. This is deliberately fake-delegation coverage, as specified by H018;
manual-drive routing is a backlog non-goal.

## Requests, results, and failures

The facade rejects provider-target mismatch, stale provider generation/epoch, unavailable runtime,
unsupported capability, and invalid semantic input before delegation. It maps safety rejection,
unavailability, transport failure, provider failure, cancellation, timeout, and correlation mismatch
to the existing H007 `CapabilityResult` failure/state model. It preserves request/capability/provider
target, execution context, audit ID, and provider generation; mismatched runtime correlation becomes
typed `MALFORMED_RESULT` failure.

`mobility.stop@1.0` has no input and is delegated at explicit `STOP` priority. Motion, head, and light
require project-owned `CapabilityData`; lower-contact and connection status are input-free read-only
operations and can return normalized `CapabilityData` output only. The facade makes no trust,
permission, active-body, availability, physical-feasibility, or safety claim of its own.

## Evidence

`LooiCompatibilityFacadeTest` covers each required H018 route/failure property: supported mapping,
unsupported fail-closed behavior, safety-owned motion result, stop priority, stale generation,
correlation preservation/mismatch, normalized read-only status output, exact six-capability set, and
protocol/legacy-executor leakage audit. H018 does not alter or invoke the physical runtime, so no new
hardware regression run is required or claimed. T032 remains the accepted physical verification for
the unchanged manual LOOI path.
