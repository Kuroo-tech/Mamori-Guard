package com.reed.fcmguard.platform.applocale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLocaleCommandAdapterTest {
    @Test
    fun parsesLocalesForTheRequestedPackage() {
        val adapter = AppLocaleCommandAdapter {
            "Locales for com.example.app are [vi-VN,en-US]"
        }

        assertEquals("vi-VN,en-US", adapter.getAppLocales("com.example.app", 0))
    }

    @Test
    fun emptyLocaleListMeansSystemDefault() {
        val adapter = AppLocaleCommandAdapter { "Locales for com.example.app are []" }

        assertEquals("system", adapter.getAppLocales("com.example.app", 0))
    }

    @Test
    fun unsupportedOutputFormatIsNotTreatedAsSystemDefault() {
        val adapter = AppLocaleCommandAdapter { "success" }

        val error = runCatching { adapter.getAppLocales("com.example.app", 0) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun shellFailureIsPropagatedAndDoesNotReportSuccess() {
        val adapter = AppLocaleCommandAdapter {
            throw IllegalStateException("permission denied")
        }

        val error = runCatching {
            adapter.setAppLocales("com.example.app", 0, "vi-VN")
        }.exceptionOrNull()
        assertEquals("permission denied", error?.message)
    }

    @Test
    fun packageAndLocaleArgumentsAreValidatedBeforeExecution() {
        var executed = false
        val adapter = AppLocaleCommandAdapter {
            executed = true
            ""
        }

        assertTrue(runCatching {
            adapter.setAppLocales("com.example.app;id", 0, "vi-VN")
        }.isFailure)
        assertTrue(runCatching {
            adapter.setAppLocales("com.example.app", 0, "vi-VN;id")
        }.isFailure)
        assertEquals(false, executed)
    }
}
