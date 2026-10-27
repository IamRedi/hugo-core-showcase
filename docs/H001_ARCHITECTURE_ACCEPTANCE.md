# H001 Architecture V2 Baseline Acceptance

## Record

- Task: `H001 - Accept V2 baseline`
- Status: `DONE`
- Accepted: 2026-08-25
- Accepted predecessor: T032 at commit `0bcf536cdaae3b5c9c22247bb093ac6fc68cff26`
- Runtime impact: none
- Hardware claim: none; this record preserves rather than repeats T032 physical evidence

## Accepted document set

- `HUGO_VISION.md`
- `ARCHITECTURE_V2.md`
- `UNIVERSAL_CAPABILITY_MODEL.md`
- `DEVICE_BODY_MANIFEST.md`
- `ADAPTER_ARCHITECTURE.md`
- `TRUST_AND_PERMISSIONS.md`
- `MEMORY_ARCHITECTURE_V2.md`
- `CONTEXT_ENGINE.md`
- `DEVELOPER_MODE.md`
- `MIGRATION_PLAN_V2.md`
- `ROADMAP_V2.md`
- `HUGO_TASKS.md`
- ADR-011 through ADR-018 in `DECISIONS.md`

## Accepted invariants

1. Hugo identity, user relationship, personality, and personal memory are independent of host, body, device, service, and AI provider.
2. LOOI is Reference Body Adapter #1. Its T001-T032 behavior, safety path, protocol evidence, and physical acceptance remain migration invariants.
3. `CapabilityProvider` is the shared semantic boundary; body, device, and service provider families retain distinct roles and policy.
4. Capability and provider contracts are project-owned, typed, versioned, platform-neutral, and free of Android, vendor SDK, raw packet, and AI-provider types.
5. Manifests describe compatibility claims and provenance. They never establish trust, grant permission, install code, select a provider, or authorize execution.
6. Discovery, identity, trust, permission, approval, deterministic safety, execution, and observed outcome remain separate decisions.
7. Physical action always retains deterministic local authority and provider safety. No universal layer may bypass the verified LOOI safety controller.
8. Context is bounded and ephemeral by default. It cannot authorize action or become durable memory without an explicit governed memory-candidate path.
9. Memory is local-first, provenance-bearing, privacy-scoped, user-governed, and independent of the active body; body/device records remain instance and version scoped.
10. Runtime Mode cannot research, edit, compile, install, enable, or deploy production code. Developer Mode is a separate user-invoked sandbox and review flow.
11. Migration is parallel and incremental. The verified legacy LOOI runtime stays available until facade equivalence and required physical regression are proven.
12. Reconnect, provider switching, context, memory, AI, and manifests cannot replay actions, grants, leases, approvals, or safety authority.

## Consistency audit

The complete V2 document set was reviewed against the repository and the accepted T032 checkpoint. Terms, dependency direction, lifecycle ownership, provider families, trust boundaries, memory/context boundaries, Developer Mode boundary, migration order, roadmap, and backlog are mutually consistent. No contradiction requiring a new architecture decision was found.

The old T033-T064 backlog is explicitly classified in `MIGRATION_PLAN_V2.md`. The replacement backlog contains exactly 80 unique tasks from H001 through H080. H001 introduces no universal runtime contract or implementation; those begin only in later tasks.

## Completion checks

- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-hugo-v2-docs.ps1`: PASS
- `git diff --check`: PASS
- Required V2 documents present and non-empty: PASS
- Relative documentation links resolve: PASS
- H-task sequence `H001-H080`, unique and complete: PASS
- Historical migration matrix `T033-T064`, unique and complete: PASS
- ADR headings `ADR-011` through `ADR-018`, unique and complete: PASS
- T032 checkpoint exists, is an ancestor, and remains recorded `DONE`: PASS
- Runtime/source/dependency changes: none
- Gradle/unit/lint/assemble: not required because H001 changes documentation and its documentation guard only

## Scope boundary

H002 has not started. No machine-readable T001-T032 evidence manifest, capability contract, provider implementation, runtime migration, AI, memory, integration, or autonomy work is included in H001.
