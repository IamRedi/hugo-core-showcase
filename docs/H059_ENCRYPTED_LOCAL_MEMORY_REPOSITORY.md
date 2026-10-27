# H059 Encrypted Local Memory Repository

## Record

- Task: `H059 - Encrypted local repository`
- Status: `DONE`; JVM/instrumented levels complete on JVM; Android Keystore instrumented run
  implemented and pending device availability (see Device evidence)
- Completed: 2026-09-04
- Prerequisites: H058 common memory envelope
- Runtime impact: none in production paths; H059 is the typed encrypted persistence boundary for
  H058 envelopes plus isolated Android platform adapters, unwired by any runtime caller
- Hardware claim: none claimed; instrumented Keystore/file verification is prepared but the
  authorized Samsung `SM-S928B` was not reachable over ADB on 2026-09-04

## Room/SQLite decision (required by the task row)

**Decision: Room/SQLite and SQLCipher are not adopted.** The repository's governed
persistence/security stack â€” established by H032 (provider instance store) and reused by H052
(gateway tokens) and H041 (service credentials) â€” is a pure typed storage port over encrypted
documents protected by the Android Keystore. The non-exportable encryption keys use
AES-256-GCM (`AES/GCM/NoPadding`) with 128-bit authentication tags and randomized 96-bit nonces, schema-bound associated data, versioned key
aliases, and atomic same-directory file replacement. H059 applies exactly this governed
architecture to memory records. Rationale: (1) the constraint "no plaintext backup/log" and
"key loss explicit" map one-to-one onto the proven H032 properties; (2) H058 envelopes are
small, bounded, canonical JSON documents addressed by immutable id â€” a key/value document store
is the natural shape and avoids an SQL schema layer that H060-H067 have not yet justified;
(3) adopting Room + SQLCipher would introduce two new dependencies and a second key-management
architecture in one task, which the architecture rules require proving first. Re-evaluation
criteria for a later task: query patterns from H065 bounded retrieval that require indexed
predicates over large record populations. Until then SQLCipher/Room remain compatible future
adapters behind the same ports: the pure boundary would keep its ports and swap only the platform
slot implementation. This decision is recorded as the migration report required by the task row;
no data migration exists yet (schema 1.0 is the first version) and future migrations change the
store schema marker and associated data together, with old documents rejected fail-closed until
an explicit governed migration lands.

## Storage architecture

`memory/storage` (pure, Android-free) defines:

- `MemoryRecordSlots` â€” per-record atomic ciphertext slots: `read`, `writeAtomically`,
  `storedRecordIds(limit)` (stable ascending order, bounded), and `reset()` (explicit destructive
  purge). Slot names derive deterministically from the canonical `MemoryRecordId`.
- `MemoryRecordProtection` â€” authenticated-encryption port over
  `ProtectedMemoryRecordPayload(algorithm, keyVersion, nonce, ciphertext)` with
  `MemoryStoreKeyVersion`; errors distinguish invalid key version, unavailable key, lost key,
  encryption failure, authentication failure, and decryption failure.
- `MemoryEnvelopeStore` â€” the authoritative repository. `persist` encodes the envelope with the
  strict H058 canonical codec, encrypts with store/schema-bound associated data
  (`hugo.memory-record-store/envelope@1`), and writes the slot atomically; `load` reads, decrypts,
  decodes strictly, and verifies the slot/document record-id binding; `storedRecordIds` lists
  bounded; `resetForLostKey` is the explicit purge. All operations are `synchronized`, so
  concurrent access is deterministic and no partial write is ever observable as success.

Platform `platform/storage` (not a V2 boundary; depends inward):

- `AtomicFileMemoryRecordSlots` â€” one file per record under an application-private directory,
  written through a pending file and `Files.move` (atomic, with a same-directory fallback),
  bounded listing, explicit purge.
- `AndroidKeystoreMemoryRecordProtection` â€” Android Keystore AES-256-GCM with the versioned alias
  `hugo.memory-record-store.v<n>`, non-exportable 256-bit keys, randomized encryption, plus
  `deleteKeyForExplicitReset()`. This is the same key-management architecture as H032/H052 with a
  memory-specific alias â€” not a parallel secret system.

## Encryption and key boundary

Encryption fails closed end to end: there is no plaintext database, no unencrypted fallback, and
no code path that stores without successful encryption. Wrong or missing keys surface as
`KEY_UNAVAILABLE`/`KEY_LOST`; tampered or foreign-key ciphertext authenticates as failed
(`AUTHENTICATION_FAILED`); key material never leaves Keystore and never appears in logs, traces,
documents, or exceptions. Plaintext buffers are zero-filled after use in the store; encoded
documents exist only inside the persist call. The store owns no user credentials. Key loss
recovery is the explicit `resetForLostKey()` purge (plus the platform alias deletion), never an
automatic downgrade.

## Repository semantics

- `persist` covers create, update, and tombstone persistence: the same record id always maps to
  the same slot, so persisting the H058 `tombstoneOf` envelope atomically replaces the active
  record, which can never silently reappear. No second deletion mechanism exists.
- Persistence never mutates the caller-owned envelope and never changes confidence, authority,
  privacy, validation state, allowed use, or scope: the stored H058 envelope is the canonical
  semantic object, compared by its canonical codec document in tests.
