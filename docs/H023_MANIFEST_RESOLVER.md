# H023 Manifest Resolver

## Record

- Task: `H023 - Manifest resolver`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H022 commit `54061b6ba427e0f84c1cee147a3211ca56001c9b`
- Runtime impact: none; the pure resolver is not wired to discovery or provider runtime
- Hardware claim: none

## Resolver model and boundary

H023 adds the pure `manifest/resolution` candidate, provenance, request, result, and resolver
contracts. The resolver consumes only a completed H022 result, bounded local candidate documents,
and optional validated H012 adapter descriptors. It performs no lookup or download.

The separation remains strict:

```text
identity match != manifest validity != adapter availability
manifest compatibility != trust != authorization != runtime availability
manifest compatibility != connection != registration != execution != physical feasibility
```

Only H022 `MATCHED_KNOWN_PROVIDER` and `NEW_PROVIDER_CANDIDATE` results may resolve a manifest.
An ambiguous identity remains `AMBIGUOUS`; insufficient, stale, conflicting, or rejected identity
input is rejected. A known match binds the manifest to the private H004 configured-instance ID for
later protected use. A new candidate binds only the definition and optional family and never invents
a `ProviderInstanceId`. Diagnostic projections reveal only whether a known instance was referenced.

## Candidate source and provenance

`ManifestCandidateProvenance` retains an acquisition source and bounded opaque catalog reference.
The source vocabulary is `BUILT_IN_HUGO`, `OFFICIAL_VENDOR`, `HUGO_MAINTAINED`,
`LOCAL_USER_APPROVED`, `DEVELOPER_MODE_CANDIDATE`, and `ADAPTER_GENERATED`. This records where a
candidate came from; it is not issuer trust, signature proof, permission, installation approval, or
execution authority.

The resolver checks acquisition provenance against the embedded H010 provenance class. Built-in and
HUGO-maintained candidates require project provenance, official-vendor candidates require vendor
provenance, local approved candidates require administrator/local-configuration provenance,
Developer Mode candidates require local-configuration provenance, and adapter-generated candidates
require adapter-package provenance. Both layers remain present in the resolved result.

H023 adds no network source, downloader, package registry, marketplace, adapter generator, or
Developer Mode execution path.

## Common and specialized validation

Candidate text is decoded only through the accepted strict H010/H011 codecs. This reuses their
closed-field behavior, supported schema major, canonical digest verification, provider definition,
provider family, capability reference/version parsing, common binding, specialized digest, and
specialized capability-subset validation.

Every resolved definition must contain the specialization matching its common family:

- `BODY_ADAPTER` -> `BodyManifest`
- `DEVICE_ADAPTER` -> `DeviceManifest`
- `SERVICE_CONNECTOR` -> `ServiceManifest`

A missing specialization is `NOT_FOUND`; the wrong specialization is
`INCOMPATIBLE_PROVIDER_FAMILY`; common/specialized/binding digest failures are `DIGEST_MISMATCH`;
unsupported schema majors are `UNSUPPORTED_SCHEMA`; and specialized capability claims absent from
the common manifest are `CONFLICTING_MANIFESTS`. No specialization is inferred or substituted.

## Identity and adapter compatibility

The common provider definition must exactly equal the H022 definition. A known provider must also
match its H004 family. For a new definition whose H022 family remains unknown, the unique validated
manifest may establish the definition's declared family without creating an instance identity.

Adapter descriptors remain optional candidate input. When none matches the required H010 adapter
reference, manifest resolution may still be `RESOLVED` with `NO_MATCHING_DESCRIPTOR`; this preserves
the distinction between a valid manifest and adapter availability. When matching descriptors are
supplied, the existing H012 matcher checks adapter ID, inclusive/exclusive version range, provider
family, common schema, specialization kind/schema, H011 binding, and every exact H005 capability
reference. If none is compatible, the outcome is `INCOMPATIBLE_ADAPTER` with a typed family,
schema, specialization, version, or capability-reference reason. Compatible descriptors are
reported as candidates, never selected, installed, loaded, or treated as available.

This keeps the later flow possible:

```text
discover -> identify -> resolve manifest -> determine adapter compatibility
         -> later trust/authorize/connect/register
```

The LOOI definition can later use the H017 semantic capability mapping and H018 facade through these
generic definition/capability/adapter contracts. H023 contains no LOOI, BLE, GATT, FED, packet, or
transport knowledge and performs no LOOI runtime wiring.

## Ambiguity, conflicts, and deterministic behavior

Exact equivalent valid candidates with distinct candidate/provenance identities remain
`AMBIGUOUS`; H023 has no precedence policy that silently prefers one source. Candidates for the
same identity with different provider, adapter, capability, compatibility, extension, or
specialized claims are `CONFLICTING_MANIFESTS`. Duplicate candidate IDs are rejected.

Candidate IDs, adapter identities/versions, compatible adapter lists, and reported traces are
canonically ordered. Failure selection uses a fixed fail-closed precedence and canonical candidate
ID. Resolution is therefore independent of input ordering.

Outcomes are `RESOLVED`, `NOT_FOUND`, `AMBIGUOUS`, `IDENTITY_MISMATCH`, `DIGEST_MISMATCH`,
`UNSUPPORTED_SCHEMA`, `INCOMPATIBLE_PROVIDER_FAMILY`, `INCOMPATIBLE_ADAPTER`,
`CONFLICTING_MANIFESTS`, and `REJECTED`.

## Completion evidence

`ManifestResolverTest` covers exact known resolution, new-definition resolution without instance
creation, missing manifests, duplicate equivalent candidates, conflicting manifests, definition
mismatch, wrong provider family, wrong specialization, common and specialized digest mismatch,
unsupported schema, provenance mismatch, adapter-version incompatibility, capability-version
incompatibility, order determinism, absence of trust/permission/connection/execution implications,
no registry mutation, and no concrete vendor/transport/platform coupling. The H019 architecture
guard now includes `manifest/resolution`.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H010/H011/H012/H022/H023 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Scope boundary

H024 has not started. H023 adds no adapter installation, network manifest discovery, provider
registration, trust/permissions, connection, capability execution, UI, AI, memory, World Model
runtime, Home Assistant, LOOI runtime rewiring, Android/BLE behavior, or physical-provider behavior.
