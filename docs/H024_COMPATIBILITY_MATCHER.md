# H024 Compatibility Matcher

## Record

- Task: `H024 - Compatibility matcher`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H023 commit `c82c65cd97657a1d1ae14cc39d2a13d56c25b2b0`
- Runtime impact: none; the pure matcher is not wired to provider runtime
- Hardware claim: none

## Model and authority boundary

H024 adds the pure `adapter/compatibility` request, evidence, check, report, and matcher contracts.
It evaluates one validated H023 `ResolvedManifestDefinition` against an optional H012 adapter
descriptor candidate, the current HUGO runtime version, known H013 connection-method contracts,
known extension schemas, and provider-version evidence.

The result is compatibility evidence only:

```text
COMPATIBLE != INSTALLED != AVAILABLE != TRUSTED != AUTHORIZED
COMPATIBLE != CONNECTED != SAFE != PHYSICALLY_FEASIBLE != SELECTED
```

The matcher has no adapter installation/enablement state, provider/task selection, trust or
permission record, connection provider invocation, lifecycle state, registry port, capability
dispatch, safety decision, or physical-world fact. A compatible descriptor remains a descriptor
candidate, not proof that code is present, intact, enabled, loaded, available, or executable.

## Request and evidence

`CompatibilityRequest` carries:

- the H023 resolved definition, including its H010/H011 documents and acquisition/embedded
  provenance;
- an optional adapter descriptor plus an opaque evidence reference;
- the HUGO runtime semantic version plus an opaque evidence reference;
- zero or more H013 semantic connection-method/version support records;
- zero or more extension ID/schema-version support records; and
- zero or more provider firmware/software/API version evidence records.

`CompatibilityEvidenceReference` accepts only bounded project-owned semantic references. It is not
a URL, endpoint, address, credential, signature, trust-chain proof, or permission. Every check
retains canonically ordered evidence source/reference pairs. Manifest checks reference the H023
candidate catalog provenance; adapter, runtime, connection, extension, and provider-version checks
retain their own input evidence.

## Separately evaluated dimensions

The report contains ordered `CompatibilityCheck` entries for:

- common manifest schema major;
- specialized manifest kind/schema major;
- provider definition and provider family;
- adapter identity and manifest-declared version range;
- adapter family/common-schema/specialization support declarations;
- HUGO runtime compatibility range;
- every exact manifest/adapter H005 capability reference;
- every H010 connection method, using exact H013 semantic method ID and version;
- every common/specialized/body-geometry extension ID and exact schema version; and
- every H010 provider firmware/software/API constraint, plus the H011 service API constraint when
  applicable.

Required/observed values are recorded for each check. Findings distinguish matches, no requirement,
missing adapter, unsupported schema/version/capability/connection method, identity/family/adapter
mismatch, extension mismatch, missing provider-version evidence, and conflicting constraints.

H010 and H012 capability declarations are matched exactly in H024. No undocumented capability or
connection-version coercion is introduced. A service common API range and specialized service API
range must overlap; disjoint ranges are `CONFLICTING_CONSTRAINT`. Multiple contradictory observed
versions for one compatibility subject are also conflicting. A declared provider constraint with
no provider-version evidence is `UNKNOWN`, not guessed compatible.

## Overall status semantics

- `COMPATIBLE`: every required check matched and no optional check is missing.
- `PARTIALLY_COMPATIBLE`: all required checks matched, but an explicitly optional connection method
  is unsupported. H024 invents no optionality for capabilities or extensions.
- `INCOMPATIBLE`: a check failed, constraints conflict, or a required connection/extension/schema/
  version/capability requirement is unsupported.
- `UNKNOWN`: required compatibility cannot be concluded because declared runtime/provider-version
  evidence is absent.
- `MISSING_ADAPTER`: the provider definition and manifest remain understood, but no adapter
  descriptor candidate was supplied. Adapter identity/version/runtime/capability checks explicitly
  retain the missing state; manifest validity is unchanged.

Detailed finding codes include canonical equivalents of `UNSUPPORTED_VERSION`,
`UNSUPPORTED_CAPABILITY`, `UNSUPPORTED_CONNECTION_METHOD`, and `CONFLICTING_CONSTRAINT`. Overall
status is derived from typed checks rather than an unexplained Boolean. Hard incompatibilities take
precedence; otherwise missing adapter, unknown evidence, and optional gaps remain distinct.

## Determinism and Developer Mode readiness

Requests defensively copy and canonically order connection, extension, and provider-version input.
Reports order dimensions by the closed enum and then by semantic check ID. Required/observed values
and evidence references are sorted. Equivalent inputs therefore produce equivalent report views,
independent of input order.

The `MISSING_ADAPTER` finding records the provider definition and required H010 adapter ID, so a
future Developer Mode layer can translate it into a development need such as "adapter missing for
provider X." H024 does not create a project, generate code, install a package, choose a source, or
apply source precedence. Manifest and adapter provenance is retained without preferring vendor,
HUGO, local, or Developer Mode sources. Signature/trust-chain verification remains separate.

## Completion evidence

`CompatibilityMatcherTest` covers full compatibility, missing adapter, adapter-version mismatch,
runtime-version mismatch, wrong adapter family, capability-version mismatch, unsupported required
connection method, extension mismatch, disjoint service API constraints, optional-connection partial
compatibility, missing provider-version unknown status, deterministic ordering/equivalent reports,
authority/runtime separation, no registry mutation, and no concrete vendor/transport coupling. The
H019 architecture guard now includes `adapter/compatibility`.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H010-H013/H023/H024 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Scope boundary

H025 has not started. H024 adds no adapter registry or installation, dynamic loading, provider
selection, connection, trust, permission, runtime availability, provider registration, capability
execution, UI, Developer Mode implementation, AI, memory, World Model runtime, Home Assistant,
LOOI runtime rewiring, Android/BLE behavior, or physical-provider behavior.
