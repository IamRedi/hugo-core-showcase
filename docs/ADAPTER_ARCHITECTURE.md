# Adapter and Connection Architecture

## Adapter roles

- `BodyAdapter`: maps body capabilities to an existing deterministic body control/safety path.
- `DeviceAdapter`: maps device capabilities to local transport, vendor SDK, or API operations.
- `ServiceConnector`: maps service capabilities to authenticated remote/local APIs and scopes.

Every adapter has identity, semantic version, supported provider/firmware/API ranges, required permissions, capability mappings, configuration schema, diagnostics, and evidence status.

## Registry

The Adapter Registry stores installed adapter descriptors, compatibility rules, origin, integrity/signature state, enabled state, verification level, and upgrade/rollback metadata. Registry lookup never downloads or enables code automatically.

Questions the registry must answer:

- Is this provider definition or instance known?
- Is a compatible adapter installed and enabled?
- Which adapter version supports this firmware/API?
- Which capabilities and limits can be negotiated?
- Which trust, permission, calibration, and verification requirements remain?

## Connection providers

A provider-neutral connection port models discovery observations, endpoint reference, connect/disconnect lifecycle, generation, cancellation, health, and typed errors. Implementations may later cover BLE, Wi-Fi/LAN, HTTP, WebSocket, Matter, MQTT, USB, Serial, ROS/ROS2, OBD-II, vendor SDKs, or future automotive APIs.

This architecture does not force packet operations into one universal transport API. BLE GATT operations remain inside the LOOI/Android BLE implementation. HTTP request details remain inside service connectors.

## Lifecycle ownership

Connection sessions are generation-owned. Late events are discarded. Disconnect revokes session registrations and action authority. Reconnect re-identifies, reauthorizes as policy requires, renegotiates, and never replays actions.

## LOOI incremental migration

1. Preserve `body/looi`, `platform/transport/ble`, `ConnectedDeviceService`, safety, and T032 UI.
2. Define universal ports in new Android-free packages.
3. Add a LOOI manifest and capability mapping specification.
4. Add a facade that delegates to the existing service/session path.
5. Register negotiated LOOI capabilities without exposing GATT types.
6. Prove automated regression, then repeat physical T032 checks for runtime-path changes.

No package move or raw protocol rewrite is required for V2 acceptance.

## Unknown-provider rule

Unknown providers can produce redacted passive discovery evidence where permitted. Runtime control, exploratory writes, adapter generation, or guessed protocols are prohibited. Missing adapters create a structured development need for Developer Mode.
