package com.reed.fcmguard.feature.applocale

import com.reed.fcmguard.core.history.HistoryChange
import com.reed.fcmguard.core.history.HISTORY_VALUE_UNAVAILABLE
import com.reed.fcmguard.core.history.HistoryRecorder
import com.reed.fcmguard.core.locale.AppLocaleRepository
import com.reed.fcmguard.core.locale.LocaleApp
import com.reed.fcmguard.core.locale.SYSTEM_DEFAULT
import com.reed.fcmguard.core.operations.TargetOperationLock
import com.reed.fcmguard.core.operations.TargetOperationLocks
import java.util.UUID

data class LocaleAppResult(
    val packageName: String,
    val previous: String?,
    val actual: String?,
    val error: String?,
    val warning: String? = null
) {
    val succeeded: Boolean get() = error == null
    val changed: Boolean get() = succeeded && previous != actual
}

data class LocaleBatchResult(
    val batchId: String,
    val results: List<LocaleAppResult>,
    val cancelled: Boolean
)

class LocaleBatchApplier(
    private val backend: AppLocaleRepository,
    private val history: HistoryRecorder,
    private val operationLocks: TargetOperationLock = TargetOperationLocks
) {
    fun apply(
        apps: List<LocaleApp>,
        languageTags: String,
        batchId: String = UUID.randomUUID().toString(),
        isCancelled: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): LocaleBatchResult {
        require(languageTags == SYSTEM_DEFAULT || VALID_TAGS.matches(languageTags)) {
            "Invalid locale tag list"
        }
        require(apps.none(LocaleApp::isSystemApp)) {
            "System applications must be explicitly excluded from the batch"
        }
        val uniqueApps = apps.distinctBy(LocaleApp::packageName)
        val results = ArrayList<LocaleAppResult>(uniqueApps.size)
        var cancelled = false

        for ((index, app) in uniqueApps.withIndex()) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            val result = applyOne(app, languageTags, batchId)
            results += result
            onProgress(index + 1, uniqueApps.size)
        }
        return LocaleBatchResult(batchId, results, cancelled)
    }

    fun retry(app: LocaleApp, languageTags: String, batchId: String = UUID.randomUUID().toString()) =
        applyOne(app, languageTags, batchId)

    private fun applyOne(app: LocaleApp, languageTags: String, batchId: String): LocaleAppResult =
        operationLocks.withLock("app-locale:${app.packageName}") {
            applyOneLocked(app, languageTags, batchId)
        }

    private fun applyOneLocked(
        app: LocaleApp,
        languageTags: String,
        batchId: String
    ): LocaleAppResult {
        var previous: String? = null
        var actual: String? = null
        return try {
            previous = backend.readLocales(app.packageName)
            if (previous == languageTags) {
                return LocaleAppResult(app.packageName, previous, previous, null)
            }
            actual = backend.setLocalesAndVerify(app.packageName, languageTags)
            check(actual == languageTags) {
                "Locale verification failed: requested $languageTags, received $actual"
            }
            val warning = try {
                val record = history.record(
                    HistoryChange(
                        feature = "app_locale",
                        operation = "app_locale",
                        target = app.packageName,
                        previousValue = previous,
                        newValue = actual,
                        batchId = batchId
                    )
                )
                if (record == null) "Change applied, but no history entry was created" else null
            } catch (error: Exception) {
                "Change applied and verified, but history could not be saved: " +
                    (error.message ?: "storage error")
            }
            LocaleAppResult(app.packageName, previous, actual, null, warning)
        } catch (error: Exception) {
            val failureMessage = error.message ?: "Locale operation failed"
            val historyWarning = try {
                val entry = history.recordOutcome(
                    HistoryChange(
                        feature = "app_locale",
                        operation = "app_locale",
                        target = app.packageName,
                        previousValue = previous ?: HISTORY_VALUE_UNAVAILABLE,
                        newValue = actual ?: languageTags,
                        batchId = batchId,
                        result = "failed: $failureMessage"
                    )
                )
                if (entry == null) "Failure outcome was not recorded in history" else null
            } catch (historyError: Exception) {
                "Failure outcome could not be saved to history: " +
                    (historyError.message ?: "storage error")
            }
            LocaleAppResult(
                app.packageName,
                previous,
                actual,
                failureMessage,
                historyWarning
            )
        }
    }

    companion object {
        private val VALID_TAGS = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*(,[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*)*")
    }
}
