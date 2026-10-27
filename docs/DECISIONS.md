# Architecture Decision Log

Decisions are append-only. Superseded entries remain for history.

## ADR-001 — Native Kotlin and Jetpack Compose

**Status:** Accepted, 2026-08-24

**Context:** The app's hardest requirements are reliable BLE lifecycle control, foreground/background behavior, camera/microphone ownership, deterministic timing, and long-lived maintainability. The primary target is Android, not cross-platform UI reuse.

**Options evaluated:**

| Option | Strengths | Costs/risks | Result |
|---|---|---|---|
| React Native + Expo development build | Fast UI iteration; strong JS/TS tooling; native libraries possible after rebuilding the client | Native rebuilds for BLE/audio changes; lifecycle and safety split across JS/native boundary; Expo Go unsuitable | Rejected for core app |
| React Native + native Android modules | Good UI velocity with direct Kotlin escape hatch | Two runtimes and bridge contracts; most critical subsystems would become native modules; larger debugging surface | Rejected |
| Kotlin + Jetpack Compose | Direct Android BLE/services/lifecycle APIs; structured concurrency; one runtime for safety and transport; excellent test/debug tooling | Android-only; UI iteration is less web-like | Selected |

VS Code remains the default editor. Android Studio is supporting tooling for SDKs, emulator/device debugging, profiling, signing, and release builds.

## ADR-002 — Hexagonal body abstraction

**Status:** Accepted, 2026-08-24

The domain exposes `RobotBody` and typed `RobotAction`; a LOOI driver is an adapter. AI, memory, voice, vision, UI, and skills depend on domain contracts, never GATT UUIDs. Replacing the body requires another adapter plus capability mapping.

## ADR-003 — Deterministic local physical authority

**Status:** Accepted, 2026-08-24

All physical requests—including manual and system requests—pass through Robot Core and the Safety Controller. Stop is always representable and prioritized. AI never emits raw packets and cannot change limits, watchdogs, or e-stop state.

## ADR-004 — Capability-based LOOI profiles

**Status:** Accepted, 2026-08-24

Do not assume one GATT map. Discover services/characteristics and select only a versioned, allowlisted profile with explicit evidence. Unknown or ambiguous profiles permit bounded, endpoint-scoped, read-only telemetry capture when the complete candidate capability set and one exact endpoint are observed, but this does not select a control profile or authorize movement.

## ADR-005 — AI gateway security boundary

**Status:** Accepted, 2026-08-24

Production provider credentials live in a backend gateway. The client uses authenticated, short-lived, scoped access. Provider adapters and task-to-model policy are gateway-side; deterministic local commands do not require cloud access.

## ADR-006 — Structured memory without retraining

**Status:** Accepted, 2026-08-24

Personalization uses structured, provenance-bearing records plus retrieval and summaries. Model-created items enter a candidate state and require deterministic validation or user confirmation before becoming trusted facts.

## ADR-007 — Monotonic time for safety

**Status:** Accepted, 2026-08-24

Command age, leases, watchdogs, and backoff use Android elapsed monotonic time. Wall-clock timestamps are stored only for human provenance/audit. Clock changes must not extend motion authority.

## ADR-008 — Protocol code reuse policy

**Status:** Accepted, 2026-08-24

No upstream implementation code is copied in M0. MIT code may be considered later with notices and design review. GPL-3.0 code is reference-only unless the project intentionally adopts GPL-3.0. Repositories without a grant of license are facts/reference-only; code is not copied.

## ADR-009 — Outbound project license is undecided

**Status:** Accepted, 2026-08-24

No root `LICENSE` is added until the owner chooses an outbound license and distribution intent. By default, this is not a grant to copy or redistribute REDI code. Any later open-source choice must recheck third-party dependencies, protocol material, branding, reverse-engineering law, and notice obligations.

## ADR-010 - Active-session GATT operation transport

**Status:** Accepted, 2026-08-24

Read/write/descriptor operations use a project-owned `GattOperationTransport` implementation owned by
the active Android GATT session. The implementation stays inside the platform transport boundary and is
consumed through `SerializedGattOperationQueue`; no feature, handshake runner, or UI receives a
`BluetoothGatt` or `BluetoothDevice`. This preserves one in-flight operation, generation correlation,
timeout/cancellation policy, and stop priority. It is the only permitted path for T016 handshake
experiments after connection/service discovery.
## ADR: Generation-scoped event contact state with motion-time FED9 liveness

- **Status:** Accepted for T032 hardware acceptance.
- **Date:** 2026-08-24.
- **Context:** Real LOOI evidence shows profile-A FED9 lower-contact `01` packets are event-based. While stationary, otherwise valid FED9 traffic can pause longer than the T031 250 ms observation-age limit, making safe manual arming impossible without weakening the motion-time fault response.
- **Decision:** A valid lower-contact observation is authoritative for manual directional gating throughout its unchanged connection generation because the firmware reports contact transitions as events rather than a periodic heartbeat. Every later valid `01` event immediately replaces that state; malformed contact data, disconnect, or generation change invalidates it. Release/cancel, local e-stop, transport loss, and the independent 100 ms command dead-man continue to request neutral stop. No autonomous source receives this event-state allowance.
- **Consequences:** Stationary FED9 silence does not block manual refresh or masquerade as a missing heartbeat. Directional hazards remain fail-closed from the latest event, while command-producer and transport failures retain independent stop paths. T032 must verify event transitions and all stop paths on real hardware.
## ADR: T032 Android fault-injection equivalence and evidence limits

