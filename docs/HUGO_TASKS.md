# HUGO Post-T032 Execution Backlog

## Rules

- T001-T032 remain historical completed evidence; H-tasks begin after commit `0bcf536cdaae3b5c9c22247bb093ac6fc68cff26`.
- One H-task at a time. No future-task implementation.
- `HW: none` means no physical claim. Any LOOI runtime-path change requires eventual real-LOOI regression.
- Completion evidence must name automated, Android-device, provider, and hardware levels separately.

## Task status

### H001 - Accept V2 baseline

- **Status:** DONE
- **Completed:** 2026-08-25
- **Accepted predecessor:** T032 commit `0bcf536cdaae3b5c9c22247bb093ac6fc68cff26`
- **Completion evidence:** [`H001_ARCHITECTURE_ACCEPTANCE.md`](H001_ARCHITECTURE_ACCEPTANCE.md) records the accepted document set, invariants, consistency audit, and passing documentation checks.
- **Runtime/hardware impact:** None. T001-T032 behavior and physical evidence are preserved without reinterpretation or a new hardware claim.
- **Boundary at completion:** H001 included no H002 implementation.

### H002 - Baseline evidence lock

- **Status:** DONE
- **Completed:** 2026-08-25
- **Locked checkpoint:** T032 commit `0bcf536cdaae3b5c9c22247bb093ac6fc68cff26` and complete tree `dfeb86b1627cdc7ee2a05057e9987138d154daaf`.
- **Completion evidence:** [`H002_BASELINE_EVIDENCE_LOCK.md`](H002_BASELINE_EVIDENCE_LOCK.md) documents the machine-readable manifest, immutable Git references, strict parser tests, and passing guards.
- **Runtime/hardware impact:** None. Historical documents and fixtures are referenced by their original blobs, not rewritten or re-signed.
- **Boundary at completion:** H002 included no H003 implementation.

### H003 - Hugo identity contract

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H003_HUGO_IDENTITY_CONTRACT.md`](H003_HUGO_IDENTITY_CONTRACT.md) records the platform-neutral identity value, strict codec, typed failures, deterministic tests, and scope boundary. Future product concepts are preserved separately in [`HUGO_FUTURE_CONCEPTS.md`](HUGO_FUTURE_CONCEPTS.md).
- **Runtime/hardware impact:** None. The contract is not persisted or wired into the application, and no hardware behavior is changed or claimed.
- **Boundary at completion:** H003 included no H004 implementation.

### H004 - Provider identity/type

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H004_PROVIDER_IDENTITY_TYPE.md`](H004_PROVIDER_IDENTITY_TYPE.md) records distinct provider definition/private-instance identities, exact provider families, redacted aliases, strict round-trip tests, and scope boundaries. [`WORLD_MODEL_AND_AFFORDANCES.md`](WORLD_MODEL_AND_AFFORDANCES.md) records the accepted future World/Affordance architecture without runtime implementation.
- **Runtime/hardware impact:** None. No discovery, persistence, provider runtime, World Model runtime, or hardware behavior is added.
- **Boundary at completion:** H004 included no H005 implementation.

### H005 - Capability identity/version

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H005_CAPABILITY_IDENTITY_VERSION.md`](H005_CAPABILITY_IDENTITY_VERSION.md) records semantic capability identity, explicit major/minor compatibility, boundary/property tests, and scope boundaries. The accepted future Home Core/multi-host direction is recorded canonically in [`HUGO_FUTURE_CONCEPTS.md`](HUGO_FUTURE_CONCEPTS.md#19-hugo-home-core-and-multi-host-runtime) without implementation.
- **Runtime/hardware impact:** None. No capability is registered or executed and no distributed runtime is added.
- **Boundary at completion:** H005 included no H006 implementation.

### H006 - Capability descriptor

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H006_CAPABILITY_DESCRIPTOR.md`](H006_CAPABILITY_DESCRIPTOR.md) records provider-neutral descriptor fields, strict conflict/limit/metadata validation, typed corpus tests, reuse boundaries, and non-goals.
- **Runtime/hardware impact:** None. Descriptors are not registered, authorized, or executed, and no hardware behavior changes.
- **Boundary at completion:** H006 included no H007 implementation.

### H007 - Request/result envelopes

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H007_REQUEST_RESULT_ENVELOPES.md`](H007_REQUEST_RESULT_ENVELOPES.md) records strict provider-neutral request/result contracts, typed lifecycle/failures, monotonic deadlines, deterministic correlation, serialization traces, tests, and scope boundaries.
- **Runtime/hardware impact:** None. Envelopes are not routed or executed and no hardware behavior changes.
- **Next boundary:** H008 has not started.

### H008 - CapabilityProvider port

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H008_CAPABILITY_PROVIDER_PORT.md`](H008_CAPABILITY_PROVIDER_PORT.md) records the pure universal provider port, lifecycle snapshot boundary, semantic negotiation, descriptor registration reporting, H007-correlated execute/cancel interaction, fake body/device/service contract tests, and explicit non-goals.
- **Runtime/hardware impact:** None. No adapter, LOOI, Android, transport, authorization, safety, or physical-world behavior changes.
- **Next boundary:** H009 has not started.

### H009 - Provider lifecycle reducer

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H009_PROVIDER_LIFECYCLE_REDUCER.md`](H009_PROVIDER_LIFECYCLE_REDUCER.md) records the pure provider lifecycle state/event model, legal transitions, generation ownership, deterministic stale/invalid-event handling, cancellation/disconnect semantics, typed failures, and reducer tests.
- **Runtime/hardware impact:** None. The reducer performs no connection, reconnect, registration, execution, authorization, safety, or LOOI behavior.
- **Next boundary:** H010 has not started.

### H010 - Common manifest schema

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H010_COMMON_MANIFEST_SCHEMA.md`](H010_COMMON_MANIFEST_SCHEMA.md) records the minimal common provider envelope, canonical digest, provenance, compatibility/version rules, closed-field/explicit-extension behavior, specialization boundaries, machine-readable schema, strict codec, and valid/invalid corpus tests.
- **Runtime/hardware impact:** None. No discovery, adapter loading, trust, authorization, registry, connection, LOOI, World Model runtime, or provider execution behavior was added.
- **Next boundary:** H011 has not started.

### H011 - Specialized manifests

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H011_SPECIALIZED_MANIFESTS.md`](H011_SPECIALIZED_MANIFESTS.md) records H010-bound Body/Device/Service manifest claims, family validation, independent schema/digest behavior, World Model geometry references, service secret exclusions, strict codec behavior, and contract tests.
- **Runtime/hardware impact:** None. No LOOI mapping, adapter, discovery, registry, connection, authorization, safety, World Model runtime, or provider behavior was added.
- **Next boundary:** H012 has not started.

### H012 - Adapter identity / integrity

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H012_ADAPTER_IDENTITY_INTEGRITY.md`](H012_ADAPTER_IDENTITY_INTEGRITY.md) records the provider-neutral adapter descriptor, strict identity/version/digest rules, deterministic integrity comparison, manifest compatibility matcher, provenance semantics, and JVM contract tests.
- **Runtime/hardware impact:** None. No adapter is loaded, installed, trusted, authorized, connected, or executed. The verified LOOI runtime and T001-T032 evidence remain unchanged.
- **Next boundary:** H013 has not started.

### H013 - Connection-provider contract

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H013_CONNECTION_PROVIDER_CONTRACT.md`](H013_CONNECTION_PROVIDER_CONTRACT.md) records the pure connection port, request/generation/session correlation, deterministic lifecycle reducer, typed failures, strict validation, and contract tests.
- **Runtime/hardware impact:** None. No BLE, network, SDK, LOOI, connection, authorization, or safety runtime implementation changed.
- **Next boundary:** H014 has not started.

### H014 - Trust/permission domain

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H014_TRUST_PERMISSION_DOMAIN.md`](H014_TRUST_PERMISSION_DOMAIN.md) records provider-instance trust, capability-specific Observe/Act/Modify grants, approval modes, revocation/expiry, host/presence restrictions, deterministic policy evaluation, and contract tests.
- **Runtime/hardware impact:** None. No dispatch gate, storage, UI, registry, credential, LOOI, safety, connection, or provider runtime behavior changed.
- **Next boundary:** H015 is complete; H016 is eligible.

### H015 - Provider registry port

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H015_PROVIDER_REGISTRY_PORT.md`](H015_PROVIDER_REGISTRY_PORT.md) records the provider-neutral registry port, immutable normalized records, exact family/capability candidate queries, H008/H009 generation and revision protection, and atomic stale-session unregister behavior.
- **Runtime/hardware impact:** None. No discovery, connection, trust/permission decision, dispatch, persistence, LOOI, Android/BLE, safety, or provider runtime behavior changed.
- **Next boundary:** H016 is complete; H017 is eligible.

### H016 - Active-body concept

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H016_ACTIVE_BODY_CONCEPT.md`](H016_ACTIVE_BODY_CONCEPT.md) records the provider-neutral, expiring active-body context; immutable embodiment evidence; mounting policy; explicit remote/mounted selection; generation handling; and deterministic detach, unavailable, and expiry clearing.
- **Runtime/hardware impact:** None. No active-body auto-selection, permission or safety authority, discovery, connection, LOOI attachment-sensor interpretation, Android/BLE, World Model, or verified runtime behavior changed.
- **Next boundary:** H017 is complete; H018 is eligible.

