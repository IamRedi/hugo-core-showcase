# HUGO First Life Vertical Slice

## Record and relationship to the canonical backlog

- Document status: `FROZEN / ACCEPTED` (FL001-FL015 done on 2026-08-31)
- Plan date: 2026-08-26
- Accepted base: H035 commit `4760d24aa30ba31b46519036e05728217f52293f`
- M3 status: accepted and frozen for this slice
- Slice tasks: `FL001` through `FL015` `DONE`
- Canonical backlog after the slice: H036-H040 completed separately; H041 is next eligible

First Life is a deliberately narrow integration milestone. It does not replace, renumber, complete,
or weaken H036-H080. Later canonical work is tracked in `HUGO_TASKS.md`; no FL task implies
completion of a future H task.

## Product statement

HUGO is the persistent entity. HiHugo is the current face, product presentation, and future wake
identity. LOOI is Reference Body #1, not HUGO itself. A later body change must not change HUGO's
identity, face, voice-session semantics, or relationship to the user.

The First Life proof is:

```text
same HUGO identity
+ provider-neutral runtime
+ LOOI as Reference Body #1
+ explicit voice
+ visible camera observation
+ bounded person orientation
+ HiHugo presentation
```

HUGO Core must still know only semantic provider and capability contracts. LOOI-specific discovery,
transport, handshake, protocol, and verified-runtime delegation remain adapter/platform concerns.

## Target experience

1. The Android app starts and restores or creates the same project-owned H003 HUGO identity.
2. The HiHugo shell renders a face whose eyes expose an honest state: idle, listening, processing,
   speaking, observing, or body connected. Animation never claims a state before runtime evidence.
3. The user explicitly starts body discovery and chooses the observed LOOI candidate.
4. Adapter-side evidence enters H021; H022 identifies the provider; H023 resolves a built-in,
   validated LOOI definition; H024/H025 establish compatible adapter knowledge; H027 prepares it.
5. Explicit connect uses H013/H028 over the existing Android GATT transport. The existing LOOI
   handshake/readiness path remains adapter-owned.
6. H018 exposes only the H017 semantic descriptors; H029 negotiates the evidence-backed subset.
7. The UI says conceptually `LOOI body available` and shows negotiated semantic capability names,
   not BLE services, UUIDs, packets, addresses, or FED identifiers.
8. The user explicitly selects LOOI through H016 as the active body. Selection neither grants nor
   executes anything.
9. The user taps the first-life interaction. HUGO speaks through TTS:
   `Përshëndetje. Unë jam HUGO. Kush je ti?`
10. Only after speech finishes, an explicit microphone session opens and the face becomes listening.
11. STT returns a bounded transcript. A deterministic local parser extracts a display name for the
    current session only. No transcript or name is made long-term memory.
12. HUGO speaks, for example: `Gëzohem që të njoh, Redi.` The face becomes idle afterward.
13. The user explicitly starts a visible front-camera session for the phone-on-LOOI mount. Frames
    are processed ephemerally by an
    on-device person detector; no face identity recognition occurs.
14. A fresh detection is represented as uncertain evidence with confidence, timestamp, and coarse
    horizontal direction (`LEFT`, `CENTER`, `RIGHT`, or `UNKNOWN`).
15. With LOOI active, negotiated, separately authorized, and the user holding the orientation
    control, HUGO may issue a tiny bounded left/right semantic motion request. H030 must authorize
    every request before H018 delegates it to the existing T032 path.
16. Each pulse ends in semantic stop. Loss/staleness of detection, release, cancellation, timeout,
    provider loss, session loss, app background, or E-stop immediately requests stop and ends the
    orientation behavior.
17. When fresh evidence remains inside the center band, movement stops and HUGO may speak the
    deterministic greeting. The camera and microphone sessions close visibly.

Manual/tap activation is the accepted trigger. A production `Hi Hugo` wake word is explicitly not a
First Life prerequisite.

## Architecture

```text
HiHugo Compose shell
  -> FirstLifeInteractionReducer (script and session-only name)
  -> explicit VoiceSession / CameraSession ports
  -> FirstLifeRuntimeCoordinator
       -> H021/H022/H023/H024/H025/H027 preparation
       -> H013 ConnectionProvider (H028 BLE wrapper)
       -> H018 LOOI CapabilityProvider evidence
       -> H029 negotiation
       -> H016 explicit active-body selection
       -> H030 authorization per semantic request
       -> FirstLifeCapabilityDispatcher
            -> H018 LooiCompatibilityFacade
            -> LooiVerifiedRuntimePort production delegate
            -> existing T032 manual/safety/runtime boundary
```

The dispatcher accepts only an `AUTHORIZED` H030 decision correlated to the exact H007 request,
provider instance, generation, negotiation, and connection session. It has no overload that accepts
a UI direction, detector result, BLE endpoint, or raw transport command. Authorization still is not
safety: the LOOI delegate must pass through the existing `DefaultSafetyController`, dead-man,
E-stop, motion authority lease, lower-contact gates, motion limits, queue, encoder, and connection
lifecycle.

