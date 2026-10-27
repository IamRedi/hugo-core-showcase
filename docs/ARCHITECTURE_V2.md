# HUGO Architecture V2

## Status and precedence

This is the post-T032 target architecture. The original `ARCHITECTURE.md` remains the historical design that produced the verified LOOI reference implementation. V2 extends it; it does not invalidate its physical safety boundaries or evidence.

## System shape

```text
Hugo Identity
  -> Working Context / Planning / Memory / Skills
  -> Capability Registry
  -> Trust / Permission / Approval Gate
  -> Capability Request Router
  -> CapabilityProvider
       -> BodyAdapter -> deterministic body safety -> transport -> hardware
       -> DeviceAdapter -> device policy -> connection/API -> device
       -> ServiceConnector -> scope policy -> authenticated API -> service
```

AI is an untrusted proposal source above authorization. A model never owns connection state, permissions, safety policy, adapter installation, or raw protocol access.

## Core domains

### Identity

`HugoIdentity` is independent of host and provider. Provider instances, current body, current host, and signed-in services are contextual relationships.

### Capability

Versioned semantic identities, descriptors, request/result envelopes, side-effect class, limits, evidence, and provider registration. Capability contracts contain no Android, BLE, vendor SDK, or model-provider types.

### Provider

`CapabilityProvider` is the common lifecycle and registration boundary. `BodyAdapter`, `DeviceAdapter`, and `ServiceConnector` are roles with distinct metadata and policy, not interchangeable bodies.

### Trust and authorization

Discovery observations, identity confidence, trust records, permission grants, approval policy, revocation, and audit are separate state. A manifest claim cannot grant itself authority.

### Context

The Context Engine builds bounded current state from authorized observations. Context is ephemeral by default and does not become memory automatically.

### World and affordances (future)

Current entities, spatial relations, geometry evidence, and physical feasibility are governed canonically by [World Model, Spatial Reasoning, and Affordances](WORLD_MODEL_AND_AFFORDANCES.md). This future layer informs provider selection but cannot grant authority or bypass deterministic safety.

### Memory

Working, episodic, semantic, personal, device, body, and strategy memory have shared provenance/privacy controls but separate validation and retrieval rules.

## Dependency direction

```text
UI / conversation / planning / dashboard
        -> application use cases
        -> identity + capability + provider + trust + context + memory contracts
        <- adapters implement ports

LOOI protocol -> existing LOOI deterministic safety path -> Android BLE platform
```

Pure contracts import no Android, Compose, Bluetooth, database, vendor SDK, or AI SDK. Platform and vendor adapters depend inward. Provider-specific DTOs never cross adapter boundaries.

## Existing repository mapping

| Current area | V2 interpretation | Migration treatment |
|---|---|---|
| `core/RobotContracts.kt` | Proven semantic body action seed | Preserve; later expose through body capabilities |
| `core/authority` | Deterministic physical authority | Preserve unchanged for LOOI; general approval is a separate layer |
| `safety` | LOOI/local physical safety implementation | Preserve authoritative path; do not generalize into service permissions |
| `body/looi` | Reference Body Adapter #1 internals | Keep vendor/protocol-specific forever |
| `platform/transport/ble` | Android BLE connection implementation | Wrap behind a future connection-provider port; do not move now |
| `platform/service` | Android ownership of active LOOI session | Preserve; later compose through provider runtime facade |
| `feature/manualcontrol` | Reference acceptance UI and controller | Preserve for regression; later route through capability use cases |
| `protocol/fixtures` | GATT research evidence tooling | Keep protocol-specific; not a universal manifest format |
| planned AI/memory docs | Useful policies | Rebase on capability providers and Hugo identity |

## Runtime lifecycle

```text
Discover -> Identify -> Classify provider family -> Find manifest/adapter
-> Establish trust -> Request authorization -> Connect
-> Negotiate capabilities -> Verify compatibility -> Calibrate if required
-> Register capabilities -> Operate -> Observe outcomes -> Disconnect
```

Each step has typed failure and cancellation. Unknown identity, missing adapter, incompatible version, missing permission, failed calibration, or stale trust fails closed. Reconnect does not replay actions, grants, or leases.

## Active body

`ActiveBodySelection` is an explicit context record containing provider instance, selection reason, capability snapshot, generation, and expiry. It does not transfer identity or authorization. Multiple providers can be connected without all being active for motion.

## Safety invariant

```text
Intent -> CapabilityRequest -> Authorization -> body/device policy
-> LOOI safety controller when provider is LOOI -> adapter -> hardware
```

No universal abstraction may bypass the LOOI safety controller. Future certified devices may reject Hugo requests through their own local controller. Hugo treats rejection and unknown physical outcome honestly.

## Deployment shape

The first host remains Android. Contracts must be Kotlin/JVM-friendly and platform-neutral so future desktop, mini-PC, robot-host, or service processes can implement adapters. This assignment does not split Gradle modules; module extraction follows dependency evidence.

The accepted but unimplemented multi-host direction is owned by the `HUGO Home Core and multi-host runtime` entry in [HUGO Future Concepts](HUGO_FUTURE_CONCEPTS.md#19-hugo-home-core-and-multi-host-runtime). Host authority, synchronization, failover, and split-brain behavior require later architecture/backlog work.

## Migration invariant

At every migration checkpoint, the same T032 manual controls, command bounds, sensor gates, E-stop behavior, GATT profile, and real-LOOI protocol must remain compile-compatible and behaviorally unchanged. Runtime-path changes require automated regression and eventual physical LOOI re-verification.
