# HUGO World Model, Spatial Reasoning, and Affordances

## Status and canonical ownership

This document is the canonical architecture owner for HUGO world entities, spatial relationships, geometry evidence, physical affordances, and feasibility reasoning. Other documents link here and retain authority over their own domains: provider manifests describe provider claims, Context Engine bounds task context, Memory Architecture governs persistence, and Trust/Safety authorize execution.

The concepts are accepted architecture but are not implemented by H004. No current LOOI attachment, geometry, spatial, or affordance semantic is newly claimed.

## World entity model

A `WorldEntity` is a durable logical reference to something that may be observed over time. Future entity classes may include people, rooms, zones, furniture, objects, devices, bodies, vehicles, doors, surfaces, containers, and environmental regions.

Entity identity is separate from observations, provider identity, connection identity, and active context. One observation may propose an entity match; it does not create verified identity by itself. Conflicting or ambiguous matches remain explicit.

## Spatial relationships

The future relation vocabulary may include `ON`, `UNDER`, `INSIDE`, `NEAR`, `ATTACHED_TO`, `MOUNTED_ON`, `IN_ROOM`, `IN_ZONE`, `FACING`, and `REACHABLE_FROM`. Relations are typed statements carrying subject, object/reference frame, provenance, observation time, freshness/expiry, evidence class, confidence, uncertainty, and validation status.

Relations are not assumed complete or exact. `CONNECTED` is not `MOUNTED_ON`; BLE reachability is not attachment evidence; selected provider is not active body; absence of an observation is not proof that a relation is false.

## Geometry and physical properties

Relevant properties may include width, height, depth, clearance, position, approximate distance, orientation, traversable space, body footprint, turning radius, sensor range, camera viewpoint, payload limits, and uncertainty bounds. Values require units, reference frame where applicable, provenance, timestamp, evidence class, and validation state.

Providers need not expose every property. Missing, stale, conflicting, or unknown geometry is a first-class state. Vendor-specific packets, pins, SDK types, and transport fields never enter the World Model.

## Evidence classification

World statements use a project-owned evidence class plus independent confidence and uncertainty. The classes are not a universal total ordering; suitability depends on source, age, scope, and task.

| Evidence class | Meaning |
|---|---|
| `VERIFIED` | Confirmed by an accepted authoritative source or controlled verification for the exact entity/version/scope. |
| `MEASURED` | Produced by a calibrated or characterized measurement with units and uncertainty. |
| `OBSERVED` | Direct observation whose interpretation has not reached verified/measurement status. |
| `LEARNED` | Retained pattern derived from prior outcomes with provenance and applicability limits. |
| `INFERRED` | Derived from other current statements through an explicit reasoning step. |
| `GENERAL_PRIOR` | General knowledge useful for proposing a hypothesis, not entity-specific fact. |
| `UNKNOWN` | No adequate current evidence. |

For example, a manufacturer specification may make vacuum height `VERIFIED` for one model/version; a sensor result may make table clearance `MEASURED`; a typical-table statement remains `GENERAL_PRIOR`. Confidence never promotes one class silently into another.

## Affordance model

An affordance asks: what action is physically possible for this provider, on this target, in this environment, under current constraints? Capability presence alone is insufficient.

Conceptually, candidate selection evaluates:

```text
Capability compatibility
+ physical feasibility
+ environment and geometry
+ provider/current state
+ trust and permission
+ required approval
+ deterministic safety
= eligible candidate action provider
```

An affordance assessment records requested action, provider, target/environment, supporting and conflicting evidence, assumptions, unknowns, constraints, confidence, expiry, and one outcome: `FEASIBLE`, `INFEASIBLE`, `VERIFICATION_REQUIRED`, or `UNKNOWN`. It proposes eligibility; it never grants permission or bypasses provider safety.

Examples:

- LOOI on a tabletop: tabletop movement may be feasible within verified safety constraints; descending to the floor is unsupported/prohibited.
- Low-profile camera vacuum under a table: inspection may be feasible if dimensions, route, state, permission, and local device safety allow it.
- Robot arm pickup: feasible only when reach, payload, gripper, object, and environment constraints are compatible.

## Feasibility before experimentation

