# Safety Model

## Safety claim

REDI aims to prevent software-requested motion outside small, explicit, time-bounded limits and to stop quickly when authority, connectivity, command freshness, sensor confidence, or operator intent is lost. This is a software risk reduction, not a certified functional-safety system. The base's undocumented firmware and sensors remain hazards.

## Authority path

```text
Action source -> Robot Core (ownership/lease) -> Safety Controller
             -> approved typed action -> LOOI driver encoder -> BLE
Telemetry ----> parser -> freshness/confidence -> Safety Controller
```

No alternative physical path is permitted. Debug raw writes are separate research builds, require explicit enablement, and are never callable by AI.

## Invariants

1. `Stop` is prioritized and allowed even when ordinary actions are rejected.
2. Drive needs an unexpired monotonic lease and fresh command.
3. Only one source owns motion at a time; manual override preempts AI/autonomy.
4. Disconnect, permission loss, service shutdown, app background policy, watchdog expiry, or stale telemetry transitions to stop/degraded state.
5. Values are finite, normalized, rate-limited, and clamped below hardware maxima.
6. Unknown GATT profiles and unknown sensor states cannot authorize movement.
7. Autonomous actions have duration, distance/time proxy, speed, and cancellation bounds.
8. E-stop latches until a deliberate local reset while stationary; an AI/tool cannot reset it.
9. Startup and shutdown are non-moving states.
10. Logs capture decisions and reasons without sensitive content.

## Controls

| Control | Required behavior |
|---|---|
| Emergency stop | High-priority local UI and service action; cancel producers, clear queue, repeatedly attempt neutral/stop, latch state |
| Command timeout | Reject commands older than configured monotonic age |
| Dead-man lease | Motion refresh required; lease expiry schedules stop independently of producer |
| Watchdog | Dedicated deterministic scheduler observes command/transport/telemetry freshness |
| BLE disconnect stop | Attempt stop before intentional disconnect; on unexpected loss, immediately revoke authority and rely on proven base timeout only as defense-in-depth |
| Speed/acceleration bounds | Conservative initial values; rate-limited changes; per-profile hardware calibration |
| Sensor-aware movement | Only verified sensors with fresh, valid samples may enable directional gates; unknown/stale is unsafe |
| Manual override | Human input preempts and cancels autonomous/AI leases |
| Bounded autonomy | Allowlisted skill, explicit envelope, maximum duration, visible state, immediate cancel |
| Safe startup/shutdown | Start neutral; no replayed command; stop and drain before resource release |

## Threats and failures

- AI hallucination or prompt injection requests dangerous tools.
- Touch event is stuck; UI/process is killed; foreground service is restricted.
- BLE callbacks arrive late/out of order or from an old connection generation.
- Notification parsing accepts malformed/truncated values.
- Cliff sensor is absent, inverted, dirty, stale, or mis-mapped.
- A valid command is replayed after reconnect.
- Queue congestion delays stop behind ordinary writes.
- Wall clock changes make a stale command appear fresh.

Mitigations are typed schemas, allowlists, monotonic timestamps, generation IDs, strict parsers, bounded queues with stop priority, independent watchdogs, conservative unknown-state policy, and destructive fault-injection tests.

## Current foundation limitations

`DefaultSafetyController` is only a pure policy seed. It currently checks connectivity, an e-stop latch, freshness, finite drive values, configured magnitude limits, and a coarse sensor-health gate. It does **not** yet implement ownership, sequence replay rejection, acceleration limiting, directional sensor gates, watchdog scheduling, stop delivery, or persistent e-stop state. Those are separate backlog tasks.

## Hardware assumptions requiring verification

- A neutral `FED0=0000` actually stops the target profile.
- Repeated command loss causes the base to stop within an acceptable measured interval.
- Reported `FED9` cliff/contact mapping exists and is active-low on target hardware.
- BLE disconnect cannot leave a long-running scripted `FE00` action active.
- Head and track limits are firmware-bounded.

Until proven, operate raised/off-floor in a clear area with immediate physical power access.
## Event-based lower-contact freshness during manual operation

Profile-A FED9 lower-contact `01` observations are generation-scoped transition events rather than a periodic stationary heartbeat. The latest valid event remains authoritative for manual directional gating within its unchanged connection generation, and each later valid event replaces it immediately. Malformed contact data, disconnect, or generation rollover invalidates the state. Release/cancel, local e-stop, transport loss, and the independent 100 ms command dead-man retain separate neutral-stop behavior. This event-state rule is manual-only and does not grant motion authority to autonomous producers.
### T032 latched lower-contact stop

During manual motion, a valid generation-owned FED9 lower-contact hazard is a latched stop event, not a temporary pause. The active motion queue is cancelled, repeated neutral is requested, E-stop is latched, and the UI press token is invalidated. A later safe observation, E-stop reset, or manual re-arm cannot replay or resume the prior direction; movement requires a new press. Transport loss, background dead-man expiry, and process death remain independent stop paths.
