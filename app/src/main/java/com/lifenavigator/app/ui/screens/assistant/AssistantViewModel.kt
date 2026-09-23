package com.lifenavigator.app.ui.screens.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.ai.AssistantContext
import com.lifenavigator.app.data.ai.GptMessage
import com.lifenavigator.app.data.ai.GptResult
import com.lifenavigator.app.data.ai.YandexGptRepository
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
            text = "Привет! Я вижу ваш бюджет и места рядом. Спросите, например: " +
                "«сколько я могу тратить в день до конца месяца?» или «где рядом купить продукты подешевле?»",
            isFromUser = false
        )
    ),
    val isReplying: Boolean = false
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState

    /**
     * Сколько последних реплик отправляем модели вместе с новым вопросом.
     * Без истории помощник не поймёт «а подешевле?» после предыдущего вопроса,
     * а слать весь диалог целиком — лишние токены и деньги.
     */
    private val historyDepth = 8

    fun sendMessage(text: String) {
        val userMessage = AssistantMessage(id = UUID.randomUUID().toString(), text = text, isFromUser = true)
        _uiState.update { it.copy(messages = it.messages + userMessage, isReplying = true) }

        viewModelScope.launch {
            val reply = if (!YandexGptRepository.isConfigured) {
                "Помощник пока не подключён. Нужны ключ YandexGPT и идентификатор каталога " +
                    "в local.properties — как их получить, написано в README."
            } else {
                val context = AssistantContext.build(getApplication())
                val history = _uiState.value.messages
                    // Приветствие — наш текст, модели он не нужен и только сбивает.
                    .filterNot { it.id == "welcome" }
                    .takeLast(historyDepth)
                    .map { GptMessage(role = if (it.isFromUser) "user" else "assistant", text = it.text) }

                when (val result = YandexGptRepository.ask(context, history)) {
                    is GptResult.Success -> result.text
                    is GptResult.Failure -> "Не удалось получить ответ: ${result.message}"
                }
            }

            val replyMessage = AssistantMessage(id = UUID.randomUUID().toString(), text = reply, isFromUser = false)
            _uiState.update { it.copy(messages = it.messages + replyMessage, isReplying = false) }
        }
    }
}
