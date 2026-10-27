# Spark Task Backlog

This is the resumable execution queue. It contains **64 ordered tasks**. Execute the lowest-numbered eligible task unless a maintainer explicitly requests another.

## Status values

`TODO` · `IN_PROGRESS` · `BLOCKED` · `DONE` · `NEEDS_HARDWARE_VERIFICATION` · `NEEDS_ARCHITECTURE_DECISION`

`DONE` means all stated evidence exists. A task with passing mock tests but unperformed physical criteria is `NEEDS_HARDWARE_VERIFICATION`, never `DONE`.

## Rules for every Spark execution

1. Execute only the requested task.
2. Read `ARCHITECTURE.md` and relevant decisions before implementation.
3. Do not redesign architecture inside a task.
4. Do not opportunistically implement future tasks.
5. Do not add dependencies without justification.
6. Do not embed credentials.
7. Do not bypass the safety layer.
8. Add or update tests for every behavioral change.
9. Run the relevant quality checks before completion.
10. Document deviations.
11. If architecture must change, stop and report a `NEEDS_ARCHITECTURE_DECISION` blocker.
12. If real hardware behavior cannot be verified locally, explicitly mark `NEEDS_HARDWARE_VERIFICATION` rather than pretending it passed.

Completion notes must distinguish unit, integration, Android-device, and real-LOOI results and include commands/log paths. Do not edit the scope of a task to make it pass.

## M0 — Research and Foundation

### T001 — Repository bootstrap

- **Status:** DONE
- **Objective:** Establish a clean, secret-safe repository foundation.
- **Exact scope:** Initialize Git; add ignore/environment examples, README, security policy, documentation map, and directories needed by the selected stack.
- **Expected files/modules:** root metadata, `.github/`, `docs/`, `app/`.
- **Prerequisites:** None.
- **Constraints:** No credentials, upstream code, or product features.
- **Acceptance criteria:** Git recognizes the repository; required M0 files are tracked; secret/build outputs are ignored.
- **Tests required:** `git status --short`; inspect ignore behavior with dummy filenames without committing them.
- **Non-goals:** Android behavior, BLE, AI, memory.
- **Completion evidence:** Commit/status listing and ignore-check transcript.

### T002 — Android project build baseline

- **Status:** DONE
- **Objective:** Prove the native Kotlin/Compose skeleton builds reproducibly.
- **Exact scope:** Validate Gradle wrapper, SDK/JDK requirements, debug assembly, JVM tests, and lint; fix bootstrap-only failures.
- **Expected files/modules:** Gradle wrapper/build files, `app/`, README if prerequisites change.
- **Prerequisites:** T001.
- **Constraints:** Keep one app module; no feature dependencies.
- **Acceptance criteria:** Clean checkout can run the documented quality command with JDK 17+ and SDK 35.
- **Tests required:** `testDebugUnitTest`, `lintDebug`, `assembleDebug`.
- **Non-goals:** Emulator launch, BLE permissions, UI polish.
- **Completion evidence:** 2026-08-24 baseline: JDK 21.0.4, SDK 35, Gradle 8.10.2; `testDebugUnitTest lintDebug assembleDebug` succeeded. See `M0_VERIFICATION.md`.

### T003 — Domain and safety contract seed

- **Status:** DONE
- **Objective:** Establish typed body-independent actions and deterministic safety entry point.
- **Exact scope:** Define `RobotAction`, `ActionRequest`, `RobotBody`, safety context/limits/decisions, and initial pure policy tests.
- **Expected files/modules:** `app/.../core`, `app/.../safety`, JVM tests.
- **Prerequisites:** T001.
- **Constraints:** No Android/BLE/AI types in domain contracts; no raw packet API.
- **Acceptance criteria:** Stop is representable; stale, invalid, over-limit, disconnected, latched, and unsafe-sensor decisions are explicit.
- **Tests required:** Pure JVM boundary tests.
- **Non-goals:** Controller orchestration, watchdog, actual transport.
- **Completion evidence:** Passing test report and contract file references.

### T004 — Research and architecture baseline

- **Status:** DONE
- **Objective:** Record stack, protocol, safety, AI, memory, attribution, roadmap, and testing decisions.
- **Exact scope:** Produce all required M0 documents with source snapshots, contradictions, assumptions, and verification levels.
- **Expected files/modules:** `docs/*.md`, README, SECURITY.
- **Prerequisites:** T001.
- **Constraints:** No unsupported hardware claims; distinguish license from technical usefulness.
- **Acceptance criteria:** Fresh agent can explain layers, milestones, protocol profiles, reuse decisions, and next tasks without conversation history.
- **Tests required:** Documentation link/file audit.
- **Non-goals:** Implement researched behavior.
- **Completion evidence:** Required-file checklist and source/commit ledger.

### T005 — Protocol fixture format

- **Status:** DONE
- **Objective:** Define reproducible, redacted BLE inventory and notification fixtures.
- **Exact scope:** Add JSON schemas/data classes for run metadata, GATT inventory, timestamped reads/writes/notifications, firmware unknowns, and privacy redaction.
- **Expected files/modules:** `protocol/fixtures` or app test fixtures, schema docs, parser tests.
- **Prerequisites:** T002, T004.
- **Constraints:** No stable MAC/serial; immutable source bytes; profile meaning separate from capture.
- **Acceptance criteria:** Valid fixture loads; malformed/identifying fixture fails with actionable errors.
- **Tests required:** Schema round-trip, malformed/truncated/redaction tests.
- **Non-goals:** BLE capture or packet interpretation.
- **Completion evidence:** Implemented `io.redi.robot.protocol.fixtures` schema/parser + fixtures in `app/src/test/resources/fixtures`, schema round-trip tests in `app/src/test/java/io/redi/robot/protocol/fixtures/ProtocolFixtureParserTest.kt`, and passing `./gradlew testDebugUnitTest`/`./gradlew lintDebug` on 2026-08-24.

### T006 — Architecture dependency guard

- **Status:** DONE
- **Objective:** Prevent forbidden dependencies on BLE/provider/database/UI types from domain packages.
- **Exact scope:** Add a lightweight architecture test or Gradle verification rule for package/module imports.
- **Expected files/modules:** test utilities, Gradle config, architecture docs if enforcement differs.
- **Prerequisites:** T002, T003.
- **Constraints:** Avoid a heavy dependency unless justified; rule must run in CI.
- **Acceptance criteria:** Current code passes and a deliberate forbidden import makes the test fail.
- **Tests required:** Positive and negative fixture/check.
- **Non-goals:** Multi-module refactor.
- **Completion evidence:** Added `io.redi.robot.architecture.ArchitectureDependencyGuardTest`, enforced in `./gradlew testDebugUnitTest`/`./gradlew lintDebug`, and captured positive/negative violation check.

## M1 — Reliable Robot Transport

### T007 — BLE runtime permissions

- **Status:** DONE
- **Objective:** Model and request version-correct Android BLE permissions.
- **Exact scope:** Manifest declarations, API-level permission policy, Compose request UI, denied/permanently-denied state.
- **Expected files/modules:** manifest, `platform/permissions`, setup UI/tests.
- **Prerequisites:** T002.
- **Constraints:** Minimize permissions; no background location unless evidence requires it; no scan yet.
- **Acceptance criteria:** Android 12+ uses scan/connect permissions; legacy behavior is explicit; denial degrades safely.
- **Tests required:** JVM policy tests and instrumented permission-state tests.
- **Non-goals:** Device scan, companion association.
- **Completion evidence:** Added `platform.permissions` policy model (`app/src/main/java/io/redi/robot/platform/permissions/BlePermissionPolicy.kt`), runtime Compose setup UI in `MainActivity.kt`, manifest BLE/location permissions, JVM policy tests in `app/src/test/java/io/redi/robot/platform/permissions/BlePermissionPolicyTest.kt`, and ignored Android-instrumented permission-state placeholder test in `app/src/androidTest/java/io/redi/robot/platform/permissions/BlePermissionPolicyInstrumentedTest.kt` pending device-based execution.
- **Validation:** `./gradlew testDebugUnitTest` and `./gradlew lintDebug` on 2026-08-24.

### T008 — BLE scan abstraction

- **Status:** DONE
- **Objective:** Expose bounded, cancellable scan observations behind an adapter.
- **Exact scope:** Scanner interface, Android implementation, scan timeout, duplicate policy, Bluetooth-off/error mapping.
- **Expected files/modules:** `body/looi/ble/scan`, fake scanner, tests.
- **Prerequisites:** T005, T007.
- **Constraints:** No auto-connect; redact stable IDs from normal logs; lifecycle-owned coroutine.
- **Acceptance criteria:** Exactly one scan; cancel/timeout releases callback; failures are typed.
- **Tests required:** Fake-callback ordering/timeout tests and Android-device scan test.
- **Non-goals:** LOOI identification or GATT connect.
- **Completion evidence:** Added scan abstraction in `app/src/main/java/io/redi/robot/body/looi/ble/scan/BleScanModels.kt`, Android adapter in `app/src/main/java/io/redi/robot/platform/transport/ble/scan/AndroidBleScanner.kt`, unit tests in `app/src/test/java/io/redi/robot/body/looi/ble/scan/FakeBleScannerTest.kt` (ordering, duplicate suppression, timeout failure), and pending Android-device placeholder in `app/src/androidTest/java/io/redi/robot/body/looi/ble/scan/AndroidBleScannerInstrumentedTest.kt`.
- **Validation:** `./gradlew testDebugUnitTest` and `./gradlew lintDebug`; Android-device verification pending (no BLE-capable device/emulator attached).

