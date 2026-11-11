package com.reed.fcmguard.feature.applocale

import com.reed.fcmguard.core.history.HistoryChange
import com.reed.fcmguard.core.history.HistoryEntry
import com.reed.fcmguard.core.history.HistoryRecorder
import com.reed.fcmguard.core.locale.AppLocaleRepository
import com.reed.fcmguard.core.locale.AppLocaleStatus
import com.reed.fcmguard.core.locale.LocaleApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleBatchApplierTest {
    @Test
    fun recordsSuccessfulChangesAndFailureOutcomesAndContinues() {
        val backend = FakeBackend(
            initial = mapOf(
                "com.example.one" to "system",
                "com.example.same" to "vi-VN"
            ),
            failures = setOf("com.example.fail")
        )
        val history = RecordingHistory()
        val applier = LocaleBatchApplier(backend, history)

        val result = applier.apply(
            listOf(
                app("com.example.one"),
                app("com.example.same"),
                app("com.example.fail")
            ),
            "vi-VN",
            batchId = "batch-1"
        )

        assertEquals(3, result.results.size)
        assertTrue(result.results[0].succeeded)
        assertFalse(result.results[1].changed)
        assertFalse(result.results[2].succeeded)
        assertEquals(2, history.changes.size)
        assertEquals("batch-1", history.changes[0].batchId)
        assertEquals("failed: fake failure", history.changes[1].result)
        assertFalse(history.entries[1].reversible)
        assertEquals("vi-VN", backend.values["com.example.one"])
    }

    @Test
    fun cancelledBatchDoesNotStartLaterItems() {
        val backend = FakeBackend(emptyMap())
        var cancel = false
        val result = LocaleBatchApplier(backend, RecordingHistory()).apply(
            listOf(app("com.example.one"), app("com.example.two")),
            "vi-VN",
            isCancelled = { cancel },
            onProgress = { completed, _ -> if (completed == 1) cancel = true }
        )

        assertTrue(result.cancelled)
        assertEquals(1, result.results.size)
        assertEquals(2, backend.readCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun systemAppsAreNeverIncludedInDefaultBatch() {
        LocaleBatchApplier(FakeBackend(emptyMap()), RecordingHistory()).apply(
            listOf(LocaleApp("com.example.system", "System", true)),
            "vi-VN"
        )
    }

    private fun app(packageName: String) = LocaleApp(packageName, packageName, false)

    private class FakeBackend(
        initial: Map<String, String>,
        private val failures: Set<String> = emptySet()
    ) : AppLocaleRepository {
        val values = initial.toMutableMap()
        var readCount = 0

        override fun status() = AppLocaleStatus.READY
        override fun connect(onStateChanged: () -> Unit) = Unit
        override fun disconnect() = Unit
        override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun openSetupGuide() = Unit
        override fun errorMessage(): String? = null
        override fun readLocales(packageName: String): String {
            readCount++
            if (packageName in failures) error("fake failure")
            return values[packageName] ?: "system"
        }

        override fun setLocales(packageName: String, languageTags: String) {
            if (packageName in failures) error("fake failure")
            values[packageName] = languageTags
        }

        override fun setLocalesAndVerify(packageName: String, languageTags: String): String {
            setLocales(packageName, languageTags)
            return readLocales(packageName)
        }
    }

    private class RecordingHistory : HistoryRecorder {
        val changes = mutableListOf<HistoryChange>()
        val entries = mutableListOf<HistoryEntry>()

        override fun record(change: HistoryChange): HistoryEntry {
            changes += change
            return HistoryEntry(
                id = "test-${changes.size}",
                timestamp = 1L,
                feature = change.feature,
                operation = change.operation,
                target = change.target,
                previousValue = change.previousValue,
                newValue = change.newValue,
                result = change.result,
                reversible = change.result == "success" &&
                    change.previousValue != change.newValue,
                batchId = change.batchId
            ).also(entries::add)
        }
    }
}
