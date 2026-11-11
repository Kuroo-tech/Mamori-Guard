package com.reed.fcmguard.platform.applocale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.net.Uri
import com.reed.fcmguard.core.locale.AppLocaleStatus
import com.reed.fcmguard.core.locale.PrivilegedLocaleBackend
import rikka.shizuku.Shizuku

class ShizukuLocaleBackend(context: Context) : PrivilegedLocaleBackend {
    private val appContext = context.applicationContext
    private var service: IAppLocalePrivilegedService? = null
    private var serviceDeathRecipient: IBinder.DeathRecipient? = null
    private var connection: ServiceConnection? = null
    private var onStateChanged: (() -> Unit)? = null
    private var binding = false
    private var listenersRegistered = false
    private var lastError: String? = null
    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { connect(onStateChanged ?: {}) }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        serviceDeathRecipient = null
        connection = null
        binding = false
        onStateChanged?.invoke()
    }

    override fun status(): AppLocaleStatus = try {
        when {
            Build.VERSION.SDK_INT < 33 -> AppLocaleStatus.UNSUPPORTED_ANDROID
            !Shizuku.pingBinder() -> AppLocaleStatus.SERVICE_UNAVAILABLE
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                AppLocaleStatus.PERMISSION_REQUIRED
            service != null -> AppLocaleStatus.READY
            binding -> AppLocaleStatus.CONNECTING
            lastError != null -> AppLocaleStatus.ERROR
            else -> AppLocaleStatus.SERVICE_UNAVAILABLE
        }
    } catch (error: Exception) {
        lastError = error.message ?: "Could not read Shizuku permission state"
        AppLocaleStatus.ERROR
    }

    override fun connect(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        if (Build.VERSION.SDK_INT < 33) {
            onStateChanged()
            return
        }
        if (!listenersRegistered) {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            listenersRegistered = true
        }
        try {
            if (!Shizuku.pingBinder() ||
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ||
                binding || service != null
            ) {
                onStateChanged()
                return
            }
        } catch (error: Exception) {
            lastError = error.message ?: "Could not inspect Shizuku service state"
            onStateChanged()
            return
        }
        binding = true
        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext, AppLocaleUserService::class.java)
        ).daemon(false).tag("fcm-guard-app-locales").version(1)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                binding = false
                if (binder == null) {
                    service = null
                    lastError = "Privileged service returned no Binder"
                } else {
                    val deathRecipient = IBinder.DeathRecipient {
                        service = null
                        serviceDeathRecipient = null
                        connection = null
                        binding = false
                        lastError = "Privileged locale service Binder died"
                        this@ShizukuLocaleBackend.onStateChanged?.invoke()
                    }
                    try {
                        binder.linkToDeath(deathRecipient, 0)
                        serviceDeathRecipient = deathRecipient
                        service = IAppLocalePrivilegedService.Stub.asInterface(binder)
                        lastError = null
                    } catch (error: Exception) {
                        service = null
                        serviceDeathRecipient = null
                        lastError = error.message ?: "Could not monitor the locale service Binder"
                    }
                }
                this@ShizukuLocaleBackend.onStateChanged?.invoke()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                binding = false
                this@ShizukuLocaleBackend.onStateChanged?.invoke()
            }

            override fun onBindingDied(name: ComponentName?) {
                service = null
                binding = false
                lastError = "Privileged service binding died"
                this@ShizukuLocaleBackend.onStateChanged?.invoke()
            }

            override fun onNullBinding(name: ComponentName?) {
                service = null
                binding = false
                lastError = "Privileged service returned a null binding"
                this@ShizukuLocaleBackend.onStateChanged?.invoke()
            }
        }
        connection = serviceConnection
        lastError = null
        try {
            Shizuku.bindUserService(args, serviceConnection)
        } catch (error: Exception) {
            binding = false
            lastError = error.message ?: "Could not bind the Shizuku user service"
            onStateChanged()
        }
    }

    override fun disconnect() {
        val currentConnection = connection
        try {
            if (currentConnection != null && Shizuku.pingBinder()) {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(appContext, AppLocaleUserService::class.java)
                ).daemon(false).tag("fcm-guard-app-locales").version(1)
                Shizuku.unbindUserService(args, currentConnection, true)
            }
        } catch (error: Exception) {
            lastError = error.message ?: "Could not stop the Shizuku user service"
        } finally {
            service?.asBinder()?.let { binder ->
                serviceDeathRecipient?.let { recipient ->
                    try {
                        binder.unlinkToDeath(recipient, 0)
                    } catch (error: Exception) {
                        lastError = error.message ?: "Could not detach the locale Binder listener"
                    }
                }
            }
            permissionListener?.let(Shizuku::removeRequestPermissionResultListener)
            permissionListener = null
            connection = null
            service = null
            serviceDeathRecipient = null
            binding = false
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            listenersRegistered = false
        }
    }

    override fun errorMessage(): String? = lastError

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        check(Build.VERSION.SDK_INT >= 33) { "Per-app locales require Android 13 or later" }
        check(Shizuku.pingBinder()) { "Shizuku/Sui service is not running" }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (permissionListener === this) permissionListener = null
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                onStateChanged?.invoke()
            }
        }
        permissionListener?.let(Shizuku::removeRequestPermissionResultListener)
        permissionListener = listener
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (error: Exception) {
            Shizuku.removeRequestPermissionResultListener(listener)
            permissionListener = null
            lastError = error.message ?: "Could not request Shizuku permission"
            throw error
        }
    }

    override fun openSetupGuide() {
        appContext.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://shizuku.rikka.app/guide/setup/")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun readLocales(packageName: String): String {
        validatePackageName(packageName)
        return requireService().getAppLocales(packageName, currentUserId())
    }

    override fun setLocales(packageName: String, languageTags: String) {
        validatePackageName(packageName)
        requireService().setAppLocales(packageName, currentUserId(), languageTags)
    }

    private fun currentUserId(): Int = Process.myUid() / USER_ID_RANGE

    private fun requireService(): IAppLocalePrivilegedService {
        check(status() == AppLocaleStatus.READY) {
            "Shizuku user service is not ready: ${status()}"
        }
        return checkNotNull(service) { "Shizuku user service disconnected" }
    }

    private fun validatePackageName(packageName: String) {
        require(PACKAGE_PATTERN.matches(packageName)) { "Invalid package name" }
    }

    companion object {
        const val REQUEST_CODE = 4107
        private const val USER_ID_RANGE = 100000
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    }
}