### H017 - LOOI capability mapping

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H017_LOOI_CAPABILITY_MAPPING.md`](H017_LOOI_CAPABILITY_MAPPING.md) records the exact evidence-backed semantic LOOI capability ledger, existing implementation boundaries, descriptor metadata, unsupported omissions, and protocol-leakage/safety-preservation tests.
- **Runtime/hardware impact:** None. No `CapabilityProvider` wrapper, dispatch, manual-control path, protocol, Android/BLE, safety, dead-man, E-stop, authority lease, sensor gate, or physical LOOI behavior changed. T032 remains the physical acceptance record; no new hardware regression claim is made.
- **Next boundary:** H018 is complete; H019 is eligible.

### H018 - LOOI compatibility facade

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H018_LOOI_COMPATIBILITY_FACADE.md`](H018_LOOI_COMPATIBILITY_FACADE.md) records the adapter-side H008 facade, exact H017 routing, H007 correlation/failure mapping, safety-owned runtime port, stop priority, and fake-delegation/leakage tests.
- **Runtime/hardware impact:** None. The verified T032 manual path is not rewired or replaced; no LOOI runtime, protocol, Android/BLE, safety, dead-man, E-stop, authority lease, sensor gate, or physical behavior changed. No new hardware regression claim is made.
- **Next boundary:** H019 is complete; H020 is eligible.

### H019 - V2 dependency guards

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H019_V2_DEPENDENCY_GUARDS.md`](H019_V2_DEPENDENCY_GUARDS.md) records the T006 guard extension, protected pure-package directions, deterministic negative/positive fixtures, World Model runtime-coupling guard, and required validation set.
- **Runtime/hardware impact:** None. The guards inspect source and documentation only; no LOOI runtime, Android BLE, safety, manual-control path, or physical behavior changed. No new device or real-LOOI claim is made.
- **Next boundary:** H020 is complete; H021 is eligible.

### H020 - M2.5 regression acceptance

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H020_M25_REGRESSION_ACCEPTANCE.md`](H020_M25_REGRESSION_ACCEPTANCE.md) records the H001-H019 documentation/baseline/architecture/build validation, legacy T032-path and shadow-registration audit, hardware-verification decision, and acceptance boundary.
- **Runtime/hardware impact:** None. No M2.5 change altered the verified manual LOOI runtime path, so the H020 backlog condition for a new real-device regression did not apply. T032 remains the locked physical acceptance evidence; H020 makes no new hardware claim.
- **Next boundary:** H021 is complete; H022 is eligible.

### H021 - Discovery observation

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H021_DISCOVERY_OBSERVATION.md`](H021_DISCOVERY_OBSERVATION.md) records the passive provider-neutral observation schema, strict protected codec, privacy/redaction/freshness rules, H019 guard extension, and deterministic contract tests.
- **Runtime/hardware impact:** None. No BLE/LAN/service discovery, identity resolution, connection, registration, LOOI runtime, Android API, or physical behavior is added or claimed.
- **Next boundary:** H022 is complete; H023 is eligible.

### H022 - Identity resolver

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H022_IDENTITY_RESOLVER.md`](H022_IDENTITY_RESOLVER.md) records the pure deterministic resolver, fixed evidence-strength and verification rules, exact provenance/freshness validation, protected identifier boundary, conservative known/new/ambiguous/conflict outcomes, strict protected codec, and read-only H015 identity projection.
- **Runtime/hardware impact:** None. No scanner, provider registration/merge, manifest or adapter lookup, trust/authorization, connection, UI, AI reasoning, Home Assistant, LOOI rewiring, Android API, or physical behavior is added or claimed.
- **Next boundary:** H023 is complete; H024 is eligible.

### H023 - Manifest resolver

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H023_MANIFEST_RESOLVER.md`](H023_MANIFEST_RESOLVER.md) records the pure identity-bound manifest resolver, explicit candidate acquisition and embedded provenance, strict H010/H011 decode and binding validation, H012 adapter compatibility reuse, conservative ambiguity/conflict behavior, and authority/runtime separation.
- **Runtime/hardware impact:** None. No network lookup, adapter installation/selection/loading, provider registration, trust/authorization, connection, execution, UI, AI, memory, Home Assistant, LOOI runtime, Android/BLE, or physical behavior is added or claimed.
- **Next boundary:** H024 is complete; H025 is eligible.

### H024 - Compatibility matcher

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H024_COMPATIBILITY_MATCHER.md`](H024_COMPATIBILITY_MATCHER.md) records the pure typed compatibility matrix, separately explained schema/identity/family/adapter/runtime/capability/connection/extension/constraint dimensions, evidence/provenance traces, exact deterministic matching, missing-adapter and partial/unknown semantics, and strict authority/runtime separation.
- **Runtime/hardware impact:** None. No adapter install/selection/loading, registry, provider selection, connection, trust/permission, availability, registration, execution, UI, Developer Mode implementation, LOOI runtime, Android/BLE, or physical behavior is added or claimed.
- **Next boundary:** H025 is complete; H026 is eligible.

### H025 - Adapter registry domain

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H025_ADAPTER_REGISTRY_DOMAIN.md`](H025_ADAPTER_REGISTRY_DOMAIN.md) records the immutable revisioned adapter knowledge index, H012-native identity/version/digest/provenance records, deterministic coexistence and conflict rules, explicit compare-and-set replacement/removal, family/capability/exact queries, and direct H024 compatibility-report reuse.
- **Runtime/hardware impact:** None. Registry presence does not imply installation, integrity verification, trust, enablement, loading, availability, authorization, connection, registration, selection, or execution. No LOOI runtime or physical behavior is changed.
- **Next boundary:** H026 is complete; H027 is eligible.

### H026 - Registry persistence / integrity

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H026_REGISTRY_PERSISTENCE_INTEGRITY.md`](H026_REGISTRY_PERSISTENCE_INTEGRITY.md) records the canonical versioned snapshot codec, exact H012/H025 record and revision restore, whole-snapshot metadata digest, fail-closed schema/corruption/duplicate handling, protected-storage port, atomic primary/rollback/backup protocol, verified-backup recovery evidence, and explicit repair/reset behavior.
- **Runtime/hardware impact:** None. Persisted registry knowledge does not imply installation, artifact verification, trust, enablement, loading, availability, authorization, selection, connection, registration, or execution. No LOOI runtime or physical behavior is changed.
- **Next boundary:** H027 is complete; H028 is eligible.

### H027 - Provider lifecycle orchestrator

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H027_PROVIDER_LIFECYCLE_ORCHESTRATOR.md`](H027_PROVIDER_LIFECYCLE_ORCHESTRATOR.md) records the explicit correlated preparation phases, narrow H022/H023/H024 stage ports, immutable H015/H025 registry inputs, H009 discovery/identity/cancellation reuse, deterministic missing/ambiguous/blocked outcomes, late-result rejection, and strict pre-trust/pre-connection/pre-registration boundary.
- **Runtime/hardware impact:** None. H027 does not infer trust/permission, connect, register a provider, negotiate/register/execute capabilities, select an active body, or change LOOI runtime or physical behavior.
- **Next boundary:** H028 is complete; H029 is eligible.

### H028 - BLE connection-provider wrapper

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H028_BLE_CONNECTION_PROVIDER_WRAPPER.md`](H028_BLE_CONNECTION_PROVIDER_WRAPPER.md) records the unchanged H013 implementation, opaque endpoint resolver, vocabulary-only bridge to the verified GATT adapter, exact generation/correlation mapping, confirmed-channel semantics, typed/redacted failure evidence, idempotent cleanup, Android composition boundary, and strict pre-trust/pre-registration/pre-capability boundary.
- **Runtime/hardware impact:** None. No existing LOOI production path, Android GATT implementation, scan/notification/protocol/control/safety behavior, provider registration, trust/permission authority, active-body state, or physical behavior was changed. A real-LOOI regression was not required and no new hardware claim is made.
- **Next boundary:** H029 is complete; H030 is eligible.

### H029 - Capability negotiation

- **Status:** DONE
- **Completed:** 2026-08-25
- **Completion evidence:** [`H029_CAPABILITY_NEGOTIATION.md`](H029_CAPABILITY_NEGOTIATION.md) records the deterministic H023/H024/H027 preparation, H005/H006 descriptor and version, H008 runtime, H009 lifecycle, H013 session, and read-only H015 registry intersection; explicit negotiated/partial/unsupported/stale/missing/conflicting outcomes; runtime-limit narrowing; canonical evidence ordering; and strict separation from H014 authority, safety, feasibility, selection, registration, and execution.
- **Runtime/hardware impact:** None. H029 is a pure evidence matcher. No provider/adapter registry mutation, authorization, connection, capability execution, active-body selection, LOOI runtime, Android/BLE, safety, or physical behavior is changed. No new hardware regression claim is made.
- **Next boundary:** H030 is complete; H031 is eligible.

### H030 - Authorization gate runtime

- **Status:** DONE
- **Completed:** 2026-08-26
- **Completion evidence:** [`H030_AUTHORIZATION_GATE_RUNTIME.md`](H030_AUTHORIZATION_GATE_RUNTIME.md) records exact H007 request/provider binding, fresh H029 capability/generation/session validation, direct H014 trust/grant/approval evaluation, H016-derived contextual restrictions, atomic one-time/per-request approval consumption, typed fail-closed decisions, canonical bounded audit evidence, and strict separation from safety, feasibility, dispatch, and execution.
- **Runtime/hardware impact:** None. H030 is an unwired provider-neutral gate. No provider execution, capability routing, approval UI, LOOI runtime, Android/BLE, deterministic safety, active-body selection, or physical behavior was changed. No hardware regression is required or claimed.
- **Next boundary:** H031 is complete; H032 is eligible.

### H031 - Calibration contract

- **Status:** DONE
- **Completed:** 2026-08-26
- **Completion evidence:** [`H031_CALIBRATION_CONTRACT.md`](H031_CALIBRATION_CONTRACT.md) records instance-bound calibration profiles, H011 requirement references, adapter-owned procedure references, typed bounded measurements, explicit evidence/verification hierarchy, deterministic validation/status/invalidation, H029 capability-readiness projection, verified World Model fact boundary, and strict separation from memory, authorization, safety, feasibility, and execution.
- **Runtime/hardware impact:** None. H031 is an unwired provider-neutral domain and pure validator. No manifest schema, provider store, calibration procedure, World Model runtime, LOOI runtime, Android/BLE, deterministic safety, or physical behavior was changed. No hardware regression is required or claimed.
- **Next boundary:** H032 is complete; H033 is eligible.

