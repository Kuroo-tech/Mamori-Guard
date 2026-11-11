package com.reed.fcmguard.platform.applocale

import android.os.Process
import android.os.Build
import android.annotation.TargetApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

class AppLocaleUserService : IAppLocalePrivilegedService.Stub() {
    private val localeCommands = AppLocaleCommandAdapter(LocaleCommandExecutor(::execute))

    override fun getAppLocales(packageName: String, userId: Int): String {
        requireSupportedVersion()
        return localeCommands.getAppLocales(packageName, userId)
    }

    override fun setAppLocales(packageName: String, userId: Int, languageTags: String) {
        requireSupportedVersion()
        localeCommands.setAppLocales(packageName, userId, languageTags)
    }

    override fun destroy() {
        Process.killProcess(Process.myPid())
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun execute(command: Array<String>): String {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Per-app locale management requires Android 13 or later"
        }
        val process = ProcessBuilder(command.asList())
            .redirectErrorStream(true)
            .start()
        val output = ByteArrayOutputStream()
        val outputTooLarge = AtomicBoolean(false)
        val readerFailure = AtomicReference<IOException?>()
        val reader = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = MAX_OUTPUT_BYTES - output.size()
                            if (remaining > 0) {
                                output.write(buffer, 0, minOf(count, remaining))
                            }
                            if (count > remaining) outputTooLarge.set(true)
                        }
                    }
                }
            } catch (error: IOException) {
                readerFailure.set(error)
            }
        }.apply {
            name = "app-locale-command-output"
            isDaemon = true
            start()
        }
        try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                throw IllegalStateException("Locale command timed out")
            }
            reader.join(2_000L)
            check(!reader.isAlive) { "Could not finish reading locale command output" }
            readerFailure.get()?.let {
                throw IllegalStateException("Could not read locale command output", it)
            }
            check(!outputTooLarge.get()) { "Locale command output exceeded the supported size" }
            val text = synchronized(output) {
                String(output.toByteArray(), StandardCharsets.UTF_8).trim()
            }
            check(process.exitValue() == 0) {
                text.ifEmpty { "Locale command failed" }
            }
            return text
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IllegalStateException("Locale command was interrupted", error)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            try {
                process.inputStream.close()
            } catch (_: IOException) {
                Unit
            }
            if (reader.isAlive) reader.interrupt()
        }
    }

    private fun requireSupportedVersion() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Per-app locale management requires Android 13 or later"
        }
    }

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 12L
        private const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
