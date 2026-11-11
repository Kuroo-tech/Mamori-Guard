package com.reed.fcmguard.platform.applocale;

interface IAppLocalePrivilegedService {
    String getAppLocales(String packageName, int userId) = 1;
    void setAppLocales(String packageName, int userId, String languageTags) = 2;
    void destroy() = 16777114;
}
