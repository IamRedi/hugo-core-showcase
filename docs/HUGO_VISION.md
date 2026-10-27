# HUGO Vision

## Product definition

HUGO is a portable, local-first personal brain runtime. It owns one persistent identity, one user relationship, and one governed memory system while using many authorized capability providers over time.

LOOI is Reference Body Adapter #1. Its successful T001-T032 implementation and evidence are permanent compatibility requirements, not a temporary prototype to be renamed away.

```text
One persistent intelligence
One identity and user relationship
One governed memory system
Many bodies, devices, services, and environments
```

## Product promises

- Hugo identity does not change when the active phone, host, body, or service changes.
- Planning uses semantic capabilities, never vendor packets or SDK objects.
- Discovery never grants authorization.
- Unknown providers fail closed for control.
- Deterministic local policy remains authoritative for physical safety.
- User data is local-first and portable; cloud AI is an optional provider, not the owner of identity or memory.
- Runtime cannot modify or deploy its own production code.
- Every capability claim is evidence-level, version, permission, and provider-instance specific.

## Provider families

- `BodyAdapter`: embodied mobility, actuation, sensing, and local body safety. Examples include LOOI, a future ESP32 robot, vacuum, drone, or vehicle.
- `DeviceAdapter`: a camera, computer, display, appliance, sensor, or smart-home device.
- `ServiceConnector`: email, calendar, music, Home Assistant, REDI CONTROL, repositories, or authorized business APIs.

These families share capability, trust, lifecycle, and audit contracts. They do not share an artificial physical-device inheritance tree.

## Identity rule

The active body is context, not identity. Body switches preserve Hugo identity, personality, personal memory, preferences, routines, and work context. Body/device-specific calibration and quirks remain attached to the provider instance that produced them.

Future multi-host operation is governed by the canonical `HUGO Home Core and multi-host runtime` entry in [HUGO Future Concepts](HUGO_FUTURE_CONCEPTS.md#19-hugo-home-core-and-multi-host-runtime); no host owns or duplicates Hugo identity.

## Near-term strategy

1. Freeze M2/T032 as the LOOI compatibility baseline.
2. Add universal contracts in parallel with the current stack.
3. Map LOOI into those contracts through a compatibility facade.
4. Prove no T032 regression before generalizing runtime behavior.
5. Add only evidence-justified providers and integrations.

## Explicit non-goals of Architecture V2

- No immediate package-wide rename or multi-module rewrite.
- No autonomous multi-body execution.
- No Gmail, Calendar, smart-home, or REDI CONTROL implementation.
- No AI model/provider implementation.
- No long-term memory implementation.
- No self-modifying production runtime.
- No weakening or reinterpretation of T001-T032 evidence.
