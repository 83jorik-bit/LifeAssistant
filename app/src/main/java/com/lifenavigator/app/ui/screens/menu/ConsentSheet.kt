package com.lifenavigator.app.ui.screens.menu

import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifenavigator.app.data.local.ConsentStore
import com.lifenavigator.app.data.local.ConsentText
import java.io.ByteArrayOutputStream

/**
 * Экран согласия на обработку персональных данных с подписью.
 *
 * Подпись рисуется пальцем и сохраняется картинкой вместе с датой и редакцией текста.
 * Юридические оговорки — в ConsentText: они показываются пользователю, а не спрятаны
 * в коде.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(
    onDismiss: () -> Unit,
    onSigned: () -> Unit
) {
    val context = LocalContext.current
    // Каждый мазок — отдельная линия, иначе при отрыве пальца они склеятся в одну.
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var canvasWidth by remember { mutableIntStateOf(0) }
    var canvasHeight by remember { mutableIntStateOf(0) }

    val hasSignature = strokes.any { it.size > 1 }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(ConsentText.TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    ConsentText.BODY,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Подпись", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Распишитесь пальцем в рамке.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                strokes.add(mutableListOf(offset))
                            },
                            onDrag = { change, _ ->
                                strokes.lastOrNull()?.add(change.position)
                                // Без этого жест «утекает» в прокрутку листа и линия рвётся.
                                change.consume()
                            }
                        )
                    }
            ) {
                canvasWidth = size.width.toInt()
                canvasHeight = size.height.toInt()
                strokes.forEach { stroke ->
                    for (i in 1 until stroke.size) {
                        drawLine(
                            color = Color.Black,
                            start = stroke[i - 1],
                            end = stroke[i],
                            strokeWidth = 4f
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { strokes.clear() }) { Text("Очистить подпись") }

            Spacer(Modifier.height(8.dp))
            Text(
                ConsentText.SIGNATURE_DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val png = renderSignature(strokes, canvasWidth, canvasHeight)
                    ConsentStore.save(context, png)
                    onSigned()
                },
                enabled = hasSignature,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Подписать и продолжить")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Черновик документа: перед публикацией приложения его должен проверить юрист, " +
                    "а в тексте — появиться реальные данные оператора.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Переводит нарисованную подпись в PNG (Base64), чтобы сохранить её как документ. */
private fun renderSignature(
    strokes: List<List<Offset>>,
    width: Int,
    height: Int
): String {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    strokes.forEach { stroke ->
        for (i in 1 until stroke.size) {
            canvas.drawLine(stroke[i - 1].x, stroke[i - 1].y, stroke[i].x, stroke[i].y, paint)
        }
    }

    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}
