# HUGO First Life acceptance - 2026-08-31

## Decision

`PASS / FROZEN`.

FL001-FL015 satisfy the documented First Life envelope on the authorized Samsung SM-S928B and the
real LOOI Reference Body #1. This decision grants no production-readiness, trust, general execution,
autonomy, AI, durable-memory, face-recognition, or multi-body claim. H036 was not started.

Accepted implementation head before this evidence record: `92687ba668d514246790960f814fd94fe862874e`.
The First Life implementation baseline remains FL014 commit
`760b435621b6897f7432301c4e14cfa2d50f2f9a`; the one later correction is disclosed below.

## Evidence index

| Slice | Accepted evidence |
|---|---|
| FL001 | [`FL001_SLICE_CONTRACT_FEASIBILITY.md`](FL001_SLICE_CONTRACT_FEASIBILITY.md) |
| FL002 | [`FL002_PERSISTENT_HUGO_IDENTITY_BOOTSTRAP.md`](FL002_PERSISTENT_HUGO_IDENTITY_BOOTSTRAP.md) |
| FL003 | [`FL003_MINIMAL_HIHUGO_SHELL_FACE_REDUCER.md`](FL003_MINIMAL_HIHUGO_SHELL_FACE_REDUCER.md) |
| FL004 | [`FL004_SCRIPTED_FIRST_LIFE_INTERACTION_REDUCER.md`](FL004_SCRIPTED_FIRST_LIFE_INTERACTION_REDUCER.md) |
| FL005 | [`FL005_UNIVERSAL_RUNTIME_COMPOSITION_HARNESS.md`](FL005_UNIVERSAL_RUNTIME_COMPOSITION_HARNESS.md) |
| FL006 | [`FL006_LOOI_REFERENCE_BODY_PROVIDER_WIRING.md`](FL006_LOOI_REFERENCE_BODY_PROVIDER_WIRING.md) |
| FL007 | [`FL007_AUTHORIZED_SEMANTIC_DISPATCH.md`](FL007_AUTHORIZED_SEMANTIC_DISPATCH.md) |
| FL008 | [`FL008_ACTIVE_BODY_CAPABILITY_PRESENTATION.md`](FL008_ACTIVE_BODY_CAPABILITY_PRESENTATION.md) |
| FL009 | [`FL009_EXPLICIT_VOICE_CAPTURE_STT.md`](FL009_EXPLICIT_VOICE_CAPTURE_STT.md) |
| FL010 | [`FL010_LOCAL_TTS_ALBANIAN_SCRIPT.md`](FL010_LOCAL_TTS_ALBANIAN_SCRIPT.md) |
| FL011 | [`FL011_VISIBLE_CAMERA_OBSERVATION_SESSION.md`](FL011_VISIBLE_CAMERA_OBSERVATION_SESSION.md) |
| FL012 | [`FL012_ON_DEVICE_PERSON_EVIDENCE.md`](FL012_ON_DEVICE_PERSON_EVIDENCE.md) |
| FL013 | [`FL013_BOUNDED_PERSON_ORIENTATION.md`](FL013_BOUNDED_PERSON_ORIENTATION.md) |
| FL014 | [`FL014_FIRST_LIFE_END_TO_END_COMPOSITION.md`](FL014_FIRST_LIFE_END_TO_END_COMPOSITION.md) |

All fourteen records exist in Git after the frozen H035 base. The reviewed task history remains
ordered; no FL task marks or renumbers H036-H080.

## Acceptance matrix

| Requirement | Result | Evidence |
|---|---|---|
| Cold launch and HiHugo face | PASS | Final post-hardware cold launch completed in 669 ms and rendered `HiHugo`, `Idle`, `HUGO is ready`, and First Life Ready. |
| Persistent H003 identity | PASS | The safe display correlation `HUGO-5875C91F` was identical before and after activity/process recreation and remained unchanged through the LOOI run and final relaunch. Run/session references changed independently. |
| Universal LOOI BODY flow | PASS | Bounded discovery, H028 connection, verified handshake, provider-instance binding, BODY manifest, adapter compatibility, negotiation, explicit active-body selection, loss, and disconnect used the accepted FL006/FL014 composition. |
| Exact semantic capabilities | PASS | The current set was exactly the six items listed below; no transport or protocol capability leaked. |
| Voice and session greeting | PASS | The FL014 signed rehearsal and current automated composition/fault suite prove fixed local Albanian introduction, explicit per-session system-fallback consent when local Albanian STT is unavailable, bounded `Redi` name input, transcript discard, and fixed local greeting. No voice implementation changed after FL014. |
| Visible camera/person evidence | PASS | Explicit front-camera UI was visibly active; frames were local and ephemeral. Current `CENTER`, `RIGHT`, stable, ambiguous, and loss evidence was exercised without identity inference. No vision implementation changed after FL014. |
| Bounded orientation route | PASS | Correlated evidence used H007 -> H029 -> H030 -> H018 -> T032. Center produced no motion; release, loss, pulse-budget, E-stop, background, and disconnect produced terminal stops. |
| Lower-contact safety | PASS | Current-generation hazard and all-supported return frames were observed. Movement was attempted while unsafe and remained at zero; movement proceeded only after the unchanged T032 policy accepted SAFE. |
| E-stop/background/disconnect | PASS | Local E-stop latched and cleared orientation authority; background produced confirmed stop and cleared authority; disconnect cleared protocol/capability/body readiness. |
| Privacy and architecture | PASS | No retained raw audio/video, transcript history, face identity, MAC, raw provider ID, GATT/FED/handshake detail, transport address, credential, or execution claim was found in app-owned output. |

The exact negotiated set was:

