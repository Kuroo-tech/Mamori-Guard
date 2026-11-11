package com.reed.fcmguard.platform.fcm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reed.fcmguard.AutostartStatusReader
import com.reed.fcmguard.SettingsGuard
import com.reed.fcmguard.core.fcm.AutostartState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JavaFcmPlatformAdapterTest {
    @Test
    fun readsLegacySettingsAndMapsUnknownAutostartState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = JavaFcmPlatformAdapter(context)
        val status = platform.readStatus()

        assertEquals(SettingsGuard.getConfiguredKey(context), status.configuredKey)
        assertEquals(
            SettingsGuard.getConfiguredRequiredItem(context),
            status.requiredItem
        )
        assertEquals(SettingsGuard.read(context), status.whitelistValue)
        assertEquals(SettingsGuard.isProtectionEnabled(context), status.protectionEnabled)
        assertEquals(
            SettingsGuard.usePersistentNotification(context),
            status.persistentNotification
        )
        val expectedAutostart = when (
            AutostartStatusReader.check(context, context.packageName)
        ) {
            AutostartStatusReader.Status.ENABLED -> AutostartState.ENABLED
            AutostartStatusReader.Status.PARTIAL -> AutostartState.PARTIAL
            AutostartStatusReader.Status.DISABLED -> AutostartState.DISABLED
            AutostartStatusReader.Status.UNKNOWN -> AutostartState.UNKNOWN
        }
        assertEquals(expectedAutostart, platform.readAutostartState(context.packageName))
    }
}
