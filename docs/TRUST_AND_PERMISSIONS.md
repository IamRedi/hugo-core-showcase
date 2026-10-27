# Trust and Permissions

## Separation of concerns

Discovery, identity, trust, authentication, authorization, approval, safety, and execution are separate decisions.

```text
Observed endpoint != identified provider != trusted instance
!= authorized capability != approved request != safe physical action
```

## Trust records

A `ProviderTrustRecord` binds a protected provider instance reference to identity evidence, owner, provenance, trust state, first/last seen, verification method, adapter/manifest digests, and revocation state. Identity conflicts degrade trust and unregister action capabilities.

## Permission model

Base permission intents are `Observe`, `Act`, and `Modify`. Grants are capability-scoped and may include provider instance, data class, location/room, account, time/session, limits, retention, and actor.

Approval modes:

- always allowed within a narrow persisted grant;
- session approval;
- one-time approval;
- explicit confirmation every request;
- prohibited.

Physical safety-critical actions default to narrower limits and stronger confirmation than informational reads. `Modify` includes adapter/configuration/code changes and never belongs to ordinary AI runtime authority.

## Authorization flow

1. Resolve actor and requested capability.
2. Resolve exact provider instance and negotiated capability version.
3. Validate trust and adapter/manifest compatibility.
4. Evaluate OS/account permission and user grant.
5. Determine confirmation requirement.
6. Apply privacy, context, rate, and risk limits.
7. For bodies, invoke deterministic body authority and safety.
8. Audit decision and observed outcome.

## Revocation

Revocation removes active registrations and cancels bounded operations where safe. Disconnect, account logout, adapter disable, trust conflict, permission loss, and process death cannot leave action authority active.

## AI boundary

AI can propose a capability request. It cannot grant permission, approve itself, reset E-stop, change safety limits, install adapters, access raw connection handles, or reinterpret a rejection.

## Secrets and privacy

Provider credentials use platform-secure storage or a controlled gateway. Production provider secrets do not ship in clients. Logs use redacted provider aliases and audit IDs, not tokens, account content, or stable private hardware identifiers.
