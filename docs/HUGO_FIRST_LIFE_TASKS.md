# HUGO First Life Tasks

## Backlog rules

- Baseline: H035 commit `4760d24aa30ba31b46519036e05728217f52293f`.
- Scope: the separate First Life integration slice defined in
  [`HUGO_FIRST_LIFE.md`](HUGO_FIRST_LIFE.md).
- IDs: `FL001` through `FL015`; these do not alter canonical HUGO task numbering.
- Current status: FL001-FL015 are `DONE`; the First Life slice is accepted and frozen.
- H036-H080 remain canonical and are tracked separately in `HUGO_TASKS.md`; H036-H040 were completed
  after the First Life freeze and H041 is the next eligible canonical task.
- Complete one FL task at a time unless a later assignment explicitly authorizes parallel scope.
- A task may narrow the demo after evidence, but may not weaken H001-H035 contracts, T032 safety,
  privacy, authorization, or fail-closed behavior.
- Any change to a production LOOI connection/control path requires the task's smallest safe staged
  Samsung + real-LOOI regression before `DONE`.

## Ordered index

| ID | Task | Depends on | Android device | Real LOOI |
|---|---|---|---|---|
| FL001 | Slice contract and feasibility decisions | H035 | No | No |
| FL002 | Persistent HUGO identity bootstrap | FL001 | Instrumented; Samsung restart check | No |
| FL003 | Minimal HiHugo shell and face reducer | FL001 | Samsung visual/lifecycle check | No |
| FL004 | Scripted first-life interaction reducer | FL001 | No | No |
| FL005 | Universal runtime composition harness | FL001 | Instrumented optional | No; fakes only |
| FL006 | LOOI Reference Body #1 provider wiring | FL005 | Yes | Yes |
| FL007 | Authorized semantic dispatch into T032 | FL006 | Yes | Yes |
| FL008 | Active-body and capability presentation | FL002, FL003, FL005, FL006 | Yes | Yes |
| FL009 | Explicit voice capture and STT | FL003, FL004 | Yes | No |
| FL010 | Local TTS and Albanian script | FL003, FL004 | Yes | No |
| FL011 | Visible camera observation session | FL003, FL004 | Yes | No |
| FL012 | On-device person evidence | FL011 | Yes | No |
| FL013 | Bounded person-orientation controller | FL007, FL008, FL012 | Yes | Yes |
| FL014 | First-life end-to-end composition | FL002-FL013 | Yes | Yes |
| FL015 | FL Acceptance - "HUGO First Life" | FL014 | Yes | Yes |

## Dependency graph

```text
H035
  -> FL001
      -> FL002 -----------------------------+
      -> FL003 -> FL009 --------------------+
      |         -> FL010 -------------------+
      |         -> FL011 -> FL012 ------+   |
      -> FL004 --------------------------|---+
      -> FL005 -> FL006 -> FL007 ----+   |   |
                   |       |         |   |   |
                   +-> FL008 --------+---+   |
                                   FL013 ----+
                                      |      |
                                      +-> FL014 -> FL015
```

FL003, FL004, FL005, FL009/FL010, and FL011 may be developed independently only after FL001 records
the shared decisions. Completion remains individually gated.

## FL001 - Slice contract and feasibility decisions

- **Status:** `DONE` (2026-08-26)
- **Objective:** Freeze the smallest demonstrable experience, implementation choices, privacy
  policy, and physical orientation envelope before code changes.
- **Exact scope:** Record ADRs for H003 identity bootstrap, first-life package boundaries, Android
  offline STT, local TTS/Albanian availability, camera stack, on-device person detector, explicit
  session authority, LOOI mounting/alignment checklist, and turn-pulse limits. Verify dependency
  licenses, APK/model size, minSdk 26 support, and no mandatory cloud service.
- **Prerequisites:** H035 accepted; First Life plan reviewed.
- **Files/modules:** `docs/`, `docs/DECISIONS.md` or dedicated First Life ADRs; no production code.
- **Acceptance criteria:** Every technical choice has evidence, fallback/block behavior, resource
  budget, privacy classification, lifecycle owner, and replacement boundary. Unsupported local STT,
  Albanian TTS, or detector feasibility is an explicit blocker or approved scope revision.
- **Tests/checks:** Documentation consistency, dependency/license review, threat checklist, HUGO and
  T032 guards, `git diff --check`.