### T009 — LOOI candidate identification

- **Status:** DONE
- **Objective:** Rank scan results without trusting name or address alone.
- **Exact scope:** Evidence-based candidate model using advertisement name/service/manufacturer evidence with ambiguous-state UI data.
- **Expected files/modules:** `body/looi/discovery`, fixtures/tests.
- **Prerequisites:** T008.
- **Constraints:** Never silently select ambiguous candidates; MAC not identity.
- **Acceptance criteria:** Known fixtures rank predictably; unknown devices remain untrusted.
- **Tests required:** Table-driven ranking, missing fields, collisions.
- **Non-goals:** Connect or profile identification.
- **Completion evidence:** Added candidate policy/model in
  `app/src/main/java/io/redi/robot/body/looi/discovery/LooiCandidateDiscovery.kt`,
  and table-driven tests in
  `app/src/test/java/io/redi/robot/body/looi/discovery/LooiCandidateDiscoveryTest.kt`
  covering positive, negative, ambiguous, missing-name, duplicate, and conflicting-evidence cases.
- **Validation:** `./gradlew testDebugUnitTest` and `./gradlew lintDebug`; policy-only task so no Android-device scan verification required.
### T010 — GATT connection state machine

- **Status:** DONE
- **Objective:** Make connection lifecycle explicit and resistant to stale callbacks.
- **Exact scope:** States/events/reducer, generation ID, single-attempt ownership, cancellation and typed failure reasons using a fake GATT adapter.
- **Expected files/modules:** `body/looi/connection`, state tests.
- **Prerequisites:** T006, T009.
- **Constraints:** Pure reducer; no handshake/movement; old generation cannot reach Ready.
- **Acceptance criteria:** Legal transition table is exhaustive; illegal/stale events are ignored or faulted deterministically.
- **Tests required:** Every transition, cancellation, out-of-order callback tests.
- **Non-goals:** Android `BluetoothGatt` implementation.
- **Completion evidence:** Added connection models/reducer in
  `app/src/main/java/io/redi/robot/body/looi/connection/ConnectionModels.kt`,
  transition reducer in `app/src/main/java/io/redi/robot/body/looi/connection/ConnectionStateMachine.kt`,
  transport/state boundary coordinator in `app/src/main/java/io/redi/robot/body/looi/connection/ConnectionController.kt`,
  and covered lifecycle, stale/out-of-order callback, cancellation idempotency, and GATT-status mapping in
  `app/src/test/java/io/redi/robot/body/looi/connection/ConnectionControllerTest.kt`.
- **Validation:** `./gradlew testDebugUnitTest` and `./gradlew lintDebug`.

### T011 — Android GATT adapter

- **Status:** DONE
- **Objective:** Connect and discover services through Android APIs behind the state machine.
- **Exact scope:** Callback adapter, generation tagging, connect/discover/close, error mapping, resource cleanup.
- **Expected files/modules:** `body/looi/ble/gatt`, manifest if needed, integration tests.
- **Prerequisites:** T007, T010.
- **Constraints:** Serialize operations; no handshake or writes beyond discovery; close exactly once.
- **Acceptance criteria:** Fake callback sequences drive correct states; device inventory can be collected without actuation.
- **Tests required:** Callback race tests and Android-device read-only test.
- **Non-goals:** Reconnect, notifications, MTU tuning.
- **Completion evidence:** Added `app/src/main/java/io/redi/robot/platform/transport/ble/gatt/AndroidGattTransportAdapter.kt` (generation-tagged `Gatt` callback adapter with connect/discover/disconnect/cleanup handling) and `app/src/main/java/io/redi/robot/platform/transport/ble/gatt/GattTransportCallbackTranslator.kt` mapping; added unit tests in
  `app/src/test/java/io/redi/robot/platform/transport/ble/gatt/GattTransportCallbackTranslatorTest.kt`;
  added Android test placeholder `app/src/androidTest/java/io/redi/robot/platform/transport/ble/gatt/AndroidGattTransportAdapterInstrumentedTest.kt` (pending real-device path verification);
  pending real-device/real-LOOI callback-path verification. Hardware-verification attempt on
  2026-08-24 was blocked before installation: Android SDK Platform Tools `adb devices -l`
  reported no attached authorized Android device. No BLE scan, candidate identification, GATT
  connection, service discovery, disconnect/reconnect, or protocol fixture was performed; no
  hardware behavior is claimed. The current debug app also exposes only the T007 permission
  screen, not a composed T008/T009/T010/T011 diagnostic flow, so a real-device read-only runner
  is required before the requested sequence can be exercised. A diagnostic runner was subsequently
  installed on 2026-08-24 on a Samsung SM-S928B running Android 16 (API 36). `BLUETOOTH_SCAN` and
  `BLUETOOTH_CONNECT` were both granted and Bluetooth was enabled. Two real 10-second scans,
  including one initiated immediately before the base was powered on, completed with `Timed out`
  and returned no BLE observations. Therefore no LOOI candidate, GATT connection, callback status,
  service discovery, UUID inventory, disconnect/reconnect result, or T005 fixture exists. No
  characteristic operation was issued and no protocol assumption was changed.
  A subsequent scan did observe a device whose display name matched the documented LOOI pattern
  (RSSI -35); its T009 classification remained `UNKNOWN` because the available scan data supplied
  only name evidence (score 2, confidence 0.2). One explicit read-only connection attempt then
  ended in `FAILED` with `GattFailure(operation=CONNECT, kind=UNKNOWN, status=19, detail=Connection
  callback failed)`. The base appearing to wake at the same time is recorded only as an operator
  observation, not evidence of a successful link. No `CONNECTED` or `TRANSPORT_READY` state,
  service-discovery start/callback, service/characteristic UUID inventory, disconnect/reconnect
  result, or T005 fixture was observed. The run stopped at this exact failure as required.
  After an app restart and a user-confirmed clean base reset, a further 10-second scan again timed
  out without observing the LOOI; no additional connection attempt was made in that run.
  The official pairing preparation was then repeated (front button held for 3-4 seconds; operator
  observed vibration and yellow breathing light), followed by another real 10-second scan. It also
  timed out with no LOOI observation and no connection attempt.
  A physical reset was subsequently completed (operator reported the documented green indicator),
  followed by the same confirmed pairing preparation and another real 10-second read-only scan.
  That scan again timed out with no LOOI observation; no further connection attempt was made.
  The operator then confirmed that the official LOOI application could pair/connect successfully on
  the same Samsung phone. After that application was closed, REDI was brought to the foreground and
  performed another real 10-second read-only scan; it timed out without a LOOI observation. No
  REDI connection was requested in that run. The diagnostic screen's retained `FAILED` label refers
  to the earlier documented status-19 connection failure, not a new callback.
  In a later scan the LOOI-named observation appeared as ephemeral UI label `device-4` with RSSI
  -24 and the same name-only T009 classification (`UNKNOWN`, confidence 0.2). A user-initiated
  read-only connection then produced `CONNECTING -> CONNECTED -> TRANSPORT_READY`, connection
  status 0, discovery status 0, and six services before the peer ended the no-handshake session
  with status 19 after about five seconds. AOSP maps `0x13` to peer termination.
  Physical closure verification was completed on 2026-08-24 using the same Samsung SM-S928B
  running Android 16 (API 36) and the real LOOI base. Two subsequent operator-initiated cycles each
  produced `CONNECTING -> CONNECTED -> TRANSPORT_READY`, connection status 0, discovery status 0,
  and six services. Generation 1 was closed by an explicit local disconnect exactly once and
  reached `DISCONNECTED`; generation 2 proved a clean reconnect after that close, was also closed
  exactly once by explicit local disconnect, and reached `DISCONNECTED`. The observed status-code
  list for the clean cycle was `0, 0`; no stale session remained. The redacted inventory is stored
  in `app/src/test/resources/fixtures/t011-hardware-2026-08-24.json`. No stable/private device
  identifier, handshake, characteristic operation, notification subscription, movement, light,
  head, heartbeat, or telemetry command was used. `testDebugUnitTest` and `lintDebug` passed after
  this evidence-only update.

### T012 — GATT capability inventory