### Presentation state

The face reducer owns visual truth only:

| Face state | Required evidence |
|---|---|
| `BOOTING` | Identity/composition bootstrap has not completed |
| `IDLE` | App ready with no active session |
| `LISTENING` | Explicit microphone session actively capturing |
| `THINKING` | Bounded transcript/detection processing in progress |
| `SPEAKING` | TTS utterance started and not terminal |
| `OBSERVING` | Visible camera session active |
| `BODY_READY` | Provider connection plus accepted current capability evidence |
| `BLOCKED` | Typed provider/permission/session/safety prerequisite prevents progress |
| `ERROR` | Unexpected terminal implementation failure ended the requested step |

`BODY_READY` does not mean active body, authorized, safe, or executing. If concurrent facts exist,
the priority is `ERROR > BLOCKED > SPEAKING > LISTENING > THINKING > OBSERVING > BODY_READY > IDLE`;
`BOOTING` applies only before bootstrap completes. The status panel retains all underlying facts.
Animation completion never drives runtime state.

### First-life interaction state

The pure reducer is finite and cancellable:

```text
READY
 -> INTRO_SPEAKING
 -> WAITING_FOR_EXPLICIT_LISTEN
 -> LISTENING
 -> TRANSCRIBING
 -> GREETING_SPEAKING
 -> READY

READY
 -> CAMERA_OPENING
 -> OBSERVING
 -> ORIENTATION_ARMED
 -> ORIENTING_PULSE <-> OBSERVING
 -> PERSON_CENTERED
 -> GREETING_SPEAKING
 -> READY
```

Permission denial, unavailable local speech/detection support, timeouts, lifecycle loss, cancellation,
or malformed callbacks lead to typed blocked/terminal states. No retry loop may silently reopen a
sensor or restart movement.

## Reused HUGO foundation

| Accepted work | First Life use |
|---|---|
| H003 | Stable HUGO identity contract; FL adds only installation-local bootstrap/persistence |
| H004-H009 | Provider identity/family, semantic provider port, request/result correlation, lifecycle generation |
| H010-H012 | Built-in validated LOOI common/body manifest and adapter descriptor/integrity metadata |
| H013 | Generic connection/session/failure contract |
| H014 | Trust, permission, scope, approval, expiry, and revocation semantics |
| H015 | Provider registration/capability snapshot after successful negotiation |
| H016 | Explicit expiring active-body selection without identity or authority coupling |
| H017 | Exact six-item evidence-backed LOOI semantic mapping |
| H018 | Adapter-side semantic facade and sole verified-runtime delegation port |
| H019-H020 | Dependency rules and unchanged M2.5/LOOI regression boundary |
| H021-H027 | Observation through deterministic provider preparation |
| H028 | Generic BLE wrapper reusing the existing GATT implementation |
| H029 | Capability intersection and current evidence report |
| H030 | Request-time authorization and atomic approval consumption contract |
| H031 | Calibration readiness where a selected capability declares it; never safety authority |
| H032 | Protected LOOI provider-instance continuity without runtime/grant resurrection |
| H033 | Honest blocked-state presentation inputs |
| H034 | Redacted provider/capability diagnostics for the status panel and acceptance evidence |
| H035 | M3 acceptance boundary and frozen regression evidence |

## Minimal new modules

The slice should add no new universal abstraction unless an accepted contract cannot express the
required composition. Proposed slice-local boundaries are:

| Module/path | Responsibility |
|---|---|
| `firstlife/identity` | Create once and restore H003 identity from protected app-local storage; no personal memory |
| `feature/hihugo` | Compose shell, animated face, explicit controls, capability/status presentation |
| `firstlife/interaction` | Pure scripted reducer, cancellable effects, bounded session-only name |
| `firstlife/runtime` | Compose accepted HUGO stages, ephemeral session authority, registration, active-body selection, diagnostics |
| `body/looi/firstlife` | Built-in LOOI manifest/adapter records and production H018-to-T032 delegate |
| `firstlife/voice` | Explicit session contract plus Android STT/TTS adapters behind ports |
| `firstlife/vision` | Visible camera session, ephemeral frames, on-device person-evidence adapter |
| `firstlife/orientation` | Deterministic bounded coarse-direction controller producing semantic requests only |

These names deliberately avoid claiming completion of canonical H036-H080 packages. Later tasks may
promote proven contracts and remove slice-local code after their own acceptance.

## LOOI integration and safety boundary

The slice must not create a second BLE stack or a second motion executor. Adapter-side composition
may translate existing verified connection/handshake readiness into H008/H013 evidence, but it must
not copy GATT callbacks, packet encoders, or safety rules into First Life.

Physical orientation is permitted only when all of these remain true:

- real provider instance and generation correlate through H022-H032;
- manifest and adapter compatibility are current;
- required H013 session and H029 capability record are fresh;
- LOOI is explicitly selected through H016;
- an explicit, expiring, session-scoped H014 grant exists;
- H030 returns `AUTHORIZED` for the exact request;
- the front-camera-to-body mounting/alignment and physical left/right calibration checklist has been
  confirmed for the run;
