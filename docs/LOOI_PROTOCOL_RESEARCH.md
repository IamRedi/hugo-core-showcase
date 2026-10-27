# LOOI Protocol Research

Research snapshot: **2026-08-24**. Sources are reverse-engineering projects, not a vendor protocol specification. No packet was sent to hardware during M0.

## Evidence labels

- **Cross-source:** independently present in at least two named repositories.
- **Source-tested:** author reports physical testing; REDI has not reproduced it.
- **Observed-only:** capture/response exists but meaning is incomplete.
- **Hypothesis:** plausible interpretation needing controlled experiments.
- **REDI-verified:** reserved for repeatable evidence produced by this project. There are no REDI-verified protocol facts yet.

## Sources inspected

| Source | Snapshot inspected | Health snapshot | License/reuse posture |
|---|---|---|---|
| [GrinZero/super-looi](https://github.com/GrinZero/super-looi) | `9985101d`, 2026-07-01 | 2 stars/2 forks/2 open issues; active-sized Expo/TS project, not archived | MIT file; candidate for later attributed reuse after provenance review |
| [andrey-tut/LOOI-Robot](https://github.com/andrey-tut/LOOI-Robot) | `aade49d`, 2026-08-12 | 9 stars/6 forks/1 open issue; focused lab, recently pushed, not archived | GPL-3.0; reference-only under current license plan |
| [CozmoCyke/LOOInteract](https://github.com/CozmoCyke/LOOInteract) | `27460eb`, 2026-03-20 | 0 stars/forks/issues; early small package, not archived | `pyproject.toml` says proprietary; no LICENSE file; no code reuse |
| [splattydoesstuff/sooperchargeforbots](https://github.com/splattydoesstuff/sooperchargeforbots) | `6b56c61`, 2026-07-07 | 39 stars/10 forks/0 issues; research notes/captures, not archived | No LICENSE file; facts/captures as research reference only |

Repository popularity and activity are weak evidence of protocol correctness. Hardware revision, firmware, activation mode, and test method matter more.

## GATT map: profile A (direct component characteristics)

Full UUIDs use the Bluetooth base suffix `-0000-1000-8000-00805f9b34fb`. `super-looi` identifies primary service `000000ff-...`; REDI must discover and confirm this, not assume it.

| Short UUID | Reported role | Evidence and contradictions | REDI status |
|---|---|---|---|
| `FED0` | Two-byte drive `[speed, turn]` | andrey reports physical verification; super-looi uses fixed two-byte direction values. Its center/turn values differ from andrey's documented signed-axis examples. | `NEEDS_HARDWARE_VERIFICATION` |
| `FED1` | One-byte head pitch | andrey: `00` up, `5A` center, `FF` down; super-looi: `00`, `87`, `FF`. Endpoints agree; center contradicts. | `NEEDS_HARDWARE_VERIFICATION` |
| `FED2` | One-byte front light | `00` off is common. andrey reports `01..FF` intensity and tests `FF`; super-looi uses `01`; soopercharge says `03` on. | `NEEDS_HARDWARE_VERIFICATION` |
| `FED5` | Notify/compatibility in profile A | andrey subscribes but reports quiet. In profile B, it is instead a write characteristic. | Profile-dependent |
| `FED8` | Battery/power read in profile A | andrey reports `[percent, externalPowerFlag]`. In profile B it carries ACK/status notifications. | Profile-dependent |
| `FED9` | Multiplexed telemetry notify | andrey has the most detailed physical mapping; super-looi treats single-byte `05/06` as dock changes; soopercharge broadly claims sensors/battery/TOF but provides less decode evidence. | `NEEDS_HARDWARE_VERIFICATION` |
| `FEDA` | Activation/handshake control | andrey writes `01`, later `03`; super-looi writes `03`, then later `8101`. Sequence materially contradicts. | Unresolved |
| `FEF0` | 20-byte handshake/time data | used by super-looi only among inspected implementations. | Unresolved |
| `FE00` | Scripted action/sequence frame | raw captures in soopercharge and used by super-looi as escape hatch; structure and firmware dependence unknown. | Experimental, disabled |
| `FF02` | “boost” drive | only community claim; andrey marks unverified. | Disabled |

## GATT map: profile B (“stable mode”)

LOOInteract reports that after pressing a touch button its tested Windows-visible endpoint exposes a different profile:

| Short UUID | Reported role | Confidence in source |
|---|---|---|
| `FED4` | read buffer/raw state, observed zeros | Observed-only |
| `FED5` | structured command write with response | Source-tested transport behavior |
| `FED6` | indicate/read, no useful payload observed | Observed-only |
| `FED7` | structured command write without response | Source-tested transport behavior |
| `FED8` | ACK/status notification/read | Source-tested response behavior |

LOOInteract did **not** see profile A on that endpoint. A captured 20-byte frame known elsewhere as `FE00` was accepted through `FED5/FED7`, but its physical effect was not proven. This may be a firmware, hardware, mode, or endpoint difference; REDI must not guess which.

## Handshake candidates

### Candidate A — andrey lab sequence

1. Connect and discover services; optionally read manufacturer `2A29`.
2. Write `FEDA=01` with response.
3. Subscribe to `FED5` and `FED9`.
4. Write `FEDA=03` with response.
5. Write neutral `FED0=0000` without response.
6. Read `FED8`, start neutral/movement heartbeat, poll battery.

### Candidate B — super-looi sequence

1. Subscribe to `FED9` and `FEF0`.
2. Write `FEDA=03` with response.
3. Write `FEF0` with 20 ASCII bytes formatted `AYYYY MM DD hh mm ss`.
4. Write `FEDA=8101` with response.

### Candidate C — official-app-assisted observation

Soopercharge states that no handshake causes disconnect after a few seconds and suggests connecting the official app first during manual exploration. That technique is useful for capture comparison but is not an independent-app solution.

REDI will implement handshake **experiments**, not declare a default, until service inventories, write acknowledgements, notification traces, survival time, firmware/app versions, and repeatability are recorded on owned hardware.

## Movement and keep-alive

andrey describes `FED0` as two signed bytes `[speed, turn]`, repeated at about 30 ms. `super-looi` repeats direction payloads every 110 ms. These are contradictory timing/encoding data points, possibly representing different tuning or reverse-engineering confidence.

Required experiments:

- Prove axis signs and mixing at very low bounds with the robot raised on a stable stand.
- Determine minimum safe refresh rate and exact dead-man behavior.
- Observe whether a neutral `0000` heartbeat is required while idle.
- Prove whether write-without-response preserves ordering under load.
- Disconnect RF/app/process during bounded motion and measure stop latency.

Until then, “heartbeat” means only repeated writes reported by sources—not a confirmed firmware watchdog. The REDI watchdog must be local and independent.

## Telemetry candidates from profile A

andrey reports these `FED9` packet types from repeated experiments:

| Pattern | Candidate meaning | Confidence claimed by source | Safety use now? |
|---|---|---|---|
| `11 01 00` | initialization complete | High | No, diagnostic only |
| `01 FL FR RL RR` | four active-low lower cliff/contact sensors | High | Not until REDI mapping and failure tests |
| `02 A B` | signed IMU-like axes | Medium | No |
| `05` / `06` | phone docked / removed | High; also in super-looi | UI hint only after verification |
| `09 v`, `0A v` | side touch press/release | High | No motion authority |
| `0B v` | external-power event | Medium-high | Status only |
| `0E X Y` | motion/attitude-like scalar | Low-medium | No odometry/control |
| `12 00 00` | front touch pulse | High | No motion authority |

Battery candidate on `FED8`: two bytes `[percentage, externalPowerFlag]`. Do not conflate this with profile B's `FED8` ACK stream.

TOF is **not confirmed** by sufficient decoded evidence in the inspected implementations. Track encoders, head-position feedback, motor current/load, charging control, precise yaw, and odometry are also unconfirmed.

## Reconnect behavior

No inspected source establishes a production-grade Android reconnect policy. Expected REDI state machine:

```text
Idle -> Scanning -> CandidateFound -> Connecting -> Discovering
     -> ProfileIdentified -> Handshaking -> Ready
Any non-ready/error/disconnect -> Stopping -> Backoff -> Scanning (when allowed)
```

Requirements include one active attempt, generation tokens to discard stale callbacks, bounded exponential backoff with jitter, user cancellation, Bluetooth/permission state handling, stop-before-cleanup, and a visible degraded state. Android's GATT cache and callback ordering need device tests.

## Safe research procedure

1. Record phone model/Android version, robot hardware/firmware, official app version, activation steps, and battery state.
2. Inventory all services, properties, descriptors, MTU, and notification behavior without writing.
3. Save redacted timestamped captures as test fixtures.
4. Select a candidate profile only by exact capability match.
5. Run handshake experiments without movement characteristics first.
6. Raise tracks off the ground, clear the area, keep a physical power intervention available, and use minimum bounded values.
7. Promote a fact only after repeatable runs and a negative/control experiment.

## Unresolved questions

- Which profile(s) the target LOOI exposes, and under what activation conditions.
- Relationship among hardware revision, firmware, profile A, and profile B.
- Correct handshake/order and whether `FEF0` time data is required.
- Drive mixing, safe minimum/maximum, refresh window, and firmware dead-man behavior.
- Correct neutral head value (`5A` versus `87`) and real position limits.
- Light intensity semantics (`01`, `03`, `FF`).
- Exact battery and external-power semantics per profile.
- Cliff sensor geometry, startup values, failure modes, and latency.
- Whether a usable TOF channel exists.
- Stop behavior on GATT disconnect, process death, RF loss, and base timeout.
- Whether MAC/name/manufacturer data is stable enough for identification (it should not be assumed).