- **Status:** DONE
- **Objective:** Convert discovered services into a stable, testable inventory.
- **Exact scope:** Normalize UUIDs, properties/descriptors, service membership, and fixture export.
- **Expected files/modules:** `body/looi/protocol/inventory`, fixture writer/tests.
- **Prerequisites:** T005, T011.
- **Constraints:** Read-only; retain unknown characteristics; deterministic ordering.
- **Acceptance criteria:** Inventory round-trips to redacted fixture and differentiates profile A/B candidates.
- **Tests required:** Golden inventories, duplicate/unknown UUID tests.
- **Non-goals:** Select or execute a profile.
- **Completion evidence:** Added the pure project-owned capability model, canonical UUID normalizer,
  deterministic ordering/deduplication, unknown-capability retention, and structural fingerprint in
  `app/src/main/java/io/redi/robot/body/looi/protocol/inventory/GattCapabilityInventory.kt`.
  Added the outward fixture mapper in
  `app/src/main/java/io/redi/robot/protocol/fixtures/GattCapabilityFixtureMapper.kt`, preserving the
  dependency direction while allowing normalized T005 fixture round-trips. Added deterministic tests
  covering duplicate observations, UUID/property/descriptor normalization, unknown UUID retention,
  invalid UUID rejection, distinct candidate A/B structures, the T005 fixture round-trip, and the
  redacted six-service real-hardware golden inventory from T011. No protocol profile was selected and
  no Android, connection, handshake, notification, write, telemetry, or actuation behavior was added.
  `testDebugUnitTest` and `lintDebug` passed.

### T013 — Protocol profile selector

- **Status:** DONE
- **Objective:** Allowlist exact supported GATT capability profiles.
- **Exact scope:** Versioned `LooiProtocolProfile`, profile A/B detection, rejection reason, research-only unknown mode.
- **Expected files/modules:** `body/looi/protocol/profile`, tests/docs.
- **Prerequisites:** T012.
- **Constraints:** Unknown/partial profile cannot move; no address/name branching.
- **Acceptance criteria:** Fixtures select only compatible profiles; ambiguous inventory refuses control.
- **Tests required:** Profile A/B/partial/superset/ambiguous tables.
- **Non-goals:** Handshake or packet definitions.
- **Completion evidence:** Added profile allowlist selector in
  `app/src/main/java/io/redi/robot/body/looi/protocol/profile/LooiProtocolProfileSelector.kt`.
  Added matrix tests (A/B/partial/superset/ambiguous plus hardware fixture case) in
  `app/src/test/java/io/redi/robot/body/looi/protocol/profile/LooiProtocolProfileSelectorTest.kt`.
  `testDebugUnitTest` and `lintDebug` passed on 2026-08-24.
- **Completion evidence:** Selector test matrix.

### T014 — Serialized GATT operation queue

- **Status:** DONE
- **Objective:** Enforce one in-flight read/write/descriptor operation with stop priority.
- **Exact scope:** Operation model, timeouts, callback correlation, bounded queue, cancellation, high-priority stop lane.
- **Expected files/modules:** `body/looi/ble/queue`, virtual-time tests.
- **Prerequisites:** T010, T011.
- **Constraints:** No unbounded buffers; timeout cannot complete a later operation.
- **Acceptance criteria:** Concurrent callers serialize; stop bypasses/drops ordinary queued writes according to policy.
- **Tests required:** Race, timeout, cancellation, queue-full, stale callback tests.
- **Non-goals:** Protocol payloads.
- **Completion evidence:** Added `app/src/main/java/io/redi/robot/body/looi/ble/queue/GattOperationQueue.kt` and
  `app/src/test/java/io/redi/robot/body/looi/ble/queue/SerializedGattOperationQueueTest.kt`.
  Deterministic tests now cover serialization/race order, stop priority with queued preemption, cancellation idempotency,
  queue-full back-pressure, timeout timeout behavior, and stale callback guards.

### T015 — Notification subscription router

- **Status:** DONE
- **Objective:** Subscribe safely and route immutable raw notifications by profile/channel.
- **Exact scope:** CCCD operation, subscription state, copy bytes, timestamp/generation tagging, unsubscribe cleanup.
- **Expected files/modules:** BLE adapter/router/tests.
- **Prerequisites:** T013, T014.
- **Constraints:** Parser not in callback thread; malformed data retained as diagnostic event only.
- **Acceptance criteria:** Subscription completes only after descriptor result; stale generation is discarded.
- **Tests required:** Ordering, duplicate, failure, disconnect tests.
- **Non-goals:** Interpret telemetry.
- **Completion evidence:** Added `app/src/main/java/io/redi/robot/body/looi/ble/notification/GattNotificationRouter.kt`
  with CCCD-enablement flow, immutable notification payload model, generation/key-aware routing, duplicate-subscribe guard,
  malformed-payload eventing, unsubscribe cleanup, and stale-generation handling via generation disconnect.
  Added `app/src/test/java/io/redi/robot/body/looi/ble/notification/GattNotificationRouterTest.kt` covering
  ordered subscription completion, duplicate-subscribe idempotency, notification copy+ordering, callback failure handling,
  queue-full reject, and disconnect cleanup behavior.
  `./gradlew testDebugUnitTest` and `./gradlew lintDebug` passed for this scope.

### T016 — Handshake experiment runner

- **Status:** DONE
- **Objective:** Execute named handshake candidates as auditable, non-motion experiments.
- **Exact scope:** Candidate A/B steps, per-step timeout/result capture, notification preconditions, feature flag, no default promotion.
- **Expected files/modules:** `body/looi/protocol/handshake`, research UI/fixtures/tests.
- **Prerequisites:** T013–T015.
- **Constraints:** No drive/FE00/FF02; exact allowlisted bytes; explicit operator selection.
- **Acceptance criteria:** Fake traces validate both candidates and failures never enter Ready.
- **Tests required:** Golden sequence, timeout, missing characteristic, cancellation tests; real LOOI checklist.
- **Non-goals:** Decide universally correct handshake.
- **Completion evidence:** Added handshake models in
  `app/src/main/java/io/redi/robot/body/looi/protocol/handshake/HandshakeModels.kt`,
  deterministic handshake runner in
  `app/src/main/java/io/redi/robot/body/looi/protocol/handshake/HandshakeRunner.kt`,
  and tests in
  `app/src/test/java/io/redi/robot/body/looi/protocol/handshake/HandshakeRunnerTest.kt`
  covering golden candidate A/B sequences, timeout, missing characteristic, cancellation, and
  feature-disabled behavior.
  `testDebugUnitTest` and `lintDebug` passed.
  Candidate A excludes `FED0` and all drive/FE00/FF02 operations in accordance with T016's
  non-motion constraint. Physical Candidate A evidence was captured on 2026-08-24 using a
  Samsung SM-S928B on Android 16/API 36 and a real LOOI base: FED5/FED9 notification
  subscriptions, FEDA writes `01` then `03`, and a FED8 read completed successfully; the
  diagnostic UI reported all four Candidate A steps as successful. The redacted, immutable
  fixture is `app/src/test/resources/fixtures/t016-hardware-candidate-a-2026-08-24.json`.
  The FED8 callback reported two bytes, but no raw value was retained, so it is intentionally
  omitted from the fixture. Candidate B remains fake-trace-only; no universal protocol profile
  is claimed. `protocolReady` remains false, and no drive/FE00/FF02, movement, light, head,
  heartbeat, or telemetry operation was issued. `testDebugUnitTest`, `lintDebug`, and
  `assembleDebug` passed after the diagnostic integration update.

### T017 — Raw telemetry envelope and strict parsers

- **Status:** DONE (automated evidence only; physical sensor semantics remain unverified)
- **Objective:** Parse profile-specific battery/dock/touch/sensor candidates without overclaiming.
- **Exact scope:** Length/type validation, raw fallback, confidence/provenance, profile A FED8/FED9 and profile B FED8 ACK separation.
- **Expected files/modules:** `body/looi/protocol/telemetry`, fixture tests.
- **Prerequisites:** T005, T013, T015.
- **Constraints:** Unknown bytes never become safe sensor state; no TOF decoder without evidence.
- **Acceptance criteria:** Known fixtures parse; malformed/truncated/arbitrary bytes cannot crash or authorize movement.
- **Tests required:** Golden, boundary, fuzz/property tests.
- **Non-goals:** UI or physical sensor verification.
- **Completion evidence:** Added project-owned raw envelope, profile-specific strict parser, and
  non-safety observation models in
  `app/src/main/java/io/redi/robot/body/looi/protocol/telemetry/LooiTelemetryParser.kt`.
  The redacted source-reported corpus is in
  `app/src/test/resources/fixtures/t017-telemetry-candidates.json`; tests in
  `app/src/test/java/io/redi/robot/body/looi/protocol/telemetry/LooiTelemetryParserTest.kt`
  cover golden candidates, malformed/boundary packets, profile B FED8 ACK separation, and a
  deterministic arbitrary-byte corpus. All parsed observations have `safetyUsable = false`.
  `testDebugUnitTest` and `lintDebug` passed. No real-device sensor semantics are claimed.

### T018 — Transport heartbeat/watchdog scheduler

- **Status:** DONE (automated timing evidence only; physical refresh interval remains pending)
- **Objective:** Separate protocol refresh from local safety watchdog with injected monotonic time.
- **Exact scope:** Scheduler interfaces, idle-neutral candidate configuration, movement refresh hooks, missed-deadline event.
- **Expected files/modules:** `body/looi/transport/timing`, tests.
- **Prerequisites:** T014, T016.
- **Constraints:** Values profile-configured and disabled until verified; no wall clock; no actual drive encoding.
- **Acceptance criteria:** Virtual time proves deadlines, cancellation, and no scheduler leak across generations.
- **Tests required:** Boundary/drift/reconnect/cancellation tests.
- **Non-goals:** Establish real refresh interval.
- **Completion evidence:** Added the pure monotonic timing coordinator and platform scheduler interfaces in
  `app/src/main/java/io/redi/robot/body/looi/transport/timing/TransportTimingCoordinator.kt`.
  It emits typed refresh-due and local-watchdog-deadline events only; it contains no BLE bytes,
  GATT calls, or drive encoding. Default refresh configuration is disabled. Virtual-time tests in
  `app/src/test/java/io/redi/robot/body/looi/transport/timing/TransportTimingCoordinatorTest.kt`
  cover deadline boundaries, drift-resistant cadence, disabled/enabled movement hooks,
  reconnect generation isolation, and idempotent cancellation. `testDebugUnitTest` and
  `lintDebug` passed. Physical interval verification remains pending.

