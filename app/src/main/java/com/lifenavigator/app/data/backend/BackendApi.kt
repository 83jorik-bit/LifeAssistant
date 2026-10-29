package com.lifenavigator.app.data.backend

import com.lifenavigator.app.BuildConfig
import com.lifenavigator.app.data.ai.GptMessage
import com.lifenavigator.app.data.model.CustomCategory
import com.lifenavigator.app.data.model.Expense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Снимок бюджета для отправки/получения целиком — ровно то, что хранит BudgetStorage. */
data class BudgetSnapshot(
    val income: Map<String, Int>,
    val limits: Map<String, Int>,
    val customCategories: List<CustomCategory>,
    val expenses: List<Expense>
)

sealed class BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>()
    data class Failure(val message: String) : BackendResult<Nothing>()
}

/**
 * Клиент собственного сервера хранения данных (см. папку `backend/` в репозитории).
 *
 * Работает только если задан `BACKEND_BASE_URL` в local.properties/секретах —
 * без него `isConfigured == false`, и все вызовы возвращают понятную ошибку,
 * а не пытаются достучаться до пустого адреса. Тот же принцип, что и у
 * YANDEX_CLIENT_ID/VK_CLIENT_ID: не настроено — честно показываем это,
 * а не притворяемся, что функция работает.
 */
object BackendApi {

    val isConfigured: Boolean get() = BuildConfig.BACKEND_BASE_URL.isNotBlank()

    private const val TIMEOUT_MS = 35_000

    /** Обменивает access_token провайдера на нашу сессию. */
    suspend fun login(provider: String, providerAccessToken: String): BackendResult<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("provider", provider)
                    .put("accessToken", providerAccessToken)
                val response = request("POST", "/api/auth/login", null, body.toString())
                val token = JSONObject(response).getString("token")
                BackendResult.Success(token)
            }.getOrElse { e -> BackendResult.Failure(e.message ?: "Не удалось войти на сервере") }
        }

    suspend fun fetchBudget(sessionToken: String): BackendResult<BudgetSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = JSONObject(request("GET", "/api/budget", sessionToken, null))
                BackendResult.Success(parseBudget(response))
            }.getOrElse { e -> BackendResult.Failure(e.message ?: "Не удалось получить бюджет с сервера") }
        }

    suspend fun pushBudget(sessionToken: String, snapshot: BudgetSnapshot): BackendResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                request("PUT", "/api/budget", sessionToken, budgetToJson(snapshot).toString())
                BackendResult.Success(Unit)
            }.getOrElse { e -> BackendResult.Failure(e.message ?: "Не удалось отправить бюджет на сервер") }
        }

    suspend fun pushConsent(
        sessionToken: String,
        version: Int,
        signedAtEpochSeconds: Long,
        signatureBase64: String
    ): BackendResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("version", version)
                    .put("signedAtEpochSeconds", signedAtEpochSeconds)
                    .put("signatureBase64", signatureBase64)
                request("PUT", "/api/consent", sessionToken, body.toString())
                BackendResult.Success(Unit)
            }.getOrElse { e -> BackendResult.Failure(e.message ?: "Не удалось отправить согласие на сервер") }
        }

    /**
     * Запрашивает ответ ИИ через наш сервер. API-ключ YandexGPT остаётся на сервере,
     * а приложение передаёт только сессионный токен и контекст для ответа.
     */
    suspend fun askAssistant(
        sessionToken: String,
        systemPrompt: String,
        history: List<GptMessage>
    ): BackendResult<String> = withContext(Dispatchers.IO) {
        runCatching {
            val historyJson = JSONArray()
            history.forEach { message ->
                historyJson.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("text", message.text)
                )
            }
            val body = JSONObject()
                .put("systemPrompt", systemPrompt)
                .put("history", historyJson)
            val response = JSONObject(
                request("POST", "/api/assistant", sessionToken, body.toString())
            )
            val text = response.optString("text").trim()
            if (text.isBlank()) throw IllegalStateException("Сервер вернул пустой ответ ИИ")
            BackendResult.Success(text)
        }.getOrElse { e ->
            BackendResult.Failure(e.message ?: "Не удалось получить ответ помощника")
        }
    }

    /** Просит сервер удалить пользователя целиком. Best-effort: вызывающий код не должен
     *  блокировать локальное удаление данных ожиданием этого запроса. */
    suspend fun deleteAccount(sessionToken: String): BackendResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                request("DELETE", "/api/account", sessionToken, null)
                BackendResult.Success(Unit)
            }.getOrElse { e -> BackendResult.Failure(e.message ?: "Не удалось удалить данные на сервере") }
        }

    private fun parseBudget(json: JSONObject): BudgetSnapshot {
        val income = mutableMapOf<String, Int>()
        json.optJSONObject("income")?.let { obj ->
            obj.keys().forEach { key -> income[key] = obj.getInt(key) }
        }
        val limits = mutableMapOf<String, Int>()
        json.optJSONObject("limits")?.let { obj ->
            obj.keys().forEach { key -> limits[key] = obj.getInt(key) }
        }
        val customCategories = mutableListOf<CustomCategory>()
        json.optJSONArray("customCategories")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                customCategories += CustomCategory(key = item.getString("key"), title = item.getString("title"))
            }
        }
        val expenses = mutableListOf<Expense>()
        json.optJSONArray("expenses")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                expenses += Expense(
                    id = item.getString("id"),
                    categoryKey = item.getString("categoryKey"),
                    amountRub = item.getInt("amountRub"),
                    note = if (item.isNull("note")) null else item.optString("note").takeIf { it.isNotBlank() },
                    dateEpochDay = item.getLong("dateEpochDay")
                )
            }
        }
        return BudgetSnapshot(income, limits, customCategories, expenses)
    }

    private fun budgetToJson(snapshot: BudgetSnapshot): JSONObject {
        val income = JSONObject()
        snapshot.income.forEach { (month, amount) -> income.put(month, amount) }
        val limits = JSONObject()
        snapshot.limits.forEach { (key, limit) -> limits.put(key, limit) }
        val customCategories = JSONArray()
        snapshot.customCategories.forEach { category ->
            customCategories.put(JSONObject().put("key", category.key).put("title", category.title))
        }
        val expenses = JSONArray()
        snapshot.expenses.forEach { expense ->
            expenses.put(
                JSONObject()
                    .put("id", expense.id)
                    .put("categoryKey", expense.categoryKey)
                    .put("amountRub", expense.amountRub)
                    .put("note", expense.note ?: JSONObject.NULL)
                    .put("dateEpochDay", expense.dateEpochDay)
            )
        }
        return JSONObject()
            .put("income", income)
            .put("limits", limits)
            .put("customCategories", customCategories)
            .put("expenses", expenses)
    }

    private fun request(method: String, path: String, sessionToken: String?, body: String?): String {
        if (!isConfigured) throw IllegalStateException("Сервер не настроен (пуст BACKEND_BASE_URL)")

        val connection = (URL(BuildConfig.BACKEND_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            sessionToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) doOutput = true
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "Сервер ответил кодом $code"
                throw IllegalStateException(message)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
