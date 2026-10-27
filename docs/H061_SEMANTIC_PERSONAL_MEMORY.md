# H061 Semantic and Personal Memory

## Record

- Task: `H061 - Semantic and personal memory`
- Status: `DONE`
- Completed: 2026-09-04; remediated after independent review and rereview: 2026-09-18
- Prerequisites: H058 common memory envelope, H059 encrypted repository (with the H060-approved
  additive keyset-listing and the newly approved additive atomic change-set extension), H060
  working/episodic memory
- Runtime impact: none in production paths; H061 is an unwired pure lifecycle layer and typed
  facades over the H059 store
- Hardware claim: none; the task row requires no device verification (`HW: none`)

## What H061 adds

Two pure packages implementing the governed semantic/personal layer:

- `memory/semantic` — `SemanticFactFactory` (validated facts and concepts, plus target-aware
  semantic corrections), `SemanticConfirmationService` (the explicit user confirmation
  lifecycle), and `SemanticCorrectionService` (atomic user corrections of semantic records
  through the H059 change-set).
- `memory/personal` — `PersonalRecordFactory` (preferences, routines, goals, corrections) and
  `PersonalCorrectionService` (atomic user corrections through the H059 change-set).

Dependency direction: semantic/personal → H058 envelope → H059 store (+ its approved
change-set extension). The store holds no semantic meaning; the layers decide.

## Validation authority — explicit user confirmation only

- A semantic or personal record requesting `VALIDATED` at creation must carry user-explicit,
  non-model provenance (`SELF_VALIDATION_FORBIDDEN` otherwise). Model, tool, observed,
  conversation-summary, and inferred provenance always start as `CANDIDATE`.
- `SemanticConfirmationService.confirm` is the only candidate→validated transition: it requires
  an explicit `userConfirmed` flag (a future governed UI flow may set it only for a genuine
  user confirmation gesture) plus a bounded actor token. Confirmation preserves provenance and
  confidence unchanged — a validated model-inferred record keeps its model-inferred origin —
  and records `updatedAt` from the injected clock. `DISPUTED` records cannot be created
  directly (the disputed state belongs to the user-correction/reconciliation flow), and only
  ACTIVE, CANDIDATE, SEMANTIC-layer records are confirmable. Confirmation is fail-closed and
  race-safe: an expired retention (`RETENTION_EXPIRED`) and an unresolved conflict
  (`UNRESOLVED_CONFLICTS`) are not confirmable, and the transition commits CAS-style through
  the store's `persistTransition` — the stored record must still be canonically identical to
  the candidate the decision was made on, so a concurrently committed correction or change can
  never be overwritten (a superseded record can never be resurrected by a stale confirmation).

## Explicit correction outranks inference

Supersession authority belongs to the user alone, for both PERSONAL and SEMANTIC records:

- `PersonalRecordFactory.createCorrection` and `SemanticFactFactory.createCorrection` are
  opened for one specific owner and can never create a correction for another owner's record
  (`OWNER_MISMATCH`, adversarially tested for both layers); the storage-level owner/pair
  checks remain as defense in depth. A correction successor's provenance is forced to
  user-explicit, non-model (`memory.correction` source tokens), its `supersedes` link targets
  the corrected record, and its lineage preserves the predecessor reference. A model may
  never correct (`CORRECTION_PROVENANCE_FORBIDDEN` on both service paths). A semantic
  correction of a non-semantic target (and a personal correction of a non-personal target) is
  rejected at the factory (`CORRECTION_TARGET_MISMATCH`) and again at the store
  (`CROSS_LAYER_SUPERSESSION`) — a successor always remains in its target's layer.
- Correction pair integrity is enforced at the store boundary for every supersession, live or
  replayed: the successor must stay bound to the target's owner and preserve the target's
  layer, type, scope, subjects, privacy, and allowed use (`CORRECTION_PAIR_MISMATCH`
  otherwise). A correction replaces content, never governance; privacy and allowed use cannot
  be relaxed through a correction.
- `PersonalCorrectionService.correct` and `SemanticCorrectionService.correct` commit the
  correction successor and the SUPERSEDED target as one atomic change-set. A user correction
  may supersede any ACTIVE record — including a VALIDATED model-inferred one — which is
  exactly the "explicit correction outranks inference" precedence; conversely nothing in H061
  allows an inference to supersede or overwrite a governed record.
- The user-corrected successor is created VALIDATED (a user statement is authoritative).

## Public correction idempotency

