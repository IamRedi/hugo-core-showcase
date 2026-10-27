# H049 Speech Adapter Contracts

## Record

- Task: `H049 - Speech adapter contracts`
- Status: `DONE`
- Completed: 2026-09-01
- Prerequisites: H046 local intent router, H047 audio session reducer, H048 explicit capture/local VAD
- Runtime impact: none; H049 is an unwired provider-neutral contract and fake composition
- Hardware claim: none

## STT and TTS contract model

H049 adds the Android-free `voice/api` package. Recognition and synthesis ports use project-owned,
redacted `SpeechOperationId` values together with the existing H047 `AudioSessionId` and monotonic
timestamps. Exact locale, request, deadline, start, cancellation, snapshot, failure, and terminal
events are typed and bounded.

Recognition requests explicitly require an H048 capture session and reuse its bounded
`AudioCaptureConfiguration`; they do not expose PCM arrays or allow continuous/background capture.
The maximum recognition operation is 30 seconds. Recognition distinguishes accepted, busy,
unsupported-locale, unavailable, and invalid-request starts, plus started, final-transcript,
no-match, cancelled, and typed failure events.

Synthesis requests carry a redacted, validated text value of at most 1,000 code points and a maximum
60-second deadline. Synthesis has the same deterministic start/cancel correlation and exposes
started, completed, cancelled, and typed failure events. No contract identifies or selects an STT,
TTS, AI, cloud, or local-engine implementation.

## Transcript privacy and local-first routing

`EphemeralSpeechTranscript` accepts one to three bounded alternatives. It is a one-shot envelope:
the coordinator atomically removes its references before passing the alternatives to H046, and
stale, wrong-correlation, late, or otherwise rejected callbacks explicitly discard the envelope.
Diagnostics redact transcript and synthesis text.

The envelope does not claim that immutable JVM strings can be zeroized and cannot control whether a
future adapter retains its own copy. Such an adapter must separately satisfy the no-retention policy.
H049 itself stores no transcript, raw audio, synthesis text history, conversation, credential, token,
provider identity, or user content after the active operation ends.

`LocalSpeechInteractionCoordinator` routes a final transcript through the exact H046
`LocalIntentRouter` before any later AI path. Its output is still only a
`LocalCapabilityProposal`/routing report:

```text
SPEECH RECOGNIZED
!= PROVIDER SELECTED
!= CAPABILITY REQUEST CREATED
!= TRUSTED
!= AUTHORIZED
!= SAFE
!= EXECUTED
```

The deterministic fake trace proves `connection status` becomes the existing
`provider.connection.status@1.0` local proposal. Unknown and ambiguous alternatives remain
non-executable reports. H049 deliberately does not create H007 requests or invoke a provider; H050
and later mediation own capability-backed skills, provider selection, authorization, safety, and
dispatch.

## Audio focus, interruption, and correlation

`SpeechAudioFocusPort` separates recognition and speech-output focus usage and distinguishes granted,
denied, unavailable, and busy acquisition plus exact release outcomes. Focus events carry the same
session/operation correlation. The coordinator acquires focus before starting either adapter and
releases it on final transcript, no match, completion, failure, deadline, rejection, or interruption.

User cancellation, backgrounding, new speech, and focus loss are first-class interruption reasons.
An interruption clears coordinator ownership before requesting adapter cancellation, preventing a
synchronous or late callback from reviving completed work. Captured attempt ordinals also reject an
old callback even if a caller incorrectly reuses the same operation ID. Wrong-session and stale-time
interruptions do nothing; repeats are deterministic and cannot cancel twice.

This is a contract-level audio-focus port only. H049 adds no Android `AudioManager` adapter and does
not alter the accepted FL009/FL010 audio-focus, recognizer, TTS, permission, or lifecycle path.

## Deterministic conversation trace

The fake end-to-end trace is content-free and stable:

```text
RECOGNITION_STARTED
LOCAL_INTENT_PROPOSED:provider.connection.status@1.0:execution=false
SYNTHESIS_STARTED
SYNTHESIS_INTERRUPTED:NEW_SPEECH:REQUESTED
```

The trace contains neither the recognized phrase nor synthesis text. It demonstrates local-first
routing and TTS interruption only; it is not evidence of speech quality, provider availability,
authorization, capability execution, or physical action.

## Coverage and checks

`LocalSpeechInteractionCoordinatorTest` contributes 12 tests covering:

- fake recognition to exact local status proposal with immediate transcript consumption;
- unknown and ambiguous transcripts with no execution;
- TTS interruption, focus-loss cancellation, exactly-once release, and late callback rejection;
- focus denial, adapter rejection, typed terminal failures, and synthesis completion;
- deadline, wrong operation/session, stale time, repeated interruption, and operation-ID reuse;
- request/text/transcript bounds, redacted diagnostics, and one-shot transcript behavior;
- deterministic content-free conversation trace; and
- absence of Android, First Life, provider, credential, network, transport, and LOOI coupling.

The architecture dependency guard now protects `voice/api` and permits only its deliberate inward
dependencies on H047 `voice/session` and H048 `voice/capture`; H046 remains a separate pure local
intent dependency. Required checks pass: First Life documentation/order guard, HUGO V2 documentation
guard, T032 baseline evidence guard, architecture dependency guard, focused H049 tests, full
`testDebugUnitTest` (799 tests across 109 suites; zero failures/errors/skips), `lintDebug`,
`assembleDebug`, and `git diff --check`.

No Android-device or LOOI verification is required or claimed. H049 adds no platform adapter,
production wiring, permission, audio resource, provider connection, safety change, or physical path.

## Next boundary

H050 is eligible and has not started. H049 does not choose production STT/TTS, implement an Android
speech adapter, create an AI gateway, register a capability-backed skill, select a provider, grant
authority, retain conversation or memory, or execute any capability.
