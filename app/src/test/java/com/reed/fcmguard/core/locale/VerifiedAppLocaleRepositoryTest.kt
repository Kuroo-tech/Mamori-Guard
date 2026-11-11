package com.reed.fcmguard.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedAppLocaleRepositoryTest {
    @Test
    fun backendAvailabilityAndPermissionStateAreExposedWithoutFallback() {
        val backend = FakeBackend(AppLocaleStatus.PERMISSION_REQUIRED)
        val repository = VerifiedAppLocaleRepository(backend)

        assertEquals(AppLocaleStatus.PERMISSION_REQUIRED, repository.status())
        backend.currentStatus = AppLocaleStatus.SERVICE_UNAVAILABLE
        assertEquals(AppLocaleStatus.SERVICE_UNAVAILABLE, repository.status())
    }

    @Test
    fun permissionRevocationIsReportedByCurrentBackendState() {
        val backend = FakeBackend(AppLocaleStatus.READY)
        val repository = VerifiedAppLocaleRepository(backend)
        backend.currentStatus = AppLocaleStatus.PERMISSION_REQUIRED

        assertEquals(AppLocaleStatus.PERMISSION_REQUIRED, repository.status())
    }

    @Test
    fun setMustReadBackTheRequestedValue() {
        val backend = FakeBackend(AppLocaleStatus.READY).apply { ignoreWrites = true }
        val repository = VerifiedAppLocaleRepository(backend)

        val error = runCatching {
            repository.setLocalesAndVerify("com.example.app", "vi-VN")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("system", backend.readLocales("com.example.app"))
    }

    @Test
    fun successfulWriteIsVerifiedByASeparateRead() {
        val backend = FakeBackend(AppLocaleStatus.READY)
        val repository = VerifiedAppLocaleRepository(backend)

        assertEquals("vi-VN", repository.setLocalesAndVerify("com.example.app", "vi-VN"))
        assertEquals(1, backend.readCount)
    }

    private class FakeBackend(var currentStatus: AppLocaleStatus) : PrivilegedLocaleBackend {
        private val values = mutableMapOf<String, String>()
        var ignoreWrites = false
        var readCount = 0

        override fun status() = currentStatus
        override fun connect(onStateChanged: () -> Unit) = onStateChanged()
        override fun disconnect() = Unit
        override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(false)
        override fun openSetupGuide() = Unit
        override fun errorMessage(): String? = null
        override fun readLocales(packageName: String): String {
            readCount++
            return values[packageName] ?: SYSTEM_DEFAULT
        }
        override fun setLocales(packageName: String, languageTags: String) {
            if (!ignoreWrites) values[packageName] = languageTags
        }
    }
}
