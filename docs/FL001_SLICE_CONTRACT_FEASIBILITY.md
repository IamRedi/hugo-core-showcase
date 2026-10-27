# FL001 - Slice Contract and Feasibility Decisions

## Record

- Task: `FL001 - Slice contract and feasibility decisions`
- Status: **DONE**
- Decision date: 2026-08-26
- Accepted base: H035 commit `4760d24aa30ba31b46519036e05728217f52293f`
- Runtime impact: none; this is a documentation/decision task
- Device or hardware claim: none
- Next eligible task: `FL002 - Persistent HUGO identity bootstrap`
- Explicitly not started: FL002-FL015 and H036

This record freezes the First Life slice. It composes H003-H035 and the physically accepted T032
runtime; it does not change their meaning. A later implementation task must stop for a new decision
instead of silently exceeding a limit in this record.

## Feasibility disposition

| Area | Decision | FL disposition |
|---|---|---|
| HUGO identity | Installation-local H003 identity, independent of user/body | GO for FL002 |
| Interaction/presentation | Pure deterministic reducers and fixed Albanian resources | GO for FL003/FL004 |
| Runtime composition | Slice-local coordinator over unchanged HUGO contracts | GO for FL005 |
| LOOI composition | One service-owned BLE/GATT session and one narrow H018-to-T032 delegate | GO, hardware-gated FL006/FL007 |
| Albanian STT | Android on-device recognizer first; disclosed system recognizer or text fallback | GO, device-gated FL009 |
| Albanian TTS | Installed Android TTS with an exact local Albanian voice | GO, device-gated FL010 |
| Camera | CameraX preview plus bounded latest-frame analysis | GO, device-gated FL011 |
| Person detector | MediaPipe Tasks Object Detector with bundled EfficientDet-Lite0 int8 | GO, size/license/performance-gated FL012 |
| Orientation | User-held, evidence-gated, three 100 ms maximum turn pulses | GO only after FL007/FL012 hardware evidence |

### Identity bootstrap and Android compatibility

FL002 will create one cryptographically random H003 identity and store its strict versioned encoding
in app-private, no-backup storage using atomic replacement. Restore either returns that exact identity
or fails closed with an explicit user reset; corruption never invents a replacement. The identity is
not derived from the user, Samsung device, account, LOOI, BLE endpoint, or session, and its private
value is absent from UI/logs/diagnostics. This is identity persistence, not personal memory.

The project remains minSdk 26. CameraX supports that floor, MediaPipe Tasks for Android requires API
24 or later, and the selected model is local. Android's on-device speech construction is runtime-
gated to API 31+ and support-check APIs to API 33+; the minSdk build therefore uses guarded platform
adapters rather than a new speech dependency. Android 11+ speech-service visibility is declared only
when FL009 adds the microphone adapter. No dependency, permission, or manifest entry is added in
FL001.

## FL-ADR-001 - Deterministic interaction and presentation truth

**Decision.** First Life has no agent, natural-language planner, wake word, or animation-driven
behavior. `FirstLifeInteractionReducer` owns one correlated, cancellable interaction session with
these states:

```text
READY
 -> INTRO_SPEAKING
 -> AWAITING_LISTEN_START
 -> LISTENING
 -> TRANSCRIBING
 -> GREETING_SPEAKING
 -> READY

READY
 -> CAMERA_OPENING
 -> OBSERVING
 -> ORIENTATION_ARMED
 -> ORIENTING_PULSE
 -> OBSERVING | PERSON_CENTERED
 -> GREETING_SPEAKING | READY

any active state -> CANCELLING -> READY
recoverable prerequisite failure -> BLOCKED
unexpected terminal implementation failure -> FAILED
```

Every asynchronous event carries the exact session/request correlation. A stale event is ignored
and cannot reopen a resource, speak, or move. `BLOCKED` is actionable and recoverable; `FAILED` is a
terminal fault for that session. Retry is an explicit user action and creates a new session ID.

The presentation vocabulary is:

| State | Honest evidence |
|---|---|
| `BOOTING` | Identity/composition bootstrap has not completed |
| `IDLE` | HUGO is ready and no higher-priority activity exists |
| `LISTENING` | The explicit microphone session is actively capturing |
| `THINKING` | Bounded transcription, parsing, or detector result processing is active |
| `SPEAKING` | TTS emitted `onStart` and has not emitted a terminal callback |
| `OBSERVING` | Visible camera preview and analysis session are active |
| `BODY_READY` | Current provider/session plus H029 capability evidence exist |
| `BLOCKED` | A typed, recoverable prerequisite prevents the requested step |
| `ERROR` | An unexpected terminal failure ended the current step |

