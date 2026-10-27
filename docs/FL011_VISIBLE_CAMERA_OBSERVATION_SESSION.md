# FL011 - Visible camera observation session

## Status

`DONE` as of 2026-08-27 for the originally selected rear-camera session. The authorized Samsung
SM-S928B opened a user-visible rear-camera preview,
produced bounded current-frame metadata, showed Android's camera privacy indicator, and released the
camera on explicit close, background, rotation, and process loss. FL012 is next; H036 remains
unstarted.

The mounted-camera amendment of 2026-08-29 supersedes the lens choice for the current composition:
the phone's rear lens points downward on LOOI, so production First Life now binds the front lens.
The lifecycle/privacy/backpressure evidence below remains historical evidence for this session
boundary; targeted Samsung front-preview, coordinate, lifecycle, and privacy revalidation is a
mandatory FL013 gate before motion and is not claimed by this record.

## Session architecture

- `firstlife/vision` owns an Android-free correlated session model, port, coordinator, opaque frame
  metadata, and a generic latest-frame gate. States distinguish permission waiting, opening,
  callback-confirmed observing, and typed blocked outcomes.
- Every request has an opaque ID and increasing generation. Open/frame/failure callbacks must match
  both. Stale generations and non-increasing frame sequences cannot advance a current session.
- `platform/vision/android/AndroidCameraXSession` owns CameraX `Preview`, `ImageAnalysis`,
  `PreviewView`, `ImageProxy`, executors, and lifecycle binding. HUGO domain and diagnostics never
  receive pixels, planes, buffers, lens objects, camera IDs, or Android types.
- CameraX is pinned to `1.4.1`. The original acceptance bound `DEFAULT_BACK_CAMERA`; the amended
  mounted composition binds only `DEFAULT_FRONT_CAMERA`. CameraX supplies the natural mirrored front
  preview while the analysis stream remains sensor-oriented and is physically verified against the
  BODY frame without an additional flip, requests 640x480 analysis, and sets
  `STRATEGY_KEEP_ONLY_LATEST`. It never constructs
  image-capture or video-capture use cases.

## Frame and backpressure boundary

The pure `LatestFrameGate` permits one in-flight frame and one latest pending frame. A newer pending
frame replaces and closes the previous pending frame. Completion, replacement, close, background,
rotation, process loss, and late completion release every accepted frame exactly once.

Domain metadata contains only the opaque request reference, generation, sequence, monotonic camera
timestamp, bounded dimensions, rotation, semantic lens designation, and analysis-mirroring flag. It
contains no pixel content, person result, face, identity, body/provider fact, safety statement, or
execution authority. FL011 performs no person detection; that remains FL012.

## Permission, preview, and lifecycle

One explicit tap requests `CAMERA` just in time. Only opening/observing states render the preview;
only a real successful CameraX bind projects `OBSERVING`. The panel permanently states that there is
no photo, video, face identity, cloud upload, frame log, or hidden capture. While active it displays
an in-app camera indicator and safe current-frame orientation metadata.

Permission denial, permanent denial, missing selected camera, bind failure, explicit close, and
background are separate typed outcomes. Close is correlation-safe and idempotent. `ON_STOP`,
activity disposal, rotation recreation, and process termination unbind use cases, clear the analyzer,
close the frame gate, and release the platform session. No camera state is restored as HUGO identity.

## Tests and automated checks

- Coordinator tests cover explicit permission, open/frame ordering, stale generation/frame rejection,
  typed denial, close, background, and exact correlation.
- Backpressure tests cover one in-flight plus one latest pending frame, replacement, close, and late
  completion with exactly-once release.
- Privacy/architecture tests keep `firstlife/vision` Android-free and reject image/video capture,
  recording, cloud, BLE, LOOI, and protocol coupling.
- Compose instrumentation covers explicit open, visible preview, active indicator, privacy boundary,
  and explicit close. The corrected viewport test scrolls the close control before clicking.
- Full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` pass.

## Samsung acceptance

Target: authorized Samsung SM-S928B, API 36, on 2026-08-27. LOOI remained disconnected.

```text
adb=authorized model=SM-S928B api=36
permission=just-in-time allow=pass denial=typed
camera-selection=rear device-semantic
preview=visible user-confirmed
android-privacy-indicator=visible user-confirmed
frame-stream=current rotation=90 non-mirrored=true bounded=640x480
explicit-close=camera-service-empty
background=camera-service-empty typed-background-state
rotation=camera-service-empty device-settings-restored
process-loss=camera-service-empty relaunch-camera-closed
app-private-media-files=0
focused-ui-shell-identity-instrumentation=10/10-pass
looi-connected=false
```

Android camera service confirmed rear camera device `0` was active only for package `io.redi.robot`
during the visible session and showed no active client after every release check. The user's original
accelerometer/user-rotation settings were restored exactly after the rotation test. The user visually
confirmed the rear preview and Android green privacy indicator. Final cleanup left camera service
empty.

No screenshot containing preview pixels is retained in the repository. The redacted acceptance
record is this semantic trace and the post-close UI/lifecycle evidence; temporary UI hierarchy files
are not committed. App-private storage contained zero image/video-like files.

## Next boundary

FL012 may later consume ephemeral frames inside the platform vision boundary to produce uncertain
person evidence. FL011 grants no detector, identity, memory, body, authorization, safety, movement,
or execution capability. FL012 and H036 were not started.
