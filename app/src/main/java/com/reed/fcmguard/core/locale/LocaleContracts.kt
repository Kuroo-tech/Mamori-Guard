package com.reed.fcmguard.core.locale

import com.reed.fcmguard.core.operations.TargetOperationLock
import com.reed.fcmguard.core.operations.TargetOperationLocks

const val SYSTEM_DEFAULT = "system"

enum class AppLocaleStatus {
    UNSUPPORTED_ANDROID,
    SERVICE_UNAVAILABLE,
    PERMISSION_REQUIRED,
    CONNECTING,
    ERROR,
    READY
}

data class LocaleApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean
)

interface PrivilegedLocaleBackend {
    fun status(): AppLocaleStatus
    fun connect(onStateChanged: () -> Unit)
    fun disconnect()
    fun requestPermission(onResult: (Boolean) -> Unit)
    fun openSetupGuide()
    fun errorMessage(): String?
    fun readLocales(packageName: String): String
    fun setLocales(packageName: String, languageTags: String)
}

interface AppLocaleRepository : PrivilegedLocaleBackend {
    fun setLocalesAndVerify(packageName: String, languageTags: String): String
}

class VerifiedAppLocaleRepository(
    private val backend: PrivilegedLocaleBackend,
    private val operationLocks: TargetOperationLock = TargetOperationLocks
) : AppLocaleRepository {
    override fun status() = backend.status()
    override fun connect(onStateChanged: () -> Unit) = backend.connect(onStateChanged)
    override fun disconnect() = backend.disconnect()
    override fun requestPermission(onResult: (Boolean) -> Unit) =
        backend.requestPermission(onResult)
    override fun openSetupGuide() = backend.openSetupGuide()
    override fun errorMessage() = backend.errorMessage()
    override fun readLocales(packageName: String) = backend.readLocales(packageName)
    override fun setLocales(packageName: String, languageTags: String) {
        operationLocks.withLock("app-locale:$packageName") {
            backend.setLocales(packageName, languageTags)
        }
    }

    override fun setLocalesAndVerify(packageName: String, languageTags: String): String =
        operationLocks.withLock("app-locale:$packageName") {
            backend.setLocales(packageName, languageTags)
            val actual = backend.readLocales(packageName)
            check(actual == languageTags) {
                "Locale read-back mismatch: requested $languageTags, received $actual"
            }
            actual
        }
}
