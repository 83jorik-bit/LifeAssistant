package com.lifenavigator.app.data.auth

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/** Данные пользователя, полученные от провайдера. */
data class OAuthAccount(
    val provider: OAuthProvider,
    val name: String,
    val email: String?
)

sealed class AuthResult {
    data class Success(val account: OAuthAccount) : AuthResult()
    data class Failure(val message: String) : AuthResult()
    data object Cancelled : AuthResult()
}

/**
 * Вход через внешний ID по схеме Authorization Code + PKCE.
 *
 * Как это работает:
 * 1. Генерируем случайный code_verifier и его хеш code_challenge.
 * 2. Открываем страницу провайдера в браузере — пароль пользователь вводит там,
 *    приложение его не видит и не хранит. Это и есть главный плюс такого входа.
 * 3. Провайдер возвращает код на наш адрес lifenavigator://auth/... .
 * 4. Меняем код на токен, передавая code_verifier. Без него перехваченный код бесполезен.
 *
 * client_secret здесь отсутствует намеренно: в мобильном приложении его невозможно
 * сохранить в тайне, и PKCE придуман именно как замена секрету.
 */
object PkceOAuthClient {

    private const val TIMEOUT_MS = 20_000

    /** Состояние текущей попытки входа: нужно, чтобы связать редирект с запросом. */
    private var pendingVerifier: String? = null
    private var pendingState: String? = null
    private var pendingProvider: OAuthProvider? = null

    /** Редирект от браузера кладётся сюда, экран входа его забирает. */
    val redirect = MutableStateFlow<Uri?>(null)

    fun consumeRedirect() {
        redirect.value = null
    }

    /** Строит адрес страницы входа и запоминает параметры текущей попытки. */
    fun buildAuthorizeUrl(provider: OAuthProvider): String {
        val verifier = randomString(64)
        val state = randomString(24)
        pendingVerifier = verifier
        pendingState = state
        pendingProvider = provider

        val challenge = sha256Base64Url(verifier)

        return buildString {
            append(provider.authorizeUrl)
            append("?response_type=code")
            append("&client_id=${provider.clientId.urlEncoded()}")
            append("&redirect_uri=${provider.redirectUri.urlEncoded()}")
            append("&scope=${provider.scope.urlEncoded()}")
            append("&state=${state.urlEncoded()}")
            append("&code_challenge=${challenge.urlEncoded()}")
            append("&code_challenge_method=S256")
        }
    }

    /**
     * Обрабатывает возврат из браузера: проверяет state, меняет код на токен
     * и запрашивает данные пользователя.
     */
    suspend fun handleRedirect(uri: Uri): AuthResult = withContext(Dispatchers.IO) {
        val provider = OAuthProvider.byRedirectPath(uri.lastPathSegment)
            ?: pendingProvider
            ?: return@withContext AuthResult.Failure("Неизвестный провайдер в ответе")

        uri.getQueryParameter("error")?.let { error ->
            val description = uri.getQueryParameter("error_description") ?: error
            return@withContext if (error.contains("access_denied", ignoreCase = true)) {
                AuthResult.Cancelled
            } else {
                AuthResult.Failure(description)
            }
        }

        val code = uri.getQueryParameter("code")
            ?: return@withContext AuthResult.Failure("Провайдер не вернул код авторизации")

        val returnedState = uri.getQueryParameter("state")
        if (pendingState != null && returnedState != pendingState) {
            // Несовпадение state — признак подмены запроса, вход прерываем.
            return@withContext AuthResult.Failure("Не совпал параметр state")
        }

        val verifier = pendingVerifier
            ?: return@withContext AuthResult.Failure("Потеряны параметры входа, попробуйте снова")

        // VK ID дополнительно возвращает device_id и требует его при обмене кода.
        val deviceId = uri.getQueryParameter("device_id")

        return@withContext try {
            val token = exchangeCodeForToken(provider, code, verifier, deviceId)
            val account = fetchAccount(provider, token)
            clearPending()
            AuthResult.Success(account)
        } catch (e: Exception) {
            clearPending()
            AuthResult.Failure(e.message ?: "Не удалось завершить вход")
        }
    }

    private fun clearPending() {
        pendingVerifier = null
        pendingState = null
        pendingProvider = null
    }

    private fun exchangeCodeForToken(
        provider: OAuthProvider,
        code: String,
        verifier: String,
        deviceId: String?
    ): String {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&code=${code.urlEncoded()}")
            append("&client_id=${provider.clientId.urlEncoded()}")
            append("&redirect_uri=${provider.redirectUri.urlEncoded()}")
            append("&code_verifier=${verifier.urlEncoded()}")
            if (deviceId != null) append("&device_id=${deviceId.urlEncoded()}")
        }

        val response = postForm(provider.tokenUrl, body)
        val json = JSONObject(response)
        return json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                json.optString("error_description").takeIf { it.isNotBlank() }
                    ?: "Провайдер не выдал токен"
            )
    }

    private fun fetchAccount(provider: OAuthProvider, token: String): OAuthAccount {
        val json = JSONObject(getWithToken(provider.userInfoUrl, token, provider))
        return when (provider) {
            OAuthProvider.YANDEX -> OAuthAccount(
                provider = provider,
                name = json.optString("real_name").takeIf { it.isNotBlank() }
                    ?: json.optString("display_name").takeIf { it.isNotBlank() }
                    ?: json.optString("login"),
                email = json.optString("default_email").takeIf { it.isNotBlank() }
            )

            OAuthProvider.VK -> {
                val user = json.optJSONObject("user") ?: json
                val first = user.optString("first_name")
                val last = user.optString("last_name")
                OAuthAccount(
                    provider = provider,
                    name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
                        .ifBlank { "Пользователь VK" },
                    email = user.optString("email").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    private fun postForm(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getWithToken(url: String, token: String, provider: OAuthProvider): String {
        // У Яндекса свой формат заголовка авторизации, у VK — стандартный Bearer.
        val header = if (provider == OAuthProvider.YANDEX) "OAuth $token" else "Bearer $token"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", header)
        }
        try {
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching {
                JSONObject(text).optString("error_description").takeIf { it.isNotBlank() }
            }.getOrNull() ?: "Сервис ответил кодом $code"
            throw IllegalStateException(message)
        }
        return text
    }

    private fun randomString(length: Int): String {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(length)
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")
}
