# Coding Standards

- Kotlin is formatted with the official style; use clear domain names over abbreviations.
- Dependency direction is `feature -> domain contracts`; platform/body adapters implement contracts. Domain code does not import Android, BLE, AI SDK, database, or UI types.
- Model physical commands as sealed, typed values. Raw byte arrays stay private to protocol encoders.
- Use monotonic elapsed time for timeouts and leases. Inject clocks and schedulers into testable components.
- Treat cancellation and lifecycle shutdown as normal paths. Never launch unowned coroutines.
- Prefer immutable state and explicit state machines. Every transition must be testable.
- Return structured errors; redact identifiers and user content from logs.
- Every behavioral change requires tests at the lowest meaningful level.
- No dependency is added without a short rationale in the task completion notes.
- `TODO` comments include a task ID. Architectural deviations require a new ADR.
- Do not claim device or robot verification without linked evidence from the appropriate checklist.