### T019 — Reconnect coordinator

- **Status:** DONE (automated lifecycle evidence only)
- **Objective:** Recover connections without unsafe replay or retry storms.
- **Exact scope:** Bounded exponential backoff+jitter, user cancel, Bluetooth/permission gating, generation reset, manual-vs-auto policy.
- **Expected files/modules:** connection coordinator/tests.
- **Prerequisites:** T010, T018.
- **Constraints:** Never replay motion; one attempt; stop/revoke before backoff.
- **Acceptance criteria:** Deterministic backoff tests and Ready requires fresh handshake/profile.
- **Tests required:** Virtual-time RF loss, toggle, cancellation, max-backoff tests.
- **Non-goals:** Persistent companion service.
- **Completion evidence:** Added pure reconnect coordination in
  `app/src/main/java/io/redi/robot/body/looi/connection/reconnect/ReconnectCoordinator.kt`.
  It uses monotonic deadlines, one active retry, bounded exponential backoff with injected jitter,
  Bluetooth/permission gates, cancellation, and generation isolation. Its safety port is called
  before every retry/backoff and the coordinator holds no action or lease to replay. Tests in
  `app/src/test/java/io/redi/robot/body/looi/connection/reconnect/ReconnectCoordinatorTest.kt`
  cover loss/backoff, jitter bounds, gates, cancellation/stale events, and ready cleanup.
  `testDebugUnitTest` and `lintDebug` passed.

### T020 — Connected-device service lifecycle

- **Status:** DONE
- **Objective:** Own BLE connection in a compliant Android component independent of screen recreation.
- **Exact scope:** Foreground/companion strategy per Android version, notification, bind/unbind API, shutdown stop, process-recreation state.
- **Expected files/modules:** service, manifest, platform lifecycle tests/docs.
- **Prerequisites:** T007, T019.
- **Constraints:** Follow current Android foreground restrictions; camera/mic are separate; visible user control.
- **Acceptance criteria:** Rotation/background/navigation do not duplicate connection; service stop revokes motion and closes GATT.
- **Tests required:** Instrumented lifecycle/process tests and Android-device checks.
- **Hardware completion evidence:** Verified on a Samsung SM-S928B running Android 16. A real BLE
  session was ended through the visible `End connection session` control: Android recorded
  `clientDisconnect` followed by `BluetoothGatt close`; the service reported
  `startRequested=false` and was no longer foreground, with no active GATT session. Normal
  `Home -> launcher icon` navigation and rotation/recovery each left exactly one screen-service
  binding. No handshake, writes, movement, head, light, heartbeat, or telemetry commands were
  sent for this lifecycle verification.
- **Non-goals:** Boot auto-start or continuous hidden connection.
- **Completion evidence:** Added a user-started `connectedDevice` foreground-service policy in
  `docs/CONNECTED_DEVICE_SERVICE.md`, manifest declaration/permissions, and the service/owner in
  `app/src/main/java/io/redi/robot/platform/service/`. The service is `START_NOT_STICKY`, has an
  explicit disconnect notification action, and calls idempotent transport shutdown on task removal
  and destruction without persisting connection target, readiness, lease, or action. Unit coverage
  is in `app/src/test/java/io/redi/robot/platform/service/ConnectedDeviceConnectionOwnerTest.kt`;
  a physical-device lifecycle checklist placeholder is in `app/src/androidTest/`. Automated checks
  passed. The diagnostic UI binds to the service-owned connection client, so activity recreation
  does not create another GATT owner. Real Android rotation/background/task-removal and real-LOOI
  GATT-close evidence remains required.

## M2 — Manual Robot Control

### T021 — Robot state store

- **Status:** DONE
- **Objective:** Publish one immutable aggregate of connection, profile, telemetry freshness, authority, and safety state.
- **Exact scope:** Reducer/store, snapshots, timestamps, unavailable/unknown distinctions.
- **Expected files/modules:** `core/state`, tests.
- **Prerequisites:** T010, T017.
- **Constraints:** Derived UI state only; no side effects in reducer.
- **Acceptance criteria:** Replayed event traces produce deterministic snapshots and reconnect clears stale state.
- **Tests required:** Reducer transition/table tests.
- **Non-goals:** Persistence or UI.
- **Completion evidence:** `RobotStateStoreTest` provides golden replay traces for deterministic
  snapshots, explicit unknown versus unavailable state, telemetry freshness, and reconnect reset.
  `testDebugUnitTest` and `lintDebug` passed with architecture guards intact. No hardware claim is
  required because this task is a pure reducer/store with no transport or UI side effects.

### T022 — Motion ownership and leases

- **Status:** DONE
- **Objective:** Ensure exactly one time-bounded motion owner with manual priority.
- **Exact scope:** Lease acquire/renew/release/preempt, source priorities, monotonic expiry events.
- **Expected files/modules:** `core/authority`, tests.
- **Prerequisites:** T003, T021.
- **Constraints:** AI/autonomy cannot preempt manual/system stop; no wall clock.
- **Acceptance criteria:** Conflicts and expiry are deterministic; reconnect invalidates all leases.
- **Tests required:** Virtual-time priority/replay/race tests.
- **Non-goals:** BLE commands.
- **Completion evidence:** `MotionAuthorityStoreTest` covers source-priority preemption,
  owner-only renew/release, virtual-time expiry, deterministic replay/race ordering, and reconnect
  invalidation. `testDebugUnitTest` and `lintDebug` passed with architecture guards intact. The
  implementation is pure core policy only; no BLE command or physical-motion behavior was added.

### T023 — Emergency-stop controller

- **Status:** DONE
- **Objective:** Latch e-stop and synchronously revoke all action producers.
- **Exact scope:** E-stop state, local reset conditions, queue cancellation hook, repeated stop request, audit reason.
- **Expected files/modules:** `safety/estop`, core integration/tests.
- **Prerequisites:** T014, T022.
- **Constraints:** Reset requires deliberate local action while stationary; no remote/AI reset.
- **Acceptance criteria:** E-stop preempts queue and survives UI recreation; reset cannot occur in unsafe state.
- **Tests required:** Race, repeated activation, restart/reset policy tests.
- **Non-goals:** Physical button integration.
- **Completion evidence:** `EmergencyStopControllerTest` verifies authority revocation occurs before queue cancellation, two stop-delivery requests are issued per activation, repeated activation remains latched with an auditable reason/count, remote and AI reset requests are rejected, local reset requires a stationary report, and a restored state remains latched across UI recreation. `testDebugUnitTest` and `lintDebug` pass. No BLE protocol write or physical-device stop behavior was exercised.

### T024 — Dead-man and stale-sequence enforcement

- **Status:** DONE
- **Objective:** Stop when input refresh ceases and reject replayed/out-of-order requests.
- **Exact scope:** Sequence window, command lease timeout, independent watchdog event, reconnect epoch.
- **Expected files/modules:** safety/core timing tests.
- **Prerequisites:** T018, T022, T023.
- **Constraints:** Watchdog is not producer-owned; monotonic time only.
- **Acceptance criteria:** Lost-up/cancel/process scenarios result in stop within configured software deadline.
- **Tests required:** Virtual-time stalls, replay, epoch rollover, queue-load tests.
- **Non-goals:** Determine firmware timeout.
- **Completion evidence:** `DeadManControllerTest` measures the virtual watchdog deadline at exactly 100 ms, verifies replay/out-of-order requests do not renew the lease, rejects old-epoch input and stale watchdog callbacks after rollover, and confirms a 32-item queue backlog is synchronously cancelled before repeated stop delivery. It also verifies a latched e-stop blocks refresh until deliberate local stationary reset. `testDebugUnitTest` and `lintDebug` pass. No firmware timeout or physical-device behavior was claimed.

### T025 — Drive encoder profile A

- **Status:** DONE
- **Objective:** Encode bounded normalized motion into candidate profile-A `FED0` bytes.
- **Exact scope:** Pure mixing/sign/rounding/neutral encoder behind experimental profile config.
- **Expected files/modules:** `body/looi/protocol/profileA/DriveEncoder`, golden tests.
- **Prerequisites:** T013, T024.
- **Constraints:** Conservative cap; finite inputs only; no `FF02`; encoder inaccessible before safety approval.
- **Acceptance criteria:** Golden vectors and all boundaries are explicit; neutral is `0000` candidate.
- **Tests required:** Table/property tests; raised-wheel checklist.
- **Non-goals:** Profile B drive or floor operation.
- **Completion evidence:** `DriveEncoderTest` covers candidate `FED0` golden vectors, neutral `0000`, conservative signed cap `16`, all normalized boundaries, explicit symmetric rounding, polarity configuration, finite-number rejection, disabled experimental configuration, and the safety-approval-only API. Raised-wheel T025 checklist added to `HARDWARE_VERIFICATION.md`. `testDebugUnitTest` and `lintDebug` pass. `FED0` signs/mixing and physical stop remain unverified, so status remains `NEEDS_HARDWARE_VERIFICATION`.

