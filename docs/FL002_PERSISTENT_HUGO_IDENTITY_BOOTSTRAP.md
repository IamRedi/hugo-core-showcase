# FL002 - Persistent HUGO Identity Bootstrap

## Record

- Task: `FL002 - Persistent HUGO identity bootstrap`
- Status: **DONE**
- Implementation date: 2026-08-26
- Accepted base: FL001 commit `8130ef6398082c2cadf1e26152f5b0dcc6549b03`
- H003 semantics: unchanged and reused directly
- Production body/transport impact: none
- H036: not started

The implementation, automated checks, and required Samsung force-stop/relaunch continuity run are
complete. FL003 is the next eligible task and remains unstarted.

## Bootstrap architecture

`firstlife/identity/HugoIdentityBootstrap.kt` is an Android-free application-domain boundary over
the existing H003 `HugoIdentity`, `HugoIdentityId`, and `HugoIdentityCodec`. It introduces no second
identity model. Its only collaborators are:

- `HugoIdentityBootstrapStorage`, a create-once persistence port;
- `HugoIdentityEntropySource`, which supplies a random UUID only after storage proves absence;
- a project-owned, per-process `HugoBootstrapRunReference`; and
- `HugoIdentityBootstrapSnapshot`, containing only the H003 identity when ready, typed bootstrap
  status, run reference, stable redacted display evidence, and recovery state.

The snapshot has no user, transcript, account, provider, body, connection, permission, active-body,
voice-session, AI, trust, or authorization field. `HugoSafeDisplayIdentity` is `HUGO-` plus eight
uppercase hexadecimal characters derived from SHA-256 of the opaque UUID. It is diagnostic
correlation, not another identity or an authentication credential.

`HugoFirstLifeApplication` performs bootstrap in `Application.onCreate`, before any Activity or body
composition. The process therefore establishes or restores HUGO even when no provider, LOOI, BLE,
account, or network exists. The application exposes the immutable snapshot for later FL composition
without adding a reset UI.

## Persistence and security boundary

`platform/storage/AndroidHugoIdentityStorage.kt` is the only Android implementation. It receives the
application's `noBackupFilesDir/hugo-identity` directory and keeps Android, filesystem, Keystore,
JSON-envelope, nonce, ciphertext, and key-alias details outside `firstlife/identity` and H003.

The exact H003 JSON document is encrypted and authenticated with an Android Keystore non-exportable
AES-256-GCM key. The strict storage envelope contains only:

```text
envelopeVersion, algorithm, keyVersion, nonce, ciphertext
```

The envelope is capped at 16 KiB and bound to fixed associated data identifying this store and its
versions. The existing provider-store secure pattern was reused: Keystore AES-GCM, strict envelope,
authenticated reads, app-private files, flush plus `fsync`, atomic same-directory move, typed
failures, and explicit destructive recovery. H032 code and semantics were not reused as an identity
model or changed.

Creation acquires a cross-process file lock, rechecks absence, writes and syncs a pending file, then
uses an atomic move. `createIfAbsent` returns either `Created` or the already established document;
it never silently replaces a winner. A failed write returns no accepted identity and preserves any
existing primary. Android backup is already disabled and `noBackupFilesDir` adds a second explicit
boundary.

## Create, restore, and failure semantics

| Storage/evidence state | Snapshot status | Identity returned | Recovery |
|---|---|---|---|
| No primary; atomic verified creation succeeds | `CREATED` | New H003 identity | None |
| Authenticated primary; strict H003 decode succeeds | `RESTORED` | Exact stored identity | None |
| Storage/Keystore/read/write temporarily unavailable | `STORAGE_UNAVAILABLE` | None | Retry storage |
| Malformed envelope, lost key, failed authentication, or malformed H003 document | `CORRUPT_IDENTITY` | None | Explicit reset required |
| Unsupported envelope/key/H003 contract version | `UNSUPPORTED_SCHEMA` | None | Explicit reset required |

Entropy is never requested when a stored primary is corrupt, unsupported, unauthenticated, or
unreadable. Repository/process recreation restores through the same authenticated path. Activity,
body, provider, connection, active-body, or voice changes do not participate in bootstrap and cannot
change the persisted document.

