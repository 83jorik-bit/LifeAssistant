package com.lifenavigator.app.ui.screens.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.ai.AssistantContext
import com.lifenavigator.app.data.ai.GptMessage
import com.lifenavigator.app.data.backend.BackendApi
import com.lifenavigator.app.data.backend.BackendResult
import com.lifenavigator.app.data.local.SessionStore
import com.lifenavigator.app.data.model.AssistantMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AssistantUiState(
    val messages: List<AssistantMessage> = listOf(
        AssistantMessage(
            id = "welcome",
            text = "Привет! Я LifeAssistant. Пока ты занимаешься важным, я помогу с повседневными задачами. " +
                "Например: «сколько я могу тратить в день до конца месяца?» или «какие места рядом стоит посмотреть?»",
            isFromUser = false
        )
    ),
    val isReplying: Boolean = false
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState

    /** Ограничиваем историю: достаточно последних реплик для контекста диалога. */
    private val historyDepth = 8

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _uiState.value.isReplying) return

        val userMessage = AssistantMessage(
            id = UUID.randomUUID().toString(),
            text = trimmed,
            isFromUser = true
        )
        _uiState.update { it.copy(messages = it.messages + userMessage, isReplying = true) }

        viewModelScope.launch {
            val token = SessionStore.token.value
            val reply = when {
                !BackendApi.isConfigured ->
                    "Защищённый ИИ пока не настроен: укажите BACKEND_BASE_URL при сборке " +
                        "и настройте ключ YandexGPT только на сервере."

                token.isNullOrBlank() ->
                    "Для работы ИИ войдите через Яндекс ID или VK ID в разделе входа. " +
                        "Это создаст защищённую серверную сессию; остальные функции приложения работают и без неё."

                else -> runCatching {
                    val context = AssistantContext.build(getApplication())
                    val history = _uiState.value.messages
                        .filterNot { it.id == "welcome" }
                        .takeLast(historyDepth)
                        .map { message ->
                            GptMessage(
                                role = if (message.isFromUser) "user" else "assistant",
                                text = message.text
                            )
                        }

                    when (val result = BackendApi.askAssistant(token, context, history)) {
                        is BackendResult.Success -> result.value
                        is BackendResult.Failure -> "Не удалось получить ответ: ${result.message}"
                    }
                }.getOrElse { error ->
                    "Не удалось подготовить запрос: ${error.message ?: "неизвестная ошибка"}"
                }
            }

            val replyMessage = AssistantMessage(
                id = UUID.randomUUID().toString(),
                text = reply,
                isFromUser = false
            )
            _uiState.update { it.copy(messages = it.messages + replyMessage, isReplying = false) }
        }
    }
}
