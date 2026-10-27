# H022 Identity Resolver

## Record

- Task: `H022 - Identity resolver`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H021 commit `255c819c6c2c61ae294a9b4f109e7ca1770d7792`
- Runtime impact: none; the pure resolver is not wired to scanners or provider runtime
- Hardware claim: none

## Boundary and identity model

H022 adds the pure `discovery/identity` evidence and resolution contracts. It preserves the strict
pipeline boundary:

```text
observation != identity != registered provider != trust != authorization != connection
```

`DiscoveryObservation` remains expiring passive input. `IdentityEvidence` is an immutable,
evidence-backed claim about a provider-definition or known configured-instance candidate.
`IdentityResolution` is a conclusion over that input, never a registration, merge, trust decision,
permission grant, connection attempt, manifest lookup, adapter selection, active-body selection, or
capability-availability assertion.

Definition and instance are separate throughout. Moderate verified definition evidence may produce
a `NewProviderIdentityCandidate` without inventing a `ProviderInstanceId`. A known-instance match
must target an existing H004 instance in the immutable H015 identity projection. The H004 instance
continues to mean HUGO's private configured-binding identity, not a physical device ID, service
account ID, endpoint, or transport address.

## Evidence strength, provenance, and freshness

Each evidence record contains:

- a project-owned evidence UUID and the H021 observation UUIDs it uses;
- exact observing-host, discovery-source set, and semantic producer provenance;
- an evidence class with fixed `WEAK`, `MODERATE`, or `STRONG` strength;
- bounded source confidence, retained as context but never added into a score;
- an evidence-class-compatible verification level;
- support or exclusion disposition and an explicit definition/instance/family candidate target;
- explicit references to contradictory evidence;
- host-local monotonic observation/expiry timestamps; and
- an optional protected lookup reference, required for stable-identifier and authenticated-binding
  classes.

Strength belongs to the evidence class and cannot be raised by confidence. Display-name and
manufacturer hints are always weak. Protocol/provider-definition classifications are moderate.
Protected identifier, authenticated integration, previously verified provider, and future manual
confirmation bindings are strong only with the verification kind allowed by that class. This is an
explicit class/verification matrix, not arbitrary additive scoring and not AI/LLM inference.

The resolver validates that every evidence observation exists, all referenced observations share
the declared host, the declared source set is exact, evidence does not predate its observations,
contradiction references exist, and instance targets agree with the registry identity. Host-specific
evaluation times preserve H021 monotonic-clock semantics. Evidence is usable only while it and all
of its supporting observations are fresh.

## Deterministic outcomes

- `MATCHED_KNOWN_PROVIDER`: exactly one H015-known instance has fresh strong, correctly verified
  supporting evidence and no strong contradiction.
- `NEW_PROVIDER_CANDIDATE`: fresh moderate-or-strong evidence supports exactly one provider
  definition but does not prove a known configured instance. No instance identity is created.
- `AMBIGUOUS`: multiple candidates remain plausible from weak/moderate evidence. Identical display
  names never merge providers.
- `CONFLICTING_EVIDENCE`: strong support/exclusion overlaps, explicit contradiction involving
  strong evidence, or strong evidence names incompatible instances, definitions, or families.
- `INSUFFICIENT_EVIDENCE`: only weak, family-only, untargeted, or otherwise non-proving evidence is
  available. A confidence of `1.0` cannot change this outcome.
- `STALE_EVIDENCE`: the observations or all supplied identity evidence are expired at their
  host-local evaluation time.
- `REJECTED`: structural/referential input fails closed, including missing clocks, duplicate IDs,
  provenance mismatch, unknown observation/contradiction/instance references, or a registry
  identity mismatch.

Inputs, registry identities, evidence, observations, candidates, and traces are normalized by their
canonical IDs. Conclusions and redacted traces are therefore independent of observation/evidence
ordering. Ambiguity is retained for future manual confirmation; H022 adds no UI.

## Protected identifiers and diagnostics

`ProtectedIdentityEvidenceReference` accepts only a project-owned `iref_` base32 token. Raw MAC or
IP addresses, UUID-shaped transport IDs, serials, endpoints, and account identifiers do not satisfy
the type. Protected evidence is serialized only by `IdentityEvidenceCodec`, a strict protected-
storage schema `1.0` codec that rejects missing/unknown fields, unsupported versions, and a strength
that disagrees with its evidence class.

`IdentityEvidence.toString()`, evidence redaction, candidate redaction, and resolution redaction omit
the protected token and H004 private instance ID. They expose only safe class/family/display data and
whether a known instance was referenced. Stable external identifiers never become public HUGO
`ProviderIdentity` values.

## Registry interaction

`KnownProviderIdentitySnapshot.fromRegistry` projects only immutable H015 identities from a
`ProviderRegistrySnapshot`. Manifest, adapter, runtime, lifecycle, availability, and registry
presence are not treated as identity proof. The resolver accepts no mutable registry port and calls
no registration/update/unregister operation. Tests prove the in-memory registry remains unchanged.

## Completion evidence

`IdentityResolverTest` covers a strong known-provider match; maximum-confidence name-only evidence;
multiple observations for one provider; identical display names; conflicting strong evidence;
stale evidence; mixed discovery sources; a new definition candidate; protected stable-identifier
handling and redaction; absence of trust/permission/connection implications; deterministic ordering;
no registry mutation; no merge from confidence; and strict codec version/field/strength behavior.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H021/H022 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Android-device/provider/hardware verification: not required and not claimed

## Scope boundary

H023 has not started. H022 adds no provider registration, manifest resolution, adapter selection,
connection, trust approval, authorization, UI, AI reasoning, World Model runtime, Home Assistant,
LOOI discovery rewiring, Android/BLE behavior, or physical-provider behavior.
