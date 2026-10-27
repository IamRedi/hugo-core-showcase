# H044 Time / Presence Context Sources

## Record

- Task: `H044 - Time/presence context sources`
- Milestone: M4 - Personal Context and Integrations
- Status: `DONE`
- Completed: 2026-09-01
- Accepted starting point: H043 commit `33fe6927c7e47d313c0057ca006e98bbe9197edd`
- Production runtime impact: none; the sources and Android bridge remain unwired
- Hardware claim: Samsung local-time acquisition and authorized coarse-location fail-closed trace only

## Source architecture

`context/sources` owns two Android-free H036 producers. `LocalTimeContextSource` projects one
injected wall/monotonic sample into four deterministic private observations:

- `time.local.epoch_day`;
- `time.local.minute_of_day`;
- `time.local.day_of_week`; and
- `time.local.utc_offset_minutes`.

The batch expires exactly at the next wall-clock minute. It contains no locale, account, calendar,
schedule, routine, user identity, durable timestamp history, scheduler, or background refresh.
Equivalent samples and opaque source aliases produce equivalent IDs; different source aliases cannot
reuse an observation ID.

`CoarsePresenceContextSource` consumes one foreground permission-gated location result and compares
it with one caller-supplied authorized region. A region must be at least 3,000 metres and no more
than 500 kilometres. The comparison includes the platform accuracy radius: it emits `true` only
when the complete uncertainty circle is inside, `false` only when it is definitely outside, and
`Unknown(NOT_OBSERVED)` at an uncertain boundary. The only output key is
`presence.authorized_region`.

## Authorization, privacy, and expiry

The Android bridge declares and checks only `ACCESS_COARSE_LOCATION` for current Android versions.
It declares no fine-location access for H044 and no background-location permission. Acquisition is
explicit, foreground-only, one-shot, cancellable, and bounded by a timeout. Fused, network, and GPS
providers may be attempted within that single bounded request; there is no polling, geofencing,
worker, service, boot receiver, or tracking history.

Raw latitude, longitude, Android `Location`, provider names, and permission objects remain inside
the platform/acquisition boundary. The domain uses coordinates only for the immediate region
comparison. Coordinates are not placed in H036 evidence, diagnostics, UI, storage, H041
credentials, snapshots, or the completion record. `CoarseLocationSample.toString()` is redacted.
Presence evidence is `RESTRICTED`; time evidence is `PRIVATE`.

Permission denial maps to `Unknown(PERMISSION_DENIED)`. Disabled location, unavailable providers,
timeout, and cancellation map to `Unknown(SOURCE_UNAVAILABLE)`. Future, over-age, expired, or
accuracy-ambiguous samples map to `Unknown(NOT_OBSERVED)`. No failure becomes `false`, `true`, or a
default region. H037 removes observations at the exact expiry boundary.

The H041 credential boundary remains unchanged and unused by location acquisition. An Android
runtime permission is not a service credential, H014 grant, trust decision, safety approval, or
execution authority.

## Non-authority and non-persistence boundary

Every source batch explicitly reports false for persistence, trust, permission, authorization,
safety, and execution implications:

```text
time/presence observed
!= durable history
!= identity
!= fact beyond freshness
!= trust
!= permission grant
!= authorization
!= safety approval
!= provider/body selection
!= execution authority
```

H044 adds no memory, AI, calendar composition, service operation, provider connection, active-body
state, movement, LOOI transport, or physical behavior.

## Android verification

The authorized Samsung `SM-S928B` was continuously visible as `device`. The current debug and
androidTest APKs installed successfully and Android location services reported enabled. The test:

1. granted `ACCESS_COARSE_LOCATION` temporarily for the isolated run;
2. launched a debug-only foreground host containing no location display or production route;
3. produced four fresh local-time observations;
4. made one bounded Android coarse-location request;
5. received no usable fix and therefore emitted `Unknown(SOURCE_UNAVAILABLE)` rather than inventing
   presence;
6. retained/logged no location value, history, account, provider, body, or authority state; and
7. revoked the temporary coarse permission in `finally` and confirmed it was no longer granted.

Redacted device evidence:

```text
H044_DEVICE_TRACE model=SM-S928B timeKeys=4 permission=coarse_foreground
result=unknown_source_unavailable freshness=bounded location=redacted history=none authority=none
```

The positive known-inside/known-outside paths, uncertainty band, permission denial, unavailable,
timeout, cancellation, stale/future/expired samples, exact H037 expiry, deterministic IDs, and
non-authority fields are covered with virtual-time/fake tests. The hardware run intentionally makes
no positive physical-location claim because Android supplied no current fix in the bounded window.

## Verification

- focused H036/H037/H044 and architecture tests: PASS
- Samsung H044 instrumented context trace: PASS, one test
- temporary coarse foreground grant cleanup: PASS
- full `testDebugUnitTest`: PASS
- `lintDebug`: PASS
- `assembleDebug` and `assembleDebugAndroidTest`: PASS
- HUGO V2 documentation guard: PASS
- First Life documentation/order guard: PASS
- T032 baseline evidence guard: PASS
- architecture dependency guard: PASS
- location/privacy leakage scan: PASS
- `git diff --check`: PASS

## Risks and next boundary

- A current coarse fix depends on device/provider/environment availability; absence remains explicit
  unknown and cannot be treated as presence.
- Region configuration and user-facing permission education belong to a later authorized
  composition. H044 persists neither regions nor observations.
- The production manifest now declares coarse foreground location for H044, but no production path
  requests it and the source is not composed into the application runtime.

H044 is complete. H045 is eligible and has not started.
