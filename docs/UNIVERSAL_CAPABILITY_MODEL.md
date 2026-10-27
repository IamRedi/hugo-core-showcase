# Universal Capability Model

## Purpose

Capabilities are semantic, versioned operations or observations exposed by an authorized provider. They answer what can be requested, not how a vendor implements it.

## Core value objects

- `CapabilityId`: stable namespace and name, for example `body.move`, `body.stop`, `device.capture_image`, `service.calendar.read`.
- `CapabilityVersion`: contract major/minor version; major changes are incompatible by default.
- `CapabilityDescriptor`: human label, provider family, side-effect class, input/output schema references, limits, evidence level, and availability conditions.
- `CapabilityInstanceId`: registration-scoped identity for one provider instance and negotiated session.
- `CapabilityRequest`: request ID, capability/version, provider target or selection policy, actor, monotonic deadline, parameters, and approval context.
- `CapabilityResult`: accepted/rejected/started/completed/cancelled/failed/unknown outcome plus observations and audit correlation.
- `CapabilityObservation`: timestamped, provenance-bearing evidence; never implicit success.

## Side-effect classes

| Class | Example | Default posture |
|---|---|---|
| Observe | battery, calendar read, camera metadata | Scoped authorization |
| Act | move, light, send draft, play music | Explicit action grant; physical actions stricter |
| Modify | configuration, account scope, adapter install | Strong confirmation and audit |

Side-effect class is not the complete policy. Each capability may require always/session/one-time/every-time approval or be prohibited.

## Capability families

- Body: `move`, `stop`, `look_at`, `report_battery`, `observe_contact`, `control_light`.
- Device: `capture_image`, `display`, `observe_temperature`, `control_light`.
- Service: `read_email`, `draft_reply`, `schedule_event`, `play_music`, `query_repository`.

The same semantic name may have provider-specific limits. A descriptor never exposes FED0, GATT, GPIO, OBD PID, MQTT topic, or vendor request payload.

## Typed payload rule

High-risk capabilities use project-owned typed parameters and validators. Extensible schema references may support service data, but a free-form map cannot be the physical-control boundary. Unknown fields and unsupported contract versions fail closed.

## Registration

A provider registers only capabilities that were negotiated and are currently available. Registration includes provider instance, adapter version, manifest digest, trust state, permission requirements, calibration state, connection generation, limits, and evidence. Registry presence does not itself authorize execution.

## Selection and execution

1. Resolve an exact capability version.
2. Filter providers by current availability and compatibility.
3. Apply trust, permission, privacy, and approval policy.
4. For physical actions, acquire deterministic local authority and evaluate provider safety.
5. Execute through the selected provider.
6. Record observed result separately from requested or transport-accepted state.

For physical tasks, capability compatibility is only one input. Future selection also consumes the evidence-bearing feasibility assessment defined by [World Model, Spatial Reasoning, and Affordances](WORLD_MODEL_AND_AFFORDANCES.md); an affordance never authorizes execution.

## Failure semantics

Required failures include unknown capability, incompatible version, provider unavailable, adapter missing, trust insufficient, permission denied, approval required/denied, calibration required, safety rejected, timeout, cancelled, transport failure, and outcome unknown.

## LOOI mapping

LOOI profile-A internals map to body capabilities only inside the LOOI adapter. Existing `RobotAction.Drive`, `Stop`, `SetHead`, and `SetLight` remain the deterministic inner API during migration. A compatibility facade translates universal requests into that existing path without changing encoder or safety semantics.