A client may lose the success acknowledgment after the durable commit and retry the exact same
correction. The stable operation identity is the successor record id; the payload binding is a
domain-separated SHA-256 (`MemoryChangeSetPayloadBinding.forSupersession`) over the successor
id, the derived SUPERSEDED target-post-state id, and both canonical envelope encodings:

- the first call applies (`Applied`);
- an exact retry of the same operation — the same successor envelope and the same derived
  target post-state, re-verified against the decrypted stored records and the recomputed
  binding — returns the explicit idempotent outcome `AlreadyApplied` (carrying the committed
  correction), immediately and after a restart;
- the same operation identity with a different payload fails closed
  (`OPERATION_ID_REUSED`, mapped to `IDEMPOTENCY_CONFLICT` on both service paths) — the stored
  record under that id is never overwritten;
- a successor slot present without a committed, consistent supersession remains a typed
  `DUPLICATE_SUCCESSOR`;
- a genuinely different correction against an already superseded target still fails
  (`STALE_WRITE`/`TARGET_NOT_ACTIVE`). Stale-write protection is unchanged.

## H059 atomic change-set (approved additive extension)

`MemoryEnvelopeStore` gains `persistSupersessionChangeSet(successor, expectedCurrent,
updatedAtEpochSecond)`, `persistTransition(rebuilt, expectedCurrent)` (CAS same-record
lifecycle commit), and `recoverChangeSet()`, backed by a new `MemoryChangeSetJournal` port
(platform: `AtomicFileMemoryChangeSetJournal`, a single atomic journal file holding only
already-encrypted slot documents, aggregate-bounded at the journal port). Semantics:

- **Pending journal gate:** every public store operation (`persist`, `load`,
  `storedRecordIds`, `persistSupersessionChangeSet`, `persistTransition`,
  `resetForLostKey`) first attempts to resolve a pending journal and fails closed with
  `PENDING_CHANGE_SET_UNRESOLVED` / `PENDING_JOURNAL_UNRESOLVED` while it cannot be resolved —
  no read, write, listing, or new change-set observes or extends intermediate state.
- **Stale-write protection:** the currently stored target is loaded, decrypted, decoded, and
  compared canonically against the caller's `expectedCurrent`; any divergence yields
  `STALE_WRITE` before anything is written. The SUPERSEDED post-state is derived by the store
  from the current record itself. Duplicate-successor detection runs before the stale compare,
  so a correction of an already-corrected record resolves through the idempotency rules above.
- **Crash safety / recoverability:** the encrypted journal is written before any slot changes
  and cleared only after every slot write succeeded. A crash at any point leaves the journal
  present; `recoverChangeSet()` strictly validates the surviving journal (see below), replays
  the same encrypted documents deterministically (idempotent forward-apply) and reports the
  applied count. A slot-write failure during `persistSupersessionChangeSet` intentionally
  retains the journal for that recovery.
- **Clear-failure correctness:** if the journal cannot be cleared after a full apply, the
  change-set reports `JOURNAL_CLEAR_FAILED` (never success), `recoverChangeSet()` reports the
  same failure, and the pending journal keeps blocking new operations until a clear actually
  succeeds — an uncleared journal can never be stranded while newer writes proceed, so a later
  replay can never overwrite newer committed state.
- **Strict replay preflight:** a decoded replay intent is fully validated before any slot is
  written: exactly two entries, unique record ids, per-entry slot-document size bounds (and
  the journal-port aggregate bound, enforced before content is read), every slot document
  decrypting/authenticating, each envelope's id matching its journal entry, the successor
  ACTIVE with a `supersedes` link to the target, correction authority re-verified
  (user-explicit, non-model successor — a forged journal cannot launder a model correction
  through replay), the recomputed payload binding matching the journal's stored binding, the
  target SUPERSEDED, and correction pair integrity. Any violation fails closed with nothing
  written.
- **Bounds:** per-record plaintext/slot ceilings unchanged; the journal carries only two
  encrypted entries (successor + target post-state).
- The journal at rest contains only ciphertext and record ids — no plaintext, no keys.

## Durable journal protocol and supported writer model

`AtomicFileMemoryRecordSlots` and `AtomicFileMemoryChangeSetJournal` implement the redo
protocol over an explicit `MemoryFileDurability` port, with fault injection at every stage
(`MemoryDurabilityStage`, `MemoryDurabilityFaultInjector`):

- **A before B:** a journal intent is durably persisted — pending bytes `fsync`ed, atomic
  rename into place, containing-directory entry synced (`JOURNAL_PENDING_FILE_SYNCED` →
  `JOURNAL_RENAMED` → `JOURNAL_DIRECTORY_SYNCED`) — before the store lets any record slot of
  that intent durably commit (each slot write: `RECORD_PENDING_FILE_SYNCED` → `RECORD_RENAMED`
  → `RECORD_DIRECTORY_SYNCED` before `Written` is reported).
