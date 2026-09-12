package com.lifenavigator.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF2E7D6B)      // тёмно-бирюзовый — "навигация/маршруты"
private val PrimaryDark = Color(0xFF56C7A9)
private val Secondary = Color(0xFFEF8354)    // акцент для предупреждений о лимитах
private val Background = Color(0xFFF7F8F6)
private val Surface = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    background = Background,
    surface = Surface
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    secondary = Secondary
)

@Composable
fun LifeNavigatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content
    )
}