- **Non-goals:** UI, sensor capture, provider wiring, movement, cloud AI, wake word.
- **Hardware/device verification:** None required; read-only capability checks on the target Samsung
  may be recorded but make no functional claim.
- **Completion evidence:** [`FL001_SLICE_CONTRACT_FEASIBILITY.md`](FL001_SLICE_CONTRACT_FEASIBILITY.md)
  freezes the interaction/presentation vocabulary, composition and sole LOOI route, active-body
  semantics, voice/camera/detector choices, privacy/fallback rules, device gates, and exact bounded
  orientation envelope. No production code, dependency, permission, model, or runtime path changed.

## FL002 - Persistent HUGO identity bootstrap

- **Status:** `DONE` (2026-08-26)
- **Objective:** Make the app consistently represent one H003 HUGO entity across process restart
  without deriving identity from the phone, LOOI, an account, or personal memory.
- **Exact scope:** Add a create-once, strict-codec, protected app-local HUGO identity repository with
  atomic write, version handling, corruption/reset behavior, and dependency injection into the
  first-life composition root. Generate entropy locally; expose only a redacted correlation to UI.
- **Prerequisites:** FL001 identity-storage decision.
- **Files/modules:** `firstlife/identity`, Android protected-storage implementation under `platform/`,
  composition root tests.
- **Acceptance criteria:** First launch creates one valid identity; process/app restart restores the
  same value; corruption fails closed; explicit reset creates no body/provider coupling; no identity
  value appears in logs, diagnostics, or UI.
- **Tests/checks:** Repository/codec/atomicity tests, corruption and migration tests, process-restart
  instrumentation, private-ID leakage scan, architecture guard.
- **Non-goals:** Account, sync, user profile, personal memory, provider identity, backup export.
- **Hardware/device verification:** Android instrumentation required; one Samsung process/relaunch
  check before completion. No LOOI.
- **Completion evidence:** [`FL002_PERSISTENT_HUGO_IDENTITY_BOOTSTRAP.md`](FL002_PERSISTENT_HUGO_IDENTITY_BOOTSTRAP.md)
  records the unchanged H003 bootstrap API, authenticated atomic no-backup storage, typed create/
  restore/corrupt/version/reset behavior, automated tests, and passing Samsung continuity evidence.

## FL003 - Minimal HiHugo shell and face reducer

- **Status:** `DONE` (2026-08-26)
- **Objective:** Present HUGO as one stable visual entity with honest animated face states.
- **Exact scope:** Add a minimal Compose shell, project-owned face/eye artwork, pure face reducer,
  deterministic animation transitions, accessibility labels, body/provider status slot, and explicit
  buttons for connect, voice, camera, orientation hold, cancel, and E-stop access. Render only state
  supplied by reducers; keep the face independent from LOOI branding.
- **Prerequisites:** FL001 presentation/state decision.
- **Files/modules:** `feature/hihugo`, Compose resources, pure presentation model/tests.
- **Acceptance criteria:** Booting, idle, listening, thinking, speaking, observing, body-ready,
  blocked, and error states render distinctly; animation cannot create runtime transitions; rotation,
  background, and recomposition preserve/cancel state according to the reducer; no inaccessible raw
  provider or transport data is shown.
- **Tests/checks:** Reducer transition table, Compose semantics/screenshot tests, lifecycle tests,
  accessibility and architecture scans, lint.
- **Non-goals:** Provider composition, speech/camera implementation, dashboard, wake word, LOOI face.
- **Hardware/device verification:** Samsung visual, touch-target, lifecycle, and animation check.
  No LOOI required.
- **Completion evidence:** [`FL003_MINIMAL_HIHUGO_SHELL_FACE_REDUCER.md`](FL003_MINIMAL_HIHUGO_SHELL_FACE_REDUCER.md)
  records the pure reducer and bootstrap projection, state-only Compose face, bounded lifecycle
  animation, Samsung visual/lifecycle run, and passing JVM/instrumented/build checks. No LOOI or
  provider runtime was invoked.

## FL004 - Scripted first-life interaction reducer

- **Status:** `DONE` (2026-08-26)
- **Objective:** Coordinate the deterministic introduction, listening, name greeting, observation,
  and cancellation flow without an AI agent.
