# H060 Working and Episodic Memory

## Record

- Task: `H060 - Working and episodic memory`
- Status: `DONE`
- Completed: 2026-09-04
- Prerequisites: H058 common memory envelope, H059 encrypted local repository
- Runtime impact: none in production paths; H060 is an unwired pure session layer and a typed
  facade over the H059 store
- Hardware claim: none; the task row requires no device verification (`HW: none`)

## What H060 adds

Two pure packages implementing the governed working/episodic layer on top of the accepted H058
envelope and H059 encrypted store — memory behavior, not a persistence redesign:

- `memory/working` — `WorkingSessionMemory`: a bounded, session-scoped buffer of H058
  WORKING-layer envelopes owned by exactly one Hugo identity.
- `memory/episodic` — `EpisodicEventFactory` (typed creation of EPISODIC event envelopes) and
  `EpisodicRecordRepository` (an EPISODIC-disciplined facade over the H059
  `MemoryEnvelopeStore`).

Dependency direction: `memory/working` and `memory/episodic` → H058 `MemoryEnvelope` →
H059 `MemoryEnvelopeStore`. The store still holds no semantic meaning; the layers decide what a
record means.

## Working memory semantics

- Only WORKING-layer, ACTIVE, SESSION_SCOPED envelopes owned by the session identity are
  accepted; everything else is rejected typed (`WRONG_LAYER`, `NOT_ACTIVE`,
  `WRONG_RETENTION_CLASS`, `OWNER_MISMATCH`). Foreign-owner envelopes can never enter a session.
- Capacity is validated bounded policy (1..64). At capacity the oldest entry is evicted
  deterministically and surfaced in the result (`Remembered(evictedRecordId=…)`).
- `forget` discards one entry; `endSession` deterministically clears the session and reports the
  cleared count. Session bounds are therefore capacity + explicit session end — nothing else.
- Working memory is in-memory only: it holds no reference to the H059 store, so nothing in it is
  ever durable. Context is not auto-durable.
- A bounded, content-free `trace` records operations (REMEMBERED/EVICTED/FORGOT/PROMOTED/
  SESSION_ENDED with ids and counts) — the "record traces" evidence form. Content, owner, and
  identity values never appear.

## Durability boundary — context is not auto-durable

`promote(recordId, retention, updatedAt)` is the single governed durability decision. It
requires a non-session retention (`SESSION_SCOPED` promotion is rejected:
`INVALID_PROMOTION_RETENTION`) and rebuilds the envelope with the EPISODIC layer while
preserving record id, provenance, confidence, validation state, disposition, privacy, allowed
use, subjects, content, links, and lineage unchanged. A model-generated candidate therefore
stays a candidate through promotion — H058's "model output is candidate only" holds at the
durability boundary, enforced by the unchanged H058 factory. Promotion removes the entry from
the session and produces the envelope; **persistence remains the caller's separate explicit
step** through the H059 store. There is no automatic durability anywhere in H060.

## Episodic memory semantics

`EpisodicEventFactory.create` produces EPISODIC, globally-personal event envelopes with explicit
provenance (including optional observed-at), bounded subjects, normalized content, confidence,
validation state, the two privacy axes, deterministic retention (`FIXED_INTERVAL` expiry is
validated against the injected creation time), and optional lineage for derived events.
`SESSION_SCOPED` retention is forbidden (`SESSION_RETENTION_FORBIDDEN`) — episodic records are
durable-policy records. Derived events (for example model-generated ones) carry lineage and stay
candidates.

`EpisodicRecordRepository` persists and loads episodic records through the H059 store using its
approved keyset-pagination amendment: `record` rejects non-EPISODIC envelopes before touching
storage (`WRONG_LAYER`), `event` reports a stored non-episodic id as a typed failure instead of
returning a value, and `events(limit)` pages with the `afterId` cursor — collecting until the
requested episodic count is satisfied or the store is exhausted — so non-episodic records can
never consume the episodic result size and episodic events remain reachable past any prefix of
non-episodic records (verified with 300 non-episodic predecessors). Every page respects the
H059 per-page bound; corruption surfaces as typed storage failures; H058/H059 round-trip
behavior is byte-for-byte unchanged — verified by canonical codec equality through the full
promote→persist→load path.

## Deterministic expiry and provenance

All timestamps (creation, observation, update, expiry, promotion update) are caller-injected
epoch seconds; no clock exists in either package. `FIXED_INTERVAL` expiry windows are validated
at creation (`InvalidRetentionWindow` when not strictly after creation), so expiry evaluation is
a pure function of injected time. Provenance is explicit, complete, and never altered by
remembering, promoting, persisting, or loading.

## H019 guard

`memory/working` and `memory/episodic` are now protected pure boundaries (additive). Imports of
`memory.domain` and `memory.storage` are not forbidden prefixes; no allowlist entries were
needed. No existing boundary or rule changed.

## Coverage and checks

`WorkingSessionMemoryTest` contributes 10 focused tests: policy bounds; round-trip; the
WORKING/ACTIVE/SESSION_SCOPED acceptance filter; foreign-owner rejection; deterministic
oldest-eviction at capacity; `forget`/`endSession` cleanup; promotion semantics (EPISODIC layer,
preserved candidate state/provenance/scope/privacy, session removal, trace); model-generated
candidates staying candidates through promotion; promotion rejections (session retention,
unknown id); and trace boundedness/redaction.

`EpisodicRecordTest` contributes 10 focused tests: deterministic-expiry creation; expired-window
and session-retention rejections; derived events keeping lineage with candidate state;
persist/load/list through the real H059 store with an authenticating fake cipher; non-episodic
records rejected pre-store; missing/invalid-limit typing; the full
working→promotion→persistence→load durability path; and typed corruption surfacing.

Record-trace evidence (content-free, from the deterministic suite):

```text
REMEMBERED:<id>:size=n | EVICTED:<id> | FORGOT:<id> | PROMOTED:<id>:EPISODIC | SESSION_ENDED:cleared=n
```

- focused H060 JVM tests: 20/20 PASS (10 working + 10 episodic, including the mixed-store
  regression and the >256 non-episodic-prefix reachability regression)
- architecture dependency guard (now also protecting `memory/working`, `memory/episodic`): PASS
- full `:app:testDebugUnitTest`: PASS — 989 tests across 123 suites, zero failures, errors, or
  skips
- `:app:lintDebug`: PASS — 0 errors, 13 warnings (unchanged count)
- `:app:assembleDebug`: PASS
- documentation guards (First Life order, HUGO V2, T032 baseline evidence): PASS
- `git diff --check`: PASS
- Android-device verification: not required by the task row (`HW: none`) and not claimed

## Boundary statement

H060 adds no H061 semantic/personal/relationship memory, no validation/correction policy, no
reconciliation, no embeddings, vector or graph search, no ranking, no consolidation, no
summaries, no strategy learning, no sync or replication, no UI, and no retrieval semantics.
Working entries are never implicitly durable; episodic records are never implicitly validated;
and nothing in H060 grants authority of any kind.

## Next boundary

H061 is eligible and has not started. H060 adds no semantic/personal memory schema, correction
outranking, candidate lifecycle policy, retrieval, summaries, UI, or physical behavior.
