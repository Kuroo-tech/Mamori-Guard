# Device validation plan

**Purpose:** Turn source/build evidence into explicit Android, Shizuku/Sui, HyperOS, lifecycle, and accessibility evidence. No physical-device validation has been performed in this environment.

> **Historical P4.5 snapshot:** The test counts below predate P5. For current
> executed/compiled results, use [`P5_VALIDATION_REPORT.md`](./P5_VALIDATION_REPORT.md);
> for runnable device steps, use [`P5_DEVICE_TEST_CHECKLIST.md`](./P5_DEVICE_TEST_CHECKLIST.md).

## Evidence status at P4.5 audit time

| Item | Status |
|---|---|
| JVM unit tests | **Executed and passed:** 29 tests, 0 failures/errors/skips. |
| Debug APK | **Built successfully** with `:app:assembleDebug`. |
| Instrumentation APK | **Compiled successfully** with `:app:assembleDebugAndroidTest`; 4 tests are included. |
| Instrumentation tests | **Not executed.** |
| Lint | **Passed:** 0 errors, 56 warnings. |
| Diff whitespace check | **Passed:** `git diff --check`. |
| Android emulator/device connection | **Unavailable:** no `adb` executable or connected device in the current environment. |
| Shizuku Binder/UserService | **Not tested on hardware.** JVM tests cover interface-level unavailable/permission states; actual Binder lifecycle is unverified. |
| Sui/root-backed service | **Not tested on hardware.** |
| Xiaomi/HyperOS | **Not tested on hardware.** |
| Locale shell command/output | **Not tested on an Android runtime.** |
| FCM reconnect/foreground service/boot behavior | **No device verification.** |

## Test matrix

Record device manufacturer/model, Android release/API, build fingerprint, target user/profile, Shizuku/Sui version/start mode, app build hash, exact steps, expected/actual results, logs, and pass/fail for every run.

### 1. Baseline install and permission path

| Device/API | Steps | Expected evidence |
|---|---|---|
| API 21–22, if supported hardware/emulator is available | Install and launch; inspect launcher manifest, legacy WRITE_SETTINGS compatibility path, basic Compose screen, boot/service restrictions. | No Shizuku provider dependency on the unsupported path; no SDK-23 `canWrite` call crash; do not infer modern OS behavior from emulator results alone. |
| API 23–32 | Deny/grant Modify system settings, toggle protection, return from system settings, repeat after process recreation. | Permission state updates; no repair write when permission is denied; no crash or stale enabled switch. |
| API 33+ non-Xiaomi baseline | Install, grant app notifications where system presents the prompt, toggle persistent notification and protection. | Correct quiet/foreground mode messaging; service startup/stop and notification state reported accurately. |

### 2. FCM Guard and original Java core regression

Run on the actual targeted Xiaomi/HyperOS version, plus a non-Xiaomi Android device where possible:

1. Set a whitelist with multiple unrelated package names and missing GMS. Trigger repair. Verify original entries remain, GMS is appended once, and no unrelated key is written.
2. Repeat repair with GMS already present. Verify no Settings write and no reconnect kick.
3. Deny WRITE_SETTINGS and trigger repair. Verify no preference claims protection is active and no write/reconnect occurs.
4. Toggle protection off/on; check `GuardService` starts/stops, observer registration, and protection preference across process death.
5. Toggle persistent notification with notifications allowed/blocked. Verify visible foreground notification, quiet channel, app settings fallback, and service lifecycle.
6. Reboot with protection enabled. Verify `BootReceiver` starts expected behavior, no duplicate watchers/notifications, and no boot-time write when already protected.
7. Change the configured key/item while protection is enabled; verify observer/service refresh and preserve packages under the new key.
8. Scan FCM/GCM apps; compare detected packages against known manifest signals. Verify partial/unknown HyperOS autostart state is not falsely shown as enabled/disabled.
9. Exercise FCM diagnostics/reconnect only as user-triggered flows. Verify reconnect is not emitted after a no-op repair.

Capture system logs and before/after `Settings.System` values where the test device permits. Do not use assertions that require private Xiaomi behavior on AOSP.

### 3. History, migration, and Undo

