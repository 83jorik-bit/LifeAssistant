package com.lifenavigator.app.data.local

import android.content.Context

/**
 * Настройки приложения. Пока здесь только радиус поиска, но это настоящая
 * рабочая настройка: она сохраняется между запусками и влияет на поиск мест.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var searchRadiusMeters: Float
        get() = prefs.getFloat(KEY_RADIUS, DEFAULT_RADIUS)
        set(value) = prefs.edit().putFloat(KEY_RADIUS, value).apply()

    companion object {
        const val DEFAULT_RADIUS = 1000f

        /** Варианты радиуса, которые предлагаются в настройках. */
        val RADIUS_OPTIONS = listOf(500f, 1000f, 2000f, 3000f)

        private const val KEY_RADIUS = "search_radius_meters"
    }
}