### T026 — Drive command executor

- **Status:** NEEDS_HARDWARE_VERIFICATION
- **Objective:** Deliver authorized drive refreshes and prioritized stops through the operation queue.
- **Exact scope:** Safety decision integration, lease refresh, encoder call, queue policy, observed result.
- **Expected files/modules:** robot core/body adapter integration/tests.
- **Prerequisites:** T014, T024, T025.
- **Constraints:** No caller can obtain raw encoder/queue handle; stop errors remain visible.
- **Acceptance criteria:** Rejected actions cause no motion write; stop preempts under load.
- **Tests required:** Fake transport integration and fault injection; hardware raised-wheel tests.
- **Non-goals:** UI.
- **Completion evidence:** `DriveCommandExecutorTest` provides a fake-transport trace showing safety-rejected drives produce no write or dead-man refresh; approved drives produce a candidate `FED0` write and observed result; stop preempts queued normal motion, cancels the active generation through e-stop, and dispatches neutral `0000` on the stop lane; injected transport failure remains visible and activates e-stop. `testDebugUnitTest` and `lintDebug` pass. Raised-wheel write/physical result remains pending, so status is `NEEDS_HARDWARE_VERIFICATION`.

### T027 — Manual drive press-and-hold controls

- **Status:** NEEDS_HARDWARE_VERIFICATION
- **Objective:** Provide accessible human controls with release/cancel dead-man semantics.
- **Exact scope:** Four-direction/joystick minimum UI, pointer cancellation, lease refresh, visible connected/safe state.
- **Expected files/modules:** `feature/manualcontrol`, UI tests.
- **Prerequisites:** T021–T026.
- **Constraints:** Disabled unless Ready/safe; e-stop always visible; no tap-to-run duration.
- **Acceptance criteria:** Release, lost focus, multi-touch cancel, navigation, and background all stop.
- **Tests required:** Compose interaction tests, Android-device and real-LOOI checklist.
- **Non-goals:** Polished face UI or autonomy.
- **Completion evidence:** `ManualDriveControllerTest` covers press/refresh/release plus multi-touch, focus-loss, navigation, background, and e-stop cancellation traces. `ManualDriveControlsInstrumentedTest` passed on `SM-S928B / Android 16`, verifying press/release semantic events, disabled-drive behavior, and an always-available e-stop action. `testDebugUnitTest`, `lintDebug`, and `connectedDebugAndroidTest` pass. The app panel remains disabled until a real Ready/safe executor integration and raised-wheel verification; no LOOI movement test or UI video was captured, so status is `NEEDS_HARDWARE_VERIFICATION`.

### T028 — Head command encoder/control

- **Status:** NEEDS_HARDWARE_VERIFICATION
- **Objective:** Add bounded head presets/slider through safety and profile adapter.
- **Exact scope:** Candidate endpoints/center as profile config, rate limit, manual UI, command-state display.
- **Expected files/modules:** protocol head encoder, safety rule, manual UI/tests.
- **Prerequisites:** T013, T021, T023.
- **Constraints:** No claimed position feedback; avoid endpoints until calibrated.
- **Acceptance criteria:** Invalid inputs rejected and profile-specific center not hard-coded globally.
- **Tests required:** Encoder/UI tests and real-LOOI calibration.
- **Non-goals:** Tracking or closed-loop head position.
- **Completion evidence:** `HeadEncoderTest` covers profile-configured candidate center `0x5A`, bounded candidate bytes, invalid input rejection, and monotonic rate limiting. `DefaultSafetyControllerTest` rejects non-finite and out-of-range head requests. `HeadControlsInstrumentedTest` passed on `SM-S928B / Android 16` for enabled semantic center command and disabled uncalibrated controls. `testDebugUnitTest` and `lintDebug` pass. No physical `FED1` write, center calibration, endpoint calibration, or position feedback was claimed; status remains `NEEDS_HARDWARE_VERIFICATION`.

### T029 — Light command encoder/control

- **Status:** NEEDS_HARDWARE_VERIFICATION
- **Objective:** Add safe light off/on or brightness according to verified profile capability.
- **Exact scope:** Profile-configured encoder, manual UI, response/error display.
- **Expected files/modules:** light encoder, manual UI/tests.
- **Prerequisites:** T013, T021.
- **Constraints:** Start at minimum; do not assume `01/03/FF` equivalence.
- **Acceptance criteria:** Off is always available when connected; unsupported brightness is not exposed.
- **Tests required:** Golden/UI tests and real-LOOI observation.
- **Non-goals:** `FE00` colored animations.
- **Completion evidence:** `LightEncoderTest` covers candidate `FED2=00` off, a profile-configured minimum on value, and rejection of unsupported brightness. Safety rejects non-finite/out-of-range light input. `testDebugUnitTest` and `lintDebug` pass. No physical light command or brightness equivalence claim was made; status remains `NEEDS_HARDWARE_VERIFICATION`.

### T030 — Telemetry UI

- **Status:** NEEDS_HARDWARE_VERIFICATION
- **Objective:** Show connection/profile, battery/power, dock, sensor freshness/confidence, and errors honestly.
- **Exact scope:** Read-only Compose screen, unknown/stale indicators, redacted diagnostic export.
- **Expected files/modules:** `feature/telemetry`, state mappers/tests.
- **Prerequisites:** T017, T021.
- **Constraints:** No inferred TOF/odometry/head feedback; raw bytes behind developer mode.
- **Acceptance criteria:** Profile A/B semantics stay distinct and stale data visibly expires.
- **Tests required:** Mapper and Compose tests; device screenshot.
- **Non-goals:** Charts or cloud upload.
- **Completion evidence:** Telemetry UI state/export tests cover unknown freshness and redaction. Compose UI marks stale telemetry explicitly. Physical telemetry observations and device screenshot remain pending; status is `NEEDS_HARDWARE_VERIFICATION`.

### T031 — Sensor-aware directional gates

- **Status:** DONE
- **Objective:** Stop/block motion toward a verified cliff/contact hazard.
- **Exact scope:** Freshness, active-low mapping config, directional policy, boot/unknown/stuck handling.
- **Expected files/modules:** safety sensor policy/tests.
- **Prerequisites:** T017, T024–T026, T030A, hardware-verified sensor mapping.
- **Constraints:** Task begins `NEEDS_HARDWARE_VERIFICATION`; no mapping by source claim alone.
- **Acceptance criteria:** Each sensor blocks correct direction; stale/malformed/absent is safe; reverse escape policy documented.
- **Tests required:** Exhaustive mapping and injected traces; real-LOOI controlled edge substitute.
- **Non-goals:** TOF navigation.
- **Completion evidence:** T030A supplied real FED9 notifications, then controlled black-matte transitions on the real LOOI verified the five-byte active-low frame and physical order `01 FL FR RL RR`. Redacted fixtures show FL `0100010101`, FR `0101000101`, RL `0101010001`, RR `0101010100`, and safe return `0101010101`: `t031-prerequisite-fed9-front-left-transition-repeat-2026-08-24.json`, `t031-prerequisite-fed9-front-right-transition-2026-08-24.json`, `t031-prerequisite-fed9-rear-left-transition-2026-08-24.json`, and `t031-prerequisite-fed9-rear-right-transition-2026-08-24.json`. The pure safety reducer/policy is generation-scoped and fail-closed for boot/unknown, malformed, future, stale, and wrong-generation state. Directional gating uses the established manual convention (`leftWheel = linear + angular`, `rightWheel = linear - angular`): each active corner blocks wheel travel toward that corner, while direct reverse escape remains permitted; a continuously triggered/stuck sensor retains the same directional block. Deterministic tests cover all four forward/reverse and turning paths, stale/malformed/absent state, stuck-active behavior, reducer timing, stale generations, exact fixture mapping, and safe returns. The targeted fixture-adapter, directional-policy, and drive-executor tests passed together; safety-rejected requests produced no `FED0` operation or dead-man refresh. This satisfies the required real-LOOI controlled edge substitute without actuating motors. T031 is `DONE`; real wheel movement and full stop acceptance remain explicitly unverified here and belong to T032.

### T030A — FED9 Telemetry Capture Diagnostic

