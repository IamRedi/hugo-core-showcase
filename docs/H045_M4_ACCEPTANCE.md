# H045 M4 Personal Context and Integrations Acceptance

## Record

- Task: `H045 - M4 acceptance`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Accepted: 2026-09-01
- Accepted starting point: H044 commit `425735be3e3ce09e26f212a326c874b6d6bdc79c`
- Product/runtime change: none; H045 adds acceptance tests and evidence only
- Device: authorized Samsung `SM-S928B`
- Selected service: the ADR-019 Google Calendar events read-only feasibility path only

## Acceptance decision

M4 is accepted for the bounded, unwired foundation implemented by H036-H044:

1. H036 represents sourced known, unknown, and conflicting observations with explicit privacy,
   confidence, monotonic freshness, and expiry.
2. H037 deterministically normalizes current evidence, preserves conflicts, filters privacy, and
   enforces item/evidence/UTF-8 budgets without hidden persistence.
3. H038 projects connected provider, host, service, selected-body, and active-body state without
   turning selection or availability into authorization.
4. H039 exposes lifecycle-owned phone accelerometer/light/proximity observations and releases all
   Android sensor registrations.
5. H040 defines provider-neutral service authentication, scopes, operations, connection methods,
   rates, and typed failures without vendor SDK types.
6. H041 protects connector credentials behind explicit encrypted storage, expiry, revoke/logout,
   reset, and redacted evidence.
7. H042 presents providers, capabilities, grants, approvals, and diagnostics as a read-only shell
   with only a bounded read retry.
8. H043 proves one exact-scope, bounded, observe-only Google Calendar connect/read/revoke path in an
   isolated debug sandbox.
9. H044 produces minute-bounded local time and one-shot coarse authorized-region presence evidence
   with explicit permission denial, uncertainty, unavailability, and stale behavior.

M4 does not compose these parts into production navigation, background synchronization, AI,
memory, action selection, capability execution, or physical behavior.

## Deterministic context acceptance

`M4PersonalContextAcceptanceTest` runs H044 time and permission-denied presence observations through
the H036/H037 pipeline. Acceptance verifies:

- four `PRIVATE` local-time items plus one `RESTRICTED` presence item;
- privacy filtering removes the restricted item without treating omission as a fact;
- a two-item budget retains exactly two whole items and records three item-budget omissions;
- denied presence remains `Unknown(PERMISSION_DENIED)`, never `false`;
- presence disappears at its exact 30-second expiry and local time at the exact next-minute expiry;
- the canonical snapshot contains no authorization field; and
- source batches persist no history and grant no trust, permission, authorization, safety, or
  execution authority.

H038 provider loss/expiry, H039 lifecycle release, H041 credential failure, and H042 revoked/error
states retain their focused acceptance evidence and are rerun by the full suite.

## Connector, credential, and revoke acceptance

The selected H043 adapter remains the only concrete service adapter. Its H040 profile is exactly:

- `calendar.events.list@1.0`;
- effect and permission intent `OBSERVE`;
- `https://www.googleapis.com/auth/calendar.events.readonly` as the sole external scope;
- deterministic GET-only event listing; and
- no insert, update, delete, send, polling, pagination loop, or background worker.

The H045 fake integration composes H040, H041, and H043 for one bounded two-item response. It proves
that private event payload text is discarded, returned evidence contains count only, and connection
evidence grants no trust, authorization, registration, runtime connection, or execution. Revoke is
observed in exact `remote_revoke -> local_write` order; a recreated H041 boundary returns
`Revoked`, and the current metadata-only document contains no fixture token bytes.

The real authorized-account H043 acceptance is reused rather than requesting account access again.
The H043 adapter/platform implementation has no changed file since commit
`33fe6927c7e47d313c0057ca006e98bbe9197edd`. Its accepted Samsung evidence remains one exact-scope
connection, one read of five event records with payload discarded, then successful remote and local
revoke. H045 made no Calendar network request, changed no OAuth consent, and incurred no new service
usage.