- **Exact scope:** Define a pure finite reducer, typed effects/events, monotonic deadlines, stale
  callback correlation, fixed Albanian resource keys, bounded local name grammar, session-only name
  value, and explicit reset/cleanup. Voice, camera, provider, and movement remain ports/effects.
- **Prerequisites:** FL001 interaction/privacy decision.
- **Files/modules:** `firstlife/interaction`, localized string resources, JVM fixtures.
- **Acceptance criteria:** Valid event sequences are deterministic; TTS finishes before microphone
  capture; ambiguous/empty transcript asks or stops honestly; session name clears on end/process
  recreation; cancellation from every active state emits required cleanup/stop effects exactly once.
- **Tests/checks:** Exhaustive reducer table, virtual-time timeout tests, stale/wrong-correlation tests,
  Albanian Unicode fixtures, property tests for no orphan sensor/movement effect.
- **Non-goals:** LLM, free-form dialog, intent learning, durable memory, wake word.
- **Hardware/device verification:** None; pure JVM acceptance.
- **Completion evidence:** [`FL004_SCRIPTED_FIRST_LIFE_INTERACTION_REDUCER.md`](FL004_SCRIPTED_FIRST_LIFE_INTERACTION_REDUCER.md)
  records the pure correlated reducer, bounded session-only name grammar, fixed Albanian dialogue,
  declarative voice/presentation/observation effects, cancellation matrix, and passing JVM/guard
  checks. No Android, provider, LOOI, sensor, AI, or persistence path was added.

## FL005 - Universal runtime composition harness

- **Status:** `DONE` (2026-08-26)
- **Objective:** Compose the accepted H021-H034 contracts into one application-layer lifecycle using
  fakes before touching the LOOI production path.
- **Exact scope:** Build a slice-local coordinator for observation, H027 preparation, H013 connection,
  H008 runtime evidence/registration, H029 negotiation, H014/H030 session authority, H015 registry,
  H016 selection, H031 readiness, H033 blocked state, and H034 diagnostics. Every asynchronous result
  carries run/generation/session correlation. Add an in-memory, session-scoped approval-consumption
  implementation; authority is created only by an explicit demo consent event.
- **Prerequisites:** FL001 architecture/authority decisions.
- **Files/modules:** `firstlife/runtime`, fake BODY/DEVICE/SERVICE fixtures, acceptance tests.
- **Acceptance criteria:** A fake BODY reaches available/negotiated/registered/selected only through
  explicit transitions; DEVICE/SERVICE still fit without inheritance; each stage can fail or cancel;
  authorization denial invokes no provider; session restart restores no authority or connection.
- **Tests/checks:** Deterministic end-to-end fake tests, fault injection at every boundary, concurrent
  stale callback/approval replay tests, registry mutation assertions, architecture guard.
- **Non-goals:** Real adapter, Android BLE, UI, physical execution, persistent grants, provider ranking.
- **Hardware/device verification:** None; fakes are required. Optional instrumentation only for
  composition lifecycle.
- **Completion evidence:** [`FL005_UNIVERSAL_RUNTIME_COMPOSITION_HARNESS.md`](FL005_UNIVERSAL_RUNTIME_COMPOSITION_HARNESS.md)
  records the provider-neutral observation-to-readiness trace, independent readiness facts,
  fail-closed correlation matrix, H033/H034 redacted blocker evidence, fake BODY/DEVICE/SERVICE
  scenarios, ephemeral approval consumption, and passing JVM/guard/build checks.

## FL006 - LOOI Reference Body #1 provider wiring

- **Status:** `DONE` (2026-08-27)
- **Objective:** Make the existing LOOI path appear as one real BODY provider through the universal
  runtime without adding a second BLE/GATT/protocol implementation.
- **Exact scope:** Add adapter-owned built-in common/body manifest, H012 descriptor/H025 record,
  H021/H022 mapping evidence, H028 composition over the existing GATT transport, existing
  handshake/readiness projection into H008, H018 provider registration, H029 negotiation, and H032
  instance continuity. Core sees only accepted contracts and the exact H017 semantic set.
- **Prerequisites:** FL005; FL001 LOOI composition ADR.
- **Files/modules:** `body/looi/firstlife`, platform composition root, manifest/adapter fixtures;
  narrowly reviewed existing service integration points only.
