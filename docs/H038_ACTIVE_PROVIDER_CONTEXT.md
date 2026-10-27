# H038 Active-Provider Context

## Record

- Task: `H038 - Active-provider context`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Completed: 2026-08-31
- Prerequisites: H016 active-body contract and H037 commit `f76e7690aff93d430711494e08ef70f565fb4d54`
- Runtime impact: none; H038 adds an unwired pure reducer/projection
- Hardware claim: none

## Accepted inputs and ownership

H038 consumes immutable H015 provider-registry snapshots and H016 active-body state. It does not
create a second provider registry, lifecycle, availability model, host identity, or body-selection
engine:

- H009/H015 remain authoritative for provider lifecycle generation and runtime availability;
- H016 remains authoritative for explicit, expiring body selection and embodiment evidence;
- H037 remains authoritative for bounded current-context normalization and expiry; and
- H038 only projects those accepted states into one current active-provider context.

The reducer is revisioned. Lower and reused H038 revisions are ignored, contradictory/future runtime
evidence fails closed, and invalid snapshot windows are rejected. Refreshes are deterministic and
never mutate either input snapshot.

## Connection and availability projection

A provider is `CONNECTED` only while its H009 lifecycle is `Connected`, `Negotiating`, or
`Registered`. `Connecting`, `Cancelling`, `Disconnecting`, `Cancelled`, `Disconnected`, `Failed`,
and earlier lifecycle states are `NOT_CONNECTED`.

Runtime `AVAILABLE`, `DEGRADED`, and `UNAVAILABLE` are retained only for connected providers. Any
non-connected lifecycle projects `UNAVAILABLE`, even if an older runtime availability observation
still says available. This makes disconnect remove context availability immediately and
deterministically.

Provider entries retain their H004 identity, generation, H015 host-association reference, and
runtime observation time inside the protected domain. Lists are immutable and sorted by provider
instance identity. Connected BODY, DEVICE, and SERVICE providers are distinct derived lists.
`availableServices` contains only connected services with exact `AVAILABLE` runtime state; degraded
services remain explicitly connected/degraded rather than silently promoted or discarded.

## Host, selected body, and active body

The current host is the accepted H016 `EmbodimentHost`; it is not HUGO identity or host authority.
An H016 selection is projected as `selectedBody` only from its inclusive selection time until its
exclusive expiry. A future or expired selection is absent.

`activeBody` is stricter than `selectedBody`. It additionally requires:

- the same H004 instance in the H015 snapshot with BODY family;
- a connected H009 lifecycle;
- runtime availability other than `UNAVAILABLE`; and
- matching H016 body evidence marked available and connected.

Therefore a body can remain explicitly selected while disconnected, but active-body readiness
clears. This does not mutate H016 selection and does not infer trust, permission, safety, mounting,
physical feasibility, or execution authority.

## H037 aggregate snapshot

Each accepted refresh creates eight private, bounded H036 observations and normalizes them through
the existing H037 engine:

- `provider.host.role`;
- `provider.connected.count`;
- `provider.body.connected_count`;
- `provider.device.connected_count`;
- `provider.service.connected_count`;
- `provider.service.available_count`;
- `provider.body.selected`; and
- `provider.body.active`.

These are deterministic aggregate facts about accepted input snapshots. They use one opaque
project-owned source reference, `PRIVATE` classification, exact refresh/expiry times, and a fixed
eight-item H037 budget. The portable projection contains no provider instance ID, alias, transport
address, registry mutation, permission, authorization, safety, or execution field.

The rich protected domain snapshot is intentionally not given a public diagnostic codec. Only its
aggregate H037 projection is portable. `h038-active-provider-state-trace.json` records a fictional
connected-body then disconnected-body reducer trace without private identity data.

## Expiry and non-persistence

Before expiry, an `Expire` event is ignored. At the exact exclusive expiry boundary, the working
snapshot is cleared and the new revision is retained. H038 does not replace expired state with an
invented empty host/provider fact. It adds no persistence, replay, background task, source adapter,
planner, provider selection policy, memory candidate, UI, or logging.

`grantsAuthorization` and `grantsExecutionAuthority` are always false. Connected, available,
selected, and active remain separate evidence and none implies trusted, authorized, safe,
physically feasible, or executable.

## Test evidence

`ActiveProviderContextTest` covers initial empty state, BODY/DEVICE/SERVICE projection, host role,
connected and available service distinctions, degraded service, H016 future/expired selection,
selected-versus-active body behavior, disconnect/disconnecting availability removal, exact expiry,
stale/reused revisions, future provider evidence, invalid windows, deterministic registry ordering,
H037 aggregate privacy/no-private-ID checks, registry immutability, non-authority fields, and the
portable two-step state trace.

Final completion checks:

- HUGO V2 documentation guard: PASS
- First Life documentation/order guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guards: PASS
- relevant H015/H016/H037/H038 JVM tests: PASS
- full `testDebugUnitTest`: PASS, 682 tests in 98 suites; zero failures/errors/skips
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `git diff --check`: PASS
- implementation leakage scan: PASS, zero Android/Bluetooth/GATT/FED/LOOI references
- Android/device/LOOI verification: not required; no production/runtime/physical path changed

## Next boundary

H039 is complete and H040 is eligible. H038 itself adds no phone sensor adapter, sensor lifecycle, context
source acquisition, service connector, persistence, planning, provider execution, LOOI behavior, or
physical control.
