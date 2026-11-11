package com.reed.fcmguard.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reed.fcmguard.core.history.HistoryEntry
import com.reed.fcmguard.core.history.HistoryRepository
import com.reed.fcmguard.core.history.HistoryUndoService
import com.reed.fcmguard.core.history.HistoryOperationState
import com.reed.fcmguard.core.history.UndoResult
import com.reed.fcmguard.ui.components.GuardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(store: HistoryRepository, undoService: HistoryUndoService) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var pendingUndo by remember { mutableStateOf<HistoryEntry?>(null) }
    var pendingBatchUndo by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var workingId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            try {
                entries = withContext(Dispatchers.IO) {
                    store.recoverInterruptedUndos()
                    store.recent()
                }
                loadError = null
            } catch (error: Exception) {
                loadError = error.message ?: "Could not read the change history."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(store) { reload() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = ::reload) { Text("Refresh") }
            }
        }
        if (loading) {
            item { CircularProgressIndicator() }
        } else if (loadError != null) {
            item { Text(loadError!!, color = MaterialTheme.colorScheme.error) }
        } else if (entries.isEmpty()) {
            item {
                GuardCard(title = "No changes yet") {
                    Text("Successful, non-no-op settings changes will appear here and remain available after restart.")
                }
            }
        }
        items(entries, key = HistoryEntry::id) { entry ->
            Card(modifier = Modifier.fillMaxWidth().clickable { selectedEntry = entry }) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(entry.operation.toDisplayName(), fontWeight = FontWeight.SemiBold)
                    Text(entry.target, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(entry.timestamp)),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("${entry.previousValue.displayValue()}  →  ${entry.newValue.displayValue()}")
                    if (entry.batchId != null) Text("Batch ${entry.batchId.take(8)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selectedEntry = entry }) { Text("Details") }
                        if (entry.reversible) {
                            Button(
                                enabled = workingId == null,
                                onClick = { pendingUndo = entry }
                            ) { Text(if (workingId == entry.id) "Undoing…" else "Undo") }
                        } else {
                            Text(
                                when (entry.operationState) {
                                    HistoryOperationState.UNDONE -> "Undone"
                                    HistoryOperationState.CONFLICT -> "Conflict"
                                    HistoryOperationState.INDETERMINATE -> "Indeterminate"
                                    HistoryOperationState.COMPENSATION_FAILED -> "Compensation failed"
                                    HistoryOperationState.UNDO_PENDING -> "Undo pending"
                                    else -> "Not reversible"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        if (entry.batchId != null &&
                            entry.operationState == HistoryOperationState.APPLIED
                        ) {
                            TextButton(
                                enabled = workingId == null,
                                onClick = { pendingBatchUndo = entry.batchId }
                            ) { Text("Undo batch") }
                        }
                    }
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text("Change details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Feature: ${entry.feature}")
                    Text("Operation: ${entry.operation}")
                    Text("Target: ${entry.target}")
                    Text("Before: ${entry.previousValue.displayValue()}")
                    Text("After: ${entry.newValue.displayValue()}")
                    Text("Result: ${entry.result}")
                    Text("Time: ${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))}")
                    entry.batchId?.let { Text("Batch: $it") }
                }
            },
            confirmButton = { TextButton(onClick = { selectedEntry = null }) { Text("Close") } }
        )
    }

    pendingUndo?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingUndo = null },
            title = { Text("Undo this change?") },
            text = {
                Text(
                    "The app will read the current value first and undo only if it still matches " +
                        "the recorded after-value. Conflicts are reported without overwriting newer changes."
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingUndo = null
                    workingId = entry.id
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { undoService.undo(entry) }
                        resultMessage = when (result) {
                            is UndoResult.Applied -> "Undo applied and verified."
                            is UndoResult.Conflict ->
                                "Conflict: expected ${result.expected.displayValue()}, current value is ${result.actual.displayValue()}. Nothing was changed."
                            is UndoResult.Failed -> "Undo failed: ${result.message}"
                            is UndoResult.InProgress -> result.message
                            is UndoResult.Indeterminate -> "Undo outcome is indeterminate: ${result.message}"
                            is UndoResult.CompensationFailed ->
                                "Undo failed and compensation could not be verified: ${result.message}"
                        }
                        workingId = null
                        reload()
                    }
                }) { Text("Check and undo") }
            },
            dismissButton = { TextButton(onClick = { pendingUndo = null }) { Text("Cancel") } }
        )
    }

    pendingBatchUndo?.let { batchId ->
        AlertDialog(
            onDismissRequest = { pendingBatchUndo = null },
            title = { Text("Undo this batch?") },
            text = {
                Text(
                    "Only successfully applied and verified items in this batch are recorded. " +
                        "Each item is checked against its current value; conflicts are skipped."
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingBatchUndo = null
                    workingId = batchId
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            undoService.undoBatch(batchId)
                        }
                        resultMessage =
                            "Batch undo: ${result.applied} applied, ${result.conflicts} conflicts, " +
                                "${result.failed} failed, ${result.indeterminate} indeterminate, " +
                                "${result.compensationFailed} compensation failed, " +
                                "${result.inProgress} already in progress (${result.total} eligible item(s))."
                        workingId = null
                        reload()
                    }
                }) { Text("Check and undo batch") }
            },
            dismissButton = { TextButton(onClick = { pendingBatchUndo = null }) { Text("Cancel") } }
        )
    }

    resultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { resultMessage = null },
            title = { Text("Undo result") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { resultMessage = null }) { Text("OK") } }
        )
    }
}

private fun String.toDisplayName(): String = when (this) {
    "config_key" -> "Settings key changed"
    "config_item" -> "Required item changed"
    "protection" -> "Automatic protection changed"
    "persistent_notification" -> "Persistent notification changed"
    "app_locale" -> "App language changed"
    "ui_locale" -> "FCM Guard language changed"
    else -> this
}

private fun String.displayValue(): String = when (this) {
    "system" -> "System default"
    "(unavailable)" -> "Unavailable"
    else -> this
}