- **Acceptance criteria:** Explicit discovery/connect yields one validated LOOI BODY, one compatible
  known adapter, current connection evidence, and negotiated semantic capabilities. Wrong/stale
  identity, generation, session, digest, or handshake remains blocked. No scanner/GATT/protocol code
  is duplicated and no physical capability executes.
- **Tests/checks:** Fake bridge integration, manifest/adapter/negotiation fixtures, stale session and
  disconnect tests, Core/UI leakage scans, full LOOI transport regression suite.
- **Non-goals:** Dispatch, movement, new capability, automatic connection, active-body selection,
  generic marketplace adapter.
- **Hardware/device verification:** Samsung + real LOOI required because production composition is
  introduced. Verify explicit connect, handshake, negotiated six-or-smaller evidence set, disconnect,
  Bluetooth-off cleanup, and no movement.
- **Completion evidence:** The dated record in
  [`FL006_LOOI_REFERENCE_BODY_PROVIDER_WIRING.md`](FL006_LOOI_REFERENCE_BODY_PROVIDER_WIRING.md)
  covers the redacted provider trace, exact capability negotiation, fail-closed historical attempts,
  queue-race correction, fresh hazard/safe FED9 evidence, and passing minimum T032 regression on the
  authorized Samsung and real LOOI. Provider readiness still implies no authority or execution.

## FL007 - Authorized semantic dispatch into the T032 path

- **Status:** `DONE`
- **Objective:** Close the critical H007/H029/H030/H018-to-T032 execution gap without bypassing the
  verified safety/runtime boundary.
- **Exact scope:** Add a narrow dispatcher accepting only a correlated `AUTHORIZED` decision and the
  exact H007 request; implement `LooiVerifiedRuntimePort` by delegating to the existing
  `T032ManualAcceptanceSession`/service-owned safety and protocol path. Preserve stop priority,
  execution epochs/sequences, dead-man refresh, E-stop, leases, gates, limits, correlation, cancel,
  timeout, and disconnect cleanup. Add an always-available semantic stop path that still uses the
  verified stop boundary.
- **Prerequisites:** FL006; FL001 safety/authority ADR.
- **Files/modules:** `firstlife/runtime` dispatcher, `body/looi/firstlife` verified-runtime delegate,
  narrowly reviewed service binder/composition interfaces.
- **Acceptance criteria:** Missing/stale/denied authorization invokes nothing; supported authorized
  requests reach H018 then the existing T032 executor; result correlation is preserved; stop
  preempts; no raw direction/encoder/packet API reaches interaction/UI; the old manual path remains
  usable and equivalent.
- **Tests/checks:** Adversarial authorization-to-dispatch tests, fake T032 delegate equivalence,
  cancellation/stale-session/replay tests, dependency scans, full safety/dead-man/E-stop/LOOI suite.
- **Non-goals:** Provider selection, planner, autonomous movement, new encoder, increased limits,
  direct UI/voice execution.
- **Hardware/device verification:** Staged Samsung + real LOOI required. First verify status/head/light
  and stop, then one minimum-magnitude user-held turn pulse on a known safe surface; repeat manual
  control, release, E-stop, lower-contact, disconnect, Bluetooth-off, and background stop regression.
- **Completion evidence:**
  [`FL007_AUTHORIZED_SEMANTIC_DISPATCH.md`](FL007_AUTHORIZED_SEMANTIC_DISPATCH.md) records the
  request-to-safety boundary, adversarial tests, redacted correlation trace, Samsung/real-LOOI
  checklist, lower-contact/dead-man/E-stop regressions, old-manual/background equivalence, and
  Bluetooth-off fail-closed result.

## FL008 - Active-body and negotiated-capability presentation

- **Status:** `DONE` (2026-08-27)
- **Objective:** Let the user explicitly select available LOOI through H016 and inspect honest
  negotiated capability evidence.
- **Exact scope:** Bind redacted FL005/FL006 state to the HiHugo shell; show provider available,
  connection/negotiation freshness, exact semantic capability list, blocked reasons, and an explicit
  expiring active-body select/deactivate control. Use H034 projections; never display private IDs or
  transport details.
