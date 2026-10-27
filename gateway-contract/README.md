# HUGO AI Gateway Contract

Version `1.0` is a provider-neutral interchange contract for text, realtime, vision, and embedding
operations. It defines strict request, cancellation, and streamed-event documents. It does not
define authentication, transport, endpoint selection, provider selection, storage, or a backend.

Compatibility is major-version strict. A consumer requiring `1.0` may accept a later `1.x` document
only when it tolerates additive optional fields declared by that later minor version; an older minor
or a different major must fail closed. The H051 `1.0` codec itself rejects unknown fields.

Every stream starts with sequence `0` / `started`, uses consecutive sequence numbers and unique event
IDs, and ends with exactly one of `completed`, `refusal`, `failed`, `cancelled`, or `timed_out`.
`usage` may occur at most once before the terminal event. A `tool_proposal` names an H050 skill but is
only untrusted model output: it is not a capability request, authorization, safety decision, or
execution instruction.

The schemas and examples contain no provider names or types, credentials, secrets, raw robot packets,
stable device identifiers, raw audio, or image bytes. Vision uses an opaque, request-scoped attachment
reference. Realtime declares modalities but leaves media transport to a later authenticated protocol.
