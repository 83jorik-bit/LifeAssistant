package com.lifenavigator.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Профиль пользователя, хранящийся только на устройстве. */
data class UserProfile(
    val name: String,
    val email: String?
)

/**
 * Локальный профиль — единственный способ «входа», который работает без сервера.
 *
 * Никакой проверки личности здесь нет и быть не может: данные лежат на устройстве
 * и никуда не отправляются. Это осознанный выбор, а не упрощение — настоящая
 * регистрация требует серверной части, см. README.
 */
object LocalProfileStore {

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile

    fun load(context: Context) {
        val prefs = prefs(context)
        val name = prefs.getString(KEY_NAME, null)
        _profile.value = name?.let {
            UserProfile(name = it, email = prefs.getString(KEY_EMAIL, null))
        }
    }

    fun save(context: Context, name: String, email: String?) {
        prefs(context).edit()
            .putString(KEY_NAME, name)
            .putString(KEY_EMAIL, email?.takeIf { it.isNotBlank() })
            .apply()
        _profile.value = UserProfile(name, email?.takeIf { it.isNotBlank() })
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        _profile.value = null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"
}
