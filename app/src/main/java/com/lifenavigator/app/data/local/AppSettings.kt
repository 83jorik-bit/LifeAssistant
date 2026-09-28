package com.lifenavigator.app.data.local

import android.content.Context
import com.lifenavigator.app.data.model.NeedCategory
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

    /**
     * Какие категории реально искать через Яндекс. По умолчанию — все: список
     * категорий из перечисления, а не жёстко записанные имена, чтобы новая
     * категория автоматически попадала в поиск, пока пользователь её не выключит.
     *
     * Каждая категория — это несколько поисковых запросов (см. YandexSearchRepository),
     * поэтому выбор категорий — не просто фильтр отображения, а реальная экономия
     * сетевых запросов и бесплатного лимита MapKit.
     */
    var searchCategories: Set<NeedCategory>
        get() {
            val saved = prefs.getStringSet(KEY_SEARCH_CATEGORIES, null) ?: return NeedCategory.entries.toSet()
            return saved.mapNotNull { name -> runCatching { NeedCategory.valueOf(name) }.getOrNull() }.toSet()
        }
        set(value) = prefs.edit()
            .putStringSet(KEY_SEARCH_CATEGORIES, value.map { it.name }.toSet())
            .apply()

    /**
     * Записывать траты автоматически из банковских уведомлений.
     * Выключено по умолчанию: чтение уведомлений — чувствительная возможность,
     * включать её без явного желания пользователя нельзя.
     */
    var autoExpensesEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EXPENSES, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_EXPENSES, value).apply()

    /**
     * Последняя известная точка пользователя — запоминается, когда геолокация
     * определяется на карте. Нужна бюджету, чтобы искать магазины из трат «рядом»,
     * не требуя фоновой геолокации. NaN — точки ещё не было.
     */
    var lastUserLatitude: Double
        get() = prefs.getString(KEY_LAST_LAT, null)?.toDoubleOrNull() ?: Double.NaN
        set(value) = prefs.edit().putString(KEY_LAST_LAT, value.toString()).apply()

    var lastUserLongitude: Double
        get() = prefs.getString(KEY_LAST_LON, null)?.toDoubleOrNull() ?: Double.NaN
        set(value) = prefs.edit().putString(KEY_LAST_LON, value.toString()).apply()

    /** Читать банковские SMS и превращать их в траты. */
    var bankSmsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BANK_SMS, false)
        set(value) = prefs.edit().putBoolean(KEY_BANK_SMS, value).apply()

    /** Читать уведомления банков и превращать их в траты. */
    var bankNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BANK_NOTIFICATIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_BANK_NOTIFICATIONS, value).apply()

    /**
     * Добавлять распознанные траты сразу в бюджет, без подтверждения.
     * По умолчанию выключено: разбор чужих уведомлений — эвристика, и ошибка
     * портит именно те данные, ради которых приложение нужно.
     */
    var bankNotificationsAutoAdd: Boolean
        get() = prefs.getBoolean(KEY_BANK_AUTO_ADD, false)
        set(value) = prefs.edit().putBoolean(KEY_BANK_AUTO_ADD, value).apply()

    /** Уведомлять о превышении лимита при добавлении новой траты. */
    var budgetAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUDGET_ALERTS, false)
        set(value) = prefs.edit().putBoolean(KEY_BUDGET_ALERTS, value).apply()

    companion object {
        const val DEFAULT_RADIUS = 1000f

        /** Варианты радиуса, которые предлагаются в настройках. */
        val RADIUS_OPTIONS = listOf(500f, 1000f, 2000f, 3000f)

        private const val KEY_RADIUS = "search_radius_meters"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_SEARCH_CATEGORIES = "search_categories"
        private const val KEY_BUDGET_ALERTS = "budget_alerts_enabled"
        private const val KEY_BANK_NOTIFICATIONS = "bank_notifications_enabled"
        private const val KEY_BANK_SMS = "bank_sms_enabled"
        private const val KEY_LAST_LAT = "last_user_lat"
        private const val KEY_LAST_LON = "last_user_lon"
        private const val KEY_BANK_AUTO_ADD = "bank_notifications_auto_add"
        private const val KEY_AUTO_EXPENSES = "auto_expenses_enabled"
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

/**
 * Полное удаление данных пользователя — одна кнопка вместо блуждания по разным
 * хранилищам. Каждое хранилище приложение заводит само по себе (SharedPreferences
 * под разными именами), общего единого места для "стереть всё" в Android нет,
 * поэтому список нужно поддерживать руками и не забывать сюда новые хранилища.
 *
 * Явно НЕ трогает: секреты сборки (ключи API — они не пользовательские данные)
 * и системные настройки экрана логина в самих Яндексе/VK — выйти из аккаунта
 * там пользователь может только сам, приложение к этому доступа не имеет.
 */
object UserDataEraser {
    fun eraseAll(context: Context) {
        LocalProfileStore.clear(context)
        ConsentStore.revoke(context)
        SessionStore.clear(context)
        PlacesCache(context).clear()
        PendingExpenseStore(context).clear()
        com.lifenavigator.app.data.money.PlaceMatcher.clear(context)
        BudgetStorage(context).clearAll()
        // Настройки (радиус, темы, выбранные категории) — это предпочтения показа,
        // а не персональные данные, поэтому намеренно не стираются вместе с остальным.
    }
}