## Hidden-persistence and privacy audit

Production sources for H036-H040, H042, and H044 contain no SharedPreferences, DataStore, Room,
SQLite, file output, WorkManager, alarm, boot receiver, or other history mechanism. H041 is the only
intentional persistence boundary in this milestone and stores authenticated encrypted credentials,
not context history. H043 uses H041 only for the explicitly authorized credential lifecycle.

The production manifest contains no background-location permission or boot receiver. Current
Android versions receive only coarse foreground location for H044. Control Center has no Connect,
Authorize, Approve, Execute, or Edit-source callback; its sole non-navigation callback is
`Retry read-only refresh`.

Generic context, service, credential, and Control Center packages remain free of Google Calendar,
Google Android SDK, LOOI, BLE/GATT, and FED protocol coupling. Raw coordinates, sensor values,
account identity, tokens, event payloads, provider IDs, transport details, and protocol details are
absent from normal acceptance evidence.

## Samsung acceptance

After one transient laptop-to-phone ADB interruption before installation, the authorized Samsung
returned as `device` and remained connected for the complete rerun. The current debug and
androidTest APKs installed successfully. Eight tests passed in one instrumentation run:

- H041 Keystore store/restore/logout/reset and tamper/unsupported-version fail-closed: 2;
- H042 Control Center screen, empty, error/retry, and revoked/read-only states: 4;
- H039 real phone sensor acquisition and resource release: 1; and
- H044 local time plus authorized coarse-presence bounded acquisition: 1.

The H044 run again received no usable current fix and correctly returned
`Unknown(SOURCE_UNAVAILABLE)`. A temporary coarse foreground grant was revoked in `finally`, and the
post-run package state confirmed `granted=false`. Redacted traces contained only credential,
source/value, and location redaction markers; no private value was recorded.

No LOOI connection, movement, head/light command, manual control, safety policy, camera, voice, AI,
memory, or additional service was exercised.

## Verification matrix

| Check | Result |
|---|---|
| H045 context/privacy/budget/revoke/hidden-persistence acceptance tests | PASS, 5 tests |
| Focused H036-H044 plus architecture tests | PASS |
| Full `testDebugUnitTest` | PASS, 751 tests in 105 suites; zero failures/errors/skips |
| Samsung M4 acceptance instrumentation | PASS, 8 tests |
| H043 accepted account connect/read/revoke evidence | PASS; implementation unchanged, not repeated |
| Temporary coarse permission cleanup | PASS; not granted after run |
| `lintDebug` | PASS |
| `assembleDebug` and `assembleDebugAndroidTest` | PASS |
| HUGO V2 documentation guard | PASS |
| First Life documentation/order guard | PASS |
| T032 baseline evidence guard | PASS |
| Architecture dependency guard | PASS |
| Secret/privacy/background/persistence scans | PASS |
| `git diff --check` | PASS |

## Risks and boundaries after M4

- Context and integrations remain isolated/unwired; production composition and user-facing source
  configuration are future work.
- The Samsung did not provide a current coarse-location fix during the bounded H044/H045 windows.
  Known inside/outside behavior is deterministic-test evidence, not a positive physical-location
  claim.
- The Google Calendar feasibility bridge is debug-only. OAuth SDK behavior, quotas, pricing, and the
  existing cloud billing association require revalidation before any production rollout.
- H043 transiently materializes token/JSON strings inside platform APIs; it logs and persists none,
  but assumes an uncompromised Android process and OS.
- Control Center is explanatory and read-only; it is not yet production navigation or a complete
  settings/dashboard product.

These are explicit future integration/product risks, not acceptance failures. Privacy, revoke,
freshness, conflict, budget, lifecycle, secret, and non-authority invariants pass. **M4 is accepted.**

## Next boundary

H046 is eligible and has not started. H045 adds no voice, AI, memory, local intent routing, model
gateway, skill execution, provider action, LOOI behavior, or physical capability.
