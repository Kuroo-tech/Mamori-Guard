package com.reed.fcmguard.core.history

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val feature: String,
    val operation: String,
    val target: String,
    val previousValue: String,
    val newValue: String,
    val result: String,
    val reversible: Boolean,
    val batchId: String?,
    val operationState: HistoryOperationState = HistoryOperationState.APPLIED
)

enum class HistoryOperationState {
    APPLIED,
    UNDO_PENDING,
    UNDONE,
    CONFLICT,
    FAILED,
    INDETERMINATE,
    COMPENSATION_FAILED
}

enum class UndoClaim {
    CLAIMED,
    IN_PROGRESS,
    NOT_REVERSIBLE
}

data class HistoryChange(
    val feature: String,
    val operation: String,
    val target: String,
    val previousValue: String,
    val newValue: String,
    val batchId: String? = null,
    val result: String = "success"
)

interface HistoryRecorder {
    fun record(change: HistoryChange): HistoryEntry?
    fun recordOutcome(change: HistoryChange): HistoryEntry? = record(change)
}

interface HistoryRepository : HistoryRecorder {
    fun recent(limit: Int = 200): List<HistoryEntry>
    fun forBatch(batchId: String): List<HistoryEntry>
    fun recordAll(changes: List<HistoryChange>): List<HistoryEntry>
    fun recordUndo(change: HistoryChange, originalId: String): HistoryEntry?
    fun beginUndo(entryId: String, expectedAfter: String): UndoClaim
    fun completeUndo(change: HistoryChange, originalId: String): HistoryEntry?
    fun finishUndoWithoutChange(entryId: String, result: String): Boolean
    fun finishUndoOutcome(
        entryId: String,
        state: HistoryOperationState,
        result: String,
        reversible: Boolean = false
    ): Boolean
    fun markUndoConflict(entryId: String, result: String): Boolean
    fun recoverInterruptedUndos(): Int
}

sealed interface UndoDecision {
    data object Safe : UndoDecision
    data class Conflict(val expected: String, val actual: String) : UndoDecision
}

fun decideUndo(entry: HistoryEntry, currentValue: String): UndoDecision =
    if (entry.reversible && currentValue == entry.newValue) {
        UndoDecision.Safe
    } else {
        UndoDecision.Conflict(entry.newValue, currentValue)
    }

const val HISTORY_VALUE_UNAVAILABLE = "(unavailable)"
