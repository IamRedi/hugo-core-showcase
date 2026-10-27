# H046 Local Intent Router

## Record

- Task: `H046 - Local intent router`
- Status: `DONE`
- Completed: 2026-09-01
- Prerequisites: H030 and accepted H045/M4 checkpoint
- Runtime impact: none; H046 is an unwired pure matcher and proposal boundary
- Hardware claim: none

## Model and API

H046 adds the provider-neutral `interaction/localintent` package. `LocalIntentRouter.route` accepts
one exact locale tag and one to three bounded transcript alternatives. It returns a typed
`LocalIntentRouteReport` with one of `PROPOSED`, `NO_MATCH`, `AMBIGUOUS`, `UNSUPPORTED_LOCALE`, or
`INVALID_INPUT`, plus canonical corpus/rule evidence.

A successful route contains `LocalCapabilityProposal`: an existing H005 `CapabilityReference`, an
optional existing H007 `CapabilityData` semantic input, and `NORMAL` or `STOP` priority. It is not an
H007 `CapabilityRequest`: it has no provider target, request/audit/execution correlation,
authorization reference, deadline, session, generation, or execution method.

```text
LOCAL INTENT MATCH
!= PROVIDER SELECTED
!= REQUEST CREATED
!= TRUSTED
!= AUTHORIZED
!= SAFE
!= AVAILABLE
!= EXECUTED
```

## Exact locale and normalization policy

Corpus version `hugo.local_intent@1` explicitly allows only `sq-AL` and `en-US`. Partial, inferred,
case-changed, underscore, language-only, or other locale tags are unsupported. There is no system
locale fallback.

Input normalization is deliberately narrow: NFC normalization, locale-independent lowercase,
outer-space trimming, and repeated ASCII-space collapse. It does not remove punctuation, fold
diacritics, expand synonyms, perform stemming, or use fuzzy matching. Blank input, more than three
alternatives, more than 160 code points, control characters, and Unicode formatting characters fail
closed. Transcript text is ephemeral and never appears in the report, evidence, or diagnostic
`toString`.

## Versioned intent corpus

| Rule family | `sq-AL` exact phrases | `en-US` exact phrases | Capability proposal |
|---|---|---|---|
| Stop | `ndalo`, `ndalo tani`, `stop` | `stop`, `stop now` | `mobility.stop@1.0`, input-free, `STOP` |
| Connection status | `statusi i lidhjes`, `a je i lidhur` | `connection status`, `are you connected` | `provider.connection.status@1.0` |
| Lower-contact status | `statusi i sensorëve të poshtëm` | `lower contact status` | `safety.lower_contact.status@1.0` |
| Light off | `fike dritën` | `light off`, `turn the light off` | `body.light.control@1.0` with `hugo.contract.body_light_input@1.0` and `enabled=false` |

The corpus uses only already accepted semantic capability and input contracts. It does not add
battery, quiet-mode, control-mode, motion, head, vendor, transport, or LOOI-specific commands where
no suitable accepted provider-neutral HUGO capability contract exists. Corpus/rule IDs, versions,
locale allowlists, phrase counts, and rules are validated and exposed as immutable ordered
snapshots.

## Ambiguity and stop priority

All matching rule candidates are evaluated independent of rule or transcript-alternative ordering.
If an exact stop candidate is present, only the canonical input-free `mobility.stop@1.0` stop
proposal is considered; this preserves the required local stop priority. STOP priority cannot be
assigned to another capability or to a stop carrying input.

Without a stop match, distinct plausible capability proposals return `AMBIGUOUS` with no proposal.
Multiple aliases that produce the same canonical proposal collapse deterministically. Unknown,
negated, extended, punctuated, malformed, or near-match phrases return no proposal rather than
guessing. Even a stop proposal remains evidence only; later H050/H055/H056 mediation owns request
construction, provider selection, authorization, cancellation, deterministic safety, and dispatch.

## Security and architecture boundary

H046 imports only project-owned capability API types and Java text/collection utilities. It has no
Android, Bluetooth, GATT, FED, vendor, LOOI, provider-instance, registry, authorization-gate,
dispatcher, network, AI, microphone, UI, storage, or memory dependency. It mutates no registry or
runtime state and cannot connect to or execute a provider.

The proposal/report APIs explicitly expose that they create no request, select no provider, grant
no trust or authorization, grant no safety approval, and execute no capability. H030 semantics are
unchanged: a later concrete H007 request still needs exact provider/negotiation correlation and a
real H030 decision before ordinary dispatch, while physical safety remains separately authoritative.

## Evidence and checks

`LocalIntentRouterTest` provides table and adversarial/property-style coverage for:

- every built-in Albanian and English exact phrase and typed light-off input;
- exact locale allowlisting, bounded case/space/NFC normalization, and redacted diagnostics;
- negation, substring, punctuation, injected text, zero-width/control characters, and bounded-input
  failures;
- prefix/suffix fuzz around action phrases;
- non-stop ambiguity with no proposal and exact semantic stop priority;
- equivalent output independent of alternative/rule ordering;
- invalid corpus/rule construction; and
- absence of provider, trust, permission, safety, execution, Android, vendor, transport, registry,
  dispatcher, and LOOI coupling.

Required completion checks pass: HUGO V2 documentation guard, First Life documentation/order guard,
T032 baseline evidence guard, architecture dependency guards, focused H046 tests, full
`testDebugUnitTest` (764 tests across 106 suites; zero failures/errors/skips), `lintDebug`,
`assembleDebug`, and `git diff --check`. The focused H046 class contributes 13 passing tests.

No Android/device or real-LOOI verification is required: H046 is pure, unwired, and changes no
voice capture, application UI, connection, provider, T032, safety, or physical runtime path.

## Next boundary

H047 is eligible and has not started. H046 adds no audio-session reducer, capture, STT/TTS adapter,
skill registry, AI gateway, conversation runtime, memory, provider execution, or physical behavior.