- **C before D:** after all slots are durably committed, journal removal is two-phase and
  crash-verifiable — the live journal is atomically renamed to a removal tombstone and the
  directory is synced (the removal intent itself becomes durable), then the tombstone is
  unlinked and the directory is synced again (`JOURNAL_DELETED` →
  `JOURNAL_DELETE_DIRECTORY_SYNCED`). A failed delete, a still-existing path, or a failing
  directory sync never reports `Cleared`; a leftover tombstone keeps the store blocked until
  the removal completes durably — before the store allows any later unrelated operation
  through the pending-journal gate.
- **Restart durability barrier, stateless:** correctness never depends on in-memory flags.
  Every `readIntent()` that finds a live journal first re-establishes the directory durability
  barrier before returning any bytes, so a fresh instance that finds a pending journal —
  however it got there, including a crash after the rename but before the write-side directory
  sync — re-establishes durability BEFORE the store can run any replay slot write; if the
  barrier cannot be established, reads fail closed and nothing is written. A fresh instance
  that finds only a removal tombstone first completes the removal durably and reports Empty.
  The ordering A → B → C → D therefore survives a restart between any stages.
- **Strict platform behavior, no fallback:** content durability uses `FileDescriptor.sync()`;
  the directory entry is forced with a real `FileChannel.force(true)` directory open — the
  supported primitive on Android/Linux. There is deliberately no best-effort fallback: where
  the platform cannot provide the directory-durability primitive (JVMs that cannot open
  directories, e.g. Windows), every durable journal/slot operation fails closed with
  `IO_FAILED` and durable capability is unavailable rather than weakened. Adapters verify the
  real successful sync where it is supported and the fail-closed behavior everywhere else.
- **Supported writer/process model:** one `MemoryEnvelopeStore` writer instance in one process
  per memory directory. Cross-process/multi-instance writer coordination is deliberately not
  claimed and not guarded; within the process, store operations serialize on the store lock.

## Debug-output boundary

No H061 memory production source emits debug output, diagnostics, or memory state to stdout
or stderr. A focused guard test drives store operations — including failure paths — with
captured standard streams and asserts both remain empty.

## Coverage and checks

`MemoryChangeSetTest` contributes 9 focused storage tests: atomic apply (successor written,
target SUPERSEDED with preserved provenance/state and injected update time); stale-write
rejection with no writes and untouched target; already-supereded target rejection; a reused
successor identity with a different payload failing closed without touching the stored record;
link-mismatch rejection; cross-layer supersession rejection; missing-target typing; mid-write
slot failure retaining the journal followed by deterministic recovery; replay idempotency plus
a clean-store nothing-to-recover outcome; and journal-unavailable typing.

`MemoryChangeSetStrictJournalTest` contributes 7 focused strictness/idempotency tests: the
strict codec rejecting unsupported versions, unknown/missing fields, oversized input,
duplicate ids, and malformed base64 without constructing an intent; replay authenticating
every entry and rejecting a forged payload binding before any write; replay rejecting
non-user correction authority before any write; an exact correction retry returning
`AlreadyApplied` before and after a store restart; the same operation identity with a
different payload failing closed; and a genuinely different correction against an already
superseded target still failing stale protection.

`MemoryChangeSetRemediationTest` contributes 8 focused store-guard tests: a pending
unresolvable journal blocking persist, load, listing, and new change-sets; journal-clear
failure never reporting success and blocking (including recovery) until the journal clears;
replay intents with a wrong entry count, tampered (non-authenticating) entries, and an
oversized entry all failing the preflight with zero slot writes; replay validation of the
envelope-id match and the successor/SUPERSEDED-target relationship; correction pair mismatch
rejected at the store boundary; and captured-stdout/stderr emptiness across success and
failure paths.

`AtomicFileMemoryChangeSetJournalDurabilityTest` contributes 11 focused real-file durability
tests: clear returning `Cleared` only after the journal file and removal tombstone are
actually absent; a failed real deletion keeping the removal tombstone in place with the store
blocked until the delete succeeds; call-order proof that the journal is durably synced before
any record slot and the journal removal syncs last; interruption after the journal rename
writing no record slot with restart recovery from the redo intent; interruption after a record
rename leaving the redo intent and restart converging; a failed journal-removal durability
blocking the next operation until the directory sync completes; a failing directory sync
failing closed across journal and slot operations with no durable completion reported and no
unsynced journal readable for replay; a failed removal never reporting `Cleared` while a fresh
failing instance stays blocked and a healthy instance completes the removal; a platform
without a directory-durability primitive failing closed on every durable operation; a fresh
instance re-establishing the journal durability barrier before any replay slot write (event
order proof, no reliance on process-memory state); and a failing barrier on a fresh instance
causing zero replay slot writes before a healthy retry recovers safely. Tests requiring the
platform primitive run where it is supported (Android/Linux, Linux CI) and are guarded by a
documented platform assumption elsewhere, where the fail-closed tests run instead.

