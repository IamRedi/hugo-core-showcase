# H055 to H056 handoff

## Authoritative checkpoint

- Branch at handoff preparation: `main`.
- Completed implementation checkpoint: H055 at commit
  `36637484989dbd6bd4b6f1b034cfdf54a6cc38f0` (`H055 add capability proposal validator`).
- H055 is `DONE`; H056 is eligible and has not started.
- The tracked worktree matched that commit before this documentation-only handoff change.
- Exactly 86 pre-existing untracked paths were present. They are user-owned evidence/workspace
  material and must not be modified, deleted, staged, normalized, or included in an H056 commit.

## Completed H055 boundary

H055 is a pure, unwired validator in `io.redi.robot.ai.tools`. It accepts only a proposal that is
present exactly once in the correlated H053 stream and H054 context, matches an exact-version H050
skill and fixed input, binds to a current exact-provider H007 request and current matching
capability-negotiation record, satisfies the informational/low-risk and confirmation rules, and
has an existing correlated `AUTHORIZED` H030 decision.

It rejects malformed or mismatched schema, target, actor, input, deadline, generation,
negotiation, risk, confirmation, trust, grant, approval, and replay evidence. Motion, raw
BLE/GATT/protocol semantics, e-stop reset/override, safety-limit mutation, authority mutation, and
adapter/package lifecycle semantics remain hard denials. Acceptance is validation evidence only:
it grants no authority, makes no safety decision, invokes no skill/capability/provider, and performs
no dispatch. Audit output is deterministic and content-redacted.

Relevant committed files:

- `app/src/main/java/io/redi/robot/ai/tools/ToolProposalValidationModel.kt`
- `app/src/main/java/io/redi/robot/ai/tools/ToolProposalValidator.kt`
- `app/src/test/java/io/redi/robot/ai/tools/ToolProposalValidatorTest.kt`
- `app/src/test/java/io/redi/robot/architecture/ArchitectureDependencyGuardTest.kt`
- `docs/H055_CAPABILITY_PROPOSAL_VALIDATOR.md`
- `docs/AI_ARCHITECTURE.md`
- `docs/HUGO_TASKS.md`

The H055 evidence records 13 focused tests and the completion run of 877 JVM tests across 115
suites, with no failures, errors, or skips; lint had no errors and `assembleDebug` passed. Handoff
revalidation also passed all three documentation/evidence guards and the focused H055 plus
architecture test classes.

## Invariants the next agent must preserve

- H030 remains the authority for trust, grant, approval consumption, authorization correlation,
  and authorization replay decisions. H055 may validate that evidence but may not create it.
- H050 remains the authoritative exact skill definition and authorized invocation boundary. A
  validated H055 proposal is not an executed skill or capability.
- H053 stream correlation/order and H054 bounded, privacy-filtered context/schema bindings remain
  exact. Tool schemas and model output carry no authority.
- H007 request/provider correlation and negotiated capability identity/version/generation/freshness
  remain exact and fail closed.
- H055's replay ledger is process-local, bounded (default 256, hard maximum 1024), synchronized,
  and non-evicting. It is defense in depth only; final H030/H050 replay protections remain
  authoritative and must still run at their owning boundaries.
- Local stop, manual control, e-stop, deterministic safety, T032 behavior, provider generation,
  cancellation, and disconnect semantics must retain priority and may not be weakened or bypassed.
- Pure packages must remain free of Android, vendor, LOOI, Bluetooth/GATT/FED, transport address,
  raw protocol, credential, and provider-implementation leakage. Preserve the H019 architecture
  dependency guard instead of adding a parallel boundary mechanism.
- Keep diagnostics redacted; do not persist or log raw audio, transcript content, secrets, stable
  device identifiers, transport addresses, or proposal argument content.

## Authoritative H056 starting scope

The status and task definition are owned by `docs/HUGO_TASKS.md`. Its H056 row specifies exactly:

| Field | Repository definition |
|---|---|
| Milestone/objective | M5 â€” Conversation/barge-in |
| Exact scope | Turn coordinator, local-first route, cancel/fallback |
| Expected module | `conversation/runtime` |
| Prerequisites | H046-H055 |
| Constraint | Local stop wins |
| Acceptance | Speech cancels generation/output |
| Tests | Fake timing/device |
| Non-goal | Memory writes |
| Evidence | Latency traces |
| Device level | Android audio |

