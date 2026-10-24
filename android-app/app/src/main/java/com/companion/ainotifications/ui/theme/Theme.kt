package com.companion.ainotifications.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFF81C995),
    tertiary = Color(0xFFFDD663),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color(0xFF003366),
    onSecondary = Color(0xFF003314),
    onBackground = Color(0xFFE3E3E3),
    onSurface = Color(0xFFE3E3E3)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    secondary = Color(0xFF1E8E3E),
    tertiary = Color(0xFFF9AB00),
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF202124),
    onSurface = Color(0xFF202124)
)

@Composable
fun AiNotificationsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
