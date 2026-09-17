package com.lifenavigator.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Оформление приложения. SYSTEM — как в системе. */
enum class ThemeMode(val title: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная")
}

/**
 * Настройки приложения. Пока здесь только радиус поиска, но это настоящая
 * рабочая настройка: она сохраняется между запусками и влияет на поиск мест.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var searchRadiusMeters: Float
        get() = prefs.getFloat(KEY_RADIUS, DEFAULT_RADIUS)
        set(value) = prefs.edit().putFloat(KEY_RADIUS, value).apply()

    companion object {
        const val DEFAULT_RADIUS = 1000f

        /** Варианты радиуса, которые предлагаются в настройках. */
        val RADIUS_OPTIONS = listOf(500f, 1000f, 2000f, 3000f)

        private const val KEY_RADIUS = "search_radius_meters"
        private const val KEY_THEME = "theme_mode"
    }
}

/**
 * Текущая тема, доступная всему приложению.
 *
 * Тема нужна и в настройках, и в корне экрана (MainActivity), поэтому держим её
 * в одном месте: настройки пишут сюда, корень подписывается и перерисовывается.
 */
object ThemeController {
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode

    fun load(context: Context) {
        _mode.value = AppSettings(context).themeMode
    }

    fun set(context: Context, value: ThemeMode) {
        AppSettings(context).themeMode = value
        _mode.value = value
    }
}
