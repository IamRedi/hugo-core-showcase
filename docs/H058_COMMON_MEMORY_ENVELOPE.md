# H058 Common Memory Envelope

## Record

- Task: `H058 - Common memory envelope`
- Status: `DONE`
- Completed: 2026-09-04
- Prerequisites: H003 Hugo identity contract, H036 context observation contract, H057 model policy
  and M5 acceptance
- Runtime impact: none; H058 is a pure, unwired, storage-free domain contract
- Hardware claim: none; the task row requires no device verification

## What the envelope represents

`memory/domain` adds the provider-neutral, storage-independent common memory envelope required by
the memory architecture record model: immutable identity and schema version, Hugo ownership, the
authoritative layer/type taxonomy, the ADR-016 scope binding, bounded subject links, normalized
content, bounded provenance, confidence, trust state, lifecycle disposition, the two privacy axes
(sensitivity and allowed use), retention intent, supersession/conflict links, derived-data lineage,
and minimal tombstone audit. One strict canonical JSON codec (H003/H036 conventions) round-trips
the envelope; unknown fields, missing fields, wrong versions, and malformed documents fail closed.

```text
MEMORY ENVELOPE
!= VALIDATED FACT
!= AUTHORIZATION
!= SAFETY DECISION
!= CAPABILITY REQUEST
!= PERSISTED STORAGE
!= RETRIEVED CONTEXT
```

## Trust state and disposition are separate concepts

`MemoryValidationState` is trust epistemics only: `CANDIDATE`, `VALIDATED`, `DISPUTED`.
`MemoryDisposition` is lifecycle/deletion: `ACTIVE`, `SUPERSEDED`, `TOMBSTONED`. `EXPIRED` is
derived from retention against injected time and is never stored. The axes are orthogonal — a
candidate can be superseded before validation, a disputed record can be tombstoned. H058
represents these states and enforces only construction invariants; every transition between them
belongs to H061 (candidate lifecycle) and H064 (reconciliation).

## Model output is candidate only — at creation

`MemoryProvenance` carries a caller-declared `modelGenerated` flag alongside the five
authoritative origins (`user_explicit`, `observed`, `tool_result`, `conversation_summary`,
`model_inferred`). At creation, `modelGenerated` provenance must be `CANDIDATE`
(`ModelOutputMustStartCandidate`), and `MODEL_INFERRED` provenance is definitionally
model-generated (`ModelProvenanceMismatch` otherwise). The rule is creation-time only: the codec's
structural restore deliberately does not re-apply it, so a stored model-inferred record that
H061/H064 validation later marked `VALIDATED` round-trips with its original provenance intact —
model output may become validated through the authoritative validation paths; it never starts as
fact. This asymmetry is proven explicitly by paired tests. A `CONVERSATION_SUMMARY` is
candidate-at-creation only when its creator was model-generated; the origin name alone decides
nothing.

## Scope binding (ADR-016)

`MemoryScope` binds every record to exactly one ownership plane: `GlobalPersonal`, or
`BodyInstance`/`DeviceInstance`/`ProviderInstance` carrying the H004 opaque `ProviderInstanceId`
plus an optional bounded generic version token. The layer/scope matrix is fail-closed: working,
episodic, semantic, and personal layers accept only `GlobalPersonal`; device layer only
`DeviceInstance`; body layer only `BodyInstance`; strategy layer `GlobalPersonal` or
`ProviderInstance`. Body/device/provider-specific evidence therefore cannot be constructed as
universal Hugo memory, and personal facts cannot masquerade as instance evidence. Instance
identifiers are redacted in diagnostics.

## Privacy: sensitivity and allowed use

Two authoritative axes are kept separate. Sensitivity reuses the H036
`ContextPrivacyClassification`. Allowed use uses the memory architecture's own four classes
(`local_only`, `gateway_allowed`, `provider_allowed`, `never_model`) as `MemoryAllowedUse`, a
monotonic ladder. Fail-closed rule: `SENSITIVE`/`RESTRICTED` records can only be `LOCAL_ONLY` or
`NEVER_MODEL` — they can never gain gateway- or provider-facing use. The envelope is immutable, so
no transformation can relax a classification; relaxation would be a new record with new provenance
(H064 policy).

## Confidence, conflicts, lineage

