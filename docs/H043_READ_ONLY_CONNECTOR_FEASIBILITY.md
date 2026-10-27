# H043 Read-Only Connector Feasibility

## Record

- Task: `H043 - Read-only connector feasibility`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Completed: 2026-09-01
- Prerequisite: H042 commit `e8e9a25d5a4f058d6573294cac28b5b0e27841a6`
- Production runtime impact: none; the feasibility activity exists only in the debug source set
- Authorized-account claim: one user-driven Google Calendar test account, recorded only as redacted evidence
- LOOI/physical claim: none

## ADR and selected service

ADR-019 selects Google Calendar event observation for the one H043 feasibility path. The official
API provides an events-only read scope, a documented list operation, and explicit OAuth revocation.
Gmail, Drive, and an unauthenticated public API were rejected because they either widen the
privacy/action surface or fail to exercise the H041 credential lifecycle.

The adapter profile declares exactly:

- provider definition: `google.calendar/service`;
- capability: `calendar.events.list@1.0`;
- HUGO semantic scope: `scope.calendar/events_readonly@1.0`;
- external OAuth scope: `https://www.googleapis.com/auth/calendar.events.readonly`;
- authentication scheme: `auth.standard/oauth@1.0`;
- connection method: `network.http/request-response@1.0`; and
- effect/intent: observe-only, informational, non-physical.

No source precedence, provider registration, account identity, trust, H014 authorization, approval,
connection availability, or execution authority is inferred from this profile.

## Connect, read, and revoke

The debug Android feasibility activity uses Google Play services `AuthorizationClient` to request
the exact external scope. Granted scopes must equal the one-element expected set; missing, extra,
or write-capable scopes fail before credential storage. The access token is copied into H041's
protected credential boundary, the temporary byte array is zeroed, and no refresh/offline token is
requested. The Google project contains an Android client restricted to the debug package and
signing certificate; no client secret or API key is embedded in the APK.

Reads are deterministic GET requests to the primary-calendar `events.list` endpoint. The domain
request allows at most 10 results and a 31-day window; the physical harness uses one seven-day
window and 10 results. Responses are capped at 256 KiB. The adapter parses only the event count and
whether another page exists, zeroes transient byte copies, and returns no event field or payload.
It performs no pagination, polling, background synchronization, insert, update, delete, email, or
other action.

Remote revoke must return success before H041 writes the local metadata-only tombstone. A failed
remote revoke preserves the local credential for an explicit retry. If remote revoke succeeds but
local tombstoning fails, the typed result records that remote access was revoked and fails closed.

## Failure and evidence model

The sandbox reports explicit invalid request, scope mismatch, credential missing/expired/revoked,
storage failure, network unavailable, timeout, cancellation, remote rejection, rate limit, and
malformed response outcomes. HTTP status 401, 403, 429, and other non-success values remain typed;
transport or Google SDK types do not enter H040 or HUGO Core.

Connection, read, revocation, and diagnostic evidence is redacted. It may contain a scope count,
event count, pagination flag, timestamps, store generation/revision, and typed status. It contains
no account email, account ID, client ID, credential ID text, access token, event title, attendee,
location, response body, endpoint detail, or raw exception. Every evidence type explicitly leaves
trust, authorization, registration, connection, and execution implications false.

```text
READ-ONLY CONNECTOR AUTHENTICATED
!= HUGO trusted
!= H014 authorized
!= provider registered
!= runtime connected/available
!= capability executed
```

## Quota and cost boundary

Google's Calendar API usage documentation current on 2026-09-01 states that standard usage is
available at no additional cost, with limits of 10,000 requests per minute per project, 600 per
minute per user per project, and a 1,000,000-request daily threshold before planned later-2026
billing. The H043 account run issued exactly one Calendar API request. The harness has no retry
loop, pagination, periodic worker, foreground polling, or production route, so it cannot approach
those limits through ordinary H043 execution.

The test Cloud project was observed with an existing billing association and a displayed current
cost of EUR 0.00. No purchase, quota increase, paid API, API key, service account, or subscription
was created. Billing association is not treated as connector authority. Pricing and thresholds are
external policy and must be reviewed again before any production rollout or background connector.

## Verification

Prepared automated coverage verifies:

- the exact H040 observe-only contract and external single scope;
- bounded deterministic GET construction and rejection of action routes;
- H041 storage plus repository recreation without identity or payload leakage;
- rejection of missing, extra, and write-capable scopes;
- missing, expired, revoked, corrupt, malformed, oversized, and contradictory failure paths;
- typed remote status, timeout, cancellation, and unavailable results;
- remote-before-local revoke ordering and explicit retry semantics;
- deterministic evidence independent of response field/item order; and
- non-authority, no registry mutation, and no production/LOOI coupling.

Final checks:

- HUGO First Life documentation/order guard: PASS
- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- focused H040/H041/H043 and architecture tests: PASS
- full `testDebugUnitTest`: PASS, 734 tests in 103 suites; zero failures/errors/skips
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `git diff --check`: PASS

Samsung `SM-S928B` authorized-account evidence: PASS. The debug activity confirmed one exact scope,
then performed one bounded read that observed five event records and immediately discarded the
payload. Remote revoke and the local H041 tombstone both passed; read/revoke controls became
unavailable until a new explicit authorization. Normal logs contained only:

```text
H043_ACCOUNT=connected;scopeCount=1;credential=redacted
H043_ACCOUNT=read;eventCount=5;payload=discarded
H043_ACCOUNT=revoked;credential=redacted
```

No account identity, event content, token, client ID, endpoint, or device serial is retained in the
repository evidence. The account chooser and consent UI were user-controlled. No LOOI connection,
body, movement, safety path, camera, voice, AI, memory, or provider runtime was exercised.

## Risks and next boundary

Google OAuth configuration can take minutes or hours to propagate; an earlier pre-configuration
attempt failed closed with Google status 8 and stored no credential. The debug HTTP bridge must
transiently materialize token and JSON strings for platform APIs; they are never logged or retained,
but an uncompromised process/OS remains an assumption. Google quota/pricing policy and OAuth SDK
behavior are external dependencies and require revalidation before production use.

H043 is complete. H044 is eligible and has not started. H043 grants no authority to begin H044 in
this task.