- **Status:** Accepted, 2026-08-25.
- **Context:** Android keeps `ConnectedDeviceService` alive while the foreground Activity remains bound, and `adb shell am stopservice` was rejected during the physical run. A separately measured RF attenuation setup was not available.
- **Decision:** T032 accepts successful `adb shell am force-stop io.redi.robot` as combined process/service-death coverage after confirming that no service record remained and the real LOOI stopped. Bluetooth adapter-off, explicit GATT disconnect, background dead-man expiry, and process death provide distinct transport/control-loss coverage. Bluetooth adapter-off is recorded only as transport-link loss and must not be relabeled as measured RF attenuation.
- **Evidence interpretation:** ADB command completion times are not physical motor-stop measurements. The run record reports them separately from operator-observed physical stop behavior and preserves the approximately 100 ms background/dead-man observation. No RF range, RF latency, or isolated bound-service destruction capability is claimed.
- **Safety consequence:** Any lower-contact hazard during active motion cancels queued motion, sends repeated neutral, latches E-stop, and invalidates the UI press token. Returning to a safe surface or resetting/rearming cannot resume the previous command; a new physical press is mandatory.

## ADR-011 - HUGO universal personal brain runtime

**Status:** Accepted, 2026-08-25

The product identity is HUGO, a portable personal brain runtime independent of host, body, device, service, and AI provider. LOOI becomes Reference Body Adapter #1. T001-T032 evidence remains valid and is a migration invariant.

## ADR-012 - CapabilityProvider is broader than RobotBody

**Status:** Accepted, 2026-08-25

Body adapters, device adapters, and service connectors share capability, trust, lifecycle, and audit contracts but retain distinct roles. Services are not modeled as physical devices. `RobotBody` remains a compatible legacy body port during migration.

## ADR-013 - Parallel incremental migration

**Status:** Accepted, 2026-08-25

Universal contracts are added beside the verified stack. No package-wide rename, LOOI protocol rewrite, or immediate module split is authorized. The legacy T032 runtime remains until facade equivalence and real-hardware regression are proven.

## ADR-014 - Manifests describe claims, not authority

**Status:** Accepted, 2026-08-25

Provider/body/device/service manifests are versioned, provenance-bearing compatibility descriptions. They cannot establish trust, grant permission, install code, select themselves, or authorize control.

## ADR-015 - Discovery, trust, permission, approval, and safety are separate

**Status:** Accepted, 2026-08-25

Discovery is never authorization. Capability dispatch requires provider identity/compatibility, trust, scoped grant, applicable approval, and provider policy. Physical bodies additionally retain deterministic local safety authority.

## ADR-016 - Hugo identity and memory are body-independent

**Status:** Accepted, 2026-08-25

Hugo identity, personality, user relationship, and personal memory persist across hosts and bodies. Device/body memory remains provider-instance and version scoped. Active body is expiring context, not identity.

## ADR-017 - Runtime and Developer Mode are strongly separated

**Status:** Accepted, 2026-08-25

Runtime records missing capabilities but cannot research, edit, compile, install, enable, or deploy production code. Adapter development occurs in an explicit sandbox with provenance, tests, verification, user review, and separate install/enable approvals.

## ADR-018 - Connection providers are lifecycle-neutral, not packet-universal

**Status:** Accepted, 2026-08-25

Generic connection contracts cover endpoint, session, generation, health, cancellation, and typed failure. BLE GATT, HTTP, MQTT, serial, ROS, and vendor operation details remain inside implementations; there is no universal raw packet API.

## ADR-019 - Google Calendar events read-only is the H043 feasibility service

**Status:** Accepted, 2026-09-01

H043 uses an isolated Google Calendar adapter sandbox to test one authenticated service connector.
It requests exactly `https://www.googleapis.com/auth/calendar.events.readonly` and permits only the
Google Calendar `events.list` read operation. The sandbox retains no account identifier, event
payload, refresh token, endpoint detail, or authorization token in HUGO contracts or diagnostics;
access-token bytes remain behind H041 and are exposed only for a bounded adapter callback.

Google Calendar was selected because its official API supplies a narrow events-only read scope, a
documented GET-only list operation, and explicit OAuth revocation. Gmail was rejected because email
content and sending create a materially larger privacy/action surface; Drive was rejected because
general file content is broader than the feasibility question; an unauthenticated public API was
rejected because it would not exercise H041 or connect/read/revoke behavior. This decision grants no
trust, H014 authorization, provider registration, background synchronization, or production rollout.
Source material: [Calendar OAuth scopes](https://developers.google.com/workspace/calendar/api/auth),
[Events list](https://developers.google.com/workspace/calendar/api/v3/reference/events/list), and
[Android AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient).
