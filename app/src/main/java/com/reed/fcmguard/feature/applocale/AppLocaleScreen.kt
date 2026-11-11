package com.reed.fcmguard.feature.applocale

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reed.fcmguard.core.history.HistoryRecorder
import com.reed.fcmguard.core.locale.AppLocaleRepository
import com.reed.fcmguard.core.locale.AppLocaleStatus
import com.reed.fcmguard.core.locale.LocaleApp
import com.reed.fcmguard.core.locale.SYSTEM_DEFAULT
import com.reed.fcmguard.ui.components.GuardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private data class LocaleRow(val app: LocaleApp, val locale: String)

@Composable
fun AppLocaleScreen(
    repository: AppLocaleRepository,
    backendStatus: AppLocaleStatus,
    history: HistoryRecorder,
    onConnect: () -> Unit,
    onStatusRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = backendStatus
    var apps by remember { mutableStateOf<List<LocaleApp>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }
    var failedApps by remember { mutableStateOf<List<LocaleApp>>(emptyList()) }
    var retryLocales by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var localeValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var localeErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var search by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var pendingLocale by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val cancelRequested = remember { AtomicBoolean(false) }

    fun refreshLocales() {
        refreshToken++
    }

    LaunchedEffect(context) {
        appsLoading = true
        try {
            apps = withContext(Dispatchers.IO) { loadEligibleApps(context) }
            loadError = null
        } catch (error: Exception) {
            loadError = error.message ?: "Could not load installed applications."
        } finally {
            appsLoading = false
        }
    }

    LaunchedEffect(status, refreshToken, apps) {
        if (status != AppLocaleStatus.READY || apps.isEmpty()) return@LaunchedEffect
        val reads = withContext(Dispatchers.IO) {
            apps.map { app ->
                try {
                    Triple(app.packageName, repository.readLocales(app.packageName), null)
                } catch (error: Exception) {
                    Triple(app.packageName, "Unavailable", error.message ?: "Locale could not be read")
                }
            }
        }
        localeValues = reads.associate { it.first to it.second }
        localeErrors = reads.mapNotNull { (packageName, _, error) ->
            error?.let { packageName to it }
        }.toMap()
    }

    val visibleRows = apps.asSequence()
        .filter { it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
        .map { LocaleRow(it, localeValues[it.packageName] ?: "Not read") }
        .toList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("App languages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Set a locale for other apps using the configured privileged locale service. The requested language only appears if that app includes matching resources.")
            }
        }
        item {
            GuardCard(title = "Privileged locale service") {
                Text(
                    when (status) {
                        AppLocaleStatus.UNSUPPORTED_ANDROID -> "Per-app locales require Android 13 or later."
                        AppLocaleStatus.SERVICE_UNAVAILABLE -> "The privileged locale service is unavailable."
                        AppLocaleStatus.PERMISSION_REQUIRED -> "Permission is required for per-app locale changes."
                        AppLocaleStatus.CONNECTING -> "Connecting to the isolated privileged service…"
                        AppLocaleStatus.ERROR -> repository.errorMessage() ?: "The privileged service could not be started."
                        AppLocaleStatus.READY -> "Connected. Locale changes are verified by reading the value back."
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status == AppLocaleStatus.PERMISSION_REQUIRED) {
                        Button(onClick = {
                            try {
                                repository.requestPermission { granted ->
                                    onStatusRefresh()
                                    if (granted) onConnect()
                                    else resultMessage = "Permission was denied. No locale changes were made."
                                }
                            } catch (error: Exception) {
                                resultMessage = error.message ?: "Could not request permission."
                            }
                        }) { Text("Grant permission") }
                    }
                    if (status == AppLocaleStatus.SERVICE_UNAVAILABLE ||
                        status == AppLocaleStatus.ERROR
                    ) {
                        Button(onClick = { repository.openSetupGuide() }) { Text("Service setup") }
                    }
                    TextButton(onClick = {
                        onConnect()
                        onStatusRefresh()
                    }) { Text("Refresh status") }
                }
            }
        }
        if (status == AppLocaleStatus.READY) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search installed apps") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { selected = apps.map(LocaleApp::packageName).toSet() }) {
                        Text("Select all eligible (${apps.size})")
                    }
                    TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = selected.isNotEmpty() && !working,
                        onClick = { pendingLocale = "vi-VN" }
                    ) { Text("Set Vietnamese") }
                    Button(
                        enabled = selected.isNotEmpty() && !working,
                        onClick = { pendingLocale = SYSTEM_DEFAULT }
                    ) { Text("Reset to System") }
                }
            }
            if (appsLoading) {
                item { CircularProgressIndicator() }
            } else if (apps.isEmpty()) {
                item { Text("No eligible user apps with a launcher entry were found.") }
            }
            items(visibleRows, key = { it.app.packageName }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = row.app.packageName in selected,
                            enabled = !working,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + row.app.packageName
                                else selected - row.app.packageName
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.app.label, fontWeight = FontWeight.SemiBold)
                            Text(row.app.packageName, style = MaterialTheme.typography.bodySmall)
                            Text("Locale: ${row.locale}", style = MaterialTheme.typography.bodyMedium)
                            localeErrors[row.app.packageName]?.let { error ->
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
        if (loadError != null) {
            item { Text(loadError!!, color = MaterialTheme.colorScheme.error) }
        }
        if (working) {
            item {
                GuardCard(title = "Applying locales") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("$completed of $total completed")
                        TextButton(onClick = { cancelRequested.set(true) }) { Text("Cancel after current app") }
                    }
                }
            }
        }
        if (failedApps.isNotEmpty() && status == AppLocaleStatus.READY) {
            item {
                GuardCard(title = "Retry failed apps") {
                    failedApps.forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(app.label, modifier = Modifier.weight(1f))
                            TextButton(
                                enabled = !working,
                                onClick = {
                                    working = true
                                    total = 1
                                    completed = 0
                                    scope.launch {
                                        val retryResult = withContext(Dispatchers.IO) {
                                            LocaleBatchApplier(repository, history).retry(
                                                app,
                                                retryLocales[app.packageName] ?: "vi-VN"
                                            )
                                        }
                                        if (retryResult.succeeded) {
                                            failedApps = failedApps - app
                                            resultMessage = "${app.label}: locale applied and verified."
                                        } else {
                                            resultMessage = "${app.label}: ${retryResult.error}"
                                        }
                                        working = false
                                        refreshLocales()
                                    }
                                }
                            ) { Text("Retry") }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Only launcher-visible non-system apps are included by default. Batch preview shows the selected count before any change.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    pendingLocale?.let { requested ->
        val targets = apps.filter { it.packageName in selected }
        AlertDialog(
            onDismissRequest = { pendingLocale = null },
            title = { Text("Preview locale change") },
            text = {
                Text(
                    "Apply ${if (requested == SYSTEM_DEFAULT) "System default" else requested} to " +
                        "${targets.size} selected user app(s)? Each result is read back and verified. " +
                        "A failed app does not stop the remaining batch."
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingLocale = null
                    cancelRequested.set(false)
                    completed = 0
                    total = targets.size
                    working = true
                    scope.launch {
                        val batchResult = withContext(Dispatchers.IO) {
                            LocaleBatchApplier(repository, history).apply(
                                apps = targets,
                                languageTags = requested,
                                isCancelled = cancelRequested::get,
                                onProgress = { done, count ->
                                    completed = done
                                    total = count
                                }
                            )
                        }
                        val successCount = batchResult.results.count(LocaleAppResult::succeeded)
                        val failures = batchResult.results.filterNot(LocaleAppResult::succeeded)
                        val warnings = batchResult.results.mapNotNull(LocaleAppResult::warning)
                        failedApps = failures.mapNotNull { failure ->
                            targets.firstOrNull { it.packageName == failure.packageName }?.also {
                                retryLocales = retryLocales + (it.packageName to requested)
                            }
                        }
                        resultMessage = buildString {
                            append("$successCount of ${batchResult.results.size} app(s) succeeded.")
                            if (batchResult.cancelled) append(" Cancellation took effect after the current app.")
                            if (failures.isNotEmpty()) {
                                append("\nFailed: ")
                                append(failures.joinToString { it.packageName })
                            }
                            if (warnings.isNotEmpty()) {
                                append("\nWarnings: ")
                                append(warnings.joinToString())
                            }
                        }
                        working = false
                        refreshLocales()
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { pendingLocale = null }) { Text("Cancel") } }
        )
    }

    resultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { resultMessage = null },
            title = { Text("Locale operation result") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { resultMessage = null }) { Text("OK") } }
        )
    }
}

private fun loadEligibleApps(context: Context): List<LocaleApp> {
    val packageManager = context.packageManager
    return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        .asSequence()
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
        .map { info ->
            val label = packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName }
            LocaleApp(info.packageName, label, isSystemApp = false)
        }
        .sortedWith(
            compareBy<LocaleApp, String>(String.CASE_INSENSITIVE_ORDER) { app -> app.label }
                .thenBy { app -> app.packageName }
        )
        .toList()
}
