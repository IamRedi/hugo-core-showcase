# HUGO Context Engine

## Purpose

The Context Engine produces a bounded, current model of authorized state for local use cases, planning, and conversation. Context is not automatically memory.

## Inputs

Potential sources include time, coarse authorized location/presence, active host/body, connected providers, negotiated capabilities, battery, sensor observations, calendar, important email metadata, work status, routines, and recent interaction state.

Every observation includes source/provider instance, monotonic and optional wall time, freshness, confidence, privacy class, authorization scope, and expiry.

## Pipeline

```text
Authorized observations -> normalize -> freshness/conflict policy
-> task relevance -> privacy filter -> bounded ContextSnapshot
```

Snapshots contain source references and explicit unknown/conflict states. They do not turn absence into fact or infer long-term knowledge without a memory-candidate workflow.

When spatial state is relevant, a snapshot may contain a bounded authorized projection of the canonical [World Model and Affordance](WORLD_MODEL_AND_AFFORDANCES.md) representation. Context does not own or permanently validate world relations.

## Active provider context

The engine distinguishes connected providers, selected provider, active body, host device, and available services. Active body selection is explicit and expiring. Provider capability availability is a registry snapshot, not an identity trait of Hugo.

## Boundaries

- Context cannot grant permissions or bypass confirmation.
- Context observations cannot directly actuate hardware.
- Safety uses its own deterministic, freshness-specific inputs.
- Models receive only the fields required for a task.
- Sensitive service content is excluded unless purpose and scope allow it.

## Persistence

Working context expires. Significant events may create episodic/semantic/personal/device/body/strategy memory candidates through explicit policy. No source can persist itself merely by appearing in context.

## Testing

Virtual-time tests cover expiry, future timestamps, conflicts, provider disconnect, active-body changes, privacy filtering, deterministic ordering, and hard item/size budgets.
