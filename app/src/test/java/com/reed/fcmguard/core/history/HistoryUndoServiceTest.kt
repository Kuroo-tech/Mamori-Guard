package com.reed.fcmguard.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HistoryUndoServiceTest {
    @Test
    fun conflictDoesNotOverwriteValueOrWriteHistory() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original))
        val locales = FakeLocales("fr")

        val result = service(store, locales).undo(original)

        assertEquals(UndoResult.Conflict(expected = "vi-VN", actual = "fr"), result)
        assertEquals("fr", locales.current)
        assertEquals(0, locales.writeCount)
        assertEquals(1, store.entries.size)
    }

    @Test
    fun safeUndoVerifiesAndRecordsTheInverseOperation() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original))
        val locales = FakeLocales("vi-VN")

        val result = service(store, locales).undo(original)

        assertTrue(result is UndoResult.Applied)
        assertEquals("system", locales.current)
        assertEquals(1, locales.writeCount)
        assertEquals(2, store.entries.size)
        assertEquals("vi-VN", store.entries.last().previousValue)
        assertEquals("system", store.entries.last().newValue)
        assertEquals(false, store.entries.first().reversible)
    }

    @Test
    fun batchUndoOnlyChangesStillMatchingSuccessfulEntries() {
        val safe = localeEntry(previous = "system", current = "vi-VN")
        val conflict = localeEntry(previous = "fr", current = "vi-VN").copy(
            id = "change-2",
            target = "com.example.other"
        )
        val store = FakeHistoryRepository(listOf(safe, conflict))
        val locales = FakeLocales("vi-VN")
        val localeValues = mapOf("com.example.app" to "vi-VN", "com.example.other" to "de")
        locales.values.putAll(localeValues)

        val result = service(store, locales).undoBatch("batch-1")

        assertEquals(2, result.total)
        assertEquals(1, result.applied)
        assertEquals(1, result.conflicts)
        assertEquals(1, locales.writeCount)
        assertEquals("system", locales.values["com.example.app"])
        assertEquals("de", locales.values["com.example.other"])
    }

    @Test
    fun concurrentUndoOnlyWritesOnce() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original))
        val locales = FakeLocales("vi-VN").apply {
            pauseOnWrite = CountDownLatch(1)
            writeEntered = CountDownLatch(1)
        }
        val undo = service(store, locales)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<UndoResult> { undo.undo(original) }
            assertTrue(locales.writeEntered!!.await(2, TimeUnit.SECONDS))
            val second = executor.submit<UndoResult> { undo.undo(original) }
            locales.pauseOnWrite!!.countDown()
            assertTrue(first.get(2, TimeUnit.SECONDS) is UndoResult.Applied)
            assertTrue(second.get(2, TimeUnit.SECONDS) is UndoResult.Conflict)
            assertEquals(1, locales.writeCount)
            assertEquals(2, store.entries.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun undosForDifferentTargetsCanProceedIndependently() {
        val firstEntry = localeEntry(previous = "system", current = "vi-VN")
        val secondEntry = firstEntry.copy(
            id = "change-2",
            target = "com.example.other",
            previousValue = "fr",
            newValue = "de"
        )
        val store = FakeHistoryRepository(listOf(firstEntry, secondEntry))
        val locales = FakeLocales("unused").apply {
            values[firstEntry.target] = firstEntry.newValue
            values[secondEntry.target] = secondEntry.newValue
            pauseOnWrite = CountDownLatch(1)
            writeEntered = CountDownLatch(1)
            pausePackage = firstEntry.target
        }
        val undo = service(store, locales)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<UndoResult> { undo.undo(firstEntry) }
            assertTrue(locales.writeEntered!!.await(2, TimeUnit.SECONDS))
            val second = executor.submit<UndoResult> { undo.undo(secondEntry) }

            assertTrue(second.get(2, TimeUnit.SECONDS) is UndoResult.Applied)
            locales.pauseOnWrite!!.countDown()
            assertTrue(first.get(2, TimeUnit.SECONDS) is UndoResult.Applied)
            assertEquals("system", locales.values[firstEntry.target])
            assertEquals("fr", locales.values[secondEntry.target])
        } finally {
            locales.pauseOnWrite?.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedWriteLeavesTargetAndEntryReversible() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original))
        val locales = FakeLocales("vi-VN").apply { failWrites = true }

        val result = service(store, locales).undo(original)

        assertTrue(result is UndoResult.Failed)
        assertEquals("vi-VN", locales.current)
        assertTrue(store.entries.first().reversible)
    }

    @Test
    fun writeExceptionAfterChangeIsAcceptedOnlyAfterReadBackAndRecorded() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original))
        val locales = FakeLocales("vi-VN").apply { throwAfterWrite = true }

        val result = service(store, locales).undo(original)

        assertTrue(result is UndoResult.Applied)
        assertEquals("system", locales.current)
        assertEquals(2, store.entries.size)
    }

    @Test
    fun verificationMismatchDoesNotClaimUndoSucceeded() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original))
        val locales = FakeLocales("vi-VN").apply { mismatchReadAfterWrite = true }

        val result = service(store, locales).undo(original)

        assertTrue(result is UndoResult.Indeterminate)
        assertEquals(HistoryOperationState.INDETERMINATE, store.entries.first().operationState)
        assertFalse(store.entries.first().reversible)
    }

    @Test
    fun historyCommitFailureCompensatesOnlyAfterVerifiedRollback() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original)).apply { failCompleteUndo = true }
        val locales = FakeLocales("vi-VN")

        val result = service(store, locales).undo(original)

        assertTrue(result is UndoResult.Failed)
        assertEquals("vi-VN", locales.current)
        assertEquals(2, locales.writeCount)
        assertTrue(store.entries.first().reversible)
    }

    @Test
    fun failedCompensationIsReportedSeparately() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original)).apply { failCompleteUndo = true }
        val locales = FakeLocales("vi-VN").apply { failOnWriteNumber = 2 }

        val result = service(store, locales).undo(original)

        assertTrue(result is UndoResult.CompensationFailed)
        assertEquals("system", locales.current)
        assertEquals(
            HistoryOperationState.COMPENSATION_FAILED,
            store.entries.first().operationState
        )
    }

    @Test
    fun backendLossAfterWriteIsIndeterminate() {
        val original = localeEntry(previous = "system", current = "vi-VN")
        val store = FakeHistoryRepository(listOf(original))
        val locales = FakeLocales("vi-VN").apply { failReadsAfterWrite = true }

        val result = service(store, locales).undo(original)

        assertTrue(result is UndoResult.Indeterminate)
        assertEquals(
            HistoryOperationState.INDETERMINATE,
            store.entries.first().operationState
        )
    }

    private fun service(repository: FakeHistoryRepository, locales: FakeLocales) =
        HistoryUndoService(
            repository,
            object : GuardHistoryGateway {
                override fun getConfiguredKey() = "settings_key"
                override fun getConfiguredRequiredItem() = "com.google.android.gms"
                override fun saveConfig(key: String, requiredItem: String) = true
                override fun isProtectionEnabled() = false
                override fun setProtectionEnabled(enabled: Boolean) = true
                override fun usePersistentNotification() = true
                override fun setPersistentNotification(enabled: Boolean) = true
            },
            locales
        )

    private fun localeEntry(previous: String, current: String) = HistoryEntry(
        id = "change-1",
        timestamp = 1L,
        feature = "app_locale",
        operation = HistoryUndoService.OP_APP_LOCALE,
        target = "com.example.app",
        previousValue = previous,
        newValue = current,
        result = "success",
        reversible = true,
        batchId = "batch-1"
    )

    private class FakeLocales(var current: String) : LocaleHistoryGateway {
        var writeCount = 0
        val values = mutableMapOf<String, String>()
        var failWrites = false
        var failOnWriteNumber: Int? = null
        var failReadsAfterWrite = false
        var throwAfterWrite = false
        var mismatchReadAfterWrite = false
        var writeEntered: CountDownLatch? = null
        var pauseOnWrite: CountDownLatch? = null
        var pausePackage: String? = null

        override fun getLocales(packageName: String): String {
            if (failReadsAfterWrite && writeCount > 0) error("fake backend unavailable")
            return if (mismatchReadAfterWrite && writeCount > 0) {
                "external"
            } else {
                values[packageName] ?: current
            }
        }
        override fun setLocales(packageName: String, languageTags: String): String {
            writeCount++
            writeEntered?.countDown()
            if (failWrites || failOnWriteNumber == writeCount) error("fake write failure")
            if (pausePackage == null || pausePackage == packageName) {
                pauseOnWrite?.await(2, TimeUnit.SECONDS)
            }
            current = languageTags
            values[packageName] = languageTags
            if (throwAfterWrite) error("write failed after applying")
            return languageTags
        }

        override fun getOwnAppLocale() = current
        override fun setOwnAppLocale(languageTags: String) = setLocales("", languageTags)
    }

    private class FakeHistoryRepository(initial: List<HistoryEntry>) : HistoryRepository {
        val entries = initial.toMutableList()
        var failCompleteUndo = false
        override fun recent(limit: Int) = entries.take(limit)
        override fun forBatch(batchId: String) = entries.filter { it.batchId == batchId }

        override fun record(change: HistoryChange): HistoryEntry? {
            if (change.previousValue == change.newValue) return null
            return newEntry(change).also(entries::add)
        }

        override fun recordOutcome(change: HistoryChange): HistoryEntry? = record(change)
        override fun recordAll(changes: List<HistoryChange>) = changes.mapNotNull(::record)
        override fun recordUndo(change: HistoryChange, originalId: String) =
            completeUndo(change, originalId)

        override fun beginUndo(entryId: String, expectedAfter: String): UndoClaim {
            val index = entries.indexOfFirst { it.id == entryId }
            if (index < 0) return UndoClaim.NOT_REVERSIBLE
            val entry = entries[index]
            if (entry.operationState == HistoryOperationState.UNDO_PENDING) {
                return UndoClaim.IN_PROGRESS
            }
            if (!entry.reversible || entry.operationState != HistoryOperationState.APPLIED ||
                entry.newValue != expectedAfter
            ) return UndoClaim.NOT_REVERSIBLE
            entries[index] = entry.copy(
                reversible = false,
                operationState = HistoryOperationState.UNDO_PENDING
            )
            return UndoClaim.CLAIMED
        }

        override fun completeUndo(change: HistoryChange, originalId: String): HistoryEntry? {
            if (failCompleteUndo) error("fake history storage failure")
            val index = entries.indexOfFirst {
                it.id == originalId && it.operationState == HistoryOperationState.UNDO_PENDING
            }
            if (index < 0 || change.previousValue == change.newValue) return null
            val entry = newEntry(change)
            entries[index] = entries[index].copy(operationState = HistoryOperationState.UNDONE)
            entries += entry
            return entry
        }

        override fun finishUndoWithoutChange(entryId: String, result: String): Boolean {
            val index = entries.indexOfFirst { it.id == entryId }
            if (index < 0 || entries[index].operationState != HistoryOperationState.UNDO_PENDING) {
                return false
            }
            entries[index] = entries[index].copy(
                reversible = true,
                result = result,
                operationState = HistoryOperationState.APPLIED
            )
            return true
        }

        override fun finishUndoOutcome(
            entryId: String,
            state: HistoryOperationState,
            result: String,
            reversible: Boolean
        ): Boolean {
            val index = entries.indexOfFirst { it.id == entryId }
            if (index < 0) return false
            entries[index] = entries[index].copy(
                reversible = reversible,
                result = result,
                operationState = state
            )
            return true
        }

        override fun markUndoConflict(entryId: String, result: String): Boolean {
            val entry = entries.firstOrNull { it.id == entryId } ?: return false
            if (entry.operationState != HistoryOperationState.APPLIED &&
                entry.operationState != HistoryOperationState.UNDO_PENDING
            ) return false
            return finishUndoOutcome(entryId, HistoryOperationState.CONFLICT, result)
        }

        override fun recoverInterruptedUndos() = 0

        private fun newEntry(change: HistoryChange) = HistoryEntry(
            id = "change-${entries.size + 1}",
            timestamp = entries.size.toLong() + 1,
            feature = change.feature,
            operation = change.operation,
            target = change.target,
            previousValue = change.previousValue,
            newValue = change.newValue,
            result = "success",
            reversible = true,
            batchId = change.batchId
        )
    }
}
