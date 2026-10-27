# H016 - Active-body concept

## Status

**DONE** - 2026-08-25

## Model

H016 adds the pure `context/provider` embodiment model. `ActiveBodyState` is expiring runtime
context and intentionally contains no `HugoIdentity`, permission grant, safety approval, credential,
connection session, transport address, vendor payload, UI state, or execution mechanism. HUGO may
therefore have no selected body, remotely select a body, mount its current host on a body, switch
bodies, detach, or become unavailable without changing HUGO identity.

`BodyProviderReference` is an H004 identity restricted to the `BODY_ADAPTER` family. It can later be
resolved through H015, but H016 does not duplicate or mutate the Provider Registry. `EmbodimentHost`
uses an opaque local reference and existing host role. A host association is neither HUGO identity nor
host authority.

## State, selection, and evidence

Each body snapshot separately records discovery, proximity, connection, remote-control observation,
availability, mounting state, and immutable evidence. `MOUNTED` is not active selection; an explicit
expiring `ActiveBodySelection` is required. Conversely, a remotely controlled available body may be
selected remotely without mounting evidence. Selection has an optional opaque correlation reference
and explicitly grants no execution authority, permission, or safety approval.

The reducer handles observation, verified mounting evidence, explicit selection/switch, detach,
unavailability, deactivation, and expiry. Lower embodiment generations are ignored. Detach and
unavailability clear active selection. Expiry also clears it. H016 adds no automatic selection,
connection, provider discovery, planner, synchronization, World Model, LOOI integration, or runtime
control behavior.

`MountingEvidencePolicy` accepts only fresh, confidence-bounded, `HARDWARE_VERIFIED`
`MOUNTING_VERIFIED` evidence. Discovery, BLE/transport connection, RSSI/proximity, charging/power,
and remote control can remain independent evidence but never establish mounting. What constitutes a
verified mounting signal for LOOI remains a future hardware-evidence task; H016 maps no LOOI sensor
or packet semantics.

## Evidence

`ActiveBodyStateTest` covers empty context, remote active assignment without mounting, remote control
without embodiment, mounted-but-unselected state, stale evidence, stale generation, body switching,
detach, provider unavailability, selection expiry, unchanged HUGO identity, and the explicit absence
of execution authority. H016 is JVM-only and does not make a hardware claim.

No T001-T032 evidence, verified LOOI path, Android/BLE code, safety controller, or H001-H015 contract
is modified.
