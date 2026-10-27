# H041 Connector Credential Boundary

## Record

- Task: `H041 - Connector credential boundary`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Implementation date: 2026-08-31
- Prerequisite: H040 commit `d74cb74af207a04f067bc807d8e782a077b30dad`
- Runtime impact: none; the boundary is not composed into a connector or application flow
- Service/account claim: none; no OAuth provider, account, endpoint, or production credential exists

## Boundary and API

`service/auth` owns one deterministic protected credential lifecycle without changing H040. A
`ServiceCredentialId` is an opaque local UUID reference. `ServiceCredentialMetadata` binds it only
to the public provider definition, H040 authentication scheme, semantic service scopes, wall-clock
creation/expiry, record revision, and optional local revocation evidence. It stores no account ID,
H040 auth-session reference, H014 grant, provider instance, body, connection, permission, or
operation state.

`ServiceCredentialBoundary` provides immutable redacted snapshots, create-only storage, bounded
credential access, explicit logout/revoke, optimistic store generation and record revision checks,
and typed explicit reset. Exact duplicate IDs and stale mutations are rejected; records are
canonically ordered. It does not select an account, authenticate a connector, refresh a token,
contact a provider, or execute a capability.

The critical separation is preserved:

```text
credential known/stored
!= service authenticated
!= HUGO trusted
!= H014 authorized
!= connected
!= available
!= operation executed
```

Every snapshot and mutation evidence object reports all authority/connection/execution implications
as false.

## Secret policy and lifetime

`ServiceCredentialSecret` accepts a bounded non-empty byte array, takes a defensive copy, never
converts plaintext to `String`, redacts `toString`, and supports explicit zeroization. The store
codec is deterministic binary rather than JSON so plaintext credential bytes do not require an
immutable JVM string. The caller closes input material after storage.

`withCredential` is the only read path. It supplies a fresh byte copy to one synchronous bounded
callback and zeroes that copy and the restored document copy in `finally`. It returns no credential
object that can be retained by the boundary. A connector remains responsible for not copying or
logging its short-lived callback input.

Expired and revoked records never enter the callback. Logout/revoke atomically replaces the active
record with a metadata-only tombstone; the rewritten current document contains no secret bytes.
Local logout is deliberately not a claim that a remote provider session or grant was revoked.

## Storage and schema

`ProtectedServiceCredentialStorage` is the Android-free port. The Android implementation lives in
`platform/service/auth` and receives an application-private no-backup directory from future
composition. It uses:

- a non-exportable Android Keystore AES-256 key;
- randomized AES/GCM encryption with a 128-bit tag and fixed store/version associated data;
- a strict `1` envelope containing only envelope version, algorithm, key version, nonce, and
  ciphertext;
- a bounded deterministic binary credential-document schema version `1`;
- a same-directory pending file, flush plus `fsync`, and atomic replace; and
- a file lock for process/thread serialization.

Unsupported envelope/document versions, authentication failure, malformed metadata, duplicate
records, missing key, truncated data, and storage failure all fail closed. No automatic reset,
credential replacement, or default invention occurs. Reset requires an
`ExplicitCredentialStoreReset` and deletes the Keystore key before encrypted files so interruption
cannot leave readable material.

## Threat report

| Threat | Control / result |
|---|---|
| Production secret extracted from APK | No secret constant, account, OAuth configuration, or provider SDK is added; source/resource and built APK prefix scans return zero matches. |
| App-private file disclosure | The file contains only authenticated ciphertext and non-secret envelope metadata; plaintext is protected by a non-exportable Keystore key. |
| Ciphertext modification/truncation | AES-GCM authentication and strict bounded envelope/document decoding fail closed. |
| Expired credential reuse | Wall-clock expiry is checked before callback access; exact expiry is rejected. |
| Logout/revoked credential reuse | Secret bytes are removed from the current atomic document and tombstone state rejects access after repository recreation. |
| Lost Keystore key or unsupported schema | No replacement credential is generated; corrupt/unsupported/unavailable remain typed failures until explicit recovery. |
| Secret in diagnostics | IDs are redacted, secret `toString` is fixed, evidence contains counts/state only, and Android tests print only redacted status. |
| Credential mistaken for authority | Domain types expose no trust/grant/connection/runtime fields; explicit implication flags remain false. |
| Partial write | Pending write is synced and atomically replaces the primary; failure reports `FAILED_PRESERVING_PREVIOUS`. |
| OAuth/provider coupling | H041 implements no OAuth flow, endpoint, HTTP client, provider choice, account selection, refresh, or remote revoke. |

The boundary assumes the normal Android application sandbox and uncompromised OS/Keystore. A rooted
device with privileged memory inspection or rollback of both application-private state and platform
security state is outside H041. Plaintext must necessarily exist briefly in the authorized future
connector callback; the callback's handling remains a later adapter responsibility.

## Verification

Local verification completed:

- HUGO First Life documentation/order guard: PASS
- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- focused H040/H041 and architecture tests: PASS
- full `testDebugUnitTest`: PASS, 716 tests in 101 suites; zero failures/errors/skips
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `assembleDebugAndroidTest`: PASS
- source/resource production-secret scan: PASS, zero matches
- built debug APK known production-secret prefix scan: PASS, zero matches
- `git diff --check`: PASS

Samsung `SM-S928B` Android instrumentation: PASS, two tests in 0.37 seconds. The run verified real
Keystore encrypted storage and repository recreation, exact bounded secret recovery before expiry,
logout tombstone rejection after recreation, ciphertext absence of the plaintext fixture, explicit
reset, authenticated-ciphertext tamper rejection, unsupported envelope rejection, and cleanup of
isolated no-backup test stores. Instrumentation/log evidence contained only
`credential=redacted`; the device serial and generated fixture bytes are not recorded.

## Next boundary

H041 is complete. H042 is eligible and has not started. No OAuth provider, service account,
production credential, network connector, runtime composition, UI, LOOI, safety, or physical-body
behavior was added or exercised.
