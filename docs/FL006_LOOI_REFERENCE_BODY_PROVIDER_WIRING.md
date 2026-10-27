# FL006 - LOOI Reference Body #1 provider wiring

## Status

`DONE` as of 2026-08-27. The software composition, Samsung/real-LOOI provider flow, controlled
lower-contact transition, and minimum T032 physical regression all pass. FL007 remains unstarted.

## Implemented composition

- The adapter-owned built-in common and BODY manifests declare only the exact H017 capability set.
- The built-in H012 descriptor and H025 knowledge record identify one known adapter definition;
  registry presence does not claim installation, trust, enablement, loading, or authority.
- H021 evidence starts as a definition-only candidate. A protected provider-instance binding is
  promoted to the H022 known-instance result only after the existing verified handshake succeeds.
- H023 manifest resolution, H024 adapter compatibility, H018 registration, and H029 negotiation use
  their canonical contracts. Missing adapter, tampered manifest, stale generation, missing handshake,
  and provider loss remain blocked.
- H032 provider-instance continuity uses the existing authenticated Android Keystore plus atomic
  no-backup storage boundary. Raw BLE identifiers remain platform-local and are never diagnosed.
- `ServiceOwnedLooiBleBridge` maps H028 correlation into the one existing
  `ConnectedDeviceService` connection owner. It creates no scanner, GATT object, protocol stack, or
  second connection owner.
- Genuine prepared, connection, negotiation, and BODY-available evidence is passed into the FL005
  coordinator. Active-body selection, authorization, safety approval, and execution remain false.
- H033 blocked projection and H034 redacted diagnostics are used for failure/current evidence.
  The FL006 H018 runtime port deliberately rejects execution; FL007 is still the only eligible place
  to add authorized semantic dispatch.

## Exact semantic capability set

1. `mobility.stop@1.0`
2. `mobility.move@1.0`
3. `body.head.position.control@1.0`
4. `body.light.control@1.0`
5. `safety.lower_contact.status@1.0`
6. `provider.connection.status@1.0`

No battery, dock, attachment, camera, autonomous, vendor-packet, or motor-encoder capability was
added.

## Automated evidence

- Relevant FL006/H017/H018/H023/H024/H027/H028/H029/FL005 JVM tests: PASS.
- Full `testDebugUnitTest`: PASS (559 tests, 0 failures/errors/skips).
- `lintDebug`: PASS.
- `assembleDebug`: PASS.
- `assembleDebugAndroidTest`: PASS.
- First Life documentation/order guard: PASS.
- HUGO V2 documentation guard: PASS.
- T032 baseline evidence guard: PASS.
- Architecture dependency guards (full JVM suite): PASS.
- Core/generic BLE, GATT, FED, handshake, and Android-type leakage scan: PASS.
- `git diff --check`: PASS (Git reports only the existing LF-to-CRLF checkout warning for
  `ConnectedDeviceService.kt`).

## Partial Samsung and real-LOOI evidence

- `adb devices -l`: authorized `SM_S928B`, serial redacted in this record, state `device`.
- Debug and androidTest APK streamed installs: PASS.
- Persistent HUGO safe display identity before force-stop, after cold relaunch, after activity
  recreation, and after the interrupted safety run: `HUGO-1EB8ED5B`.
- Generic provider-only instrumentation: PASS after Bluetooth was confirmed `ON`.
- Provider alias stayed `looi-4cea9b9dd3c3`; reconnect changed generation `1` to `2` and changed
  the redacted session reference.
- BODY manifest resolution, compatible adapter knowledge, verified handshake, and negotiation of
  exactly the six capabilities above: PASS.
- Trust, authorization, active-body selection, safety approval, and execution authority remained
  false.
- Bluetooth-off/provider-loss case: PASS; stage became `FAILED`, protocol readiness became false,
  and negotiated capabilities cleared.
- The first combined-class run passed the Bluetooth-off case, then its immediate next scan failed
  while Android was still restoring Bluetooth. The provider-only reconnect case passed when rerun
  after Android reported Bluetooth `ON`.
- One later T032 attempt experienced a transient USB/ADB reconnection before assertions completed;
  the service was force-stopped and Android reported no remaining service record.
- The repeated T032 attempt connected, completed the verified handshake, enabled FED9, obtained a
  known contact observation, and then rejected the first minimal left-turn request with
  `RejectedBySafety(reason=SENSOR_STATE_UNSAFE)`. No motion write was accepted. The test's `finally`
  path requested E-stop/disconnect, and the service record was absent afterward.
