# H042 Control Center Shell

## Record

- Task: `H042 - Control Center shell`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Implementation date: 2026-09-01
- Prerequisites: H033, H038, H040, and H041 through commit
  `87e1fc846ab4a561a58de60f8200d151623b5714`
- Runtime impact: none; the shell is not wired into the production HiHugo route
- Hardware/service claim: none

## Read-only shell

`feature/controlcenter` provides one compact Compose shell with five explicit sections:

- Providers
- Capabilities
- Grants
- Approvals
- Diagnostics

The shell renders immutable evidence and section counts, supports local tab navigation, explains
section-specific empty states, and fails closed on unavailable, stale, corrupt, or unsupported
source evidence. The only optional callback is `Retry read-only refresh`; no control binds to a
provider, edits a source, creates a grant, approves a request, connects, revokes canonical state, or
executes a capability.

The snapshot states this boundary directly:

```text
CONTROL CENTER VISIBLE
!= provider trusted
!= permission granted
!= request approved
!= provider connected
!= capability executed
```

## Canonical source projection

`ControlCenterProjector` accepts read-only canonical evidence from:

- H038 active-provider context;
- H034 redacted provider diagnostics and H033 blocked-state codes;
- H040 service authentication snapshots;
- H041 credential metadata snapshots, never credential bytes;
- H014 grants, approvals, validity windows, and revocations.

Private provider-instance, auth-session, credential, grant, and approval identifiers are used only
inside the projector for exact correlation. Output references are one-way SHA-256-derived local
`cc_` labels. Provider definitions, capability references, family names, status enums, and already
redacted aliases are the only domain values rendered.

Duplicate service snapshots for one definition are rejected as conflicting source evidence rather
than selected by ordering. A source-level error discards partial items and produces an error screen;
it never mixes stale records with current-looking truth. Collections are bounded, immutable,
canonically ordered, and reject duplicate output references or sensitive-shaped text.

## State semantics

Providers distinguish known, available, connected, degraded, blocked, revoked, and unavailable.
Service authentication and protected credential knowledge are separate fields, so authenticated,
expired, and revoked evidence cannot be inferred from one another.

Capabilities distinguish negotiated, partially negotiated, blocked, and unknown. Grants and
approvals distinguish active, expired, revoked, denied, consumed, and requested states. H014
revocations override validity and record state in the presentation. No presented `ACTIVE` state
creates authority; it only reports the supplied H014 evidence.

Diagnostics reuse H034 redacted snapshots and H033 blocked/resolution codes. Raw exceptions,
transport addresses, account IDs, credentials, protocol values, stable provider IDs, or vendor
payloads have no presentation field.

## Empty, error, and revoked usability

An entirely empty snapshot states that HUGO can run without a connected/configured provider and
explains why each other section is empty. An error snapshot states that nothing changed and no
authority was granted, with an optional bounded read retry. Revoked provider, authentication,
credential, grant, and approval states remain visible and use explicit warning treatment. There is
no “try anyway” or exploratory-control path.

## Verification

Prepared tests cover:

- empty and error state construction and non-authority flags;
- canonical ordering, immutable collections, bounds, duplicate and unsafe-text rejection;
- deterministic section reducer behavior;
- H040 authentication plus H041 credential-metadata projection without reference leakage;
- H014 grant/approval expiry and revocation precedence;
- conflicting service evidence and source-error fail-closed behavior;
- transport/vendor/LOOI/source-editor boundary scans;
- Compose navigation across all five sections;
- empty, error/retry, and revoked screen semantics;
- absence of Connect/Authorize/Execute/Edit-source actions; and
- real Android raster capture for screen evidence.

Final checks:

- HUGO First Life documentation/order guard: PASS
- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- H042 projector/state tests and boundary scans: PASS
- full `testDebugUnitTest`: PASS, 724 tests in 102 suites; zero failures/errors/skips
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `assembleDebugAndroidTest`: PASS
- `git diff --check`: PASS

Samsung `SM-S928B` instrumentation: PASS, four tests in 4.09 seconds. It verified all five tabs,
empty explanations, error plus bounded retry, revoked/expired/consumed presentation, absence of
Connect/Authorize/Execute/Edit-source actions, and a non-empty real Android raster. The first test
run exposed only an instrumentation gesture issue when the test tried to click an off-viewport tab;
the harness was corrected to scroll before click and the full class then passed. Production model,
authority, and runtime behavior did not change.

Screen evidence:
[historical private-device screenshot omitted from this showcase].
It contains only a fictional public service definition and explicit revoked/read-only statuses; no
private identifier, account, credential, endpoint, transport, or protocol detail is present.

No LOOI, physical body, service account, OAuth flow, credential access, provider connection, or
capability execution was required or exercised.

## Next boundary

H042 is complete. H043 is eligible and has not started. The Control Center remains an isolated
read-only shell; production navigation/composition and full dashboard polish remain later work.
