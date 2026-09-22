package com.lifenavigator.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Токен сессии собственного сервера (см. `backend/`), отдельный от локального
 * профиля: профиль есть всегда после входа через Яндекс/VK, а сессия на сервере —
 * только если сервер настроен (BACKEND_BASE_URL) и обмен токена прошёл успешно.
 * Без сессии приложение просто работает локально, как и раньше.
 */
object SessionStore {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    fun load(context: Context) {
        _token.value = prefs(context).getString(KEY_TOKEN, null)
    }

    fun save(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        _token.value = token
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        _token.value = null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private const val KEY_TOKEN = "backend_session_token"
}
