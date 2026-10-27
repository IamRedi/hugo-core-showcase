# H025 Adapter Registry Domain

## Record

- Task: `H025 - Adapter registry domain`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H024 commit `8aa1cb56c8cc06d0bbaaa2d742874d2c0e03e51a`
- Runtime impact: none; the registry is an in-memory knowledge index and is not wired to provider runtime
- Hardware claim: none

## Domain and authority boundary

H025 adds the pure `adapter/registry` record, snapshot, mutation, query, and in-memory registry
contracts. An entry wraps one validated H012 `AdapterDescriptor` and indexes its identity, version,
digest, provenance, manifest-family/schema claims, capabilities, runtime range, declared connection
methods, and extension schemas.

Registry presence means only that HUGO knows a definition:

```text
KNOWN_ADAPTER != INSTALLED != INTEGRITY_VERIFIED != TRUSTED != ENABLED
KNOWN_ADAPTER != LOADED != AVAILABLE != AUTHORIZED != EXECUTING
```

The registry contains no package filesystem state, installer, downloader, signature verifier,
trust/permission record, enablement flag, class loader, connection provider, provider registry,
capability dispatcher, or runtime availability signal.

## Record model

`AdapterRegistryRecord` contains:

- the H012 descriptor and typed `(AdapterId, AdapterVersion)` key;
- an opaque descriptor evidence reference;
- an optional opaque artifact/package reference;
- the descriptor's package digest and provenance;
- `DEFINITION`, `PACKAGE_REFERENCE`, or `DEVELOPMENT_CANDIDATE` knowledge state;
- exact H013 connection-method references; and
- exact extension ID/schema-version claims.

The knowledge-state enum describes which kind of metadata was indexed. It does not describe an
installed, enabled, verified, trusted, loaded, or executable lifecycle state. H012 provenance is
retained without ranking built-in, vendor, HUGO-maintained, local, Developer Mode, or installable
sources. The indexed digest is an expectation, not evidence that bytes were retrieved or verified.

Records defensively copy and canonically order their connection and extension collections.
Duplicate connection references and duplicate extension identities are rejected at construction.
The H012 descriptor remains the canonical source for family/schema/capability/runtime and package
digest declarations rather than parallel registry-owned identity semantics.

## Revision and coexistence rules

`AdapterRegistryRevision` starts at zero. Every successful register, explicit replacement, or
removal increments it once. Mutations require the caller's expected revision; stale requests return
`StaleRevision` without changing state.

The key is the exact H012 adapter ID and semantic version. Multiple versions of one adapter ID
coexist. Registration never upgrades, downgrades, or chooses a version. For an existing exact key:

- byte-for-byte equivalent canonical knowledge is `Duplicate` and does not change the revision;
- a different H012 package digest is `PACKAGE_DIGEST` conflict;
- a different descriptor with the same digest is `DESCRIPTOR` conflict; and
- differing registry metadata is `INDEX_METADATA` conflict.

Replacement is a separate explicit operation. It requires the exact existing key, expected current
digest, expected current revision, and a replacement with the same identity/version. Removal also
requires the expected digest and revision. These compare-and-set rules prevent silent replacement
or stale deletion. H025 defines no policy deciding whether a replacement or removal should be
approved.

## Snapshot and query semantics

`AdapterRegistrySnapshot` is an immutable, revisioned, canonically ordered view. Existing snapshots
remain unchanged after later registry mutations. Exact lookup and queries return deterministic
ascending `(AdapterId, AdapterVersion)` order.

The registry supports:

- exact ID/version lookup;
- all known versions for an adapter ID;
- provider-family query using H012 manifest-support declarations;
- exact H005 capability identity/version query; and
- resolved-manifest compatibility query.

Queries return all matches and never select a preferred adapter. No source precedence is applied.

## H024 compatibility reuse

`queryManifestCompatibility` creates an H024 `CompatibilityRequest` for every known record and calls
the existing `CompatibilityMatcher`. It supplies the H023 resolved manifest, HUGO runtime evidence,
record descriptor/provenance evidence, record connection/extension claims, and caller-provided
provider-version evidence.

`AdapterCompatibilityCandidateSet` retains every candidate and its complete H024 report. Its
derived `satisfyingCandidates` contains only `COMPATIBLE` and `PARTIALLY_COMPATIBLE` reports;
`unknownCandidates` remains distinct. An H024 `MISSING_ADAPTER` result can therefore lead to a
registry query asking whether any known definition satisfies the manifest, without installing,
activating, or selecting it. H025 introduces no second compatibility engine.

## Developer Mode and LOOI readiness

`DEVELOPMENT_CANDIDATE` and the existing H012 `DEVELOPER_MODE_CANDIDATE` provenance can represent a
future non-production adapter definition after an appropriate future workflow. They grant no
enablement or execution authority. The existing verified LOOI implementation could later be
described as a registry fixture, but H025 does not register or refactor it and contains no BLE,
protocol, vendor, Android, or LOOI runtime dependency.

## Completion evidence

`AdapterRegistryTest` covers valid registration, exact lookup, coexisting versions, exact duplicate,
descriptor and digest conflicts, provider-family and exact-capability queries, H024 manifest
compatibility queries, stale revisions, explicit replacement, removal compare-and-set behavior,
deterministic ordering, immutable/isolated snapshots, authority-state absence, and no concrete
vendor/transport/runtime coupling. The architecture guard includes `adapter/registry`.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H012/H023/H024/H025 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Scope boundary

H026 has not started. H025 adds no persistence, package installation/download, dynamic loading,
signature trust chain, source precedence, provider connection/registration, Developer Mode workflow,
dashboard, AI, memory, LOOI migration, runtime availability, authorization, or capability execution.
