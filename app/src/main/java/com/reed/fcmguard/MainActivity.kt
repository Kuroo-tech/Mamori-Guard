package com.reed.fcmguard

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.reed.fcmguard.core.fcm.FcmGuardController
import com.reed.fcmguard.core.locale.AppLocaleRepository
import com.reed.fcmguard.core.locale.AppLocaleStatus
import com.reed.fcmguard.core.locale.VerifiedAppLocaleRepository
import com.reed.fcmguard.core.history.HistoryChange
import com.reed.fcmguard.core.history.HistoryStore
import com.reed.fcmguard.core.history.HistoryUndoService
import com.reed.fcmguard.core.history.SettingsGuardHistoryGateway
import com.reed.fcmguard.feature.dashboard.FcmGuardDashboard
import com.reed.fcmguard.feature.applocale.AppLocaleScreen
import com.reed.fcmguard.feature.history.HistoryScreen
import com.reed.fcmguard.platform.applocale.JavaAppLocaleHistoryAdapter
import com.reed.fcmguard.platform.applocale.selectLocaleBackend
import com.reed.fcmguard.platform.applocale.ShizukuLocaleBackend
import com.reed.fcmguard.platform.applocale.UnsupportedAppLocaleBackend
import com.reed.fcmguard.platform.fcm.JavaFcmPlatformAdapter
import com.reed.fcmguard.ui.theme.GuardTheme

private enum class MainTab {
    DASHBOARD,
    APP_LOCALES,
    HISTORY
}

class MainActivity : ComponentActivity() {
    private val resumeEpoch = mutableIntStateOf(0)
    private lateinit var fcmPlatform: JavaFcmPlatformAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fcmPlatform = JavaFcmPlatformAdapter(this)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        fcmPlatform.migrateAppLanguagePreference()

        setContent {
            GuardTheme(mode = fcmPlatform.getThemeMode()) {
                val context = applicationContext
                val historyStore = remember { HistoryStore(context) }
                val localeRepository: AppLocaleRepository = remember {
                    val backend = selectLocaleBackend(
                        Build.VERSION.SDK_INT,
                        supported = { ShizukuLocaleBackend(context) },
                        unsupported = ::UnsupportedAppLocaleBackend
                    )
                    VerifiedAppLocaleRepository(backend)
                }
                var backendStatus by remember { mutableStateOf(localeRepository.status()) }
                val historyController = remember(historyStore, localeRepository) {
                    val guard = SettingsGuardHistoryGateway(fcmPlatform)
                    val locales = JavaAppLocaleHistoryAdapter(localeRepository, fcmPlatform)
                    HistoryUndoService(historyStore, guard, locales)
                }
                val guardController = remember(historyStore) {
                    FcmGuardController(fcmPlatform, historyStore)
                }
                var selectedTab by rememberSaveable { mutableStateOf(MainTab.DASHBOARD) }

                DisposableEffect(historyStore, localeRepository) {
                    localeRepository.connect { backendStatus = localeRepository.status() }
                    onDispose {
                        localeRepository.disconnect()
                        historyStore.close()
                    }
                }

                LaunchedEffect(resumeEpoch.intValue) {
                    backendStatus = localeRepository.status()
                    localeRepository.connect { backendStatus = localeRepository.status() }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == MainTab.DASHBOARD,
                                onClick = { selectedTab = MainTab.DASHBOARD },
                                icon = { Text("D") },
                                label = { Text("Dashboard") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == MainTab.APP_LOCALES,
                                onClick = { selectedTab = MainTab.APP_LOCALES },
                                icon = { Text("A") },
                                label = { Text("App languages") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == MainTab.HISTORY,
                                onClick = { selectedTab = MainTab.HISTORY },
                                icon = { Text("H") },
                                label = { Text("History") }
                            )
                        }
                    }
                ) { contentPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
                        when (selectedTab) {
                            MainTab.DASHBOARD -> FcmGuardDashboard(
                                context = this@MainActivity,
                                platform = fcmPlatform,
                                controller = guardController,
                                refreshKey = resumeEpoch.intValue
                            )
                            MainTab.APP_LOCALES -> AppLocaleScreen(
                                repository = localeRepository,
                                backendStatus = backendStatus,
                                history = historyStore,
                                onConnect = {
                                    localeRepository.connect { backendStatus = localeRepository.status() }
                                    backendStatus = localeRepository.status()
                                },
                                onStatusRefresh = { backendStatus = localeRepository.status() }
                            )
                            MainTab.HISTORY -> HistoryScreen(
                                historyStore,
                                historyController
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeEpoch.intValue++
        fcmPlatform.startGuardService()
        recordExternalAppLocaleChange()
    }

    private fun recordExternalAppLocaleChange() {
        val current = fcmPlatform.getAppLanguage()
        val previous = fcmPlatform.getTrackedAppLanguage()
        if (previous == null) {
            fcmPlatform.setTrackedAppLanguage(current)
            return
        }
        if (previous == current) return

        val history = HistoryStore(this)
        try {
            history.record(
                HistoryChange(
                    feature = "settings",
                    operation = HistoryUndoService.OP_UI_LOCALE,
                    target = packageName,
                    previousValue = previous,
                    newValue = current
                )
            )
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Could not save locale change to history: ${error.message ?: "storage error"}",
                Toast.LENGTH_LONG
            ).show()
            return
        } finally {
            history.close()
        }
        fcmPlatform.setTrackedAppLanguage(current)
    }
}