- **Status:** DONE
- **Objective:** Collect bounded, read-only real-hardware FED9 notification evidence before T031.
- **Exact scope:** Subscribe only to FED9 through the compatible observed profile; retain immutable raw payloads with generation, relative timestamp, sequence, endpoint, and profile metadata; show diagnostic capture state and export a redacted T005 fixture.
- **Prerequisites:** T011, T017, T030.
- **Constraints:** Reuse only the physically verified non-motion Candidate A handshake prerequisite when required to keep FED9 alive. No drive, head, light, FED0, heartbeat, FE00, FF02, telemetry interpretation, or experimental writes. Android Bluetooth types remain in platform transport; stale-generation notifications are discarded; capture is bounded and stops on disconnect/cancel.
- **Acceptance criteria:** Automated capture/fixture tests pass; physical phone/LOOI run observes subscription and bounded idle FED9 evidence, exports a redacted fixture, then cleanly stops. If no notifications arrive, retain exact failure evidence and do not improvise writes.
- **Tests required:** Subscription lifecycle, immutable bytes, stale callback, arbitrary payload, cancellation/disconnect, bounded capture, T005 round-trip, and prohibited-endpoint guard.
- **Non-goals:** T031 sensor mapping, actuation, packet semantics, T032 acceptance.
- **Completion evidence:** `./gradlew testDebugUnitTest lintDebug assembleDebug` passed with the required Android Studio JBR and SDK. The first physical run on Samsung SM-S928B / Android 16/API 36 proved that a bare FED9 subscription became `ACTIVE` but received zero notifications before Android GATT status `19` disconnected it after about `1951 ms`; evidence is retained in `app/src/test/resources/fixtures/t030a-hardware-fed9-active-no-notification-2026-08-24.json`. The successful follow-up reused only the previously hardware-verified non-motion Candidate A prerequisite (`FEDA=01`, active FED5/FED9 subscriptions, `FEDA=03`, FED8 read), then started the bounded FED9 capture. The capture stayed active, received 14 immutable raw FED9 notifications, stopped cleanly, and exported a redacted T005-compatible fixture at `app/src/test/resources/fixtures/t030a-hardware-fed9-notifications-2026-08-24.json`. No FED0, heartbeat, drive/head/light command, actuation, telemetry interpretation, profile-ready promotion, or private device identifier was used or exported. T030A is complete; subsequent controlled physical transitions established the directional sensor mapping and unblocked T031 implementation.

### T032 — M2 manual-control acceptance

