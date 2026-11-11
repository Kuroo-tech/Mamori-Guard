package com.reed.fcmguard.core.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContentValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {
    @Test
    fun changesSurviveClosingAndReopeningTheDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("fcm_guard_history.db")

        val firstStore = HistoryStore(context)
        firstStore.record(
            HistoryChange(
                feature = "app_locale",
                operation = "app_locale",
                target = "com.example.app",
                previousValue = "system",
                newValue = "vi-VN",
                batchId = "batch-persistence"
            )
        )
        firstStore.recordOutcome(
            HistoryChange(
                feature = "app_locale",
                operation = "app_locale",
                target = "com.example.failed",
                previousValue = HISTORY_VALUE_UNAVAILABLE,
                newValue = "vi-VN",
                batchId = "batch-persistence",
                result = "failed: service unavailable"
            )
        )
        firstStore.close()

        val reopenedStore = HistoryStore(context)
        val entries = reopenedStore.recent()
        val entry = entries.single { it.target == "com.example.app" }
        assertEquals("vi-VN", entry.newValue)
        assertEquals("batch-persistence", entry.batchId)
        val failure = entries.single { it.target == "com.example.failed" }
        assertEquals("failed: service unavailable", failure.result)
        assertFalse(failure.reversible)
        reopenedStore.close()
        context.deleteDatabase("fcm_guard_history.db")
    }

    @Test
    fun versionThreeDatabaseMigratesAndSupportsUndoClaims() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fcm_guard_history.db"
        context.deleteDatabase(databaseName)
        val oldDatabase = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        oldDatabase.execSQL(
            """
            CREATE TABLE history (
                id TEXT PRIMARY KEY NOT NULL,
                timestamp INTEGER NOT NULL,
                feature TEXT NOT NULL,
                operation TEXT NOT NULL,
                target TEXT NOT NULL,
                previous_value TEXT NOT NULL,
                new_value TEXT NOT NULL,
                operation_result TEXT NOT NULL,
                reversible INTEGER NOT NULL,
                batch_id TEXT,
                operation_state TEXT NOT NULL DEFAULT 'APPLIED'
            )
            """.trimIndent()
        )
        oldDatabase.execSQL(
            "INSERT INTO history VALUES " +
                "('legacy', 1, 'app_locale', 'app_locale', 'com.example.app', " +
                "'system', 'vi-VN', 'success', 1, NULL, 'APPLIED')"
        )
        oldDatabase.version = 3
        oldDatabase.close()

        val migratedStore = HistoryStore(context)
        val legacyEntry = migratedStore.recent().single()
        assertEquals("legacy", legacyEntry.id)
        assertEquals(UndoClaim.CLAIMED, migratedStore.beginUndo("legacy", "vi-VN"))
        assertTrue(migratedStore.finishUndoWithoutChange("legacy", "test rollback"))
        assertTrue(migratedStore.recent().single().reversible)
        migratedStore.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun interruptedUndoIsMarkedIndeterminateInsteadOfReplayed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fcm_guard_history.db"
        context.deleteDatabase(databaseName)
        val store = HistoryStore(context)
        store.record(
            HistoryChange(
                feature = "app_locale",
                operation = "app_locale",
                target = "com.example.app",
                previousValue = "system",
                newValue = "vi-VN"
            )
        )
        val entry = store.recent().single()
        assertEquals(UndoClaim.CLAIMED, store.beginUndo(entry.id, entry.newValue))
        store.close()

        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        db.update(
            "history",
            ContentValues().apply { put("operation_owner", "dead-process") },
            "id = ?",
            arrayOf(entry.id)
        )
        db.close()

        val reopenedStore = HistoryStore(context)
        assertEquals(1, reopenedStore.recoverInterruptedUndos())
        assertEquals(
            HistoryOperationState.INDETERMINATE,
            reopenedStore.recent().single().operationState
        )
        reopenedStore.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun failedInverseInsertRollsBackTheDatabaseTransition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "fcm_guard_history.db"
        context.deleteDatabase(databaseName)
        val store = HistoryStore(context)
        store.record(
            HistoryChange(
                feature = "app_locale",
                operation = "app_locale",
                target = "com.example.app",
                previousValue = "system",
                newValue = "vi-VN"
            )
        )
        val original = store.recent().single()
        assertEquals(UndoClaim.CLAIMED, store.beginUndo(original.id, original.newValue))
        store.writableDatabase.execSQL(
            "CREATE TRIGGER reject_history_insert BEFORE INSERT ON history " +
                "BEGIN SELECT RAISE(ABORT, 'forced insert failure'); END"
        )

        assertTrue(
            runCatching {
                store.completeUndo(
                    HistoryChange(
                        feature = original.feature,
                        operation = original.operation,
                        target = original.target,
                        previousValue = original.newValue,
                        newValue = original.previousValue
                    ),
                    original.id
                )
            }.isFailure
        )
        val afterFailure = store.recent()
        assertEquals(1, afterFailure.size)
        assertEquals(HistoryOperationState.UNDO_PENDING, afterFailure.single().operationState)
        store.close()
        context.deleteDatabase(databaseName)
    }
}
