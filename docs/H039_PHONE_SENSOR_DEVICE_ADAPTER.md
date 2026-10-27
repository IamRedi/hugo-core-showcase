# H039 Phone Sensor DeviceAdapter

## Record

- Task: `H039 - Phone sensor DeviceAdapter`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Completed: 2026-08-31
- Prerequisites: H028 and H036-H038, through H038 commit
  `6b23311b7fe6c6303405b7bcfa537055db6d0f1b`
- Production runtime impact: none; H039 adds an unwired source adapter and Android backend
- Hardware claim: Samsung phone sensor acquisition and release only; no LOOI claim

## Adapter and platform boundary

`PhoneSensorDeviceAdapter` owns the project-domain lifecycle. It accepts a monotonically increasing
generation, an explicit non-empty sensor-kind set, one opaque H036 source reference, and a bounded
freshness window. Its backend port contains no Android type. The Android implementation remains in
`platform/device/phone/sensors`, owns `SensorManager` and `SensorEventListener`, copies callback
values immediately, and translates only validated project-domain samples across the boundary.

H039 supports the standard phone accelerometer, ambient-light sensor, and proximity sensor. The
adapter reports requested kinds as disjoint `activeKinds` and `unavailableKinds`; one absent sensor
does not invent data and does not prevent another requested sensor from operating. If none is
available, registration fails explicitly. H039 adds no manifest permission because these standard
sensors require none in this scope.

The output keys are:

- `phone.sensor.acceleration_x_mps2`;
- `phone.sensor.acceleration_y_mps2`;
- `phone.sensor.acceleration_z_mps2`;
- `phone.sensor.ambient_light_lux`; and
- `phone.sensor.proximity_cm`.

They are bounded sensor observations, not position, navigation, velocity, heading, distance,
activity recognition, user presence, safety, or physical-state conclusions.

## Lifecycle, generation, and release

Lifecycle is explicit as `STOPPED`, `STARTING`, `ACTIVE`, or `FAILED`. A start generation must be
strictly newer than every generation already owned by the adapter. Replacing a session closes the
previous registration first. Old-generation callbacks, inactive sensor callbacks, reused or
decreasing sequences, decreasing timestamps, future samples, and samples stale at the exact expiry
boundary cannot emit observations.

`stop` requires the exact current generation; an older or future stop is typed and rejected. A
repeated exact stop and repeated `close` are idempotent. Backend, sink, timestamp, and resource
release failures transition fail closed. A release failure prevents a new generation from opening,
so an uncertain old platform registration can never be treated as a successfully replaced source.

Snapshots are immutable and retain only lifecycle metadata, active/unavailable kinds, last accepted
timestamps, observation count, and typed failure. They retain no sensor values. Android
registrations and raw callback arrays remain ephemeral and are not persisted.

## H036/H037 evidence semantics

Each accepted sample becomes deterministic H036 evidence with:

- source type `phone.sensor`;
- a caller-owned opaque run-local source reference;
- a semantic key and canonical decimal value bounded to six fractional digits;
- Android sensor time translated to the shared monotonic millisecond clock;
- an exclusive expiry calculated from the explicit session freshness window;
- accuracy mapped to source-confidence permille; and
- `SENSITIVE` privacy classification.

The adapter sends each sample projection as one complete batch. H037 accepts it as ordinary
ephemeral source evidence, applies its existing privacy and freshness policy, and excludes it at the
exact expiry boundary. Observation IDs are deterministic from generation, kind, sequence, and key.
They are event correlation only and are not phone, installation, HUGO, user, or provider identity.

Phone sensor availability and observations grant no navigation claim, trust, authorization,
permission, active-body selection, safety approval, execution authority, or provider/runtime
availability. H039 performs no fusion, persistence, logging pipeline, planning, or capability
execution.

## Android device evidence

Physical verification ran on 2026-08-31 with an authorized Samsung `SM-S928B` reported by
`adb devices -l` in state `device`.

The prepared debug and androidTest APKs both installed successfully. The bounded instrumented class
`AndroidPhoneSensorBackendInstrumentedTest` ran one test and returned `OK (1 test)` in 0.562 s. Its
redacted device trace recorded:

```text
model=SM-S928B
activeKinds=ACCELEROMETER,AMBIENT_LIGHT,PROXIMITY
unavailableKinds=<none>
batches=2
observations=4
source=redacted
values=redacted
release=confirmed
```

At least one genuine accelerometer batch was required before acceptance. Every captured observation
had the expected opaque source, `SENSITIVE` classification, and was fresh on the monotonic clock.
After exact-generation stop, the test waited 500 ms and confirmed that the observation count did not
change. The trace exposed sensor kind names and counts only; it logged no sensor values, endpoint,
account, phone identifier, transport address, provider identity, or LOOI data.

## Test and completion evidence

`PhoneSensorDeviceAdapterTest` covers exact availability partitioning, accelerometer/light/proximity
projection, canonical units and values, H036 source/privacy/confidence/freshness, H037 inclusion and
exact expiry, deterministic event IDs, generation replacement, stale callbacks, order/future/stale
rejection, stop/close idempotence, backend and sink failure, resource-release failure, bounded input,
immutable no-value snapshots, non-authority semantics, and pure-package leakage guards.

Final completion checks:

- HUGO V2 documentation guard: PASS
- First Life documentation/order guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H036/H037/H039 JVM tests: PASS
- H039 Samsung instrumented test: PASS, `OK (1 test)`
- full `testDebugUnitTest`: PASS, 694 tests in 99 suites; zero failures/errors/skips
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `assembleDebugAndroidTest`: PASS
- `git diff --check`: PASS
- pure-domain leakage scan: PASS
- production/LOOI/T032 impact: none; no existing runtime composition was changed

## Next boundary

H040 is complete and H041 is eligible. H039 itself adds no ServiceConnector, credential storage, source
fusion, navigation, persistence, UI, AI, memory, provider connection, LOOI behavior, safety policy,
or physical control.
