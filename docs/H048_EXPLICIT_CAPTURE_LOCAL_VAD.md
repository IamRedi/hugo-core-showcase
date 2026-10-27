# H048 Explicit Capture and Local VAD

## Record

- Task: `H048 - Explicit capture/local VAD`
- Status: `DONE`
- Completed: 2026-09-01
- Prerequisite: H047 audio session reducer
- Runtime impact: isolated, unwired Android microphone adapter; no production voice-route change
- Hardware claim: redacted Samsung `SM-S928B` instrumented capture evidence only

## Capture model and API

H048 adds the Android-free `voice/capture` boundary. `ExplicitAudioCapturePort` accepts only an
explicit, correlated PTT-style request carrying the existing H047 `AudioSessionId`, monotonic request
and deadline times, and a bounded PCM configuration. Start, stop, snapshot, event, failure, and
evidence types are project-owned; microphone permission or ownership is never inferred merely from
the presence of the port.

The default capture format is mono PCM16 at 16 kHz in 20 ms frames. Supported configurations are
strictly bounded, and a capture request can never exceed 15 seconds. Start distinguishes accepted,
busy, permission unavailable, audio unavailable, and invalid request. Stop distinguishes requested,
already stopping, inactive, and wrong-session requests, with explicit user-release, cancellation,
background, and deadline reasons.

H048 does not connect this port to the H047 reducer or the existing First Life voice route. That
composition belongs to later voice tasks. It introduces no wake word, recognizer, transcript, TTS,
provider choice, AI request, authority, capability invocation, persistence, or background capture.

## Ephemeral PCM and local VAD

`EphemeralPcm16Frame` copies only one bounded frame, rejects access after release, and zeroes its
sample array on idempotent close. `EphemeralVadProcessor` guarantees release on both success and
detector failure. No public API returns the backing array.

`ThresholdLocalVoiceActivityDetector` is a deterministic local energy boundary detector. Its
default policy requires an average absolute amplitude of 900, two consecutive voiced frames to
start activity, and five consecutive quiet frames to end it. Input sequences must increase and all
timestamps are monotonic. The detector retains only counters and active/inactive state; it retains
no PCM. It is not speech recognition and makes no semantic claim about captured sound.

Capture evidence is content-free: processed frame/sample/release counts, first-frame latency,
maximum VAD decision latency, and transition count. The model requires one release per processed
frame and structurally forbids `rawAudioRetained` or `audioUploaded` from becoming true.

## Android capture and permission boundary

`AndroidAudioRecordCapturePort` is isolated under `platform/voice/android`. It maps the pure contract
to Android `AudioRecord` using `VOICE_RECOGNITION`, mono PCM16, and a bounded blocking-read worker.
It checks `RECORD_AUDIO` before allocation and during capture, validates the real recording state,
honours the correlated deadline, zeroes both the per-frame copy and reusable read buffer, resets the
detector, and releases `AudioRecord` exactly once on every terminal path.

Typed failures cover permission revocation, initialization, start, read, callback, and unknown
failures. Stop and release are idempotent. The `BACKGROUNDED` stop reason is available to a future
lifecycle coordinator, but H048 does not add an implicit lifecycle observer or a background-start
path. The adapter creates no file, preference, database, log, network request, transcript, STT/TTS
object, or long-lived audio store.

## Device evidence

On 2026-09-01 the prepared debug and androidTest APKs were installed on an authorized Samsung
`SM-S928B`; the hardware serial is intentionally omitted. Two focused instrumented tests passed:

- an injected permission-unavailable path rejected capture before allocating `AudioRecord`; and
- a real foreground 800 ms explicit capture reached its deadline, produced 34 frames, observed its
  first frame at 141 ms, completed local VAD decisions with a maximum measured latency of 1 ms,
  released all 34 frame buffers, released the microphone, and reported no retained or uploaded
  audio.

The ambient capture produced zero voice transitions; that is not a failure because deterministic
start/end transition behavior is covered with synthetic PCM in JVM tests. No raw samples, device
serial, speech content, transcript, or private identifier were logged. The temporary microphone
grant was revoked after the run and verified as `granted=false`. LOOI was not connected or modified.

## Coverage and checks

`LocalVoiceActivityDetectorTest` contributes nine JVM tests covering deterministic start/end
transitions, exact amplitude boundary and latency, copy/zero/release behavior, detector failures,
sequence replay and reset, bounded request validation, non-retention invariants, correlated fake-port
start/stop behavior, and package/dependency privacy checks. The Android class contributes two passing
instrumented tests for permission denial and genuine bounded capture/release.

The architecture dependency guard now protects `voice/capture` as an Android-free inward domain and
allows its deliberate dependency on H047 `voice/session`; Android media types remain confined to the
platform adapter. Required completion checks pass: First Life documentation/order guard, HUGO V2
documentation guard, T032 baseline evidence guard, architecture dependency guard, focused H048 JVM
tests, full `testDebugUnitTest` (787 tests across 108 suites; zero failures/errors/skips), `lintDebug`,
`assembleDebug`, `assembleDebugAndroidTest`, instrumented device tests, and `git diff --check`.

## Next boundary

H049 is eligible and has not started. H048 provides capture and local voice-boundary evidence only;
it does not add speech adapter contracts, audio-focus coordination, STT, TTS, wake-word activation,
conversation orchestration, provider selection, AI, memory, or capability execution.
