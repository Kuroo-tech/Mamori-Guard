# FCM platform adapter contracts

**Status:** Implemented in package boundaries; still in the single `:app` Gradle module.

## Boundary

```text
DashboardScreen / FcmGuardController
              |
              v
 core.fcm.FcmGuardPlatform + Kotlin models
              ^
              |
 platform.fcm.JavaFcmPlatformAdapter
              |
              v
 Existing Java FCM / HyperOS implementations
```

`DashboardScreen.kt` accepts the `FcmGuardPlatform` contract and no longer imports
Java platform classes. `FcmGuardController` handles protection, notification,
configuration, whitelist repair/reconnect, and this app's locale-history actions.
The Activity is the composition root and supplies `JavaFcmPlatformAdapter`.
Java-to-Kotlin interop is concentrated in `platform/fcm/JavaFcmPlatformAdapter.kt`.

The contract covers:

- Reading guard/configuration/notification/WRITE_SETTINGS state.
- Saving configuration and toggling protection/notification preferences.
- Repairing the GMS whitelist and reconnecting FCM only when a repair changed it.
- Scanning FCM applications and exposing HyperOS autostart as `ENABLED`, `PARTIAL`,
  `DISABLED`, or `UNKNOWN`.
- Starting/stopping `GuardService`, opening system settings, and exposing appearance
  and application-language preferences.

## Compatibility behavior

The adapter delegates to the existing Java `SettingsGuard`, `GuardService`,
`FcmReconnect`, `FcmAppScanner`, `AutostartStatusReader`, `HyperOsSettings`,
`LocaleHelper`, and `ThemeHelper`. It keeps the existing preference keys and
`Settings.System.canWrite` semantics. It does not replace the legacy repair
algorithm or raise `targetSdk`; the `WRITE_SETTINGS` path remains in the Java-backed
platform adapter.

`FcmGuardPlatform` is Kotlin-only. A replacement implementation can be selected in
`MainActivity` without changing the dashboard's implementation-language dependency.
`SettingsGuardHistoryGateway` consumes the `SettingsGuardPort`; the Java adapter
implements that port as well as the dashboard contract.

## Tests

- JVM tests exercise controller behavior using `FakePlatform`: WRITE_SETTINGS
  denial does not mutate protection, repair no-ops do not reconnect, changed repairs
  reconnect, and `UNKNOWN` autostart is preserved.
- An instrumentation contract test compares adapter status reads with the existing
  Java `SettingsGuard` values and checks the autostart enum mapping. It is compiled
  with `assembleDebugAndroidTest`; it is not a device result unless run via
  `connectedDebugAndroidTest`.
- These tests do not independently certify Xiaomi/HyperOS behavior, service
  lifecycle, boot handling, FCM scanning, or actual GMS reconnection. Those remain
  device-validation items.
