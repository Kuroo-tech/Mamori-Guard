package com.reed.fcmguard.platform.fcm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.reed.fcmguard.AutostartStatusReader
import com.reed.fcmguard.FcmAppScanner
import com.reed.fcmguard.FcmReconnect
import com.reed.fcmguard.GuardService
import com.reed.fcmguard.HyperOsSettings
import com.reed.fcmguard.LocaleHelper
import com.reed.fcmguard.SettingsGuard
import com.reed.fcmguard.ThemeHelper
import com.reed.fcmguard.core.fcm.AutostartState
import com.reed.fcmguard.core.fcm.ConfigurationSaveResult
import com.reed.fcmguard.core.fcm.FcmApplication
import com.reed.fcmguard.core.fcm.FcmGuardPlatform
import com.reed.fcmguard.core.fcm.GuardStatus
import com.reed.fcmguard.core.fcm.RepairOutcome
import com.reed.fcmguard.core.operations.TargetOperationLock
import com.reed.fcmguard.core.operations.TargetOperationLocks
import com.reed.fcmguard.core.history.GuardHistoryGateway
import com.reed.fcmguard.core.history.SettingsGuardPort

class JavaFcmPlatformAdapter(
    private val context: Context,
    private val lock: TargetOperationLock = TargetOperationLocks
) : FcmGuardPlatform, SettingsGuardPort {
    private val appContext = context.applicationContext

    override fun readStatus(): GuardStatus = GuardStatus(
        configuredKey = getConfiguredKey(),
        requiredItem = getConfiguredRequiredItem(),
        whitelistValue = SettingsGuard.read(appContext),
        protectionEnabled = isProtectionEnabled(),
        persistentNotification = usePersistentNotification(),
        canWriteSettings = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.System.canWrite(appContext),
        notificationAvailable = GuardService.canShowPersistentNotification(appContext)
    )

    override fun getConfiguredKey(): String = SettingsGuard.getConfiguredKey(appContext)

    override fun getConfiguredRequiredItem(): String =
        SettingsGuard.getConfiguredRequiredItem(appContext)

    override fun isProtectionEnabled(): Boolean = SettingsGuard.isProtectionEnabled(appContext)

    override fun setProtectionEnabled(enabled: Boolean): Boolean {
        return lock.withLock(PROTECTION_TARGET) {
            SettingsGuard.setProtectionEnabled(appContext, enabled)
            isProtectionEnabled() == enabled
        }
    }

    override fun usePersistentNotification(): Boolean =
        SettingsGuard.usePersistentNotification(appContext)

    override fun setPersistentNotification(enabled: Boolean): Boolean {
        return lock.withLock(NOTIFICATION_TARGET) {
            SettingsGuard.setPersistentNotification(appContext, enabled)
            usePersistentNotification() == enabled
        }
    }

    override fun saveConfig(key: String, requiredItem: String): Boolean =
        lock.withLock(CONFIGURATION_TARGET) {
            SettingsGuard.saveConfig(appContext, key, requiredItem)
            SettingsGuard.getConfiguredKey(appContext) == normalize(key, true) &&
                SettingsGuard.getConfiguredRequiredItem(appContext) == normalize(requiredItem, false)
        }

    override fun saveConfiguration(key: String, requiredItem: String): ConfigurationSaveResult =
        lock.withLock(CONFIGURATION_TARGET) {
            val safeKey = normalize(key, true)
            val safeItem = normalize(requiredItem, false)
            SettingsGuard.saveConfig(appContext, key, requiredItem)
            val actualKey = getConfiguredKey()
            val actualItem = getConfiguredRequiredItem()
            val verified = actualKey == safeKey && actualItem == safeItem
            ConfigurationSaveResult(
                success = verified,
                configuredKey = actualKey,
                requiredItem = actualItem,
                message = if (verified) "Saved" else "Configuration read-back did not match"
            )
        }

    override fun repairWhitelist(): RepairOutcome {
        val result = lock.withLock(WHITELIST_TARGET) { SettingsGuard.repair(appContext) }
        return RepairOutcome(result.success, result.changed, result.value, result.message)
    }

    override fun reconnectFcm(): Boolean = FcmReconnect.kick(appContext)

    override fun scanFcmApplications(): List<FcmApplication> =
        FcmAppScanner.scan(appContext).map {
            FcmApplication(it.packageName, it.label)
        }

    override fun readAutostartState(packageName: String): AutostartState =
        when (AutostartStatusReader.check(appContext, packageName)) {
            AutostartStatusReader.Status.ENABLED -> AutostartState.ENABLED
            AutostartStatusReader.Status.PARTIAL -> AutostartState.PARTIAL
            AutostartStatusReader.Status.DISABLED -> AutostartState.DISABLED
            AutostartStatusReader.Status.UNKNOWN -> AutostartState.UNKNOWN
        }

    override fun openAutostartManager(): Boolean =
        HyperOsSettings.openAutoStartManager(appContext)

    override fun startGuardService(): String? {
        if (!isProtectionEnabled()) return null
        val intent = Intent(appContext, GuardService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && usePersistentNotification()) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            null
        } catch (error: Exception) {
            "Preference saved, but protection service could not start: " +
                (error.message ?: error.javaClass.simpleName)
        }
    }

    override fun stopGuardService() {
        appContext.stopService(Intent(appContext, GuardService::class.java))
    }

    override fun openWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            appContext.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${appContext.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun openNotificationSettings(): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${appContext.packageName}")
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override fun openFcmDiagnostics() {
        val candidates = listOf(
            "com.google.android.gms.gcm.GcmDiagnostics",
            "com.google.android.gms.gtalkservice.diagnostics.GTalkServiceDiagnostics",
            "com.google.android.gms.googlehelp.GmsDiagnostics"
        )
        candidates.forEach { className ->
            val intent = Intent().setClassName("com.google.android.gms", className)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(appContext.packageManager) != null) {
                    appContext.startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                Unit
            }
        }
        appContext.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun getAppLanguage(): String = LocaleHelper.getLanguage(appContext)

    override fun setAppLanguage(languageTag: String) {
        lock.withLock(APP_LANGUAGE_TARGET) {
            LocaleHelper.setLanguage(appContext, languageTag)
        }
    }

    fun migrateAppLanguagePreference() = LocaleHelper.migrateLegacyPreference(appContext)

    override fun setTrackedAppLanguage(languageTag: String) {
        appContext.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TRACKED_APP_LOCALE, languageTag).apply()
    }

    fun getTrackedAppLanguage(): String? =
        appContext.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TRACKED_APP_LOCALE, null)

    override fun getThemeMode(): String = ThemeHelper.getMode(appContext)

    override fun setThemeMode(mode: String) {
        ThemeHelper.setMode(appContext, mode)
    }

    private fun normalize(value: String, key: Boolean): String =
        value.trim().ifEmpty {
            appContext.getString(
                if (key) com.reed.fcmguard.R.string.default_key
                else com.reed.fcmguard.R.string.default_required_item
            )
        }

    companion object {
        private const val CONFIGURATION_TARGET = "guard:configuration"
        private const val PROTECTION_TARGET = "guard:protection"
        private const val NOTIFICATION_TARGET = "guard:notification"
        private const val WHITELIST_TARGET = "guard:whitelist"
        private const val APP_LANGUAGE_TARGET = "app-locale:com.reed.fcmguard"
        private const val KEY_TRACKED_APP_LOCALE = "history_tracked_app_locale"
    }
}
