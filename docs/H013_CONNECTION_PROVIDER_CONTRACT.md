# H013 - Connection-provider contract

## Status

**DONE** - 2026-08-25

## Contract

H013 adds the pure `ConnectionProvider` port in `connection/api`. An adapter uses it to request a
communication channel through `availabilitySnapshot`, `connect`, `cancel`, and `disconnect`.
`ConnectionMethodReference` is a versioned semantic method identity. `ConnectionEndpointReference`
is an opaque local reference, never a BLE address, URL, socket, credential, or vendor payload.

The port is distinct from `CapabilityProvider`, which exposes semantic capabilities, and from an
Adapter, which translates a concrete body, device, or service. BLE, HTTP, WebSocket, MQTT, Matter,
USB, serial, ROS, OBD, LAN, and vendor SDK implementations remain outside this pure boundary.

## Lifecycle and readiness

Every attempt has a `ConnectionRequestId` and strictly owned `ConnectionGeneration`; every channel
has a separate `ConnectionSessionId` retaining the initiating request correlation. The pure reducer
models `Idle`, `Connecting`, `Connected`, `Disconnecting`, `Disconnected`, `Cancelled`, and typed
`Failed` states. Stale/future/reused generations, correlation mismatch, duplicate disconnects, and
invalid transitions are deterministically ignored. Disconnect is idempotent.

`CHANNEL_ESTABLISHED` means only that a communication channel exists. It is not discovery, identity,
trust, authorization, safety approval, capability readiness, registration, active-body context, or
physical feasibility. H009 remains the provider lifecycle reducer; a later orchestrator may correlate
provider and connection generations without merging their semantics.

## Failure and scope boundaries

Typed failures cover unsupported/unavailable method, permission unavailable, rejection, timeout,
cancellation, transport failure, session loss, and unknown. Cancellation while connecting is terminal;
cancellation while connected requests disconnect. Late callbacks cannot revive a terminal attempt.

H013 deliberately has no wire codec because this is an in-process port, not a transport protocol or
multi-host IPC format. Its constructors provide strict validation for all public identifiers, timeout
ordering, availability, and command outcome consistency. A future IPC boundary may add a separately
versioned codec without changing the connection model.

H013 adds no transport implementation, BLE refactor, manifest change, registry, discovery, trust,
dashboard, AI, memory, Home Assistant, or LOOI migration. The verified LOOI BLE path is untouched.

## Evidence

`ConnectionProviderContractTest` covers valid contracts, deterministic generation/session handling,
cancellation, timeout, typed failures, stale callbacks, idempotent disconnect, unsupported method,
strict validation, and platform/vendor/LOOI-free public types. This is JVM-only and makes no hardware
claim.
