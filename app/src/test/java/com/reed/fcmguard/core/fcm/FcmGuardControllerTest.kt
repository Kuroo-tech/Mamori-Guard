package com.reed.fcmguard.core.fcm

import com.reed.fcmguard.core.history.HistoryChange
import com.reed.fcmguard.core.history.HistoryEntry
import com.reed.fcmguard.core.history.HistoryOperationState
import com.reed.fcmguard.core.history.HistoryRepository
import com.reed.fcmguard.core.history.UndoClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmGuardControllerTest {
    @Test
    fun repairReconnectsOnlyWhenWhitelistChanged() {
        val platform = FakePlatform()
        val controller = FcmGuardController(platform, FakeHistory())

        platform.repair = RepairOutcome(true, false, "gms", "Already repaired")
        val unchanged = controller.repairAndReconnect()
        assertTrue(unchanged.success)
        assertFalse(unchanged.reconnectRequested)
        assertEquals(0, platform.reconnectCount)

        platform.repair = RepairOutcome(true, true, "gms, other", "Repaired")
        val changed = controller.repairAndReconnect()
        assertTrue(changed.success)
        assertTrue(changed.reconnectRequested)
        assertEquals(1, platform.reconnectCount)
    }

    @Test
    fun missingWriteSettingsPermissionDoesNotChangeProtection() {
        val platform = FakePlatform().apply {
            status = status.copy(canWriteSettings = false)
        }
        val result = FcmGuardController(platform, FakeHistory()).setProtectionEnabled(true)

        assertFalse(result.success)
        assertEquals(0, platform.protectionWriteCount)
        assertTrue(platform.writeSettingsOpened)
    }

    @Test
    fun unknownAutostartStateIsPreservedByPlatformContract() {
        val platform = FakePlatform().apply { autostart = AutostartState.UNKNOWN }

        assertEquals(AutostartState.UNKNOWN, platform.readAutostartState("com.example.app"))
    }

    @Test
    fun reconnectDispatchFailureIsNotReportedAsSuccess() {
        val platform = FakePlatform().apply {
            repair = RepairOutcome(true, true, "gms, other", "Repaired")
            reconnectAccepted = false
        }

        val result = FcmGuardController(platform, FakeHistory()).repairAndReconnect()

        assertFalse(result.success)
        assertTrue(result.reconnectRequested)
        assertTrue(result.message.contains("could not be dispatched"))
    }

    private class FakePlatform : FcmGuardPlatform {
        var status = GuardStatus(
            configuredKey = "settings_key",
            requiredItem = "com.google.android.gms",
            whitelistValue = "com.google.android.gms",
            protectionEnabled = false,
            persistentNotification = false,
            canWriteSettings = true,
            notificationAvailable = true
        )
        var repair = RepairOutcome(true, false, status.whitelistValue, "No change")
        var reconnectCount = 0
        var protectionWriteCount = 0
        var writeSettingsOpened = false
        var autostart = AutostartState.UNKNOWN
        var reconnectAccepted = true

        override fun readStatus() = status
        override fun getConfiguredKey() = status.configuredKey
        override fun getConfiguredRequiredItem() = status.requiredItem
        override fun saveConfig(key: String, requiredItem: String) = true
        override fun isProtectionEnabled() = status.protectionEnabled
        override fun setProtectionEnabled(enabled: Boolean): Boolean {
            protectionWriteCount++
            status = status.copy(protectionEnabled = enabled)
            return true
        }
        override fun usePersistentNotification() = status.persistentNotification
        override fun setPersistentNotification(enabled: Boolean): Boolean {
            status = status.copy(persistentNotification = enabled)
            return true
        }
        override fun repairWhitelist() = repair
        override fun reconnectFcm(): Boolean {
            reconnectCount++
            return reconnectAccepted
        }
        override fun scanFcmApplications() = emptyList<FcmApplication>()
        override fun readAutostartState(packageName: String) = autostart
        override fun openAutostartManager() = true
        override fun startGuardService(): String? = null
        override fun stopGuardService() = Unit
        override fun openWriteSettings() {
            writeSettingsOpened = true
        }
        override fun openNotificationSettings() = true
        override fun openFcmDiagnostics() = Unit
        override fun getAppLanguage() = "system"
        override fun setAppLanguage(languageTag: String) = Unit
        override fun setTrackedAppLanguage(languageTag: String) = Unit
        override fun getThemeMode() = ThemeModes.SYSTEM
        override fun setThemeMode(mode: String) = Unit
        override fun saveConfiguration(key: String, requiredItem: String) =
            ConfigurationSaveResult(true, key, requiredItem, "Saved")
    }

    private class FakeHistory : HistoryRepository {
        private val entries = mutableListOf<HistoryEntry>()
        override fun recent(limit: Int) = entries.take(limit)
        override fun forBatch(batchId: String) = entries.filter { it.batchId == batchId }
        override fun record(change: HistoryChange): HistoryEntry? {
            if (change.result != "success" || change.previousValue == change.newValue) return null
            return HistoryEntry(
                id = "test-${entries.size}",
                timestamp = entries.size.toLong(),
                feature = change.feature,
                operation = change.operation,
                target = change.target,
                previousValue = change.previousValue,
                newValue = change.newValue,
                result = change.result,
                reversible = true,
                batchId = change.batchId
            ).also(entries::add)
        }
        override fun recordOutcome(change: HistoryChange) = record(change)
        override fun recordAll(changes: List<HistoryChange>) = changes.mapNotNull(::record)
        override fun recordUndo(change: HistoryChange, originalId: String) = null
        override fun beginUndo(entryId: String, expectedAfter: String) = UndoClaim.NOT_REVERSIBLE
        override fun completeUndo(change: HistoryChange, originalId: String) = null
        override fun finishUndoWithoutChange(entryId: String, result: String) = false
        override fun finishUndoOutcome(
            entryId: String,
            state: HistoryOperationState,
            result: String,
            reversible: Boolean
        ) = false
        override fun markUndoConflict(entryId: String, result: String) = false
        override fun recoverInterruptedUndos() = 0
    }
}
