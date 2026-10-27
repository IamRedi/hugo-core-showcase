# H005 Capability Identity and Version Completion

## Record

- Task: `H005 - Capability identity/version`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H004 commit `eeb4e77e6c4616933852dc170f37782b9e98a6a0`
- Runtime impact: none; capability contracts are not registered or dispatched
- Hardware claim: none

## Contract

- `CapabilityNamespace`: one or more lowercase semantic segments.
- `CapabilityName`: final lowercase semantic operation/observation name.
- `CapabilityId`: canonical `namespace.name` identity.
- `CapabilityVersion`: positive major and non-negative minor version.
- `CapabilityReference`: canonical `capability.id@major.minor` reference.
- `CapabilityCompatibility`: directed exact, backward-compatible-minor, or explicitly incompatible result.

The contract establishes no capability catalog. Examples in tests exercise the syntax but do not claim that a provider currently implements those capabilities.

## Invariants

- Capability identity is semantic and independent of provider instance, adapter implementation, vendor, transport, host, UI, connection, and LOOI protocol.
- Core capability IDs contain no provider/vendor packet or endpoint identity.
- Kotlin types keep capability identity distinct from provider definition/instance and Hugo identity.
- IDs are canonical lowercase ASCII with dot-separated namespace and final name; underscore-separated words are allowed within a segment.
- Payload/schema descriptors are outside H005 and begin in later tasks.

## Compatibility rules

Compatibility is directional from an offered provider contract to a required contract:

- different capability ID: incompatible;
- different major: incompatible, regardless of minor;
- same major and same minor: exact;
- same major and offered minor greater than required: backward-compatible minor;
- same major and offered minor lower than required: incompatible because the offer is too old.

An adapter/provider later advertises only versions it actually supports. Version compatibility never grants permission, proves availability, or authorizes execution.

## Completion evidence

- Boundary tests cover blank, malformed, noncanonical, incomplete, negative, zero-major, leading-zero, and integer-overflow inputs.
- Round-trip tests cover nested namespaces and versioned references.
- Property-style matrix tests cover major/minor compatibility direction across bounded combinations.
- Explicit tests cover major mismatch, different IDs, and distinction from provider identity.
- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Device/hardware verification: not required and not claimed

## Scope boundary

H006 has not started. H005 adds no payload schemas, descriptors, provider registration, adapter mapping, transport, host synchronization, Home Core runtime, persistence, UI, or LOOI behavior.
