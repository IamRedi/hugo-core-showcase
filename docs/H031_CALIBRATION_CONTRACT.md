# H031 Calibration Contract

## Record

- Task: `H031 - Calibration contract`
- Status: `DONE`
- Completed: 2026-08-26
- Prerequisite: H030 commit `6ffa40a2442ca1ae242f2f6e3426c9e7667abee4`
- Runtime impact: none; the domain is not wired to a provider, safety gate, execution path, or LOOI
- Hardware claim: none

## Domain and manifest boundary

H031 adds the pure provider-neutral `provider/calibration` domain. H011 body manifests continue to
declare only immutable `ManifestContractReference` values in `calibrationRequirements`; no manifest
schema or resolver semantic changes were required. A mutable calibration result is never stored in a
manifest and cannot become provider-definition-wide truth.

`CalibrationProfile` expands those accepted references into an instance-bound runtime contract with:

- a UUID `CalibrationProfileId` and supported `CalibrationContractVersion`;
- one exact H004 `ProviderIdentity`, including its private provider-instance binding;
- required or optional `CalibrationRequirement` records;
- versioned manifest requirement and result-schema references;
- typed bounded fields, canonical units, finite numeric bounds, and affected H005 capabilities;
- adapter-owned manual, semi-automatic, or automatic procedure references;
- minimum verification/confidence levels and explicit invalidation conditions; and
- creation time and H007 audit correlation.

Procedure references identify adapter/provider-owned contracts only. HUGO Core does not know steps,
packets, sensors, commands, physical motion, or vendor calibration algorithms.

## Requests, observations, and results

`CalibrationRequest` binds one profile, provider instance, provider generation, item, supported
procedure reference, monotonic request/deadline interval, and audit ID. It requests observation work;
it neither invokes an adapter nor grants authority.

`CalibrationResult` represents provider-instance-specific observations with one of
`IN_PROGRESS`, `CANDIDATE`, `VERIFIED`, `FAILED`, or `UNSUPPORTED`. Candidate/verified records carry:

- an exact profile, contract version, provider instance, item, and manifest requirement binding;
- a schema-bound immutable `CalibrationMeasurement` of typed project-owned values;
- optional provider procedure correlation;
- ordered evidence and explicit verification level;
- provider generation plus optional firmware and hardware-revision compatibility binding;
- recorded and valid-until timestamps;
- copied invalidation conditions; and
- an audit ID.

The generic value vocabulary is finite decimal, integer, boolean, and bounded text. Structured
calibration is represented as schema-owned named fields, so future bodies and devices can define
poses, geometry, offsets, envelopes, or other measurements without putting their field names or
procedures into generic HUGO code. Raw bytes and protocol values are not representable.

## Evidence and verification

H031 distinguishes `VENDOR_DEFAULT_SPECIFICATION`, `USER_ENTERED`, `MEASURED_CALIBRATION`,
`HARDWARE_VERIFIED_CALIBRATION`, and `INFERRED_ESTIMATE`. Evidence carries a bounded opaque
reference, confidence in permille, observation time, and optional expiry.

Verification levels are ordered separately as `UNVERIFIED`, `CANDIDATE`, `MEASURED`, and
`HARDWARE_VERIFIED`. Evidence types cap the strongest level they can support:

- vendor/default, user-entered, and inferred evidence can support only a candidate;
- measured evidence can support measured verification; and
- hardware-verified evidence can support hardware verification.

A result marked `VERIFIED` requires at least measured verification. Each requirement also declares
the minimum evidence confidence needed before validation accepts that level. Low-confidence
evidence remains candidate-only. An inferred estimate cannot be
constructed as measured or hardware-verified, and a `CANDIDATE` result never satisfies a required
calibration even if it carries useful evidence. A requirement may demand hardware verification; a
valid measured result then remains explicitly `CANDIDATE` for that requirement.

Evidence, requirements, measurements, invalidation conditions, results, assessments, and validated
facts are defensively copied and canonically ordered.

## Validation and invalidation

`CalibrationValidator` is a pure evaluator over one profile, bounded result list, and current
provider context. It validates exact instance/definition/family, profile and contract version,
manifest requirement reference, result schema, field set and type, numeric bounds, supported
procedure, result/evidence timestamps, verification strength, and compatibility bindings.

