# Repeatable Hardware Verification

Each run creates a dated evidence record (future location: `docs/hardware-runs/<run-id>.md`). Do not commit stable device addresses or personal media.

## Run metadata

- Task ID and app commit/APK hash
- Operator/date/location class
- Phone model, Android version, app permission state
- LOOI hardware revision/serial alias, firmware if discoverable, official app version
- Base/phone battery and external-power state
- Test fixture/profile ID and expected characteristic inventory
- Physical setup photo reference (private if necessary)

## Preflight

- [ ] Clear a safe area; keep people, pets, stairs, edges, cables, and fragile items away.
- [ ] For first actuation, raise tracks securely off the surface.
- [ ] Physical power intervention is reachable; second observer used for risky tests.
- [ ] Official app is closed unless a capture experiment explicitly requires it.
- [ ] App starts neutral; e-stop UI works without a BLE connection.
- [ ] Logs/captures are enabled and privacy-redacted.

## Stage A — read-only inventory

- [ ] Scan/identify without relying solely on name or MAC.
- [ ] Record advertisements, services, characteristic properties/descriptors, MTU, and activation steps.
- [ ] Repeat power cycle and Bluetooth cycle three times.
- [ ] Confirm profile selector refuses unknown inventories.

## Stage B — handshake and telemetry (no movement)

- [ ] Execute one candidate handshake with timestamps and write results.
- [ ] Remain connected for 10 minutes; record spontaneous notifications.
- [ ] Verify battery against a second observation and external-power changes.
- [ ] Exercise dock/touch/sensor candidates one at a time with control runs.
- [ ] Disconnect/reconnect ten times; record success, time, stale callbacks, and leaks.

## Stage C — raised-wheel actuation

- [ ] T025 profile-A `FED0`: start with candidate neutral `0000`; record write result and observed track state.
- [ ] T025 profile-A `FED0`: test signed cap `16` at most 250 ms per direction, with the base secured and wheels raised.
- [ ] T025 profile-A `FED0`: prove or reject candidate linear/turn signs and mixing before changing polarity, cap, or floor-testing.
- [ ] Confirm stop payload first.
- [ ] Test minimum forward/back/left/right values for at most 250 ms each.
- [ ] Confirm sign/mixing and that both tracks stop.
- [ ] Test head endpoints slowly and determine safe center.
- [ ] Test light off and minimum value before higher values.
- [ ] Trigger command expiry, UI release, e-stop, Bluetooth-off, RF loss, app background, service stop, and process termination; measure stop latency.

## Stage D — floor manual control

- [ ] Use conservative calibrated limits on a level, non-edge surface.
- [ ] Press-and-hold control stops on release/cancel/multi-touch/lost focus.
- [ ] Manual override preempts every non-manual source.
- [ ] Verified cliff gates stop the correct direction; sensor staleness stops motion.
- [ ] Run 15 minutes with connection disturbances; no uncontrolled motion.

## Evidence/result

For every check record `PASS`, `FAIL`, `NOT_RUN`, or `INCONCLUSIVE`, measured values, log/capture reference, and anomalies. A mocked or raised-wheel result does not pass floor behavior. Failed stop behavior blocks further actuation and sets the task `BLOCKED` or `NEEDS_ARCHITECTURE_DECISION` as appropriate.
