package com.reed.fcmguard.core.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class HistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), HistoryRepository {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
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
                operation_state TEXT NOT NULL DEFAULT 'APPLIED',
                operation_owner TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX history_timestamp_idx ON history(timestamp DESC)")
        db.execSQL("CREATE INDEX history_batch_idx ON history(batch_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && oldVersion >= 1) {
            db.execSQL("ALTER TABLE history ADD COLUMN batch_id TEXT")
            db.execSQL("CREATE INDEX history_batch_idx ON history(batch_id)")
        }
        if (oldVersion < 3) {
            db.execSQL(
                "ALTER TABLE history ADD COLUMN operation_state TEXT NOT NULL DEFAULT 'APPLIED'"
            )
            db.execSQL(
                "UPDATE history SET operation_state = CASE " +
                    "WHEN reversible = 1 THEN 'APPLIED' " +
                    "WHEN operation_result = 'success' THEN 'UNDONE' ELSE 'FAILED' END"
            )
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE history ADD COLUMN operation_owner TEXT")
        }
    }

    override fun record(change: HistoryChange): HistoryEntry? {
        if (change.result != "success") return null
        return recordAll(listOf(change)).firstOrNull()
    }

    override fun recordOutcome(change: HistoryChange): HistoryEntry? {
        if (change.result == "success") return record(change)
        if (change.previousValue == change.newValue) return null
        return insertEntries(listOf(change)).firstOrNull()
    }

    override fun recordAll(changes: List<HistoryChange>): List<HistoryEntry> {
        return insertEntries(changes.filter {
            it.result == "success" && it.previousValue != it.newValue
        })
    }

    private fun insertEntries(changes: List<HistoryChange>): List<HistoryEntry> {
        if (changes.isEmpty()) return emptyList()
        val db = writableDatabase
        db.beginTransaction()
        try {
            var timestamp = nextTimestamp(db)
            val entries = changes.map { change ->
                HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = timestamp++,
                    feature = change.feature,
                    operation = change.operation,
                    target = change.target,
                    previousValue = change.previousValue,
                    newValue = change.newValue,
                    result = change.result,
                    reversible = change.result == "success" &&
                        change.previousValue != change.newValue,
                    batchId = change.batchId,
                    operationState = if (change.result == "success" &&
                        change.previousValue != change.newValue
                    ) HistoryOperationState.APPLIED else HistoryOperationState.FAILED
                )
            }
            entries.forEach { db.insertOrThrow(TABLE, null, it.toValues()) }
            db.setTransactionSuccessful()
            return entries
        } finally {
            db.endTransaction()
        }
    }

    @Deprecated("Use beginUndo followed by completeUndo for recoverable Undo state")
    override fun recordUndo(change: HistoryChange, originalId: String): HistoryEntry? {
        return completeUndo(change, originalId)
    }

    override fun beginUndo(entryId: String, expectedAfter: String): UndoClaim {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("reversible", 0)
                put("operation_state", HistoryOperationState.UNDO_PENDING.name)
                put("operation_result", HistoryOperationState.UNDO_PENDING.name.lowercase())
                put("operation_owner", PROCESS_OWNER)
            }
            val updated = db.update(
                TABLE,
                values,
                "id = ? AND reversible = 1 AND operation_state = ? AND new_value = ?",
                arrayOf(entryId, HistoryOperationState.APPLIED.name, expectedAfter)
            )
            val state = queryState(db, entryId)
            db.setTransactionSuccessful()
            return when {
                updated == 1 -> UndoClaim.CLAIMED
                state == HistoryOperationState.UNDO_PENDING -> UndoClaim.IN_PROGRESS
                else -> UndoClaim.NOT_REVERSIBLE
            }
        } finally {
            db.endTransaction()
        }
    }

    override fun completeUndo(change: HistoryChange, originalId: String): HistoryEntry? {
        if (change.previousValue == change.newValue) return null
        val db = writableDatabase
        db.beginTransaction()
        try {
            val entry = HistoryEntry(
                id = UUID.randomUUID().toString(),
                timestamp = nextTimestamp(db),
                feature = change.feature,
                operation = change.operation,
                target = change.target,
                previousValue = change.previousValue,
                newValue = change.newValue,
                result = "success",
                reversible = true,
                batchId = change.batchId,
                operationState = HistoryOperationState.APPLIED
            )
            db.insertOrThrow(TABLE, null, entry.toValues())
            val values = ContentValues().apply {
                put("reversible", 0)
                put("operation_state", HistoryOperationState.UNDONE.name)
                put("operation_result", HistoryOperationState.UNDONE.name.lowercase())
                putNull("operation_owner")
            }
            check(
                db.update(
                    TABLE,
                    values,
                    "id = ? AND operation_state = ? AND reversible = 0 AND operation_owner = ?",
                    arrayOf(
                        originalId,
                        HistoryOperationState.UNDO_PENDING.name,
                        PROCESS_OWNER
                    )
                ) == 1
            ) {
                "History entry is not claimed for Undo"
            }
            db.setTransactionSuccessful()
            return entry
        } finally {
            db.endTransaction()
        }
    }

    private fun nextTimestamp(db: SQLiteDatabase): Long {
        val lastTimestamp = db.rawQuery("SELECT MAX(timestamp) FROM $TABLE", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
        return maxOf(System.currentTimeMillis(), lastTimestamp + 1)
    }

    override fun finishUndoWithoutChange(entryId: String, result: String): Boolean {
        val values = ContentValues().apply {
            put("reversible", 1)
            put("operation_state", HistoryOperationState.APPLIED.name)
            put("operation_result", result)
            putNull("operation_owner")
        }
        return writableDatabase.update(
            TABLE,
            values,
            "id = ? AND operation_state = ? AND operation_owner = ?",
            arrayOf(entryId, HistoryOperationState.UNDO_PENDING.name, PROCESS_OWNER)
        ) == 1
    }

    override fun finishUndoOutcome(
        entryId: String,
        state: HistoryOperationState,
        result: String,
        reversible: Boolean
    ): Boolean {
        val values = ContentValues().apply {
            put("reversible", if (reversible) 1 else 0)
            put("operation_state", state.name)
            put("operation_result", result)
            putNull("operation_owner")
        }
        return writableDatabase.update(
            TABLE,
            values,
            "id = ? AND operation_state = ? AND operation_owner = ?",
            arrayOf(entryId, HistoryOperationState.UNDO_PENDING.name, PROCESS_OWNER)
        ) == 1
    }

    override fun markUndoConflict(entryId: String, result: String): Boolean {
        val values = ContentValues().apply {
            put("reversible", 0)
            put("operation_state", HistoryOperationState.CONFLICT.name)
            put("operation_result", result)
            putNull("operation_owner")
        }
        return writableDatabase.update(
            TABLE,
            values,
            "id = ? AND (operation_state = ? OR " +
                "(operation_state = ? AND operation_owner = ?))",
            arrayOf(
                entryId,
                HistoryOperationState.APPLIED.name,
                HistoryOperationState.UNDO_PENDING.name,
                PROCESS_OWNER
            )
        ) == 1
    }

    override fun recoverInterruptedUndos(): Int {
        val values = ContentValues().apply {
            put("reversible", 0)
            put("operation_state", HistoryOperationState.INDETERMINATE.name)
            put("operation_result", "indeterminate: process interrupted during undo")
            putNull("operation_owner")
        }
        return writableDatabase.update(
            TABLE,
            values,
            "operation_state = ? AND (operation_owner IS NULL OR operation_owner != ?)",
            arrayOf(HistoryOperationState.UNDO_PENDING.name, PROCESS_OWNER)
        )
    }

    private fun queryState(db: SQLiteDatabase, entryId: String): HistoryOperationState? =
        db.query(
            TABLE,
            arrayOf("operation_state"),
            "id = ?",
            arrayOf(entryId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                runCatching { HistoryOperationState.valueOf(cursor.getString(0)) }.getOrNull()
            } else null
        }

    override fun recent(limit: Int): List<HistoryEntry> {
        require(limit in 1..1000)
        return queryEntries(
            orderBy = "timestamp DESC, id DESC",
            limit = limit.toString()
        )
    }

    override fun forBatch(batchId: String): List<HistoryEntry> =
        queryEntries(
            selection = "batch_id = ?",
            selectionArgs = arrayOf(batchId),
            orderBy = "timestamp ASC, id ASC"
        )

    private fun queryEntries(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        orderBy: String,
        limit: String? = null
    ): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        readableDatabase.query(
            TABLE,
            COLUMNS,
            selection,
            selectionArgs,
            null,
            null,
            orderBy,
            limit
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += HistoryEntry(
                    id = cursor.getString(0),
                    timestamp = cursor.getLong(1),
                    feature = cursor.getString(2),
                    operation = cursor.getString(3),
                    target = cursor.getString(4),
                    previousValue = cursor.getString(5),
                    newValue = cursor.getString(6),
                    result = cursor.getString(7),
                    reversible = cursor.getInt(8) != 0,
                    batchId = if (cursor.isNull(9)) null else cursor.getString(9),
                    operationState = runCatching {
                        HistoryOperationState.valueOf(cursor.getString(10))
                    }.getOrDefault(HistoryOperationState.INDETERMINATE)
                )
            }
        }
        return entries
    }

    private fun HistoryEntry.toValues() = ContentValues().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("feature", feature)
        put("operation", operation)
        put("target", target)
        put("previous_value", previousValue)
        put("new_value", newValue)
        put("operation_result", result)
        put("reversible", if (reversible) 1 else 0)
        if (batchId == null) putNull("batch_id") else put("batch_id", batchId)
        put("operation_state", operationState.name)
        putNull("operation_owner")
    }

    companion object {
        private const val DATABASE_NAME = "fcm_guard_history.db"
        private const val DATABASE_VERSION = 4
        private val PROCESS_OWNER = UUID.randomUUID().toString()
        private const val TABLE = "history"
        private val COLUMNS = arrayOf(
            "id", "timestamp", "feature", "operation", "target",
            "previous_value", "new_value", "operation_result", "reversible", "batch_id",
            "operation_state"
        )
    }
}
