# FL008 - Active-body and negotiated-capability presentation

## Status

`DONE` as of 2026-08-27. Samsung SM-S928B and real-LOOI acceptance passed without movement or
capability execution. FL009 is the next eligible First Life task; H036 remains unstarted.

## Presentation and selection architecture

- `HiHugoBodyPresentationProjection` converts only the redacted FL005/FL006 runtime snapshot into
  `NO_BODY`, `CONNECTING`, `AVAILABLE`, `SELECTED`, or `BLOCKED` presentation state. It carries no
  provider-instance ID, endpoint, request/session correlation, transport data, permission, grant, or
  capability callback.
- `MainActivity` binds to the existing service-owned First Life composition. Binding starts no scan or
  connection. Runtime changes are observed through the existing redacted listener.
- Available BODY evidence exposes one explicit `Select as active body` control. Selection delegates to
  the existing H016 `ActiveBodyReducer`, is current-generation/current-provider bound, and expires
  after 60 seconds. `Deactivate body`, expiry, provider loss, disconnect, and generation replacement
  clear the selection deterministically.
- H033 blocked-state content is adapted at the UI boundary. The provider-neutral HiHugo reducer does
  not depend on the provider feature package. H034 diagnostic state remains available from the same
  service composition; no second error taxonomy or source-precedence policy was introduced.
- Selection is presentation state only. It grants no trust, authorization, permission, safety
  approval, execution authority, connection authority, or automatic provider selection.

## HiHugo visual foundation

The screen now uses a lifecycle-bounded, reduced-motion-aware Compose Canvas presentation on a pure
black field. The early realistic face was deliberately simplified after device review into a classic
LOOI-like monochrome expression: two spaced, filled circles and one lower curved smile, with no head,
brow, iris, or body outline and only a restrained edge glow. A dense safe binary field covers the
background as regular horizontal rows containing only `0` and `1`; it carries no runtime/private data.
It remains static and green in idle, falls downward while booting, flows horizontally at varied speeds
during listen/think/speak/observe/connect activity, and becomes a fixed red error field with a straight
mouth. It contains no LOOI protocol or transport knowledge and remains reusable for later
First Life states. The richer raster concepts under `design/hugo-identity/concepts` remain design
references for future versions rather than runtime identity data.

The body panel is vertically scrollable, its selection controls meet the 48 dp minimum target, and
the negotiated list is stable and semantic. There is deliberately no per-capability execution button.

## Exact negotiated capability evidence

The real provider presentation contained exactly the six H017/H029 semantic references, in
deterministic order:

- `body.head.position.control@1.0`
- `body.light.control@1.0`
- `mobility.move@1.0`
- `mobility.stop@1.0`
- `provider.connection.status@1.0`
- `safety.lower_contact.status@1.0`

Capability presence remained distinct from authorization, safety approval, runtime execution, and
physical feasibility.

## Samsung and real-LOOI acceptance

Target: Samsung SM-S928B, authorized over ADB on 2026-08-27. The opt-in FL008 instrumented flow used
the existing FL006 composition to discover the advertising LOOI, connect, complete the verified
handshake, bind the genuine BODY provider, and negotiate the exact list above. It issued no movement,
head, light, or other capability command.

The final correlated trace recorded:

```text
available-window=open exact-capabilities=6 authority=false no-motion=true
selected-window=open expiring=true authority=false no-motion=true
deactivated=verified identity-unchanged=true
disconnected-window=open selection=cleared capabilities=cleared
```

The test rejected expiry as evidence of manual deactivation; the accepted run completed explicit
deactivation before the H016 deadline. It then selected once more through H016 solely to verify that
disconnect clears selection and negotiated capability presentation. HUGO identity remained unchanged.

Redacted device screenshots:

- [historical private-device screenshot omitted from this showcase]
- [historical private-device screenshot omitted from this showcase]
- [historical private-device screenshot omitted from this showcase]

The screenshots and normal trace expose no MAC address, raw provider-instance identity, transport
address, GATT/FED/UUID/handshake detail, trust token, permission, or session correlation.

## Tests and checks

- Projection/runtime tests cover exact capability projection, explicit selection, expiry boundary,
  manual deactivation, generation replacement, provider loss, disconnect, identity continuity, and
  the absence of authority/execution implications.
- Compose instrumentation covers the exact list, selection/deactivation controls, authority warning,
  absence of an execution control, scroll accessibility, and existing lifecycle/reduced-motion shell
  behavior.
- The opt-in Samsung/real-LOOI instrumentation passed with three automatically correlated redacted
  screenshots.
- Full JVM tests, First Life/HUGO/T032 documentation and evidence guards, architecture dependency
  guards, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`, and `git diff --check` pass.

## Boundaries and next task

FL008 changes presentation and explicit active-body selection only. It does not alter LOOI discovery,
BLE/GATT, handshake, telemetry, T031 mapping, T032 safety, authorization, capability dispatch, or
physical behavior. FL009 may now begin only under a separate instruction. H036 remains untouched.
