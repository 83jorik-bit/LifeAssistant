package com.lifenavigator.app.data.auth

import com.lifenavigator.app.BuildConfig

/**
 * Провайдеры входа через существующий ID.
 *
 * Работаем по стандартному Authorization Code + PKCE прямо из приложения, без их SDK:
 * PKCE как раз и придуман для мобильных клиентов, где невозможно безопасно хранить
 * client_secret. Секрет здесь не нужен и не используется.
 */
enum class OAuthProvider(
    val title: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val userInfoUrl: String,
    val scope: String,
    val redirectPath: String
) {
    YANDEX(
        title = "Яндекс ID",
        authorizeUrl = "https://oauth.yandex.ru/authorize",
        tokenUrl = "https://oauth.yandex.ru/token",
        userInfoUrl = "https://login.yandex.ru/info?format=json",
        scope = "login:info login:email",
        redirectPath = "yandex"
    ),
    VK(
        title = "VK ID",
        authorizeUrl = "https://id.vk.com/authorize",
        tokenUrl = "https://id.vk.com/oauth2/auth",
        userInfoUrl = "https://id.vk.com/oauth2/user_info",
        scope = "email",
        redirectPath = "vk"
    );

    /** client_id задаётся в local.properties и попадает в сборку. Пусто — вход не настроен. */
    val clientId: String
        get() = when (this) {
            YANDEX -> BuildConfig.YANDEX_CLIENT_ID
            VK -> BuildConfig.VK_CLIENT_ID
        }

    val isConfigured: Boolean get() = clientId.isNotBlank()

    /**
     * Адрес возврата в приложение.
     *
     * У Яндекса он настраивается в кабинете, и там же есть поле для него.
     * У VK ID для Android/iOS такого поля в кабинете НЕТ ВООБЩЕ — адрес не задаётся,
     * а вычисляется по client_id и обязан быть именно таким:
     * vk{clientId}://vk.ru/blank.html. Отсюда и путаница на скриншотах кабинета —
     * поле искали не там, потому что его в принципе не существует.
     */
    val redirectUri: String
        get() {
            if (this == VK) return "vk$clientId://vk.ru/blank.html"

            val override = BuildConfig.YANDEX_REDIRECT_URI
            return override.takeIf { it.isNotBlank() } ?: "$REDIRECT_SCHEME://auth/$redirectPath"
        }

    companion object {
        const val REDIRECT_SCHEME = "lifenavigator"

        fun byRedirectPath(path: String?): OAuthProvider? =
            entries.firstOrNull { it.redirectPath == path }
    }
}