## Explicit reset

Reset requires an `ExplicitHugoIdentityReset.afterUserConfirmation(...)` command with a typed reason.
No production screen, Activity action, intent, service API, notification, or casual settings control
constructs that command in FL002. Reset deletes the Keystore key first and then the encrypted primary
under the same file lock. If reset fails, the old state remains absent or unreadable and bootstrap
continues to fail closed; it cannot invent a replacement. A successful reset returns `Completed`
without creating an identity. Only a later explicit application bootstrap may create a new H003
identity.

## Tests and checks

`HugoIdentityBootstrapperTest` covers first creation, exact second restore, repository recreation,
concurrent create-once behavior, corrupt and unsupported state, unavailable storage without entropy,
failed atomic creation, explicit reset success/failure, independence from body/provider/connection/
voice state, strict H003-only persisted fields, and redacted display evidence.

`HugoIdentityBootstrapInstrumentedTest` covers the real Application composition, repository and
Activity recreation, Android Keystore/no-backup persistence, ciphertext tampering, unsupported
envelope behavior, and explicit test cleanup. All four tests passed on the target Samsung.

The H019 architecture guard now includes `firstlife/identity` as a pure boundary and rejects Android,
platform, LOOI, feature, network-client, or vendor dependencies. A pre-existing randomized H032 test
was made deterministic by changing one ciphertext Base64 character to another valid Base64 character;
the asserted `AUTHENTICATION_FAILED` semantics and all H032 production code remain unchanged.

| Check | Result |
|---|---|
| First Life documentation/order guard | PASS |
| HUGO V2 documentation guard | PASS |
| T032 baseline evidence guard | PASS |
| Relevant H003/FL002/architecture JVM tests | PASS |
| Android FL002 test APK compilation | PASS |
| Full `testDebugUnitTest` (514 tests) | PASS |
| `lintDebug` | PASS |
| `assembleDebug` | PASS |
| `git diff --check` | PASS |
| Samsung force-stop/relaunch continuity | PASS |

## Samsung acceptance evidence

- Device: Samsung `SM-S928B`, Android 16 / API 36, authorized ADB state `device` (serial redacted).
- APK installation: debug and Android-test APKs installed successfully with `-r -t`; no app-data
  clear or identity reset was used.
- First valid bootstrap/repository recreation test: PASS; redacted evidence `HUGO-1EB8ED5B`.
- Pre-restart authenticated-file SHA-256:
  `2da7ff9847e76af70603921a5b4dbd57c54b2fc4b4d725f4ea376c47ca471ad4`.
- `am force-stop io.redi.robot`: process ID absent afterward; cold Activity relaunch succeeded.
- Post-relaunch strict H003 restore/repository recreation test: PASS; redacted evidence remained
  `HUGO-1EB8ED5B` while the test asserted a different run reference.
- Post-relaunch authenticated-file SHA-256: exact match with the pre-restart value.
- `ActivityScenario.recreate()` test compared the raw in-memory H003 value before/after recreation:
  PASS; only `HUGO-1EB8ED5B` was printed.
- Full FL002 Android class: four tests PASS, including real Keystore ciphertext-tamper rejection and
  unsupported-envelope fail-closed behavior in isolated test stores.
- Final private store contained only the identity lock/encrypted files; temporary test stores were
  removed. The app process and `ConnectedDeviceService` were absent after the run.

No scan, connect, handshake, provider, body, active-body, permission grant, voice session, or LOOI
operation was invoked. The existing UI can bind the service during Activity recreation, but the
service was never started as a user connection session and created no connection.

## Risks and residual gates

- Android Keystore loss or authenticated-file corruption intentionally requires explicit recovery;
  there is no automatic identity continuity after such loss.
- A successful explicit reset permanently changes HUGO identity on the following bootstrap and must
  remain a deliberate recovery operation.
- The redacted display value is evidence correlation only; exact continuity is established by raw
  in-test H003 equality plus the unchanged authenticated-file digest, not by treating the display
  hash as a new identity.