- A final read-only provider rerun with explicit FL005 flag assertions could not rediscover a named
  LOOI advertisement within 16 seconds. It made no connection and cannot add a new hardware claim.
- On the focused resume, the Samsung remained `device` across five one-second ADB checks and before
  and after instrumentation with unchanged transport ID `33`; the androidTest APK reinstalled
  successfully. The focused T032 test again observed no named LOOI advertisement within 16 seconds.
  It stopped at discovery, so no connection, FED9 read, safety decision, or motion request occurred.
- The opt-in verification harness now requires, before dispatch, a present/current T031 `01` frame,
  an all-supported semantic mapping through the existing `LowerContactSafetyAdapter`, and an
  `Allowed` dry preflight from the unchanged `DefaultSafetyController`. This is test-only evidence
  logic and changes no production safety policy or execution path.
- HUGO safe display identity after this stopped run remained `HUGO-1EB8ED5B`; no service record
  remained after cleanup.
- A requested pairing-mode retry was not executed: immediately after an initial authorized-device
  check, ADB lost the Samsung before the official-app force-stop and bounded instrumentation could
  run. The pending `am instrument` command was cancelled while it was still waiting for a device so
  it could not resume later and perform an unattended hardware test. No BLE scan, connection,
  safety evaluation, or motion request occurred in this attempt, and the discovery logic was not
  changed.
- The repeated pairing-mode attempt ran with the official app stopped, Bluetooth ON, and the Samsung
  continuously present as `device` on transport ID `34`. LOOI discovery, GATT connection, service
  discovery, verified handshake setup, and FED9 notification subscription succeeded. During the
  bounded 20-second preflight window no lower-contact evidence became both known and fresh, so the
  test failed at `Timed out waiting for T032 safety prerequisite`. It disconnected and closed GATT
  without evaluating a motion request. This is classified as missing/stale telemetry, not an ADB
  interruption and not a new `SENSOR_STATE_UNSAFE` result.

## Resolved physical gate

The final bounded run on the authorized Samsung and real LOOI completed the controlled T031
hazard-to-safe transition and the minimum T032 regression through the production FL006 composition.
The earlier `SENSOR_STATE_UNSAFE`, missing event-driven telemetry, discovery, and queue-race results
remain recorded below as fail-closed historical evidence; none was bypassed or reinterpreted.

## Event-driven telemetry audit

- FL006 and the accepted T031/T032 path use the same service-owned GATT adapter, operation queue,
  notification observer, connection generation, and `T032ManualAcceptanceSession`.
- Candidate A ordering is unchanged: subscribe FED5/FED9, write the verified FEDA handshake steps,
  perform the existing one-time FED8 read, then prepare T032 and start the bounded FED9 capture.
- The accepted path contains no periodic FED8 keep-alive, neutral drive write, or movement refresh to
  provoke lower-contact telemetry. Adding one would be a new protocol behavior and is not justified.
- ADR/T031/T032 evidence explicitly defines profile-A lower-contact `01` frames as transition events.
  A valid event remains authoritative within its unchanged generation; each later valid event
  replaces it, and disconnect/generation change invalidates it.
- The opt-in FL006 harness now mirrors the controlled T031 black-matte procedure: it waits without
  motor activity for one active-low hazard transition and a later exact safe-return
  `01 01 01 01 01` frame, requires matching current generation/session and fresh T032 state, then
  evaluates the unchanged `DefaultSafetyController` before any motion dispatch.
- The first controlled-transition attempt after this audit did not open the transition window
  because the unchanged bounded scanner observed no named LOOI candidate within 16 seconds. No
  connection, FED9 evidence, safety decision, or motion occurred. That attempt's blocker was
  discovery/advertising availability; the prior successful connection still demonstrates the
  separate event-driven stationary-silence behavior.
- A later pairing-mode run completed the controlled transition and full device-side regression:
  hazard `01 00 01 01 01`, safe return `01 01 01 01 01`, current/fresh generation, unchanged
  `DefaultSafetyController` `Allowed`, release stop, dead-man latch, local E-stop, and disconnect
  readiness clearing all passed. HUGO identity remained `HUGO-1EB8ED5B` and instrumentation reported
  `OK (1 test)`.
- The requested immediate repeat again passed hazard/safe return, fresh safe preflight, release stop,
  dead-man, and local E-stop, but the final minimal turn used to enter the disconnect-under-motion
  case was rejected with `GattOperationFailure(reason=QUEUE_FULL)`. The test cleanup requested
  E-stop/disconnect and Android showed no remaining service record. Because the repeat exposed a
  concrete nondeterministic queue-capacity failure, FL006 remains `NEEDS_DEVICE_VERIFICATION`; the
  failure is not reinterpreted as a safety or telemetry result and no queue/runtime change was made.
