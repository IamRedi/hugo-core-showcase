# H037 Context Snapshot Engine

## Record

- Task: `H037 - Context snapshot engine`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Completed: 2026-08-31
- Prerequisite: H036 commit `badff1fa838eeefe7dff3b67ef6444614c32b126`
- Runtime impact: none; H037 adds an unwired pure context reducer and canonical ephemeral projection
- Hardware claim: none

## Deterministic pipeline

`ContextSnapshotEngine` consumes bounded H036 observations plus an explicit request and produces one
immutable current-state snapshot. Its order-independent pipeline is:

1. reject more than 1,024 input observations;
2. de-duplicate byte-equivalent repeated observation IDs and reject contradictory ID reuse;
3. exclude not-yet-valid and stale observations using request-provided monotonic virtual time;
4. apply exact key scope and explicit privacy-class allowlist;
5. normalize each `(key, source type, opaque source reference)` to its newest observation time;
6. preserve same-time evidence and aggregate all remaining sources for each key;
7. retain agreement as known, retain typed unknown reasons, or preserve distinct values as conflict;
8. select keys in explicit requested priority, or canonical key order for an all-key request; and
9. enforce whole-item evidence, item-count, total-evidence, and exact canonical UTF-8 byte budgets.

There is no source-precedence or confidence-ranking policy. A source is never preferred merely due
to origin, privacy class, confidence, or input ordering. Different fresh values remain `Conflict`.
Agreeing values normalize to one known value while all contributing evidence remains visible.

## Snapshot and evidence model

Every `ContextSnapshotItem` contains its semantic H036 key, a known/unknown/conflict resolution,
effective most-restrictive privacy classification, and deterministically ordered evidence. Evidence
retains observation ID, semantic source type, opaque run-local source reference, original H036
claim, confidence, privacy classification, and exact observation/expiry times.

Unknown output contains the sorted set of source-provided H036 reasons. Absence alone does not create
a fact or default value. Conflict output contains the canonical distinct candidate set and never
chooses a winner. Input collections, policy collections, items, resolutions, evidence, and omission
records are defensively copied.

Omission evidence is aggregated by typed reason: duplicate, future, stale, key-filtered,
privacy-filtered, superseded, per-item evidence budget, item budget, total-evidence budget, or byte
budget. Budget pressure drops a whole key item; it never returns partial evidence that could conceal
a conflict.

## Hard budgets and canonical size

`ContextSnapshotBudget` validates caller limits against hard ceilings of 128 items, 32 evidence
records per item, 512 total evidence records, and 128 KiB canonical UTF-8 output. The engine removes
lowest-priority whole items until the exact `ContextSnapshotCodec` representation fits. If required
request/snapshot metadata alone cannot fit, creation fails with `SnapshotBudgetTooSmall`; it does not
truncate identifiers, claims, source evidence, or omission evidence.

`ContextSnapshotCodec` writes deterministic schema `1.0` JSON for an ephemeral snapshot projection.
It is deliberately encode-only and is not a persistence, restore, cache, logging, upload, or memory
format. `h037-context-snapshot-v1.json` is a fictional portable golden fixture.

## Authorization, privacy, memory, and authority boundary

The request contract states that observation acquisition/authorization is an upstream
responsibility. Its privacy allowlist only narrows already-authorized input; it cannot grant
permission to observe or disclose a source. H037 performs no trust or grant evaluation.

A snapshot is current evidence only. It is not durable memory, a reconciled fact, truth, safety
approval, provider selection, active-body state, connection authority, capability authorization, or
execution instruction. No snapshot data persists itself. H037 adds no source adapters, storage,
background work, provider composition, UI, Android types, BLE/GATT, LOOI protocol, or physical path.

The architecture dependency guard covers `context/runtime`, preventing platform, feature, vendor,
LOOI, transport, and protocol coupling.

## Test evidence

`ContextSnapshotEngineTest` covers virtual-time future/expiry boundaries, latest-per-source
normalization, H036 conflict propagation, cross-source conflict, agreeing sources, typed unknown,
privacy and exact-key filtering, duplicate/conflicting IDs, defensive copies, per-item/item/total/
byte budgets, metadata-too-large failure, explicit selection priority, portable golden projection,
thirty deterministic input permutations, and non-authority/non-platform fields.

Final completion checks:

- HUGO V2 documentation guard: PASS
- First Life documentation/order guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H036/H037 JVM tests: PASS
- full `testDebugUnitTest`: PASS, 669 tests in 97 suites; zero failures/errors/skips
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `git diff --check`: PASS
- implementation leakage scan: PASS, zero Android/Bluetooth/GATT/FED/LOOI references
- Android/device/LOOI verification: not required; no runtime or physical path changed

## Next boundary

H038 is complete and H039 is eligible. H037 itself adds no active-provider source, phone sensor, service
connector, persistence, memory workflow, AI context packet, dashboard, control path, LOOI behavior,
or physical execution.
