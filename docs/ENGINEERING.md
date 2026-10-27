# Engineering map

## A practical code tour

1. [Authorization gate](../app/src/main/java/io/redi/robot/trust/runtime/AuthorizationGate.kt):
   inspect the conditions between a request and permission to execute.
2. [Motion authority reducer](../app/src/main/java/io/redi/robot/core/authority/MotionAuthority.kt)
   and [local safety](../app/src/main/java/io/redi/robot/safety/SafetyController.kt):
   follow leases, generations, value limits and stale-command rejection.
3. [Serialized GATT queue](../app/src/main/java/io/redi/robot/body/looi/ble/queue/GattOperationQueue.kt)
   and its [adversarial tests](../app/src/test/java/io/redi/robot/body/looi/ble/queue/SerializedGattOperationQueueTest.kt):
   transport ordering, cancellation and callback correlation.
4. [Semantic memory factory](../app/src/main/java/io/redi/robot/memory/semantic/SemanticMemory.kt)
   and [tests](../app/src/test/java/io/redi/robot/memory/semantic/SemanticMemoryTest.kt):
   provenance, candidate validation and explicit correction semantics.
5. [Authenticated change-set journal](../app/src/main/java/io/redi/robot/memory/storage/MemoryChangeSetJournal.kt)
   and [durability tests](../app/src/test/java/io/redi/robot/platform/storage/AtomicFileMemoryChangeSetJournalDurabilityTest.kt):
   inspect interrupted writes and recovery paths, not only round trips.
6. [Architecture dependency guard](../app/src/test/java/io/redi/robot/architecture/ArchitectureDependencyGuardTest.kt):
   executable import constraints around the pure Kotlin boundaries.

## System coverage

| Concern | Implemented mechanism | Inspect |
|---|---|---|
| Persistent identity | Body/provider-independent identity; atomic bootstrap | `hugo/identity`, `firstlife/identity` |
| Capability contracts | Typed versioned descriptors, request/result envelopes | `capability/api`, `manifest/domain` |
| Trust and runtime policy | Scoped grants, freshness, approval consumption, revocation | `trust/domain`, `trust/runtime` |
| Discovery and providers | Identity/manifest resolution, compatibility, lifecycle, calibration | `discovery`, `provider`, `adapter` |
| Motion authority | Explicit monotonic leases, source priority, generation revocation | `core/authority` |
| Physical safety | Bounded values, stale-command rejection, contact policy, local E-stop | `safety` |
| BLE transport | One in-flight GATT operation, correlation, timeout/cancel and stop priority | `body/looi/ble`, `platform/transport` |
| AI mediation | Bounded context, strict proposals, replay and idempotency ledgers | `conversation`, `ai/tools`, `gateway` |
| Voice and observation | Explicit capture, local VAD, barge-in, bounded frame/session ownership | `voice`, `firstlife`, `platform/vision` |
| Context | Bounded snapshots, freshness, privacy, non-authority observations | `context` |
| Memory governance | Candidate semantics, provenance, conflicts, allowed use, retention | `memory/domain`, `memory/semantic`, `memory/personal` |
| Memory persistence | AES-GCM, Keystore boundary, authenticated journals, atomic corrections | `memory/storage`, `platform/storage` |
| Proof | Negative fixtures, dependency guards, real-file tests, device suites | `app/src/test`, `app/src/androidTest` |

Package paths are under `app/src/main/java/io/redi/robot/`. Tests are part of the
engineering material, not just a numeric badge. Look at rejected and stale paths
as well as success cases: a model cannot approve itself, discovery cannot grant
trust, old generations cannot regain authority, and corrupt persistence does
not silently become an empty trusted memory.

Historical ADRs, per-task evidence, H001–H080 ledger and frozen First Life task
ordering remain available under `docs/`. They document different evidence levels.
The showcase does not advance the H062 product boundary.
