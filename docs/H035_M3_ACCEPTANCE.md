# H035 M3 Universal Runtime Acceptance

## Record

- Task: `H035 - M3 acceptance`
- Milestone: M3 - Universal Device/Body Runtime
- Status: `DONE`
- Accepted: 2026-08-26
- Prerequisite: H034 commit `9673158b387bc312b5588bf45ba9cd24cfc14f51`
- T032 physical authority: `0bcf536cdaae3b5c9c22247bb093ac6fc68cff26`
- Product/runtime change: none; H035 adds acceptance tests and evidence only

## Accepted capability boundary

M3 accepts the provider-neutral foundation spanning H021 through H034:

1. bounded passive discovery observations;
2. identity resolution that retains unknown, ambiguous, conflicting, and stale outcomes;
3. validated common and specialized manifest resolution with explicit provenance;
4. dimensioned compatibility evidence;
5. adapter knowledge plus fail-closed persistent metadata integrity;
6. deterministic provider preparation with generation/correlation ownership;
7. the generic H013 connection contract and additive BLE wrapper over the existing GATT transport;
8. capability negotiation from manifest, adapter, connection, and runtime evidence;
9. request-time trust, grant, permission, approval, session, host, and presence authorization checks;
10. instance-bound calibration readiness without a safety or execution shortcut;
11. protected provider-instance persistence without runtime-authority persistence;
12. explanatory, control-free fail-closed provider presentation; and
13. bounded, versioned, redacted diagnostic export.

This is a contract/domain and fake-provider foundation. M3 does not provide automatic provider
selection, installation/loading, real service/device integrations, capability routing, new LOOI
routing, or a new physical capability claim.

## Deterministic flow and separation audit

The focused H021-H034 tests exercise the conceptual progression:

`Observation -> Identity -> Manifest -> Compatibility -> Adapter knowledge -> Provider preparation
-> Connection evidence -> Capability negotiation -> Authorization gate -> blocked/ready evidence`

Each transition requires a typed input and yields an independently typed result. The accepted APIs
do not collapse these facts. In particular:

`discovered != identified != compatible != trusted != authorized != connected != negotiated != safe
!= physically feasible != executed != active body`

Specific separation evidence includes ambiguous identity remaining unresolved, valid manifests with
missing adapters remaining valid, registry presence remaining knowledge only, BLE channel evidence
granting no capability readiness, negotiation granting no authorization, authorization granting no
safety approval, calibration granting no safety/execution authority, diagnostics granting no trust
or control, and provider-instance restore recreating no grants, approvals, sessions, registration,
connection, or active-body selection.

## Fake-provider acceptance

`M3UniversalRuntimeAcceptanceTest` runs three distinct deterministic scenarios through the same H008
`CapabilityProvider` port and the same runtime/negotiation/registration result types:

| Family | Fictional definition | Semantic capability | Result |
|---|---|---|---|
| BODY | `hugo.test/body` | `mobility.status@1.0` | Available, negotiated, and declared through the generic port |
| DEVICE | `hugo.test/device` | `device.light.status@1.0` | Available, negotiated, and declared through the generic port |
| SERVICE | `hugo.test/service` | `service.records.read@1.0` | Available, negotiated, and declared through the generic port |

All three use the same fake implementation class. Family is data in `ProviderIdentity`; there is no
body/device/service implementation inheritance and no vendor, transport, Android, endpoint, or
protocol type in the port. The fake execution methods deliberately fail if invoked: acceptance of a
declaration or negotiation does not create execution authority.

## LOOI reference-adapter audit

H017 and H018 still expose exactly the six evidence-backed semantic capabilities:

- `mobility.stop@1.0`
- `mobility.move@1.0`
- `body.head.position.control@1.0`
- `body.light.control@1.0`
- `safety.lower_contact.status@1.0`
- `provider.connection.status@1.0`

Every mapping retains hardware evidence, a verified limit, and an existing adapter boundary. Action
mappings retain the existing safety path. The acceptance test rejects any change to this exact set
and scans the public mapping text for BLE/GATT/FED/Android type leakage. Battery, dock/power, general
telemetry, arbitrary brightness, autonomous movement, head feedback, and all other unevidenced claims
remain unsupported. HUGO Core learns only semantic capabilities; H018 remains an unwired facade.

## Failure and fail-closed matrix

| Failure | Accepted typed evidence | Fail-closed result |
|---|---|---|
| Unknown or insufficient identity | H022 identity status/reason | No identity, manifest, or authority invented |
| Ambiguous/conflicting identity | H022 candidate/evidence result | Stops ambiguous/conflicting |
| Missing manifest | H023 `NOT_FOUND` | Preparation stops |
| Conflicting manifest | H023 `CONFLICTING_MANIFESTS` | No candidate selected |
| Missing adapter | H024 `MISSING_ADAPTER`, H027 `NEEDS_ADAPTER` | Manifest remains valid; no connection |
| Incompatible adapter/runtime | H024 typed failed dimensions | Preparation blocked |
| Connection unavailable | H013 typed failure | No channel/session invented |
| Stale generation/session | H013/H027/H029 correlation result | Callback/evidence ignored or negotiation blocked |
| Capability not negotiated | H029/H030 missing prerequisite | Dispatch denied |
| Missing trust | H030 `TRUST_REQUIRED` | Dispatch denied |
| Missing permission | H030 `PERMISSION_REQUIRED` | Dispatch denied |
| Approval required | H030 `APPROVAL_REQUIRED` | Dispatch denied until separately approved |
| Calibration required/invalid | H031 readiness | Capability blocked; no safety implication |
| Provider unavailable | H008/H029 runtime evidence | Negotiation/dispatch blocked |
| Cancellation | H013/H027/H029 typed cancellation | Terminal cancellation; no later advancement |
| Corrupt adapter state | H026 metadata digest/decode error | Restore fails closed or uses only verified backup |
| Corrupt provider state/key loss | H032 protected-store error | Restore fails closed; no identity invented |
| Diagnostic export | H034 redacted snapshot | Evidence only; all authority/control properties false |