`CalibrationStatus` represents `REQUIRED`, `NOT_CALIBRATED`, `IN_PROGRESS`, `CANDIDATE`, `VERIFIED`,
`EXPIRED`, `INVALIDATED`, `FAILED`, and `UNSUPPORTED`. Each item carries one deterministic finding.
Multiple results for the same item are a conflict and fail closed; H031 does not choose a preferred
result. Unknown items and future-dated state also fail closed.

Invalidation conditions can bind truth to provider generation, firmware version, hardware revision,
profile version, manifest requirement, evidence freshness, and explicit invalidation. Changes are
checked only when the requirement declares the corresponding condition. A result must carry the
exact requirement condition set and cannot weaken it. Exact provider instance, profile, contract,
and requirement bindings always remain mandatory. No record from one
provider instance is silently reused for another, even when definitions/models match.

The assessment exposes `allRequiredItemsVerified`, ordered unmet items, and affected semantic
capabilities without executing policy. Required items that are missing, candidate, expired,
invalidated, failed, or unsupported remain unsatisfied; optional absence does not invalidate a fully
verified required set.

## H029 readiness boundary

`CalibrationNegotiationReadinessEvaluator` correlates an H031 assessment with one current H029
negotiation report. It validates provider instance, generation, negotiation identity/status, record
correlation, and record freshness, then reports only:

- `READY`: no currently negotiated semantic capability has an unmet required calibration;
- `CALIBRATION_REQUIRED`: negotiated capabilities are blocked by named unmet calibration items;
- `STALE_NEGOTIATION`: H029 evidence is stale or mismatched; or
- `INVALID`: calibration assessment evidence is structurally failed.

This is readiness evidence, not an execution policy. It does not invoke H030, select providers,
authorize requests, run safety, or dispatch a capability. A capability not currently negotiated is
not reported as blocked by this projection; the calibration profile and assessment remain valid
independent provider-instance knowledge.

## World Model, memory, and safety boundaries

`ValidatedCalibrationFact` projects only verified semantic measurement, provider/profile/item,
generation, evidence, validity, and audit correlation. It intentionally omits the procedure. A
future World Model may consume these facts as geometry/physical evidence but owns interpretation,
spatial relations, uncertainty reconciliation, and physical-feasibility assessment. It does not own
calibration procedures.

H031 has no storage or codec. H062 Body/Device Memory may later persist version-bound calibration
history under the Memory Architecture, with migration, reconciliation, privacy, and deletion rules.
Avoiding a premature wire/storage format prevents H031 from silently defining memory semantics.

```text
CALIBRATION_REQUIREMENT != PROCEDURE != RESULT != VERIFIED
VERIFIED_CALIBRATION != AUTHORIZED != SAFE != PHYSICALLY_FEASIBLE != EXECUTED
```

Profiles, requests, results, assessments, facts, and H029 readiness explicitly grant no permission,
authorization, safety approval, execution authority, feasibility proof, or memory persistence. A
later deterministic policy may require valid calibration after negotiation/authorization and before
physical execution, but H031 neither implements nor bypasses that policy. Existing T032 LOOI
calibration, controls, telemetry, safety, and hardware evidence are untouched.

## Completion evidence

`CalibrationValidatorTest` covers valid required/optional requirements, adapter-owned request
binding, measured and hardware-verified results, typed/out-of-range values, cross-provider rejection,
wrong profile/version, result and evidence expiry, generation/firmware/hardware/explicit
invalidation, inferred candidate isolation, insufficient verification, duplicate/conflicting
results, in-progress/failed/unsupported observations, immutable/canonical collections,
deterministic assessments, H029 required/ready/stale projection, verified World Model fact boundary,
and absence of permission/safety/execution/memory/vendor/transport/LOOI coupling.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H010/H011/H016/H029/H030/H031 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Next boundary

H032 has not started. H031 adds no provider-instance store, calibration persistence/codec, procedure
implementation, sensor UI, World Model runtime, safety execution, provider selection, planner, AI,
dashboard, Home Assistant, LOOI runtime rewiring, or new physical behavior.
