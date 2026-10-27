# H019 V2 Dependency Guards

## Purpose

H019 extends the existing T006 `ArchitectureDependencyGuardTest` rather than
creating a second architecture mechanism. The deterministic JVM guard protects
the V2 inward dependency direction while leaving the verified T001-T032 LOOI
runtime unchanged.

## Protected directions

The guard scans production Kotlin sources for these V2 pure package roots:

- `hugo/identity`
- `capability/api`
- `trust/domain`
- `manifest/domain`
- `connection/api`

Those roots reject imports from Android, Compose, concrete LOOI, platform
transport, UI/application packages, and known vendor/network libraries. They
also reject Android Bluetooth/GATT type references and raw LOOI protocol
identifiers (`FED0`, `FED8`, and `FED9`). This keeps HUGO identity/core,
capability API, trust, manifest, and connection contracts free of concrete
provider, transport, UI, and protocol coupling.

The World Model document guard rejects Android Bluetooth/GATT and raw LOOI
protocol references. It deliberately permits documented evidence examples that
name LOOI, because those examples do not expose runtime protocol details.

The guard also asserts the permitted inward direction: the LOOI compatibility
facade imports generic capability contracts, while no protected pure package
imports the LOOI adapter. That prevents a generic-to-adapter reverse edge and
therefore a generic/adaptor cycle.

## Proof fixtures

Fixtures under `app/src/test/resources/architecture/h019` prove the guard
rejects a generic LOOI import, a direct Android Bluetooth reference, and a
manifest import of the BLE implementation. A provider-neutral fixture proves a
valid generic source is accepted. Fixtures are test resources and are never
compiled into the application.

## Scope and non-goals

H019 adds enforcement only. It does not add LOOI wiring, provider routing,
execution, AI, voice, memory, dashboard, Home Assistant, or a World Model
runtime. It does not modify the verified T032 safety path, Android BLE
implementation, or physical behavior.

## Completion evidence

The H019 completion check runs the HUGO V2 documentation guard, T032 baseline
evidence guard, architecture/JVM tests, `testDebugUnitTest`, `lintDebug`, and
`git diff --check`. H019 has no Android-device or real-LOOI claim because it
changes no runtime path.