### H032 - Provider-instance store

- **Status:** DONE
- **Completed:** 2026-08-26
- **Completion evidence:** [`H032_PROVIDER_INSTANCE_STORE.md`](H032_PROVIDER_INSTANCE_STORE.md) records the versioned immutable identity/configuration records, optimistic store/record revisions, H022-safe redacted projection, AES-256-GCM protection port, Android Keystore key boundary, atomic primary/backup persistence, explicit fail-closed recovery/reset behavior, and strict separation from provider registry state, trust authority, grants, credentials, calibration payloads, memory, connection state, and active-body state.
- **Runtime/hardware impact:** None. H032 is not composed into discovery, registration, trust, connection, calibration, capability execution, active-body, or LOOI production paths. The Android implementation adds application-private protected storage only; no BLE, protocol, safety, or physical behavior changed. No hardware regression is required or claimed.
- **Next boundary:** H033 is complete; H034 is eligible.

### H033 - Unknown fail-closed UX

- **Status:** DONE
- **Completed:** 2026-08-26
- **Completion evidence:** [`H033_UNKNOWN_FAIL_CLOSED_UX.md`](H033_UNKNOWN_FAIL_CLOSED_UX.md) records the typed H022-H032 evidence adapters, deterministic blocked-state mapper, immutable explanatory UI state, privacy filtering, control-free reusable Compose card, honest generic fallback, future Control Center reuse boundary, and strict separation from provider logic, remediation workflows, connection, authorization, calibration procedures, and execution.
- **Runtime/hardware impact:** None. H033 adds an unwired reusable presentation component and pure mapper. It does not alter app navigation, provider orchestration, BLE, LOOI runtime, capability execution, safety, or physical behavior. Because no real screen is reachable in the debug app, Samsung presentation/navigation verification was not required or claimed.
- **Next boundary:** H034 is complete; H035 is eligible.

### H034 - Provider diagnostics/export

- **Status:** DONE
- **Completed:** 2026-08-26
- **Completion evidence:** [`H034_PROVIDER_DIAGNOSTICS_EXPORT.md`](H034_PROVIDER_DIAGNOSTICS_EXPORT.md) records the bounded versioned redacted snapshot, strict deterministic codec, H021-H033 safe projections, one-way export-local correlation labels, typed H024 findings, semantic capability list, H033 bridge without a parallel blocked taxonomy, calibration-reference-only boundary, portable fixture, and strict non-authority/non-protocol export semantics.
- **Runtime/hardware impact:** None. H034 is an unwired provider-neutral diagnostic/export contract and codec. It adds no Android UI, logging, upload, provider control, BLE/GATT inspection, LOOI runtime, safety, or physical behavior. No device or hardware verification is required or claimed.
- **Next boundary:** H035 is complete; H036 is eligible.

### H035 - M3 acceptance

- **Status:** DONE
- **Completed:** 2026-08-26
- **Completion evidence:** [`H035_M3_ACCEPTANCE.md`](H035_M3_ACCEPTANCE.md) records the complete H021-H034 deterministic-flow audit, BODY/DEVICE/SERVICE fake-provider scenarios, exact H017/H018 LOOI semantic-capability audit, typed fail-closed fault matrix, protected provider-instance continuity, privacy/security scans, full JVM/lint/assemble evidence, and the decision to retain T032 as physical authority because its production path is unchanged.
- **Runtime/hardware impact:** None. H035 adds acceptance tests and documentation only. No provider is composed, connected, selected, authorized, registered, or executed; no Android, BLE/GATT, LOOI, safety, manual-control, or physical behavior changes. A repeated hardware run was not required.
- **Milestone result:** M3 is accepted and ready to freeze.
- **Next boundary:** H036 is complete; H037 is eligible.

### H036 - Context observation contract

- **Status:** DONE
- **Completed:** 2026-08-31
- **Completion evidence:** [`H036_CONTEXT_OBSERVATION_CONTRACT.md`](H036_CONTEXT_OBSERVATION_CONTRACT.md) records the bounded source/freshness/privacy/confidence envelope, explicit known/unknown/conflict claims, deterministic strict schema codec, typed fail-closed validation, and separation from memory, facts, trust, authorization, safety, selection, connection, and execution.
- **Runtime/hardware impact:** None. H036 adds a pure provider-neutral `context/api` contract, codec, and JVM tests only. No source adapter, persistence, snapshot runtime, First Life composition, provider runtime, Android/BLE, LOOI, safety, manual-control, or physical behavior changed. Device/hardware verification was not required or claimed.
- **Next boundary:** H037 is complete; H038 is eligible.

### H037 - Context snapshot engine

- **Status:** DONE
- **Completed:** 2026-08-31
- **Completion evidence:** [`H037_CONTEXT_SNAPSHOT_ENGINE.md`](H037_CONTEXT_SNAPSHOT_ENGINE.md) records deterministic de-duplication, virtual-time freshness/expiry, latest-per-source normalization, explicit unknown/conflict retention, privacy/key scope, whole-item selection, hard item/evidence/UTF-8 budgets, canonical ephemeral projection, and strict non-memory/non-authority boundaries.
- **Runtime/hardware impact:** None. H037 adds an unwired pure `context/runtime` reducer, model, projection codec, fixture, and JVM tests only. No source adapter, persistence, First Life composition, provider runtime, Android/BLE, LOOI, safety, manual-control, or physical behavior changed. Device/hardware verification was not required or claimed.
- **Next boundary:** H038 is complete; H039 is eligible.

### H038 - Active-provider context

- **Status:** DONE
- **Completed:** 2026-08-31
- **Completion evidence:** [`H038_ACTIVE_PROVIDER_CONTEXT.md`](H038_ACTIVE_PROVIDER_CONTEXT.md) records the read-only H015/H016 projection, strict connected/available/degraded semantics, explicit selected-versus-active body state, host and service context, revision/expiry handling, bounded private H037 aggregate snapshot, portable state trace, and non-authorization/non-execution boundaries.
- **Runtime/hardware impact:** None. H038 adds an unwired pure `context/provider` reducer, projection, fixture, and JVM tests only. No provider registry/lifecycle mutation, active-body selection mutation, source adapter, persistence, First Life composition, Android/BLE, LOOI, safety, manual-control, or physical behavior changed. Device/hardware verification was not required or claimed.
- **Next boundary:** H039 is complete; H040 is eligible.

### H039 - Phone sensor DeviceAdapter

- **Status:** DONE
- **Completed:** 2026-08-31
- **Completion evidence:** [`H039_PHONE_SENSOR_DEVICE_ADAPTER.md`](H039_PHONE_SENSOR_DEVICE_ADAPTER.md) records the Android-free generation-owned source adapter, bounded accelerometer/light/proximity observations, explicit active/unavailable sensor partition, exact H036 freshness and H037 expiry behavior, deterministic stale-callback rejection, idempotent resource release, privacy/non-authority boundary, fake tests, and redacted Samsung `SM-S928B` instrumented device trace.
- **Runtime/hardware impact:** H039 adds an unwired project-domain adapter and Android `SensorManager` backend. It adds no permission, source fusion, navigation, persistence, provider lifecycle, LOOI, safety, manual-control, or physical-execution behavior. Samsung verification confirmed genuine sensor acquisition and resource release; no LOOI verification was required or claimed.
- **Next boundary:** H040 is complete; H041 is eligible.

### H040 - ServiceConnector contract

- **Status:** DONE
- **Completed:** 2026-08-31
- **Completion evidence:** [`H040_SERVICE_CONNECTOR_CONTRACT.md`](H040_SERVICE_CONNECTOR_CONTRACT.md) records the H008-derived service role, versioned auth/scope/operation/rate declarations, opaque generation-owned auth-session evidence, H013 connection availability/freshness binding, typed fail-closed operation assessment and error/retry model, strict H014 non-authority separation, fake capability registration, and deterministic redacted trace fixture.
- **Runtime/hardware impact:** None. H040 adds an unwired pure `service/api` contract, evaluator, fake tests, and fixture only. It adds no credential, token, account, network client, vendor SDK, real connector, runtime registration, authorization, UI, LOOI, safety, or physical behavior. No device or service-account verification is required or claimed.
- **Next boundary:** H041 is complete; H042 is eligible.

### H041 - Connector credential boundary

- **Status:** DONE
- **Completed:** 2026-08-31
- **Completion evidence:** [`H041_CONNECTOR_CREDENTIAL_BOUNDARY.md`](H041_CONNECTOR_CREDENTIAL_BOUNDARY.md) records the protected credential lifecycle, opaque local identity and revision model, short-lived zeroized byte access, deterministic versioned binary document, AES-256-GCM Android Keystore envelope, atomic no-backup storage, explicit logout/revoke/reset behavior, fail-closed expiry/corruption/version handling, non-authority semantics, secret scans, threat analysis, and redacted Samsung `SM-S928B` instrumentation evidence.
- **Runtime/hardware impact:** None. H041 is an unwired credential storage boundary and isolated Android storage adapter. It adds no OAuth/provider choice, account, production credential, network request, connector composition, service authentication claim, H014 grant, UI, LOOI, safety, manual-control, or execution behavior. Samsung verification exercised only isolated randomly named test stores and cleaned them afterward.
- **Next boundary:** H042 is complete; H043 is eligible.

