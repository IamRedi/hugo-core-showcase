# H033 Unknown Fail-Closed UX

## Record

- Task: `H033 - Unknown fail-closed UX`
- Status: `DONE`
- Completed: 2026-08-26
- Prerequisite: H032 commit `453f0aae175e60656e6589805a673ea4d48782ea`
- Runtime impact: none; the mapper and card are not wired into app navigation or provider runtime
- Hardware claim: none

## UX and state model

H033 adds a reusable `feature/providers` blocked-state presentation component, not the final HUGO
dashboard. `ProviderBlockedUiState` contains one stable reason kind, an optional H004 redacted alias,
concise statements for what HUGO knows and does not know, why processing stopped, the safe next step,
whether that step is currently user-resolvable or requires waiting/policy/future functionality, and
canonically ordered safe detail lines.

The model deliberately cannot carry an action callback, connection request, provider instance ID,
endpoint, token, permission grant, approval, calibration value, or capability request. Explicit
properties document that a blocked state exposes no exploratory control, grants no trust or
permission, starts no connection, and executes no capability.

`ProviderBlockedTextContent` is a pure rendering projection used by the Compose card and JVM tests.
It always presents these four sections:

1. What HUGO knows.
2. What HUGO does not know.
3. Why HUGO cannot proceed.
4. The safe next step.

## Mapper architecture

`ProviderBlockedEvidence` is a typed presentation adapter over accepted H022-H032 enums. It does not
copy their reasoning or inspect raw transport/provider data. `ProviderBlockedStateMapper` maps:

- H022 `IdentityResolutionStatus`;
- H023 `ManifestResolutionStatus`;
- H024 report status and finding codes;
- H013 connection availability;
- H029 negotiation status/reason;
- H030 authorization decision status;
- H031 calibration/readiness status and semantic item IDs;
- H008 provider runtime availability; and
- H032 known/disabled/retired knowledge status.

Accepted successful states return `NotBlocked`; the mapper never manufactures a blocked card for a
resolved, compatible, available, negotiated, authorized, or verified state. An unmapped failure
returns an honest generic blocked state instead of guessing. Inputs and details are canonically
mapped, so equivalent evidence produces equivalent UI output.

Compose receives only `ProviderBlockedUiState`. It does not import or branch over H022, H023, H024,
H030, H031, or H032 internals. This keeps presentation reusable by a later provider list, Control
Center, or universal dashboard without moving domain decisions into Compose.

## Represented blocked states

The typed model represents unknown, ambiguous, conflicting, and stale identity; missing/conflicting
manifest; missing/incompatible adapter; incompatible or unsupported provider; unavailable
connection; unnegotiated capability; missing trust, permission, or approval; required or invalid
calibration; unavailable provider runtime; explicit prohibited/disabled/retired state; and generic
unmapped failure.

Messages distinguish identity from manifest validity, adapter compatibility, availability,
authorization, and calibration. They never imply that detecting or recognizing a provider makes it
trusted, connected, available, calibrated, safe, selected, or executable.

## Privacy and presentation

Only `RedactedProviderAlias` can enter the UI adapter. A defense-in-depth display filter suppresses
aliases containing UUID, IPv4, MAC, or account-like stable identifiers. Private provider-instance
IDs, protected identity references, endpoints, serials, credentials, tokens, raw protocol data, and
domain diagnostic payloads are neither accepted nor rendered.

`ProviderBlockedCard` is a Material 3 card with accessible heading semantics and explanatory text.
It contains no `Button`, `onClick`, clickable modifier, gesture, control callback, “try anyway” path,
automatic connection, or fallback command. Calibration detail contains only validated semantic
`CalibrationItemId` values and never guessed measurements or procedure controls.

## Next-step semantics

Every blocked state identifies one of four resolution categories:

- `USER_CAN_RESOLVE_NOW`: restore an already-defined prerequisite such as platform connection
  availability, then re-evaluate;
- `WAIT_AND_RETRY`: wait for fresh identity/runtime evidence;
- `ADMIN_OR_POLICY_REQUIRED`: trust, permission, approval, or policy requires an authorized actor;
- `FUTURE_FEATURE_REQUIRED`: provider setup, adapter installation/development, approval UI, or
  calibration procedure does not exist in H033.

These are explanatory labels only. H033 implements none of the workflows and exposes no action that
could bypass their owning architecture layer.

## Device and runtime boundary

The Compose card is a reusable component but is intentionally not routed from `MainActivity`.
There is no accepted runtime provider-blocked presentation source or provider navigation flow to
wire without inventing state. Therefore H033 creates no real debug-app screen reachable on the
Samsung, and device presentation/navigation verification is not required. `assembleDebug` verifies
the Android presentation compiles. No provider or hardware behavior is inferred from it.

## Completion evidence

`ProviderBlockedStateMapperTest` covers unknown/ambiguous/conflicting identity, missing/conflicting
manifest, missing/incompatible adapter, provider incompatibility, unavailable connection,
unsupported provider, capability negotiation failure, trust/permission/approval requirements,
required/expired/invalid calibration, stale evidence, provider unavailability, prohibited and
disabled state, deterministic output, successful `NotBlocked` behavior, absent control authority,
safe alias rendering, private-ID suppression, generic fallback, four-section text rendering, and
Compose separation/no-clickable-control source checks.

- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H022-H033 JVM tests: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `./gradlew assembleDebug`: PASS
- `git diff --check`: PASS
- Samsung UI verification: not required because the component is not reachable in the debug app
- Provider/hardware verification: not required and not claimed

## Next boundary

H034 is eligible and has not started. H033 adds no provider diagnostics/export, Control Center,
dashboard, provider setup, Developer Mode, adapter installation, approval workflow, calibration
procedure, capability-specific remote, voice, AI, memory, World Model runtime, LOOI runtime change,
provider execution, or physical behavior.
