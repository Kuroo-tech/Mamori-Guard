package com.reed.fcmguard.core.history

import com.reed.fcmguard.core.operations.TargetOperationLocks
import com.reed.fcmguard.core.operations.TargetOperationLock
import java.util.UUID

interface LocaleHistoryGateway {
    fun getLocales(packageName: String): String
    fun setLocales(packageName: String, languageTags: String): String
    fun getOwnAppLocale(): String
    fun setOwnAppLocale(languageTags: String): String
}

sealed interface UndoResult {
    data class Applied(val entry: HistoryEntry) : UndoResult
    data class Conflict(val expected: String, val actual: String) : UndoResult
    data class Failed(val message: String) : UndoResult
    data class InProgress(val message: String) : UndoResult
    data class Indeterminate(val message: String) : UndoResult
    data class CompensationFailed(val message: String) : UndoResult
}

data class UndoBatchResult(
    val total: Int,
    val applied: Int,
    val conflicts: Int,
    val failed: Int,
    val indeterminate: Int,
    val compensationFailed: Int,
    val inProgress: Int
)

class HistoryUndoService(
    private val store: HistoryRepository,
    private val guard: GuardHistoryGateway,
    private val locales: LocaleHistoryGateway,
    private val operationLocks: TargetOperationLock = TargetOperationLocks
) {
    fun undo(entry: HistoryEntry, undoBatchId: String? = null): UndoResult =
        operationLocks.withLock(lockKey(entry)) { undoLocked(entry, undoBatchId) }

    private fun undoLocked(entry: HistoryEntry, undoBatchId: String?): UndoResult {
        val initial = try {
            readCurrent(entry)
        } catch (error: Exception) {
            return UndoResult.Failed(error.message ?: "Could not read the current value")
        }

        if (initial != entry.newValue) {
            store.markUndoConflict(entry.id, "conflict: recorded after-value no longer matches")
            return UndoResult.Conflict(entry.newValue, initial)
        }

        when (store.beginUndo(entry.id, entry.newValue)) {
            UndoClaim.IN_PROGRESS -> return UndoResult.InProgress("Undo is already in progress")
            UndoClaim.NOT_REVERSIBLE -> return UndoResult.Failed("History entry is no longer reversible")
            UndoClaim.CLAIMED -> Unit
        }

        val beforeWrite = try {
            readCurrent(entry)
        } catch (error: Exception) {
            store.finishUndoOutcome(
                entry.id,
                HistoryOperationState.INDETERMINATE,
                "indeterminate: could not re-read before rollback: ${error.message}"
            )
            return UndoResult.Indeterminate(
                error.message ?: "Could not re-read the target after claiming Undo"
            )
        }
        if (beforeWrite != entry.newValue) {
            store.markUndoConflict(
                entry.id,
                "conflict: target changed before rollback write"
            )
            return UndoResult.Conflict(entry.newValue, beforeWrite)
        }

        var writeError: String? = null
        try {
            writeValue(entry, entry.previousValue)
        } catch (error: Exception) {
            writeError = error.message ?: error.javaClass.simpleName
        }

        val afterWrite = try {
            readCurrent(entry)
        } catch (error: Exception) {
            store.finishUndoOutcome(
                entry.id,
                HistoryOperationState.INDETERMINATE,
                "indeterminate: read-back failed after rollback: ${error.message}"
            )
            return UndoResult.Indeterminate(
                "Rollback was attempted but its result could not be read: " +
                    (error.message ?: "backend unavailable")
            )
        }

        if (afterWrite == entry.previousValue) {
            return completeOrCompensate(entry, afterWrite, undoBatchId)
        }

        if (afterWrite == entry.newValue) {
            store.finishUndoWithoutChange(
                entry.id,
                "failed: rollback did not change the target" +
                    (writeError?.let { " ($it)" } ?: "")
            )
            return UndoResult.Failed(
                writeError ?: "Rollback did not change the target"
            )
        }

        store.finishUndoOutcome(
            entry.id,
            HistoryOperationState.INDETERMINATE,
            "indeterminate: rollback outcome differs from both recorded values"
        )
        return UndoResult.Indeterminate(
            "The target changed to an unexpected value; no compensation was attempted"
        )
    }

    private fun completeOrCompensate(
        entry: HistoryEntry,
        verifiedAfter: String,
        undoBatchId: String?
    ): UndoResult {
        try {
            val inverse = store.completeUndo(
                HistoryChange(
                    feature = entry.feature,
                    operation = entry.operation,
                    target = entry.target,
                    previousValue = entry.newValue,
                    newValue = verifiedAfter,
                    batchId = undoBatchId
                ),
                entry.id
            )
            return if (inverse == null) {
                compensateAfterHistoryFailure(entry, "Undo produced no history entry")
            } else {
                UndoResult.Applied(inverse)
            }
        } catch (error: Exception) {
            return compensateAfterHistoryFailure(
                entry,
                "Undo state could not be committed: ${error.message ?: "history storage error"}"
            )
        }
    }

    private fun compensateAfterHistoryFailure(entry: HistoryEntry, reason: String): UndoResult {
        val current = try {
            readCurrent(entry)
        } catch (error: Exception) {
            store.finishUndoOutcome(
                entry.id,
                HistoryOperationState.COMPENSATION_FAILED,
                "compensation_failed: $reason; read-back unavailable: ${error.message}"
            )
            return UndoResult.CompensationFailed(
                "$reason; the target could not be read, so no restoration was attempted"
            )
        }
        if (current != entry.previousValue) {
            val detail = "$reason; target changed to $current, so the prior value was not restored"
            store.finishUndoOutcome(
                entry.id,
                HistoryOperationState.INDETERMINATE,
                "indeterminate: $detail"
            )
            return UndoResult.Indeterminate(detail)
        }

        val preCompensation = try {
            readCurrent(entry)
        } catch (error: Exception) {
            store.finishUndoOutcome(
                entry.id,
                HistoryOperationState.COMPENSATION_FAILED,
                "compensation_failed: pre-write read failed: ${error.message}"
            )
            return UndoResult.CompensationFailed(
                "$reason; target could not be re-checked before restoring"
            )
        }
        if (preCompensation != entry.previousValue) {
            val detail = "$reason; target changed before compensation"
            store.finishUndoOutcome(
                entry.id,
                HistoryOperationState.INDETERMINATE,
                "indeterminate: $detail"
            )
            return UndoResult.Indeterminate(detail)
        }

        return try {
            val accepted = writeValue(entry, entry.newValue)
            val verified = readCurrent(entry)
            if (accepted && verified == entry.newValue) {
                if (store.finishUndoWithoutChange(
                        entry.id,
                        "failed: $reason; original value restored and verified"
                    )
                ) {
                    UndoResult.Failed("$reason; original value was restored and verified")
                } else {
                    UndoResult.CompensationFailed(
                        "$reason; original value was restored but history state could not be updated"
                    )
                }
            } else {
                store.finishUndoOutcome(
                    entry.id,
                    HistoryOperationState.COMPENSATION_FAILED,
                    "compensation_failed: $reason; restoration was not verified"
                )
                UndoResult.CompensationFailed(
                    "$reason; restoration was rejected or could not be verified"
                )
            }
        } catch (error: Exception) {
            store.finishUndoOutcome(
                entry.id,
                HistoryOperationState.COMPENSATION_FAILED,
                "compensation_failed: $reason; ${error.message ?: "backend unavailable"}"
            )
            UndoResult.CompensationFailed(
                "$reason; restoration failed: ${error.message ?: "backend unavailable"}"
            )
        }
    }

    fun undoBatch(batchId: String): UndoBatchResult {
        val entries = store.forBatch(batchId).filter(HistoryEntry::reversible)
        val undoBatchId = UUID.randomUUID().toString()
        var applied = 0
        var conflicts = 0
        var failed = 0
        var indeterminate = 0
        var compensationFailed = 0
        var inProgress = 0
        entries.forEach { entry ->
            when (undo(entry, undoBatchId)) {
                is UndoResult.Applied -> applied++
                is UndoResult.Conflict -> conflicts++
                is UndoResult.Failed -> failed++
                is UndoResult.Indeterminate -> indeterminate++
                is UndoResult.CompensationFailed -> compensationFailed++
                is UndoResult.InProgress -> inProgress++
            }
        }
        return UndoBatchResult(
            entries.size,
            applied,
            conflicts,
            failed,
            indeterminate,
            compensationFailed,
            inProgress
        )
    }

    private fun lockKey(entry: HistoryEntry): String = when (entry.operation) {
        OP_CONFIG_KEY, OP_CONFIG_ITEM -> "guard:configuration"
        OP_PROTECTION -> "guard:protection"
        OP_NOTIFICATION -> "guard:notification"
        OP_APP_LOCALE -> "app-locale:${entry.target}"
        OP_UI_LOCALE -> "app-locale:${entry.target}"
        else -> "history:${entry.operation}:${entry.target}"
    }

    private fun readCurrent(entry: HistoryEntry): String = when (entry.operation) {
        OP_CONFIG_KEY -> guard.getConfiguredKey()
        OP_CONFIG_ITEM -> guard.getConfiguredRequiredItem()
        OP_PROTECTION -> guard.isProtectionEnabled().toString()
        OP_NOTIFICATION -> guard.usePersistentNotification().toString()
        OP_APP_LOCALE -> locales.getLocales(entry.target)
        OP_UI_LOCALE -> locales.getOwnAppLocale()
        else -> throw IllegalArgumentException("Unsupported history operation: ${entry.operation}")
    }

    private fun writeValue(entry: HistoryEntry, value: String): Boolean = when (entry.operation) {
        OP_CONFIG_KEY -> guard.saveConfig(value, guard.getConfiguredRequiredItem())
        OP_CONFIG_ITEM -> guard.saveConfig(guard.getConfiguredKey(), value)
        OP_PROTECTION -> guard.setProtectionEnabled(value.toBooleanStrict())
        OP_NOTIFICATION -> guard.setPersistentNotification(value.toBooleanStrict())
        OP_APP_LOCALE -> locales.setLocales(entry.target, value) == value
        OP_UI_LOCALE -> locales.setOwnAppLocale(value) == value
        else -> false
    }

    companion object {
        const val OP_CONFIG_KEY = "config_key"
        const val OP_CONFIG_ITEM = "config_item"
        const val OP_PROTECTION = "protection"
        const val OP_NOTIFICATION = "persistent_notification"
        const val OP_APP_LOCALE = "app_locale"
        const val OP_UI_LOCALE = "ui_locale"
    }
}

