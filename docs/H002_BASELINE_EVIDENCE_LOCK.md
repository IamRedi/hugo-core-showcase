# H002 T001-T032 Baseline Evidence Lock

## Record

- Task: `H002 - Baseline evidence lock`
- Status: `DONE`
- Completed: 2026-08-25
- Prerequisite: H001 commit `ee35539cd1985f64f9bbf62dd9b386316a3e4a1e`
- Locked checkpoint: T032 commit `0bcf536cdaae3b5c9c22247bb093ac6fc68cff26`
- Runtime impact: none
- Hardware claim: none

## Lock mechanism

[`architecture/evidence/t001-t032-baseline.json`](../architecture/evidence/t001-t032-baseline.json) is the machine-readable baseline record. It locks:

- the T001-T032 range and historical `SPARK_TASKS.md` ledger;
- the immutable T032 commit, parent, complete repository tree, timestamp, and subject;
- the accepted H001 Architecture V2 commit and tree;
- the complete 139-file T032 repository tree;
- every tracked file under the T032 `docs` and protocol-fixture evidence roots;
- 16 historical documents and 14 fixtures through exact Git blob IDs;
- explicit negative claims that H002 changed no runtime behavior, re-signed no history, and performed no new hardware verification.

Git commit, tree, and blob IDs make the references immutable. The manifest indexes historical objects rather than copying, normalizing, rewriting, or re-signing their contents. Existing working-tree artifacts that were never part of T032 remain outside the checkpoint and are neither deleted nor promoted by H002.

## Validation

`scripts/check-t032-baseline-evidence.ps1` verifies that both commits and trees exist, H001 directly descends from T032, current HEAD descends from H001, repository/root counts match, every indexed path is complete and unique, every blob resolves exactly at the T032 commit, and the H001 acceptance record exists in the H001 commit.

`BaselineEvidenceManifestTest` parses the manifest with strict keys and types. It verifies the accepted immutable IDs, task range, counts, required evidence, safe repository-relative paths, unique evidence entries, and negative runtime/history/hardware claims. Negative tests reject unsupported schemas, mutable/malformed object references, duplicate evidence paths, and path traversal.

## Completion checks

- HUGO V2 documentation guard: PASS
- T032 baseline evidence Git guard: PASS
- `./gradlew testDebugUnitTest`: PASS
- `git diff --check`: PASS
- Runtime/source changes: none; only test-source validation was added
- Fixture/history rewrites: none
- Android/device/hardware execution: not required and not claimed

## Scope boundary

H003 has not started. H002 adds no identity contract, provider contract, capability manifest, adapter, runtime migration, AI, memory, integration, Developer Mode, or autonomous behavior.
