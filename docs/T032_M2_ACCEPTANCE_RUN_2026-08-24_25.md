# T032 M2 Manual-Control Acceptance Run

## Run identity

- Task: T032, M2 manual-control acceptance
- Dates: 2026-08-24 through 2026-08-25
- Phone: Samsung SM-S928B
- Android: 16, API 36
- Robot: physical LOOI base
- BLE candidate: `MELK-OF10 BC`; no stable/private identifier retained
- Protocol path: verified profile-A connection and handshake, FED9 subscription, project-owned command/safety models
- Build: debug APK built with Android Studio JBR and the repository Android SDK

## Automated checks

| Check | Result |
|---|---|
| `./gradlew testDebugUnitTest` | PASS |
| `./gradlew lintDebug` | PASS |
| `./gradlew assembleDebug` | PASS |
| Debug APK install and launch | PASS |

## Physical acceptance record

| Item | Result | Evidence |
|---|---|---|
| Explicit connect, handshake, FED9, and manual arm | PASS | No automatic connection or movement |
| Forward / backward | PASS | Physical movement observed; conservative signed magnitude 16 |
| Left / right | PASS | Physical mapping corrected and observed in matching directions |
| Release-to-neutral | PASS | Physical stop observed; later commands remain usable |
| Head up / center / down | PASS | Limited `FED1` controls physically observed on 2026-08-25 |
| Light off / minimum on | PASS | Physical light response observed |
| Lower-contact directional stop | PASS | Black-matte/edge substitute stopped motion |
| No stale resume after hazard | PASS | Safe return plus reset/re-arm remained stationary until a new press |
| Reconnect reliability | PASS | 10/10 clean reconnect, handshake, and FED9 cycles |
| Continuous session | PASS | User-timed 15-minute session; all directions/light used; no disconnect, uncontrolled motion, or reported command failure |
| Local E-stop | PASS | Immediate physical stop observed; explicit stationary reset required |
| App background | PASS | Physical stop observed at approximately 0.1 s; explicit E-stop reset and re-arm required |
| Explicit BLE disconnect | PASS | Immediate physical stop observed |
| Bluetooth adapter OFF | PASS | ADB command returned in 210 ms; Android reported disconnected; physical stop and E-stop observed |
| Isolated `stopservice` while bound | EXCEPTION | Android rejected the command and retained the bound service; not claimed as a pass |
| Process/service death | PASS | `am force-stop` returned in 166 ms, no service record remained, and physical stop/power-down behavior was observed |
| Measured RF attenuation | NOT RUN | Adapter-off is transport-loss evidence only; no RF-range or attenuation claim |

## Stop timing interpretation

The `210 ms` Bluetooth-off and `166 ms` force-stop values measure ADB trigger command completion, not motor physics. Background/dead-man stop was operator-observed at approximately `100 ms`, matching the configured deadline. Release, local E-stop, explicit disconnect, lower-contact stop, and process death were physically observed as immediate with no visible continued travel, but no synchronized high-speed camera or wheel encoder was available; no fabricated millisecond latency is reported.

## Acceptance defects corrected

- Dead-man sequence ownership moved to the connection-generation session so Activity recreation cannot produce out-of-order motion refreshes.
- Neutral release cancels the active watchdog deadline without ending the connection epoch.
- Press-and-hold consumes Android pointer ownership deterministically inside the scrollable diagnostic screen.
- Physical angular polarity was corrected so left and right match the controls.
- A lower-contact hazard during motion cancels pending motion, sends repeated neutral, and latches E-stop.
- E-stop invalidates the UI press token so safe return or reset/re-arm cannot resume a stale command.
- Head calibration remains explicitly armed and limited; no observed-position claim is made.

## Boundaries and exceptions

The accepted ADR in `docs/DECISIONS.md` records why Android process termination covers combined process/service death while isolated bound-service destruction remains an exception. Bluetooth adapter-off is not called measured RF loss. No speed-limit increase, autonomy, AI, raw BLE write control, heartbeat change, undocumented endpoint, or original-app dependency was added.

## Sign-off

- Physical operator/project owner: interactive PASS confirmations recorded during the run, including final drive/safety and head checks on 2026-08-25.
- Evidence recorder: Codex, using repository checks and ADB observations.
- Result: PASS with the documented Android bound-service and RF-attenuation evidence exceptions.
