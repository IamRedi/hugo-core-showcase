# H011 - Specialized manifests

## Status

**DONE** - 2026-08-25

## Contract shape

H011 adds independently versioned `BodyManifest`, `DeviceManifest`, and `ServiceManifest` claims.
Every specialization carries a `CommonManifestBinding` of provider definition ID plus canonical H010
digest. It must be validated against the decoded common document before use: the definition, digest,
provider family, and every grouped/evidence capability reference must match the common claim.

The specialization codec has a closed envelope (`kind`, schema version, digest, common binding,
explicit extension references, and type-specific details). Unknown ordinary fields fail closed;
unknown extension IDs remain opaque versioned/digested references. The specialized payload digest is
canonical and independent from the common-manifest digest.

## Body claims

Body Manifest adds body class, mobility class, semantic actuator/sensor capability groups, local
safety-controller claim, mount-host support, power/charging relationship, calibration and surface
constraint references, mounting-evidence capabilities, and an optional geometry-profile extension
reference. It stores neither motor/protocol data nor actual attachment/mounting state. Geometry is
owned by [World Model and Affordances](WORLD_MODEL_AND_AFFORDANCES.md); this manifest only refers to
a versioned/digested geometry profile claim.

## Device claims

Device Manifest adds device category, semantic sensing/control groups, placement-semantics
references, static power characteristic, and static local/remote control characteristic. It has no
robot mobility, mounting, or body-safety fields, so lights, TVs, cameras, sensors, and appliances
remain natural device providers.

## Service claims

Service Manifest adds service category, an authentication-scheme reference, authorization-scope
references, API compatibility range, usage-policy references, static reachability characteristic,
and semantic operation groups. It contains no credential, token, secret, endpoint, or account
identity. Home Assistant can later be a ServiceConnector/aggregator rather than a physical device.

## Authority and legacy boundaries

All fields are manifest claims, never runtime state, trust, permission grants, authorization, safety
approval, current connection, or physical feasibility. Legacy hardware/services need not publish a
HUGO format: their adapter may derive both common and specialized candidates from official evidence.
The first LOOI Body Manifest is deferred to H017; H011 changes no LOOI runtime or safety behavior.

## Evidence

`SpecializedProviderManifestTest` covers valid body/device/service specializations, family and
common-binding mismatch, duplicate groups, unsupported versions, geometry-reference-only behavior,
service no-secret behavior, strict round trips, unknown-field/extension rules, and public coupling
checks. H011 is JVM-only and makes no hardware claim.
