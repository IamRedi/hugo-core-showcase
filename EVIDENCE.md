# Verification and evidence boundaries

Evaluation snapshot prepared on 27 October 2026 from source revision
`cf03ce5366b8ce41637837a02d41913db88bf265` (H061). The original private
repository, branches, installation and untracked experiments were not modified.

## Local build and JVM verification

Windows, JDK 21 running Java/Kotlin target 17, Android SDK 35, Gradle 8.10.2:

```text
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
BUILD SUCCESSFUL
132 JVM suites: 1059 discovered, 1050 passed, 0 failed, 0 errors, 9 skipped
```

The original H061 baseline is 1053 cases across 131 suites. This snapshot adds
six cases for the presentation shell: explicit one-shot confirmation, speed and
staleness rejection, generation invalidation, proposal expiry, local stop/AI
reset rejection, and semantic-memory self-validation/correction rules.
Nine existing strict filesystem tests are conditional on Linux directory-fsync
support and were skipped on Windows. They were not deleted or weakened; the
Linux CI reports its own actual result. The original source revision's Android
CI was successful; that is source evidence, not a new showcase CI result.

Both Android modules were linted and built. Both instrumented test APKs were
compiled; the research APK was never installed for this demonstration.
The new `showcase` module additionally declares the AndroidJUnitRunner runtime
dependency required to execute its instrumented tests.

## Source preservation and architecture

- SHA-256 checks retain **221 original production Kotlin files unchanged**
  after LF normalization. The manifest is [source-snapshot.json](docs/source-snapshot.json).
- The original dependency guards remain enabled. The new presentation-domain
  package is added to the pure-Kotlin boundary guard.
- HUGO V2, First Life and T032 historical baseline documentation guards passed
  against the untouched source checkout and its real ancestry. Historical
  ancestry checks cannot be represented as passing against a fresh Git root;
  their scripts are retained for inspection. The standalone First Life guard
  and snapshot-specific checker apply directly to this repository.
- The Gradle wrapper JAR matches the official 8.10.2 SHA-256:
  `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.
  The distribution checksum is pinned as well.

## APK isolation

`io.redi.hugo.showcase` is a separate application ID. Its merged manifest
requests no Android platform permission. Compiled-class checks verify that the
original live MainActivity, connected-device service, BLE body, live transport,
voice/vision platform adapters and LOOI identity repository are absent. Actual
safety reducers, semantic factories and native face presentation are included.

The original `app` research module remains inspectable with its original
permissions. Install **only** `showcase/build/outputs/apk/debug/showcase-debug.apk`
for this evaluation. Source retention is not permission to contact a real body.

## Instrumentation and media

**Executed successfully: 1 native UI walkthrough + 3 H059 Keystore/storage
instrumented tests, 0 failures.** Results are recorded in
[verification-local.json](docs/verification-local.json) and the final capture
manifest. A disposable Android 35 x86_64 emulator contains only synthetic data.
The native UI walkthrough checks the exact app ID, emulator marker, absence of
live permissions/services, approval/rejection paths and memory-envelope rules.
The separate H059 instrumented suite exercises Android Keystore/AES-GCM,
encrypted file storage, corruption/key-loss handling and bounded listing with
synthetic records. Emulator success does not establish hardware-backed TEE,
physical stopping behavior, radio compatibility or real robot acceptance.

The demo confirmation shell is **not** the production H030 authorization
orchestrator. Its memory lab constructs ephemeral envelopes; it does not commit
canonical durable corrections. No live AI provider or physical device is used.
Videos are captioned montages of actual native screenshots, not continuous
screen recordings. [Media method and manifest](docs/MEDIA.md).

## Privacy, provenance and CI

Original Git history, untracked local files and four private-device screenshots
are omitted. Private machine paths were removed. No provider credentials,
owner-memory store, signing key, APK, model weights or real device address is
included. The visibly artificial MAC sentinel occurs only in existing negative
test fixtures and in the checker that recognizes those fixtures.
The Gitleaks configuration extends default rules without allowlisting secrets.

[THIRD_PARTY.md](THIRD_PARTY.md) separates first-party implementations,
research citations and dependency licensing. The evaluation license does not
override third-party rights. The resolved [dependency inventory](docs/DEPENDENCIES.json)
contains artifact hashes, scopes and upstream POM license metadata.

The GitHub workflow runs the original and isolated-module JVM/lint/build gates,
snapshot/media/source checks, wrapper validation, First Life documentation guard
and a nonempty full-history Gitleaks scan. Instrumented suites are compiled in
CI; local execution is reported separately. No deployment workflow is added.
This snapshot was prepared privately for owner review. GitHub CI status must be
read from the actual run rather than inferred from local success.
