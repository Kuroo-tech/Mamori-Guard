package com.reed.fcmguard.platform.applocale

import com.reed.fcmguard.core.locale.AppLocaleStatus
import com.reed.fcmguard.core.locale.PrivilegedLocaleBackend
import org.junit.Assert.assertSame
import org.junit.Test

class LocaleBackendSelectorTest {
    @Test
    fun usesShizukuBackendAtApiThirtyThreeAndNewer() {
        val supported = FakeBackend()

        assertSame(
            supported,
            selectLocaleBackend(33, { supported }, { error("unsupported backend selected") })
        )
    }

    @Test
    fun selectsUnsupportedBackendBelowApiThirtyThree() {
        val unsupported = FakeBackend()

        assertSame(
            unsupported,
            selectLocaleBackend(32, { error("Shizuku backend selected too early") }, { unsupported })
        )
    }

    private class FakeBackend : PrivilegedLocaleBackend {
        override fun status() = AppLocaleStatus.READY
        override fun connect(onStateChanged: () -> Unit) = Unit
        override fun disconnect() = Unit
        override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun openSetupGuide() = Unit
        override fun errorMessage(): String? = null
        override fun readLocales(packageName: String) = "system"
        override fun setLocales(packageName: String, languageTags: String) = Unit
    }
}
