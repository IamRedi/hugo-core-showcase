# FL013 - Bounded person-orientation controller

## Status

`DONE` as of 2026-08-30. The controller, effect binding, HiHugo controls, architecture guard, JVM
tests, Android tests, and the bounded Samsung + real-LOOI physical acceptance passed. FL014 is now
eligible but remains unstarted. H036 remains unstarted.

## Controller and hard envelope

`firstlife/orientation` is Android- and provider-neutral. Its pure reducer accepts only current
FL012 `PersonDirectionEvidence` plus explicit boolean prerequisites. It emits semantic `LEFT`,
`RIGHT`, or mandatory stop effects; it cannot express forward, backward, head, light, BLE, GATT,
FED, packet, encoder, or raw motor operations.

The reducer enforces the frozen FL001 envelope exactly:

- signed magnitude 16;
- at most two 50 ms move refreshes and 100 ms energized time per pulse;
- a semantic stop after every pulse, with confirmed stop required before progress;
- at least 250 ms cooldown after confirmed stop;
- at most three pulses and 300 ms cumulative energized time;
- at most two seconds wall clock per explicit hold;
- no overlapping effects; and
- `CENTER` completes with no movement while `UNKNOWN` never searches.

Release, pointer cancellation, background, E-stop, body/selection/mount loss, connection or H029
staleness, H014 authority loss, camera closure, person loss/staleness/ambiguity, unarmed floor mode,
and unknown/stale/unsafe lower-contact evidence converge on terminal semantic stop. An unconfirmed
stop blocks all later pulses.

## Authority and production route

The user must first explicitly select the BODY, confirm the secured phone/body alignment, establish
fresh T032 floor-safety evidence, keep the visible camera open, and press-and-hold the orientation
control. Provider readiness or active-body selection alone never starts or authorizes movement.

Each accepted hold creates only an in-memory, session-scoped H014 grant bounded to two seconds, the
exact provider instance, `mobility.move@1.0`, local mobile host, physical presence, mounted host, and
active BODY. Every move refresh receives a fresh request-bound approval and is evaluated by H030.
Release/end clears the grant and approval-consumption state; nothing is persisted or restored.

The only physical route is:

`FL012 evidence -> FL013 reducer -> H007 request -> H029 current negotiation -> H030 authorization
-> H018 LOOI facade -> existing T032 safety/runtime path`.

The service keeps only a bounded redacted run-local summary: move/confirmed-stop counts, typed
outcome, route enum, and authorization/invocation/stop booleans. It retains or logs no provider ID,
connection/session ID, camera box, confidence trace, BLE address, protocol detail, or request ID.
Authorization explicitly grants no safety authority; T032 lower-contact, dead-man, E-stop, motion
lease, limits, serialized queue, encoder, and disconnect cleanup remain decisive.

## HiHugo lifecycle

HiHugo exposes a visible preparation action, honest mounting/fresh-SAFE/camera status, a true
press-and-hold surface, Cancel, and local E-stop. Pointer-up and pointer cancellation both terminate
the hold in a `finally` path. Activity background terminates orientation before releasing camera and
voice resources. Provider loss, active-body expiry/deactivation, explicit disconnect, E-stop,
service shutdown, and transport loss clear the ephemeral authority.

The visual panel states the exact envelope and repeats that BODY readiness implies no trust,
authorization, safety approval, or execution authority.

## Automated evidence

- Reducer tests cover centered no-motion, exact left/right refreshes, all prerequisite losses,
  alternating/noisy/stale/ambiguous evidence, release/cancel/background/E-stop, stop failure,
  stale effect correlation, cooldown, three-pulse/300 ms budget, and two-second timeout.
- Virtual-time coordinator tests prove six move refreshes, three confirmed stops, exact cooldowns,
  one terminal authority cleanup, and no later pulse after an unconfirmed stop.