- **Status:** DONE
- **Objective:** Prove reliable manual operation without the original app.
- **Exact scope:** Execute full Stage C/D checklist, 10 reconnects, 15-minute drive session, all stop fault injections; fix only acceptance defects.
- **Expected files/modules:** hardware run evidence; targeted fixes/tests if failures found.
- **Prerequisites:** T020–T031.
- **Constraints:** No autonomy/AI; any uncontrolled motion blocks milestone.
- **Acceptance criteria:** All M2 checklist items pass on declared hardware or exceptions have ADR approval.
- **Tests required:** Full automated suite plus Android-device/real-LOOI run.
- **Non-goals:** Later milestone features.
- **Completion evidence:** The signed and dated run record is `docs/T032_M2_ACCEPTANCE_RUN_2026-08-24_25.md`. On the real LOOI with Samsung SM-S928B / Android 16 (API 36), Stage C/D acceptance passed for explicit manual arming, profile-A drive in all four correctly calibrated directions, release-to-neutral, bounded head up/down/center, light off/minimum-on, active-low directional lower-contact stops, 10/10 clean reconnects, and a user-timed 15-minute session without disconnect, uncontrolled motion, or observed command failure. Fault injection passed for local E-stop, app background, explicit disconnect, Bluetooth adapter-off, and Android process/service termination; the run record separates ADB command completion (`210 ms` adapter-off, `166 ms` force-stop) from qualitative physical stop observation and records the approximately `100 ms` background/dead-man observation. Android rejected isolated `stopservice` while the Activity remained bound, so accepted ADR coverage uses verified process termination for combined Activity/service death. Bluetooth adapter-off is transport-loss evidence, not a claim of measured RF attenuation. Acceptance defects fixed in T032 include generation-owned dead-man sequence ordering, release watchdog cancellation, deterministic press-and-hold ownership, calibrated angular polarity, lower-contact queue cancellation with latched E-stop, and prevention of stale-command resume after reset. `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with the required Android Studio JBR/SDK; the final APK installed and the final drive, edge-stop/no-resume, light, reconnect, and head checks passed on physical hardware. Redacted telemetry evidence is retained in `app/src/test/resources/fixtures/t032-hardware-manual-drive-2026-08-24.json`. No autonomy, AI, raw-write UI, speed-limit increase, measured RF-range claim, or original-app dependency was introduced.

## M3 — Local Robot Intelligence

### T033 — Local intent router

- **Status:** TODO
- **Objective:** Route a small allowlist of exact local commands without an LLM.
- **Exact scope:** Text normalization and intents for stop, quiet, battery, light off, and control-mode requests.
- **Expected files/modules:** `interaction/localintent`, tests.
- **Prerequisites:** T021, T023, T029–T030.
- **Constraints:** Ambiguity means no physical action; stop recognition has priority.
- **Acceptance criteria:** Locale-scoped fixtures map deterministically and adversarial near-matches do not move.
- **Tests required:** Table/fuzz tests.
- **Non-goals:** General NLU or wake word.
- **Completion evidence:** Intent fixture report.

### T034 — Audio session state machine

- **Status:** TODO
- **Objective:** Model activation, listening, thinking, speaking, interruption, timeout, and error states.
- **Exact scope:** Pure reducer and audio-focus/lifecycle events; no STT provider.
- **Expected files/modules:** `voice/session`, tests.
- **Prerequisites:** T006.
- **Constraints:** Cancellation first-class; never imply continuous cloud streaming.
- **Acceptance criteria:** Every state has exit/timeout; interruption cancels output/in-flight request.
- **Tests required:** Transition and virtual-time tests.
- **Non-goals:** Capture or synthesis.
- **Completion evidence:** State transition coverage.

### T035 — Explicit activation and local VAD

- **Status:** TODO
- **Objective:** Capture audio only during explicit sessions and detect speech boundaries locally.
- **Exact scope:** Mic permission, push-to-talk activation, AudioRecord adapter, pluggable VAD, ephemeral buffers.
- **Expected files/modules:** `voice/capture`, setup UI/tests.
- **Prerequisites:** T034.
- **Constraints:** No cloud upload; discard raw audio by default; foreground restrictions respected.
- **Acceptance criteria:** Denial/error degrades safely; session timeout releases mic; VAD events have measured latency.
- **Tests required:** Fake audio tests and Android-device tests.
- **Non-goals:** Wake word or STT.
- **Completion evidence:** Device latency/resource log.

### T036 — Speech adapter contracts and local command path

- **Status:** TODO
- **Objective:** Define STT/TTS contracts and connect transcript to local intents before AI.
- **Exact scope:** Interfaces/events/cancellation, fake adapters, local intent orchestration, audio focus.
- **Expected files/modules:** `voice/api`, interaction orchestrator/tests.
- **Prerequisites:** T033–T035.
- **Constraints:** Provider-neutral; no credentials/dependency on cloud SDK.
- **Acceptance criteria:** Fake transcript can execute allowed local non-motion/status commands; interruption cancels TTS.
- **Tests required:** End-to-end fake session tests.
- **Non-goals:** Choose production STT/TTS.
- **Completion evidence:** Deterministic conversation trace.

### T037 — Device sensor gateway

- **Status:** TODO
- **Objective:** Publish lifecycle-aware phone accelerometer/gyro/orientation observations independently from robot telemetry.
- **Exact scope:** Sensor interfaces, timestamps, sampling policy, unavailable state, test fake.
- **Expected files/modules:** `perception/device`, tests.
- **Prerequisites:** T006.
- **Constraints:** No fusion/navigation claim; battery-aware sampling; monotonic timestamps.
- **Acceptance criteria:** Start/stop releases listeners and observations identify source/freshness.
- **Tests required:** Fake sensor/lifecycle and Android-device tests.
- **Non-goals:** Robot pose estimation.
- **Completion evidence:** Device trace or pending marker.

### T038 — Perception event contracts

- **Status:** TODO
- **Objective:** Define independent, confidence-bearing voice/vision/device/robot perception events.
- **Exact scope:** Typed envelopes, provenance, coordinate/time semantics, freshness and privacy classification.
- **Expected files/modules:** `perception/api`, tests/docs.
- **Prerequisites:** T017, T037.
- **Constraints:** Observations are not facts or movement authority.
- **Acceptance criteria:** Events serialize/version and unknown confidence/source is rejected.
- **Tests required:** Schema round-trip/boundary tests.
- **Non-goals:** Camera/model implementation.
- **Completion evidence:** Schema examples and tests.

### T039 — Deterministic robot skill registry

- **Status:** TODO
- **Objective:** Run small local skills with typed inputs, permissions, bounds, cancellation, and observed outcomes.
- **Exact scope:** Registry/executor; initial non-motion battery/status/light-off skills; lifecycle/audit.
- **Expected files/modules:** `skills/api`, `skills/local`, tests.
- **Prerequisites:** T022–T024, T030, T033.
- **Constraints:** No raw driver; motion skills deferred; bounded execution.
- **Acceptance criteria:** Unknown/invalid skill rejected; cancellation releases authority; outcome reflects telemetry.
- **Tests required:** Registry/schema/timeout tests.
- **Non-goals:** AI tool calling or plugin marketplace.
- **Completion evidence:** Skill execution traces.

## M4 — AI Interaction

### T040 — AI gateway protocol contract

- **Status:** TODO
- **Objective:** Specify versioned provider-neutral text/realtime/vision/embedding and error contracts.
- **Exact scope:** OpenAPI/JSON schemas, streaming event order, cancellation, usage/cost fields, compatibility policy.
- **Expected files/modules:** `gateway-contract/`, Android DTO tests, AI docs.
- **Prerequisites:** T004, T036, T038–T039.
- **Constraints:** No provider names/types or robot raw packets; no secrets.
- **Acceptance criteria:** Contract validates examples and can represent partial output, tool proposal, refusal, timeout, usage.
- **Tests required:** Schema compatibility/round-trip tests.
- **Non-goals:** Backend deployment.
- **Completion evidence:** Versioned schema and test output.

### T041 — Secure client authentication boundary

- **Status:** TODO
- **Objective:** Authenticate app to gateway without embedding provider credentials.
- **Exact scope:** Threat model, short-lived scoped token interface, secure storage, logout/revocation, debug/release endpoint policy.
- **Expected files/modules:** `gateway/auth`, network security config, tests/docs.
- **Prerequisites:** T040 and deployment identity decision.
- **Constraints:** Provider keys server-only; cleartext debug only and excluded from release.
- **Acceptance criteria:** Release APK contains no sample/provider secret; expired/revoked token fails closed.
- **Tests required:** Unit/instrumented storage tests, secret scan.
- **Non-goals:** Provider account implementation without chosen backend.
- **Completion evidence:** Threat checklist and binary/source scan.

### T042 — Gateway client and streaming reducer

- **Status:** TODO
- **Objective:** Consume the gateway contract with cancellation and deterministic stream state.
- **Exact scope:** HTTP/stream adapter, event reducer, timeout, typed errors, fake server.
- **Expected files/modules:** `ai/gateway`, tests.
- **Prerequisites:** T040–T041.
- **Constraints:** Bounded buffers; lifecycle cancellation; redacted logs.
- **Acceptance criteria:** Partial text/tool/usage/error sequences reduce correctly; cancellation closes transport.
- **Tests required:** Contract/fake-server ordering, truncation, timeout tests.
- **Non-goals:** Conversation UX or actual provider.
- **Completion evidence:** Integration traces.

### T043 — Conversation context builder

- **Status:** TODO
- **Objective:** Build bounded task context from current turn, summary, selected memories, and needed tool schemas.
- **Exact scope:** Token/item budgets, ordering, provenance IDs, privacy filter; empty memory adapter initially.
- **Expected files/modules:** `conversation/context`, tests.
- **Prerequisites:** T038–T040.
- **Constraints:** Never entire history; no database/provider coupling.
- **Acceptance criteria:** Hard budgets hold and forbidden privacy classes never serialize.
- **Tests required:** Boundary/adversarial prompt tests.
- **Non-goals:** Memory retrieval implementation.
- **Completion evidence:** Context snapshots and size tests.

### T044 — Tool proposal validator

- **Status:** TODO
- **Objective:** Strictly validate AI tool proposals before local registry invocation.
- **Exact scope:** Schema/version/authorization/risk/confirmation/idempotency validation and audit reason.
- **Expected files/modules:** `ai/tools`, tests.
- **Prerequisites:** T039–T043.
- **Constraints:** Extra fields rejected; AI cannot reset e-stop/change limits/access raw BLE.
- **Acceptance criteria:** Malformed/unauthorized/injected proposals cause no skill invocation.
- **Tests required:** Schema fuzz, allowlist, replay, prompt-injection fixtures.
- **Non-goals:** Add motion tools.
- **Completion evidence:** Rejection matrix.

### T045 — AI robot-action mediation

- **Status:** TODO
- **Objective:** Map a tiny allowlist of validated semantic proposals to short AI-owned action leases.
- **Exact scope:** Non-motion first; one explicitly bounded motion candidate only after M2 hardware acceptance and confirmation UX.
- **Expected files/modules:** tool adapters, core authority integration/tests.
- **Prerequisites:** T032, T039, T044.
- **Constraints:** Same safety path as manual; manual/e-stop preemption; observed outcomes.
- **Acceptance criteria:** AI cannot exceed envelope or continue after cancel/timeout/disconnect.
- **Tests required:** End-to-end fake agent/core/body fault tests; hardware if motion enabled.
- **Non-goals:** Arbitrary plans or raw packets.
- **Completion evidence:** Authorization/stop traces and hardware status.

### T046 — Voice conversation orchestration and barge-in

- **Status:** TODO
- **Objective:** Connect activated audio, STT/realtime gateway, context, output, TTS, and interruption.
- **Exact scope:** Turn coordinator, latency timestamps, cancel propagation, fallback messages.
- **Expected files/modules:** `conversation/runtime`, voice adapters/tests.
- **Prerequisites:** T036, T042–T044.
- **Constraints:** Mic session-bound; local stop wins; no continuous cloud stream.
- **Acceptance criteria:** New speech cancels playback/generation; provider loss releases audio and robot lease.
- **Tests required:** Fake realtime timing/cancellation and Android-device audio tests.
- **Non-goals:** Wake word or memory writes.
- **Completion evidence:** Latency trace and device result.

### T047 — Task/model policy and cost controls

- **Status:** TODO
- **Objective:** Select capabilities/models by configuration and enforce budgets without app hard-wiring.
- **Exact scope:** Gateway-side policy contract, per-task limits, daily/session budgets, circuit breaker, usage UI data.
- **Expected files/modules:** gateway policy/config, Android status model/tests.
- **Prerequisites:** T040, T042.
- **Constraints:** Models configurable; safe local functions independent; no silent unlimited fallback.
- **Acceptance criteria:** Budget exhaustion blocks expensive requests with explicit result; adapter can be swapped in tests.
- **Tests required:** Policy matrix, usage accounting, retry/circuit tests.
- **Non-goals:** Optimize a specific vendor model.
- **Completion evidence:** Configuration examples and budget tests.

### T048 — M4 privacy/security/latency acceptance

- **Status:** TODO
- **Objective:** Verify AI interaction boundaries before enabling general use.
- **Exact scope:** Secret scan, network/auth tests, context audit, tool red-team fixtures, barge-in and perceived-latency measurements.
- **Expected files/modules:** acceptance evidence and defect fixes only.
- **Prerequisites:** T040–T047.
- **Constraints:** No provider key in app; failures cannot affect stop.
- **Acceptance criteria:** Defined privacy/security cases pass and latency measurements are documented rather than marketed.
- **Tests required:** Full M4 automated/device acceptance suite.
- **Non-goals:** Memory or vision.
- **Completion evidence:** Dated acceptance report with measurements/blockers.

## M5 — Personal Memory

### T049 — Versioned memory domain schema

- **Status:** TODO
- **Objective:** Represent all memory types with provenance, confidence, validation, privacy, retention, conflicts, and tombstones.
- **Exact scope:** Domain entities/value validators/versioning; no database.
- **Expected files/modules:** `memory/domain`, tests.
- **Prerequisites:** T006, T043.
- **Constraints:** Embeddings are derived; model inference is candidate/untrusted.
- **Acceptance criteria:** Invalid provenance/privacy/confidence rejected; correction/deletion states explicit.
- **Tests required:** Constructor/schema/boundary/round-trip tests.
- **Non-goals:** Storage or retrieval.
- **Completion evidence:** Schema examples and tests.

### T050 — Encrypted local memory repository

- **Status:** TODO
- **Objective:** Persist versioned records locally with migration and transactional deletion support.
- **Exact scope:** Room/SQLite adapter, Keystore-backed encryption decision, migrations, repository interface.
- **Expected files/modules:** `memory/storage`, migrations/tests, ADR if library selected.
- **Prerequisites:** T049.
- **Constraints:** No plaintext backups/logging; loss/recovery behavior explicit.
- **Acceptance criteria:** CRUD/version/supersession/tombstone survive restart; migrations preserve provenance.
- **Tests required:** JVM/instrumented database, migration, key-loss tests.
- **Non-goals:** Sync or vector search.
- **Completion evidence:** Migration/device test reports.

### T051 — Memory candidate validator

- **Status:** TODO
- **Objective:** Prevent model-created or weak observations from silently becoming trusted facts.
- **Exact scope:** Type-specific validation rules, confirmation requirement, sensitivity and contradiction detection.
- **Expected files/modules:** `memory/validation`, tests.
- **Prerequisites:** T049–T050.
- **Constraints:** Explicit user correction outranks inference; validators deterministic.
- **Acceptance criteria:** Each source/type follows documented lifecycle and disputed records do not enter normal retrieval.
- **Tests required:** Rule matrix and adversarial inferred-memory tests.
- **Non-goals:** Confirmation UI.
- **Completion evidence:** Validation decision fixtures.

### T052 — Deduplication and correction workflow

- **Status:** TODO
- **Objective:** Merge candidate duplicates without losing provenance and support explicit corrections.
- **Exact scope:** Canonical keys, similarity candidate interface, merge/supersede/conflict transactions.
- **Expected files/modules:** `memory/reconcile`, storage tests.
- **Prerequisites:** T050–T051.
- **Constraints:** No destructive silent overwrite; vector similarity alone cannot merge.
- **Acceptance criteria:** Multi-source fact retains sources; correction removes old active value; conflict remains inspectable.
- **Tests required:** Duplicate/correction/conflict/idempotency tests.
- **Non-goals:** UI or embeddings provider.
- **Completion evidence:** Before/after record graphs.

### T053 — Bounded memory retrieval

- **Status:** TODO
- **Objective:** Return a small privacy-filtered context packet relevant to a task.
- **Exact scope:** Exact/lexical retrieval, optional embedding interface, re-ranker, diversity, token/item budget, source IDs.
- **Expected files/modules:** `memory/retrieval`, context integration/tests.
- **Prerequisites:** T043, T050–T052.
- **Constraints:** Active validated records only by default; hard budget; embeddings not canonical.
- **Acceptance criteria:** Relevance/recency/confidence/provenance policy is deterministic and privacy partition cannot leak.
- **Tests required:** Golden ranking, budget, conflict, adversarial privacy tests.
- **Non-goals:** Cloud vector database.
- **Completion evidence:** Retrieval explanations and tests.

### T054 — Provenance-preserving summaries

- **Status:** TODO
- **Objective:** Compact conversations/episodes while retaining sources, uncertainty, and replaceability.
- **Exact scope:** Summary candidate contract, covered-range/source links, deterministic checks, hierarchical refresh.
- **Expected files/modules:** `memory/summarization`, fake model/tests.
- **Prerequisites:** T042, T051–T053.
- **Constraints:** Summary untrusted until validated; source records retained per policy; strict size limit.
- **Acceptance criteria:** New evidence refreshes affected summary and deletion propagates to derived content.
- **Tests required:** Fake summary, provenance, update/deletion tests.
- **Non-goals:** Fine-tuning.
- **Completion evidence:** Source-to-summary graph and tests.

### T055 — Memory management, export, and deletion UI

- **Status:** TODO
- **Objective:** Let users inspect why a memory exists, correct/delete/export it, and set privacy/retention.
- **Exact scope:** Browse/detail/provenance, candidate confirmation, correction, deletion propagation status, portable export.
- **Expected files/modules:** `feature/memory`, repository use cases/tests.
- **Prerequisites:** T050–T054.
- **Constraints:** Destructive confirmation; export encrypted or explicit risk warning; no hidden copies.
- **Acceptance criteria:** Deleted record disappears from exact/lexical/vector/cache/summary retrieval and audit proves propagation.
- **Tests required:** Use-case/UI/instrumented deletion/export tests.
- **Non-goals:** Cloud sync or social sharing.
- **Completion evidence:** Deletion audit and UI test results.

## M6 — Vision and Higher-Level Behaviors

### T056 — Camera session and frame-policy abstraction

- **Status:** TODO
- **Objective:** Acquire camera frames only in visible/authorized sessions with bounded processing.
- **Exact scope:** CameraX adapter, lifecycle/permission, frame metadata, backpressure, downscale/crop policy, ephemeral buffers.
- **Expected files/modules:** `vision/camera`, tests.
- **Prerequisites:** T038.
- **Constraints:** No background continuous camera; no cloud upload; release resources promptly.
- **Acceptance criteria:** Permission/lifecycle/error paths work and frame backlog stays bounded.
- **Tests required:** Fake frame tests and Android-device camera tests.
- **Non-goals:** Detection or recording.
- **Completion evidence:** Device resource/frame-rate log.

### T057 — Local person/object detection adapter

- **Status:** TODO
- **Objective:** Produce local detection events with model/version/confidence and coordinates.
- **Exact scope:** Select one on-device runtime/model, inference adapter, throttling, taxonomy mapping.
- **Expected files/modules:** `vision/detection`, model asset policy/tests/ADR.
- **Prerequisites:** T056.
- **Constraints:** License/model size documented; no identity recognition; thermal/battery limits.
- **Acceptance criteria:** Synthetic/consented test set yields measured latency/precision notes and graceful no-model state.
- **Tests required:** Golden images, coordinate transforms, device benchmark.
- **Non-goals:** Face identity or tracking/movement.
- **Completion evidence:** Model/license record and benchmark.

### T058 — Local target tracker

- **Status:** TODO
- **Objective:** Associate detections over time and expose bounded target offset/confidence.
- **Exact scope:** Track lifecycle, smoothing, lost-target timeout, target selection/cancel.
- **Expected files/modules:** `vision/tracking`, tests.
- **Prerequisites:** T057.
- **Constraints:** No robot commands; uncertainty and frame age explicit.
- **Acceptance criteria:** Occlusion/lost/re-entry traces behave deterministically and stale track expires.
- **Tests required:** Recorded synthetic sequences and virtual-time tests.
- **Non-goals:** Person identity or navigation.
- **Completion evidence:** Track trace visualization/data.

### T059 — Selective cloud vision request

- **Status:** TODO
- **Objective:** Answer explicit visual questions with minimized, user-authorized images through provider-neutral gateway.
- **Exact scope:** Capture selection, resize/redaction hook, privacy confirmation, vision contract, cost/result metadata.
- **Expected files/modules:** `vision/cloud`, gateway integration/tests.
- **Prerequisites:** T040–T043, T056.
- **Constraints:** No continuous upload; exclude bystanders/sensitive memory by policy; discard image per retention.
- **Acceptance criteria:** Only selected frame is sent and cancel/privacy denial sends nothing.
- **Tests required:** Fake gateway/privacy/size/cancellation tests.
- **Non-goals:** Surveillance or face database.
- **Completion evidence:** Request audit with synthetic image.

### T060 — Bounded behavior plan schema

- **Status:** TODO
- **Objective:** Represent multi-step behavior as allowlisted, time-bounded semantic steps.
- **Exact scope:** Step schema, total envelope, preconditions, cancellation, observed completion, compensation/stop.
- **Expected files/modules:** `behavior/domain`, validator tests.
- **Prerequisites:** T039, T045, T053.
- **Constraints:** No raw bytes/unbounded loops; safety re-evaluated per step; manual override always wins.
- **Acceptance criteria:** Invalid duration/speed/tool/nesting rejected; plan cannot expand its own limits.
- **Tests required:** Schema/property/adversarial tests.
- **Non-goals:** Execute plans.
- **Completion evidence:** Acceptance/rejection corpus.

### T061 — Bounded behavior executor

- **Status:** TODO
- **Objective:** Execute validated steps sequentially with leases, telemetry checks, timeout, and stop.
- **Exact scope:** State machine, step observation, pause/cancel/fault, audit trace; fake body first.
- **Expected files/modules:** `behavior/runtime`, tests.
- **Prerequisites:** T024, T039, T060.
- **Constraints:** Revalidate every physical step; no background orphan; no hardware enable by default.
- **Acceptance criteria:** Any fault/cancel/manual override prevents further steps and requests stop.
- **Tests required:** Virtual-time/fake-body exhaustive state tests.
- **Non-goals:** Person following.
- **Completion evidence:** Fault-injected behavior traces.

### T062 — Feasibility-gated person following experiment

- **Status:** TODO
- **Objective:** Measure whether camera target offsets plus LOOI actuation can support safe, slow following.
- **Exact scope:** Simulation/raised-wheel controller, lost-target stop, floor experiment only after review, metrics and go/no-go report.
- **Expected files/modules:** experimental behavior, tests, hardware evidence, ADR result.
- **Prerequisites:** T031–T032, T058, T060–T061.
- **Constraints:** Explicit experimental mode; tiny speed/duration; no stairs/crowds; no identity inference.
- **Acceptance criteria:** Defined latency, loss, oscillation, stop, and sensor metrics determine feasible/not feasible; failure is a valid outcome.
- **Tests required:** Simulation, device benchmark, supervised hardware checklist.
- **Non-goals:** Production autonomous navigation.
- **Completion evidence:** Measurements and accepted go/no-go ADR.

### T063 — Personality presentation layer

- **Status:** TODO
- **Objective:** Add configurable expression/voice/style without changing truth, safety, tools, or memory authority.
- **Exact scope:** Presentation configuration, prompt segment, facial UI states, accessibility and quiet mode.
- **Expected files/modules:** `personality`, UI/tests.
- **Prerequisites:** T046, T055, T061.
- **Constraints:** Cannot alter tool schemas/safety decisions or fabricate sensor/memory facts.
- **Acceptance criteria:** Persona swap changes presentation only; quiet/accessibility modes override it.
- **Tests required:** Snapshot/state and prompt-boundary tests.
- **Non-goals:** Emotional manipulation or hidden profiling.
- **Completion evidence:** Boundary tests and UI captures.

### T064 — M6 system acceptance and capability claims

- **Status:** TODO
- **Objective:** Audit end-to-end capabilities and publish only evidence-supported claims/limits.
- **Exact scope:** Full automated/device/hardware suite, privacy/safety review, thermal/battery runs, capability matrix and failure modes.
- **Expected files/modules:** acceptance report, README/roadmap updates, defect fixes only.
- **Prerequisites:** T056–T063 and all enabled earlier features.
- **Constraints:** Infeasible features are documented/disabled, not relabeled as success.
- **Acceptance criteria:** Every advertised feature maps to evidence level, supported hardware, limits, and immediate stop/manual override.
- **Tests required:** Full regression plus applicable hardware checklist.
- **Non-goals:** Smart home, remote operation, new body support.
- **Completion evidence:** Dated release-candidate evidence matrix and unresolved blockers.
