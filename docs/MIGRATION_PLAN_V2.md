# HUGO Architecture Migration Plan V2

## Baseline

Commit `0bcf536cdaae3b5c9c22247bb093ac6fc68cff26` is the accepted M2/T032 baseline. T001-T032 history, tests, fixtures, safety decisions, and physical evidence remain valid.

## Current-code audit

### Already body-independent enough to preserve

- project-owned semantic `RobotAction` and `ActionRequest`;
- motion authority, monotonic dead-man, E-stop reducers, and safety policy style;
- immutable state/event patterns, generation ownership, typed failures;
- architecture dependency guard for `core` and `safety`;
- fixture provenance/redaction and evidence-level testing rules.

### Legitimately LOOI-specific forever

- candidate identification, GATT inventories/profiles, handshake, FED encoders/parsers;
- GATT queue/notification behavior tied to LOOI profiles;
- lower-contact mapping and physically calibrated LOOI safety configuration;
- T032 acceptance session and hardware fixtures as reference evidence.

### Must become universal contracts incrementally

- provider/capability identity and lifecycle;
- manifest and adapter compatibility;
- trust, permissions, approval, registry, and active-body context;
- provider-neutral connection session contracts;
- skills/tools that target capabilities rather than `RobotBody` directly;
- memory/context ownership independent of body and AI provider.

### Naming/dependency debt

- root namespace and README still say robot/REDI; package rename is deferred because it risks evidence.
- `RobotBody` is too narrow for services/devices but remains a valid legacy body port.
- scan/connection models under `body/looi` include concepts that future contracts also need; create new contracts rather than moving them.
- features currently import LOOI directly; application use cases should eventually mediate capability requests.
- platform service composes diagnostics and T032 behavior directly; preserve until a tested provider runtime facade exists.

## Migration phases

1. Documentation and baseline lock.
2. Add Android-free capability/provider/manifest/trust contracts and guards.
3. Add LOOI mapping/facade while preserving existing runtime path.
4. Register LOOI capabilities in shadow/diagnostic mode.
5. Route one non-motion capability through the facade and compare outcomes.
6. Route manual control only after deterministic equivalence tests.
7. Repeat real-LOOI T032 regression before retiring any legacy composition path.

No phase moves raw protocol code into generic packages.

## Old T033-T064 migration matrix

| Old task | Class | V2 treatment |
|---|---|---|
| T033 | MODIFY | Keep deterministic local intent; output capability proposals, not robot-only intents. |
| T034 | KEEP | Move after provider foundations; audio session reducer remains pure. |
| T035 | KEEP | Preserve explicit capture/VAD and privacy boundaries. |
| T036 | MODIFY | Speech path invokes local capability mediation before AI. |
| T037 | MODIFY | Phone sensors become DeviceAdapter observations. |
| T038 | MODIFY | Generalize provenance to provider/context observation envelopes. |
| T039 | REPLACE | Replace robot skill registry with capability-backed local skill registry. |
| T040 | KEEP | Provider-neutral AI gateway remains valid, scheduled later. |
| T041 | KEEP | Secure gateway authentication remains valid. |
| T042 | KEEP | Streaming reducer remains provider-neutral. |
| T043 | MODIFY | Build context from Context Engine plus bounded memory packets. |
| T044 | MODIFY | Validate capability proposals, provider target, trust, and approval. |
| T045 | REPLACE | Replace robot-action mediation with universal capability mediation; LOOI stays one target. |
| T046 | MOVE | Conversation orchestration follows capability/tool foundations. |
| T047 | KEEP | Model policy/cost controls remain provider-neutral. |
| T048 | MOVE | AI acceptance becomes M5 acceptance after universal mediation. |
| T049 | SPLIT | Split common memory envelope from layer-specific schemas. |
| T050 | KEEP | Encrypted local repository remains required. |
| T051 | KEEP | Deterministic memory candidate validation remains required. |
| T052 | KEEP | Reconciliation/correction remains required. |
| T053 | MODIFY | Retrieval becomes identity-, privacy-, task-, and provider-aware. |
| T054 | KEEP | Provenance-preserving summaries remain valid. |
| T055 | KEEP | User memory control remains required. |
| T056 | MODIFY | Camera is a DeviceAdapter capability with visible lifecycle. |
| T057 | MOVE | Local detection follows provider/perception contracts. |
| T058 | MOVE | Tracking remains non-authoritative perception. |
| T059 | MODIFY | Cloud vision uses capability permission/context privacy. |
| T060 | MODIFY | Plans contain capability steps and provider constraints, not body-only steps. |
| T061 | MODIFY | Executor reauthorizes every provider capability step. |
| T062 | DEFER | Replace LOOI-only following with later feasibility-gated provider experiment. |
| T063 | KEEP | Personality remains presentation-only and identity-bound. |
| T064 | REPLACE | Replace with portable HUGO system acceptance and evidence matrix. |

## Exit criteria for migration

- V2 contracts and architecture guards pass.
- LOOI registers equivalent semantic capabilities without raw-type leakage.
- Existing tests/build/lint pass.
- The original T032 runtime remains available until replacement equivalence is proven.
- Any changed manual-control runtime path passes real-LOOI regression.
