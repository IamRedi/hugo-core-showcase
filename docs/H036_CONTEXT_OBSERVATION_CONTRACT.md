# H036 Context Observation Contract

## Record

- Task: `H036 - Context observation contract`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Completed: 2026-08-31
- Accepted starting point: First Life freeze commit `91535a65112f7350fdb5a1002dd346b4d5eb04c9`
- Runtime impact: none; H036 adds a pure provider-neutral contract and strict interchange codec
- Hardware claim: none

## Observation envelope

H036 adds the pure `context/api` package. A `ContextObservation` contains exactly the bounded evidence
needed for one source observation:

- a canonical UUID event reference that is not an identity;
- a semantic source type and opaque run-local source reference;
- a provider-neutral semantic context key;
- an explicit known, unknown, or conflicting claim;
- source-observation confidence in integer permille;
- an explicit public, private, sensitive, or restricted privacy classification; and
- monotonic observation and expiry timestamps.

Freshness is derived deterministically as `NOT_YET_VALID`, `FRESH`, or `STALE`. The expiry boundary
is strict: an observation is stale at `expiresAt`, and an invalid or empty validity window is
rejected. H036 defines no wall-clock conversion, source catalogue, refresh policy, normalization,
selection, persistence, or snapshot engine.

## Known, unknown, and conflict semantics

`ContextObservationClaim.Known` carries one bounded scalar value. Supported values are boolean,
signed integer, canonical decimal, bounded text, and semantic symbol. Decimal spelling is canonical,
text rejects blank/control-bearing values, and semantic tokens use a restricted portable grammar.

`Unknown` carries a typed reason: not observed, source unavailable, permission denied, unsupported,
or redacted. It cannot contain an invented fallback value.

`Conflict` preserves two through eight distinct candidates and orders them by their canonical type
and value. It never chooses a winner. Inputs are defensively copied, so later caller mutation cannot
change accepted evidence. Duplicate, too-small, and oversized conflict sets fail closed.

## Source, confidence, and privacy boundary

Source references are project-owned opaque aliases. Their grammar rejects addresses such as MAC
values and does not expose provider IDs, account IDs, serials, endpoints, or Android/platform types.
Source type identifies only a semantic source class. H036 establishes no precedence or trust policy
between sources.

Confidence describes only how strongly a source supports its observation and is bounded to
`0..1000`. It does not turn an observation into truth or authorization. Privacy classification is
mandatory on every observation; later consumers must apply their own scope and disclosure policy.
H036 performs no logging or export.

## Strict schema and deterministic codec

`ContextObservationCodec` provides versioned schema `1.0` interchange. It writes a canonical compact
JSON field order and decodes exact root, claim, and value shapes. Unsupported schema versions,
missing/unexpected fields, malformed scalar types, invalid identifiers, invalid freshness windows,
invalid confidence, non-canonical values, duplicate conflicts, and attempts to add a value to an
unknown claim are rejected with typed errors.

The codec is not a repository or persistence format owner. Durable snapshots, expiry processing,
conflict reconciliation, privacy budgets, source implementations, and context selection belong to
later tasks beginning with H037.

## Non-authority and architecture guarantees

An observation is explicitly not a durable memory, reconciled fact, truth claim, trust decision,
permission, authorization, safety approval, provider selection, connection state, active-body
authority, or execution instruction. No field can grant those semantics. Unknown and conflict are
first-class results rather than implicit null/default states.

The architecture dependency guard covers `context/api` and prevents Android, platform, feature,
LOOI, transport, vendor, and protocol dependencies. H036 does not modify H003-H035 contracts, First
Life composition, provider runtime, safety, manual control, BLE/GATT, or physical behavior.

## Completion evidence

`ContextObservationTest` and `ContextObservationCodecTest` cover:

- explicit source, privacy, confidence, and freshness boundaries;
- known, unknown, and conflicting claims;
- deterministic conflict ordering and defensive copying;
- bounded/canonical scalar values and identifiers;
- strict deterministic schema round trips;
- unsupported, missing, unexpected, and malformed document rejection;
- invalid confidence and freshness rejection;
- prevention of unknown-value and authority-field smuggling; and
- absence of memory, fact, provider, trust, permission, selection, connection, or execution fields.

Final check results are recorded after the completion run:

- HUGO V2 documentation guard: PASS
- First Life documentation/order guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H036 JVM tests: PASS
- full `testDebugUnitTest`: PASS
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `git diff --check`: PASS
- Android/device/LOOI verification: not required; no runtime or physical path changed

## Next boundary

H037 is eligible and has not started. H036 adds no source adapters, context snapshot runtime,
persistence, memory, AI, voice, dashboard, service connector, provider control, LOOI behavior, or
physical execution.
