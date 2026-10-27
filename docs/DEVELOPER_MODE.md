# HUGO Developer Mode

## Boundary

Runtime Mode executes only installed, enabled, compatible, trusted adapters and authorized capabilities. Developer Mode is a separate, user-invoked development environment. Production runtime never edits, compiles, installs, or deploys itself.

## Missing-adapter workflow

1. Runtime records a redacted `DevelopmentNeed` containing observed identity evidence, desired capability, missing/incompatible adapter reason, and consent scope.
2. User explicitly opens Developer Mode.
3. A sandbox may research official documentation/SDKs and legally reusable sources.
4. It creates a versioned integration proposal with provenance, license analysis, manifest, capability mapping, permissions, threats, tests, and rollback plan.
5. Implementation occurs in isolation with no production credentials or uncontrolled hardware authority.
6. Automated tests and static/security checks run.
7. Hardware verification is requested when applicable.
8. User reviews permissions, risks, evidence, and signature/integrity metadata.
9. Installation and enablement are separate explicit approvals.

## Prohibitions

- No guessed raw writes to unknown devices.
- No autonomous production deployment or silent adapter update.
- No production secrets in coding sandboxes.
- No bypass of vendor terms, licensing, privacy, or safety requirements.
- No capability escalation beyond reviewed manifests and grants.
- No live modification of deterministic safety policy by an AI model.

## Adapter package proposal

A proposal contains adapter ID/version, supported provider/firmware/API ranges, source provenance, dependencies/licenses, manifest templates, capability mappings, permission requirements, safety/threat model, test evidence, hardware status, migration/rollback behavior, integrity metadata, and reviewer decisions.

## Runtime installation

The Adapter Registry verifies compatibility/integrity, records approval, installs disabled, and requires explicit enablement. Upgrade does not preserve active sessions or authority automatically. Failed verification rolls back or disables the adapter.