- **Prerequisites:** FL002, FL003, FL005, FL006.
- **Files/modules:** `feature/hihugo`, `firstlife/runtime` presentation projection.
- **Acceptance criteria:** Selection requires explicit user action and current BODY availability;
  disconnect, expiry, generation change, or deactivation clears it; selection grants no authority;
  capability names match H029 and diagnostics; blocked states reuse H033.
- **Tests/checks:** Projection/reducer/UI tests, expiry/disconnect cases, redaction scan, screenshot and
  accessibility tests.
- **Non-goals:** Automatic selection, provider ranking, dashboard, execution button per capability.
- **Hardware/device verification:** Samsung + real LOOI check for available/selected/disconnected UI
  and exact negotiated list. No movement.
- **Completion evidence:** [`FL008_ACTIVE_BODY_CAPABILITY_PRESENTATION.md`](FL008_ACTIVE_BODY_CAPABILITY_PRESENTATION.md)
  records the H016 selection boundary, redacted state/diagnostic projection, exact capability list,
  expiry/deactivation/disconnect behavior, accessibility/UI tests, and Samsung + real-LOOI screenshots.

## FL009 - Explicit voice capture and STT

- **Status:** `DONE` (2026-08-27)
- **Objective:** Capture one user response in a visible, cancellable, privacy-bounded voice session.
- **Exact scope:** Add slice-local voice-session port/reducer and Android speech-recognition adapter
  selected by FL001. Request microphone permission just in time; require explicit start; prefer/
  require the approved on-device mode; bound duration and transcript size; correlate callbacks; clear
  audio/transcript/name on terminal session; map denial/unavailable/no-match/timeout/cancel errors.
- **Prerequisites:** FL003, FL004, FL001 speech feasibility decision.
- **Files/modules:** `firstlife/voice`, `platform/voice/android`, manifest microphone permission,
  lifecycle composition.
- **Acceptance criteria:** Tap opens one visible listening session; Albanian name utterance produces a
  bounded transcript when supported; cancel/background/timeout release the microphone exactly once;
  no raw audio or transcript persists/logs; no automatic network/cloud fallback. A separately
  disclosed and consented Android system recognizer is allowed only as the FL001 fallback tier.
- **Tests/checks:** Reducer/fake-adapter tests, callback ordering, permission denial, timeout,
  lifecycle/resource release, privacy/backup/log scans, Android instrumentation.
- **Non-goals:** Wake word, continuous listening, conversation history, cloud STT, speaker identity.
- **Hardware/device verification:** Samsung microphone and approved recognizer required; test grant,
  deny, capture, cancel, background, offline/network-disabled policy, and process restart.
- **Completion evidence:** [`FL009_EXPLICIT_VOICE_CAPTURE_STT.md`](FL009_EXPLICIT_VOICE_CAPTURE_STT.md)
  records the pure correlated session, on-device-first Android adapter, separately consented system
  fallback, bounded ephemeral transcript, lifecycle/privacy checks, and Samsung acceptance evidence.

## FL010 - Local TTS and Albanian script

- **Status:** `DONE` (2026-08-27)
- **Objective:** Speak the fixed introduction and session-name greeting with correlated local TTS.
- **Exact scope:** Add a slice-local TTS port and Android adapter; check engine/language/data support;
  queue one bounded utterance; map start/done/error/cancel; coordinate audio focus and lifecycle; use
  localized resource templates and sanitized session display name only.
- **Prerequisites:** FL003, FL004, FL001 TTS feasibility decision.
- **Files/modules:** `firstlife/voice`, `platform/voice/android`, Albanian string resources.
- **Acceptance criteria:** Exact introduction and greeting are spoken on the target when Albanian is
  supported; face state follows real TTS callbacks; error/missing language blocks honestly; cancel/
  background stops speech; microphone never starts before the introduction terminal callback.
- **Tests/checks:** Fake engine ordering, Unicode/template tests, name sanitization, audio-focus and
  lifecycle instrumentation, missing-engine/language cases.
- **Non-goals:** Voice cloning, generated dialog, cloud synthesis, arbitrary text speaking.
- **Hardware/device verification:** Samsung speaker/TTS engine required, including Albanian language
  availability, cancel, interruption, and volume/audibility check. No LOOI.
- **Completion evidence:** [`FL010_LOCAL_TTS_ALBANIAN_SCRIPT.md`](FL010_LOCAL_TTS_ALBANIAN_SCRIPT.md)
  records the correlated Android-free output domain, exact local `sq-AL` voice gate, fixed localized
  script, sanitized session-name boundary, audio-focus/lifecycle behavior, and Samsung acceptance.

