# H008 - CapabilityProvider port

## Status

**DONE** - 2026-08-25

## Contract

`CapabilityProvider` is the universal, pure port between HUGO's request/result boundary and a
future adapter or connector. A body adapter, device adapter, or service connector can implement
the same five operations:

1. `runtimeSnapshot()` reports a point-in-time availability observation.
2. `negotiate()` reports support for requested semantic capability references.
3. `registerCapabilities()` reports descriptor registrations for a runtime generation.
4. `execute()` accepts one H007 `CapabilityRequest` and returns a correlated `CapabilityResult`.
5. `cancel()` accepts a cancellation envelope copied from the original H007 correlation tuple and
   returns a correlated `CapabilityResult`.

The port uses H004 `ProviderIdentity`, H005 `CapabilityReference` and compatibility, H006
`CapabilityDescriptor`, and H007 request/result, audit, deadline, and execution-context models.

## Invariants

- The port and all H008 models are Android-, vendor-, host-, transport-, UI-, credential-, and
  LOOI-protocol-free.
- `ProviderIdentity` is stable configured-provider identity. Runtime availability is only a
  separate snapshot and never changes provider or capability identity.
- A descriptor registration is a declaration for a caller to consume. It does not write a provider
  registry, prove provider availability, trust, authorization, safety approval, or physical
  feasibility.
- `authorizationReference` remains inside the original request as an opaque reference. The port
  does not interpret it as authority.
- Negotiation is restricted to semantic identity/version compatibility. It does not select a
  provider, resolve a manifest, establish a connection, or make a trust/safety/affordance decision.
- `execute()` and `cancel()` return H007 results that retain request ID, capability reference,
  execution context epoch/sequence, audit ID, and selected provider identity, so future stale or
  late result rejection remains possible.
- H008 records a generation on runtime observations, negotiations, and registration snapshots, but
  defines no lifecycle transitions. H009 owns the lifecycle reducer and stale-transition rules.

## Scope boundary

This task adds no provider registry, manifest, adapter implementation, LOOI migration, execution
engine, Android/Bluetooth API, HTTP/MQTT/ROS detail, permission/trust evaluation, safety policy,
World Model runtime, dashboard, AI, or memory behavior. It does not change T001-T032 evidence or
verified LOOI behavior.

## Completion evidence

`CapabilityProviderContractTest` supplies deterministic fake body, device, and service providers.
It verifies the shared port shape, runtime/declaration separation, compatible-version negotiation,
H007 correlation through execute/cancel, and invalid negotiation/duplicate-registration rejection.
The task is JVM-only; no hardware claim is made.
