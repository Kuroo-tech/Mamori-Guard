package com.reed.fcmguard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.reed.fcmguard.core.fcm.ThemeModes

object GuardSpacing {
    val xSmall = 4
    val small = 8
    val medium = 12
    val large = 16
    val xLarge = 24
}

private val GuardLightColors = lightColorScheme(
    primary = Color(0xFF315F9B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E5FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF555F71),
    tertiary = Color(0xFF725573),
    surface = Color(0xFFFAF9FF),
    background = Color(0xFFF7F7FC),
    error = Color(0xFFBA1A1A)
)

private val GuardDarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF16477D),
    onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFFBBC7DC),
    tertiary = Color(0xFFE2BCE1),
    surface = Color(0xFF111318),
    background = Color(0xFF111318),
    error = Color(0xFFFFB4AB)
)

@Composable
fun GuardTheme(
    mode: String = ThemeModes.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeModes.DARK -> true
        ThemeModes.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> GuardDarkColors
        else -> GuardLightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = Shapes(),
        content = content
    )
}
