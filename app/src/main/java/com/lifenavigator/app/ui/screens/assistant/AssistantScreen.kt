package com.lifenavigator.app.ui.screens.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var voiceSessionActive by remember { mutableStateOf(false) }
    var committedSpeech by remember { mutableStateOf("") }
    var partialSpeech by remember { mutableStateOf("") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        }
    }
    // Диктовка — это сессия, а не одна короткая фраза. Результаты каждого сегмента
    // накапливаются; пауза/таймаут распознавателя не отправляют сообщение и не теряют текст.
    val appendRecognized = rememberUpdatedState<(String) -> Unit> { recognized ->
        val clean = recognized.trim()
        if (clean.isNotBlank()) {
            committedSpeech = listOf(committedSpeech.trim(), clean)
                .filter { it.isNotBlank() }.joinToString(" ")
            partialSpeech = ""
            input = committedSpeech
            voiceError = null
        }
    }

    val scheduleNextSegment = rememberUpdatedState<() -> Unit> {
        if (voiceSessionActive && speechRecognizer != null) {
            mainHandler.postDelayed({
                if (voiceSessionActive) {
                    runCatching { speechRecognizer.startListening(speechIntent) }
                        .onFailure {
                            voiceSessionActive = false
                            voiceStatus = null
                            voiceError = "Не удалось продолжить диктовку: \${it.message ?: "ошибка распознавания"}"
                        }
                }
            }, 350L)
        }
    }

    DisposableEffect(speechRecognizer) {
        if (speechRecognizer != null) {
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val recognized = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!recognized.isNullOrBlank()) {
                        appendRecognized.value(recognized)
                    }
                    if (voiceSessionActive) {
                        voiceStatus = "Пауза. Можешь продолжить говорить или нажать «Готово»."
                        scheduleNextSegment.value()
                    } else {
                        partialSpeech = ""
                        input = committedSpeech
                        voiceStatus = if (committedSpeech.isBlank()) null
                            else "Диктовка завершена — проверь текст и нажми «Отправить»"
                    }
                }

                override fun onError(error: Int) {
                    if (voiceSessionActive && error in setOf(
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_NO_MATCH
                        )) {
                        voiceStatus = "Пауза. Я сохраняю распознанный текст — можешь продолжить."
                        scheduleNextSegment.value()
                    } else {
                        voiceSessionActive = false
                        partialSpeech = ""
                        voiceStatus = null
                        voiceError = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Для голосового ввода нужно разрешение на микрофон."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Распознаватель занят. Подожди немного и попробуй снова."
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Проблема с сетью распознавания. Уже распознанный текст сохранён."
                            else -> "Ошибка распознавания речи (код $error). Уже распознанный текст сохранён."
                        }
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    voiceError = null
                    voiceStatus = "Слушаю. Можно делать паузы — текст не отправляется автоматически."
                }
                override fun onBeginningOfSpeech() { voiceStatus = "Говорите…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { voiceStatus = "Обрабатываю фрагмент диктовки…" }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    partialSpeech = partial
                    input = listOf(committedSpeech.trim(), partial.trim())
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        onDispose {
            voiceSessionActive = false
            mainHandler.removeCallbacksAndMessages(null)
            runCatching { speechRecognizer?.cancel() }
            speechRecognizer?.destroy()
        }
    }

    val startSpeech: () -> Unit = {
        voiceError = null
        if (speechRecognizer == null) {
            voiceError = "На устройстве недоступно распознавание речи."
        } else if (!voiceSessionActive) {
            committedSpeech = input.trim()
            partialSpeech = ""
            voiceSessionActive = true
            voiceStatus = "Слушаю… Говори в удобном темпе; для завершения нажми «Готово»."
            runCatching { speechRecognizer.startListening(speechIntent) }
                .onFailure {
                    voiceSessionActive = false
                    voiceStatus = null
                    voiceError = "Не удалось запустить микрофон: \${it.message ?: "ошибка распознавания"}"
                }
        } else {
            voiceSessionActive = false
            mainHandler.removeCallbacksAndMessages(null)
            partialSpeech = ""
            voiceStatus = "Завершаю диктовку…"
            runCatching { speechRecognizer.stopListening() }
                .onFailure {
                    voiceStatus = null
                    voiceError = "Не удалось завершить диктовку: \${it.message ?: "ошибка"}"
                }
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
            text = if (state.replyModeLabel.startsWith("Облачный ИИ")) {
                "Режим: ${state.replyModeLabel}. Сводка бюджета и последние реплики передаются серверу и YandexGPT; имя, почта и точные координаты не включаются."
            } else {
                "Режим: ${state.replyModeLabel}. Ответы формируются на устройстве по данным бюджета и сохранённым местам. Для свободного диалога нужен настроенный серверный ИИ."
            },
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
            IconButton(
                onClick = { viewModel.setVoiceReplyEnabled(!state.isVoiceReplyEnabled) }
            ) {
                Icon(
                    if (state.isVoiceReplyEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (state.isVoiceReplyEnabled) "Выключить голосовые ответы" else "Включить голосовые ответы"
                )
            }
            IconButton(
                enabled = !state.isReplying,
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        startSpeech()
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) {
                Icon(
                    if (voiceSessionActive) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (voiceSessionActive) "Завершить диктовку" else "Начать диктовку"
                )
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
        when {
            voiceError != null -> Text(
                text = voiceError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
            voiceSessionActive -> Text(
                text = "Идёт диктовка. Паузы допустимы; для завершения нажми кнопку остановки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
            state.isReplying -> Text(
                text = "Обрабатываю команду…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
            state.isSpeaking -> Text(
                text = "LifeAssistant отвечает голосом…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
            voiceStatus != null -> Text(
                text = voiceStatus.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
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
