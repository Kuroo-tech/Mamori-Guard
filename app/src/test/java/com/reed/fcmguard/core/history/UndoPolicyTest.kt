package com.reed.fcmguard.core.history

import org.junit.Assert.assertEquals
import org.junit.Test

class UndoPolicyTest {
    @Test
    fun undoIsSafeOnlyWhenCurrentValueMatchesRecordedAfterValue() {
        val entry = entry(reversible = true)

        assertEquals(UndoDecision.Safe, decideUndo(entry, "vi-VN"))
        assertEquals(
            UndoDecision.Conflict(expected = "vi-VN", actual = "fr"),
            decideUndo(entry, "fr")
        )
    }

    @Test
    fun alreadyUndoneEntryCannotBeUndoneAgain() {
        assertEquals(
            UndoDecision.Conflict(expected = "vi-VN", actual = "vi-VN"),
            decideUndo(entry(reversible = false), "vi-VN")
        )
    }

    private fun entry(reversible: Boolean) = HistoryEntry(
        id = "1",
        timestamp = 1L,
        feature = "app_locale",
        operation = "app_locale",
        target = "com.example.app",
        previousValue = "system",
        newValue = "vi-VN",
        result = "success",
        reversible = reversible,
        batchId = "batch-1"
    )
}
