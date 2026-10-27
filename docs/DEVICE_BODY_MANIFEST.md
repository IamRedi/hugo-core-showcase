# Provider, Device, Body, and Service Manifests

## Principle

Manifests describe claims and compatibility metadata. They do not prove identity, install code, establish trust, grant permission, or authorize control.

## Manifest family

- `ProviderManifest`: common envelope for every provider.
- `BodyManifest`: embodied sensors, actuators, geometry class, calibration, and safety-controller requirements.
- `DeviceManifest`: device functions, local/remote connection methods, and device policy.
- `ServiceManifest`: API/auth/scopes, data classes, rate/retention limits, and service compatibility.

`BodyManifest`, `DeviceManifest`, and `ServiceManifest` reference a common provider envelope rather than inheriting physical semantics from one another.

## Common fields

- manifest schema version and canonical digest;
- provider definition ID, provider family, display name, and optional product metadata;
- required adapter reference and version range;
- semantic capability-reference claims;
- semantic connection-method and policy-requirement references;
- bounded compatibility constraints;
- explicit versioned/digested specialization references;
- provenance source, issuer, source reference, and evidence level.

The exact common contract and its exclusions are recorded by
[H010 - Common manifest schema](H010_COMMON_MANIFEST_SCHEMA.md). Authentication scopes, body
geometry, calibration, device policy, service rate/retention rules, protocol/API configuration,
and other family-specific details are not common fields.

Stable private device-instance identifiers are not required in distributable manifests. Runtime instance binding is stored in protected local configuration.

## Specialized fields

### Body manifest

Actuators, sensors, stop semantics, local safety authority, operational envelope, calibration profile, physical setup constraints, and hardware verification state.

The implemented H011 specialization adds only semantic body class/mobility, capability groups,
local-safety claim, mount support, power relationship, calibration/surface references, and geometry
profile reference. Actual mounted/attached state remains runtime evidence.

Geometry, footprint, viewpoint, turning, payload, and movement-surface claims use the canonical evidence and affordance boundaries in [World Model, Spatial Reasoning, and Affordances](WORLD_MODEL_AND_AFFORDANCES.md). Manifest claims are inputs, not automatically verified world facts.

### Device manifest

Device class, commands/observations, connection locality, data sensitivity, availability, firmware support, and configuration operations.

H011 keeps Device Manifest independent of robot semantics through category, semantic capability
groups, placement references, static power characteristic, and control locality.

### Service manifest

Authentication method, account tenancy, API version, required scopes, read/write operations, webhook/stream behavior, quotas, retention, and regional/privacy constraints.

H011 represents authentication schemes, authorization-scope references, API compatibility,
usage-policy references, reachability, and operation groups. Credentials, tokens, secrets, and
actual account identity are explicitly excluded.

## Source and resolution

A manifest may come from the project, adapter package, vendor, administrator, or local user configuration. Existing hardware is not expected to emit HUGO manifests. An adapter may translate official SDK/API/protocol evidence into a manifest candidate.

Resolution order is explicit and conflict-bearing. A newer or vendor-signed manifest does not silently override local safety constraints, user-denied scopes, or verified adapter limits.

## Validation

- Unknown schema major version is rejected.
- Duplicate capability IDs with conflicting schemas are rejected.
- Capability limits must be finite and internally consistent.
- Adapter and firmware/API compatibility must be deterministically resolvable.
- Missing safety/auth/calibration requirements fail closed where applicable.
- Signature/provenance status is visible and cannot be inferred from transport identity.

## LOOI

The first LOOI manifest is project-maintained evidence derived from T001-T032. It references the verified profile and adapter but never exports raw private device IDs. Existing GATT fixtures remain protocol evidence, not universal manifests.
