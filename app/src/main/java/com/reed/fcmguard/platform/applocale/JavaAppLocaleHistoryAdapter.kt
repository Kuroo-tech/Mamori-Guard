package com.reed.fcmguard.platform.applocale

import com.reed.fcmguard.core.history.LocaleHistoryGateway
import com.reed.fcmguard.core.locale.AppLocaleRepository
import com.reed.fcmguard.core.fcm.FcmGuardPlatform

class JavaAppLocaleHistoryAdapter(
    private val localeRepository: AppLocaleRepository,
    private val appSettings: FcmGuardPlatform
) : LocaleHistoryGateway {
    override fun getLocales(packageName: String): String =
        localeRepository.readLocales(packageName)

    override fun setLocales(packageName: String, languageTags: String): String =
        localeRepository.setLocalesAndVerify(packageName, languageTags)

    override fun getOwnAppLocale(): String = appSettings.getAppLanguage()

    override fun setOwnAppLocale(languageTags: String): String {
        appSettings.setAppLanguage(languageTags)
        return appSettings.getAppLanguage().also {
            check(it == languageTags) {
                "App locale read-back did not match the requested value"
            }
        }
    }
}
