# H029 Capability Negotiation

## Record

- Task: `H029 - Capability negotiation`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H028 commit `20eb37383dd4d85b2508b411ffaff58cc6569434`
- Runtime impact: none; the negotiation matcher is not wired to production providers or LOOI
- Hardware claim: none

## API and input boundary

H029 adds the pure `capability/negotiation` model and `CapabilityNegotiator`. It consumes immutable,
already-produced evidence rather than calling execution, authorization, connection, or registry
mutation APIs:

- an H027 `PREPARED` provider snapshot containing the H023 resolved manifest and one H024-compatible
  H025 adapter record;
- the known H004 provider instance identity and current H009 lifecycle snapshot;
- an immutable H015 provider-registry snapshot plus an opaque snapshot evidence reference;
- accepted H006 descriptor evidence for manifest-declared H005 capabilities;
- H008 runtime availability, semantic negotiation result, and descriptor-registration result for
  one provider generation;
- zero or more H013 connected-session snapshots with explicit provider-generation bindings; and
- a canonical negotiation UUID, provider instance/generation, expected session IDs, monotonic
  negotiation time, and requested evidence lifetime.

H029 does not create a second capability identity, descriptor, provider lifecycle, connection
session, adapter compatibility, or registry model. Provider-registry absence is informational
because H009 negotiates before `CapabilitiesRegistered`; a present H015 record must correlate
exactly and is never modified.

## Deterministic intersection

For every H010 manifest capability, H029 separately checks:

1. the H023 manifest declaration;
2. H024-compatible H012 adapter support using H005 directional version compatibility;
3. the H008 provider negotiation entry and offered version;
4. exactly one accepted H006 descriptor declaration;
5. exactly one H008 runtime descriptor matching the offered capability;
6. invariant descriptor effect, risk, safety class, permission intent, approval requirement,
   cancellation semantics, and availability semantics;
7. compatible H006 input/output contract identity, major version, and minimum minor version; and
8. runtime limits that are equal to or narrower than accepted descriptor limits.

Adapter or runtime capabilities absent from the validated manifest are excluded and recorded; they
never enter the negotiated set. Multiple plausible descriptors for one semantic capability are a
conflict, not a selection opportunity. Common and specialized manifest consistency remains H023's
responsibility, while adapter/runtime/schema compatibility remains based on H005/H006/H024 evidence.

Reports and all nested input, record, check, limit, session, and provenance lists are defensively
copied and canonically ordered. Equivalent evidence produces equivalent status, capability order,
check order, and redacted report text regardless of input ordering.

## Outcomes and records

Overall outcomes are explicit:

- `NEGOTIATED`: every manifest capability passed and runtime availability is `AVAILABLE`;
- `PARTIALLY_NEGOTIATED`: at least one manifest capability passed but another did not, or all passed
  while runtime availability is explicitly `DEGRADED`;
- `UNSUPPORTED` / `VERSION_MISMATCH`: no capability survived the corresponding intersection;
- `RUNTIME_UNAVAILABLE` / `PROVIDER_NOT_READY`: current H008/H009 evidence cannot support
  negotiation;
- `STALE_SESSION`: provider generation, connection binding, or expected H013 session is stale/wrong;
- `MISSING_PREREQUISITE`: preparation, known provider identity, descriptor, or required connection
  evidence is absent;
- `CONFLICTING_DECLARATION`: identity/family/registry/descriptor/contract/limit evidence conflicts;
- `FAILED`: an external dependency failed before evidence could be evaluated; and
- `CANCELLED`: the caller cancelled negotiation.

Each report retains ordered dimension checks, required/observed semantic values, finding codes, and
opaque provenance references. Each `NegotiatedCapabilityRecord` contains only the negotiated H005
reference, accepted H006 descriptor, provider instance/generation, negotiation ID, current H013
session IDs where required, accepted input/output contracts, H008 runtime availability, narrowed
limits, freshness interval, and evidence references.

The report does not write H015. It does not register capabilities with H009/H015; later lifecycle
code may consume a successful report and perform that distinct transition.

## Generation, connection, and readiness

The provider identity binding from H023, H029 correlation, H009 lifecycle generation, H008 runtime,
H008 negotiation, H008 registrations, and any present H015 record must agree. H009 must explicitly
be in `Negotiating`; merely being discovered, identified, connected, registered, or selected is not
substituted.

Every required H010 connection method needs a current H013 `Connected` session whose semantic method
ID/version matches exactly. Expected and observed session-ID sets, provider-generation bindings, and
session timestamps must correlate. Stale, terminal, connecting, missing, or wrong sessions stop
negotiation.

H013 `CHANNEL_ESTABLISHED` is necessary only when the manifest requires a connection. It is never
sufficient by itself: H008 runtime availability, semantic provider negotiation, and descriptor
evidence must still succeed. H028's BLE service-discovery `Ready` diagnostic is not an input and
cannot make a HUGO capability ready.

Runtime evidence has an explicit expiry. A negotiated record cannot outlive either the requested
validity or runtime evidence expiry.

## Runtime limit semantics

Runtime limits are intersected by canonical H006 limit key. A runtime minimum may move upward and a
runtime maximum may move downward. Missing runtime overrides preserve the accepted descriptor
bound. Units must match, intersections must remain non-empty, and runtime-only undeclared limit keys
are rejected as conflicting.

Any attempted lower minimum or higher maximum is `RUNTIME_LIMIT_BROADENING` and rejects that
capability. H029 never silently clips a broadened claim into apparent success. Effect, risk, safety,
approval, permission-intent, and cancellation declarations must also remain invariant, but their
presence is semantic descriptor data—not a trust, permission, approval, or safety decision.

## Authority, feasibility, selection, and LOOI boundary

```text
MANIFEST CLAIM != ADAPTER SUPPORT != RUNTIME SUPPORT != NEGOTIATED
NEGOTIATED != TRUSTED != AUTHORIZED != SAFE != PHYSICALLY FEASIBLE != SELECTED
```

H014 is not invoked. No grant, approval, trust, execution authority, safety decision, World Model
judgment, active-body state, provider ranking, or dispatch operation exists in the model. Multiple
provider instances can negotiate the same capability concurrently; H029 never ranks or selects one.

The generic package has no Android, Bluetooth, GATT, endpoint, packet, vendor, LOOI, UI, AI, or
World Model dependency. A future LOOI acceptance path can supply H017 descriptors and H018/H008
runtime results after the LOOI adapter-specific handshake and readiness prerequisites complete.
HUGO Core then sees only the same semantic negotiation input used for any body, device, or service.
No production LOOI path is wired or changed by H029.

## Completion evidence

`CapabilityNegotiatorTest` covers full and partial negotiation, manifest capability unsupported by
the adapter, adapter-only capability exclusion, runtime version mismatch, provider-family mismatch,
stale provider generation, stale/wrong connection session, provider lifecycle not ready, missing
connection prerequisite, duplicate descriptor conflict, contract-schema mismatch, runtime limit
narrowing, rejected broadening, runtime unavailable/expired evidence, deterministic ordering,
multiple providers exposing the same capability, cancellation/dependency-failure evidence,
read-only registry use, and absence of authority/vendor/transport/LOOI coupling.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H005/H006/H008/H009/H017/H024/H027/H028/H029 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Next boundary

H030 has not started. H029 adds no authorization gate, provider selection, planner, AI, dashboard,
World Model runtime, memory, Home Assistant, autonomous body switching, LOOI execution wiring, or
new physical behavior.