- `mobility.stop@1.0`
- `mobility.move@1.0`
- `body.head.position.control@1.0`
- `body.light.control@1.0`
- `safety.lower_contact.status@1.0`
- `provider.connection.status@1.0`

Availability, compatibility, selection, and negotiation remained separate from trust, authorization,
safety approval, execution authority, runtime availability, and physical feasibility.

## Samsung and real-LOOI physical record

- Device: authorized Samsung SM-S928B; ADB reported state `device` before the final run and after the
  one documented USB interruption. The private device serial is omitted from this acceptance export.
- Operator attestation: Redi, 2026-08-31. The operator explicitly confirmed each staged physical
  action and kept LOOI on a flat supported surface except for the controlled no-motor sensor changes.
- Setup: official LOOI app closed, Bluetooth enabled, real LOOI explicitly advertising, phone close
  to and mounted for the accepted front-camera/body orientation.
- Lower contact: controlled hazard was observed, then the all-supported return was known, fresh,
  SAFE, and owned by the current generation before any permitted movement.
- Center: `CENTERED`, zero move invocations, zero pulses.
- Release: one bounded pulse followed by confirmed semantic stop.
- Person loss: removing the person while holding produced a terminal stop.
- Hard budget: exactly three pulses and 300 ms cumulative energized time; no later pulse occurred.
- Unsafe gate: a hold during lower-contact hazard produced no movement; the later SAFE event was
  required before rearming.
- E-stop continuation: `latched-authority-cleared` through the same local E-stop entrypoint.
- Background: `confirmed-stop-authority-cleared`; the observed terminal was `USER_RELEASED`, which is
  in the accepted background-safe terminal set and occurred before cleanup completed.
- Disconnect: `safe-readiness-cleared`.
- Final continuation instrumentation: `OK (1 test)`, 669.682 seconds, zero failed/ignored.

The physical harness intentionally supports staged continuation. The primary run emitted accepted
center, release, person-loss, hard-budget, and unsafe-gate evidence before an operator/ADB timing
interruption. The final `remaining-stops-only` continuation declared
`prior-stages=evidence-preserved` and passed E-stop, background, and disconnect. Interrupted attempts
failed closed, issued no unconfirmed success, and did not justify any safety or protocol change.

## Redacted diagnostic export

```text
device=SM-S928B authorized=true
hugoIdentity=HUGO-5875C91F (safe display correlation only)
providerFamily=BODY providerPrepared=true connectionCurrent=true negotiationCurrent=true
capabilities=exact-six-semantic-set activeBody=explicit-only
lowerContact=known,current-generation,SAFE-before-motion
route=H007_H029_H030_H018_T032
center=no-motion release=confirmed-stop person-loss=terminal-stop
budget=pulses-3,energized-ms-300,later-pulses-false
unsafe-gate=no-move estop=latched-authority-cleared
background=confirmed-stop-authority-cleared disconnect=safe-readiness-cleared
privateTransport=omitted rawMedia=none retainedTranscript=none authorityImplied=false
```

This export intentionally contains no endpoint, address, raw provider identity, request/session ID,
GATT/FED/handshake data, frame, audio, transcript, credential, or secret.

## Automated, Android, and static checks

- Fresh `testDebugUnitTest --rerun-tasks`: 645 tests across 94 suites; zero failures, errors, or
  skips. The set includes 132 First Life tests and the H003-H035 architecture/privacy/baseline guards.
- Full Android instrumentation after the disclosed correction: `OK (43 tests)`. Hardware-gated
  classes remained opt-in; the final real-LOOI continuation separately passed `OK (1 test)`.
- Target manual-control instrumentation: both disabled-semantics and press/release cases passed.
- `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`: PASS.
- First Life documentation/order guard, HUGO V2 documentation guard, T032 baseline evidence guard,
  and architecture dependency guards: PASS.
- Production secret assignments: zero. Tracked raw audio/video: zero. Tracked capture artifacts:
  zero. App voice/vision/UI network API imports: zero. The merged manifest has no INTERNET
  permission and `allowBackup=false`.
- Core/UI/voice/vision forbidden BLE/GATT/FED/encoder imports: zero. App-owned normal diagnostics
  exposed no private provider or transport data.
- `git diff --check`: PASS.

## Disclosed acceptance correction and deviations

Full Android acceptance exposed one inherited T032 accessibility mismatch: the custom disabled
manual-drive surface blocked pointer input correctly but did not publish Compose's `disabled`
semantics. The user explicitly authorized the narrow originating-boundary correction. Commit
`92687ba668d514246790960f814fd94fe862874e` adds only conditional disabled semantics; it changes no
pointer handling, command routing, safety controller, FED9 mapping, freshness, dead-man, E-stop,
limits, leases, encoder, or execution behavior. The previously failing isolated test, its full class,
all 43 Android tests, JVM tests, lint, builds, and guards then passed.

Non-accepting physical attempts were retained as fail-closed evidence: one USB/ADB interruption and
operator timing/positioning timeouts. A safety rejection was never attributed to ADB. No threshold,
mapping, connection, handshake, discovery, telemetry, authorization, or safety behavior was weakened
to obtain the final pass.

## Freeze boundary and remaining risks

First Life is accepted only for one explicitly operated Samsung/LOOI reference setup and the bounded
documented interaction. Event-driven lower-contact evidence still requires an actual transition
after a new physical setup when no current known state exists. Albanian recognition may require the
explicitly disclosed Android system fallback on this device; that consent is per session and is not
an offline or cloud-architecture claim. USB/ADB stability affects evidence collection but never
changes the safety decision.

The slice is frozen at FL015. Any production onboarding, generalized voice/vision, additional body,
autonomy, memory, AI, or canonical runtime work requires its own backlog task and acceptance. H036 is
the next eligible canonical task, but it was not started by FL015.
