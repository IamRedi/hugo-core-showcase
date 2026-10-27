# FL003 - Minimal HiHugo Shell and Face Reducer

## Record

- Task: `FL003 - Minimal HiHugo shell and face reducer`
- Status: **DONE**
- Implementation date: 2026-08-26
- Accepted base: FL002 commit `80353498acc4dc23653127880a679a251cce859e`
- FL001 presentation vocabulary: reused unchanged
- H003/FL002 identity semantics: unchanged
- Production LOOI/body/transport impact: none
- H036: not started

## Presentation architecture

`feature/hihugo/presentation` is an Android-free presentation boundary. It defines the nine accepted
First Life face states, their deterministic visual specifications, a monotonic presentation-event
reducer, the FL002 bootstrap projection, and the lifecycle/reduced-motion animation policy. A reducer
event changes only the face state and sequence. It preserves the exact H003 identity, redacted display
identity, and bootstrap run reference, and it rejects an equal or older sequence as stale.

The FL002 projection maps `CREATED` and `RESTORED` to `IDLE`, temporary storage unavailability to
`BLOCKED`, and corrupt or unsupported persisted identity to `ERROR`. Failure projections contain no
identity and cannot create one. The face is therefore available without a body while identity failure
remains visible and fail closed.

`feature/hihugo/ui` is a Compose-only renderer over `HiHugoPresentationModel`. The project-owned face
is drawn from two abstract eyes and an outline; it contains no LOOI or vendor artwork. Each state has a
distinct label and deterministic combination of eye openness, gaze, accent, motion class, and bounded
interval. Accessibility semantics expose only `HUGO face`, the presentation label, and whether motion
is active or paused.

## State and lifecycle behavior

| State | Presentation behavior |
|---|---|
| `BOOTING` | partially open blue eyes with bounded startup motion |
| `IDLE` | cyan eyes with a low-frequency blink |
| `LISTENING` | attentive cyan pulse |
| `THINKING` | violet lateral gaze |
| `SPEAKING` | violet bounded openness pulse |
| `OBSERVING` | blue bounded gaze sweep |
| `BODY_READY` | green readiness glow, with no connection or authority claim |
| `BLOCKED` | static amber reduced-open state |
| `ERROR` | static red reduced-open state |

One composition-owned `LaunchedEffect` may toggle an animation phase only while the Activity is
resumed, motion is enabled, and the visual specification permits motion. The key cancels the previous
loop on state/lifecycle change and Compose cancels it on disposal. System animator scale zero selects
the static reduced-motion path. Animation never emits a reducer event and cannot advance runtime or
provider state.

Activity recreation preserves the developer preview selection with saveable UI state and reprojects
the same process-owned FL002 snapshot. A cold process/app launch projects the authenticated bootstrap
snapshot to `IDLE`; a previous preview state is not durable session state.

## Shell and separation boundaries

`MainActivity` now launches the HiHugo entry shell. It passes the immutable FL002 snapshot, debug flag,
and reduced-motion setting. The prior diagnostic/manual composable remains unchanged in source but is
not invoked by the FL003 entry path. Consequently launch does not bind `ConnectedDeviceService`, ask
for Bluetooth permissions, scan, connect, establish trust, authorize, select a provider, or execute a
capability.

The shell shows an explicit `Body / provider: not connected` slot. Debug builds expose nine 48 dp
minimum presentation-preview controls. Those controls dispatch only `HiHugoPresentationEvent`; they
have no runtime ports. No production connect, voice, camera, orientation, cancel, E-stop, AI, memory,
or LOOI behavior was added in FL003.

## Tests and checks

Pure JVM coverage verifies all nine deterministic visual specifications, ready/failure bootstrap
mapping, identity preservation, stale-event rejection, absence of trust/authorization/connection/
permission fields, and lifecycle/reduced-motion animation gating. The architecture guard treats the
presentation reducer as a pure boundary and rejects Android, platform, feature, LOOI, network, and
vendor dependencies.

Samsung instrumentation verifies idle bootstrap rendering, face semantics, disconnected status,
all nine preview states, exact H003 identity across Activity recreation, and background/resume
lifecycle cleanup. The existing FL002 continuity test was also rerun before and after force-stop; both
authenticated restores passed, and the cold relaunch returned to `IDLE`.

| Check | Result |
|---|---|
| First Life documentation/order guard | PASS |
| HUGO V2 documentation guard | PASS |
| T032 baseline evidence guard | PASS |
| Architecture dependency guards | PASS |
| Relevant FL002/FL003 JVM tests | PASS |
| Full `testDebugUnitTest` | PASS |
| `lintDebug` | PASS |
| `assembleDebug` and Android-test APK assembly | PASS |
| Samsung FL003 instrumented class (4 tests) | PASS |
| Samsung force-stop/cold-relaunch idle check | PASS |
| `git diff --check` | PASS |

## Samsung acceptance evidence

- Device: authorized Samsung `SM-S928B`, Android 16 / API 36 (serial redacted).
- Debug and Android-test APKs installed successfully without clearing app data.
- Visual inspection: dark HiHugo shell, two abstract cyan idle eyes, readable state/status, and no
  identity value, provider identity, address, transport detail, or LOOI branding.
- Accessibility hierarchy: `HUGO face`, `Idle`, `HUGO is ready`, and
  `Body / provider: not connected`; all preview controls have at least 48 dp touch targets.
- Preview harness: all nine accepted states rendered successfully on-device.
- `ActivityScenario.recreate()`: preview state and exact H003 identity preserved.
- Background/resume: animation ownership stopped/restarted with Activity lifecycle; no presentation
  transition was generated by animation.
- Force-stop/relaunch: authenticated identity restore passed before and after; shell returned to idle.
- Screenshot and UI-hierarchy evidence were inspected from temporary ADB captures and intentionally
  not retained in Git; no raw personal or transport data was present.
- No LOOI connection, scan, handshake, body activation, permission request, sensor, audio, camera, or
  capability execution occurred.

## Risks and residual gates

- FL003 states are presentation vocabulary, not evidence that voice, camera, body, trust,
  authorization, connection, safety, or physical feasibility exists. Later reducers must supply only
  states justified by their own accepted evidence.
- The preview harness is debuggable-build-only and must remain presentation-only.
- Future FL composition may replace the temporary disconnected slot, but must preserve the reducer/UI
  separation and lifecycle animation ownership.

FL004 is the next eligible First Life task. It remains unstarted. H036 remains unstarted.