When facts overlap, face priority is `ERROR > BLOCKED > SPEAKING > LISTENING > THINKING >
OBSERVING > BODY_READY > IDLE`; `BOOTING` applies only before bootstrap completes. A separate compact
status area retains body and sensor facts hidden by face priority. `BODY_READY` does not mean active,
authorized, safe, or executing. The visual is simple project-owned animated eyes/face. HiHugo and
HUGO identity do not depend on LOOI.

The fixed dialogue resources are exactly:

- Introduction: `Përshëndetje. Unë jam HUGO. Kush je ti?`
- Name greeting: `Gëzohem që të njoh, %1$s.`

The name parser accepts only one short display value after Unicode normalization and whitespace
collapse: 1-40 Unicode letters plus internal space, apostrophe, or hyphen. It rejects digits,
control characters, markup, and empty/ambiguous transcripts. It does not infer a name. The value is
session working state only. A centered-person greeting may reuse it only while that same First Life
session remains active.

## FL-ADR-002 - Runtime composition, body selection, and sole LOOI route

**Decision.** `firstlife/runtime` is an application composition layer, not HUGO Core. It coordinates
accepted contracts and owns correlation/cancellation, but owns no BLE, LOOI protocol, trust,
authorization, or safety policy.

The only permitted connection owner is `ConnectedDeviceService`. FL006 may refactor its construction
so the H028 wrapper and the existing handshake/T032 code share the same
`AndroidGattTransportAdapter`; it may not call the current H028 factory in parallel with the
service's adapter. The service remains the sole scanner/GATT lifecycle owner. Because this changes
production composition, FL006 must run the smallest Samsung + real-LOOI connect, handshake,
disconnect, Bluetooth-off, and cleanup regression before `DONE`.

The exact physical execution route is:

```text
FirstLifeInteractionReducer
 -> FirstLifeRuntimeCoordinator
 -> H007 CapabilityRequest (semantic data only)
 -> current H029 negotiated-capability evidence
 -> H030 request-time authorization for that exact request
 -> FirstLifeAuthorizedCapabilityDispatcher
 -> H018 LooiCompatibilityFacade
 -> LooiVerifiedRuntimePort service delegate
 -> ConnectedDeviceService-owned T032ManualAcceptanceSession
 -> existing ProfileADriveCommandExecutor
 -> DefaultSafetyController / authority lease / dead-man / E-stop / contact gates / limits
 -> existing serialized operation queue and Android GATT session
```

The dispatcher has no UI-direction, detector-result, motor, encoder, packet, UUID, endpoint, or
Android overload. It accepts only the exact H007 request and a correlated current `AUTHORIZED` H030
decision. The adapter-owned `LooiVerifiedRuntimePort` translates H017 semantic input into the
smallest existing service command and maps the observed result back with H007 correlation. Stop
uses the existing prioritized neutral-stop path. It does not copy T032 policy and cannot report
success before the verified path reports an accepted/observed outcome. FL007 must prove equivalence
and run the staged physical stop regression before `DONE`.

Active body selection is an explicit user action over a currently available registered BODY with a
fresh H029 report. It uses H016 remote selection; mounting evidence is not required by H016 and is
not fabricated. The selection expires after ten minutes or earlier on user deactivation, provider
loss, connection/session or generation change, negotiation invalidation, or app process death. It
is never automatically restored or reselected. Selection is context only: it grants no trust,
permission, authorization, safety, or execution authority. Before physical orientation, a separate
run checklist must confirm that the phone is mechanically secured and its camera/body left-right
mapping is calibrated.

## FL-ADR-003 - Explicit Albanian voice session

### STT

The smallest first choice is Android's installed on-device `SpeechRecognizer`, requested with
`Locale.forLanguageTag("sq-AL")`. On API 31+ the adapter must first require
`isOnDeviceRecognitionAvailable`, then create `createOnDeviceSpeechRecognizer`; on API 33+ it also
uses `checkRecognitionSupport` and may offer an explicit user-triggered model download. A preference
flag alone is not proof of offline execution. The recognizer is destroyed exactly once on every
terminal path. Android documents both the on-device APIs and that the default recognizer may stream
audio to remote servers: [SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer).

