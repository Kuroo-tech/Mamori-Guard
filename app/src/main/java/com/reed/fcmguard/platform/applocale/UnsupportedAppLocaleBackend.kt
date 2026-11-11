package com.reed.fcmguard.platform.applocale

import com.reed.fcmguard.core.locale.AppLocaleStatus
import com.reed.fcmguard.core.locale.PrivilegedLocaleBackend

class UnsupportedAppLocaleBackend : PrivilegedLocaleBackend {
    override fun status() = AppLocaleStatus.UNSUPPORTED_ANDROID
    override fun connect(onStateChanged: () -> Unit) = onStateChanged()
    override fun disconnect() = Unit
    override fun requestPermission(onResult: (Boolean) -> Unit) {
        throw UnsupportedOperationException("Per-app locales require Android 13 or later")
    }

    override fun openSetupGuide() = Unit
    override fun errorMessage(): String? = null
    override fun readLocales(packageName: String): String =
        throw UnsupportedOperationException("Per-app locales require Android 13 or later")

    override fun setLocales(packageName: String, languageTags: String) {
        throw UnsupportedOperationException("Per-app locales require Android 13 or later")
    }
}
