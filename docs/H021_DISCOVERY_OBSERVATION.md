# H021 Discovery Observation

## Contract

H021 adds the pure `discovery/api` `DiscoveryObservation` contract. It records
only passive, expiring evidence that a possible provider may be observable by a
specific host. It is deliberately not a `ProviderIdentity`, registered provider,
trust record, permission grant, connection, capability-availability claim, or
active-body state.

Each observation has a per-observation UUID, extensible source type, opaque
observing-host reference, monotonic observation/expiry timestamps, optional
display and provider-family hints, protocol/service hints, evidence items,
bounded confidence, privacy classification, optional opaque source-local
reference, and normalized non-secret metadata. `ProviderFamily` is only a
non-authoritative class hint; H021 contains no provider definition or instance
identity.

`DiscoveryObservationCodec` is strict schema `1.0` protected-storage encoding.
It rejects missing/unknown fields and unsupported schema versions. Diagnostics
must use `redacted()`, which omits the source-local reference.

## Privacy and freshness

Sources must transform stable/private raw identifiers before they enter this
contract. Display hints, evidence summaries, and metadata values reject common
MAC, IP, URL, and account-like forms; metadata keys reject address, serial,
account, credential, secret, and token fields. The opaque local reference
accepts only a restricted local token form and cannot be public. It is not a
HUGO or provider identity.

An observation is fresh only while `now < expiresAt`; equality is stale. It is
immutable and defensively copies hints, evidence, and metadata. Duplicate
observations remain independent evidence until H022 identity resolution.

## Source neutrality and future flow

`DiscoverySourceType` accepts a bounded canonical semantic type, allowing later
BLE, LAN, mDNS, Matter, USB, serial, ROS, service/account, or future source
implementations without exposing Android, vendor SDK, transport, endpoint, or
raw protocol types. Discovery remains passive/read-only in H021.

The next layers may use the evidence flow:

```text
observe -> accumulate evidence -> identify -> find manifest/adapter
        -> establish trust -> authorize -> connect/register
```

H021 implements only the first noun in that flow. It does not match providers,
connect, register, select an active body, choose an adapter, or operate LOOI.

## Completion evidence

`DiscoveryObservationTest` covers valid and unknown observations, optional
metadata defensive copying, freshness/expiry, confidence bounds, duplicate
observations, privacy/stable-reference rejection, malformed values, strict
codec round-trip/version/unknown-field behavior, redaction, and the absence of
identity/trust/permission/connection semantics. H019 extends its pure-package
scan to include `discovery/api`.

No Android-device or real-LOOI verification is required or claimed: H021 adds
no scanner, connection, registration, or runtime wiring.