`CorrectionOwnerBoundaryTest` contributes 2 adversarial tests: a personal factory opened for
owner A and a semantic factory opened for owner A each rejecting an owner-B correction target.

`CorrectionPublicIdempotencyTest` contributes 2 focused tests: the personal correction exact
retry returning `AlreadyApplied` across a restart with reused identities and conflicting
payloads failing closed, and the semantic correction exact retry returning `AlreadyApplied`
across a restart.

`SemanticMemoryTest` contributes 14 focused tests: creation validation authority
(user-explicit VALIDATED; every other provenance CANDIDATE; self-validation forbidden);
session-retention and DISPUTED rejection; the confirmation lifecycle (candidate→validated
with provenance preserved, wrong-layer/not-active/not-candidate/user-flag-missing
rejections); confirmation fail-closed safety (expired retention, unresolved conflicts, and a
stale CAS transition rejected with nothing written); the regression in which an explicit user
correction atomically supersedes an inferred semantic fact while the successor stays bound to
the target's governance fields; model-correction prohibition; foreign-layer correction-target
rejection; and the canonical round trip through the store.

`PersonalCorrectionTest` contributes 11 focused tests: personal creation rules; the
correction successor shape (user-explicit provenance, supersedes link, lineage); atomic
apply with SUPERSEDED target; stale-write rejection with zero writes; target-not-active and
reused-identity rejections; correction provenance enforcement (model corrections forbidden);
governance-field change rejection without overwriting; and full change-set recovery after a
simulated mid-write crash.

Rereview repairs recorded: `MemoryRetention` received value `equals`/`hashCode` (the pair
integrity check compares retention semantically, so equal-but-distinct retention values are no
longer falsely rejected); the reused-successor-identity expectations were aligned with the
idempotency semantics (`OPERATION_ID_REUSED`/`IDEMPOTENCY_CONFLICT`); the forged-authority
replay test now builds its model-provenance successor through the codec restore path (the
creation path correctly refuses model-inferred VALIDATED records outright); the durability
protocol was tightened to strict platform behavior (the best-effort marker fallback was
removed — directory-sync failure now propagates and durable capability is unavailable rather
than weakened where the primitive does not exist); journal removal became a crash-verifiable
two-phase tombstone; and the restart durability barrier became stateless (every fresh instance
re-establishes the barrier before any replay slot write, with zero writes when it cannot).

Record-trace style evidence remains content-free; all diagnostics redact content, owners,
instance identifiers, and actors.

- focused H061 JVM tests: 53/53 PASS (9 change-set + 7 strict journal/idempotency + 8
  remediation store-guard + 2 owner boundary + 2 public idempotency + 14 semantic + 11
  personal)
- real-file H059/H061 storage and journal tests: 15/15 PASS (11 journal durability — the
  primitive-requiring tests run on Android/Linux CI, the fail-closed tests run everywhere —
  plus 4 slots pagination); H059 store contract tests: 19/19 PASS
- architecture dependency guard (now also protecting `memory/semantic`, `memory/personal`):
  PASS
- full `:app:testDebugUnitTest`: PASS — 1053 tests across 131 suites, zero failures or
  errors; 9 skips are the documented platform assumptions for the primitive-requiring
  real-file durability tests on this Windows host (they run on Android/Linux CI)
- `:app:lintDebug`: PASS — 0 errors, 13 warnings (unchanged count)
- `:app:assembleDebug`: PASS
- documentation guards (First Life order, HUGO V2, T032 baseline evidence): PASS
- `git diff --check`: PASS
- Android-device verification: not required by the task row (`HW: none`) and not claimed

## Boundary statement

H061 adds no retrieval, embeddings, vector/graph search, ranking, consolidation, summaries,
UI, autonomous learning, strategy memory, sync, or H062+ behavior. Semantic/personal records
are never authoritative for safety; validation state never implies truth or authorization;
and the H059 store remains the sole canonical, encrypted, fail-closed persistence.

## Next boundary

H062 is eligible and has not started. H061 adds no device/body instance memory, no
validation/reconciliation service, no retrieval, no UI, and no physical behavior.
