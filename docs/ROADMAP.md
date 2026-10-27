# Roadmap

## M0 — Research and Foundation

Repository, stack decision, protocol evidence ledger, architectural boundaries, safety model, basic Android build, CI, and sequential backlog. Exit: clean build/test/lint and documents sufficient for a fresh agent.

## M1 — Reliable Robot Transport

BLE permissions, discovery, capability-based GATT profile identification, connection state machine, notification routing, handshake experiments, heartbeat, battery/telemetry parsing, reconnect, and disconnect stop. Exit: repeatable connection/telemetry evidence on Android and real LOOI; no manual drive claim yet.

## M2 — Manual Robot Control

E-stop, command leases, drive/head/light encoders, press-and-hold control, telemetry UI, lifecycle stop, and hardware acceptance. Exit: a human can reliably drive the real LOOI without the original app and recover safely from disconnect/app backgrounding.

## M3 — Local Robot Intelligence

Deterministic intent router, local commands, device sensors, voice activation primitives, perception interfaces, and bounded local skills. Exit: common commands remain local and all physical skills use the same safety path.

## M4 — AI Interaction

Authenticated provider-neutral gateway, text/realtime interfaces, conversation orchestration, tool schema, action mediation, barge-in, observability, budgets, and fallback behavior. Exit: provider can be swapped without robot/body changes and loss of cloud cannot compromise stop/control.

## M5 — Personal Memory

Encrypted structured store, provenance and confidence, candidate validation, retrieval, summarization, deduplication, correction/deletion/export, and privacy controls. Exit: relevant bounded context is demonstrably selected and all personal records are manageable by the user.

## M6 — Vision and Higher-Level Behaviors

Local detection/tracking primitives, selective cloud vision, bounded behavior executor, personality presentation, and real-world feasibility trials. Exit: every advertised behavior has device and hardware evidence, clear operating limits, and immediate manual override.

## Deferred possibilities

Smart-home integrations, additional robot bodies, remote presence, mapping, and navigation are post-M6 candidates. They require separate threat, privacy, and safety decisions.
