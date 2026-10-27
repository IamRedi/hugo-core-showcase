# H050 Capability-Backed Skill Registry

## Record

- Task: `H050 - Capability-backed skill registry`
- Status: `DONE`
- Completed: 2026-09-01
- Prerequisites: H030 authorization gate and H046 local intent router
- Runtime impact: none; H050 is an unwired registry/executor with fake dispatch only
- Hardware claim: none

## Registry and schema model

H050 adds the Android-free `skills/api` and `skills/local` boundaries. `SkillId`, `SkillVersion`,
`SkillReference`, and redacted `SkillExecutionId` provide exact versioned identity. A
`SkillDefinition` binds one skill to one existing H005 capability, H007 actor kind, optional exact
fixed input, optional output contract, reviewed local skill class, a maximum execution duration, and
required cancellation.

Inputs are deliberately closed rather than open-ended. A definition accepts either no input or one
exact immutable H007 `CapabilityData` value whose contract and canonical scalar values match the
reviewed definition. Durations are limited to 100 ms through 30 seconds, and definitions that omit
cancellation fail validation.

`SkillRegistry` is immutable, bounded to 64 exact definitions, rejects duplicates, orders its `1.0`
snapshot deterministically, supports exact lookup and capability query, and does not imply that a
skill is installed, enabled, authorized, or executing. Multiple different skill references may map
to the same semantic capability; H050 defines no source precedence, marketplace, dynamic loading,
or automatic best-version selection.

## Reviewed local catalog

`HugoLocalSkillCatalog` contains exactly three non-motion definitions:

| Skill | H007 capability | Input | Expected output |
|---|---|---|---|
| `hugo.local.connection_status@1.0` | `provider.connection.status@1.0` | none | `hugo.contract.provider_connection_status_output@1.0` |
| `hugo.local.lower_contact_status@1.0` | `safety.lower_contact.status@1.0` | none | `hugo.contract.lower_contact_status_output@1.0` |
| `hugo.local.light_off@1.0` | `body.light.control@1.0` | exact `enabled=false` | provider H007 outcome |

The first two are observations. Light-off is a reviewed non-motion control with one fixed semantic
input; its presence is not permission or execution. `mobility.move`, head control, arbitrary light
values, raw writes, and all other motion skills are absent. H046 connection-status and light-off
proposals resolve to these exact definitions without creating or dispatching a request.

## H007 and H030 execution boundary

`AuthorizedSkillInvocation.create` accepts an existing H007 `CapabilityRequest` only together with
an actual H030 `AuthorizationDecision` whose status is `AUTHORIZED`. It requires exact request ID,
audit ID, capability, actor, authorization reference, and provider-instance correlation, and rejects
`AnyCompatible` targets. The reference in H007 remains correlation data; it cannot substitute for
the H030 decision.

`CapabilityBackedSkillExecutor` validates the selected definition, capability, actor, fixed input,
request lifetime, maximum skill duration, and current H030 decision time before calling
`AuthorizedSkillDispatchPort`. That port accepts only the semantic H007/H030 invocation and exposes
no provider implementation, raw adapter, vendor, or transport type.

```text
SKILL KNOWN
!= REQUEST AUTHORIZED
!= PROVIDER AVAILABLE
!= SAFE
!= PHYSICALLY FEASIBLE
!= EXECUTION SUCCESSFUL
```

H050 does not construct a request, select a provider, run H030, grant trust/permission, decide
physical safety, or dispatch through a production provider. A future caller must prepare the exact
H007 request and obtain the matching current H030 decision first.

## Lifecycle, cancellation, timeout, and replay

Accepted dispatches preserve H007 `ACCEPTED`/`STARTED` progress and terminal
`COMPLETED`/`REJECTED`/`FAILED`/`CANCELLED`/`TIMED_OUT`/`UNKNOWN` outcomes. Every result must match
request, capability, provider, execution context, and audit correlation. Completed observation
outputs must use the definition's exact output contract; correlation, timing, or output conflicts
become `MALFORMED_RESULT`, request cancellation, and release the executor's authorization
association.

User, background, session, and deadline cancellation are explicit. The executor drops its H030
authorization association before requesting cancellation, so cancellation cannot reuse the decision
as fresh authority. A bounded cancellation grace defaults to two seconds; missing terminal evidence
after that window becomes explicit `UNKNOWN`, never invented success. A late completion at or after
the H007 deadline initiates timeout rather than becoming success.

The executor retains a bounded, fail-closed in-memory ledger of accepted execution and request IDs.
Replays are rejected; when the configured ledger capacity is exhausted, new dispatch fails closed
instead of evicting evidence and permitting reuse. This ledger is process-local and not a durable
idempotency claim. H055 owns broader proposal/replay mediation; a production composition must not
claim cross-process replay protection from H050 alone.

## Deterministic skill trace

The successful fake observation trace is content-free:

```text
DISPATCHED:hugo.local.connection_status@1.0
TERMINAL:COMPLETED:LOCALLY_OBSERVED:authority=false
```

The terminal event retains the typed H007 result for consumers, while diagnostics redact output and
private provider identity. `LOCALLY_OBSERVED` remains evidence provenance, not authorization or a
general truth claim.

## Coverage and checks

`CapabilityBackedSkillExecutorTest` contributes 15 tests covering:

- deterministic catalog/schema, fixed light-off input, registry queries, immutability semantics,
  duplicates, malformed identity, duration, and mandatory cancellation;
- H046 proposal-to-skill mapping without execution;
- exact authorized H030 correlation and rejection of denied, mismatched, or non-exact requests;
- H007 progress and locally observed typed completion;
- unknown skill, wrong capability/actor/input, excessive duration, and dispatch rejection;
- user cancellation, immediate authorization release, terminal cancellation, deadline, grace expiry,
  provider timeout, and explicit unknown;
- wrong result correlation/output contract, late success prevention, and stale callback rejection;
- bounded request/execution replay rejection and fail-closed ledger exhaustion;
- deterministic redacted non-authoritative trace; and
- absence of Android, raw adapter/provider, vendor, transport, AI, marketplace, and LOOI coupling.

The architecture dependency guard now protects both skill packages. Required checks pass: First
Life documentation/order guard, HUGO V2 documentation guard, T032 baseline evidence guard,
architecture dependency guard, focused H050 tests, full `testDebugUnitTest` (814 tests across 110
suites; zero failures/errors/skips), `lintDebug`, `assembleDebug`, and `git diff --check`.

No Android-device or LOOI verification is required or claimed. H050 changes no production
composition, provider connection, authorization store, T032 safety path, manual control, or physical
behavior.

## Next boundary

H051 is eligible and has not started. H050 adds no production dispatch adapter, motion skill, AI tool
calling, gateway, plugin/marketplace, persistence, UI, memory, provider selection, or physical action.
