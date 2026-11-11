# P5 modularization readiness

## Decision

**NO-GO for broad Gradle multi-module extraction at P5 exit.** Package-level seams
are materially improved, but persistence instrumentation is unexecuted, the
debug signing key is local/ignored, the locale shell protocol has no device
compatibility evidence, and core service/boot/HyperOS behavior has only source
inspection. These gaps make a broad extraction risky and hard to validate.

A narrowly scoped P6 `:core:domain` pilot may be reconsidered after the validation
gates below. Do not create placeholder modules.

## Boundary assessment

| Candidate | Responsibility / public API | Allowed / forbidden dependencies | Required tests and migration risk | Extraction readiness |
|---|---|---|---|---|
| `:app` | Manifest, launcher/navigation, object construction and implementation selection. | May depend on selected feature/data/platform modules; libraries must not depend back on `:app`. | Merged manifest, provider, resources, notification channels, and config-change behavior must remain identical. | **Keep as-is.** Manual composition root is small enough; no DI framework needed. |
| `:core:domain` | Pure history entities/ports/Undo policy, FCM contracts/use cases, locale repository contracts and batch policy. | Kotlin/JDK only; forbid Android, Compose, SQLite, Shizuku, app Java implementation, feature UI. | JVM tests for lock behavior, contracts, undo outcomes, validation; retain persisted operation names. Risk: contracts currently coexist with Android SQLite source and share a global lock singleton. | **Best first pilot, but not yet extraction-ready.** First make operation states/results and adapters explicit; no Android type may cross the module. |
| `:core:data` | `HistoryStore` SQLite implementation and schema migrations behind `HistoryRepository`. | Android/SQLite + `:core:domain`; forbid feature UI, Shizuku, Java FCM implementation. | Execute creation/reopen, v1/v2/v3 migrations, transactional rollback, interrupted owner recovery on an emulator. Risk: current test covers synthetic v3 only and has not run. | **Not ready.** Keep in `:app` until connected instrumentation migration tests pass. |
| `:core:designsystem` | Theme, design tokens, reusable components. Public `GuardTheme` / token APIs. | Compose/Material dependencies; forbid feature business logic and platform adapters. | Compose previews/UI test, dynamic fallback, light/dark and accessibility/font scaling. Risk: current screens still use raw Material 3 values and tokens are incomplete. | **Partial.** Extract after token/component API audit, not before. |
| `:feature:dashboard` | Dashboard presentation over `FcmGuardPlatform` and controller. | Domain + design system + Compose; forbid Java implementation, HistoryStore, Shizuku. | Fake controller tests; UI state/permission/error checks; adapter parity. Risk: large composable and app-level resources/callbacks. | **Mostly bounded at package level; module extraction deferred.** |
| `:feature:history` | Timeline, details, confirmations over repository/use-case contracts. | Domain + design system + Compose; forbid SQLite implementation, platform concrete classes. | UI state tests and executing all Android persistence tests. Risk: contracts currently in `core.history`, result messages and data models are not yet a stable separate API. | **Mostly bounded; blocked on domain/data separation and executed migration tests.** |
| `:feature:app-locale` | App selection/search, preview/progress/retry, locale workflow. | Domain + design system + Android app-catalog port; forbid Shizuku API/UserService and concrete history data. | UI tests for denied/unavailable/unknown/partial/cancel states; command compatibility on each claimed API/ROM. Risk: installed-app listing remains in UI; shell command is an unstable runtime dependency. | **Mostly bounded from Shizuku, not ready for release/module extraction** until app catalog port and hardware evidence exist. |
| `:feature:settings` | Only if settings grows into an independent UI/workflow. | Domain + design system. | Define ownership of theme and own-app locale preferences. | **Do not create now:** no standalone settings feature in the current navigation. |
| `:platform:guard` | Existing `SettingsGuard`, `GuardService`, `BootReceiver`, scanner, reconnect and Kotlin adapter. | Android SDK + domain contracts; forbid Compose, Shizuku, features. | Device tests for Settings writes, observer repair, service restart, boot and reconnect. Risk: Java classes reference app `R`, manifest components and behavior must not move without preserving resources. | **Not ready.** First establish device regression and adapter tests. |
| `:platform:hyperos` | MIUI/HyperOS deep links and best-effort autostart reader. | Android framework + domain status contract; forbid UI, history and Shizuku. | Test supported ROMs; preserve UNKNOWN when vendor APIs unavailable. Risk: reflective AppOps and OEM activity names are private/unstable. | **Not ready for support claims.** Can be separated later when target ROM matrix exists. |
| `:platform:shizuku` | Shizuku provider/client, UserService, AIDL and command runner. | Android/AIDL + Shizuku + domain locale port; forbid Compose/history DB/FCM core. | Binder lifecycle, permission revoke, UserService failure, command protocol and read-back on Android 13/14/15. Risk: app-wide provider/dependency and command output semantics. | **Package isolation exists; Gradle extraction deferred** until actual device matrix validates minimum support. |

## Current cross-cutting risks

- `FcmGuardPlatform` combines guard operations with theme and app-language settings;
  future API split should not alter the underlying SharedPreferences keys.
- `HistoryStore`, history models, contracts, and Undo use case share
  `core.history`; module separation requires moving interfaces/models first.
- The target lock registry is process-local and keeps keyed monitor objects for the
  process lifetime. It is not a cross-process compare-and-set for Android state.
- Shizuku dependency/provider is still app-wide because only `:app` exists.
- `AppLocaleScreen` still enumerates packages through Android `PackageManager`.
- Compose screens depend directly on Material 3; token-based replacement is incomplete.
- Debug signing file `ci-signing/fcmguard-debug.keystore` is ignored and not tracked.
  Builds in this checkout work, but reproducibility and update compatibility cannot
  be guaranteed in another checkout without the same key.

## P6 entry gates

1. Run all four Android history instrumentation tests on an emulator/device,
   including schema migration and transactional failure; add an actual pre-history
   upgrade fixture if a supported predecessor had a history database.
2. Run an FCM regression pass on at least one AOSP-like Android device and the
   specifically supported Xiaomi/HyperOS build(s).
3. Validate Shizuku shell and Sui/root behavior on Android 13, 14, and 15 separately;
   record command outputs and read-back.
4. Establish debug/release signing and upgrade policy using actual signed artifacts.
5. Freeze module APIs and add dependency-direction checks before moving files.

When gates 1–4 pass, start with a single real `:core:domain` module and its existing
JVM tests. Reassess before extracting SQLite or platform modules.