### H042 - Control Center shell

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H042_CONTROL_CENTER_SHELL.md`](H042_CONTROL_CENTER_SHELL.md) records the immutable five-section read-only shell, canonical H033/H034/H038/H040/H041/H014 projection, one-way redacted item references, explicit provider/auth/credential/capability/grant/approval/diagnostic states, deterministic source-conflict rejection, empty/error/revoked usability, non-authority/non-execution boundary, UI/state tests, and Samsung screen evidence.
- **Runtime/hardware impact:** None. H042 adds an isolated Compose shell and read-only projector; it is not wired into the production HiHugo route. It adds no source editor, provider/account setup, OAuth flow, trust/grant/approval mutation, connection, credential access, dispatch, LOOI, safety, manual-control, or physical behavior. Samsung verification rendered fictional data only.
- **Next boundary:** H043 is complete; H044 is eligible.

### H043 - Read-only connector feasibility

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H043_READ_ONLY_CONNECTOR_FEASIBILITY.md`](H043_READ_ONLY_CONNECTOR_FEASIBILITY.md) records ADR-019, the isolated Google Calendar events read-only adapter, exact single-scope OAuth profile, H040/H041 composition, bounded GET-only observation, typed fail-closed errors, remote-before-local revocation, deterministic privacy evidence, quota/cost boundary, and redacted Samsung `SM-S928B` authorized-account connect/read/revoke evidence.
- **Runtime/hardware impact:** No production or LOOI impact. H043 adds an isolated debug-only Android authorization/HTTP harness and an unwired adapter-specific domain sandbox. It does not enter the production HiHugo route, background work, provider registry, H014 grants, LOOI connection, safety, manual control, or capability execution. One authorized Calendar read was bounded to ten results and seven days; payload content was discarded and access was revoked.
- **Next boundary:** H044 is complete; H045 is eligible.

### H044 - Time/presence context sources

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H044_TIME_PRESENCE_CONTEXT_SOURCES.md`](H044_TIME_PRESENCE_CONTEXT_SOURCES.md) records the deterministic minute-bounded local-time projection, one-shot foreground-only coarse authorized-region source, minimum three-kilometre region and accuracy-envelope semantics, explicit restricted privacy/freshness, denial/unavailable/timeout/stale unknown behavior, no-history/non-authority boundary, pure virtual-time coverage, and redacted Samsung `SM-S928B` context trace.
- **Runtime/hardware impact:** No production or LOOI runtime path is wired. H044 declares approximate foreground location but adds no production permission request, background location, tracking, persistence, provider/body composition, safety decision, or execution. Samsung verification produced fresh time evidence and correctly remained `Unknown(SOURCE_UNAVAILABLE)` when Android supplied no current coarse fix; the temporary test grant was revoked.
- **Next boundary:** H045 is complete; H046 is eligible.

### H045 - M4 acceptance

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H045_M4_ACCEPTANCE.md`](H045_M4_ACCEPTANCE.md) records the H036-H044 context/privacy/connector/control-center audit, deterministic privacy and whole-item budget behavior, exact expiry and unknown semantics, H040/H041/H043 count-only read plus remote-before-local revoke, hidden-persistence/background/action scans, unchanged accepted H043 account path, eight-test Samsung regression, redacted evidence, risks, and M4 acceptance decision.
- **Runtime/hardware impact:** None. H045 adds acceptance tests/evidence only. The Samsung rerun exercised isolated Keystore, read-only UI, phone sensors, and a bounded coarse presence attempt; the temporary permission was revoked. No new Calendar request, provider/body connection, LOOI behavior, safety path, physical action, AI, voice, or memory was added or exercised.
- **Next boundary:** H046 is complete; H047 is eligible.

### H046 - Local intent router

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H046_LOCAL_INTENT_ROUTER.md`](H046_LOCAL_INTENT_ROUTER.md) records the immutable versioned `sq-AL`/`en-US` exact-phrase corpus, narrow bounded normalization, typed existing-capability proposals, deterministic stop priority, ambiguity-with-no-proposal behavior, redacted evidence, adversarial near-match/fuzz coverage, and strict non-authority/non-execution boundary.
- **Runtime/hardware impact:** None. H046 is an unwired pure matcher. It adds no H007 request, provider selection, trust, authorization, safety approval, dispatch, execution, Android/voice/network/storage dependency, LOOI runtime, or physical behavior. No device or hardware verification is required or claimed.
- **Next boundary:** H047 is complete; H048 is eligible.

### H047 - Audio session reducer

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H047_AUDIO_SESSION_REDUCER.md`](H047_AUDIO_SESSION_REDUCER.md) records the pure correlated activation/listening/thinking/speaking lifecycle, cancellation-first and exactly-once release semantics, bounded monotonic phase/overall deadlines, typed completion/cancellation/failure/timeout evidence, stale and out-of-order rejection, content-free privacy boundary, transition/virtual-time coverage, and architecture guard extension.
- **Runtime/hardware impact:** None. H047 is an unwired reducer/effect contract. It adds no microphone capture, audio focus, STT/TTS implementation, transcript retention, provider/AI/network dependency, authority, capability execution, Android resource, LOOI runtime, or physical behavior. No device or hardware verification is required or claimed.
- **Next boundary:** H048 is complete; H049 is eligible.

### H048 - Explicit capture/local VAD

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H048_EXPLICIT_CAPTURE_LOCAL_VAD.md`](H048_EXPLICIT_CAPTURE_LOCAL_VAD.md) records the explicit correlated PTT contract, bounded ephemeral zeroed PCM frames, deterministic local energy VAD, typed denial/failure/stop evidence, Android `AudioRecord` isolation, permission and exactly-once release behavior, non-retention/non-upload boundary, JVM coverage, and redacted Samsung `SM-S928B` device evidence.
- **Runtime/hardware impact:** H048 adds an unwired capture domain and isolated Android microphone adapter. It does not alter the production First Life voice route, start background capture, retain/upload audio, recognize speech, select a provider, invoke AI or capabilities, connect LOOI, or change safety/manual-control behavior. Samsung verification exercised one bounded 800 ms foreground capture; the temporary microphone grant was revoked afterward.
- **Next boundary:** H049 is complete; H050 is eligible.

### H049 - Speech adapter contracts

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H049_SPEECH_ADAPTER_CONTRACTS.md`](H049_SPEECH_ADAPTER_CONTRACTS.md) records the provider-neutral correlated STT/TTS ports, bounded one-shot transcript envelope, H048 capture contract reuse, H046 local-first routing, typed audio-focus ownership, cancellation/deadline/stale-callback handling, deterministic content-free conversation trace, and fake integration coverage.
- **Runtime/hardware impact:** None. H049 is an unwired pure contract and fake coordinator. It adds no Android speech/audio-focus implementation, production STT/TTS choice, credential, key, network request, provider selection, capability request/authorization/execution, transcript/audio retention, First Life path change, LOOI behavior, safety change, or physical action. Device verification is not required or claimed.
- **Next boundary:** H050 is complete; H051 is eligible.

### H050 - Capability-backed skill registry

- **Status:** DONE
- **Completed:** 2026-09-01
- **Completion evidence:** [`H050_CAPABILITY_BACKED_SKILL_REGISTRY.md`](H050_CAPABILITY_BACKED_SKILL_REGISTRY.md) records the versioned immutable skill schema/registry, reviewed connection-status/lower-contact-status/light-off catalog, exact H007 request plus H030 decision binding, bounded semantic dispatch lifecycle, cancellation/timeout/unknown evidence, fixed-input and output-contract validation, bounded replay ledger, redacted deterministic trace, and fake integration coverage.
- **Runtime/hardware impact:** None. H050 is an unwired pure registry/executor using fake dispatch only. It creates no H007 request or H030 authority, selects no provider, loads no adapter, adds no motion skill, invokes no production capability, and changes no Android, First Life, LOOI, T032 safety, manual-control, or physical behavior. Device verification is not required or claimed.
- **Next boundary:** H051 is complete; H052 is eligible.

### H051 - AI gateway contract

- **Status:** DONE
- **Completed:** 2026-09-02
- **Completion evidence:** [`H051_AI_GATEWAY_CONTRACT.md`](H051_AI_GATEWAY_CONTRACT.md) records the provider-neutral `1.0` JSON Schema/OpenAPI artifact, strict Kotlin DTO codec, bounded text/realtime/vision/embedding request shapes, exact cancellation correlation, deterministic streamed-event ordering, typed terminal/error/refusal outcomes, measured/estimated/unknown usage and optional cost evidence, H050 skill proposals without authority, portable examples, and schema/round-trip coverage.
- **Runtime/hardware impact:** None. H051 adds an unwired contract artifact and pure validator only. It adds no gateway backend/client, network request, endpoint or provider selection, authentication, credential, secret, raw media upload, context assembly, AI execution, capability request, authorization, safety decision, LOOI behavior, or physical action. Device verification is not required or claimed.
- **Next boundary:** H052 is complete; H053 is eligible.

### H052 - Gateway authentication

- **Status:** DONE
- **Completed:** 2026-09-02
- **Completion evidence:** [`H052_GATEWAY_AUTHENTICATION.md`](H052_GATEWAY_AUTHENTICATION.md) records the short-lived scoped app-session token model, exact-origin release HTTPS and loopback-only debug policies, strict expiry/audience/scope/revoke/rotation behavior, H041 Keystore/AES-GCM storage reuse with isolated key/file boundaries, release manifest and secret scans, non-authority separation, JVM coverage, and two-test Samsung `SM-S928B` Keystore/tamper acceptance.
- **Runtime/hardware impact:** H052 adds an unwired token repository, protected-storage adapter, and network-security policy only. It configures no backend or production origin, grants no trust/authorization/safety/execution authority, sends no network request, and changes no First Life, provider, LOOI, T032 safety, manual-control, or physical behavior. Samsung verification used only isolated random test credentials and no network, camera, microphone, provider, or LOOI interaction.
- **Next boundary:** H053 is complete; H054 is eligible.

### H053 - Gateway client/reducer