## FL011 - Visible camera observation session

- **Status:** `DONE` (2026-08-27)
- **Objective:** Open a user-visible, lifecycle-safe phone-camera session behind a slice-local port.
- **Exact scope:** Add explicit camera permission/session reducer, preview surface, bounded latest-frame
  analysis port, opaque frame metadata, orientation mapping, and exact close/release behavior. No frame
  crosses into HUGO Core or diagnostics.
- **Prerequisites:** FL003, FL004, FL001 camera-stack decision.
- **Files/modules:** `firstlife/vision`, `platform/vision/android`, `feature/hihugo` preview/indicator,
  camera dependencies approved in FL001.
- **Acceptance criteria:** Explicit tap and permission open one visible session; only the latest bounded
  frame is analyzed; close/cancel/background/rotation/process loss releases camera resources; no raw
  image is stored, logged, backed up, or exported.
- **Tests/checks:** Session reducer, fake frame source/backpressure tests, permission/lifecycle
  instrumentation, resource-release assertions, privacy/file/log scans.
- **Non-goals:** Hidden camera, recording, photo capture, cloud upload, person detection, face identity.
- **Hardware/device verification:** Samsung front/rear camera decision and preview/lifecycle/privacy
  indicator check. No LOOI.
- **Completion evidence:** [`FL011_VISIBLE_CAMERA_OBSERVATION_SESSION.md`](FL011_VISIBLE_CAMERA_OBSERVATION_SESSION.md)
  records the correlated Android-free session, original CameraX rear-lens acceptance, latest-frame
  backpressure, exact lifecycle release, and privacy constraints. The 2026-08-29 mounted-camera
  amendment selects the front lens and makes targeted front-lens revalidation a prerequisite to
  FL013 motion.

## FL012 - On-device person evidence

- **Status:** `DONE`
- **Objective:** Convert ephemeral frames into bounded, uncertain person-presence and coarse-direction
  evidence without identification.
- **Exact scope:** Integrate the FL001-approved on-device detector behind a port. Emit confidence,
  monotonic observation/expiry time, bounding-box-derived horizontal offset, and `LEFT/CENTER/RIGHT/
  UNKNOWN`; require reviewed threshold and consecutive fresh observations; drop stale/out-of-order
  results; expose no frame, crop, embedding, face, or identity.
- **Prerequisites:** FL011; FL001 detector/license/performance decision.
- **Files/modules:** `firstlife/vision` detector/evidence adapter and golden fixtures; platform model
  loading boundary.
- **Acceptance criteria:** Representative person/no-person/multiple-person fixtures yield deterministic
  bounded evidence; multiple persons remain ambiguous unless one deterministic non-identity rule is
  explicitly approved; low confidence/stale/lost input becomes unknown; device latency meets the
  reviewed budget without retaining frames.
- **Tests/checks:** Golden detector adapter tests, direction geometry/property tests, stale/order/
  ambiguity cases, model/license/size scan, on-device latency/resource benchmark.
- **Non-goals:** Face detection as identity, recognition, tracking history, age/gender/emotion,
  distance/safety inference, cloud vision.
- **Hardware/device verification:** Samsung camera required with consenting test person, empty scene,
  left/center/right positions, loss, multiple-person ambiguity, and lifecycle closure. No LOOI.
- **Completion evidence:** Redacted confidence/direction/latency table only; no images retained in Git.
- **Verification evidence:** [`FL012_ON_DEVICE_PERSON_EVIDENCE.md`](FL012_ON_DEVICE_PERSON_EVIDENCE.md)
  records the complete implementation, automated checks, Samsung empty/left/center/right/loss,
  performance and lifecycle checks, and the passing real two-person ambiguity scene. No image or
  identifying evidence was retained.

## FL013 - Bounded person-orientation controller

- **Status:** `DONE` (2026-08-30)
- **Objective:** Turn the selected LOOI a tiny amount toward fresh coarse person-direction evidence
  through the complete semantic/authorization/safety path.