interface GuardHistoryGateway {
    fun getConfiguredKey(): String
    fun getConfiguredRequiredItem(): String
    fun saveConfig(key: String, requiredItem: String): Boolean
    fun isProtectionEnabled(): Boolean
    fun setProtectionEnabled(enabled: Boolean): Boolean
    fun usePersistentNotification(): Boolean
    fun setPersistentNotification(enabled: Boolean): Boolean
}

class SettingsGuardHistoryGateway(
    private val settings: SettingsGuardPort
) : GuardHistoryGateway {
    override fun getConfiguredKey() = settings.getConfiguredKey()
    override fun getConfiguredRequiredItem() = settings.getConfiguredRequiredItem()
    override fun saveConfig(key: String, requiredItem: String) = settings.saveConfig(key, requiredItem)
    override fun isProtectionEnabled() = settings.isProtectionEnabled()
    override fun setProtectionEnabled(enabled: Boolean) = settings.setProtectionEnabled(enabled)
    override fun usePersistentNotification() = settings.usePersistentNotification()
    override fun setPersistentNotification(enabled: Boolean) = settings.setPersistentNotification(enabled)
}

interface SettingsGuardPort {
    fun getConfiguredKey(): String
    fun getConfiguredRequiredItem(): String
    fun saveConfig(key: String, requiredItem: String): Boolean
    fun isProtectionEnabled(): Boolean
    fun setProtectionEnabled(enabled: Boolean): Boolean
    fun usePersistentNotification(): Boolean
    fun setPersistentNotification(enabled: Boolean): Boolean
}
