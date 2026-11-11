# P5 validation report

## Baseline captured before P5 changes

**Captured:** 2026-11-11  
**Repository:** `/home/doann/Projects/Mamori-Guard`

The worktree was already substantially modified at the start of P5. No P5 source,
build, or documentation edits had been made when this baseline was recorded.
`git diff --cached --stat` was empty; `git diff --check` passed.

Tracked modified files at baseline:

- `README.md`, `README.zh-CN.md`
- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/reed/fcmguard/MainActivity.java`
- `app/src/main/java/com/reed/fcmguard/SettingsGuard.java`
- `app/src/main/res/values-night/styles.xml`
- `app/src/main/res/values/styles.xml`
- `build.gradle`
- `docs/icon.svg`
- `gradle.properties`

Untracked existing work at baseline:

- `app/src/androidTest/`, `app/src/main/aidl/`
- `app/src/main/java/com/reed/fcmguard/MainActivity.kt`
- `app/src/main/java/com/reed/fcmguard/{core,feature,platform,ui}/`
- `app/src/test/`
- `app/src/main/res/values-night-v23/`, `values-night-v27/`, `values-v23/`,
  `values-v24/`, `values-v27/`, and `values/bools.xml`
- `docs/ARCHITECTURE_AUDIT.md`, `DEPENDENCY_GRAPH.md`,
  `DEVICE_VALIDATION_PLAN.md`, `FCM_ADAPTER_CONTRACTS.md`,
  `HISTORY_RELIABILITY.md`, `LOCALE_BACKEND_COMPATIBILITY.md`,
  `MODULARIZATION_ROADMAP.md`
- `docs/icon.svg:Zone.Identifier`
- `gradle/`

These are pre-P5 state and must be preserved. In particular, `docs/icon.svg` and
its existing metadata sidecar are intentionally out of scope.

## Baseline source/configuration findings

- `applicationId` is `com.reed.fcmguard`; `targetSdk` is 22; `minSdk` is 21;
  compile SDK is 35; version is `30` / `1.5.9`.
- `settings.gradle` includes only `:app`; package-level contracts are not separate
  Gradle modules.
- The current debug signing config references
  `ci-signing/fcmguard-debug.keystore`. Whether this key matches the key used to
  sign an installed predecessor cannot be established from source alone.
- The manifest retains the WRITE_SETTINGS, boot, foreground-service, and
  notification declarations; Shizuku provider is present. `GuardService` uses
  channel ID `fcm_guard_persistent_v2`, and source retains the Java receiver and
  service declarations.
- `SettingsGuard` currently stores preferences in `guard_state`, including
  `enabled`, `persistent_notification`, `settings_key`, `required_item`, and
  `last_good_` values. Existing locale/theme helpers use `ui_language`,
  `native_locale_migrated`, and `appearance_mode`; the adapter adds
  `history_tracked_app_locale`. History uses a separate SQLite database,
  `fcm_guard_history.db`, schema version 4.
- Existing project changes modify `SettingsGuard` to gate `canWrite` behind API
  23, and retain a corresponding API guard in the old Java Activity. These
  compatibility adjustments predate P5 and were not altered during baseline
  capture.
- P4.5 documentation contains stale P4 sections and some claims that need source
  reconciliation. For example, later in `ARCHITECTURE_AUDIT.md` and
  `DEPENDENCY_GRAPH.md`, diagrams still show direct Java/Shizuku feature coupling
  despite the P4.5 update sections.

## Validation state at baseline

The latest recorded P4.5 run reports 29 JVM tests passing, successful debug and
instrumentation APK assembly, and lint with 0 errors / 56 warnings. These are
historical results until rerun as part of P5. No Android device/emulator or
`adb` executable is available in this environment; therefore instrumentation,
Shizuku/Sui, HyperOS, Settings writes, notification lifecycle, boot, and FCM
reconnect remain unverified on hardware.

## P5 outcome

P5 completed as a source audit and narrow reliability hardening pass; it did not
perform a broad rewrite or module extraction.

### Source changes

- The Kotlin reconnect port now returns the existing Java helper's dispatch
  acceptance. The adapter forwards it, and dashboard messaging distinguishes
  failed local dispatch from accepted dispatch. This is not evidence that GMS
  received or acted on the broadcasts.
- The Shizuku UserService command runner drains merged process output while the
  command runs, limits captured output to 64 KiB, enforces a timeout, cleans up
  the process, and reports interruption as failure rather than hiding it.
- Added JVM coverage for reconnect dispatch failure and independent concurrent
  Undo operations on different targets.
- Added an Android instrumentation test for SQLite transaction rollback when
  insertion of the inverse history row fails.
- Added P5 regression, device-test, and modularization-readiness documents.
  Corrected P4-era documentation claims by labeling historical graphs/findings
  as superseded and adding current source evidence.
- The existing Java `SettingsGuard`, `GuardService`, `BootReceiver`,
  `FcmReconnect`, scanner, and HyperOS helper implementations were not changed
  by P5. Existing unrelated worktree modifications were preserved.

### Validation results

| Validation | Result |
|---|---|
| `:app:testDebugUnitTest --rerun-tasks --no-daemon` | **Executed and passed:** 31 tests, 7 suites, 0 failures, 0 errors, 0 skipped. |
| `:app:assembleDebug` | **Passed.** Output: `app/build/outputs/apk/debug/app-debug.apk`. |
| `:app:assembleDebugAndroidTest` | **Passed (compiled/packaged only).** Output: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. Five instrumentation test methods are present; none was executed on a device. |
| `:app:lintDebug` | **Passed:** 0 errors, 56 warnings. Existing warning classes include dependency-update suggestions, unused resources, legacy/API-level checks and reflective non-SDK AppOps access. Also observed the manifest `tools:replace` warning and Java deprecated-API compiler note. No broad suppressions or target SDK changes were made. |
| `git diff --check` | **Passed.** |

The combined Gradle invocation for the unit test, debug APK, instrumentation APK,
and lint completed successfully. The unit test task was then forced with
`--rerun-tasks` and executed all 31 tests. Build and lint tasks in the combined
run completed successfully; many unchanged compilation tasks were up-to-date.

### Installation and device compatibility

Source still identifies the app as `com.reed.fcmguard`, `versionCode 30`,
`versionName 1.5.9`, `minSdk 21`, `targetSdk 22`, `compileSdk 35`. The APK is
signed with the local debug signing configuration. There is no predecessor APK
or known predecessor signing fingerprint in evidence, so a real upgrade/install
compatibility claim cannot be made. Same application ID, preference keys, and
SQLite migrations are source evidence only.

No connected device/emulator or `adb` execution was available. Instrumentation,
WRITE_SETTINGS provider behavior, service lifecycle, reboot restoration,
notifications, HyperOS/MIUI, Shizuku/Sui permission and Binder lifecycle,
`cmd locale` compatibility, app-specific locale read-back on Android 13/14/15,
and upgrade from a signed release remain **unverified on hardware**. In
particular, local dispatch acceptance is not end-to-end FCM delivery, and the
locale command/output format is not a stable Android SDK contract.

### P6 decision

**NO-GO for broad Gradle module extraction.** Package-level seams are in place,
but actual SQLite instrumentation/migration behavior, device regressions, and
signing/upgrade continuity have not been established. A narrowly scoped
`:core:domain` pilot may be reconsidered after the P6 gates in
[`P5_MODULARIZATION_READINESS.md`](./P5_MODULARIZATION_READINESS.md) pass. This
project is not architecturally or device-validation complete.