1. Execute each reversible operation: config key, required item, protection, persistent-notification setting, own-app locale, external-app locale, and batch locale.
2. Verify each persisted record contains feature/operation/target/before/after/result/time/reversible/batch as applicable. Verify successful no-op operations do not create success records.
3. Kill the app process, relaunch, and verify history survives. Reboot separately if safe.
4. Install/upgrade from an artifact with a real schema-v1 DB fixture; verify v1→v2 migration preserves rows and adds usable batch index/data. Do not substitute a fresh-install test for migration evidence.
5. Change the target setting externally after a record is made; Undo must report a conflict and leave the external value untouched.
6. Make a backend unavailable before Undo (Shizuku stopped, permission revoked, service killed). Verify an explicit failure, no false inverse record, and no silent overwrite.
7. Force persistence errors after platform rollback (test build/fake repository or controlled DB failure). Verify compensation or report state as uncertain.
8. Double-tap Undo / invoke two Undo attempts concurrently and race an external change against Undo. Confirm only one operation consumes a reversible record and no newer value is overwritten. Source has process-local target locks and a durable conditional claim for one history entry; it does not provide a cross-process lock for every external target.
9. Undo a batch with a mix of successful items, stale conflicts, and backend failures. Confirm successful items remain undone, others are skipped/reported, and result counts match.
10. Check ordering for multiple records written within one millisecond and batch details.

### 4. Shizuku/Sui and app-specific locales

Test separately on Android 13+ devices:

| Mode | Setup | Assertions |
|---|---|---|
| Shizuku started via wireless debugging | Grant app-specific Shizuku permission; stop/restart server; reboot and restart Shizuku. | Correct unavailable/permission/connecting/ready state; reconnection after Binder death; user informed that Shizuku may need restart after reboot. |
| Sui/root-backed Shizuku API | Install Sui only on an authorized rooted test device; verify it is detected through provider integration. | Locale operation success only after read-back; no assumption that root mode behaves like shell mode. |
| Permission denied/revoked | Deny request, revoke permission in Shizuku, then retry. | No locale writes; clear permission state; no stuck loading/binding state. |
| UserService death | Kill/unbind service, induce command failure, reconnect. | Backend recovers or exposes a classified error; no false success. |
| App/user coverage | Test normal user, work profile if supported, secondary user if available. | Correct target user ID; no cross-user mutation outside selection. |

For each device/OS build, record actual command support and output for:

```text
cmd locale get-app-locales <package> --user <userId>
cmd locale set-app-locales <package> --user <userId> --locales <localeTags>
cmd locale set-app-locales <package> --user <userId> --locales ""
```

Validate command option order, exit codes, empty-locale/reset semantics, locale list formatting, output on stderr, timeout behavior, permission failures, and package-not-found behavior. The current parser expects a specific English output form; any mismatch must fail clearly and must not be reported as a successful locale read.

Then test:

- Search by label and package; selection state through filtering and rotation.
- Apply `vi-VN` to one app and several apps with Vietnamese resources; verify actual locale and rendered resources. Include an app without Vietnamese resources and confirm the UI does not claim translations were added.
- Reset to system default and verify returned empty locale list normalizes as expected.
- Exclude system apps by default; confirm no system app is accidentally included in batch preview.
- Batch partial failure: one invalid/unavailable package and one valid package. Check success, failure, history, retry, and cancellation between apps.
- Cancel during a long operation; document that the current Binder/shell operation completes before cancellation takes effect.

Do not claim support for an OEM/API level until the matrix passes on that ROM/build.

### 5. UI, accessibility, and lifecycle

- Rotate/change window size while on each tab; navigate away and back; background/foreground during scan, batch locale, and history Undo.
- Test 320dp/narrow phone, tablet/foldable, landscape, display cutout/gesture navigation, RTL, 200% font scale, TalkBack, keyboard navigation, and contrast in light/dark/dynamic/fallback themes.
- Confirm each checkbox/switch/button has a meaningful spoken label and state; verify bottom navigation icons are not announced as opaque letters.
- Verify loading, empty, error, permission denied, partial success, cancellation, and retry states remain visible and do not claim success prematurely.
- Look for main-thread stalls, stale Compose state, progress updates from IO, uncaught Binder exceptions, and process recreation loss.

## Required automated follow-up

Before device runs, add:

- History database v1 migration instrumentation test using a fixture.
- Undo tests for failed write, failed verification, compensation failure, backend unavailable, and concurrent/double Undo.
- Shizuku adapter tests around permission/lifecycle using an adapter seam; fake locale backend alone is not sufficient.
- FCM regression tests around existing package preservation, no-op repair, reconnect conditions, and write permission denial.
- Compose/state tests for permission/error/partial/cancel states and accessible semantics.

Run instrumentation with a connected emulator/device using `:app:connectedDebugAndroidTest` after installing the appropriate SDK/device image. Preserve logs and test artifacts as evidence; an assembled test APK is not an executed instrumentation result.
