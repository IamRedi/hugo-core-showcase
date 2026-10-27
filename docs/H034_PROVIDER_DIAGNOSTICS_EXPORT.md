# H034 Provider Diagnostics and Export

## Record

- Task: `H034 - Provider diagnostics/export`
- Status: `DONE`
- Completed: 2026-08-26
- Prerequisite: H033 commit `6c5114f596fbca02ea8bb415e4fa6a277cdc1402`
- Runtime impact: none; the diagnostic model, projections, and codec are not wired to logging, UI, or upload
- Hardware claim: none

## Model and sections

H034 adds the pure `provider/diagnostic` package. `ProviderDiagnosticSnapshot` is a bounded immutable
redacted evidence artifact with schema `1.0`, export-generation timestamp, and optional sections for:

1. provider summary;
2. lifecycle and H027 orchestration state;
3. H022 identity-resolution outcome;
4. H023 manifest-resolution status;
5. H024 compatibility report and typed checks/findings;
6. H025 adapter knowledge;
7. H013 connection availability/readiness and safe method contract;
8. H029 negotiated semantic capabilities;
9. H033 blocked-state code and resolution category;
10. H031 calibration readiness/status and item/profile references;
11. bounded evidence with safe provenance; and
12. timestamps and explicit `FRESH`, `STALE`, or `UNKNOWN` state.

The summary can represent a partially known provider with definition/family hints and no confirmed
identity. Missing sections remain `null`, not invented defaults. Adapter presence and connection
readiness have contradiction checks. Identity-known summaries require definition and family.

Diagnostics explicitly grant no trust, permission, safety approval, physical-feasibility proof,
execution success, or active-body selection.

## Redaction and correlation policy

The exported model has no `ProviderInstanceId`, protected identity reference, endpoint, transport
address, account ID, credential, token, key, approval secret, packet, or Android Bluetooth type.
It accepts only H004 redacted aliases and suppresses aliases shaped like UUIDs, IPv4 addresses, MAC
addresses, or account identifiers.

`ProviderDiagnosticId` is an export-local project-owned `diag_` label. Projection code derives it by
SHA-256 over a typed scope plus source correlation and exports only the first 96 bits as lowercase
hex. The source value never appears in the portable artifact, and the same source under different
scopes yields different labels. These labels support within-export correlation only; they are not
provider identity, trust evidence, or authorization references.

Arbitrary check/provenance values pass through `DiagnosticSafeValue`, which is bounded and rejects
control/whitespace, URLs, UUIDs, MAC/IP/account forms, and sensitive-shaped terms such as credentials,
OAuth/token/API-key/access-code, serial, IMEI, and endpoint labels. Safe projections replace an unsafe upstream display value
with the literal semantic marker `redacted` rather than leaking it.

## Accepted-contract projections

`ProviderDiagnosticProjection` converts only safe fields from accepted H021-H032 values:

- redacted provider identity/definition/family/alias;
- orchestration phase, stop reason, lifecycle state, and generation;
- redacted identity/manifest status, reasons, counts, and one-way candidate/evidence labels;
- compatibility status plus every typed dimension, check status, and H024 finding;
- adapter ID/version/knowledge state without digest or artifact path;
- connection method/readiness/availability without endpoint, address, or raw session ID;
- negotiation status/reason, semantic H005 capabilities, and freshness without provider/session IDs;
- calibration readiness/status/item references without measurement, evidence payload, or procedure;
- redacted H021 discovery timestamps and generic discovery provenance without source-local reference.

H033 owns the blocked taxonomy. `ProviderBlockedUiState.toDiagnosticSection()` lives in
`feature/providers` and exports H033 `kind` and `resolutionAvailability` names verbatim as safe
values. The pure diagnostic layer therefore neither imports the feature package nor defines a
second error taxonomy. The H019 architecture guard protects `provider/diagnostic` from Android,
feature, platform, LOOI, and other forbidden dependencies.

## Codec, strictness, and bounds

`ProviderDiagnosticCodec` encodes canonical compact JSON and always writes every top-level section,
using explicit `null` for unavailable knowledge. Collections are defensively copied, de-duplicated,
and ordered by stable semantic keys. Equivalent snapshots therefore encode identically independent
of input ordering.

Decode requires the exact top-level and nested field sets, exact enum names, valid accepted IDs and
versions, valid timestamps, internally consistent sections, and schema `1.0`. Malformed documents,
unexpected/missing fields, invalid values, duplicate correlations/capabilities/items/checks,
contradictions, unsupported schema major/minor, and oversized documents fail closed.

Limits are 64 KiB encoded output, 128 compatibility checks, 128 semantic capabilities, 128 evidence
records, 64 calibration items, and 64 identity candidates. There is no unrestricted log or raw data
escape hatch.

## Capability, compatibility, blocked, and calibration semantics

Capabilities are strict H005 `CapabilityReference` values only. A future LOOI export may therefore
show semantic names such as `mobility.stop`, `mobility.move`, and
`body.head.position.control`; FED service IDs, Bluetooth addresses, GATT objects/handles, commands,
packets, and vendor codec details are not representable.

Compatibility preserves H024 report status and typed dimension/check/finding evidence instead of a
boolean. Missing adapter remains distinct from incompatible provider/adapter. Blocked state reuses
H033 codes exactly and remains explanatory evidence only. Calibration exports readiness, aggregate
status, opaque profile correlation, and semantic unmet item IDs—never measurement values or private
calibration payloads.

## Portable fixture and completion evidence

`fixtures/h034-provider-diagnostic-v1.json` is a redacted portable complete artifact for support,
Developer Mode, hardware integration, and acceptance-test workflows. It contains a fictional HUGO
test provider and semantic capabilities, not LOOI/private hardware data.

`ProviderDiagnosticTest` covers the complete fixture, partially known/ambiguous provider, missing
adapter, incompatible provider, negotiated semantic capabilities, H033 authorization block,
calibration block/reference-only output, stale evidence/timestamps, deterministic ordering, strict
round trip, malformed/unexpected fields, schema mismatch, stable-identifier and secret rejection,
private-ID/type absence, vendor/protocol leakage rejection, scoped one-way correlations, and size/
count bounds.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H021-H034 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- `assembleDebug`: not required because H034 adds no Android implementation or UI
- Android/device/provider/hardware verification: not required and not claimed

## Next boundary

H035 is eligible and has not started. H034 adds no M3 acceptance run, diagnostic UI, provider
control, packet console, upload, Developer Mode, AI, voice, memory, World Model runtime, dashboard,
LOOI runtime change, capability execution, or physical behavior.
