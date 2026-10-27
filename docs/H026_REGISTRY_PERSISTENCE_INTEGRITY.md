# H026 Registry Persistence and Integrity

## Record

- Task: `H026 - Registry persistence / integrity`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H025 commit `9b8dd7f1081c8d45a4b7174b0e5845f40fea4c1c`
- Runtime impact: none; persistence is not wired to adapter installation or provider runtime
- Hardware claim: none

## Persistence model

H026 adds the pure `adapter/storage` schema, codec, protected-storage port, persistence coordinator,
restore evidence, and same-directory atomic file implementation. It serializes an immutable H025
`AdapterRegistrySnapshot` and reconstructs an `AdapterRegistry` with the exact persisted revision;
it does not replay registrations or infer new records.

The persisted state remains registry knowledge only:

```text
KNOWN != INSTALLED != INTEGRITY_VERIFIED != TRUSTED != ENABLED
KNOWN != LOADED != AVAILABLE != AUTHORIZED
```

No persisted type contains installation, enablement, loading, availability, authorization, trust,
execution, or provider-runtime state.

## Schema and deterministic encoding

The current persistence schema is `1.0`. The canonical root contains only:

- `schemaVersion`;
- the non-negative H025 registry `revision`;
- canonically ordered adapter `records`; and
- a whole-snapshot `metadataDigest`.

Each record embeds the canonical H012 descriptor and the H025 descriptor/artifact references,
knowledge state, connection-method references, and extension-schema claims. H012 decoding rechecks
adapter ID, version, descriptor schema, package digest shape, provenance, family/schema support,
capabilities, and runtime range. H025 construction rechecks connection/extension duplicates and
canonical ordering. Exact `(AdapterId, AdapterVersion)` duplicates and conflicts are rejected.

The codec always re-encodes validated values before checking the metadata digest, so field ordering
cannot change restored semantics. Restore preserves exact versions, package digest metadata,
provenance, compatibility metadata, knowledge state, record ordering, and registry revision.

`RegistryPersistenceSchemaPolicy` classifies the current schema, older schemas requiring migration,
and unsupported newer schemas. H026 performs no speculative migration: every non-current schema
fails closed with its typed disposition. This is the dispatch boundary for future explicit,
tested migrations.

## Metadata integrity and failure behavior

The metadata digest is SHA-256 over the canonical schema/revision/records payload. It detects
accidental modification, partial content, or contradictory resealing mistakes. It is not a
signature, trust chain, credential, or proof of publisher authenticity.

Malformed, truncated, oversized, digest-mismatched, unsupported, contradictory, duplicate, or
otherwise invalid snapshots never produce an empty/default registry. Diagnostics contain typed
error categories and record indexes only; raw documents, paths, adapter payloads, credentials,
provider identifiers, and transport details are not echoed.

H026 deliberately does not salvage individual records from a corrupt snapshot. Partial recovery
could create a registry state that never existed. Recovery accepts only a separately stored backup
that passes the complete schema, record, duplicate/conflict, and digest validation pipeline.

The persisted H012 package digest is metadata integrity expectation only. A later installer/runtime
must independently hash and validate actual artifact bytes before loading or execution.

## Atomicity and recovery

`ProtectedAdapterRegistryStorage` separates serialization from application-private storage. Its
atomic write contract either commits the complete text or reports failure while retaining the
previous committed state.

`AtomicFileAdapterRegistryStorage` uses fixed filenames inside a caller-supplied application-private
directory, a bounded four-megabyte snapshot, a same-directory pending file, file-descriptor sync,
and atomic moves. Replacement uses a rollback slot until the new primary is committed, then retains
the prior primary as backup. An interrupted move can therefore be restored from rollback/backup.
Filesystems without atomic-move support fail closed instead of falling back to a non-atomic write.

Restore behavior is explicit:

- valid primary: `Restored` with schema/revision/digest evidence;
- missing primary and backup: `Absent`, leaving initialization policy to the caller;
- invalid primary plus valid backup: `RecoveredFromBackup`, retaining the rejected-primary error;
- no fully valid copy: `Failed`, never an invented registry.

`commitRecovery` explicitly rewrites the verified backup snapshot as primary while preserving the
valid backup during the repair operation. `reset` explicitly removes persistence slots and returns
a typed success/failure; corruption never triggers reset automatically.

## Security and scope boundary

The schema contains no credentials, tokens, provider secrets, private provider-instance identity,
raw vendor protocol data, or transport address. The file implementation has no Android, BLE, LOOI,
vendor, installer, downloader, signature, selection, connection, or execution dependency. The
caller is responsible for providing an application-private directory; H026 neither selects a path
nor exposes it in diagnostic results.

The only H025 change is an internal snapshot restore hook that copies the persisted records and
revision. It does not change registration, version coexistence, query, conflict, or authority
semantics. H024 compatibility reports are not persisted as authority and remain recomputable from
restored H012/H025 metadata.

## Completion evidence

`AdapterRegistryPersistenceTest` covers persist/restore, canonical ordering, revision continuation,
multiple versions, exact package-digest preservation, metadata corruption, truncation, verified
backup recovery and repair, unsupported/future schema, migration-required classification,
duplicate/conflicting records, failed atomic replacement preserving the prior snapshot, real file
primary/backup behavior, explicit reset, authority separation, and secret/transport-field absence.
The architecture guard includes `adapter/storage`.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H012/H024/H025/H026 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Next boundary

H027 has not started. H026 adds no adapter installation/download/loading, signature trust chain,
source precedence, automatic adapter selection, provider orchestration/execution, Developer Mode,
dashboard, AI, memory, LOOI runtime change, or physical behavior.
