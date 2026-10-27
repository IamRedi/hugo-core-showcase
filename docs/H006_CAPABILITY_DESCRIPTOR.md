# H006 Capability Descriptor Completion

## Record

- Task: `H006 - Capability descriptor`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H005 commit `b72a0fa4a217bb7a51e58b602fd2a6e470bdc815`
- Runtime impact: none; descriptors are not registered, matched, authorized, or executed
- Hardware claim: none

## Descriptor contract

`CapabilityDescriptor` contains:

- versioned semantic `CapabilityReference`;
- bounded human label and description;
- optional versioned input/output contract references without payload definitions;
- `OBSERVE`, `ACT`, or `MODIFY` side-effect class and derived read-only state;
- risk and physical-safety classifications;
- matching permission intent and declarative approval requirement;
- cancellation and availability semantics;
- capability-claim evidence class;
- bounded finite numeric limits with semantic keys/units;
- bounded optional semantic metadata.

The factory copies collections and rejects permission/effect conflicts, observation/physical-action conflicts, safety/risk conflicts, duplicate or excessive limits, excessive metadata, non-finite/reversed/missing bounds, malformed fields, and raw-protocol metadata keys.

## Authorization and risk boundary

Descriptor permission and approval fields declare what evaluation is required. They do not grant permission, record user approval, establish trust, or authorize execution. Risk and safety classes support later policy/UI decisions but never replace deterministic provider safety. Current connection, registration, permission, and availability state are not descriptor fields.

## Provider neutrality and reuse

The descriptor contains no vendor, provider instance, adapter, transport, host, UI, LOOI, packet, endpoint, or current connection type. A future manifest can bind a provider implementation to the semantic reference. A registry can index it, trust policy can inspect its declarations, dashboards can choose generic controls from effect/contracts/limits, and planners can discover semantic operations without raw implementation knowledge.

Physical feasibility remains exclusively governed by `WORLD_MODEL_AND_AFFORDANCES.md`. A descriptor states what a capability means; it does not claim that one provider can safely perform it in the current environment.

## Completion evidence

- Typed descriptor corpus covers observe, act, and modify semantics without defining a production catalog.
- Schema tests cover contract references, effect/permission, safety/risk, cancellation, availability, evidence, bounded metadata, and copied collections.
- Limit tests cover missing, NaN, infinity, reversed, duplicate, and valid finite bounds.
- Raw protocol metadata keys are rejected.
- Capability API coupling audit: PASS
- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Device/hardware verification: not required and not claimed

## Scope boundary

H007 has not started. H006 adds no payload schemas, request/result envelopes, registry, manifests, provider matching, permission runtime, dashboard, planner, memory, integration, World Model runtime, or LOOI behavior.