- **Exact scope:** Add a pure finite controller that accepts current FL012 evidence and FL005-FL008
  provider/selection/authority state. Under continuous user hold, produce at most left/right
  `mobility.move@1.0` proposals and mandatory `mobility.stop@1.0` requests. Enforce the FL001 envelope:
  exact magnitude 16, maximum 100 ms pulse using at most two 50 ms refreshes, stop after every pulse,
  maximum three pulses/300 ms energized/two seconds wall-clock, no overlapping request, minimum
  250 ms cooldown after confirmed stop, and terminal stop on every prerequisite loss.
- **Prerequisites:** FL007, FL008, FL012; reviewed mounting/alignment and physical-test checklist.
- **Files/modules:** `firstlife/orientation`, `firstlife/runtime` effect binding, HiHugo hold/cancel UI.
- **Acceptance criteria:** Centered evidence emits no move; left/right emits only the matching bounded
  proposal; detector loss/staleness, user release, timeout, denial, disconnect, stale session,
  background, or E-stop emits stop and blocks further pulses. Every movement trace contains H007,
  H029, H030, H018, and T032 correlations. No forward/backward request exists.
- **Tests/checks:** Virtual-time exhaustive reducer/model tests, noisy/alternating/lost detections,
  authorization and stale-callback faults, hard-budget property tests, fake-T032 trace, architecture
  scan forbidding BLE/FED/GATT/encoder imports.
- **Non-goals:** Navigation, following, continuous tracking, PID control, distance estimation,
  unobserved search, stairs/edge tests, head-camera assumptions.
- **Hardware/device verification:** Staged Samsung mounted/aligned with its front camera facing the
  person on real LOOI in a cleared flat area, manual E-stop operator present. Prove front-preview and
  physical BODY left/center/right calibration, then centered no-motion and one left/right minimum pulse,
  loss/release stop, max-budget stop, E-stop, lower-contact gate, disconnect, and background stop.
- **Completion evidence:** The staged Samsung SM-S928B + real-LOOI run proved calibrated direction,
  centered no-motion, a bounded magnitude-16/100 ms pulse and release stop, real-move person-loss
  stop, exact three-pulse/300 ms budget, fresh lower-contact hazard blocking and SAFE return, the
  existing E-stop latch, fail-closed HOME/pointer-cancel stop, disconnect cleanup, route evidence,
  and redacted diagnostics. The final continuation passed `OK (1 test)` in 75.544 seconds.
- **Verification evidence:** [`FL013_BOUNDED_PERSON_ORIENTATION.md`](FL013_BOUNDED_PERSON_ORIENTATION.md)
  records the implemented controller, authorization/effect route, automated evidence, Samsung UI,
  and completed bounded real-LOOI physical acceptance.

## FL014 - First-life end-to-end composition

- **Status:** `DONE`
- **Objective:** Compose identity, face, provider runtime, voice, vision, and orientation into the one
  scripted product experience without introducing a general agent.
- **Exact scope:** Add the application composition root and lifecycle owner; bind reducers/effects;
  replace the current entry presentation with the minimal HiHugo shell while retaining explicit
  diagnostics and E-stop access; enforce single active microphone/camera/orientation session and
  deterministic cancellation order. Add a scripted demo/reset mode containing no fake success.
- **Prerequisites:** FL002-FL013 complete with their device/hardware evidence.
- **Files/modules:** app composition root, `feature/hihugo`, `firstlife/*`, narrowly reviewed Android
  lifecycle/service wiring.
- **Acceptance criteria:** Cold launch through greeting and centered-person greeting completes on
  current real evidence; each blocked state is honest/actionable; HUGO identity/face survives body
  disconnect; process/background/cancel releases resources and stops motion; diagnostics show only
  redacted semantic facts; no direct transport call exists outside adapter/platform code.
- **Tests/checks:** Full fake end-to-end and fault matrix, Compose/instrumented navigation/lifecycle,
  process recreation, permission denial, stale generation/session, disconnect, privacy/secret/
  dependency scans, full JVM/lint/assemble.
- **Non-goals:** Production onboarding, dashboard, wake word, AI/LLM, durable user memory, provider
  ranking, multiple bodies, general camera/voice platform.
- **Hardware/device verification:** Samsung + real LOOI rehearsal required, including app launch,
  connect/select, voice, camera, bounded orientation, greeting, cancel, E-stop, background, and
  disconnect. This is rehearsal evidence, not final acceptance.