Supporting repository documentation, without changing the task row, says that voice is
activation-gated rather than a continuous cloud stream, microphone ownership is session-bound,
barge-in cancels playback and in-flight generation, provider loss releases audio and any robot
lease, and raw audio is discarded unless explicit consent/policy says otherwise. The migrated
legacy T046 entry names latency timestamps, cancellation propagation, fallback messages, fake
realtime timing/cancellation, Android-device audio testing, and wake word as a non-goal.
`docs/MIGRATION_PLAN_V2.md` says conversation orchestration follows the capability/tool foundations.
These are supporting constraints, not permission to implement model policy, memory, or a new action
authority. If H056 needs behavior not stated in these documents, stop for an architecture decision
instead of inventing it.

H056 should begin from the existing H046 local intent router, H047 audio session reducer, H048
explicit-capture/local-VAD boundary, H049 provider-neutral speech contracts, H050 skill registry,
H051 gateway contract, H052 authentication, H053 client/reducer, H054 bounded context packet, and
H055 validator. Reuse those contracts; do not duplicate them.

## Validation commands

On this workstation, Gradle needs the Android Studio JBR and local Android SDK in the current
PowerShell process:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = '[private workspace path omitted]
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

Before H056, verify the checkpoint, tracked cleanliness, untracked count, guards, prerequisites,
and architecture boundary:

```powershell
git branch --show-current
git merge-base --is-ancestor 36637484989dbd6bd4b6f1b034cfdf54a6cc38f0 HEAD
git status --short --untracked-files=no
@(git ls-files --others --exclude-standard).Count
.\scripts\check-hugo-first-life-docs.ps1
.\scripts\check-hugo-v2-docs.ps1
.\scripts\check-t032-baseline-evidence.ps1
.\gradlew.bat :app:testDebugUnitTest --tests 'io.redi.robot.interaction.localintent.LocalIntentRouterTest' --tests 'io.redi.robot.voice.session.AudioSessionReducerTest' --tests 'io.redi.robot.voice.capture.LocalVoiceActivityDetectorTest' --tests 'io.redi.robot.voice.api.LocalSpeechInteractionCoordinatorTest' --tests 'io.redi.robot.skills.local.CapabilityBackedSkillExecutorTest' --tests 'io.redi.robot.gateway.contract.GatewayContractTest' --tests 'io.redi.robot.gateway.auth.GatewayTokenRepositoryTest' --tests 'io.redi.robot.ai.gateway.GatewayClientTest' --tests 'io.redi.robot.conversation.context.ConversationContextBuilderTest' --tests 'io.redi.robot.ai.tools.ToolProposalValidatorTest' --tests 'io.redi.robot.architecture.ArchitectureDependencyGuardTest' --no-daemon
git diff --check
```

After H056, rerun the guards and focused prerequisite command above, then run the complete JVM,
lint, build, and Android test artifacts. The repository does not yet define an H056 test class name,
so this handoff does not invent one; the full tasks include the H056 tests that are added:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
git diff --check
adb devices -l
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

Record fake timing/cancellation results, latency traces, and the Android-audio device result at
separate evidence levels. Do not claim device acceptance from JVM tests.

## Explicit do-not-change list

- Do not rewrite or reinterpret H001-H055, FL001-FL015, or locked T001-T032 evidence.
- Do not alter the 86 pre-existing untracked paths in any way.
- Do not weaken or bypass H030 authorization, H050 execution, H055 validation/replay, safety,
  manual-stop, dead-man, e-stop, provider-generation, cancellation, or disconnect boundaries.
- Do not add motion tools, arbitrary plans, raw packets, raw BLE/GATT/FED access, provider-specific
  AI types, secrets, trust/grant/approval creation, or install/enable/reset authority.
- Do not add wake-word behavior, continuous cloud microphone streaming, memory reads/writes,
  model-selection/cost policy (H057), backend deployment, or unrelated UI/LOOI/runtime refactors.
- Do not make a physical/runtime claim without the evidence level required by the H056 row.
- Do not start H057 or any later task while implementing H056.
