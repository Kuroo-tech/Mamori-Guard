package com.reed.fcmguard.platform.applocale

import com.reed.fcmguard.core.locale.PrivilegedLocaleBackend

internal fun selectLocaleBackend(
    apiLevel: Int,
    supported: () -> PrivilegedLocaleBackend,
    unsupported: () -> PrivilegedLocaleBackend
): PrivilegedLocaleBackend =
    if (apiLevel >= 33) supported() else unsupported()
