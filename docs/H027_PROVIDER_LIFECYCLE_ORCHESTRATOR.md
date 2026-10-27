# H027 Provider Lifecycle Orchestrator

## Record

- Task: `H027 - Provider lifecycle orchestrator`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H026 commit `c8f33862bff008c5e72cad62eb12ed2ef1fbed9a`
- Runtime impact: none; H027 stops before trust, permission, connection, registration, or execution
- Hardware claim: none

## Orchestrator and state model

H027 adds the provider-neutral `provider/runtime` preparation coordinator. It composes accepted
contracts through three narrow replaceable ports:

- `ProviderIdentityResolutionStage`, canonically backed by H022 `IdentityResolver`;
- `ProviderManifestResolutionStage`, canonically backed by H023 `ManifestResolver`; and
- `ProviderCompatibilityStage`, canonically backed by the immutable H025 registry query and H024
  `CompatibilityMatcher`.

The orchestrator owns sequencing only. It does not reproduce identity, manifest, compatibility,
registry, or provider-lifecycle rules. Explicit phases are:

```text
INACTIVE -> OBSERVED -> RESOLVING_IDENTITY -> IDENTITY_RESOLVED
         -> RESOLVING_MANIFEST -> MANIFEST_RESOLVED
         -> CHECKING_COMPATIBILITY
         -> PREPARED | NEEDS_ADAPTER | AMBIGUOUS | BLOCKED | FAILED | CANCELLED
```

Each stage has separate `begin`, `run`, and `accept` operations. This permits asynchronous callers
to begin work, cancel while a dependency is running, and submit a correlated result later. The
canonical `run` operations invoke a port once and have no retry or fallback loop.

`PREPARED` means only that one known adapter descriptor produced an H024 `COMPATIBLE` or
`PARTIALLY_COMPATIBLE` evidence report. It does not install, select for execution, trust, authorize,
connect, register, activate, or execute that adapter/provider.

## Stages and accepted-contract reuse

The request carries an H021/H022 identity request, an immutable H015 provider-registry snapshot,
H023 manifest candidates, an immutable H025/H026-restored adapter-registry snapshot, HUGO runtime
evidence, and provider-version evidence.

The canonical flow is:

1. H009 receives `Discovered` for the attempt generation.
2. H022 evaluates H021 observations/evidence against the captured H015 snapshot.
3. Only `MATCHED_KNOWN_PROVIDER` or `NEW_PROVIDER_CANDIDATE` advances H009 with
   `IdentityResolved`.
4. H023 resolves the identity result using the supplied manifest candidates and H012 descriptors
   from the captured adapter snapshot.
5. H025 evaluates that resolved manifest over the same immutable adapter snapshot and delegates
   every candidate report to H024.
6. H027 stops at a typed preparation outcome.

H009 remains the canonical session-sequencing reducer. H027 uses only its discovery, identity,
failure, and pre-connection cancellation transitions. It never emits `ConnectRequested`,
`ConnectionEstablished`, `NegotiationRequested`, or `CapabilitiesRegistered`.

## Stop and block outcomes

H022 outcomes remain distinct:

- ambiguous identity -> `AMBIGUOUS_IDENTITY`;
- conflicting evidence -> `CONFLICTING_IDENTITY_EVIDENCE`;
- stale observations/evidence -> `STALE_OBSERVATION`;
- insufficient evidence -> `INSUFFICIENT_IDENTITY_EVIDENCE`; and
- rejected identity input -> `IDENTITY_REJECTED`.

H023 outcomes distinguish missing, ambiguous, conflicting, identity/family mismatch,
digest/schema invalidity, adapter incompatibility, and rejected manifests. H027 never chooses among
multiple plausible manifest candidates.

For compatibility:

- no registry record with the required adapter identity -> `NEEDS_ADAPTER` / `MISSING_ADAPTER`;
- more than one H024-compatible record -> `AMBIGUOUS` / `MULTIPLE_COMPATIBLE_ADAPTERS`;
- exactly one compatible or partially compatible record -> `PREPARED`, retaining its full report;
- only unknown reports -> `COMPATIBILITY_UNKNOWN`; and
- known but incompatible adapter/runtime/capability evidence ->
  `INCOMPATIBLE_ADAPTER_OR_RUNTIME`.

Missing adapter is not converted into a dependency failure. Its snapshot retains the resolved
provider definition, manifest correlation, adapter-registry revision, candidate set, and the H024
missing-adapter report where applicable. A future Developer Mode layer can consume this evidence;
H027 creates no development project.

## Correlation, stale results, and cancellation

Every attempt has a canonical UUID `ProviderOrchestrationRunId` plus H005 `ProviderGeneration`.
Snapshots retain sorted H021 observation IDs, the full redaction-safe H022 result, H023 identity
binding, candidate ID/provenance, common and specialized digests, H024 reports/evidence, H009
lifecycle snapshot, and an ordered audit trace.

Every stage operation validates generation before run ID. Older generations, future generations,
wrong run IDs, reused generations, reused run IDs, invalid phases, and duplicate results return
typed ignored dispositions without modifying state. A new higher-generation attempt can begin only
after the prior attempt reaches a terminal preparation state. Late results from the prior attempt
therefore cannot advance it.

Cancellation is accepted in every non-terminal preparation phase. It sends H009
`CancellationRequested` and `CancellationCompleted`; because H027 never connects, cancellation
never needs a disconnect side effect. Repeated cancellation is idempotently ignored. No hidden
retry, fallback provider, fallback manifest, or fallback adapter exists.

## Registry and authority boundaries

H027 accepts registry snapshots, not mutable registration authority. H015 is read only by H022.
H025 compatibility queries now also operate directly on one immutable revision, so a mid-run
adapter-registry change cannot alter the attempt. H027 never calls provider or adapter registration,
replacement, removal, persistence, installation, or activation methods.

The following remain strict:

```text
OBSERVED != IDENTIFIED != COMPATIBLE != TRUSTED != AUTHORIZED
AUTHORIZED != CONNECTED != REGISTERED != ACTIVE_BODY
```

H014 trust/permission, H013 connection providers, H015 mutation, H016 active-body selection,
World Model feasibility, and physical execution are absent. No connection object or callback is
accepted by the API. No LOOI, BLE, vendor, protocol, endpoint, or Android runtime type crosses the
boundary.

## Completion evidence

`ProviderLifecycleOrchestratorTest` covers the deterministic compatible path, known and new
provider results, ambiguous/conflicting/stale identity, missing/conflicting manifest, compatible,
missing, multiple, and runtime-incompatible adapter outcomes, cancellation at every major phase,
idempotent cancellation, stale old-run and duplicate results, dependency failure without retry,
equivalent input ordering, no provider-registry mutation, no connection, and authority/active-body/
vendor/transport absence. The architecture guard includes `provider/runtime`.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H009/H015/H021-H026/H027 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Next boundary

H028 has not started. H027 adds no BLE wrapper, physical/network connection, provider registration,
capability negotiation/execution, trust/permission, active-body selection, Developer Mode,
dashboard, AI, memory, World Model runtime, LOOI rewiring, or physical behavior.