- **Status:** DONE
- **Completed:** 2026-09-02
- **Completion evidence:** [`H053_GATEWAY_CLIENT_REDUCER.md`](H053_GATEWAY_CLIENT_REDUCER.md) records the H051 event reducer, H052-scoped authenticated client, provider-neutral pull-stream port, strict correlation/order and terminal validation, hard frame/text/proposal/embedding/event/time bounds, typed cancellation/timeout/transport/protocol outcomes, exactly-once close, redacted evidence, and deterministic fake-stream integration coverage.
- **Runtime/hardware impact:** None. H053 is an unwired pure client/reducer and transport seam. It configures no HTTP implementation, backend, endpoint, provider, model, context packet, retry, account, media upload, trust, authorization, safety decision, skill/capability execution, Android runtime, First Life path, LOOI behavior, or physical action. Device verification is not required or claimed.
- **Next boundary:** H054 is complete; H055 is eligible.

### H054 - Bounded AI context packet

- **Status:** DONE
- **Completed:** 2026-09-02
- **Completion evidence:** [`H054_BOUNDED_AI_CONTEXT_PACKET.md`](H054_BOUNDED_AI_CONTEXT_PACKET.md) records the canonical current-turn/summary/H037/empty-memory/tool packet, exact H051 correlation and H050 schema binding, hard item/input/UTF-8 bounds, whole-item deterministic admission, sensitive/restricted exclusion, opaque provenance, adversarial serialization tests, non-authority semantics, and memory-retrieval separation.
- **Runtime/hardware impact:** None. H054 is an unwired pure builder/codec with an empty memory adapter. It adds no database, retrieval, history scan, memory write, gateway/model call, backend, provider selection, media upload, authorization, safety decision, tool/capability execution, Android runtime, First Life route, LOOI behavior, or physical action. Device verification is not required or claimed.
- **Next boundary:** H055 is complete; H056 is eligible.

### H055 - Capability proposal validator

- **Status:** DONE
- **Completed:** 2026-09-02
- **Completion evidence:** [`H055_CAPABILITY_PROPOSAL_VALIDATOR.md`](H055_CAPABILITY_PROPOSAL_VALIDATOR.md) records the exact H053/H054/H050/H007/H030 validation chain, strict typed argument/version/provider/negotiation/risk/confirmation checks, hard motion/raw-protocol/e-stop/limit/authority/install denials, bounded non-evicting proposal/request/execution replay ledger, deterministic redacted rejection audit, schema-fuzz and red-team matrix, and zero-dispatch boundary.
- **Runtime/hardware impact:** None. H055 is an unwired pure validator. It adds no skill executor call, capability dispatch, new trust/grant/approval authority, safety decision, provider execution, Android/runtime integration, First Life change, LOOI behavior, T032 path, motion tool, or physical action. Device verification is not required or claimed.
- **Next boundary:** H056 is eligible and has not started.

### H056 - Conversation/barge-in

- **Status:** DONE
- **Completed:** 2026-09-02
- **Completion evidence:** [`H056_CONVERSATION_BARGE_IN.md`](H056_CONVERSATION_BARGE_IN.md) records the pure `conversation/runtime` turn coordinator over H047/H049/H046/H054/H053, explicit-activation gating, local-first routing where local stop wins and non-stop matches stay unexecuted typed outcomes, AMBIGUOUS clarification without gateway escalation, explicit re-activation barge-in that cancels in-flight generation and output, provider-loss cancel/fallback with injected bounded messages, content-free latency traces, and no capability/authorization/memory execution.
- **Runtime impact:** None in production paths. H056 is an unwired pure coordinator plus an isolated instrumented Android-audio verification test; no production speech adapter, network transport, provider, skill execution, authorization, memory, First Life, LOOI, T032, or physical path is changed. The focused Android-audio class passed 3/3 scenarios on the authorized Samsung `SM-S928B` (Android 16, API 36) on 2026-09-03: real bounded `AudioRecord` listen (855 ms) with real local TTS output (788 ms), barge-in cancellation measured at 21 ms, and provider-loss fallback spoken locally; `RECORD_AUDIO` was granted only for the run and revoked afterward. No backend, provider, or LOOI claim is made.
- **Next boundary:** H057 is eligible and has not started.

### H057 - Model policy and M5 acceptance

- **Status:** DONE
- **Completed:** 2026-09-04
- **Completion evidence:** [`H057_MODEL_POLICY_AND_M5_ACCEPTANCE.md`](H057_MODEL_POLICY_AND_M5_ACCEPTANCE.md) records the pure bounded `gateway/policy` policy engine (task eligibility, H051 budget envelopes, H054-narrowing-only context allowance, session/daily usage accounting from H051 usage evidence, locked circuit CLOSED/OPEN/HALF_OPEN semantics), the additive default-preserving H056 generation-policy hook with typed `POLICY_BLOCKED` local fallback, budget/circuit blocks before any transport open, and the androidTest-only loopback configured test gateway acceptance suite. **M5 is accepted** on the complete evidence chain.
- **Runtime impact:** None in production paths. H057 adds no backend, provider SDK or name, model field, credential, retry chain, failover, production transport, memory write, or UI; H052/H053/H054 bounds are untouched and the policy can only narrow them. Local STOP and local routing never consult the policy. On 2026-09-04 the authorized Samsung `SM-S928B` ran the focused configured-gateway suite 3/3 (round trip with measured usage and 6 ms socket barge-in cancellation reaching the gateway path; two post-start `PROTOCOL_ERROR` failures opening the circuit with fail-fast blocking and half-open probe recovery; session exhaustion blocking before transport with local stop intact) and the H056 Android-audio regression 3/3; `RECORD_AUDIO` was granted only for the regression and revoked afterward.
- **Next boundary:** M6/H058 is eligible and has not started.

### H059 - Encrypted local repository

- **Status:** DONE
- **Completed:** 2026-09-04
- **Completion evidence:** [`H059_ENCRYPTED_LOCAL_MEMORY_REPOSITORY.md`](H059_ENCRYPTED_LOCAL_MEMORY_REPOSITORY.md) records the authoritative encrypted per-record memory store over the governed H032/H052 Keystore AES-256-GCM architecture (versioned alias, schema-bound associated data, atomic slots, explicit key-loss purge), the Room/SQLCipher decision with migration report, strict fail-closed decode with typed corrupt/unsupported-version/authentication failures, bounded listing, tombstone persistence without active-state reappearance, and 16 focused JVM store tests. The Android Keystore instrumented suite is implemented and pending device availability; no device result is claimed.
- **Runtime/hardware impact:** None in production paths; the repository is unwired and adds no retrieval, ranking, embeddings, sync, UI, or lifecycle policy. Device verification level: instrumented suite prepared, pending execution on the authorized Samsung `SM-S928B`; JVM fake-crypto evidence is not device evidence.
- **Next boundary:** H060 is eligible and has not started.

### H060 - Working and episodic memory

- **Status:** DONE
- **Completed:** 2026-09-04
- **Completion evidence:** [`H060_WORKING_EPISODIC_MEMORY.md`](H060_WORKING_EPISODIC_MEMORY.md) records the bounded session-scoped `memory/working` buffer (owner-bound, WORKING-layer/ACTIVE/SESSION_SCOPED acceptance filter, deterministic oldest-eviction, explicit `forget`/`endSession` cleanup, bounded content-free record traces), the governed durability boundary (`promote` produces an EPISODIC envelope preserving provenance/candidate-state/privacy unchanged; session retention rejected; persistence remains a separate explicit caller step through the H059 store), and `memory/episodic` typed event creation plus an EPISODIC-disciplined facade over the H059 store with deterministic expiry validation.
- **Runtime/hardware impact:** None. H060 adds no persistence redesign, no automatic durability, no validation/reconciliation policy, no retrieval, summaries, embeddings, ranking, sync, or UI. Device verification is not required or claimed.
- **Next boundary:** H061 is eligible and has not started.

### H061 - Semantic and personal memory

