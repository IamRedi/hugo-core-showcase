# FL012 - On-device person evidence

## Status

`DONE` as of 2026-08-29. The implementation, deterministic fixtures, privacy boundary, size budget,
and Samsung empty/left/center/right/loss/multiple-person/performance/lifecycle checks pass. FL013 and
H036 remain unstarted.

## Architecture and evidence model

- `firstlife/vision` owns Android-free candidates, bounded boxes, uncertain evidence, freshness,
  direction, reducer, and latency telemetry. It exposes no pixel, crop, face, embedding, identity,
  user, provider, authority, or execution type.
- `platform/vision/android` owns CameraX frame conversion and MediaPipe Tasks Vision. Pixels remain
  ephemeral inside this boundary and are released after each inference.
- The detector is MediaPipe Tasks Vision `1.0.0` with the official EfficientDet-Lite0 int8 v1 model,
  SHA-256 `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb` and size 4,602,795 bytes.
  Model provenance and retained licenses are recorded beside the asset and under `third_party/`.
- The application manifest removes transitive `INTERNET` and `ACCESS_NETWORK_STATE` permissions.
  The Samsung package check confirmed both absent. No detector input or result can leave the device.

One qualifying person requires confidence at least 0.60 and three consecutive same-direction fresh
observations within 500 ms; each observation may be at most 250 ms old. Bounding-box center X below
0.38 is `LEFT`, 0.42 through 0.58 is `CENTER`, and above 0.62 is `RIGHT`; the deliberate gaps are
`UNKNOWN`. No person, low confidence, stale/out-of-order evidence, invalid geometry, or lost evidence
is unknown. More than one qualifying person is `MULTIPLE_PEOPLE` and remains ambiguous.

Detector boxes that cross a frame edge are clipped to the user-visible frame before normalization;
collapsed or contradictory boxes remain invalid. This corrected an observed Samsung edge case
without changing confidence, direction, or freshness policy. Camera close also treats an analyzer
frame already dispatched during shutdown as stale cleanup, preventing it from crashing or advancing
the closed session.

## Resource and privacy evidence

The compressed MediaPipe Tasks/core/model set is 27,609,123 bytes (26.33 MiB), within the approved
27 MiB library/model budget. Comparing release artifacts against accepted commit
`f825e889d705c8eaa8e5ef3ccf8684c299f544e5`, the arm64 single-ABI compressed payload increased by
17,233,355 bytes (16.44 MiB), within the 25 MiB APK-increase budget. The universal APK contains all
four MediaPipe ABIs and is not the single-ABI gate.

No frame, screenshot, crop, video, or detector image is retained in Git or app-private storage.
Diagnostics contain only redacted direction, reason, bounded confidence, sample count, and latency.
LOOI was not connected or modified.

## Samsung verification

Target: authorized Samsung SM-S928B, API 36, on 2026-08-27.

| Scene/check | Redacted result | Status |
|---|---|---|
| Empty | `NO_PERSON`; 2.633 s | PASS |
| Center | `CENTER / STABLE_PERSON`; 2.951 s | PASS |
| Left | `LEFT / STABLE_PERSON`; 6.414 s after edge-clipping correction | PASS |
| Right | `RIGHT / STABLE_PERSON`; 2.450 s | PASS |
| Loss | stable person followed by `NO_PERSON`; 14.597 s | PASS |
| Performance | at least 30 samples; observed 120-sample median 53-55 ms, p95 60-61 ms | PASS |
| Lifecycle | explicit close clears evidence/performance; post-close stale frame is cleanup only | PASS |
| Multiple real people | `UNKNOWN / MULTIPLE_PEOPLE / no confidence`; 2.535 s | PASS |

Two bounded attempts used the user's large photo beside the user only to test whether the detector
would expose two plausible person regions. Both correctly produced one stable person rather than an
invented second detection (`CENTER`, 83% and 75%). A photograph is not recorded as two-person/live
acceptance and no threshold was weakened. Transient USB/ADB interruptions and one locked-screen
instrumentation launch were separately identified and are not detector failures.

Redi, the device-test operator, confirmed this provisional photo-scene result on 2026-08-27 and
requested that it remain recorded without marking FL012 `DONE` until a second consenting real person
was available.

On 2026-08-29, two consenting real people completed the bounded Samsung test. An initial run with the
people outside the rear-camera field correctly remained `UNKNOWN / NO_PERSON`; it is retained as
staging evidence and is not interpreted as a detector failure. After the phone was oriented with its
rear camera toward both separated people, the same unmodified instrumented test produced
`UNKNOWN / MULTIPLE_PEOPLE / no confidence` in 2.535 seconds and passed. The result preserves
ambiguity: it carries no identity, selection, orientation target, confidence invention, permission,
authority, or execution implication. Only this redacted text was logged; no media was captured or
retained.

## Automated evidence

Golden fixtures cover empty, left, center, right, and multiple-person candidates without media.
Reducer/property tests cover confidence, exact direction boundaries, three-frame stabilization,
freshness, stale/order rejection, loss, ambiguity, deterministic ordering, bounded latency, and
session correlation. Platform/architecture tests enforce person-only local inference and prohibit
Android/pixel/identity/provider/LOOI coupling in the domain.

Final checks after the real two-person scene:

| Check | Result |
|---|---|
| First Life documentation/order guard | PASS |
| HUGO V2 documentation guard | PASS |
| T032 baseline evidence guard | PASS |
| Architecture dependency guards / full `testDebugUnitTest` | PASS |
| `lintDebug` | PASS |
| `assembleDebug` | PASS |
| `assembleDebugAndroidTest` | PASS |
| Samsung real two-person instrumented acceptance | PASS (`OK (1 test)`) |
| `git diff --check` | PASS |

FL012 is `DONE`; FL013 is now eligible but remains unstarted, and H036 remains unstarted.

## Mounted-camera amendment

The Samsung evidence above remains the dated rear-lens acceptance for FL012's detector, uncertainty,
latency, lifecycle, and privacy behavior. On 2026-08-29 the actual phone-on-LOOI geometry showed that
the rear lens points downward, so the mounted composition was explicitly changed to the front lens.
No detector threshold, mapping, freshness rule, or ambiguity rule changed. Before FL013 can move,
the same implementation must produce fresh front-camera no-person/person evidence and the operator
must calibrate physical BODY left/center/right. The prior rear-lens direction table is not silently
reused as proof of mounted front-lens direction.
