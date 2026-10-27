# HUGO Future Concepts Register

## Purpose and status

This register preserves agreed product and architecture direction without turning it into current runtime behavior. Architecture V2 remains authoritative. Entries marked `BACKLOG REVIEW` need later milestone/task review; they do not renumber H001-H080 or authorize implementation.

The common boundaries remain those in [HUGO Vision](HUGO_VISION.md), [Architecture V2](ARCHITECTURE_V2.md), [Universal Capability Model](UNIVERSAL_CAPABILITY_MODEL.md), [Adapter Architecture](ADAPTER_ARCHITECTURE.md), [Trust and Permissions](TRUST_AND_PERMISSIONS.md), [Memory Architecture V2](MEMORY_ARCHITECTURE_V2.md), [Context Engine](CONTEXT_ENGINE.md), and [Developer Mode](DEVELOPER_MODE.md).

## 1. Hugo has no permanent body

Hugo identity is independent of phone, PC, mini-PC, robot, vehicle, device, host, service, and cloud AI provider. Moving between authorized hosts or bodies preserves identity, personality, personal memory, relationships, preferences, routines, and work context. Active host/body/provider associations are expiring runtime context. LOOI remains Reference Body Adapter #1.

Mapping: H003, H016, H038, H058-H067.

## 2. Embodiment and presence state

Discovery, proximity, connection, remote control, physical mounting, and active-body selection are distinct evidence-bearing states. Future vocabulary may include `DISCOVERED`, `NEARBY`, `CONNECTED`, `REMOTE_CONTROLLED`, `ATTACHED` or `MOUNTED`, `ACTIVE_BODY`, `DETACHED`, and `UNAVAILABLE`, but the final state model belongs to its implementing task.

BLE connection alone never proves attachment. LOOI attachment/phone-stick, charging, BLE, and related signals remain unverified mounting evidence unless a controlled hardware task proves their semantics.

Mapping: H009, H016, H021-H022, H027, H038. `BACKLOG REVIEW`: explicit attachment/mounting evidence and embodiment-state contract are not currently named as standalone tasks.

## 3. Standalone LOOI product

The verified LOOI protocol/safety implementation should remain one shared implementation reusable by the HUGO LOOI Body Adapter and, optionally, a professional standalone LOOI Android application. The standalone product must be possible without HUGO memory, AI, services, or universal runtime. Protocol duplication is prohibited.

Mapping: H017-H020, H028, H035. `BACKLOG REVIEW`: standalone application extraction, packaging, and acceptance are not explicitly scheduled.

## 4. Reference Adapter acceptance

HUGO Core must contain no LOOI protocol knowledge. Given only an authorized LOOI adapter, manifest, permissions, and verified connection, generic contracts must discover and operate its declared capabilities. A future ESP32 robot should become Reference Adapter #2. Supporting both without HUGO Core changes is the portability proof.

Mapping: H017-H020, H028-H035. `BACKLOG REVIEW`: Reference Adapter #2 implementation and two-body portability acceptance are intentionally not yet scheduled.

## 5. Device-owned low-level intelligence

Bodies/devices should own low-level physical control where possible. An ESP32 robot should own motor PWM, sensor loops, local emergency behavior, camera endpoint, and health, while exposing semantic capabilities such as move, turn, stop, observe, distance, camera, and battery. HUGO never plans against pins or raw transport packets.

Mapping: H006-H008, H011-H013, H029-H031. Device firmware implementation remains partner/provider work rather than HUGO Core work.

## 6. Universal manifest translation

Devices need not publish native HUGO manifests. The supported path is vendor API/SDK/protocol -> adapter -> HUGO manifest -> Capability Registry. Existing trustworthy machine-readable descriptions should be reused; otherwise the adapter translates evidence into standard claims. Translation never grants authority.

Mapping: H010-H012, H023-H025, H029.

## 7. Minimum-human-setup integration

The desired governed flow is discover -> identify -> find official interface -> match adapter -> resolve manifest -> negotiate capabilities -> determine permissions -> request authorization -> connect -> verify read-only behavior -> enable approved capabilities -> learn provider-specific behavior.

The UX target is: "I found device X. It can provide A/B/C. Allow?" OAuth, pairing, owner codes, physical confirmation, legal restrictions, and user authorization are never bypassed.

Mapping: H021-H034, H073-H077. `BACKLOG REVIEW`: end-to-end minimum-setup UX acceptance is broader than any one current task.

## 8. Unknown-device Developer Mode

Unknown runtime devices may create redacted development needs but never trigger uncontrolled experiments. Research, legally usable source review, proposals, implementation, tests, hardware verification, approval, installation, and enablement occur in the separate Developer Mode sandbox. Production runtime remains immutable.

Mapping: H073-H077.

## 9. Aggregator connectors

HUGO should prefer authorized aggregators where they safely normalize many providers. Home Assistant is a high-value example; future options may include REDI CONTROL, vehicle platforms, ROS/ROS2, Matter ecosystems, and other device hubs. Aggregators remain ServiceConnectors or adapter-backed providers with scoped trust and permissions.

Mapping: H040-H045, H068-H072. `BACKLOG REVIEW`: no concrete Home Assistant, REDI CONTROL, ROS, Matter, or vehicle connector is currently committed.

## 10. Capability-driven universal dashboard

Controls should compose from negotiated capabilities rather than defaulting to one custom screen per model. Examples include universal drive, light, TV, camera, and status components. A provider selector retargets a compatible control only after authorization and capability validation. Device-specific UI is exceptional.

