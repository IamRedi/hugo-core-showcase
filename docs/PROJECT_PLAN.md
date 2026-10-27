# Project Plan

## Goal

Deliver a dependable Android-based personal robot whose LOOI body is replaceable and whose AI capabilities cannot bypass local safety. Progress is evidence-gated: unit, integration, Android-device, and real-LOOI verification are separate claims.

## Scope boundary for M0

M0 produces research, decisions, interfaces, a buildable app shell, deterministic safety-policy tests, and a sequential backlog. It does not implement BLE control, AI integrations, long-term memory, autonomous movement, or a polished UI.

## Work streams

| Stream | Responsibility | Physical authority? |
|---|---|---|
| Robot Core | Typed intents, ownership, state, command lifecycle | Requests only |
| Safety Controller | Limits, freshness, e-stop, dead-man, sensor gates | Authorizes or rejects |
| Body Driver | BLE lifecycle, encoding, notifications, stop delivery | Executes authorized actions |
| Interaction | UI, wake/voice, conversation, personality | None |
| Perception | Local camera/audio/device-sensor outputs | Evidence input only |
| Agent and Skills | Plans and typed tool requests | None |
| Memory | Validated records, retrieval, correction/deletion | None |
| Gateway | Provider-neutral AI mediation, auth, cost policy | None |

## Delivery method

1. Execute one task from `SPARK_TASKS.md` at a time.
2. Preserve layer boundaries and decision records.
3. Add deterministic tests before claiming behavior.
4. Record Android-device and hardware results separately.
5. Stop for an architecture decision when evidence contradicts a recorded invariant.

## Definition of milestone completion

A milestone is complete only when every required task is `DONE` or explicitly waived by a recorded decision, automated checks pass, and its device/hardware acceptance checklist has evidence. Mocked tests never satisfy physical acceptance.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Multiple GATT profiles/firmwares | Capability discovery, protocol profile registry, captured fixtures |
| Unlicensed upstream code | Reimplement from documented behavior; retain attribution and provenance |
| Android process/background limits | Native lifecycle-aware service design and on-device kill/reconnect tests |
| Runaway motion | Local watchdog, dead-man lease, stop on disconnect, bounded commands |
| Hallucinated AI action | Typed schema, allowlist, policy validation, short leases, audit log |
| Secret extraction from APK | Server-side provider credentials and short-lived client auth |
| Sensitive memory leakage | Local-first storage, field-level privacy, minimal retrieval, deletion/export |
| Overstated sensor capability | Confidence labels and repeatable physical experiments |

## Immediate next action

Execute T001 to record a reproducible baseline, then T002/T003. Do not start packet transmission until the protocol fixture and safety prerequisites in M1 are complete.
