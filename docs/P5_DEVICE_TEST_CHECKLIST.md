# P5 device test checklist

No device or emulator was available during P5. Record hardware/build details and
attach logs for every run; mark each case PASS/FAIL/BLOCKED rather than assuming
support based on compile success.

## Capture before testing

- [ ] Manufacturer, model, Android release/API, build fingerprint, security patch.
- [ ] HyperOS/MIUI version where applicable.
- [ ] Build variant, APK SHA-256, application ID, `versionCode`, signing certificate
      SHA-256.
- [ ] Shizuku/Sui version, startup method (ADB shell/root), and reported UID.
- [ ] Primary/work/secondary Android user being tested.
- [ ] Existing app version and signer before upgrade; back up test data.

## Install / upgrade and stored data

- [ ] Install clean debug APK; verify launcher opens Kotlin Compose `MainActivity`.
- [ ] Verify `applicationId` remains `com.reed.fcmguard`; confirm the package installer
      recognizes the APK as an update to the intended predecessor.
- [ ] If update is rejected, capture both signing certificate fingerprints; do not
      uninstall as an implicit workaround because uninstall deletes app data.
- [ ] Before update, set protection, custom whitelist key/item, persistent-notification
      preference, theme, app language, and create history entries.
- [ ] Install the candidate as an update with the same signer; verify SharedPreferences
      values and the history database remain present.
- [ ] Update from an actual pre-history version and verify SQLite migration preserves
      rows. The synthetic v3 fixture is not a substitute for an upgrade artifact.
- [ ] Verify no database reset, reinstall, or clear-data step is needed.
- [ ] Record app signing identity separately for debug and release artifacts. Do not
      assume the ignored local debug keystore is the production release key.

## Core FCM / HyperOS regression

Run on a supported AOSP-like device and on each intended Xiaomi/HyperOS build:

- [ ] Deny Modify system settings; attempt protection/repair. Confirm no write,
      no false protected success, and actionable settings guidance.
- [ ] Grant Modify system settings; repair a whitelist containing unrelated entries.
      Confirm all previous entries remain and the required GMS item appears once.
- [ ] Trigger repair when already correct; confirm no settings write and no reconnect
      request.
- [ ] Exercise Settings access unavailable / provider write rejection and record
      exact user-facing result.
- [ ] Toggle protection off/on; verify `GuardService` start/stop, observer registration,
      and preference read-back.
- [ ] Force-stop/restart the app and confirm `onResume` restores only the configured
      protection lifecycle.
- [ ] With protection enabled, reboot. Verify `BootReceiver` restores service only
      under the stored enabled condition.
- [ ] Modify the watched key externally. Verify debounce, repair, package preservation,
      and reconnect only if the whitelist changed.
- [ ] Test wake signal on a device with GMS present and absent. The app can observe
      local dispatch exceptions, not whether GMS acted on broadcasts.
- [ ] Test scan results and all autostart states. Confirm unreadable OEM AppOps render
      `UNKNOWN`, never a guessed status.
- [ ] Test each HyperOS deep link and all fallbacks with Security Center missing,
      disabled, or activity unavailable.
- [ ] Verify notification channel ID remains `fcm_guard_persistent_v2`; test channel
      importance, notifications disabled, and API 33+ permission state.

## History / Undo / persistence

- [ ] Create changes for config key/item, protection, notification preference, own-app
      locale, one app locale, and a multi-app locale batch.
- [ ] Confirm no-op and failed operations are not shown as successful reversible
      changes.
- [ ] Force-stop between system write and history completion if safely possible;
      verify `UNDO_PENDING` is shown/recovered as indeterminate, never silently
      replayed.
- [ ] Change a setting outside the app after recording; Undo must report conflict and
      preserve the external value.
- [ ] Run two Undo attempts for the same entry rapidly; verify only one rollback and
      one inverse entry.
- [ ] Undo distinct targets concurrently; confirm they do not block each other.
- [ ] Make Shizuku unavailable before Undo; confirm explicit non-success state.
- [ ] Exercise batch Undo with one success, one conflict, one backend failure, and
      verify per-item counts and inverse history.
- [ ] Test low-storage/database write failure in a disposable test install; check
      compensation and persisted state. Never run this against user data.
- [ ] Close/reopen app and verify stable timestamp order and batch membership.

## Shizuku/Sui App Locale (Android 13+)

Run each supported Android API/build separately; never generalize across ROMs:

- [ ] Android 13 / API 33: record command availability and exact output for get,
      set `vi-VN`, and reset to System.
- [ ] Android 14 / API 34: repeat; verify permission and output behavior.
- [ ] Android 15 / API 35: repeat; distinguish AOSP from HyperOS.
- [ ] Stop/restart Shizuku; revoke permission; deny permission; test Binder death,
      UserService bind failure, null binding, and reconnect.
- [ ] Repeat in ADB-shell and root/Sui mode if both are claimed; record `Shizuku.getUid`
      and test the same package operations.
- [ ] Test installed, uninstalled, restricted, system, and secondary-user packages.
- [ ] Test malformed command output, nonzero exit, timeout, and backend disappearance.
- [ ] Verify locale state with an independent read after every modification; require
      mismatch to appear as failure/unknown, not success.
- [ ] Test an app with Vietnamese resources and one without. Explain that locale
      selection cannot add translations.
- [ ] Cancel a multi-app operation while a command is in flight; verify that the
      active operation completes/reports, and no subsequent app starts.

## Evidence to retain

- [ ] Device/API/ROM and Shizuku startup/UID table.
- [ ] Before/after preference, Settings.System, locale read-back, and history values.
- [ ] `logcat` around service, receiver, provider, binder, and locale command failures.
- [ ] Screenshots or screen recording of error/unknown/partial outcomes.
- [ ] APK hashes and signing fingerprints.
- [ ] Explicit PASS/FAIL/BLOCKED per checklist item. A compiled instrumentation APK
      is not a test result.