One tap opens one visible microphone session after just-in-time permission. Capture starts only
after the introduction's terminal TTS callback, lasts at most eight seconds, accepts at most three
alternatives, and retains at most 80 Unicode characters long enough to run the local name parser.
There is no app-owned audio recording or file.

Fallback hierarchy is fixed:

1. On-device `sq-AL` recognition after a positive support check.
2. One explicit retry of the same local path.
3. Optional Android system recognizer for `sq-AL`, only after a per-session disclosure that audio
   may use the network and explicit user consent. It is labeled `SYSTEM_NETWORK_ASSISTED`, is never
   automatic, adds no SDK/key/backend, and is not claimed as offline acceptance.
4. Visible text entry through the same sanitizer/parser. It keeps the demonstration usable but is
   not an STT pass.

No result, no match, timeout, ambiguous name, permission denial, or recognizer loss produces a
name. HUGO says/displays that it did not catch the name and offers retry or text entry. FL009 must
record which tier actually works on the SM-S928B with network disabled and enabled.

### TTS

First Life uses the installed Android `TextToSpeech` engine and requests `sq-AL`. It must call
`isLanguageAvailable`, enumerate voices, and select an exact Albanian-language voice that does not
require a network connection and whose data is installed. `setLanguage` alone is insufficient
because Android may choose a closest match. Availability and voice network requirements are exposed
by the official [TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
and [Voice](https://developer.android.com/reference/android/speech/tts/Voice) APIs.

Missing data may lead to an explicit system voice-data setup action and retry. There is no bundled
speech engine, voice cloning, prerecorded-name combinator, or cloud TTS fallback in First Life. If
no acceptable local Albanian voice exists, the exact Albanian text remains visible and the voice
step is `BLOCKED`; the app/body UI still functions, but FL015 cannot claim the spoken experience.
Face state follows real utterance start/done/error callbacks, and cancellation/background stops
playback and releases the engine/session.

## FL-ADR-004 - Visible ephemeral camera and person evidence

The camera implementation is CameraX `Preview` plus `ImageAnalysis`, lifecycle-bound to the visible
HiHugo screen and using the front camera for the outward-facing mounted-body view. It never binds image
capture or video capture. The user explicitly opens the session after just-in-time permission; the
preview and in-app observing indicator remain visible. `STRATEGY_KEEP_ONLY_LATEST`, one analyzer,
one inference in flight, and mandatory `ImageProxy.close()` bound memory and backpressure, following
the official [CameraX analysis guidance](https://developer.android.com/media/camera/camerax/analyze).
Input is capped at 640x480 and resized/cropped in memory for the model. Close, cancel, screen
background, owner destruction, or permission loss closes analysis and preview exactly once.

The selected detector is MediaPipe Tasks Vision Object Detector `1.0.0` with the bundled official
EfficientDet-Lite0 int8 TFLite model version `1` and category allowlist `person`. It directly supplies the only
required output: category, score, and bounding box. The task runs locally in live-stream mode with
at most two results and one pending frame; no tracking or face task is loaded. Google's official
[Object Detector Android guide](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android)
documents asynchronous live-stream operation, busy-frame dropping, score thresholds, allowlists,
and result bounding boxes. MediaPipe samples are Apache-2.0
([repository](https://github.com/google-ai-edge/mediapipe-samples)); the selected TensorFlow model
page reports Apache-2.0 licensing ([EfficientDet-Lite models](https://www.kaggle.com/models/tensorflow/efficientdet/tfLite)).

The FL001 repository review measured the official Google Maven artifacts without adding them:
`tasks-vision:1.0.0` is 495,413 bytes (0.47 MiB), its `tasks-core:1.0.0` dependency is 22,510,915
bytes (21.47 MiB), and the official model artifact is 4,602,795 bytes (4.39 MiB). The published AAR
POM and model card report Apache-2.0. These are artifact/download sizes, not an APK-size or device-
performance claim.

FL012 must retain the applicable license/notice and source URL, record the exact model SHA-256, and
run the dependency/license scan before bundling. Its go/no-go budgets are: combined compressed
library/model artifacts at most 27 MiB, release-like single-ABI APK increase at most 25 MiB, median
detector latency at most 100 ms and p95 at most 150 ms on the target Samsung, with no unbounded
queue or sustained resource fault. Missing provenance, an exceeded size/latency budget, or an
unsupported runtime blocks FL012 and requires a reviewed detector decision; it does not silently
download or substitute a model.

`PersonDirectionEvidence` contains only an observation/session ID, monotonic observed/expiry time,
`LEFT | CENTER | RIGHT | UNKNOWN`, normalized bounding box, normalized horizontal center offset,
and confidence. It contains no frame/crop, face, embedding, identity, name, distance, consent, or
safety claim. Initial threshold is confidence `>= 0.60`; FL012 may raise but not lower it without a
reviewed decision. Multiple qualifying people, low confidence, invalid geometry, or no person is
`UNKNOWN`.

Direction uses the calibrated, non-mirrored analysis/body frame:

- `LEFT` when center-x `< 0.38`;
- `CENTER` when center-x is in `[0.42, 0.58]`;
- `RIGHT` when center-x `> 0.62`; and
- the two hysteresis gaps are `UNKNOWN`.

Usable direction requires three consecutive, same-direction observations within 500 ms, from the
same camera session, with no observation older than 250 ms. Alternation resets the sequence. The
controller stores only the current bounded evidence window and clears it when the session closes.

### Mounted-camera amendment (2026-08-29)

The earlier FL001/FL011 acceptance selected the rear lens before the phone-on-LOOI mounting geometry
was exercised. The operator then confirmed that this lens points downward in the actual mount and
cannot observe the person. The accepted mounted composition therefore uses CameraX
`DEFAULT_FRONT_CAMERA`. This is an explicit product/mounting correction; no automatic lens fallback
is permitted.

CameraX presents the front preview mirrored for the user's natural view, while its rotation-corrected
analysis buffer remains sensor-oriented. A no-motion bidirectional Samsung sweep confirmed that the
analysis coordinates already match physical BODY left and right; no additional horizontal flip is
applied. `PersonDirectionEvidence` and the frozen thresholds above therefore continue to use the
calibrated body frame. Before any FL013 motion,
the Samsung must revalidate visible front preview, no-person/person evidence, and physical BODY
left/center/right calibration. A preview-side label is never movement evidence, and a failed or
uncertain calibration blocks motion rather than swapping left/right implicitly.

## FL-ADR-005 - Bounded orientation envelope

The detector never controls LOOI. The only causal route is camera observation -> coarse evidence ->
First Life controller -> H007 semantic `mobility.move@1.0` request -> current H029 evidence -> H030
authorization -> H018 -> existing T032 runtime.

One orientation attempt requires a current explicitly selected LOOI, successful physical
mount/alignment checklist, fresh direction evidence, current connection/negotiation/grant, floor
mode safely armed, and continuous user press-and-hold. The envelope is stricter than the concept:

- in-place `LEFT` or `RIGHT` only; no forward, reverse, head, search, following, or overlapping move;
- exact already accepted minimum signed drive magnitude `16`; no adaptive increase;
- each pulse has at most two 50 ms refreshes and a hard maximum of 100 ms energized command time;
- semantic stop is requested after every pulse and a correlated terminal stop must be observed
  before a minimum 250 ms cooldown and any later pulse;
- fresh qualifying evidence is rechecked before every pulse;
- at most three pulses, at most 300 ms cumulative energized time, and at most two seconds wall-clock
  from hold acceptance, whichever ends first; and
- `CENTER` completes without motion; `UNKNOWN` never initiates a search pulse.

The 50 ms refresh and 100 ms cap align with the existing manual producer and independent T032
100 ms dead-man. If a controlled turn is not useful at magnitude 16/100 ms on the staged hardware
test, FL013 is blocked; implementation may not lengthen or strengthen it under this decision.

Release, pointer cancellation, app background/lifecycle loss, person loss/ambiguity/staleness,
camera closure, request timeout, missing/stale H029 evidence, H030 denial/revocation/expiry,
provider/session/generation loss, active-body expiry/loss, T032 safety/contact rejection, failure to
confirm the preceding stop, budget exhaustion, and E-stop all end the attempt, prohibit later
pulses, and request the existing prioritized semantic stop. Disconnect and E-stop retain their
existing independent stop behavior. No detector result is collision, floor, clearance, or consent
evidence. The local E-stop remains visible and staffed during physical tests.

## Privacy, retention, and security policy

| Data | Retention and diagnostics |
|---|---|
| Raw microphone input | Owned by the active Android recognizer only; no app file/buffer/history |
| Transcript/name | Memory-only, bounded; cleared on reset/end/cancel/background/process death; never H034 diagnostics |
| Camera frame/tensor | At most current/latest processing buffers; closed after inference; never stored/exported/backed up |
| Detection | Current three-sample evidence window only; cleared on closure; no person history |
| Body/connection | Existing protected opaque references and H034 redaction only; no address/UUID/protocol diagnostics |

No credentials, cloud keys, biometric data, face database, audio/video capture artifact, model
download, or private provider identifier is introduced. System-network-assisted STT is the only
permitted network-capable fallback and requires a fresh disclosure/consent each session. Android
privacy indicators are not obscured. Logs and acceptance evidence contain typed outcomes and timing,
not transcript, name, frame, bounding box, or raw confidence traces tied to a person.

## Failure and fallback matrix

| Failure | Honest behavior |
|---|---|
| HUGO bootstrap corruption | Fail closed with explicit reset; never derive identity from LOOI/user |
| LOOI absent/unavailable | HUGO launches and voice/face remain usable; show `No body available`; no fake BODY_READY |
| Active body absent/expired | Voice/camera may work; orientation is blocked and cannot dispatch |
| STT unsupported/no match | One explicit local retry, then disclosed system recognizer or text; never invent a name |
| Local Albanian TTS absent | Offer system voice-data setup; show exact text; mark spoken step blocked |
| Camera permission/support failure | Close session, show blocked reason, emit no direction and no movement |
| Detector/model failure or ambiguity | Direction becomes `UNKNOWN`; stop/end orientation; no blind search |
| Authorization/provider/evidence loss | Stop/end orientation and require a new explicit attempt |
| Safety rejection/E-stop/stop not confirmed | Existing T032 stop/latch wins; no retry until deliberate safe recovery |
| Background/process loss | Release voice/camera, clear session name/evidence/selection, converge on existing stop |

## Hardware and device acceptance gates

FL001 itself performs no device or physical verification. Later tasks must record, rather than
assume:

1. SM-S928B/API 36 on-device `sq-AL` recognizer availability, model state, offline result, timeout,
   cancellation, and default-system recognizer disclosure behavior (FL009).
2. Installed exact local Albanian TTS voice, audibility/quality, network-required flag, start/done/
   error/cancel behavior (FL010).
3. Front-camera orientation for the mounted composition, preview visibility, permission/lifecycle
   release and privacy indicators (FL011 evidence plus the 2026-08-29 FL013 amendment).
4. Pinned detector/model license and SHA-256, APK delta, latency budgets, empty/left/center/right/
   multiple-person behavior with a consenting adult (FL012).
5. Single-owner H028/service composition: connect, handshake, ten clean reconnects where the task
   checklist requires them, disconnect, Bluetooth-off, stale-generation rejection, and exact cleanup
   on Samsung + real LOOI (FL006).
6. H018-to-T032 equivalence, stop priority, magnitude 16/100 ms usefulness, left/right calibration,
   release/loss/budget/background/disconnect/contact/E-stop stops on a secured phone mount and clear
   flat test area (FL007/FL013).

Failure of any gate blocks only the dependent First Life claim. It cannot be converted into a
success by fallback text, a simulator, or earlier T032 evidence.

## Order and boundary audit

The FL001-FL015 graph remains valid. FL001-FL013 are complete, including FL013's bounded Samsung +
real-LOOI physical acceptance. FL014 is the single next eligible task and remains unstarted; FL015
remains the final acceptance gate. Nothing in this record starts or completes H036-H080.

No production Kotlin, manifest, dependency, model, permission, runtime path, or T032 evidence was
changed by FL001. The accepted HUGO V2 definitions remain canonical, and this record uses them by
reference.

## Completion checks

| Check | Result |
|---|---|
| First Life document/status/link consistency and FL001-FL015 order audit | PASS |
| Architecture boundary and sole BLE/GATT/T032 route review | PASS |
| Privacy, retention, fallback, and bounded-orientation safety review | PASS |
| HUGO V2 documentation guard | PASS |
| T032 baseline evidence guard | PASS |
| `ArchitectureDependencyGuardTest` | PASS |
| Canonical `docs/HUGO_TASKS.md` and production-runtime unchanged audit | PASS |
| `git diff --check` | PASS |

The Gradle guard used the Android Studio JBR and installed Android SDK. No full runtime build, lint,
device run, or hardware run is required or claimed for this documentation-only task.
