package com.lifenavigator.app.ui.screens.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifenavigator.app.data.model.AssistantMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(viewModel: AssistantViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
    }
    val onRecognized = rememberUpdatedState<(String) -> Unit> { recognized ->
        input = recognized
        voiceError = null
    }

    DisposableEffect(speechRecognizer) {
        if (speechRecognizer != null) {
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val recognized = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (recognized.isNullOrBlank()) {
                        voiceError = "Не удалось распознать речь. Попробуйте ещё раз."
                    } else {
                        onRecognized.value(recognized)
                    }
                }

                override fun onError(error: Int) {
                    voiceError = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "Речь не распознана. Попробуйте ещё раз."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Для голосового ввода нужно разрешение на микрофон."
                        else -> "Ошибка распознавания речи (код $error)."
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) { voiceError = null }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        onDispose { speechRecognizer?.destroy() }
    }

    val startSpeech: () -> Unit = {
        voiceError = null
        if (speechRecognizer == null) {
            voiceError = "На устройстве недоступно распознавание речи."
        } else {
            speechRecognizer.startListening(speechIntent)
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeech()
        else voiceError = "Разрешение на микрофон не предоставлено."
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(state.messages.size - 1) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("LifeAssistant") })
        Text(
            text = "Для персональных ответов передаются сводка бюджета и названия мест; имя и точные координаты не отправляются.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages, key = { it.id }) { message ->
                ChatBubble(message)
            }
            if (state.isReplying) {
                item { TypingIndicator() }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Чем заняться вместо рутины?") },
                singleLine = true
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    startSpeech()
                } else {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }) {
                Icon(Icons.Filled.Mic, contentDescription = "Голосовой ввод")
            }
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    viewModel.sendMessage(input.trim())
                    input = ""
                    voiceError = null
                }
            }, enabled = input.isNotBlank() && !state.isReplying) {
                Icon(Icons.Filled.Send, contentDescription = "Отправить")
            }
        }
        if (voiceError != null) {
            Text(
                text = voiceError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun ChatBubble(message: AssistantMessage) {
    val alignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isFromUser)
        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isFromUser)
        MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(
                message.text,
                modifier = Modifier.padding(12.dp),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Card {
            Text(
                "Помощник печатает…",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
