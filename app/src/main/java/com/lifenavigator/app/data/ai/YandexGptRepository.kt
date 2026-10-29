package com.lifenavigator.app.data.ai

import com.lifenavigator.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Одна реплика диалога для отправки модели. */
data class GptMessage(val role: String, val text: String)

sealed class GptResult {
    data class Success(val text: String) : GptResult()
    data class Failure(val message: String) : GptResult()
}

/**
 * Клиент YandexGPT (Yandex Foundation Models API).
 *
 * ВАЖНО про ключ. Сейчас запрос уходит напрямую из приложения, а значит ключ лежит
 * внутри APK. Любой, кто разберёт установочный файл, сможет его достать и тратить
 * вашу квоту. Для теста это терпимо, для публикации — нет: правильное место для
 * такого вызова — собственный сервер (папка `backend/`), который держит ключ у себя
 * и проксирует запросы. Пока сервер не развёрнут, оставлено прямое обращение,
 * но это временное решение, а не рабочая схема для боевой версии.
 */
object YandexGptRepository {

    // API credentials must not be bundled in an APK. This client remains disabled
    // until a server-side proxy is configured; never place the secret in BuildConfig.
    private val apiKey: String get() = ""


    private const val URL_COMPLETION = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
    private const val TIMEOUT_MS = 30_000

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && BuildConfig.YANDEX_GPT_FOLDER_ID.isNotBlank()

    /**
     * Задаёт вопрос модели.
     *
     * [systemPrompt] описывает роль помощника и содержит выжимку данных пользователя —
     * бюджет и места рядом. Благодаря этому ответы получаются по существу, а не общими
     * словами: модель видит реальные цифры, а не догадывается о них.
     */
    suspend fun ask(systemPrompt: String, history: List<GptMessage>): GptResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured) {
                return@withContext GptResult.Failure(
                    "Облачный помощник временно отключён до настройки защищённого серверного прокси."
                )
            }

            runCatching {
                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("text", systemPrompt))
                    history.forEach { message ->
                        put(JSONObject().put("role", message.role).put("text", message.text))
                    }
                }

                val body = JSONObject()
                    // yandexgpt-lite дешевле и быстрее Pro, а для коротких бытовых
                    // подсказок разницы в качестве почти нет. Поменять на "yandexgpt"
                    // (Pro) можно здесь же одной строкой.
                    .put("modelUri", "gpt://${BuildConfig.YANDEX_GPT_FOLDER_ID}/yandexgpt-lite/latest")
                    .put(
                        "completionOptions",
                        JSONObject()
                            .put("stream", false)
                            .put("temperature", 0.3)
                            .put("maxTokens", "600")
                    )
                    .put("messages", messages)

                val response = post(body.toString())
                val text = JSONObject(response)
                    .getJSONObject("result")
                    .getJSONArray("alternatives")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("text")

                GptResult.Success(text.trim())
            }.getOrElse { e ->
                GptResult.Failure(e.message ?: "Не удалось получить ответ")
            }
        }

    private fun post(body: String): String {
        val connection = (URL(URL_COMPLETION).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Api-Key ${apiKey}")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "YandexGPT ответил кодом $code"
                throw IllegalStateException(message)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
