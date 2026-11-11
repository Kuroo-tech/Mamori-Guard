package com.reed.fcmguard.feature.dashboard

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.reed.fcmguard.R
import com.reed.fcmguard.core.fcm.AutostartState
import com.reed.fcmguard.core.fcm.FcmApplication
import com.reed.fcmguard.core.fcm.FcmGuardController
import com.reed.fcmguard.core.fcm.FcmGuardPlatform
import com.reed.fcmguard.core.fcm.ThemeModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FcmGuardDashboard(
    context: Context,
    platform: FcmGuardPlatform,
    controller: FcmGuardController,
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    val appContext = context.applicationContext
    val initialStatus = remember(appContext) { platform.readStatus() }
    var protectionEnabled by remember(appContext) { mutableStateOf(initialStatus.protectionEnabled) }
    var persistentNotification by remember(appContext) { mutableStateOf(initialStatus.persistentNotification) }
    var keyValue by remember(appContext) { mutableStateOf(initialStatus.configuredKey) }
    var requiredItem by remember(appContext) { mutableStateOf(initialStatus.requiredItem) }
    var statusMessage by remember(appContext) { mutableStateOf("Ready") }
    var themeMode by remember(appContext) { mutableStateOf(platform.getThemeMode()) }
    var scanRequested by remember(appContext) { mutableStateOf(false) }
    var scanGeneration by remember(appContext) { mutableIntStateOf(0) }
    var detectedApps by remember(appContext) { mutableStateOf(emptyList<FcmApplication>()) }
    var autostartStatuses by remember(appContext) {
        mutableStateOf<Map<String, AutostartState>>(emptyMap())
    }
    var scanLoading by remember(appContext) { mutableStateOf(false) }
    var scanError by remember(appContext) { mutableStateOf<String?>(null) }
    var showLanguagePicker by remember(appContext) { mutableStateOf(false) }

    LaunchedEffect(scanGeneration) {
        if (scanGeneration > 0) {
            scanLoading = true
            scanError = null
            try {
                val scanResult = withContext(Dispatchers.IO) {
                    val apps = platform.scanFcmApplications()
                    apps to apps.associate { app ->
                        app.packageName to platform.readAutostartState(app.packageName)
                    }
                }
                detectedApps = scanResult.first
                autostartStatuses = scanResult.second
            } catch (error: Exception) {
                scanError = error.message ?: "Could not scan installed apps."
            } finally {
                scanLoading = false
            }
        }
    }

    LaunchedEffect(refreshKey) {
        val current = platform.readStatus()
        protectionEnabled = current.protectionEnabled
        persistentNotification = current.persistentNotification
        keyValue = current.configuredKey
        requiredItem = current.requiredItem
    }

    val currentStatus = platform.readStatus()
    val whitelistValue = currentStatus.whitelistValue.orEmpty()
    val hasRequiredItem = currentStatus.requiredItemPresent
    val canWrite = currentStatus.canWriteSettings

    val statusLabel = when {
        protectionEnabled && canWrite && hasRequiredItem -> "Protected"
        canWrite && hasRequiredItem -> "Ready"
        else -> "Attention"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "FCM Guard",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = { showLanguagePicker = true }) {
                Text("Language")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = statusLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = statusMessage.ifBlank { "System is watching the Xiaomi whitelist." })
                StatusRow(
                    label = "Modify system settings",
                    value = when {
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> "Not required"
                        canWrite -> "Granted"
                        else -> "Not granted"
                    }
                )
                StatusRow(label = "GMS present", value = if (hasRequiredItem) "Yes" else "No")
                StatusRow(label = "Automatic protection", value = if (protectionEnabled) "Enabled" else "Disabled")
                StatusRow(label = "Persistent notification", value = if (persistentNotification) "Enabled" else "Disabled")
                StatusRow(
                    label = "Notification access",
                    value = if (currentStatus.notificationAvailable) "Available" else "Needs attention"
                )
                StatusRow(label = "Mode", value = if (persistentNotification && protectionEnabled && currentStatus.notificationAvailable) "Foreground" else "Quiet")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Event-driven monitoring of only the FCM whitelist key. A non-waking 30-minute fallback check is used only as insurance.")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Automatic protection")
                    Switch(
                        checked = protectionEnabled,
                        onCheckedChange = { enabled ->
                            val result = controller.setProtectionEnabled(enabled)
                            protectionEnabled = platform.readStatus().protectionEnabled
                            statusMessage = result.message
                        }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Persistent notification")
                    Switch(
                        checked = persistentNotification,
                        onCheckedChange = { enabled ->
                            val result = controller.setPersistentNotification(enabled)
                            persistentNotification = platform.readStatus().persistentNotification
                            statusMessage = result.message
                        }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        val result = controller.repairAndReconnect()
                        statusMessage = result.message
                        protectionEnabled = platform.readStatus().protectionEnabled
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Repair now")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        statusMessage = if (platform.reconnectFcm()) {
                            "FCM reconnect broadcasts dispatched"
                        } else {
                            "FCM reconnect broadcasts could not be dispatched"
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Text("Wake")
                    }
                    Button(onClick = { platform.openFcmDiagnostics() }, modifier = Modifier.weight(1f)) {
                        Text("Diagnostics")
                    }
                }
                Button(onClick = { platform.openWriteSettings() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant modify system settings")
                }
                if (!platform.readStatus().notificationAvailable) {
                    Button(onClick = {
                        statusMessage = if (platform.openNotificationSettings()) {
                            "Opened notification settings"
                        } else {
                            "Notification settings could not be opened"
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open notification settings")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FCM apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Automatically detect apps that declare standard FCM/GCM receivers and check autostart status when HyperOS exposes it.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        scanRequested = true
                        scanGeneration++
                        statusMessage = "Scanning detected FCM apps..."
                    }, modifier = Modifier.weight(1f)) {
                        Text("Scan FCM apps")
                    }
                    Button(onClick = {
                        statusMessage = if (platform.openAutostartManager()) {
                            "Opened HyperOS auto-start manager"
                        } else {
                            "HyperOS auto-start manager is unavailable"
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Text("Auto-start")
                    }
                }
                if (scanRequested) {
                    if (scanLoading) {
                        Text("Scanning installed apps…")
                    } else if (scanError != null) {
                        Text(scanError!!, color = MaterialTheme.colorScheme.error)
                    } else if (detectedApps.isEmpty()) {
                        Text("No detected FCM apps")
                    } else {
                        val count = detectedApps.size
                        val enabled = autostartStatuses.values.count {
                            it == AutostartState.ENABLED
                        }
                        Text("Detected $count app(s); $enabled enabled")
                        detectedApps.forEach { app ->
                            val status = autostartStatuses[app.packageName]
                                ?: AutostartState.UNKNOWN
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .border(
                                        width = 1.dp,
                                        color = when (status) {
                                            AutostartState.ENABLED -> MaterialTheme.colorScheme.primary
                                            AutostartState.PARTIAL, AutostartState.DISABLED -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.outline
                                        }.copy(alpha = 0.5f)
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(status.name.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Advanced configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = keyValue,
                    onValueChange = { keyValue = it },
                    label = { Text("Settings key") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = requiredItem,
                    onValueChange = { requiredItem = it },
                    label = { Text("Required item") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val result = controller.saveConfiguration(keyValue, requiredItem)
                        statusMessage = result.message
                        keyValue = platform.readStatus().configuredKey
                        requiredItem = platform.readStatus().requiredItem
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save configuration")
                }

                if (showLanguagePicker) {
                    val languageCodes = listOf("system", "en", "zh-CN", "zh-TW", "fr", "ja", "ko", "es", "pt", "de", "ru")
                    val languageLabels = appContext.resources.getStringArray(R.array.language_entries)
                    AlertDialog(
                        onDismissRequest = { showLanguagePicker = false },
                        title = { Text("App language") },
                        text = {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                languageCodes.forEachIndexed { index, code ->
                                    TextButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            val result = controller.setOwnAppLanguage(
                                                code,
                                                appContext.packageName
                                            )
                                            statusMessage = result.message
                                            showLanguagePicker = false
                                            if (Build.VERSION.SDK_INT < 33) (context as? ComponentActivity)?.recreate()
                                        }
                                    ) {
                                        Text(
                                            text = languageLabels.getOrElse(index) { code },
                                            fontWeight = if (platform.getAppLanguage() == code) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showLanguagePicker = false }) { Text("Close") } }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Low-power design", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Low-power design: the service no longer polls every 30 seconds, no longer watches unrelated system settings, and only sends an FCM reconnect request after an actual whitelist repair.")
                Text("Recommended on HyperOS: keep Autostart enabled and battery mode set to No restrictions. Developer options, USB debugging and Wireless debugging are not required.")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(ThemeModes.SYSTEM, ThemeModes.LIGHT, ThemeModes.DARK).forEach { mode ->
                        val selected = themeMode == mode
                        val label = when (mode) {
                            ThemeModes.LIGHT -> "Light"
                            ThemeModes.DARK -> "Dark"
                            else -> "System"
                        }
                        Button(
                            onClick = {
                                platform.setThemeMode(mode)
                                themeMode = platform.getThemeMode()
                                (context as? ComponentActivity)?.recreate()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Whitelist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val packages = whitelistValue.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (packages.isEmpty()) {
                    Text("No current value")
                } else {
                    packages.forEach { pkg ->
                        val accent = when {
                            pkg == "com.google.android.gms" -> MaterialTheme.colorScheme.primary
                            pkg == appContext.packageName -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.tertiary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .border(width = 1.dp, color = accent.copy(alpha = 0.4f))
                                .background(accent.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pkg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