- **Completion evidence:** [`FL014_FIRST_LIFE_END_TO_END_COMPOSITION.md`](FL014_FIRST_LIFE_END_TO_END_COMPOSITION.md)
  records the implemented composition owner, deterministic cleanup/reset, full fake path/fault
  evidence, passing Samsung UI/cold-launch checks, and the completed Samsung + real-LOOI rehearsal.
  The real run proved persistent identity, universal BODY preparation and exact capability evidence,
  explicit active-body selection, bounded voice/name/greeting, fresh centered front-camera evidence,
  centered no-motion completion, E-stop/Cancel/background/disconnect cleanup, and redacted output.

## FL015 - FL Acceptance - "HUGO First Life"

- **Status:** `DONE` (2026-08-31)
- **Objective:** Produce the final automated, Android, privacy, architecture, and real-device proof for
  the First Life envelope and decide pass/fail without adding features.
- **Exact scope:** Audit FL001-FL014 evidence; execute full deterministic/fault suites, documentation
  and T032 guards, architecture/dependency/privacy/secret scans, lint and debug build; run the signed
  Samsung + real-LOOI scenario; record deviations and capability claims; freeze the slice if passed.
- **Prerequisites:** FL014 complete; no unresolved safety/privacy blocker.
- **Files/modules:** dated acceptance document and evidence index only; acceptance fixes must be
  separately justified and remain inside an originating FL task boundary.
- **Acceptance criteria:** App launches; HiHugo face renders; same HUGO identity restores; LOOI becomes
  available through the universal flow; negotiated capabilities are visible; explicit active-body
  selection works; TTS introduction, STT response, and session-only greeting work; visible camera
  session detects and coarsely locates a consenting person; bounded orientation uses H029/H030/H018/
  T032; centered/lost/cancelled cases stop; manual E-stop/disconnect/background remain safe; no
  BLE/GATT/FED/encoder leakage or retained raw media exists.
- **Tests/checks:** HUGO documentation and T032 evidence guards; architecture guards; all FL and
  relevant H003-H035 JVM/instrumented tests; `testDebugUnitTest`; Android tests; `lintDebug`;
  `assembleDebug`; secret/privacy/dependency scans; `git diff --check`; signed physical checklist.
- **Non-goals:** Any implementation, scope expansion, performance marketing claim, general AI,
  long-term memory, face recognition, autonomous navigation/following, production release.
- **Hardware/device verification:** Final Samsung Android phone + real LOOI run is mandatory. A failed
  physical safety or privacy check is a valid `FAILED/BLOCKED` acceptance result and cannot be waived
  by automated tests.
- **Completion evidence:** `docs/HUGO_FIRST_LIFE_ACCEPTANCE_<date>.md`, redacted diagnostic export,
  build/test reports, no raw audio/video, signed physical matrix, final slice status, and freeze
  recommendation. [`HUGO_FIRST_LIFE_ACCEPTANCE_2026-08-31.md`](HUGO_FIRST_LIFE_ACCEPTANCE_2026-08-31.md)
  records the FL001-FL014 audit, full automated/Android/static results, embedded redacted diagnostic
  export, operator-confirmed Samsung + real-LOOI matrix, disclosed originating-boundary correction,
  final `PASS`, and slice freeze. H036 was unstarted at that slice-freeze boundary.

## Global stop conditions

Stop the active FL task and request an architecture decision if it requires any of the following:

- changing H003-H035 semantics rather than composing them;
- teaching HUGO Core/UI/voice/vision about LOOI, BLE, GATT, FED, UUIDs, packets, or encoders;
- creating a second BLE, handshake, protocol, safety, motion, or authorization engine;
- bypassing H029 negotiation or H030 authorization before physical dispatch;
- bypassing `DefaultSafetyController`, dead-man, E-stop, sensor gates, limits, or authority leases;
- retaining or uploading raw microphone/camera data;
- treating person detection as identity, certainty, safety, consent, or distance;
- requiring cloud LLM/STT/vision to make the first-life happy path work;
- starting H036-H080 work under an FL identifier; or
- expanding orientation into navigation, following, or open-ended autonomy.

## First recommended task

FL001-FL015 are `DONE`; the First Life slice is accepted and frozen. Later canonical work is tracked
separately in `HUGO_TASKS.md`; H041 is the next eligible canonical task.
