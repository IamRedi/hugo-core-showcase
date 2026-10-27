# H028 BLE Connection-Provider Wrapper

## Record

- Task: `H028 - BLE connection-provider wrapper`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H027 commit `ad006606938f9edc4e5ad06d24bec7e990d4a744`
- Runtime impact: none; the existing LOOI service/runtime path is not rewired
- Hardware claim: none; no physical behavior changed

## Wrapper API and composition

H028 adds `BleConnectionProvider` in `platform/connection/ble`. It implements the unchanged H013
`ConnectionProvider` contract for the canonical `io.hugo.connection/bluetooth-le@1.0` method and
uses H013 lifecycle types for availability, commands, correlation, channel sessions, failures, and
snapshots.

The wrapper depends on narrow project-owned platform ports for endpoint resolution, availability,
monotonic time, deadlines, session IDs, and BLE transport events. It contains no Android Bluetooth,
GATT callback, scanner, vendor, packet, service UUID, or LOOI protocol type.

`ExistingGattConnectionBridge` is a vocabulary-only adapter in `platform/transport/ble/gatt`. It
delegates to the already-verified `GattTransportAdapter`; it does not scan, open a GATT object,
discover services, filter generations, or close Android resources itself. Those responsibilities
remain in `AndroidGattTransportAdapter`.

`AndroidBleConnectionProviderFactory` is the Android composition boundary. It returns only H013
`ConnectionProvider`, constructs the existing GATT adapter, and shares the same
`BLUETOOTH_CONNECT` and Bluetooth-enabled checks between H013 availability and the delegate. Merely
creating the provider does not connect or acquire execution authority.

## H013-to-existing-GATT mapping

```text
H013 connect request
  -> opaque endpoint resolver (protected platform boundary)
  -> project-owned BLE transport target
  -> ExistingGattConnectionBridge
  -> existing GattTransportAdapter / AndroidGattTransportAdapter

existing Connecting    -> redacted diagnostic evidence
existing Connected     -> H013 ChannelEstablished
existing Ready         -> transport-ready diagnostic only
existing Disconnected  -> H013 Disconnected / Cancelled completion
existing Failure       -> typed H013 Failed + idempotent cleanup
```

`Connected` is the first callback that confirms a BLE channel, so it alone creates the H013
`CHANNEL_ESTABLISHED` session. `Ready` records that the existing adapter completed service
discovery, but does not imply a capability handshake, notification subscription, capability
readiness, provider registration, or execution readiness. No success is inferred before an
existing transport callback confirms it.

## Lifecycle, generation, and cleanup

The H013 `ConnectionGeneration` value is passed unchanged to the existing transport. Each observer
is bound to its H013 request, generation, and deterministic session ID. Both generation and active
attempt identity must match before a callback is accepted, so a callback retained by an older
transport attempt cannot advance a newer generic session.

H013's reducer remains the lifecycle authority. The wrapper schedules the request deadline against
an injected monotonic clock, cancels it on channel establishment, and maps expiry to H013 `TIMEOUT`.
Cancellation while connecting becomes terminal and closes the delegate. Cancellation after channel
establishment waits for the existing disconnect callback. Disconnect and cancellation are
idempotent, and the delegate cleanup call is guarded exactly once per attempt. There is no retry,
fallback endpoint, second transport session, or invented disconnect confirmation.

## Endpoint privacy and diagnostics

HUGO-facing requests and session metadata retain only `ConnectionEndpointReference`. A
`BleEndpointResolver` translates that opaque value to a platform-local transport handle inside the
BLE boundary. The handle can be a private address but is never returned through H013, lifecycle
snapshots, or diagnostics.

Diagnostics retain request/generation correlation, typed failure kind, operation category, and an
optional numeric platform status. Raw addresses, free-text Android failure messages, exceptions,
GATT objects, callbacks, credentials, and protocol payloads are omitted.

Existing typed failures map as follows:

- timeout -> `TIMEOUT`;
- permission/auth unavailable -> `PERMISSION_UNAVAILABLE`;
- disabled/missing transport, unresolved endpoint, or unsupported transport operation ->
  `UNAVAILABLE`;
- link loss -> `SESSION_LOST`;
- cancellation -> `CANCELLED`; and
- other GATT/initiation failures -> `TRANSPORT_FAILURE` or `UNKNOWN`.

The original typed GATT category, operation, and numeric status remain in redacted platform
diagnostic evidence where available.

## Authority and LOOI boundaries

```text
BLE CHANNEL ESTABLISHED != TRUSTED != AUTHORIZED
CHANNEL ESTABLISHED != MANIFEST VALID != CAPABILITY READY
CHANNEL ESTABLISHED != LOOI HANDSHAKE COMPLETE != ACTIVE BODY
```

H028 has no dependency on trust, provider registration, capability negotiation/execution, active
body, World Model, or LOOI protocol packages. It adds no handshake, subscription, telemetry,
movement, head/light control, or safety decision.

No existing production class instantiates `AndroidBleConnectionProviderFactory`; the verified LOOI
service continues to instantiate and use `AndroidGattTransportAdapter` through its existing owner.
No current production connection, GATT, scan, notification, protocol, control, or safety code was
modified. Therefore H028 does not require a real-LOOI hardware regression and makes no new hardware
claim.

## Completion evidence

`BleConnectionProviderTest` covers existing-adapter invocation, confirmed channel establishment,
service-ready separation, typed failure/status mapping, deadline timeout, cancellation before and
after connection, disconnect and repeated disconnect, cleanup exactly once, stale observer and
wrong-generation rejection, wrong request/session correlation, Bluetooth-disabled and permission
unavailable behavior, unavailable transport/endpoint paths, unsupported methods, deterministic
session/evidence output, endpoint redaction, and Android/GATT/LOOI protocol/authority absence.

The architecture dependency guard treats `platform/connection/ble` as a pure wrapper boundary.
Existing H013, GATT callback translation, LOOI connection reducer/controller, service connection
owner, H027, and architecture tests remain green.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant BLE/H013/H027/H028 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `./gradlew assembleDebug`: PASS
- `git diff --check`: PASS
- Real LOOI regression: not required; no production path or physical behavior changed

## Next boundary

H029 has not started. H028 adds no capability negotiation, trust/permission authorization,
provider registration, active-body selection, Developer Mode, UI, AI, memory, World Model runtime,
Home Assistant integration, LOOI runtime migration, or physical behavior.
