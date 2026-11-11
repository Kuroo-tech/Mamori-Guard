# P5 regression matrix

Results distinguish executed JVM behavior from source inspection and unexecuted
device-dependent behavior. No row implies a physical-device pass.

| Scenario | Expected behavior | Test evidence | Result |
|---|---|---|---|
| Modify-system-settings permission denied | Enabling protection is rejected and the preference is not changed; permission settings are offered. | Executed `FcmGuardControllerTest.missingWriteSettingsPermissionDoesNotChangeProtection` with a fake platform. Adapter uses `Settings.System.canWrite` on API 23+. | **JVM PASS; device flow unverified.** |
| Settings access unavailable | Repair returns failure; no successful repair/reconnect is claimed. | `SettingsGuard.repair` and adapter/controller paths statically inspected; fake permission-denial test covers protection toggle, not real Settings writes. | **Static inspection only; no Android write test.** |
| Repair is a no-op | Keep package list unchanged; do not dispatch a reconnect. | Executed controller test for unchanged repair. Existing Java implementation inspected; no instrumentation test invokes Xiaomi Settings. | **JVM PASS for controller; Java behavior statically inspected.** |
| Repair preserves existing entries | Existing comma-separated package values remain, with only missing required package added. | `SettingsGuard.repair` implementation statically inspected; no JVM test can run Android Settings provider. | **Static inspection only.** |
| Protection starts or stops service | Preference is persisted/verified; enable follows existing service-start and repair path; disable stops service. | Controller/platform wiring statically inspected. No Android instrumentation or device service lifecycle test. | **Static inspection only.** |
| Activity resume service restart | `MainActivity.onResume` calls the platform service-start path; adapter avoids start when protection is disabled. | Kotlin Activity and adapter statically inspected. | **Static inspection only; device lifecycle unverified.** |
| Boot restoration | `BootReceiver` starts the service only if the stored protection preference is enabled and selects foreground start under the existing notification preference. | Existing Java receiver and manifest statically inspected; no source changes in P5. | **Static inspection only; reboot test not run.** |
| Observer-triggered repair | Whitelist observer debounces a repair; reconnect is kicked only after a changed repair. | Existing `GuardService` statically inspected; no Android Settings observer test. | **Static inspection only; device test required.** |
| Reconnect helper failure | If dispatch helper rejects all sends, UI/controller reports failure instead of claiming wake dispatch. A true return only means broadcasts were dispatched locally, not handled by GMS. | New executed fake-platform controller test. `FcmReconnect` remains unchanged and swallows per-broadcast exceptions. | **JVM PASS for propagated result; receiver/GMS response unverified.** |
| Autostart status is unknown | Preserve `UNKNOWN`; do not collapse it to enabled/disabled. | Adapter enum mapping statically inspected; instrumentation comparison test compiled but not executed on HyperOS. | **Static inspection; OEM behavior unverified.** |
| HyperOS settings entry unavailable | Try existing action, explicit activity, then Android application settings fallback. | Existing Java `HyperOsSettings` statically inspected; no HyperOS runtime. | **Static inspection only.** |
| FCM/GCM app scan | Scan through the existing Java scanner; errors are represented by dashboard scan error state. | Dashboard/adapter statically inspected; no scanner device test. | **Static inspection only.** |
| Undo same target, concurrently | Only one operation may claim and mutate a single reversible history row; cooperating same-process operations on that target serialize. | Executed JVM test with latches; Android SQLite claim test compiled only. | **JVM PASS for fake repository/process lock; SQLite/device execution unverified.** |
| Undo different targets, concurrently | Keyed locks do not serialize unrelated locale targets. | Executed JVM latch test. | **JVM PASS.** |
| Undo interrupted by process death | Pending row from another process owner becomes `INDETERMINATE`; no automatic replay. | Android instrumentation test compiles; not executed. | **Source verified; instrumentation unexecuted.** |
| Undo transaction failure | Inverse insertion and original-row transition roll back together. External write may already have occurred; guarded compensation is best effort. | Android trigger-based transaction test compiles; not executed. JVM tests cover fake history commit failure and compensation. | **JVM compensation PASS; SQLite transaction test compile-only.** |
| External state conflict before Undo | Do not overwrite; record conflict when claim ownership allows it. | Executed conflict JVM test. | **JVM PASS.** |
| External change after final read, before write | The race cannot be eliminated without platform compare-and-set; do not claim atomic protection. | No deterministic test; source sequence inspected. | **Unverified residual race.** |
| Locale backend unavailable/revoked | Expose unavailable/permission-required/error state; write exceptions must not appear successful. | Executed fake repository/backend tests; real Shizuku Binder permission revocation not tested. | **JVM contract PASS; Shizuku runtime unverified.** |
| Locale command malformed output or mismatch | Unknown/unrecognized output errors; modification success requires separate read-back. | Executed parser/repository tests; command protocol is not an Android SDK contract. | **JVM PASS; OS command compatibility unverified.** |
| Locale timeout / cancellation | Timeout terminates the child process; batch cancellation stops before the next package, but cannot cancel a Binder command already in flight. | Output-drain/timeout runner statically inspected; no Android UserService test. Batch cancellation JVM test covers inter-item cancellation. | **Static inspection + JVM batch test; device behavior unverified.** |

`GuardService`, `BootReceiver`, `SettingsGuard`, `FcmReconnect`, and
`HyperOsSettings` were not rewritten in P5. `SettingsGuard` already had a
pre-existing API-23 guard modification in the baseline worktree; it remains
untouched.