- the user continuously holds the orientation enable control;
- fresh person evidence remains above the reviewed confidence threshold; and
- the existing T032 safety path accepts the movement.

The experiment uses rotation only: no forward/backward travel, navigation, following, path planning,
or obstacle exploration. It uses the physically accepted minimum signed drive magnitude `16` and a
hard 100 ms maximum pulse (at most two existing 50 ms refreshes). The behavior is capped at three
pulses, 300 ms cumulative energized command time, and two seconds per explicit attempt, with a
semantic stop and correlated terminal stop after every pulse. Any loss of a prerequisite requests
stop immediately. Failure to confirm stop blocks further pulses and requires manual recovery. If
16/100 ms is not useful on staged hardware, the task blocks rather than increasing the envelope.

The local E-stop remains visible and available throughout. App background, activity/service loss,
BLE loss, stale generation/session, detector loss, camera closure, user release, cancellation, and
timeout must converge on the existing stop/cleanup behavior. The behavior never interprets a person
detection as proof of identity, consent, safety, floor conditions, distance, or collision clearance.

## Voice and vision privacy

- Microphone capture starts only from a visible explicit session and after runtime permission.
- Camera capture starts only from a visible explicit session and after runtime permission.
- Android privacy indicators and an in-app active-session indicator must remain visible.
- Audio buffers, camera frames, crops, embeddings, and detector tensors are ephemeral and bounded.
- Raw audio/video is not logged, diagnosed, persisted, backed up, or exported.
- The transcript and parsed name exist only in the reducer's current session and are cleared on end,
  cancellation, process death, or explicit reset.
- No face recognition, face template, identity database, biometric inference, or person history exists.
- The first STT tier and person detector are on-device. If Albanian on-device STT is unavailable,
  the user may explicitly choose a disclosed Android system recognizer that may use the network, or
  use text entry. There is no silent cloud upload, third-party cloud SDK, credential, or permanent
  cloud architecture decision; only the on-device tier counts as offline acceptance.
- TTS output is local playback. Speech-engine language/data availability is checked explicitly and
  missing Albanian support is a typed setup blocker, not silently replaced with an unrelated voice.

## Deterministic and scripted boundary

First Life is intentionally deterministic:

- face states come from reducers and accepted runtime events;
- introduction and greeting text are fixed Albanian resources;
- name extraction accepts a small documented utterance grammar and retains only one bounded display
  value for the session;
- the user chooses discovery, connect, active body, voice, camera, and orientation actions;
- authority is explicit and session-scoped;
- person detection produces a coarse uncertain observation, not recognition;
- orientation is a finite left/right/stop controller with hard pulse/time limits; and
- unknown input, missing support, stale evidence, or ambiguity stops the flow honestly.

Later canonical work may add wake-word activation, richer local intent routing, generalized voice
sessions, model gateways, bounded AI context, personal memory, perception tracking, and reviewed
autonomy under H046-H079. No such capability is implied by this slice.

## Acceptance boundary

The final FL acceptance must physically demonstrate on the target Samsung and real LOOI:

- the app launches into the persistent HiHugo face;
- LOOI becomes available only through the universal provider flow;
- current negotiated semantic capabilities are visible in redacted diagnostics;
- explicit active-body selection is visible and expires/clears correctly;
- explicit STT and local TTS complete the scripted introduction/name greeting;
- a visible front-camera session produces fresh uncertain person/direction evidence;
- bounded orientation traverses H007 -> H029 -> H030 -> H018 -> T032;
- centered/lost/cancelled cases stop safely;
- manual E-stop, disconnect, app background, and stale-session cases remain safe; and
- no Core/UI/voice/vision source imports BLE, GATT, FED, or motor-encoder APIs.

Passing this proof means only that the First Life slice works inside its documented envelope. It is
not a claim of general intelligence, general voice understanding, face recognition, durable personal
memory, autonomous navigation, person following, provider ranking, or multi-body orchestration.

The frozen FL001 choices and gates are in
[`FL001_SLICE_CONTRACT_FEASIBILITY.md`](FL001_SLICE_CONTRACT_FEASIBILITY.md). The executable backlog
is defined separately in [`HUGO_FIRST_LIFE_TASKS.md`](HUGO_FIRST_LIFE_TASKS.md).

## Final acceptance and freeze

FL015 passed on 2026-08-31. The automated, Android, privacy, architecture, and staged Samsung plus
real-LOOI evidence is frozen in
[`HUGO_FIRST_LIFE_ACCEPTANCE_2026-08-31.md`](HUGO_FIRST_LIFE_ACCEPTANCE_2026-08-31.md).

This freeze preserves all FL001-FL014 constraints and the H001-H035/T001-T032 boundaries. It does
not start or complete H036, grant a production claim, or authorize work outside the First Life
envelope.