- **Status:** DONE
- **Completed:** 2026-09-04; remediated after independent review and rereview: 2026-09-18
- **Completion evidence:** [`H061_SEMANTIC_PERSONAL_MEMORY.md`](H061_SEMANTIC_PERSONAL_MEMORY.md) records the semantic fact factory with explicit-user-confirmation-only validation authority (model/observed/inferred provenance can never self-validate), the CAS-style confirmation lifecycle (expired retention and unresolved conflicts fail closed; a stale transition can never resurrect a concurrently changed record), target-aware user corrections for both SEMANTIC and PERSONAL records bound to the factory owner (a model may never correct; successors preserve the target's owner, layer, type, scope, subjects, privacy, allowed use, and retention), public correction idempotency (the successor record id is the stable operation identity bound to a domain-separated SHA-256 payload digest; an exact retry after a lost acknowledgment or restart returns an explicit AlreadyApplied outcome; a reused identity with a different payload fails closed as IDEMPOTENCY_CONFLICT), and correction successor + SUPERSEDED target committed as one all-or-nothing crash-safe recoverable idempotent H059 change-set behind a mandatory pending-journal gate, with a strict authenticated replay preflight (schema version, exact fields, size bounds enforced before read, decrypt/authenticate, envelope-id match, successor/target relationship, correction authority, payload binding, pair integrity) before any slot write, and journal-clear failures reported and blocking until resolved. The file adapters implement an explicit durable redo ordering under strict platform semantics (journal intent fsynced, renamed, and directory-synced before any slot commits; crash-verifiable two-phase tombstoned removal directory-synced before later operations proceed; a stateless restart barrier that re-establishes journal durability before any replay write and writes nothing if it cannot; real directory fsync on Android/Linux with no fallback — where the platform lacks the primitive, durable capability fails closed) under a documented single-process single-writer model. No memory production source emits debug output; a guard test asserts captured streams stay empty across success and failure paths. 53/53 focused H061 JVM tests, 15/15 real-file durability/pagination tests (11 journal durability — platform-conditional strict/fail-closed coverage — plus 4 slots pagination), 19/19 H059 store contract tests; full suite 1053 tests across 131 suites with zero failures.
- **Runtime/hardware impact:** None. H061 adds no retrieval, embeddings, UI, social behavior, consolidation, or H062+ behavior. Device verification is not required or claimed.
- **Next boundary:** H062 is eligible and has not started.

## M2.5 - Architecture Migration

| ID | Milestone | Objective | Exact scope | Expected files/modules | Prerequisites | Constraints | Acceptance criteria | Tests | Non-goals | Completion evidence | HW/device |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H001 | M2.5 | Accept V2 baseline | Validate V2 docs, links, terms, invariants | docs, doc guard | T032 | No runtime change | Required docs consistent; T032 frozen | doc/schema checks | Contracts/code | [`H001_ARCHITECTURE_ACCEPTANCE.md`](H001_ARCHITECTURE_ACCEPTANCE.md) | None |
| H002 | M2.5 | Baseline evidence lock | Machine-readable T001-T032 checkpoint metadata | architecture/evidence | H001 | No fixture rewrite | Commit/evidence references immutable | parser tests | Re-sign history | [`H002_BASELINE_EVIDENCE_LOCK.md`](H002_BASELINE_EVIDENCE_LOCK.md) | None |
| H003 | M2.5 | Hugo identity contract | Identity independent of host/provider/body | `hugo/identity` | H001 | No persistence | Invalid/blank/version errors typed | value/serialization tests | Accounts/sync | [`H003_HUGO_IDENTITY_CONTRACT.md`](H003_HUGO_IDENTITY_CONTRACT.md) | None |
| H004 | M2.5 | Provider identity/type | Definition vs private instance IDs; body/device/service family | `capability/provider` | H003 | Redacted display aliases | IDs cannot cross scopes accidentally | constructor/round-trip | Discovery | [`H004_PROVIDER_IDENTITY_TYPE.md`](H004_PROVIDER_IDENTITY_TYPE.md) | None |
| H005 | M2.5 | Capability identity/version | Namespace/name and compatibility rules | `capability/api` | H001 | No vendor names in core IDs | Major incompatibility explicit | boundary/property tests | Payloads | [`H005_CAPABILITY_IDENTITY_VERSION.md`](H005_CAPABILITY_IDENTITY_VERSION.md) | None |
| H006 | M2.5 | Capability descriptor | Side effects, schemas, limits, evidence, availability | `capability/api` | H004-H005 | Finite limits; no raw protocol | Invalid/conflicting descriptors rejected | schema tests | Execution | [`H006_CAPABILITY_DESCRIPTOR.md`](H006_CAPABILITY_DESCRIPTOR.md) | None |
| H007 | M2.5 | Request/result envelopes | Actor, provider target, deadlines, outcomes, audit IDs | `capability/api` | H006 | Monotonic deadlines; outcome honest | All terminal/nonterminal states representable | reducer/serialization | Routing | [`H007_REQUEST_RESULT_ENVELOPES.md`](H007_REQUEST_RESULT_ENVELOPES.md) | None |
| H008 | M2.5 | CapabilityProvider port | Lifecycle, negotiation, register, execute, cancel | `capability/provider` | H007 | Android/vendor-free | Fake body/device/service implement port | contract tests | Real adapters | [`H008_CAPABILITY_PROVIDER_PORT.md`](H008_CAPABILITY_PROVIDER_PORT.md) | None |
| H009 | M2.5 | Provider lifecycle reducer | Discover through disconnect with generation ownership | `capability/provider/lifecycle` | H008 | No action replay | Invalid/stale transitions rejected | exhaustive state tests | Transport impl | [`H009_PROVIDER_LIFECYCLE_REDUCER.md`](H009_PROVIDER_LIFECYCLE_REDUCER.md) | None |
| H010 | M2.5 | Common manifest schema | Provider envelope, provenance, digest, compatibility | `manifest/domain`, schemas | H004-H006 | Manifest not authority | Unknown major/conflicts fail closed | schema/fuzz | Specialized fields | [`H010_COMMON_MANIFEST_SCHEMA.md`](H010_COMMON_MANIFEST_SCHEMA.md) | None |
| H011 | M2.5 | Specialized manifests | Body, device, service manifest extensions | `manifest/domain` | H010 | No forced physical inheritance | Each family validates distinct requirements | round-trip/boundary | Vendor manifests | [`H011_SPECIALIZED_MANIFESTS.md`](H011_SPECIALIZED_MANIFESTS.md) | None |
| H012 | M2.5 | Adapter identity contract | Adapter version, support range, origin, evidence, integrity | `adapter/api` | H010-H011 | No code loading | Compatibility deterministically expressible | matrix tests | Registry/install | [`H012_ADAPTER_IDENTITY_INTEGRITY.md`](H012_ADAPTER_IDENTITY_INTEGRITY.md) | None |
| H013 | M2.5 | Connection-provider contract | Endpoint/session/generation/health/error abstractions | `connection/api` | H008-H009 | No universal packet API | BLE/HTTP fakes fit without SDK types | lifecycle tests | Implementations | [`H013_CONNECTION_PROVIDER_CONTRACT.md`](H013_CONNECTION_PROVIDER_CONTRACT.md) | None |
| H014 | M2.5 | Trust/permission domain | Trust records, Observe/Act/Modify grants, approval modes | `trust/domain` | H004-H007 | Discovery never authorizes | Denied/expired/revoked fail closed | policy/property tests | UI/storage | [`H014_TRUST_PERMISSION_DOMAIN.md`](H014_TRUST_PERMISSION_DOMAIN.md) | None |
| H015 | M2.5 | Provider registry port | Registrations, lookups, capability snapshots, revocation | `capability/registry` | H008,H014 | Presence not authorization | Stale session unregisters atomically | fake repository tests | Persistence | [`H015_PROVIDER_REGISTRY_PORT.md`](H015_PROVIDER_REGISTRY_PORT.md) | None |
| H016 | M2.5 | Active-body concept | Expiring explicit selection as context | `context/provider` | H015 | Not Hugo identity; no implicit act grant | Disconnect/expiry clears selection | reducer tests | Multi-body planning | [`H016_ACTIVE_BODY_CONCEPT.md`](H016_ACTIVE_BODY_CONCEPT.md) | None |
| H017 | M2.5 | LOOI capability mapping | Map T032 actions/observations to descriptors | docs, `body/looi/manifest` fixtures | H006,H011 | No runtime/protocol edits | Mapping covers verified claims/limits only | fixture/schema tests | Execution facade | [`H017_LOOI_CAPABILITY_MAPPING.md`](H017_LOOI_CAPABILITY_MAPPING.md) | None |
| H018 | M2.5 | LOOI compatibility facade | Delegate universal non-motion requests to existing path | `body/looi/capability` | H008,H017 | Existing safety/encoders untouched | Fake delegation equals legacy result | equivalence tests | Manual-drive routing | [`H018_LOOI_COMPATIBILITY_FACADE.md`](H018_LOOI_COMPATIBILITY_FACADE.md) | None initially |
| H019 | M2.5 | V2 dependency guards | Protect new pure packages and LOOI/platform boundaries | architecture tests | H003-H018 | Do not weaken T006 guard | Negative fixtures detect forbidden edges | JVM guard tests | Gradle modules | [`H019_V2_DEPENDENCY_GUARDS.md`](H019_V2_DEPENDENCY_GUARDS.md) | None |
| H020 | M2.5 | M2.5 regression acceptance | Full suite and shadow LOOI registration audit | acceptance evidence | H018-H019 | Legacy path remains default | No test/build/lint regression; mapping honest | full JVM/lint/assemble | New features | [`H020_M25_REGRESSION_ACCEPTANCE.md`](H020_M25_REGRESSION_ACCEPTANCE.md) | Android; real LOOI only if runtime path changed |

## M3 - Universal Device/Body Runtime

| ID | Milestone | Objective | Exact scope | Expected files/modules | Prerequisites | Constraints | Acceptance criteria | Tests | Non-goals | Completion evidence | HW/device |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H021 | M3 | Discovery observation | Provider-neutral passive observation envelope | `discovery/api` | H020 | No authorization/PII leak | Source/confidence/expiry required | schema tests | Scanners | [`H021_DISCOVERY_OBSERVATION.md`](H021_DISCOVERY_OBSERVATION.md) | None |
| H022 | M3 | Identity resolver | Match observations to definition/instance candidates | `discovery/identity` | H021 | Ambiguous remains unknown | Conflicts never force identity | policy/fuzz | Network research | [`H022_IDENTITY_RESOLVER.md`](H022_IDENTITY_RESOLVER.md) | None |
| H023 | M3 | Manifest resolver | Resolve local/vendor/adapter candidates with conflicts | `manifest/resolution` | H010-H012,H022 | Safety/user policy wins | Provenance and conflict retained | precedence tests | Downloading | [`H023_MANIFEST_RESOLVER.md`](H023_MANIFEST_RESOLVER.md) | None |
| H024 | M3 | Compatibility matcher | Provider/manifest/adapter/firmware matrix | `adapter/compatibility` | H012,H023 | Exact versions; no guessing | Unsupported reason typed | matrix/property tests | Install | [`H024_COMPATIBILITY_MATCHER.md`](H024_COMPATIBILITY_MATCHER.md) | None |
| H025 | M3 | Adapter registry domain | Installed/enabled/verified descriptors and lookup | `adapter/registry` | H024 | No dynamic code yet | Missing/disabled/incompatible distinct | registry tests | Package install | [`H025_ADAPTER_REGISTRY_DOMAIN.md`](H025_ADAPTER_REGISTRY_DOMAIN.md) | None |
| H026 | M3 | Registry persistence/integrity | Local records, digest, migration, rollback metadata | `adapter/storage` | H025 | No executable loading | Tamper/migration fails safe | storage/migration tests | Marketplace | [`H026_REGISTRY_PERSISTENCE_INTEGRITY.md`](H026_REGISTRY_PERSISTENCE_INTEGRITY.md) | Android storage |
| H027 | M3 | Provider lifecycle orchestrator | Compose identity, trust, connect, negotiate, register | `provider/runtime` | H009,H013-H015,H024 | Cancellation/generation strict | Fault at every stage cleans state | virtual-time integration | UI | [`H027_PROVIDER_LIFECYCLE_ORCHESTRATOR.md`](H027_PROVIDER_LIFECYCLE_ORCHESTRATOR.md) | None |
| H028 | M3 | BLE connection-provider wrapper | Wrap current scan/GATT lifecycle behind H013 | `platform/connection/ble` | H013,H027 | No LOOI rewrite/types leak | Existing callbacks map equivalently | fake/instrumented tests | Other transports | [`H028_BLE_CONNECTION_PROVIDER_WRAPPER.md`](H028_BLE_CONNECTION_PROVIDER_WRAPPER.md) | Android BLE; no LOOI claim yet |
| H029 | M3 | Capability negotiation | Intersect manifest, adapter, connection, runtime evidence | `capability/negotiation` | H014,H023-H028 | Claims not automatically trusted | Only compatible capabilities register | matrix tests | Execution | [`H029_CAPABILITY_NEGOTIATION.md`](H029_CAPABILITY_NEGOTIATION.md) | None |
| H030 | M3 | Authorization gate runtime | Apply grants/approvals before provider dispatch | `trust/runtime` | H014,H027,H029 | AI cannot approve | Every dispatch has auditable decision | adversarial tests | Approval UI | [`H030_AUTHORIZATION_GATE_RUNTIME.md`](H030_AUTHORIZATION_GATE_RUNTIME.md) | None |
| H031 | M3 | Calibration contract | Required/valid/expired calibration and evidence links | `provider/calibration` | H011,H029 | No guessed calibration | Required calibration blocks act | reducer/time tests | LOOI recalibration | [`H031_CALIBRATION_CONTRACT.md`](H031_CALIBRATION_CONTRACT.md) | None |
| H032 | M3 | Provider-instance store | Known instance aliases, trust links, versions, last use | `provider/storage` | H022,H026 | Encrypt private IDs | Restart preserves identity without grants leakage | storage tests | General memory | [`H032_PROVIDER_INSTANCE_STORE.md`](H032_PROVIDER_INSTANCE_STORE.md) | Android storage |
| H033 | M3 | Unknown fail-closed UX | Explain missing identity/adapter/auth/calibration | `feature/providers` | H022-H032 | No exploratory control | Each blocked state actionable and honest | mapper/UI tests | Developer Mode | [`H033_UNKNOWN_FAIL_CLOSED_UX.md`](H033_UNKNOWN_FAIL_CLOSED_UX.md) | Android |
| H034 | M3 | Provider diagnostics/export | Redacted lifecycle/capability/compatibility export | `provider/diagnostic` | H027-H033 | No stable IDs/secrets | Round-trip and redaction pass | fixture tests | Raw protocol console | [`H034_PROVIDER_DIAGNOSTICS_EXPORT.md`](H034_PROVIDER_DIAGNOSTICS_EXPORT.md) | Android optional |
| H035 | M3 | M3 acceptance | Validate runtime with LOOI reference and fakes | acceptance evidence | H021-H034 | T032 behavior unchanged | Full checks and equivalent LOOI capabilities | full suite/fault tests | Second body | [`H035_M3_ACCEPTANCE.md`](H035_M3_ACCEPTANCE.md) | Android + real LOOI regression if routed |

## M4 - Personal Context and Integrations

| ID | Milestone | Objective | Exact scope | Expected files/modules | Prerequisites | Constraints | Acceptance criteria | Tests | Non-goals | Completion evidence | HW/device |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H036 | M4 | Context observation contract | Source/freshness/privacy/confidence envelope | `context/api` | H035 | Observation not memory/fact | Unknown/conflict representable | schema tests | Sources | [`H036_CONTEXT_OBSERVATION_CONTRACT.md`](H036_CONTEXT_OBSERVATION_CONTRACT.md) | None |
| H037 | M4 | Context snapshot engine | Normalize, expire, conflict, budget, select | `context/runtime` | H036 | Deterministic/bounded | Hard budgets and expiry hold | virtual-time/property | Persistence | [`H037_CONTEXT_SNAPSHOT_ENGINE.md`](H037_CONTEXT_SNAPSHOT_ENGINE.md) | None |
| H038 | M4 | Active-provider context | Connected/active body/host/service snapshot | `context/provider` | H016,H037 | Selection not authorization | Disconnect removes availability | reducer tests | Planning | [`H038_ACTIVE_PROVIDER_CONTEXT.md`](H038_ACTIVE_PROVIDER_CONTEXT.md) | None |
| H039 | M4 | Phone sensor DeviceAdapter | Lifecycle-aware sensor observations | `device/phone/sensors` | H028,H036-H038 | No navigation claim | Resources release; source/freshness clear | fake/instrumented | Fusion | Device trace | Android phone |
| H040 | M4 | ServiceConnector contract | Auth session, scopes, operations, rate/error model | `service/api` | H008,H013-H014 | No vendor SDK types | Fake service registers capabilities | contract tests | Real service | Fake traces | None |
| H041 | M4 | Connector credential boundary | Secure storage, logout/revoke, secret policy | `service/auth` | H040 | No production secrets in APK | Expired/revoked fails closed | unit/instrumented/secret scan | OAuth provider choice | [`H041_CONNECTOR_CREDENTIAL_BOUNDARY.md`](H041_CONNECTOR_CREDENTIAL_BOUNDARY.md) | Android |
| H042 | M4 | Control Center shell | Providers, capabilities, grants, approvals, diagnostics | `feature/controlcenter` | H033,H038,H040-H041 | No source editing required | Empty/error/revoked states usable | UI/state tests | Full dashboard polish | [`H042_CONTROL_CENTER_SHELL.md`](H042_CONTROL_CENTER_SHELL.md) | Android |
| H043 | M4 | Read-only connector feasibility | Select one low-risk service after ADR | adapter-specific sandbox | H040-H042 | Observe only; minimal scopes | Connect/read/revoke documented | contract/integration | Email sending/actions | [`H043_READ_ONLY_CONNECTOR_FEASIBILITY.md`](H043_READ_ONLY_CONNECTOR_FEASIBILITY.md) | Authorized test account if selected |
| H044 | M4 | Time/presence context sources | Local time and coarse authorized presence | `context/sources` | H037,H041 | Privacy/expiry explicit | Denial and stale state safe | virtual-time/device tests | Tracking history | [`H044_TIME_PRESENCE_CONTEXT_SOURCES.md`](H044_TIME_PRESENCE_CONTEXT_SOURCES.md) | Android |
| H045 | M4 | M4 acceptance | Context/privacy/connector/control-center audit | acceptance evidence | H036-H044 | No hidden persistence | Privacy, revoke, budgets pass | full suite/device | AI/memory | [`H045_M4_ACCEPTANCE.md`](H045_M4_ACCEPTANCE.md) | Android; selected service only |

## M5 - Voice and AI Agent

| ID | Milestone | Objective | Exact scope | Expected files/modules | Prerequisites | Constraints | Acceptance criteria | Tests | Non-goals | Completion evidence | HW/device |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H046 | M5 | Local intent router | Exact locale allowlist to capability proposals | `interaction/localintent` | H030,H045 | Ambiguity no act; stop priority | Adversarial near-matches safe | table/fuzz | General NLU | [`H046_LOCAL_INTENT_ROUTER.md`](H046_LOCAL_INTENT_ROUTER.md) | None |
| H047 | M5 | Audio session reducer | Activation/listen/think/speak/cancel/error | `voice/session` | H045 | Cancellation first | Every state exits/times out | transition/virtual-time | Capture | [`H047_AUDIO_SESSION_REDUCER.md`](H047_AUDIO_SESSION_REDUCER.md) | None |
| H048 | M5 | Explicit capture/local VAD | PTT, AudioRecord port, ephemeral buffers | `voice/capture` | H047 | No cloud/default retention | Denial/timeout releases mic | fake/instrumented latency | Wake word/STT | [`H048_EXPLICIT_CAPTURE_LOCAL_VAD.md`](H048_EXPLICIT_CAPTURE_LOCAL_VAD.md) | Android |
| H049 | M5 | Speech adapter contracts | STT/TTS events, cancellation, audio focus | `voice/api` | H046-H048 | Provider-neutral/no keys | Fake transcript/TTS cancellation works | integration tests | Provider selection | [`H049_SPEECH_ADAPTER_CONTRACTS.md`](H049_SPEECH_ADAPTER_CONTRACTS.md) | None |
| H050 | M5 | Capability-backed skill registry | Typed bounded local skills over H007 | `skills/api`,`skills/local` | H030,H046 | No raw adapters; non-motion first | Unknown/invalid/cancel safe | schema/timeout | Marketplace/motion | [`H050_CAPABILITY_BACKED_SKILL_REGISTRY.md`](H050_CAPABILITY_BACKED_SKILL_REGISTRY.md) | None |
| H051 | M5 | AI gateway contract | Versioned text/realtime/vision/embedding streams | `gateway-contract` | H036,H049-H050 | No provider types/secrets | Examples validate all outcomes | schema compatibility | Backend | [`H051_AI_GATEWAY_CONTRACT.md`](H051_AI_GATEWAY_CONTRACT.md) | None |
| H052 | M5 | Gateway authentication | Short-lived scoped app identity | `gateway/auth` | H041,H051 | Provider keys server-only | Revoke/expiry/endpoint policy pass | storage/secret tests | Deployment provider | [`H052_GATEWAY_AUTHENTICATION.md`](H052_GATEWAY_AUTHENTICATION.md) | Android |
| H053 | M5 | Gateway client/reducer | Streaming, cancellation, timeout, usage | `ai/gateway` | H051-H052 | Bounded buffers/redacted logs | Event orders reduce deterministically | fake-server tests | Real model | [`H053_GATEWAY_CLIENT_REDUCER.md`](H053_GATEWAY_CLIENT_REDUCER.md) | None |
| H054 | M5 | Bounded AI context packet | Current turn + H037 snapshot + empty memory port | `conversation/context` | H037,H051,H053 | Hard privacy/token budget | Forbidden fields never serialize | adversarial/budget | Memory retrieval | [`H054_BOUNDED_AI_CONTEXT_PACKET.md`](H054_BOUNDED_AI_CONTEXT_PACKET.md) | None |
| H055 | M5 | Capability proposal validator | Schema, target, trust, grant, approval, idempotency | `ai/tools` | H030,H050,H053-H054 | AI cannot grant/reset/install | Malformed/injected invokes nothing | fuzz/replay/red-team | Motion enable | [`H055_CAPABILITY_PROPOSAL_VALIDATOR.md`](H055_CAPABILITY_PROPOSAL_VALIDATOR.md) | None |
| H056 | M5 | Conversation/barge-in | Turn coordinator, local-first route, cancel/fallback | `conversation/runtime` | H046-H055 | Local stop wins | Speech cancels generation/output | fake timing/device | Memory writes | [`H056_CONVERSATION_BARGE_IN.md`](H056_CONVERSATION_BARGE_IN.md) | Android audio |
| H057 | M5 | Model policy and M5 acceptance | Task routing, budgets, circuit, security/privacy/latency | gateway policy, acceptance | H051-H056 | Configurable/no unlimited fallback | Exhaustion/provider loss safe | policy/full/device tests | Optimize one model | [`H057_MODEL_POLICY_AND_M5_ACCEPTANCE.md`](H057_MODEL_POLICY_AND_M5_ACCEPTANCE.md) | Android + configured test gateway |

## M6 - Long-Term Personal Memory and Learning

| ID | Milestone | Objective | Exact scope | Expected files/modules | Prerequisites | Constraints | Acceptance criteria | Tests | Non-goals | Completion evidence | HW/device |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H058 | M6 | Common memory envelope | Provenance/privacy/confidence/retention/conflicts | `memory/domain` | H003,H036,H057 | Model output candidate only | Invalid states rejected | schema/property | Storage | [`H058_COMMON_MEMORY_ENVELOPE.md`](H058_COMMON_MEMORY_ENVELOPE.md) | None |
| H059 | M6 | Encrypted local repository | Ports, Room/SQLite decision, keys, migrations | `memory/storage` | H058 | No plaintext backup/log | CRUD/migration/key loss explicit | JVM/instrumented | Sync/vector | [`H059_ENCRYPTED_LOCAL_MEMORY_REPOSITORY.md`](H059_ENCRYPTED_LOCAL_MEMORY_REPOSITORY.md) | Android |
| H060 | M6 | Working and episodic memory | Session bounds and event records | `memory/working`,`memory/episodic` | H058-H059 | Context not auto-durable | Expiry/provenance deterministic | virtual-time/storage | Summaries | [`H060_WORKING_EPISODIC_MEMORY.md`](H060_WORKING_EPISODIC_MEMORY.md) | None |
| H061 | M6 | Semantic and personal memory | Validated facts/preferences/routines/corrections | `memory/semantic`,`memory/personal` | H058-H060 | Explicit correction outranks inference | Candidate lifecycle enforced | rule/adversarial | UI | [`H061_SEMANTIC_PERSONAL_MEMORY.md`](H061_SEMANTIC_PERSONAL_MEMORY.md) | None |
| H062 | M6 | Device and body memory | Instance/version/calibration/quirk records | `memory/provider` | H031-H032,H058-H059 | No transfer across instances | Version mismatch blocks reuse | schema/storage | Strategy | LOOI examples | None |
| H063 | M6 | Strategy memory | Preconditions, attempts, outcomes, transfer limits | `memory/strategy` | H050,H060-H062 | Evidence-bearing candidate; no authority | Failed strategy cannot become fact | rule/graph tests | Autonomous execution | Strategy corpus | None |
| H064 | M6 | Validation/reconciliation | Sensitivity, contradictions, dedupe, supersede | `memory/validation`,`reconcile` | H061-H063 | No silent overwrite/vector merge | Provenance/conflicts retained | matrix/idempotency | UI | Record graphs | None |
| H065 | M6 | Bounded retrieval | Exact/lexical, optional embedding port, ranking/budgets | `memory/retrieval` | H054,H059-H064 | Validated/privacy-scoped defaults | No partition leak; explanations stable | golden/adversarial | Cloud vector DB | Retrieval report | None |
| H066 | M6 | Provenance summaries | Candidate summaries and deletion propagation | `memory/summarization` | H053,H064-H065 | Sources retained; size bound | Refresh/delete affects derivatives | fake-model tests | Fine-tuning | Source graph | None |
| H067 | M6 | Memory control and acceptance | Inspect/confirm/correct/delete/export/privacy | `feature/memory`, acceptance | H059-H066 | Destructive confirmation/no hidden copies | Deletion clears all retrieval paths | UI/instrumented/full | Cloud sync | Dated privacy audit | Android |

## M7 - Multi-Provider Orchestration

| ID | Milestone | Objective | Exact scope | Expected files/modules | Prerequisites | Constraints | Acceptance criteria | Tests | Non-goals | Completion evidence | HW/device |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H068 | M7 | Provider capability selector | Rank authorized providers by capability/context/evidence | `orchestration/selection` | H038,H065,H067 | Selection is proposal, not action | Missing capability yields explicit alternatives | golden/property | Execution | Explanations | None |
| H069 | M7 | Cross-provider proposal/approval | Explain provider switch, scopes, risks, confirmation | `orchestration/proposal` | H030,H068 | No silent body/service switch | User denial invokes nothing | policy/UI tests | Automation | Approval traces | Android UI |
| H070 | M7 | Bounded routine schema | Versioned capability steps, limits, compensation | `routine/domain` | H050,H055,H069 | No raw/unbounded/self-expansion | Invalid nesting/limits rejected | schema/fuzz | Executor | Corpus | None |
| H071 | M7 | Routine executor | Reauthorize each step; cancellation/fault/observations | `routine/runtime` | H070 | Manual/E-stop preempt; no orphan | Any fault prevents later steps | virtual-time/fake providers | Open autonomy | Fault traces | None |
| H072 | M7 | Multi-provider simulation acceptance | Simulate body/device/service alternatives and failures | acceptance fixtures | H068-H071 | No real autonomous motion | Selection/approval/cancel deterministic | full fake integration | Floor multi-body run | Dated report | Real providers deferred |

## M8 - Developer Mode and Adapter Expansion

| ID | Milestone | Objective | Exact scope | Expected files/modules | Prerequisites | Constraints | Acceptance criteria | Tests | Non-goals | Completion evidence | HW/device |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H073 | M8 | DevelopmentNeed record | Redacted missing/incompatible adapter backlog item | `developer/domain` | H033,H072 | Runtime cannot act/research itself | Dedupe/privacy/consent explicit | schema tests | Coding | Need fixtures | None |
| H074 | M8 | Runtime/sandbox boundary | IPC/artifact threat model and isolated lifecycle | docs, `developer/api` | H073 | No prod secrets/code mutation | Sandbox cannot invoke runtime capabilities | threat/negative tests | Sandbox engine | Boundary report | None |
| H075 | M8 | Adapter proposal/research policy | Provenance/license/manifest/mapping/test/rollback format | `developer/proposal` | H012,H074 | Official/legal sources; no guessed writes | Incomplete/risky proposal rejected | schema/policy tests | Implementation agent | Proposal corpus | None |
| H076 | M8 | Verification/install approval | Integrity, automated/hardware gates, disabled install, enable/rollback | `adapter/install` | H025-H026,H075 | Separate install/enable approvals | Failed/tampered package cannot activate | fake package/security tests | Marketplace | Audit trace | Hardware only per adapter |
| H077 | M8 | Developer Mode acceptance | Red-team self-modification, secrets, escalation, rollback | acceptance evidence | H073-H076 | No autonomous deployment | Threat cases fail closed | full security suite | General coding assistant | Dated report | Sandbox host; test adapter only |

## M9 - Higher Autonomy and Product Acceptance

| ID | Milestone | Objective | Exact scope | Expected files/modules | Prerequisites | Constraints | Acceptance criteria | Tests | Non-goals | Completion evidence | HW/device |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H078 | M9 | Portable perception foundation | Camera DeviceAdapter, detection/tracking observation contracts | `vision`,`perception` | H039,H057,H072 | Visible sessions; no direct motion/identity recognition | Bounded frames and stale tracks expire | fake/golden/device benchmark | Following | Model/license report | Android camera |
| H079 | M9 | Feasibility-gated bounded autonomy | Simulation first; one reviewed capability routine experiment | `behavior/experimental` | H071-H072,H078 | Tiny envelope/manual override/no stairs | Metrics produce go/no-go; failure valid | simulation/device/hardware checklist | Product autonomy claim | ADR + measurements | Real hardware only after approval |
| H080 | M9 | Portable product acceptance | Packaging, migration, security/privacy/reliability, capability claims | release docs/tests | H035,H045,H057,H067,H072,H077-H079 | Claims map to evidence; user owns config/data | Supported hosts/providers/limits explicit | full regression/scans/device/hardware | SaaS multi-tenancy | Release evidence matrix | All advertised hardware/providers |
