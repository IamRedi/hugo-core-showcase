# FL014 - First-life end-to-end composition

## Status

`DONE` as of 2026-08-30. The Android-neutral composition owner, production wiring, scripted HiHugo
controls, provider-setup bridge, fake end-to-end/fault tests, Samsung instrumentation, cold launch,
and the mandatory Samsung + real-LOOI end-to-end rehearsal are complete. FL015 is now eligible but
has not started; H036 remains unstarted.

## Composition architecture

`firstlife/experience` is the FL014 application-session owner. It binds the accepted FL004
`FirstLifeInteractionReducer` to the existing FL009 voice, FL010 speech, FL011/FL012 camera/person,
FL013 orientation, and FL005-FL008 body presentation owners. It contains no Android, UI, provider,
LOOI, transport, protocol, or execution dependency.

The production root in `MainActivity` supplies only narrow effects backed by the already accepted
coordinators. `FirstLifeProviderSetupController` is a platform-only presentation bridge over the
existing scanner, H028 connection provider, verified handshake, and FL006 provider session. It
keeps the raw scan identifier inside platform code and exposes only redacted setup stages. It does
not create a second scanner, GATT, handshake, manifest, capability, safety, or execution path.

The resulting scripted path is:

`persistent H003 identity -> fixed local introduction -> explicit microphone tap -> bounded name
result -> fixed local greeting -> explicit visible-camera tap -> fresh centered evidence ->
orientation stop -> camera close`.

Discovery and connection remain explicit controls. The script does not silently discover, connect,
select a body, open a sensor, authorize motion, or claim success without corresponding current
evidence. The provider setup panel only finds a compatible named candidate, requests the existing
generic connection/verified preparation path, and permits disconnect. Active-body selection,
voice, camera, and orientation remain separate explicit controls. The First Life panel adds only
Start, Cancel, Reset, and the evidence-gated camera continuation.

The FL004 name-input deadline now begins only when FL009 reports `LISTENING`. Time spent reviewing
and explicitly consenting to the system fallback is not charged against the unchanged eight-second
microphone window. The fallback remains opt-in per session; no automatic cloud fallback was added.

## Lifecycle and reset

The coordinator admits only one active request in each accepted subsystem and rejects conflicting
voice/camera/orientation starts with typed `RESOURCE_CONFLICT` evidence. Full-session Cancel, Reset,
background, and disposal use one deterministic cleanup order:

1. stop orientation through the existing FL013 coordinator;
2. close the visible camera;
3. release voice capture;
4. cancel local speech;
5. deactivate the active-body selection.

Cleanup is idempotent for a repeated background callback. Reset creates a new run/session reference,
clears the ephemeral name and observation completion, and preserves the exact H003 `HugoIdentity`.
Process recreation likewise starts a fresh run with the same application bootstrap identity and no
restored name, camera, microphone, orientation, provider authority, or success state.

A stable fresh `CENTER` result closes the scripted observation safely: orientation is stopped first,
then the camera is closed. The presentation retains only a boolean typed completion fact. It does
not retain the camera request, frame, box, confidence trace, transcript, name, provider identity,
connection session, authorization, or transport detail.

## Automated and Samsung evidence

- The fake end-to-end test uses the real `SpeechOutputCoordinator`, `VoiceSessionCoordinator`,
  `CameraSessionCoordinator`, FL004 reducer, and FL014 coordinator. It proves introduction,
  explicit listening, bounded transcript delivery, automatic greeting, explicit camera start,
  three-observation stable center, orientation stop, camera close, and final completion.
- Fault tests cover camera and microphone permission failure, resource conflicts, cleanup ordering,
  repeated lifecycle callbacks, explicit reset, process/repository recreation, late callbacks,
  body loss, identity continuity, and the absence of invented centered/success evidence.
- Architecture/privacy tests reject Android, LOOI, platform, Bluetooth, GATT, FED, encoder, private
  payload, and raw-string fields from the composition domain/projection.
- The debug and androidTest APKs installed on the authorized Samsung SM-S928B. The new HiHugo FL014
  UI suite passed `OK (3 tests)` in 2.506 seconds. It verified explicit Start, ephemeral Reset,
  explicit camera continuation presentation, and evidence-gated completion without opening a real
  camera, microphone, or LOOI session.
- A real cold launch completed successfully in 652 ms and exposed the honest ready panel. No
  Android runtime crash was present in the bounded log check.

## Samsung + real-LOOI rehearsal

- ADB continuously reported the authorized `SM-S928B` (`RFCX10YN5TF`) as `device`. The current debug
  and androidTest APKs installed successfully and the app cold-launched after replacement.
- The FL002 instrumentation restored the same redacted identity before and after activity
  recreation: `HUGO-5875C91F`. Repository recreation, authenticated storage tamper handling, and
  unsupported storage handling also passed; only the safe display identity was printed.
- With the official LOOI app closed and the real body explicitly advertising, the bounded scan
  found one compatible candidate. The app connected through the existing H028 path, completed the
  verified handshake/provider preparation, resolved the LOOI Reference BODY, and reported current
  negotiation for exactly:
  `body.head.position.control@1.0`, `body.light.control@1.0`, `mobility.move@1.0`,
  `mobility.stop@1.0`, `provider.connection.status@1.0`, and
  `safety.lower_contact.status@1.0`.
- Active-body selection required a separate explicit action. The presentation continued to state
  that discovery, connection, negotiation, readiness, and selection grant no trust, authorization,
  safety approval, or execution authority.
- The fixed local introduction completed. Local Albanian recognition honestly reported its absent
  on-device model; after explicit per-session fallback consent, the bounded system capture accepted
  `Redi`, discarded the transcript, and completed the local fixed greeting. The physical retry
  proved that consent-review time no longer consumes the listening deadline.
- The user explicitly opened the visible front camera. A current stable centered-person result
  completed the First Life path, stopped orientation, and closed the camera. Because the person was
  already centered, the accepted FL013 centered no-motion branch was used: zero pulses and zero
  energized milliseconds; no movement, head, or light command was sent.
- The local E-stop control was invoked while idle with zero pulses/energized time. Backgrounding
  fail-closed removed the active-body selection while preserving the provider link. A fresh First
  Life session was then cancelled back to Ready. Explicit provider disconnect cleared body
  readiness and reported that HUGO identity was unchanged.
- Normal HiHugo evidence contained semantic labels only. No MAC address, raw provider identifier,
  Bluetooth/GATT object, FED/handshake detail, frame, face identity, transcript history, audio,
  video, transport address, trust, authorization, safety approval, or execution claim was exposed.

## Final checks

- `testDebugUnitTest`: 645 tests, 0 failures/errors/skips.
- Samsung targeted instrumentation: `OK (7 tests)` for FL002 identity continuity/storage and FL014
  presentation, including the three FL014 Compose cases.
- `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`: PASS.
- First Life documentation/order guard, HUGO V2 documentation guard, T032 baseline evidence guard,
  architecture/dependency guards, and `git diff --check`: PASS.

FL014 changes composition and presentation only. It does not weaken or bypass camera consent and
lifecycle, H029/H030 authorization, H018/T032 routing, lower-contact policy, E-stop, dead-man, or the
FL013 pulse envelope. FL015 is eligible but was not started; H036 was not started.
