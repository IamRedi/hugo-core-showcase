# H003 Hugo Identity Contract Completion

## Record

- Task: `H003 - Hugo identity contract`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H002 commit `3fd144d4ee3cb22e20bfcf00d13e33f885657171`
- Runtime impact: none; contract is not wired into application lifecycle
- Hardware claim: none

## Contract

`HugoIdentity` is a pure Kotlin value containing an opaque `HugoIdentityId` and `HugoIdentityContractVersion`. The only supported contract version is currently `1.0`. The canonical ID representation is a lowercase UUID-format value created from caller-provided entropy; H003 deliberately provides no installation-lifecycle generator or persistence.

`HugoIdentityCodec` serializes only `schemaVersion` and `identityId`. Decoding is strict and fail-closed. Project-owned typed failures distinguish blank identity IDs, malformed/noncanonical IDs, unsupported versions, missing/unexpected/invalid fields, blank documents, malformed JSON, and non-object roots.

## Invariants

- Hugo identity is not an Android installation ID, host ID, body ID, device ID, service account, or AI-provider account.
- Identity creation must never derive from those external identifiers.
- Active host, body, device, provider, service, and connection associations are runtime context outside this object.
- The identity value contains no Android, Bluetooth, LOOI, SDK, account, persistence, memory, or network type.
- LOOI remains Reference Body Adapter #1 and receives no special identity semantics.
- Serialization is deterministic and versioned; unknown versions and fields fail closed.

## Completion evidence

- Future concepts are durably recorded in `HUGO_FUTURE_CONCEPTS.md` with backlog mappings and explicit review gaps.
- `HugoIdentityTest` covers canonical values, blank/malformed/noncanonical typed errors, deterministic round-trip serialization, unsupported versions, malformed documents, missing/unexpected fields, and absence of host/body/device/installation/provider/account associations.
- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- `git diff --check`: PASS
- Physical/device verification: not required and not claimed

## Scope boundary

H004 has not started. H003 adds no persistence, account/sync, provider identity, embodiment state, discovery, manifest, adapter, dashboard, memory, integration, Developer Mode, AI, or runtime wiring.
