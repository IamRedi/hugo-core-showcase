# H015 - Provider registry port

## Status

**DONE** - 2026-08-25

## Scope

H015 adds the pure `capability/registry` port, its immutable normalized provider records, and a
deterministic in-memory reference implementation used by JVM contract tests. It is a source of
provider references, not a discovery engine, connection manager, trust authority, permission store,
capability execution engine, World Model, or memory store. No persistence is implemented.

Each `ProviderRegistryRecord` combines the existing H004 provider instance identity, H010/H011
common/specialized manifest binding, H012 adapter identity/package-digest reference, H006
capability descriptors, and H008/H009 runtime/lifecycle snapshot. An optional opaque host-association
reference supports a future multi-host runtime without making a host part of provider identity or
claiming host authority, address, credentials, or availability.

## Lifecycle and queries

`ProviderRegistry` supports register, immutable snapshot lookup, exact family query, exact
capability-reference query, normalized runtime update, and atomic unregister. Query results are
candidates only. A listed capability does not mean that the provider is connected, available,
trusted, authorized, safe, physically feasible, selected, or executable.

Records carry H008 `ProviderGeneration` and a local `ProviderRegistryRevision`. Updates require the
current revision and reject lower provider generations. Unregister requires both the current revision
and current provider generation, so a stale session cannot unregister a later clean registration or
reconnection. H009 remains the sole owner of lifecycle transition legality; H015 only retains its
state as normalized observation.

Registry presence never grants H014 trust, a permission grant, approval, safety approval, physical
feasibility, or connection readiness. The registry carries no Android/Bluetooth objects, transport
addresses, vendor packets, credentials, secrets, UI state, or provider-specific payloads.

## Evidence

`ProviderRegistryTest` covers valid registration, duplicate and identity-conflicting instances,
family and exact-capability queries, immutable snapshot inputs, lifecycle/availability generation
validation, stale revision/generation rejection, atomic stale-session unregister protection, unknown
unregister behavior, and opaque host-reference validation. The tests also demonstrate that an
unavailable provider may be returned as a candidate; registry lookup does not imply authority or
readiness.

No LOOI, Android, BLE, runtime safety, device behavior, physical evidence, or T001-T032 artifact is
modified by H015. A future registry persistence task must define durable integrity and migration
semantics without changing this provider-neutral port.
