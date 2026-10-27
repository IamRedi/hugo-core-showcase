# H010 - Common manifest schema

## Status

**DONE** - 2026-08-25

## Small common envelope

The H010 common manifest is a versioned, provenance-bearing claim envelope shared by future body,
device, and service manifests. It contains only:

- schema version and canonical SHA-256 payload digest;
- provider definition ID, provider family, display name, and optional product metadata;
- one manifest-side adapter identity/version-range requirement;
- sorted semantic `CapabilityReference` claims;
- semantic connection-method references without endpoint or protocol configuration;
- typed authentication, permission, safety, risk, lifecycle, and availability requirement references;
- bounded firmware/software/API compatibility ranges;
- explicit extension references carrying their own schema version and digest;
- source, issuer, source reference, and evidence-level provenance.

The manifest deliberately references capabilities rather than copying H006
`CapabilityDescriptor`. A manifest claim and the adapter-provided descriptor must later agree during
compatibility/negotiation before registration. This avoids two common definitions of capability
meaning.

## Version and extension behavior

Schema `1.x` shares a closed common field set. Major versions other than `1` fail closed. Unknown
ordinary fields fail closed at every parsed object. Future information crosses the common boundary
only through explicit extension references; H010 preserves their ID, schema version, and digest but
does not parse or trust their payload. A future minor version is accepted only when it uses the
known closed envelope and explicit extension references.

The codec emits a canonical field/list order and calculates `sha256` over the canonical payload
excluding the digest field. Decode reconstructs the validated canonical model and rejects digest
mismatch. Digests provide integrity comparison, not issuer trust or signature verification.

## Boundaries and specialization

The common schema contains no provider-instance ID, credentials, scopes, endpoint/address, raw
transport or protocol configuration, connection state, current availability, calibration,
geometry, sensor/actuator detail, rate limits, retention policy, or vendor payload. H011 may define
separate body, device, and service extension documents that reference this common envelope.

Geometry/footprint/viewpoint/clearance extensions must remain claims for the canonical
[World Model and Affordances](WORLD_MODEL_AND_AFFORDANCES.md); they are never automatically verified
world facts or physical feasibility decisions.

The supported legacy path remains:

```text
Vendor API/SDK/protocol -> Adapter -> HUGO manifest claim
```

The common schema supports later discover/identify/adapter/manifest/compatibility/capability/
authorization/registration composition, but H010 performs none of those runtime steps.

## Authority boundary

A valid manifest is not identity proof, trust, authorization, current availability, connection
state, physical feasibility, registry presence, adapter installation, or permission to execute.
Every claim still requires provenance, adapter compatibility, trust/permission/safety evaluation,
negotiation, and runtime availability before use.

## Evidence

The project-owned Kotlin model and strict codec live in `manifest/domain`; the matching
machine-readable schema is `schemas/hugo-common-provider-manifest-1.0.schema.json`.
`CommonProviderManifestTest` covers valid/strict round-trip, malformed input, unsupported major,
provider identity/family, invalid and duplicate capability claims, invalid/conflicting ranges,
unknown-field/extension rules, digest tampering, schema loading, and forbidden coupling. H010 is
JVM-only and makes no runtime or hardware claim.
