# Contributing to this evaluation snapshot

This is a separate presentation copy of HUGO. SC001 in
[`docs/SHOWCASE_TASK.md`](docs/SHOWCASE_TASK.md) defines the current authorized
work. The original HUGO ledger and per-task evidence remain historical product
authority; this showcase does not implement H062 or any successor task.

Preserve pure-package architecture guards, deterministic injected clocks and
transports, safety limits, negative fixtures, authorization and memory contracts.
Do not weaken tests to make a presentation pass. Keep proposals, permission,
approval, execution and physical acceptance distinct. Never wire a real provider
or register the original live activity/service in the evaluation APK.

Required gate: `./gradlew testDebugUnitTest lintDebug assembleDebug
assembleDebugAndroidTest`, `python scripts/check-showcase.py --built`,
`pwsh -File scripts/check-hugo-first-life-docs.ps1`, Gitleaks and `git diff --check`.
Instrumented-suite compilation is not execution. Run the showcase walkthrough
only on a separately identified emulator; original real-device suites require
their own explicit device scope and hardware authorization.

Never commit keys, memory content, recordings, real addresses, installed app data,
machine paths or unreviewed media. Third-party source needs a real grant of rights;
attribution alone is not permission. Product and media changes require matching
evidence and fresh owner review before publication.
