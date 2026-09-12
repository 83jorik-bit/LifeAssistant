package com.lifenavigator.app.ui.screens.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.model.AssistantMessage
import com.lifenavigator.app.data.repository.MockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AssistantUiState(
    val messages: List<AssistantMessage> = listOf(
        AssistantMessage(
            id = "welcome",
            text = "Привет! Я помогу спланировать бюджет, напомню о подписках и подберу маршрут по ближайшим точкам. Спросите что-нибудь 🙂",
            isFromUser = false
        )
    ),
    val isReplying: Boolean = false
)

class AssistantViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState

    fun sendMessage(text: String) {
        val userMessage = AssistantMessage(id = UUID.randomUUID().toString(), text = text, isFromUser = true)
        _uiState.update { it.copy(messages = it.messages + userMessage, isReplying = true) }

        viewModelScope.launch {
            val reply = MockRepository.getAssistantReply(text)
            val replyMessage = AssistantMessage(id = UUID.randomUUID().toString(), text = reply, isFromUser = false)
            _uiState.update { it.copy(messages = it.messages + replyMessage, isReplying = false) }
        }
    }
}
