package com.reed.fcmguard.core.fcm

import com.reed.fcmguard.core.history.HistoryChange
import com.reed.fcmguard.core.history.HistoryRepository
import com.reed.fcmguard.core.history.HistoryUndoService
import com.reed.fcmguard.core.operations.TargetOperationLock
import com.reed.fcmguard.core.operations.TargetOperationLocks

data class GuardStatus(
    val configuredKey: String,
    val requiredItem: String,
    val whitelistValue: String?,
    val protectionEnabled: Boolean,
    val persistentNotification: Boolean,
    val canWriteSettings: Boolean,
    val notificationAvailable: Boolean
) {
    val requiredItemPresent: Boolean
        get() = whitelistValue.orEmpty().split(',').any { it.trim() == requiredItem }
}

data class RepairOutcome(
    val success: Boolean,
    val changed: Boolean,
    val value: String?,
    val message: String
)

data class FcmApplication(
    val packageName: String,
    val label: String
)

object ThemeModes {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

enum class AutostartState {
    ENABLED,
    PARTIAL,
    DISABLED,
    UNKNOWN
}

interface FcmGuardPlatform {
    fun readStatus(): GuardStatus
    fun getConfiguredKey(): String
    fun getConfiguredRequiredItem(): String
    fun saveConfig(key: String, requiredItem: String): Boolean
    fun isProtectionEnabled(): Boolean
    fun setProtectionEnabled(enabled: Boolean): Boolean
    fun usePersistentNotification(): Boolean
    fun setPersistentNotification(enabled: Boolean): Boolean
    fun repairWhitelist(): RepairOutcome
    fun reconnectFcm(): Boolean
    fun scanFcmApplications(): List<FcmApplication>
    fun readAutostartState(packageName: String): AutostartState
    fun openAutostartManager(): Boolean
    fun startGuardService(): String?
    fun stopGuardService()
    fun openWriteSettings()
    fun openNotificationSettings(): Boolean
    fun openFcmDiagnostics()
    fun getAppLanguage(): String
    fun setAppLanguage(languageTag: String)
    fun setTrackedAppLanguage(languageTag: String)
    fun getThemeMode(): String
    fun setThemeMode(mode: String)
    fun saveConfiguration(key: String, requiredItem: String): ConfigurationSaveResult
}

data class DashboardActionResult(
    val success: Boolean,
    val message: String,
    val reconnectRequested: Boolean = false
)

class FcmGuardController(
    private val platform: FcmGuardPlatform,
    private val history: HistoryRepository,
    private val operationLocks: TargetOperationLock = TargetOperationLocks
) {
    fun setProtectionEnabled(enabled: Boolean): DashboardActionResult =
        operationLocks.withLock("guard:protection") {
            setProtectionEnabledLocked(enabled)
        }

    private fun setProtectionEnabledLocked(enabled: Boolean): DashboardActionResult {
        val before = platform.readStatus()
        if (enabled && !before.canWriteSettings) {
            platform.openWriteSettings()
            return DashboardActionResult(false, "Modify system settings permission is required")
        }
        if (before.protectionEnabled == enabled) {
            return DashboardActionResult(true, if (enabled) "Protection is already enabled" else "Protection is already disabled")
        }

        platform.setProtectionEnabled(enabled)
        val after = platform.readStatus()
        if (after.protectionEnabled != enabled) {
            return DashboardActionResult(false, "The protection preference could not be verified")
        }
        try {
            history.record(
                HistoryChange(
                    feature = "protection",
                    operation = HistoryUndoService.OP_PROTECTION,
                    target = "automatic_protection",
                    previousValue = before.protectionEnabled.toString(),
                    newValue = enabled.toString()
                )
            )
        } catch (error: Exception) {
            platform.setProtectionEnabled(before.protectionEnabled)
            return DashboardActionResult(
                false,
                "Protection preference reverted because history could not be saved: " +
                    (error.message ?: "storage error")
            )
        }

        if (!enabled) {
            platform.stopGuardService()
            return DashboardActionResult(true, "Protection stopped")
        }

        val notificationMessage = if (after.persistentNotification && !after.notificationAvailable) {
            if (platform.openNotificationSettings()) {
                "Allow notifications to keep foreground protection visible"
            } else {
                "Notification settings could not be opened"
            }
        } else null
        val serviceError = platform.startGuardService()
        val repair = platform.repairWhitelist()
        val reconnectAccepted = !repair.changed || platform.reconnectFcm()
        val reconnectError = if (repair.changed && !reconnectAccepted) {
            "FCM reconnect broadcasts could not be dispatched"
        } else null
        val messages = listOfNotNull(
            serviceError,
            notificationMessage,
            if (repair.success) null else repair.message,
            reconnectError
        )
        return DashboardActionResult(
            success = repair.success && reconnectAccepted,
            message = messages.joinToString(". ").ifEmpty { repair.message },
            reconnectRequested = repair.changed
        )
    }

    fun setPersistentNotification(enabled: Boolean): DashboardActionResult =
        operationLocks.withLock("guard:notification") {
            setPersistentNotificationLocked(enabled)
        }

    private fun setPersistentNotificationLocked(enabled: Boolean): DashboardActionResult {
        val before = platform.readStatus()
        if (before.persistentNotification == enabled) {
            return DashboardActionResult(true, "Notification preference is unchanged")
        }
        platform.setPersistentNotification(enabled)
        val after = platform.readStatus()
        if (after.persistentNotification != enabled) {
            return DashboardActionResult(false, "The notification preference could not be verified")
        }
        try {
            history.record(
                HistoryChange(
                    feature = "protection",
                    operation = HistoryUndoService.OP_NOTIFICATION,
                    target = "persistent_notification",
                    previousValue = before.persistentNotification.toString(),
                    newValue = enabled.toString()
                )
            )
        } catch (error: Exception) {
            platform.setPersistentNotification(before.persistentNotification)
            return DashboardActionResult(
                false,
                "Notification preference reverted because history could not be saved: " +
                    (error.message ?: "storage error")
            )
        }

        val notificationMessage = if (enabled && !after.notificationAvailable) {
            if (platform.openNotificationSettings()) {
                "Allow notifications to keep foreground protection visible"
            } else {
                "Notification settings could not be opened"
            }
        } else if (enabled) {
            "Persistent notification enabled"
        } else {
            "Persistent notification disabled"
        }
        val serviceError = if (after.protectionEnabled) platform.startGuardService() else null
        return DashboardActionResult(true, serviceError ?: notificationMessage)
    }

    fun saveConfiguration(key: String, requiredItem: String): DashboardActionResult =
        operationLocks.withLock("guard:configuration") {
            saveConfigurationLocked(key, requiredItem)
        }

    private fun saveConfigurationLocked(key: String, requiredItem: String): DashboardActionResult {
        val before = platform.readStatus()
        val previousItem = before.requiredItem
        val batchId = java.util.UUID.randomUUID().toString()
        val values = platform.saveConfiguration(key, requiredItem)
        if (!values.success) return DashboardActionResult(false, values.message)
        val changes = listOf(
            HistoryChange(
                feature = "advanced_configuration",
                operation = HistoryUndoService.OP_CONFIG_KEY,
                target = "settings_key",
                previousValue = before.configuredKey,
                newValue = values.configuredKey,
                batchId = batchId
            ),
            HistoryChange(
                feature = "advanced_configuration",
                operation = HistoryUndoService.OP_CONFIG_ITEM,
                target = "required_item",
                previousValue = previousItem,
                newValue = values.requiredItem,
                batchId = batchId
            )
        )
        try {
            history.recordAll(changes)
        } catch (error: Exception) {
            platform.saveConfiguration(before.configuredKey, previousItem)
            return DashboardActionResult(
                false,
                "Configuration was reverted because history could not be saved: " +
                    (error.message ?: "storage error")
            )
        }
        return DashboardActionResult(true, platform.startGuardService() ?: "Saved")
    }

    fun repairAndReconnect(): DashboardActionResult =
        operationLocks.withLock("guard:whitelist") {
            repairAndReconnectLocked()
        }

    private fun repairAndReconnectLocked(): DashboardActionResult {
        val result = platform.repairWhitelist()
        val reconnectAccepted = !result.changed || platform.reconnectFcm()
        val message = if (result.changed && !reconnectAccepted) {
            "${result.message}. FCM reconnect broadcasts could not be dispatched"
        } else {
            result.message
        }
        return DashboardActionResult(
            success = result.success && reconnectAccepted,
            message = message,
            reconnectRequested = result.changed
        )
    }

    fun setOwnAppLanguage(languageTag: String, targetPackage: String): DashboardActionResult =
        operationLocks.withLock("app-locale:$targetPackage") {
            val previous = platform.getAppLanguage()
            if (previous == languageTag) {
                return@withLock DashboardActionResult(true, "App language is unchanged")
            }
            platform.setAppLanguage(languageTag)
            val actual = platform.getAppLanguage()
            if (actual != languageTag) {
                return@withLock DashboardActionResult(
                    false,
                    "App language read-back did not match the requested value"
                )
            }
            try {
                history.record(
                    HistoryChange(
                        feature = "settings",
                        operation = HistoryUndoService.OP_UI_LOCALE,
                        target = targetPackage,
                        previousValue = previous,
                        newValue = actual
                    )
                )
                platform.setTrackedAppLanguage(actual)
                DashboardActionResult(true, "App language changed")
            } catch (error: Exception) {
                platform.setAppLanguage(previous)
                DashboardActionResult(
                    false,
                    "App language reverted because history could not be saved: " +
                        (error.message ?: "storage error")
                )
            }
        }
}

data class ConfigurationSaveResult(
    val success: Boolean,
    val configuredKey: String,
    val requiredItem: String,
    val message: String
)
