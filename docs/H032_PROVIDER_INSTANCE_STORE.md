# H032 Provider-Instance Store

## Record

- Task: `H032 - Provider-instance store`
- Status: `DONE`
- Completed: 2026-08-26
- Prerequisite: H031 commit `4bece2a2223d5f5d1b700fb763a1c9c5f60aaf0f`
- Runtime impact: none; the store is not composed into discovery, registration, trust, connection, or execution
- Hardware claim: none

## Domain boundary

H032 adds a pure `provider/storage` domain for durable identity and configuration knowledge about
specific H004 provider instances. It does not replace H015 `ProviderRegistry`, whose snapshots
describe current normalized runtime knowledge. The provider-instance store contains no lifecycle,
availability, connection, session, capability-execution, or active-body state.

`ProviderInstanceStore` exposes explicit create, lookup, alias update, association/reference update,
status update, delete, immutable snapshot, redacted diagnostics, and H022-facing projection
operations. Store and record revisions provide deterministic optimistic concurrency. Exact duplicate
instance IDs, duplicate opaque entries, stale revisions, conflicting protected identity bindings,
and contradictory definition/manifest/family bindings fail closed. The store never generates an
identity during lookup or update.

## Record model

Each versioned `ProviderInstanceRecord` contains only:

- one private H004 `ProviderInstanceId`, definition, family, and redacted display alias;
- one opaque local entry reference that is not provider identity;
- optional H010/H011 manifest schema/version/digest/specialization reference;
- optional H012 adapter ID/version reference;
- H014 `TrustRecordId` references, never copied trust state or grants;
- H031 `CalibrationProfileId` references, never calibration results or payloads;
- protected H022 identity-evidence references used for later exact matching;
- first-seen, last-seen, and optional last-successful-use wall-clock timestamps;
- record revision, provenance, and `KNOWN`, `DISABLED`, or `RETIRED` knowledge status.

Records and snapshots defensively copy and canonically order all collections. An alias is display
metadata only. Manifest, adapter, firmware-related manifest-version, trust-link, timestamp, and
status changes increment revisions without changing `ProviderInstanceId`. Two devices with the same
definition/model remain distinct because neither aliases nor definitions are identity evidence.

## H022 projection and diagnostics

`KnownProviderInstanceProjection` exposes the opaque entry reference, definition, family, redacted
alias, protected project-owned evidence references, and knowledge status. It deliberately omits
`ProviderInstanceId`. H022 may later consume this read-only index and correlate protected evidence;
H032 does not reimplement or bypass H022 matching.

Diagnostics use `RedactedProviderInstanceRecord`; `ProviderInstanceRecord.toString()` also delegates
to that projection. Private instance UUIDs and protected binding values are not printed. Public
aliases remain subject to H004 stable-address rejection and never become authoritative identity.

## Encryption and persistence

`ProviderInstanceStorePersistence` serializes the strict current `1.0` snapshot schema and sends the
entire plaintext, including private IDs and stable binding references, through the
`ProviderInstanceProtection` port. Only a `1.0` envelope containing algorithm, key version, random
nonce, and ciphertext reaches `ProtectedProviderInstanceStorage`.

The platform implementation uses Android Keystore non-exportable AES-256 keys and
`AES/GCM/NoPadding` with a 128-bit authentication tag, randomized 96-bit nonce, and fixed
store/schema associated data. The key alias is versioned. Android Keystore and cryptography types
remain in `platform/storage`; the domain sees typed protection results only.

`AtomicFileProviderInstanceStorage` writes an application-private pending file, flushes and fsyncs
it, and atomically replaces primary while preserving a same-directory backup. The maximum encrypted
document size is bounded. A failed replacement preserves the previous snapshot.

Private IDs, aliases, definitions, trust/calibration references, and timestamps all remain inside
authenticated ciphertext. Persisted state contains no password, token, access code, API key,
permission grant, approval, credential, transport address, BLE handle, protocol value, session, AI
memory, or device/body-memory payload.

## Restore, recovery, retirement, and deletion

Restore authenticates/decrypts before parsing. It then validates the exact schema, every typed
reference, definition/family binding, record ordering constraints, duplicate identities/entries,
and protected-evidence conflicts. Corrupt/truncated envelopes, GCM authentication failures,
unavailable or lost keys, unsupported schemas, and contradictory records return typed failures and
never an empty invented store.

A valid backup may be returned with exact evidence naming the rejected primary error. Committing
that recovery is a separate explicit atomic action. Reset is explicit: delete encrypted store files,
then use the platform key-reset operation only with operator-approved destructive recovery. Key loss
does not cause decrypt to create a replacement key or identity.

Retirement preserves the record and private identity with explicit `RETIRED` status. Deletion
removes all local identity knowledge. A later observation is therefore unknown and requires an
explicit create workflow with a caller-supplied identity; H032 never silently resurrects or invents
one. Alias and version metadata changes never merge identities.

## Authority, calibration, memory, and restart separation

```text
IDENTITY_CONTINUITY != AUTHORITY_CONTINUITY
KNOWN_INSTANCE != REGISTERED != TRUSTED != AUTHORIZED != CONNECTED != ACTIVE_BODY
TRUST_REFERENCE != TRUST_STATE
CALIBRATION_PROFILE_REFERENCE != CALIBRATION_PAYLOAD != DEVICE_MEMORY
```

Restart restores only durable H032 identity/configuration knowledge. Trust validity remains owned by
H014, runtime registration by H015, active-body selection by H016, calibration truth by H031/future
calibration storage, and general device/body memory by H062. Permission grants, approval consumption,
connections, runtime sessions, execution authority, and active-body state are not representable in
the H032 record or codec.

## Completion evidence

`ProviderInstanceStoreTest` covers create/store/load, encrypted restart identity continuity, exact
revision restore, alias/manifest/adapter updates, two same-definition devices, duplicate and
conflicting identity rejection, immutable/canonical snapshots, stale revisions, retirement,
deletion/rediscovery, trust/calibration reference-only boundaries, redacted diagnostics/H022
projection, encrypted-at-rest private identifiers, corrupt ciphertext, wrong/missing key, unsupported
schema, backup recovery, atomic-write preservation, absence of grants/runtime state, Android-free
domain APIs, and stable transport-identifier exclusion.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H004/H014/H022/H026/H031/H032 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `./gradlew assembleDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Next boundary

H033 is eligible and has not started. H032 adds no unknown-provider UX, discovery scanner, provider
registration, trust/permission persistence, credential storage, connection, automatic selection,
calibration storage, H062 memory, UI, AI, World Model runtime, LOOI runtime wiring, or physical behavior.
