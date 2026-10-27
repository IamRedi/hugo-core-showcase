# H014 - Trust and permission domain

## Status

**DONE** - 2026-08-25

## Trust and grant model

H014 adds a pure, provider-neutral `trust/domain` model. `ProviderTrustRecord` binds one protected
H004 provider-instance ID to an explicit trust state, bounded validity, provenance, audit reference,
and independently revocable record ID. Trust is necessary input but never a permission grant.

`PermissionGrant` binds the existing H007 authorization reference to an exact actor, provider
instance, capability/version, and canonical H006 permission intent (`OBSERVE`, `ACT`, or `MODIFY`).
Optional scope constraints further narrow resources such as labels, rooms, accounts, or operation
classes without defining provider-specific Gmail, Home Assistant, LOOI, or Developer Mode policy.
Permission intents are matched exactly: Observe cannot satisfy Act, and Act cannot satisfy Modify.

Grants are explicitly one-time, session, or persistent records. One-time grants bind one request;
session grants bind one authority-session reference; persistent means grant lifetime only, not
irrevocability or automatic authority across connections, hosts, process restarts, or provider
generations. H014 defines no storage.

## Approval, revocation, and evaluation

H014 reuses H006 approval terminology: `WITHIN_GRANT`, `SESSION_CONFIRMATION`,
`ONE_TIME_CONFIRMATION`, `EVERY_REQUEST`, and `PROHIBITED`. Descriptor and grant requirements are
combined fail-closed using the stricter requirement. Approval records bind actor, provider,
capability, grant, and either session or exact request. A consumed one-time approval is rejected as
replay.

`RevocationRecord` can independently target trust, a grant, or an approval. Any applicable record at
or before evaluation time invalidates that authority input. Expired, revoked, mismatched, denied,
consumed, missing, or prohibited inputs produce typed deterministic denials.

`TrustPermissionEvaluator` is a pure decision function. A permitted result says only that the H014
trust/permission/approval gate passed and identifies any approval that must be consumed atomically
by a future runtime. It does not dispatch, consume, persist, mint an execution token, approve itself,
or make connection, safety, privacy, rate, physical-feasibility, or provider-policy decisions.

## Host, embodiment, and safety boundaries

A grant may restrict host roles (`HOME_CORE`, `MOBILE_HOST`, `OTHER_HOST`, `CLOUD_RELAY`), local versus
remote origin, physical presence, mounted-host evidence, provider proximity, and active-body match.
These values are evaluation inputs supplied by future evidence-bearing domains; storing a restriction
does not make presence, attachment, proximity, or active-body state true. H014 implements no
distributed authority, host election, synchronization, failover, or split-brain behavior.

Discovery, manifest compatibility, and connection evidence are explicitly represented as
non-authority facts and cannot replace a valid grant. `CapabilityRequest.authorizationReference` is
only a lookup/correlation reference and fails when no matching live grant exists.

Every permitted result retains `deterministicSafetyStillRequired = true`. Physical permissions are
necessary but never sufficient: provider/body policy and the verified LOOI deterministic safety path
remain authoritative after H014.

## Scope and evidence

H014 adds no runtime enforcement gate, UI/dialog, registry, persistence, credentials, integration,
AI, memory, Developer Mode, distributed runtime, LOOI mapping, or transport change. No wire codec is
introduced because H014 records are not yet persisted or transported; strict construction and schema
major validation are covered now, and a future storage/IPC task must define its own closed codec.

`TrustPermissionDomainTest` covers valid deterministic grants, invalid scopes/schema versions,
expiry, revocation, wrong provider/capability/actor/host/origin, permission-intent isolation,
one-time replay, prohibited actions, missing authorization references, non-authority evidence, and
platform/vendor/credential-free public types. H014 is JVM-only and makes no hardware claim.