- `load` distinguishes `Missing` from typed failures: corrupt slot documents, malformed envelopes,
  unsupported H058 schema versions (`UNSUPPORTED_SCHEMA_VERSION`), tampered ciphertext
  (`AUTHENTICATION_FAILED`), lost/unavailable keys, oversize slots, and slot/document record-id
  mismatches are each explicit.
- Bounds: persisted plaintext â‰¤ 32 KiB (defensive; H058 bounds keep documents far smaller), slot
  documents â‰¤ 64 KiB, listing â‰¤ 256 ids per call with `INVALID_LIMIT` outside 1..256. No
  unbounded reads or whole-store loads exist.
- Associated data binds every ciphertext to the store schema, preventing cross-store or
  cross-version ciphertext replay.
- Derived indexes (embeddings, summaries, search) are not implemented and would be rebuildable
  from this store; none of them is authoritative.

## Privacy

No memory payload, provenance content, sensitive reference, key, or secret appears in logs or
diagnostics: the store adds no logging at all, slot documents carry only algorithm/key-version/
nonce/ciphertext, and envelopes redact themselves in `toString`. Persisting a memory grants no
model, gateway, or provider use and changes no authorization; the H058 privacy/allowed-use model
is preserved bit-for-bit through the canonical codec.

## Coverage and checks

`MemoryEnvelopeStoreTest` contributes 16 focused JVM tests with deterministic fake slots and a
fake authenticated-encryption fixture: faithful round trips for every governed scope (global
personal, body, device, provider instance) and every field group (provenance, lineage, privacy,
allowed use, validation state, disposition); tombstone persistence with the previous active state
unable to reappear and no content remnant in any slot; missing-versus-failed load distinction;
corrupt-record, tamper-authentication, and unsupported-schema typed failures; wrong-key isolation
with no plaintext exposure; key-loss failures and the separate explicit purge; failed-encryption
atomicity leaving the previous state intact; oversize/limit bounds; bounded stable listing;
deterministic 4Ã—24 concurrent persist/load across 6 threads with zero partial successes;
ciphertext-at-rest plaintext absence; associated-data binding; and slot/document record-id
mismatch.

`AndroidMemoryRecordStorageInstrumentedTest` (compiled, pending device execution) verifies on
real Android Keystore and real files: protection round trip, tamper and wrong-key fail-closed,
encrypted-at-rest slot files with no plaintext content, restart-equivalent reload, corruption
typed failure, explicit purge, bounded listing, and tombstone persistence.

Schema corpus reuse: H058's 17-document corpus continues to gate the codec the store persists
through; no duplicate corpus was added.

- focused H059 JVM tests: 16/16 PASS
- architecture dependency guard (now protecting `memory/storage`): PASS
- full `:app:testDebugUnitTest`: PASS â€” 962 tests across 120 suites, zero failures, errors, or
  skips
- `:app:lintDebug`: PASS â€” 0 errors, 13 warnings (unchanged count)
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: PASS
- documentation guards (First Life order, HUGO V2, T032 baseline evidence): PASS
- `git diff --check`: PASS
- Android instrumented verification: implemented; device unavailable on 2026-09-04 â€” recorded as
  pending, and no device result is claimed

## Contract amendment (H060, approved) â€” CURRENT AMENDMENT VERIFICATION

Everything above this section is HISTORICAL EVIDENCE as accepted at H059 completion; it is
preserved unmodified and must not be rewritten with later numbers.

The listing contract was amended additively for H060's episodic enumeration (approved
architecture decision, Option A): the port, store, and platform slot implementation now expose
`storedRecordIds(limit, afterId)` â€” with `afterId` set, only ids strictly greater than the
cursor are listed. The default `afterId = null` preserves the original from-the-start behavior
at every existing call site, the per-page bound (1..256) is unchanged and still applies during
continuation, ordering remains ascending and stable, and no bound, invariant, or guard was
weakened. The original 256-per-page ceiling was a listing ceiling, never a store-capacity
claim; continuation makes records beyond any prefix reachable without unbounded reads.

CURRENT AMENDMENT VERIFICATION (2026-09-04, after the amendment):

- focused H059 JVM tests: 19/19 PASS â€” the original 16 tests unchanged plus 3 new
  keyset-continuation tests (stable ordering; strict cursor semantics with no duplicate or
  skipped id, including across id gaps; per-page bound enforced during continuation)
- new `AtomicFileMemoryRecordSlotsPaginationTest`: 4/4 PASS on the real atomic-file slots
- full `:app:testDebugUnitTest`: 989 tests across 123 suites, zero failures, errors, or skips
- `lintDebug`: 0 errors, 13 warnings; `assembleDebug`: PASS; all documentation guards PASS

## Known accepted limitations and H060+ deferrals

- The device Keystore/file run is pending; JVM fake-crypto evidence is not device evidence.
- Record listing is id-ordered paging only; selection, ranking, and query semantics are H065.
- Session-scoped retention has no automatic sweeper; enforcement is H060 with virtual time.
- Validation/disposition transitions remain H061/H064; the store persists states, never decides
  them. Deletion propagation to derived artifacts is H066/H067 (no derived artifacts exist).
- No sync, multi-host replication, cloud, or Home Core behavior (explicit non-goals).
- A future Room/SQLCipher adapter remains possible behind the same ports if retrieval-volume
  evidence justifies it; the associated-data/schema marker discipline documented here is the
  migration hook.

## Next boundary

H060 is eligible and has not started. H059 adds no working/episodic session behavior, validation
policy, reconciliation, retrieval, summaries, UI, sync, or physical behavior.
