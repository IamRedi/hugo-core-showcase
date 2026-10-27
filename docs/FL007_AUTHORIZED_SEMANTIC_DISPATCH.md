# FL007 - Authorized semantic dispatch into the T032 path

## Status

`DONE` as of 2026-08-27. Correlated H007/H030 requests now reach the existing H018 facade and the
single service-owned T032 runtime boundary. Samsung and real-LOOI acceptance passed. FL008 is the
next eligible First Life task; H036 remains unstarted.

## Dispatch architecture

- `FirstLifeAuthorizedCapabilityDispatcher` accepts one exact H007 request, the current H029
  negotiation report, and matching H030 authorization evidence. It performs no provider selection,
  trust, permission, connection, registration, or adapter installation.
- Normal capability invocation requires an exact current `AUTHORIZED` decision. Missing, denied,
  expired, mismatched, stale-generation, stale-session, replayed, or contradictory evidence invokes
  no provider method.
- `mobility.stop@1.0` remains harm-reducing and always available through the same verified stop
  boundary; it does not manufacture authorization for any other capability.
- The dispatcher calls the already validated `LooiCompatibilityFacade` and checks the returned H007
  request/provider/generation/capability correlation before exposing the result.
- Cancellation maps only to the verified neutral stop. It cannot create execution authority.

## T032 and service boundary

- `LooiT032VerifiedRuntimeDelegate` implements the existing H018 runtime port with project-owned
  semantic values. It delegates status, head, light, movement, stop, and cancel to
  `ServiceOwnedLooiT032SemanticPort`.
- The service-owned port wraps the current `T032ManualAcceptanceSession`. There is no second BLE,
  GATT, handshake, telemetry, encoder, queue, dead-man, E-stop, or movement implementation.
- Existing T032 stop priority, generation, internal sequence, lower-contact gate, freshness policy,
  dead-man lease, local E-stop, limits, disconnect cleanup, and serialized GATT execution remain the
  execution authority. `AUTHORIZED` is necessary for normal dispatch but is not safety approval.
- The original UI-owned `ManualDriveController` remains usable. The FL007 hardware regression drove
  it through the same binder/session and verified its background cancellation behavior.
- `T032AcceptanceState.deadManArmed` is read-only diagnostic state used to prove that an accepted
  command actually armed the unchanged lease. It changes no timing or safety decision.

## Capability contracts

The exact six H017 identities are unchanged. Their H006 descriptors now carry the versioned semantic
input/output contracts already consumed by the H018 facade:

- `mobility.move@1.0` -> `hugo.contract.mobility_move_input@1.0`
- `body.head.position.control@1.0` -> `hugo.contract.body_head_position_input@1.0`
- `body.light.control@1.0` -> `hugo.contract.body_light_input@1.0`
- `safety.lower_contact.status@1.0` -> `hugo.contract.lower_contact_status_output@1.0`
- `provider.connection.status@1.0` -> `hugo.contract.provider_connection_status_output@1.0`

`mobility.stop@1.0` needs no input contract. No raw direction byte, encoder, UUID, packet, GATT,
FED, MAC address, or Android Bluetooth type enters HUGO Core or interaction/UI.

## Automated evidence

- Dispatcher adversarial tests cover valid invocation, missing/denied/mismatched authorization,
  stale generation/session/deadline, replay, cancellation, stop priority, and result correlation.
- Runtime delegate and service-owned semantic-port tests cover all semantic mappings, typed failures,
  status output, cancellation, and exact T032 command delegation.
- T032 tests prove typed entrypoints preserve the original manual entrypoint behavior and expose the
  unchanged dead-man lease state.
- Full JVM, First Life order/documentation, HUGO V2 documentation, T032 baseline, architecture
  dependency, Android compilation, lint, and APK checks pass as recorded in the completion commit.

## Samsung and real-LOOI acceptance

- Device: authorized Samsung `SM-S928B`, ADB state `device` (serial omitted from committed evidence).
  Current debug and androidTest APKs installed successfully; Bluetooth was ON before each bounded
  scan.
- The production composition discovered the advertising real LOOI, established the generic H028
  channel, completed the verified handshake and FED9 subscription, retained the known provider
  binding, BODY manifest/adapter result, and exact negotiated H017 capability set.
- With no motor command active, a controlled T031 transition produced a fresh current-generation
  hazard observation, followed by a fresh all-supported SAFE observation. The unchanged
  `DefaultSafetyController` accepted the safe state before any command.
- Authorized semantic status completed; prioritized semantic stop was accepted. Head-center and
  minimum light-on reached the existing safety/runtime path.
- One bounded left pulse was accepted for no more than 100 ms and release/stop passed. The unchanged
  dead-man latched on missing refresh, local E-stop latched, a fresh in-motion lower-contact hazard
  stopped motion without disconnecting, and explicit disconnect cleared readiness.
- A separate minimum manual-path pulse was stopped with `ManualStopReason.BACKGROUND`; the dead-man
  lease cleared and no erroneous E-stop remained. This uses the same controller instantiated by the
  activity lifecycle.
- Bluetooth-off while connected cleared protocol readiness and negotiated capabilities fail-closed;
  the test restored Bluetooth ON afterward.
- Normal FL007 evidence contains only semantic stage/result labels and current-generation claims. It
  contains no MAC, raw provider ID, UUID, GATT/FED/handshake payload, private transport address,
  credential, token, or persisted authority.

## Authority boundary

The hardware-only authorization fixture creates genuine transient H030 evidence for the exact test
request and never persists it. Provider readiness, manifest validity, adapter compatibility,
negotiated capabilities, connection, and successful status reads still do not imply trust,
authorization, active-body selection, safety approval, or general execution authority.