Mapping: H042, H068-H069. `BACKLOG REVIEW`: capability-to-UI composition schemas and universal controller acceptance need explicit later scope.

## 11. Context-aware dashboards and workspaces

Home, car, and work contexts may select different authorized providers and compose available UI/actions from context + selected provider + manifest + permissions. Switching providers should reuse generic workspaces where capabilities are compatible; context never grants authority.

Mapping: H036-H045, H068-H069. `BACKLOG REVIEW`: reusable vehicle/workspace composition is not explicitly scheduled.

## 12. Device and body memory

Calibration, latency, reconnect behavior, quirks, preferred parameters, firmware behavior, physical limits, and successful strategies remain provider-instance and version scoped. They never silently contaminate another instance or class.

Mapping: H062, with common governance from H058-H067.

## 13. Strategy memory and cross-provider reasoning

Hugo should reason first about the required capability, then select the best authorized provider. Successful evidence-bearing approaches may become strategy candidates with preconditions, outcomes, confidence, and transfer limits. They remain proposals and never carry authority.

Mapping: H063, H068-H072.

## 14. Personal digital integrations

The same capability architecture should cover authorized email, calendar, music, smart home, repositories, PC/server, REDI CONTROL, and business platforms. Normal authorization should eventually be dashboard configuration rather than source editing.

Mapping: H040-H045, H068-H072. `BACKLOG REVIEW`: specific write-capable integrations require separate scopes, ADRs, and acceptance tasks.

## 15. Self-improvement backlog

Runtime may record redacted missing adapters, repetitive workarounds, integration failures, missing capabilities, and automation opportunities as structured development needs. Only Developer Mode/coding agents may work on them in sandbox with user approval. Runtime cannot modify itself.

Mapping: H073-H077.

## 16. Future device-side HUGO compatibility

Future devices may publish identity, capabilities, safety boundaries, sensors, actions, versions, and authentication methods so integration becomes negotiation rather than reverse engineering. This is an interoperability goal, not a claim about current standards or devices.

Mapping: H010-H013, H021-H029. `BACKLOG REVIEW`: a partner-facing compatibility specification and conformance suite are not explicit in H001-H080.

## 17. Hardware partner model

Partners may provide an official API, SDK, protocol, HUGO-compatible manifest, or official adapter. HUGO provides personal intelligence and governed runtime; partners provide hardware capabilities and device-local safety. Commercial collaboration must preserve provenance, integrity, permissions, verification, and truthful capability claims.

Mapping: H075-H080. `BACKLOG REVIEW`: partner certification, distribution, support, and commercial policy are not explicitly scheduled.

## 18. World Model, spatial reasoning, and affordances

Hugo must reason about entities, spatial relations, geometry, evidence quality, environmental constraints, and provider-specific physical feasibility before proposing multi-provider action. Capability presence alone does not prove that a body/device can perform an action in the current environment. World state, context, and persistent memory remain separate, and uncertain geometry may justify only a bounded authorized verification step.

The canonical definitions, pencil/LOOI/vacuum example, evidence classes, affordance outcomes, and decomposed backlog gaps are owned by [World Model, Spatial Reasoning, and Affordances](WORLD_MODEL_AND_AFFORDANCES.md).

Mapping: H010-H011, H023, H029, H031, H036-H039, H058-H072, H078-H079. `BACKLOG REVIEW`: focused world entity, spatial relation, geometry/evidence, world-state, environmental-memory, affordance-evaluation, planning-integration, and simulation tasks are not yet explicit.

## 19. HUGO Home Core and multi-host runtime

One persistent Hugo identity may operate across multiple authorized hosts. Host availability changes capability availability and execution location; it never creates or owns Hugo identity.

Conceptual future host roles are:

- `HOME_CORE`: usually an always-on mini-PC/server providing primary local memory, services, device registry, context, routines, Home Assistant, and local integrations even when the phone is offline.
- `MOBILE_HOST`: phone capabilities such as microphone, camera, GPS, display, local BLE, and possible physical mounting on a body such as LOOI.
- `OTHER_HOST`: authorized workstation, robot computer, vehicle host, or future runtime node.
- `CLOUD_RELAY`: optional AI providers, secure relay/sync, or external integrations; never identity owner by definition.

The future distributed architecture must preserve local-first ownership and explicitly solve host authority, synchronization, failover, conflict resolution, and split-brain prevention. Multiple hosts must not accidentally create multiple authoritative Hugo instances. No synchronization, election, distributed persistence, or failover runtime is authorized by this concept.

Mapping: H032, H035-H045, H058-H067, H080. `BACKLOG REVIEW`: host identity/trust, authority lease/election, encrypted synchronization, conflict resolution, failover, offline behavior, and split-brain acceptance are not explicit in H001-H080.

## 20. HUGO and HiHugo product identity

`HUGO` is the persistent AI entity/intelligence defined by the accepted identity architecture.
`HiHugo` is a possible future product, wake phrase, or visual identity around that entity. This is a
product concept only; it is not a trademark, registration, domain, or legal-availability claim.

Mapping: H003 and H080. `BACKLOG REVIEW`: naming, wake-word, visual identity, trademark, and product
release decisions are not authorized by this concept.

## Review rule

Backlog-review markers are inputs to a future roadmap review, not permission to edit or insert tasks during H003. Any future task change must preserve H001/H002, use an explicit architecture/backlog decision, and retain LOOI/T032 compatibility evidence.
