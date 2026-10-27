# H020 M2.5 Regression Acceptance

**Completed:** 2026-08-25
**Accepted predecessor:** H019 commit `3ff7d6d`
**Locked physical baseline:** T032 commit
`0bcf536cdaae3b5c9c22247bb093ac6fc68cff26`

## Acceptance scope

H020 accepts the H001-H019 M2.5 foundation only. It adds no runtime feature,
provider routing, automatic registration, or LOOI protocol behavior. The
acceptance distinguishes automated architecture/build evidence from the
historical T032 physical acceptance evidence.

## Documentation and architecture validation

- The HUGO V2 documentation guard validates the complete H001-H020 document
  set and backlog references.
- The T032 baseline evidence guard still resolves the accepted checkpoint,
  locked tree, indexed documents, fixtures, and repository coverage.
- The extended T006/H019 `ArchitectureDependencyGuardTest` validates the
  inward V2 dependency direction and its positive/negative fixtures.
- The guard confirms pure HUGO identity, capability API, trust, manifest, and
  connection packages contain no LOOI, Android Bluetooth/GATT, platform,
  concrete transport, UI, vendor, AI, credential, or raw protocol coupling.
- The World Model document guard rejects Bluetooth/GATT and raw LOOI protocol
  coupling while preserving evidence-level LOOI examples.

## LOOI shadow registration and legacy-path audit

The static comparison from the locked T032 checkpoint through H019 found no
modified legacy file in:

- `core`
- `safety`
- `platform`
- `feature/manualcontrol`
- the pre-existing `body/looi` runtime

The only HUGO-era LOOI production additions are:

- `body/looi/manifest/LooiCapabilityMapping.kt` (H017)
- `body/looi/capability/LooiCompatibilityFacade.kt` (H018)

Both remain adapter-side. The production reference audit found their references
only within those adapter-side files; `feature/manualcontrol` has no reference
to either mapping, facade, or `CapabilityProvider`. Therefore legacy manual
control remains the default path and H017/H018 do not register, route, or
execute a shadow provider.

The accepted V2 architecture continues to require the existing LOOI
`SafetyController`, dead-man, E-stop, authority leases, sensor gates, and
movement limits for every LOOI action. No generic HUGO package can bypass that
path: H019 forbids the reverse generic-to-LOOI/platform dependency, and no new
runtime wiring was introduced.

## Automated and Android build evidence

The following H020 acceptance checks passed using the repository-required
Android Studio JBR and Android SDK environment:

- HUGO V2 documentation guard
- T032 baseline evidence guard
- focused `ArchitectureDependencyGuardTest`
- complete `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- `git diff --check`

## Hardware status

The H020 backlog requires real-device/real-LOOI regression only if an M2.5
change alters the verified runtime path. The legacy-path audit above found no
such change, so no additional Samsung/LOOI run was required or performed.
T032 remains the locked, physically verified evidence for connect, controlled
movement, stop, head, light, disconnect, E-stop, and dead-man behavior. H020
does not make a new physical claim and does not reinterpret T032 evidence.

## Result and boundary

H020 is accepted. H021 is the next eligible task. No H021 implementation is
included in this checkpoint.
