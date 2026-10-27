# Reproduce the isolated Android demonstration

Build with JDK 17+ and Android SDK 35:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python scripts/check-showcase.py --built
```

Use a fresh emulator. Specify its serial explicitly; do not target an owner's
phone. Install the debug APK and the test APK only on that emulator, then run
`io.redi.robot.showcase.ShowcaseAcceptanceTest` through AndroidJUnitRunner.
That test verifies the emulator marker, exact showcase application ID, absent
permissions, and absence of the original live activity/device service before
performing any walkthrough interaction.

Scenario: Runtime → Safety → propose light → explicit approve → overspeed
rejection → stale-command rejection → generation invalidation → local stop →
Memory → candidate → rejected model self-validation → user correction draft →
Architecture. The demonstration uses the existing HUGO factories/reducers;
the surrounding UI and confirmation shell were added for evaluation.

The memory lab never persists envelopes. A user correction constructs a successor
draft; it is not a claim of committing an encrypted durable change-set.
The instrumented H059 storage suite is a separate synthetic-data test and must
be reported separately from the UI demo and from physical-device verification.

Generated screenshots are saved to the showcase app's external files directory.
Only reviewed captures enter `docs/assets/`; screenshots from the original
private installation must not be substituted.
