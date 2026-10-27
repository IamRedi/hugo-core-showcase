# H040 ServiceConnector Contract

## Record

- Task: `H040 - ServiceConnector contract`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Completed: 2026-08-31
- Prerequisites: H008, H013, and H014 through H039 commit
  `bab61f73165cff4f75d86db63384be49297ac48e`
- Runtime impact: none; H040 adds an unwired pure contract, evaluator, fake, and trace fixture
- Hardware/account claim: none

## ServiceConnector API

`ServiceConnector` extends the existing H008 `CapabilityProvider`. It adds only a versioned
`serviceContract` declaration and a generation-owned `serviceSnapshot`. Negotiation, capability
registration, semantic execution, and cancellation remain the H008 methods; H040 deliberately does
not create a second service-operation dispatch path.

The service contract binds one public provider-definition ID to:

- an H011-compatible semantic authentication-scheme reference;
- a bounded set of semantic service authorization-scope references;
- a deterministic list of capability-to-service operation definitions;
- one generic H013 connection-method reference per operation; and
- optional bounded rate-policy references and per-operation costs.

Operation definitions contain no URL, route, HTTP method, SDK request, response DTO, vendor error,
account identity, or payload. Capability descriptors remain authoritative for H006 effect,
permission-intent, approval, risk, cancellation, and evidence semantics. A fake service proves that
its declared operations register through the normal H008 registration API.

## Authentication and scope separation

Authentication session state is explicit as `UNAUTHENTICATED`, `AUTHENTICATED`, `EXPIRED`,
`REVOKED`, or `FAILED`. An authenticated session requires only an opaque project-owned UUID
correlation reference, current provider generation, granted semantic scopes, observation time, and
optional expiry. The reference is not an access token, refresh token, cookie, account ID, API key,
or credential. H040 stores none of those values; their future protected boundary belongs to H041.

Service authorization scopes describe what the external service session reports. They are a
different Kotlin type from H014 permission scopes and grants. Consequently:

```text
SERVICE AUTHENTICATED
!= HUGO TRUSTED
!= H014 AUTHORIZED
!= APPROVED
!= CONNECTED
!= OPERATION EXECUTED
```

Contradictory auth state/session/scope/expiry/failure combinations are rejected at construction.
Expired and revoked snapshots retain at most an opaque local session reference and expose no active
scopes. Granted scopes not declared by the service contract produce an explicit contract mismatch.

## Connection, rate, and error evidence

Connection evidence wraps only an H013 `ConnectionAvailabilitySnapshot` plus a bounded exclusive
freshness time. It does not expose endpoints or establish a channel. Missing, unavailable, future,
or stale evidence remains distinct, and `establishesConnection` is always false.

Rate policies have a semantic ID, bounded unit limit, and bounded window. Generation-owned rate
evidence is either `KNOWN` with non-negative remaining units and a future reset boundary or
`UNKNOWN` with no invented numbers. Assessment detects missing/unknown state, stale reset evidence,
policy-limit/window contradictions, and insufficient remaining units. Rate-limited evidence retains
only a monotonic retry time.

`ServiceOperationFailure` supplies provider-neutral codes for authentication, scope, unsupported
operation, rate limiting, connection unavailability, timeout, cancellation, transient/permanent
failure, malformed response, and unknown failure. Retry times are accepted only for explicitly
retryable codes; rate limiting requires one. The type has no free-form message, vendor code,
exception, or raw response field.

## Deterministic assessment

`ServiceOperationAssessor` consumes already-collected H004/H008/H013/H040 evidence. It checks,
without side effects:

- provider family and provider-definition binding;
- provider, registration, auth, and rate generations;
- protected provider-instance correlation on H008 registration;
- exact operation-to-capability registration;
- current H008 runtime availability;
- authentication state and expiry;
- declared versus granted service scopes;
- H013 connection-method availability and freshness; and
- rate policy, state, freshness, remaining units, and retry boundary.

The report is `PRECONDITIONS_SATISFIED`, `BLOCKED`, or `UNKNOWN`, with deterministic checked
dimensions, typed findings, matched/missing scope evidence, and optional rate evidence. Degraded
provider or stale/missing evidence remains `UNKNOWN`; definitive mismatch, denial, expiry,
unavailability, or rate exhaustion is `BLOCKED`.

Even `PRECONDITIONS_SATISFIED` means only that the supplied technical evidence is internally
compatible. Every report exposes `grantsTrust=false`, `grantsAuthorization=false`,
`establishesConnection=false`, and `executesOperation=false`. H030/H014 and any later connection or
dispatch runtime remain independently required.

## Fake trace and privacy

The canonical fake uses a fictional calendar-style service definition and read-only capability. Its
redacted trace records public provider definition/family, semantic capability, status, checked
dimensions, findings, scope counts, and rate/retry values. It intentionally omits provider-instance
ID, auth-session reference, scope names, account identity, credentials, tokens, connection endpoint,
vendor SDK data, and service payloads.

The fixture `h040-service-connector-trace-v1.json` is deterministic and portable. It is test
evidence only; H040 adds no logging, persistence, network request, account sign-in, credential flow,
or real service integration.

## Test and completion evidence

`ServiceConnectorContractTest` covers H008 capability registration, technically satisfied evidence,
non-authority semantics, unauthenticated/expired/revoked/failed auth, missing and undeclared scopes,
available/missing/stale connection evidence, rate-limit/unknown/stale state, bounded retry semantics,
provider family/definition/instance/generation mismatch, registration mismatch, unsupported
operations/schema, duplicate/undeclared contract metadata, contradictory snapshots, defensive copy,
deterministic ordering, redacted canonical trace, and platform/vendor/credential leakage checks.

Final completion checks:

- HUGO V2 documentation guard: PASS
- First Life documentation/order guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- focused H008/H013/H014/H040 JVM tests: PASS
- full `testDebugUnitTest`: PASS, 707 tests in 100 suites; zero failures/errors/skips
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `git diff --check`: PASS
- pure service API platform/vendor/credential leakage scan: PASS
- Android/device/real-service verification: not required; no runtime or vendor connector exists
- LOOI/T032 impact: none

## Next boundary

H041 is eligible and has not started. H040 adds no credential storage, OAuth flow, account binding,
network client, real connector, provider registration mutation, authorization runtime, UI,
dashboard, AI, memory, LOOI behavior, safety policy, or physical execution.
