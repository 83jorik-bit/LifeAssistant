package com.lifenavigator.app.ui.screens.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.ai.AssistantContext
import com.lifenavigator.app.data.ai.GptMessage
import com.lifenavigator.app.data.ai.LocalAssistantEngine
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
                "Могу рассчитать расходы и остаток бюджета, проверить лимиты и показать сохранённые места рядом.",
            isFromUser = false
        )
    ),
    val isReplying: Boolean = false,
    val replyModeLabel: String = "Автономный режим"
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState

    /** Последние реплики нужны серверной модели для контекста диалога. */
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
            var replyMode = "Автономный режим"
            val application = getApplication<Application>()
            val sessionToken = SessionStore.token.value

            val cloudResult = if (BackendApi.isConfigured && !sessionToken.isNullOrBlank()) {
                runCatching {
                    val systemPrompt = AssistantContext.build(application)
                    val history = _uiState.value.messages
                        .filterNot { it.id == "welcome" }
                        .takeLast(historyDepth)
                        .map { message ->
                            GptMessage(
                                role = if (message.isFromUser) "user" else "assistant",
                                text = message.text
                            )
                        }
                    BackendApi.askAssistant(sessionToken, systemPrompt, history)
                }.getOrElse { error ->
                    BackendResult.Failure(error.message ?: "Серверный ИИ недоступен")
                }
            } else {
                null
            }

            val reply = when (cloudResult) {
                is BackendResult.Success -> {
                    replyMode = "Облачный ИИ"
                    cloudResult.value
                }
                is BackendResult.Failure -> {
                    replyMode = "Автономный режим · сервер недоступен"
                    val local = LocalAssistantEngine.reply(application, trimmed)
                    "$local\n\nСейчас ответ сформирован локально: не удалось обратиться к серверному ИИ."
                }
                null -> {
                    val local = LocalAssistantEngine.reply(application, trimmed)
                    if (!BackendApi.isConfigured) {
                        replyMode = "Автономный режим"
                        local
                    } else {
                        replyMode = "Автономный режим · требуется вход"
                        "$local\n\nДля свободного диалога войди через Яндекс ID или VK ID — после этого приложение сможет обратиться к настроенному серверному ИИ."
                    }
                }
            }

            val replyMessage = AssistantMessage(
                id = UUID.randomUUID().toString(),
                text = reply,
                isFromUser = false
            )
            _uiState.update {
                it.copy(
                    messages = it.messages + replyMessage,
                    isReplying = false,
                    replyModeLabel = replyMode
                )
            }
        }
    }
}
