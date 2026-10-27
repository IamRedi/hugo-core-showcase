# H004 Provider Identity and Type Completion

## Record

- Task: `H004 - Provider identity/type`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H003 commit `cc2d126dc95313b154ac264c06b472ab503dc7dc`
- Runtime impact: none; contracts are not wired into discovery or provider runtime
- Hardware claim: none

## Contract

- `ProviderDefinitionId`: public stable identity for one adapter-supported provider definition/class.
- `ProviderInstanceId`: private HUGO registration identity for one configured binding; never a physical device/body ID, account ID, transport address, endpoint, or connection generation.
- `ProviderFamily`: exact V2 roles `BODY_ADAPTER`, `DEVICE_ADAPTER`, and `SERVICE_CONNECTOR`.
- `RedactedProviderAlias`: bounded user-facing alias that rejects blank/control/MAC-address content.
- `ProviderIdentity`: typed composition of definition, private instance, family, and alias.
- `RedactedProviderIdentity`: diagnostic/UI projection that structurally omits private instance identity.
- `ProviderIdentityCodec`: strict version `1.0` protected-storage round trip; unknown fields and versions fail closed.

Separate Kotlin value types prevent Hugo identity, provider definition identity, and provider instance identity from crossing constructor scopes accidentally even when opaque UUID text happens to match.

## World Model architecture record

[World Model, Spatial Reasoning, and Affordances](WORLD_MODEL_AND_AFFORDANCES.md) is accepted as the single canonical owner for world entities, relations, geometry evidence, affordance outcomes, and feasibility reasoning. It is linked, not duplicated, from the related V2 documents. H004 implements no World Model runtime.

## Completion evidence

- Constructor tests cover definition/private-instance type separation and exact provider families.
- Round-trip tests cover all body/device/service roles.
- Negative tests cover blank/malformed/noncanonical IDs, stable-address aliases, unsupported schema, and forbidden transport fields.
- Redaction test verifies diagnostic projection omits private instance identity.
- HUGO V2 documentation guard: PASS
- T032 baseline evidence guard: PASS
- `./gradlew testDebugUnitTest`: PASS
- `./gradlew lintDebug`: PASS
- Documentation canonical-owner/link audit: PASS
- `git diff --check`: PASS
- Device/hardware verification: not required and not claimed

## Scope boundary

H005 has not started. H004 adds no discovery, external instance mapping, persistence, manifest implementation, capability identity, World Model runtime, spatial store, planner, autonomy, integration, dashboard, or LOOI behavior.
