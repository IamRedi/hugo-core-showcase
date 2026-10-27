# HUGO Memory Architecture V2

## Identity anchor

Memory belongs to Hugo's user-owned runtime, not to the active body, host, or AI provider. Moving Hugo between bodies preserves personal identity and memory while retaining provider-specific ownership on device/body records.

## Layers

| Layer | Purpose | Default lifetime |
|---|---|---|
| Working | Current turn, task, temporary observations | Session/bounded |
| Episodic | Events and experiences | Policy-controlled |
| Semantic | Validated facts and concepts | Until corrected/expired |
| Personal | Preferences, routines, people, places, goals, corrections | User-governed |
| Device | Knowledge about a service/device instance | Instance-bound |
| Body | Calibration, latency, quirks, sensor behavior | Body-instance and adapter-version bound |
| Strategy/procedural | Evidence-bearing reusable approaches | Candidate then validated |

## Common record envelope

Records carry ID/schema, layer/type, subject links, provenance/source, creator, observed/created/updated times, confidence, validation state, privacy class, allowed uses, retention/expiry, conflicts/supersession, deletion state, provider/body ownership, and derived-data lineage.

Model-generated and strategy records begin as candidates. Confidence is not truth. Explicit correction and strong verified evidence outrank inference according to deterministic policy.

## Device/body versioning

Body and device memory includes provider instance, manifest digest, adapter version, firmware/API version, calibration environment, and evidence run. It cannot silently transfer to another instance or version. T032 LOOI facts remain LOOI-instance/profile evidence, not universal robot facts.

## Strategy memory

A strategy records preconditions, attempted providers/capabilities, outcome evidence, failures, confidence, and transfer limits. It may propose another provider when the current provider lacks a capability, but execution still requires current authorization and approval.

Validated environmental knowledge and spatial strategy candidates follow the ownership boundaries in [World Model, Spatial Reasoning, and Affordances](WORLD_MODEL_AND_AFFORDANCES.md). This document remains authoritative for whether and how those records persist.

## Retrieval

Retrieval is task-, privacy-, provider-, and budget-scoped. It returns a small provenance-bearing packet. Full history/database access is never supplied to conversation or a model. Conflicts remain visible; memories cannot authorize safety-critical action.

## Storage

Canonical storage is local-first and encrypted where technically appropriate. Repository ports remain platform-neutral. Search indexes, embeddings, summaries, and caches are derived and deletable. Optional sync is separately authorized and does not make a cloud model the memory owner.

## User control

Users can inspect why a record exists, validate/dispute/correct it, set retention/privacy, export, and delete it. Deletion propagates through indexes, summaries, caches, and authorized remote copies with auditable status.
