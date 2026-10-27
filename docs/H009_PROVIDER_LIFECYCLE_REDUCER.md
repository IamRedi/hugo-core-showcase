# H009 - Provider lifecycle reducer

## Status

**DONE** - 2026-08-25

## Model

`ProviderLifecycleReducer` is a pure transition function over project-owned
`ProviderLifecycleSnapshot` and `ProviderLifecycleEvent`. It has no Android, BLE, vendor,
transport, LOOI, UI, credential, manifest, registry, authorization, or safety-policy dependency.

The legal progression is:

```text
Inactive -> Discovered -> Identified -> Connecting -> Connected
-> Negotiating -> Registered -> Disconnecting -> Disconnected
```

Cancellation is explicit. Before a connection-bearing state it moves through `Cancelling` and can
complete as `Cancelled`. From `Connecting`, `Connected`, `Negotiating`, or `Registered`, it records
that disconnect is required and reaches `Cancelled` only once a disconnect is observed. Failures
from an active state are typed terminal `Failed` states.

`Connected` represents only a lifecycle/session observation. It is not trust, authorization, safety
approval, capability availability, physical feasibility, or permission to act.

## Generation ownership and invalid events

Every event carries an H008 `ProviderGeneration`. The reducer accepts a new `Discovered` event only
from `Inactive` or a terminal state and only with a strictly newer generation. It deterministically
returns an unchanged snapshot with `Ignored` for:

- events before any active generation;
- stale, future, or reused generations;
- duplicate terminal events;
- events that are illegal for the current state.

No event triggers discovery, connection, retry, reconnect, capability registration, or execution.
The caller owns effects and serializes reducer calls. H013 will define a connection-provider port;
H015 will own registry behavior; authorization, safety, and World Model/affordance decisions remain
separate.

## Completion evidence

`ProviderLifecycleReducerTest` covers normal lifecycle progression, invalid events, stale/future/
reused generations, cancellation with and without a required disconnect, failure/late callback
handling, disconnect idempotence, and platform/vendor/LOOI-free public types. This is a pure JVM
contract and makes no hardware claim.
