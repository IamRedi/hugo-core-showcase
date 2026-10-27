# Architecture

## System shape

REDI uses ports and adapters around a deterministic robot domain. The Android app owns real-time local safety and hardware lifecycle; a separate gateway owns production AI credentials and provider integrations.

```text
 Android application

  Compose UI   Voice   Vision   Memory   Personality
       \         |       |        |         /
        +---- Interaction / Agent / Skills --------+
                              | typed proposals     |
                              v                     |
                     +----------------+             |
                     |   Robot Core   |<------------+
                     | state/leases   |
                     +-------+--------+
                             | ActionRequest
                             v
                     +----------------+
 telemetry/freshness | Safety Control | e-stop/watchdog/manual override
          +----------+-------+--------+
          |                  | approved typed action
          |                  v
          |          +----------------+
          +----------| RobotBody port | body-neutral domain boundary
                     +-------+--------+
                             |
                 +-----------+-------------+
                 | LOOI adapter (replaceable)
                 | profile -> encoder -> GATT queue |
                 +-----------+---------------------+
                             |
                     LOOI BLE hardware

 Android conversation client <-- authenticated contract --> AI gateway
                                                      provider adapters
```

## Layers and dependency rules

### Domain

Pure Kotlin contracts and policy: `RobotAction`, `ActionRequest`, `RobotBody`, robot state, motion authority, safety decisions, perception/memory/tool contracts. It imports no Android, Bluetooth, database, AI SDK, or Compose types.

### Application/orchestration

Use cases coordinate domain ports: connect robot, acquire manual lease, evaluate/execute action, run skill, build conversation context, validate tool proposal. It owns no concrete GATT or provider implementation.

### Adapters

- LOOI body: Android BLE scanner/GATT, profile detection, handshake, encoders, strict telemetry parsers.
- Platform: foreground/companion service, audio, camera, phone sensors, secure storage.
- Persistence: Room/encrypted memory repository and indexes.
- Gateway: authenticated provider-neutral network client.
- UI: Compose screens observing immutable state and sending semantic intents.

Dependency flow points inward. Raw characteristic UUIDs and bytes never leave the LOOI adapter. Provider-specific model names and response objects never leave gateway adapters.

## Physical action flow

1. Source creates typed semantic request with source, sequence/epoch, and monotonic timestamp.
2. Robot Core validates current motion owner/lease and assigns an execution envelope.
3. Safety Controller evaluates e-stop, connection/profile readiness, freshness, finite/bounded values, watchdog, sensor confidence/direction, acceleration and autonomy bounds.
4. Rejected request produces an auditable reason and no body write.
5. Approved action reaches `RobotBody`; the selected profile encoder produces allowlisted bytes.
6. Serialized GATT queue delivers it; stop has priority.
7. Observed callbacks/telemetry update state. A write callback alone is not physical-success proof.

AI, UI, voice, skills, and behaviors all use this same flow. There is no privileged AI or debug path.

## Robot transport state

```text
Idle
  -> Scanning -> CandidateFound -> Connecting -> Discovering
  -> ProfileIdentified -> Handshaking -> Ready

Any permission/Bluetooth/error/disconnect/cancel:
  -> Stopping -> Disconnected -> Backoff (if policy permits) -> Scanning
```

Every attempt has a generation ID. Late callbacks from older generations are discarded. Entering `Ready` requires a complete allowlisted profile and successful current-generation handshake. Reconnect never replays motion or restores a lease.

LOOI protocol profiles are versioned capability objects. Profile A and reported profile B remain separate. Unknown or ambiguous profiles are read-only research targets; an endpoint-scoped diagnostic capture may record candidate-profile evidence without entering protocol-ready or authorizing control.

## Concurrency and timing

- Kotlin coroutines use explicit lifecycle-owned scopes and structured cancellation.
- One serialized queue owns GATT operations; callbacks are correlated to generation and operation.
- Safety time uses injected monotonic elapsed time, never wall clock.
- A dedicated watchdog observes leases and transport freshness independently of action producers.
- State is immutable and reduced from events. UI observes state; it does not own connection or watchdog lifecycle.

## Process/background model

A visible, user-controlled connected-device service will own a connection that must survive activity recreation. Exact Companion Device/foreground-service policy is an M1 task and must follow the targeted Android version. Camera and microphone have separate while-in-use lifecycles and are not kept alive merely because BLE is connected. Process death revokes motion; persisted connection hints do not persist action authority.

## AI and tool boundary

The Android app knows gateway contracts, not provider SDKs. The gateway holds production provider credentials, selects models by task/policy, reports usage, and supports streaming cancellation. Local tools have versioned schemas and risk classes. AI proposals are untrusted inputs; strict validation precedes skill execution. A semantic robot tool can request a bounded action but cannot access BLE bytes, safety configuration, e-stop reset, or unbounded loops.

## Memory boundary

Canonical personal memory is structured, provenance-bearing, correctable/deletable, and local-first. Model-created memories start as candidates. Retrieval returns a small privacy-filtered `MemoryContextPacket`; conversation code cannot scan the database or send full history. Embeddings and summaries are derived data with source/version links.

## Voice and vision independence

Voice is activation-gated: local activation/VAD, swappable STT/realtime/TTS, barge-in cancellation, and local handling for simple commands. Continuous cloud microphone streaming is not the default.

Vision owns camera sessions and local frame processing. Detection/tracking events contain source, model version, confidence, coordinate system, and age. Optional cloud vision uses a deliberately selected minimized frame. Perception is evidence input, not direct motor authority.

## Suggested package evolution

```text
io.redi.robot
├── core              # actions, state, authority, use cases
├── safety            # policies, watchdog, e-stop
├── body
│   └── looi          # profiles, encoders, parsers, BLE adapter
├── interaction       # local intent and conversation orchestration
├── voice             # capture and provider-neutral speech ports
├── vision            # camera, detection, tracking
├── perception        # shared observation contracts
├── memory            # domain, repository ports, retrieval
├── skills            # typed registry/executor
├── behavior          # bounded multi-step behaviors
├── ai                # gateway client and proposal validation
└── feature           # Compose screens/view models
```

This may become multiple Gradle modules when package dependency tests or build scale justify it. M0 deliberately avoids premature module complexity.

## Deployment boundaries

| Component | Secrets | Network required | May directly move robot? |
|---|---|---:|---:|
| Android domain/safety/body | No provider secret | No | Body only after local approval |
| Android UI/voice/vision/memory | Short-lived app auth at most | Optional | No |
| AI gateway | Provider/service secrets | Yes for cloud providers | No |
| AI provider | Provider-managed | Yes | No |

## Observability

Use structured redacted events with correlation ID, connection generation, action source, safety decision reason, profile ID, and monotonic timing. Never log tokens, raw personal media, memory bodies, transcripts by default, stable addresses, or secrets. Hardware evidence retains raw protocol bytes only under explicit research storage policy.

## Current implementation boundary

M0 implements only the app shell, domain contracts, and a small pure safety-policy seed. The package diagram describes intended seams, not completed features. `SPARK_TASKS.md` is authoritative for sequencing.