- If verified/current geometry rules an action out, do not attempt it.
- If evidence is incomplete but risk is acceptably bounded, request a read-only or low-risk verification step using authorized sensors.
- If uncertainty creates unacceptable safety risk, reject or request more evidence/user approval.
- Never send every available body to attempt a task when known constraints already show failure.

Verification steps are capabilities subject to normal trust, permission, approval, freshness, cancellation, and safety policy. Failure or unknown outcome remains explicit.

## World Model, Context, and Memory boundaries

- World Model: current structured understanding of entities, relations, geometry, and physical environment, including unknowns and conflicts.
- Context: bounded authorized subset relevant to the current task/session.
- Memory: governed persistence of validated or confidence-bearing records over time.

An observation does not automatically become memory. A temporary inference does not become a permanent world fact. Context expiry does not delete validated memory, and memory cannot authorize physical action.

## Environmental memory

Future environmental records may preserve validated knowledge such as a named work table's room, dimensions, clearance, access paths, useful viewpoints, and prior provider access. Records require provenance, timestamps, confidence, validation/correction state, retention, conflicts, and derived lineage under [Memory Architecture V2](MEMORY_ARCHITECTURE_V2.md).

## Provider geometry metadata

[Provider manifests](DEVICE_BODY_MANIFEST.md) may claim body/device dimensions, footprint, camera pose, turning radius, payload, movement-surface constraints, and calibration/verification status. Claims remain manifest evidence, not authority or automatically verified world facts. The World/Affordance layer consumes resolved, provenance-bearing metadata without knowing vendor protocols.

LOOI geometry, host mounting state, and movement-surface constraints require specific evidence. BLE connection alone cannot establish physical mounting.

## Strategy learning

Successful interactions may create strategy-memory candidates containing context, provider capabilities, world assumptions, constraints, attempts, outcomes, failures, evidence, confidence, and transfer limits. A low-profile camera-body alternative is a candidate strategy, not an unconditional rule and not authority for execution.

## Multi-provider pencil example

For a pencil possibly under a work table, future reasoning should represent:

1. Hugo's current active body is LOOI, with evidence that it is on the tabletop.
2. LOOI cannot safely descend and lacks the required below-table viewpoint.
3. The subtask requires an authorized observation capability with physical access below the table.
4. A camera-equipped vacuum is a candidate provider, not automatically eligible.
5. Vacuum dimensions and table clearance are compared with evidence class, confidence, units, and uncertainty.
6. General knowledge may propose feasibility but remains `GENERAL_PRIOR`/`INFERRED`, never entity fact.
7. If risk permits, the vacuum may verify clearance with its own authorized sensors before proceeding.
8. Trust, permission, approval, current state, route feasibility, and device-local safety still apply.
9. A successful outcome may later become a bounded strategy candidate.

Using another provider does not change Hugo identity or necessarily change the active body.

## Backlog coverage audit

| Concern | Existing partial coverage | Gap disposition |
|---|---|---|
| Provider geometry claims | H010-H011, H023, H029, H031 | Specialized geometry schema needs focused amendment review. |
| World observations/context | H036-H039, H044 | No canonical entity/relation/world-state contract task exists. |
| Environmental persistence | H058-H067 | H062 covers provider memory, not general environment memory. |
| Provider selection | H068-H072 | Capability selection lacks explicit physical-affordance evaluation. |
| Perception observations | H078 | Detection/tracking does not itself establish world identity or geometry truth. |
| Simulation/verification | H072, H079 | No dedicated world/affordance verification corpus is scheduled. |

The following focused amendments are proposed for a future backlog review without assigning IDs or renumbering H001-H080:

1. M4: world entity and spatial-relation contracts.
2. M4: unit-safe geometry, reference-frame, measurement, uncertainty, and evidence model.
3. M4: bounded current world-state reducer/store with conflict and expiry handling.
4. M6: environmental memory records and reconciliation.
5. M3/M4: provider-manifest geometry and sensor-viewpoint metadata extension.
6. M7: pure affordance/physical-feasibility evaluator.
7. M7: planner/provider-selection integration with bounded verification proposals.
8. M7/M9: simulation corpus and controlled physical acceptance for feasibility decisions.

These are architecture gaps, not permission to implement future tasks during H004.