- The physical operator confirmed that neither the official LOOI app nor a visible HiHugo Activity
  was open. The FL006 instrumentation runner nevertheless started the `io.redi.robot` process and
  bound its background `ConnectedDeviceService`; that test-owned service created the verified BLE
  session. A dedicated no-motion isolation run then recorded hazard `01 00 01 01 01`, held the
  same generation/session and GATT connection stable for ten seconds, recorded a later fresh safe
  return `01 01 01 01 01`, and held the connection stable for another ten seconds before an
  explicit disconnect. Instrumentation reported `OK (1 test)`. The physical operator confirmed
  that LOOI's head/light presentation stayed normal during both sensor states and changed only at
  that explicit disconnect. This resolves the earlier apparent sensor-transition/disconnect
  discrepancy without changing T031 mapping, telemetry, handshake, or safety behavior.
- After the isolation pass, the paced T032 repeat again produced hazard `01 00 01 01 01`, fresh
  safe return `01 01 01 01 01`, and unchanged `DefaultSafetyController` `Allowed`. Physical
  release/stop, the 100 ms dead-man latch, and local E-stop all passed. The next phase exposed a
  concrete serialized-queue concurrency failure before disconnect-under-motion could be accepted:
  Android logged `NoSuchElementException` at
  `SerializedGattOperationQueue.removeFromQueue(GattOperationQueue.kt:352)`, followed by
  `RejectedByQueue(... QUEUE_FULL ...)`. Read-only audit shows queue mutation can occur from the
  instrumentation/service caller and the Android GATT callback thread without a synchronization
  boundary; `removeFromQueue` can therefore observe a non-empty `ArrayDeque` and race with another
  mutation before `removeFirst`. Cleanup disconnected safely. This is not a sensor, T031 mapping,
  provider-composition, or safety-policy failure, and FL006 does not change the accepted T032
  execution path to bypass it.

## Final Samsung and real-LOOI acceptance

- The required `SM_S928B` remained authorized as ADB state `device` for the final run. Debug and
  androidTest builds installed successfully.
- The bounded scan discovered the advertising LOOI candidate, then the existing service-owned path
  completed GATT connection, verified handshake, and FED9 subscription. No second BLE, handshake,
  telemetry, or protocol path was introduced.
- With no motor command active, a controlled lower-contact transition produced current-generation
  hazard `01 00 01 01 01`; returning LOOI to the fully supported surface produced fresh
  current-generation safe `01 01 01 01 01`.
- The unchanged `LowerContactSafetyAdapter` and `DefaultSafetyController` mapped the safe return to
  `Allowed`. The connection remained stable during an additional ten-second no-motion safe hold.
- One unchanged minimal left turn was refreshed only for the bounded 200 ms observation window.
  The physical operator confirmed the turn was visible. Release/stop, the 100 ms dead-man latch,
  local E-stop, and explicit disconnect-safe readiness clearing all passed. LOOI's final presentation
  change occurred at the intentional disconnect, not at the lower-contact transition.
- The queue failure was resolved at its actual concurrency boundary by serializing mutation of the
  existing `SerializedGattOperationQueue`. A 1,000-iteration cancel-versus-callback JVM regression
  passes. Ordering, capacity, payload, timeout, generation, cancellation, and safety semantics are
  unchanged.
- Android scan permission selection now uses `BLUETOOTH_SCAN` on API 31+ and legacy fine-location
  permission on API 30 and below. A secondary Android 9 diagnostic confirmed the same provider path;
  the required acceptance claim remains the `SM_S928B` run.
- Persistent HUGO identity remained `HUGO-1EB8ED5B`; the targeted repository-recreation
  instrumentation passed after the final build. Run/session references may change, but no body,
  provider, permission, authority, or session state became HUGO identity.
- Prepared, connected, exact-six-capability negotiated, and BODY-available evidence reached FL005.
  Provider readiness still granted no trust, authorization, active-body authority, safety approval,
  or execution authority.
- Application diagnostics used redacted candidate categories and safe identity display only. No raw
  provider identifier, transport address, GATT/FED/handshake payload, or private transport evidence
  was added to HUGO diagnostics.
- Final checks: full JVM suite (559 tests), `lintDebug`, `assembleDebug`,
  `assembleDebugAndroidTest`, First Life/HUGO/T032/architecture guards, targeted HUGO identity
  instrumentation, and `git diff --check` all pass.