- Effect-binding tests prove explicit hold-only authority, fresh H030 approval per request, exact
  left/right input, harm-reducing unauthorised stop, expiry/stale correlation behavior, redacted
  trace, and the H007-H029-H030-H018-T032 route.
- The architecture guard now treats `firstlife/orientation` as a pure boundary and rejects Android,
  platform, LOOI, Bluetooth, GATT, and FED references.
- HiHugo Android tests cover preparation, hold down/up, Cancel, E-stop, envelope, and the authority
  boundary. The first connected-device run was infrastructure-blocked by the Samsung secure lock
  screen; the same existing FL012 camera UI test failed identically. After unlocking the Samsung,
  the unmodified FL013 UI suite passed (`OK (2 tests)`).

## Physical acceptance evidence

The first staging attempt exposed a concrete mounting mismatch before FL013 movement: the previously
selected rear camera points downward when the Samsung is mounted on LOOI. Per the operator's explicit
2026-08-29 correction, First Life now binds only the front camera. A no-motion Samsung sweep across
both sides then distinguished the mirrored preview from the analysis coordinates: the analysis axis
already matches physical BODY left and right, so no additional horizontal flip is applied. CameraX's
mirrored user preview remains distinct from this calibrated BODY evidence; detector thresholds,
freshness, ambiguity, controller direction, authorization, and T032 safety semantics are unchanged.

On 2026-08-30 the authorized Samsung SM-S928B remained in ADB state `device` while mounted/aligned
with its front camera facing the person on the real LOOI. The test area was flat and supported, the
local E-stop was staffed, and every movement stage first required fresh, current-generation,
all-supported lower-contact evidence accepted by the unchanged T032 safety path. The physical test
was deliberately staged across reconnects so no previously passed movement was repeated merely to
collect the remaining stop evidence. Every continuation established a fresh connection generation,
performed the existing handshake and lower-contact subscription, generated a controlled no-motor
hazard-to-safe transition, and required the new SAFE observation before movement.

The preserved physical evidence proves:

1. BODY left/center/right calibration matched the front-camera analysis coordinates; the mirrored
   preview did not invert controller direction.
2. Centered evidence produced zero move requests.
3. A direction hold produced one magnitude-16 pulse bounded to 100 ms, followed by a correlated
   confirmed stop on release.
4. Person loss occurred after a real accepted pulse and produced a terminal confirmed stop with no
   continuing authority.
5. A continuous hold stopped at exactly three pulses and 300 ms cumulative energized time, with no
   later pulse.
6. A fresh current-generation lower-contact hazard blocked movement completely; returning LOOI to
   the supported surface produced a fresh SAFE observation before the test continued.
7. The visible local E-stop path was exercised after a real active pulse. A staged continuation also
   invoked the same project-owned binder entry point used by that button and proved the existing
   T032 latch, terminal `EMERGENCY_STOP`, and cleared orientation authority.
8. Sending the activity to HOME during an active pulse produced terminal `USER_RELEASED` because
   Android cancelled the held pointer before lifecycle teardown; the harness confirmed HOME was
   accepted, the camera closed, stop was confirmed, and authority was cleared. This is the exact
   observed fail-closed lifecycle/pointer race, not a claim that the terminal was `BACKGROUNDED`.
9. Disconnect cleared protocol readiness, negotiated capabilities, and all orientation authority.
10. Each accepted move used `H007_H029_H030_H018_T032`. Evidence and diagnostics contained only
    typed stage/results; no MAC, provider identifier, camera frame/box, GATT/FED detail, transport
    address, or request/session identifier was retained.

The final background/disconnect continuation completed in 75.544 seconds with `OK (1 test)` and
instrumentation code `-1` (AndroidJUnitRunner success). Earlier stages and the final continuation
together form the complete signed FL013 physical envelope. No head/light action, forward/backward
movement, additional motion, protocol change, T031 mapping change, or T032/H014/H029/H030 safety or
authorization change was made.