Confidence is the reused H036 permille value and carries no truth, authorization, or confirmation
semantics. `conflictsWith` is an explicit bounded (≤8) self-free link list; conflicts are
represented, never resolved — resolution is H064 and confidence alone never picks truth.
`supersedes` is the correction edge (the successor holds the link; the replaced record carries
`SUPERSEDED`). `MemoryLineage.derivedFrom` (1..8, distinct, self-free) is the minimal derived-data
lineage: a summary or derived candidate retains its source record ids, and those ids survive
tombstoning — source references are never erased. `updatedAt` is representable for reconciliation
audit but H058 writes no transitions.

## Tombstone minimal audit

`MemoryEnvelope.tombstoneOf` is the only producer of `TOMBSTONED` (direct construction is
rejected). It drops personal/content payload — content, confidence, retention — and retains the
minimum identity/audit/linkage the architecture requires: ids, owner, scope, layer/type, subjects
(opaque keys), provenance tokens and flags, validation state (audit of what it was), privacy and
allowed use, lineage, supersession/conflict links, creation time, and the tombstone audit itself
(`tombstonedAt` + bounded actor; no reason text). Provenance tokens therefore survive deletion as
audit while provenance-attached payload does not.

## Bounds, time, and redaction

Subjects 1..4 distinct; conflicts ≤8; lineage 1..8; content via the reused H036
`ContextValue`/`ContextText` bounds; all tokens use the shared semantic-token pattern with fixed
length ceilings. Timestamps are caller-injected epoch seconds (creation, optional observation,
optional update, optional expiry, tombstone instant) — the domain reads no clock. Diagnostics
redact content, owner identity, instance identifiers, and tombstone actors, and expose only
non-personal ids, tokens, states, and counts. Marker properties state that the envelope grants no
authority, approves no safety decision, is not a fact, and authorizes no use.

## Coverage and checks

`MemoryDomainModelTest` contributes 17 focused tests: valid paths across layers/scopes/origins;
model-candidate creation enforcement and the MODEL_INFERRED definitional binding; conversation
summary neutrality; sensitive allowed-use restriction; the full layer/scope matrix; retention
class/expiry validation; subject, link, lineage, and update-time bounds; tombstone factory
strip/retain semantics exactly as specified; typed token/identifier rejection; redaction; and a
source-level absence test proving no Android, storage, clock, coroutine, platform, feature, LOOI,
retrieval, or gateway coupling.

`MemoryEnvelopeCodecTest` contributes 9 tests: canonical round trips for the personal,
model-candidate, body-scoped, and tombstoned representatives; the decode/create asymmetry (a
stored post-validated model-inferred document decodes with provenance intact while the identical
creation is rejected); unknown-field, missing-field, wrong-version, malformed-identifier,
truncated, and non-object document rejection; and the checked-in schema corpus.

Schema corpus (`app/src/test/resources/memory/domain/corpus/`): 9 valid documents — user-explicit
validated personal, model-inferred candidate with lineage/conflicts, body-instance and
device-instance records, sensitive never-model fixed-interval, model-generated summary candidate
with lineage, a disputed/superseded record paired with its superseding correction, and a
tombstoned minimal-audit record — and 8 invalid documents (unknown field, missing field, wrong
schema version, malformed id, oversized conflicts, duplicate subjects, tombstone with content,
invalid update ordering), each asserted in both directions.

- focused H058 JVM tests: 26/26 PASS
- architecture dependency guard (now protecting `memory/domain`): PASS
- full `:app:testDebugUnitTest`: PASS — 946 tests across 118 suites, zero failures, errors, or
  skips
- `:app:lintDebug`: PASS — 0 errors, 13 warnings (unchanged count)
- `:app:assembleDebug`: PASS
- documentation guards (First Life order, HUGO V2, T032 baseline evidence): PASS
- `git diff --check`: PASS
- Android-device verification: not required by the task row (`HW: none`) and not claimed

## Boundary statement

H058 adds no storage, database, file, encryption, retrieval, embedding, summarization, UI, sync,
memory write, clock read, provider type, or lifecycle transition logic. Repository ports, encrypted
persistence, working/episodic binding, semantic/personal validation policy, device/body scoping
policy, strategy envelopes, reconciliation, bounded retrieval, provenance summaries, and memory
control belong to H059-H067. The envelope feeds them as their common representation and grants no
authority of any kind.

## Next boundary

H059 is eligible and has not started. H058 adds no encrypted repository, Room/SQLite decision,
keys, migrations, working or episodic session binding, validation or reconciliation policy,
retrieval, summaries, UI, or physical behavior.
