# H052 Gateway Authentication

## Record

- Task: `H052 - Gateway authentication`
- Status: `DONE`
- Implementation date: 2026-09-02
- Prerequisites: H041 protected credential storage and H051 gateway contract
- Runtime impact: none; the boundary is not composed into the app or an H053 gateway client
- Deployment claim: none; no backend, production origin, token issuer, account, or provider is selected

## Short-lived scoped app identity

The Android-free `gateway/auth` boundary models one client-held gateway access token. A token is
bound to a redacted random `GatewayClientSessionId`, redacted `GatewayTokenId`, exact gateway origin,
one or more text/realtime/vision/embedding scopes, issue time, expiry, revision, and optional local
revocation evidence. Lifetime is bounded to 30 through 900 seconds. The client-session reference is
ephemeral gateway correlation; it is not H003 `HugoIdentity`, Android installation identity, phone
identity, user account, provider, body, or AI-provider identity.

Only one token record is stored. Initial store requires no current token. Rotation requires the exact
current token ID and increments its revision. Missing, unexpected, mismatched, or duplicate current
identity fails closed; nothing is silently replaced. H052 neither acquires nor refreshes a token.

```text
TOKEN STORED / UNEXPIRED
!= GATEWAY SESSION AUTHENTICATED
!= PROVIDER SELECTED
!= HUGO TRUSTED
!= H014/H030 AUTHORIZED
!= SAFE
!= CONNECTED
!= CAPABILITY EXECUTED
```

Snapshots expose `hasActiveToken` separately from the always-false `authenticatesGatewayNow` and all
trust, authorization, safety, connection, and execution implications.

## Token access, expiry, scope, audience, and revoke

`GatewayAccessToken` takes a defensive byte copy, never converts token plaintext into a `String`,
redacts diagnostics, and explicitly zeroes its owned bytes. `withValidToken` is the only read path.
It checks endpoint policy, exact audience, non-empty required scope, token state, and exact expiry
before providing a fresh byte copy to one synchronous callback. The callback copy and restored
document copy are zeroed in `finally`.

Expiry is strict at `expiresAt`. Audience mismatch, insufficient scope, forbidden endpoint, corrupt
storage, and unavailable storage are distinct failures and never invoke the callback. Logout/revoke
atomically replaces secret-bearing state with a metadata-only tombstone. Explicit recovery requires
`ExplicitGatewayAuthReset`; no corrupt or unreadable token is silently replaced.

## Endpoint and Android network policy

`GatewayEndpointPolicy` receives configuration rather than inventing a source-precedence or
deployment choice. Release mode accepts only exact allowlisted HTTPS origins, including exact port.
It rejects cleartext, unlisted origins, user-info, paths, queries, fragments, loopback, and numeric
release allowlist entries. Debug mode may additionally use cleartext only for `localhost`,
`127.0.0.1`, or Android-emulator host `10.0.2.2`; remote debug cleartext remains denied.

The main Android network-security resource denies all cleartext. The debug override permits only the
same three loopback names. The merged release manifest has `usesCleartextTraffic=false` and no
`INTERNET` permission. H053 may add a client only after a deployment and endpoint decision; H052
itself sends no request.

## Protected persistence reuse

`AndroidProtectedGatewayTokenStorage` maps the H052 storage port onto the already verified H041
Android Keystore/AES-256-GCM atomic blob implementation. It uses a dedicated `gateway-auth-v1`
subdirectory and dedicated `hugo.gateway.auth.v1` key alias, so service credentials and gateway
tokens do not share files or keys. This preserves H041 authenticated encryption, randomized nonce,
strict envelope version, atomic pending-file replacement, filesystem lock, tamper/truncation
detection, missing-key behavior, and delete-key-first reset semantics without creating a second
cryptographic storage design.

The inner gateway document is a strict bounded binary schema version `1`. It preserves token IDs,
client-session correlation, exact origin, sorted scopes, issue/expiry, revision, revoke evidence,
and secret bytes. Unsupported, contradictory, trailing, malformed, oversized, duplicate-scope, or
secret/tombstone-inconsistent data fails closed.

## Threat checklist

| Threat | Control / current result |
|---|---|
| Provider key shipped in APK | No provider key field, constant, dependency, or adapter; source and release APK known-prefix scans pass. |
| Token disclosed from app-private file | Non-exportable Keystore AES key plus authenticated ciphertext; plaintext fixture must not occur in the encrypted file. |
| Token logged | IDs, origin, metadata, mutation evidence, and token `toString` are redacted; instrumented evidence is content-free. |
| Expired/revoked token reused | Exact expiry and persistent tombstone reject access before callback. |
| Token used for wrong task or gateway | Required scopes and exact audience are checked before access. |
| Silent token replacement | Rotation requires exact current token identity; duplicate/mismatch fails closed. |
| Cleartext release traffic | Main resource and merged release manifest deny cleartext; release policy requires exact HTTPS origin. |
| Arbitrary debug cleartext | Both domain and Android config restrict it to three loopback targets. |
| Corrupt/tampered/unsupported storage | Authenticated envelope and strict inner schema fail closed; reset is explicit. |
| Token mistaken for HUGO/provider authority | Snapshot/evidence flags are false; no H003, provider, trust, authorization, safety, or capability types cross the boundary. |
| Network/backend silently introduced | No HTTP library/client, INTERNET permission, configured production origin, issuer, refresh flow, or backend exists in H052. |

The normal Android sandbox and uncompromised OS/Keystore are assumed. A privileged/rooted runtime
and a future callback that intentionally copies token bytes are outside H052. H053 must preserve the
bounded callback and redacted-diagnostic rules.

## Verification

- focused H052 and architecture JVM tests: PASS
- `assembleDebugAndroidTest`: PASS
- `lintDebug`: PASS, zero errors
- `assembleRelease` and `lintVitalRelease`: PASS
- merged release manifest: cleartext false and INTERNET permission absent
- main-source known production-secret prefix scan: PASS
- unsigned release APK known production-secret prefix scan: PASS
- Samsung `SM-S928B` Keystore instrumentation: PASS, 2 tests in 2.009 seconds; zero failures/errors/skips

`GatewayTokenRepositoryTest` covers secure store/restore access and zeroization, expiry, revoke,
scope/audience/endpoint denial, exact rotation, duplicate and failed-write preservation, release/debug
endpoint matrices, strict parsing, lifetime/secret bounds, corruption/version/storage failures,
explicit reset, deterministic persistence, non-authority fields, network-security resources, and
dependency/secret boundaries.

The Samsung run verified real Keystore store and repository recreation, bounded token access,
ciphertext exclusion of the random plaintext fixture, exact-expiry rejection, persisted revoke
tombstone, and explicit reset. Its second test modified authenticated ciphertext and injected an
unsupported envelope; both failed closed. Evidence was limited to:

```text
H052_ANDROID=keystore_restore_expiry_revoke_fail_closed;token=redacted;endpoint=redacted
H052_ANDROID=tamper_and_version_fail_closed;token=redacted;endpoint=redacted
```

No real gateway token, network request, provider, account, camera, microphone, LOOI, capability, or
physical action was involved. Isolated files and Keystore aliases were removed in test cleanup.

## Next boundary

H053 is eligible and has not started. H052 adds no token acquisition/refresh, issuer verification protocol,
backend, gateway network client, streaming reducer, provider/model selection, AI context, tool
mediation, conversation runtime, or execution.
