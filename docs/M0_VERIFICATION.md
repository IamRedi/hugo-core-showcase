# M0 Verification Record

Date: 2026-08-24
Environment: Windows, Android SDK platforms 34/35, Build Tools 34.0.0/35.0.0/35.0.1, Android Studio JBR 21.0.4, Gradle wrapper 8.10.2.

## Quality command

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='[private workspace path omitted]
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --stacktrace
```

Initial result: `BUILD SUCCESSFUL in 2m 36s`, 51 actionable tasks. Four JVM safety-policy tests passed with zero failures/errors/skips. After correcting bootstrap resource warnings and intentionally suppressing dependency-update suggestions while M0 is pinned to API 35, the complete command passed again in 17 seconds with zero lint issues.

Artifacts:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- JVM report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`

## Evidence boundaries

- Unit tested: yes, pure safety-policy seed only.
- Integration tested: no BLE/gateway/database integration exists.
- Android-device tested: no.
- Real-LOOI tested: no.

No hardware capability is verified by this build.
