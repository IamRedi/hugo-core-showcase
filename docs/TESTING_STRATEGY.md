# Testing Strategy

## Evidence levels

Never collapse these into one “tested” label:

1. **Unit tested:** pure code on JVM; no Android/BLE hardware claim.
2. **Integration tested:** components/adapters together using fakes, replay fixtures, or Android test APIs.
3. **Android-device tested:** physical Android phone behavior, including permissions/lifecycle; may use a BLE simulator.
4. **Real-LOOI tested:** named phone plus named LOOI hardware/firmware under the hardware checklist.

Task completion evidence states every applicable level and marks absent levels pending.

## Test pyramid

- Pure JVM: encoders/parsers, state transitions, safety policy, leases, timeouts, retrieval ranking, tool validation.
- Property/fuzz: arbitrary bytes and truncated telemetry never crash or create unsafe state; numeric inputs reject NaN/infinity/out-of-range.
- Integration: fake GATT callback scripts, connection generations, notification ordering, reconnect/backoff, stop-priority queue, gateway contracts, database migrations.
- Instrumented Android: runtime permissions across API levels, foreground service, Bluetooth toggles, lifecycle/process recreation, Room/Keystore, audio focus, camera ownership.
- Hardware: read-only profile inventory first, then handshake, telemetry, raised-wheel bounded actuation, fault injection, manual-drive acceptance.

## Determinism

Inject monotonic clock, dispatcher, scheduler, random/backoff source, transport, and storage. Tests use virtual time. Protocol fixtures are immutable, redacted, versioned binary/hex data with provenance metadata.

## Safety-critical cases

- stop preempts a full command queue;
- stale/replayed command rejected after reconnect;
- lease/watchdog expiry stops without producer cooperation;
- disconnect, Bluetooth-off, permission revoke, activity stop, service kill, and process restart revoke authority;
- sensor absent/stale/malformed/contradictory prevents motion as designed;
- e-stop latches and cannot be reset remotely or by AI;
- invalid floating-point and boundary values are rejected;
- old GATT callbacks cannot transition a new connection.

## CI gates

Per change: unit tests and lint. Before merging transport/control work: integration and debug assembly. Before milestone acceptance: instrumented tests on the supported API matrix plus the hardware checklist. Release adds dependency/license scan, secret scan, static analysis, signed-artifact checks, and privacy/security review.

Current command:

```text
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Coverage philosophy

Coverage percentage is diagnostic, not proof. State-machine transitions, safety rejection reasons, parser failure paths, and protocol encoder golden vectors require explicit tests. UI snapshots do not substitute for behavior tests.

## Test data

- No personal recordings, faces, tokens, stable BLE addresses, or memory exports in Git.
- Synthetic audio/images/memory by default.
- Hardware captures are redacted, labeled with consent and retention, and stored only when licensing/privacy permits.
