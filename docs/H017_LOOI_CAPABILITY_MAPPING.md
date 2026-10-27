# H017 - LOOI capability mapping

## Status

**DONE** - 2026-08-25

## Scope and boundary

H017 adds the adapter-side `body/looi/manifest/LooiCapabilityMapping` ledger. It declares only
semantic H005/H006 capabilities and points to existing LOOI implementation boundaries. It introduces
no `CapabilityProvider` implementation, wrapper dispatch, protocol refactor, Android/BLE type,
packet encoding, handshake, raw write, or change to manual control. A future H018 facade may compose
semantic `CapabilityRequest` with this ledger and delegate only into the existing verified LOOI path.

HUGO Core and `capability/api` receive no LOOI protocol knowledge. The mapping does not make a
capability available, trusted, authorized, physically feasible, active-body selected, or executable.
It carries no transport, GATT, endpoint, profile, packet, motor-polarity, sensor-parser, credential,
or device-identifier detail.

## Verified mappings

| Semantic capability | Evidence | Existing adapter boundary | Verified limit |
|---|---|---|---|
| `mobility.stop@1.0` | T032 physical manual acceptance | Existing drive safety path | Prioritized neutral stop |
| `mobility.move@1.0` | T032 physical manual acceptance | Existing drive safety path | Calibrated four-direction manual motion; release-to-neutral |
| `body.head.position.control@1.0` | T032 physical manual acceptance | Existing head safety path | Bounded up/down/center presets |
| `body.light.control@1.0` | T032 physical manual acceptance | Existing light safety path | Off or verified minimum-on only |
| `safety.lower_contact.status@1.0` | T031 physical lower-contact mapping; T032 acceptance | Existing lower-contact safety observation | Fresh directional status only |
| `provider.connection.status@1.0` | T011 physical connection lifecycle; T032 acceptance | Existing connection-state observation | State observation only |

All action descriptors preserve the existing LOOI safety boundary. A future semantic move or stop
request must still traverse the established safety controller, dead-man, E-stop, authority lease,
sensor gates, and existing motion limits. H017 neither reorders nor bypasses those controls. Move is
classified `ACT`, `SAFETY_CRITICAL`, `SAFETY_CRITICAL_ACTION`, and `EVERY_REQUEST`; stop remains an
existing prioritized safety action. Head and light are bounded physical actions. Lower-contact and
connection capabilities are observations.

Battery, dock/power, general telemetry, raw sensor frames, arbitrary brightness, autonomous motion,
head feedback, attachment/mounting semantics, and any unlisted capability are deliberately
unsupported. T030 has not supplied the physical evidence necessary to map battery/dock claims.

## Evidence and validation

`LooiCapabilityMappingTest` verifies the exact six-item ledger, hardware evidence references,
semantic provider-neutral identity, risk/permission/safety metadata, unsupported battery rejection,
preserved action safety boundary, and absence of protocol constants from Core and the Capability API.
The ledger has no real-device runtime impact, so H017 requires no new hardware regression run and
makes no new hardware claim. T032 remains the governing physical acceptance record.
