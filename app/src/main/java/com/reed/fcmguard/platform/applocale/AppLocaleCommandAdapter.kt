package com.reed.fcmguard.platform.applocale

internal fun interface LocaleCommandExecutor {
    fun execute(command: Array<String>): String
}

internal class AppLocaleCommandAdapter(
    private val executor: LocaleCommandExecutor
) {
    fun getAppLocales(packageName: String, userId: Int): String {
        validatePackageName(packageName)
        require(userId >= 0) { "Invalid Android user id" }
        val output = executor.execute(
            arrayOf("cmd", "locale", "get-app-locales", packageName, "--user", userId.toString())
        )
        return CmdLocaleOutputParser.parse(packageName, output)
    }

    fun setAppLocales(packageName: String, userId: Int, languageTags: String) {
        validatePackageName(packageName)
        require(userId >= 0) { "Invalid Android user id" }
        val normalized = normalizeLanguageTags(languageTags)
        executor.execute(
            arrayOf(
                "cmd",
                "locale",
                "set-app-locales",
                packageName,
                "--user",
                userId.toString(),
                "--locales",
                if (normalized == SYSTEM_DEFAULT) "" else normalized
            )
        )
    }

    private fun validatePackageName(packageName: String) {
        require(PACKAGE_PATTERN.matches(packageName)) { "Invalid package name" }
    }

    private fun normalizeLanguageTags(languageTags: String): String {
        if (languageTags == SYSTEM_DEFAULT) return SYSTEM_DEFAULT
        val tags = languageTags.split(',').map(String::trim).filter(String::isNotEmpty)
        require(tags.isNotEmpty() && tags.all(LANGUAGE_TAG_PATTERN::matches)) {
            "Invalid locale tag"
        }
        return tags.joinToString(",")
    }

    companion object {
        private const val SYSTEM_DEFAULT = "system"
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private val LANGUAGE_TAG_PATTERN = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")
    }
}

internal object CmdLocaleOutputParser {
    fun parse(packageName: String, output: String): String {
        val pattern = Regex(
            "Locales for ${Regex.escape(packageName)} are \\[(.*)]",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = pattern.matchEntire(output.trim())
            ?: throw IllegalStateException("Android returned an unrecognized app-locale response")
        return match.groupValues[1].trim().ifEmpty { "system" }
    }
}
