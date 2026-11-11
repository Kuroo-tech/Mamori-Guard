# App Locale backend compatibility

**Status:** The UI and locale orchestration depend on `AppLocaleRepository` and
`PrivilegedLocaleBackend`. The current platform backend is Shizuku UserService plus
the `cmd locale` interface. No ROM/device compatibility is certified here.

## Implemented boundary

```text
feature.applocale
  AppLocaleScreen / LocaleBatchApplier
              |
              v
core.locale.AppLocaleRepository
              ^
              |
platform.applocale
  VerifiedAppLocaleRepository
  ShizukuLocaleBackend
  AppLocaleUserService + AIDL + command adapter
```

The screen has no Shizuku imports or concrete backend reference. It requests
permission and reads status through the repository. `VerifiedAppLocaleRepository`
serializes package operations and independently reads locale state after writes.
The batch applier previews eligible apps, excludes system apps by default, reports
progress/cancellation and per-app errors, supports retry, and records each verified
result. Locale tags and package names are validated and passed as discrete process
arguments (not concatenated into a shell command string). Output that does not
match the recognized response format is an error, never an inferred system default.

The Shizuku AIDL UserService lifecycle, permission/Binder listeners, binding errors,
and setup action are in `platform.applocale`. Binder death clears the connection;
permission-result listeners are removed on completion/disconnect. The AIDL
`destroy` method uses Shizuku's documented UserService transaction code.

## Public API and privilege constraints

Android's public `LocaleManager` is available from API 33. Its no-argument
`getApplicationLocales()` and `setApplicationLocales()` target the calling app.
The public API documents cross-package reads as restricted to installers, current
input methods, or apps holding `READ_APP_SPECIFIC_LOCALES`; the public setter is
for the calling app. Therefore the application does not use public
`LocaleManager.setApplicationLocales()` to modify another package.

The implementation uses a Shizuku UserService because Shizuku documents that a
UserService runs as UID 2000 (ADB shell) or UID 0 (root/Sui). Those identities have
different Android permissions and filesystem/SELinux capabilities. The service
currently invokes `cmd locale get-app-locales` and `set-app-locales`; successful
writes are not trusted until a distinct read call verifies them. This shell command
and its text output are not a stable Android SDK contract. No hidden
`ILocaleManager` Binder API is used: hidden Binder interfaces are version-sensitive
and are not established here as a safer cross-version contract.

## Compatibility matrix

| Platform | API | Source-level expectation | Verified on device |
|---|---:|---|---|
| Android 13 | 33 | Public per-app locale support exists; privileged command must be available and parse as expected. | No |
| Android 14 | 34 | Same command-based strategy; Shizuku API 13.1.5 release notes include an Android 14 provider fix. This does not certify locale commands or OEM behavior. | No |
| Android 15 | 35 | Build compiles against SDK 35; command and permission behavior remain runtime-dependent. | No |
| Newer Android | 36+ | Unknown until tested; command output/authorization may change. | No |
| HyperOS / MIUI | Device-specific | Shell privilege, user service, package behavior and locale application may vary by OEM. | No |

Shizuku upstream documents both root and ADB-shell identities and warns that shell
permissions vary by Android version. Shizuku/Sui startup/availability is therefore
an explicit prerequisite, not a dependency of normal FCM Guard behavior.

## Tests and remaining risks

The forced P5 JVM run executed 31 tests across 7 suites with no failures. Locale
coverage includes backend selection,
recognized/empty/unsupported command output, executor failure, argument validation,
backend unavailable/permission-required states, read-back mismatch, and batch
partial success. State and permission tests use contract fakes; they do not exercise
Shizuku's real Binder. The UserService Binder-death callback has no automated
integration test, and no physical-device test is claimed.

Sources:

- [Android `LocaleManager` reference](https://developer.android.com/reference/android/app/LocaleManager)
- [Shizuku API README](https://github.com/RikkaApps/Shizuku-API)
- [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/)