The focused suites execute these cases, including duplicate/conflicting manifests, stale callbacks,
cleanup, persistence corruption/truncation, authorization adversarial cases, calibration status, UI
mapping, and diagnostic round trip/redaction.

## Provider-instance continuity

`ProviderInstanceStoreTest` proves persist/restart restores exact provider identity and revision while
the private instance ID is encrypted at rest. It separately proves that restart restores no grants,
approvals, runtime sessions, connections, registration, or active-body state. Corrupt ciphertext,
wrong/missing keys, unsupported schema, and invalid records fail closed; a backup is used only after
successful protection/decode validation and produces explicit recovery evidence.

## Architecture, privacy, and security findings

- The H019 architecture guard passes for all pure HUGO package roots, including H034 diagnostics.
- No HUGO Core-to-LOOI dependency exists.
- Generic contracts contain no Android/Bluetooth/GATT types or raw FED protocol constants.
- Discovery, manifest, compatibility, adapter knowledge, negotiation, authorization, calibration,
  diagnostics, and persistence preserve their documented non-authority boundaries.
- A tracked production/resource secret-assignment scan reported `0` findings.
- A generic-source scan for `FED*`, `BluetoothGatt`, `BluetoothDevice`, `ScanResult`,
  `android.bluetooth`, and `body.looi` reported `0` findings.
- The H034 portable fixture scan for UUID/MAC/private/protocol/credential shapes reported `0`
  findings, and its decoded authority/control properties are all false.
- No credential, token, provider secret, private transport address, raw packet, or real integration
  was added by H035.

## T032 physical regression decision

No H021-H034 commit changed or rerouted the accepted physical LOOI path. A Git diff from the locked
T032 checkpoint through the H034 prerequisite reports no changes under the authoritative safety,
motion authority, dead-man, manual-control, existing LOOI protocol, connected-service, or
`MainActivity` paths. Later LOOI capability mapping/facade and BLE connection-provider wrapper files
are additive; repository reference search confirms they are not composed into the production manual
path.

Therefore no repeated real-LOOI run is required. T032 remains the physical authority for:

- `SafetyController`/deterministic safety ownership;
- dead-man timing and stop behavior;
- local E-stop and stationary reset;
- motion leases and reconnect invalidation;
- directional lower-contact sensor gates;
- conservative movement/head/light limits; and
- the manual control and existing BLE/GATT/protocol path.

H035 makes no new hardware claim.

## Automated and Android build evidence

Run on 2026-08-26 with Android Studio JBR and the repository Android SDK:

| Check | Result |
|---|---|
| H035 fake-family/LOOI/fault/privacy acceptance test | PASS |
| Focused H021-H034 plus H017/H018/architecture/baseline JVM suites | PASS |
| `./gradlew testDebugUnitTest` | PASS: 503 tests, 66 suites, 0 failures/errors/skips |
| `./gradlew lintDebug` | PASS |
| `./gradlew assembleDebug` | PASS; debug APK produced |
| HUGO V2 documentation guard | PASS |
| T032 baseline evidence guard | PASS |
| Architecture dependency guard | PASS |
| Secret/privacy leakage scans | PASS: 0 findings in all three scans |
| T032 authoritative-path Git diff audit | PASS: no changed path |
| `git diff --check` | PASS |

## Unresolved risks and freeze decision

- The universal flow is not yet production-composed into an end-to-end provider vertical slice.
- No real non-LOOI BODY, DEVICE, or SERVICE integration has been installed or tested.
- H028 is an additive wrapper over the verified transport, but no production provider currently uses
  it through HUGO orchestration.
- H018 defines an adapter facade and equivalence boundary; it does not route manual LOOI control.
- Diagnostics remain unwired; artifact lifecycle, user consent, UI, deletion, and transfer policy
  remain future work.
- T032 physical evidence retains its documented isolated-bound-service and measured-RF exceptions.

These are explicit post-M3 integration/product risks, not acceptance failures. The M3 contracts,
deterministic fake runtime, failure boundaries, persistence, privacy controls, LOOI semantic mapping,
and unchanged T032 path pass acceptance. **M3 is accepted and ready to freeze.**

## Next boundary

H036 is eligible and has not started. H035 adds no M4 context runtime, voice, AI, memory, World Model
runtime, dashboard, provider selection, autonomy, Home Assistant, real integration, or new LOOI
behavior.
