# Memory Architecture

## Objective

Personalize through explicit, correctable records and retrieval—not continuous base-model retraining or full-history prompting.

## Record model

Every memory record has:

- immutable ID, type, schema version;
- subject/entity links and normalized content;
- source provenance (`user_explicit`, `observed`, `tool_result`, `conversation_summary`, `model_inferred`);
- source reference and creator;
- created/observed/updated timestamps;
- confidence and validation state;
- privacy class and allowed uses;
- retention/expiry policy;
- supersedes/conflicts-with links;
- deletion tombstone/audit metadata;
- optional embedding model/version, never used as the canonical value.

## Types

- User profile and preferences
- People and relationships
- Places
- Routines
- Facts
- Important events and episodic memories
- Robot experiences
- Task history and outcomes
- Feedback/corrections
- Conversation summaries

Keep profile/fact state separate from episodic evidence. A summary never destroys its source references.

## Trust lifecycle

```text
candidate -> validated -> active -> superseded/deleted/expired
             |             |
             +-> disputed <-+
```

Model-created memories start as candidates. Deterministic validators check schema, source, sensitivity, duplication, contradictions, and confidence. Sensitive facts, identities, durable preferences, and corrections generally require explicit user confirmation. Observations can decay and must not silently override explicit user statements.

## Retrieval

1. Classify the current task and permitted privacy scope.
2. Extract entities/time/location without persisting them automatically.
3. Retrieve exact structured facts and recent relevant episodes.
4. Optionally use lexical/vector candidates within the allowed partition.
5. Re-rank by relevance, recency, confidence, provenance, validation, diversity, and token budget.
6. Remove duplicates/conflicts or present the conflict explicitly.
7. Serialize a small context packet with source IDs so outputs remain auditable.

The entire history is never sent. Retrieval has item and token ceilings. Safety decisions do not depend on recalled preferences.

## Summarization and deduplication

- Summaries are derived records with covered time range and source IDs.
- Hierarchical compaction may create session, daily, and longer summaries.
- Canonical keys plus semantic similarity identify candidates; merging preserves all provenance.
- Corrections create a new version/supersession edge rather than rewriting history invisibly.
- Conflicting claims remain explicit until resolved; confidence alone does not pick truth.

## Privacy and user control

- Local encrypted database is the default canonical store; backup/sync is opt-in and separately encrypted.
- Privacy classes include local-only, gateway-allowed, provider-allowed, and never-model.
- UI supports inspect, explain “why recalled,” correct, delete, bulk delete, export, and retention settings.
- Deletion removes canonical content, search indexes/embeddings, caches, derived summaries where required, and schedules remote deletion if applicable; tombstones retain only minimal audit data.
- Logs never contain memory bodies by default.

## Storage boundaries

Repository interfaces live in the memory domain. SQLite/Room, vector index, encryption/keystore, remote sync, and model summarizers are adapters. The conversation layer receives only a `MemoryContextPacket`, not database access.

## Validation tests

- Candidate facts cannot become active without the rule appropriate to their type.
- Explicit correction outranks and supersedes older inferred records.
- Deletion removes exact, lexical, vector, cached, and derived retrieval paths.
- Retrieval respects privacy and token budgets under adversarial prompts.
- Same fact from multiple sources deduplicates without losing provenance.
- Embedding/model-version changes trigger safe re-indexing, not canonical data mutation.
