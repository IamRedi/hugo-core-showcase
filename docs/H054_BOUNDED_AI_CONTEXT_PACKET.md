# H054 Bounded AI Context Packet

## Record

- Task: `H054 - Bounded AI context packet`
- Status: `DONE`
- Implementation date: 2026-09-02
- Prerequisites: H037 context snapshot, H050/H051 skill/request contracts, and H053 gateway client
- Runtime impact: none; the builder and codec are pure and unwired
- Deployment claim: none; no model, gateway, provider, memory database, or conversation runtime is configured

## Packet model and deterministic ordering

`conversation/context` builds one immutable schema `1.0` outbound packet correlated to an existing
H051 request. It contains, in fixed canonical sections:

1. the exact current H051 prompt with a redacted random provenance reference;
2. at most one explicitly supplied summary;
3. privacy-filtered H037 current-state projections;
4. caller-selected memory candidates obtained through a memory port; and
5. the exact H050 definitions for only the skills declared by the H051 request.

The current turn and every declared tool schema are mandatory. A missing prompt, missing/extraneous
tool definition, duplicate definition, or mandatory content that cannot fit fails closed. Summary,
snapshot, and memory entries are optional and are considered in that order. H037 snapshot order is
preserved, memory candidates are ordered by opaque provenance ID, and tool schemas are ordered by
exact `SkillReference`. Budget pressure drops one whole optional item and records a typed count; it
never slices text, provenance, conflicts, or a tool schema.

H037 projections preserve the semantic key, known/unknown/conflict resolution, effective privacy
class, and sorted observation IDs. They deliberately omit source references, source types,
confidence, timestamps, and the duplicate evidence claims already represented by the resolution.
The packet is current task input, not a complete H037 snapshot, history, log, durable memory, fact,
or authority record.

## Hard item/input/byte budgets

`ConversationContextBudget` has hard ceilings of 64 total items, 8,192 canonical Unicode input
units, and 32,768 UTF-8 bytes. Its input ceiling matches H051 `GatewayText.MAX_CODE_POINTS`; every
successful packet can therefore be converted back to an H051 `GatewayText`. Exact canonical JSON
is measured after every proposed whole-item addition, so escaping and multibyte characters count.

`maximumInputUnits` is a provider-neutral pre-tokenization unit, not a claim about a particular
model tokenizer. A later H057 model policy may impose a smaller provider/model token allowance, but
it cannot enlarge H054's item, canonical-input, or byte ceilings. H054 does not guess token counts,
cost, context windows, or model capacity.

## Privacy and provenance

The outbound policy can permit only `PUBLIC` and/or `PRIVATE`. `SENSITIVE` and `RESTRICTED` are hard
forbidden in H054. A forbidden current turn rejects the build. Forbidden summary, H037 item, or
memory candidate is omitted before serialization and counted in separate content-free build
evidence. Filtered content and its provenance never enter the packet document.

Current-turn, summary, and memory references are canonical random project-owned UUIDs. Duplicate
provenance across those sections fails closed. H037 contributes its existing observation IDs; no
provider instance, endpoint, account, device address, source reference, Android installation, user
identity, body, or LOOI identity is introduced. The JSON codec structurally escapes adversarial
prompt text, so text cannot create fields, tools, authorization, or instructions outside its value.

The privacy allowlist only narrows data already supplied by an authorized upstream caller. It does
not grant permission to collect or disclose that data.

## Empty memory boundary

`ConversationMemoryContextPort` accepts only a bounded selection request. The initial
`EmptyConversationMemoryContextPort` deterministically returns no candidates. An unavailable or
throwing port produces an explicit `MEMORY_UNAVAILABLE` omission and no invented memory. Duplicate
or more than 64 returned candidates fail closed; privacy and final packet budgets are re-applied to
all returned items.

This is a seam for later H065 bounded retrieval, not retrieval itself. H054 adds no database,
ranking, embeddings, persistence, history scan, summary generation, inference, correction, or
memory write.

## Tool and authority boundary

Tool context is projected from existing H050 definitions: exact skill/capability identity, actor,
reviewed local class, input/output contracts, fixed input, maximum duration, and cancellation
requirement. The declared H051 skill set and supplied definitions must match exactly.

```text
TOOL SCHEMA IN CONTEXT
!= TOOL PROPOSAL VALIDATED
!= H030 AUTHORIZED
!= SAFETY APPROVED
!= SKILL EXECUTED
!= CAPABILITY EXECUTED
```

Every tool projection serializes `authority=false`. Packet flags for trust, authorization, safety,
skill execution, and capability execution remain false. H055 owns proposal validation and
mediation; H054 never invokes the H050 registry/executor or constructs an H007 request.

## Verification

`ConversationContextBuilderTest` covers:

- deterministic current/summary/H037/memory/tool assembly under reversed source ordering;
- exact provenance and requested-tool binding;
- hard sensitive/restricted exclusion from summary, snapshot, memory, and policy;
- forbidden current-turn failure;
- adversarial JSON/prompt injection remaining one escaped text value;
- whole-item count/input/UTF-8 budget behavior and mandatory-content rejection;
- H051 `GatewayText` compatibility;
- empty and unavailable memory behavior;
- duplicate/mismatched tools and duplicate memory provenance;
- prompt-less request rejection;
- non-authority and redacted diagnostics; and
- absence of Android, network, storage, provider/runtime, LOOI, authorization, safety, or execution coupling.

Required documentation/evidence and architecture dependency guards pass. The focused H054 suite
passes 12 tests; the full JVM suite passes 864 tests across 114 suites with zero failures, errors,
or skips. `lintDebug` passes with zero errors (13 warnings and one informational item),
`assembleDebug` and `git diff --check` pass. No device verification is required because H054 is
pure, unwired, and changes no First Life, LOOI, T032, network, media, or physical path.

## Next boundary

H055 is eligible and has not started. H054 adds no capability-proposal validator, schema fuzzing,
authorization/approval mediation, idempotency enforcement, skill invocation, conversation runtime,
model policy, memory retrieval, backend, or real model execution.
