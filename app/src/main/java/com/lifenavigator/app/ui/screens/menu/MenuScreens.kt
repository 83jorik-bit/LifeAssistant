package com.lifenavigator.app.ui.screens.menu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifenavigator.app.data.local.AppSettings

/**
 * Вход в аккаунт.
 *
 * Настоящая аутентификация требует сервера: где-то должны храниться учётные записи,
 * проверяться пароли и выдаваться токены. Пока такого сервера нет, показывать форму
 * логина было бы обманом — она бы просто ничего не делала. Поэтому здесь честное
 * объяснение, а не нерабочее поле ввода.
 */
@Composable
fun SignInDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вход") },
        text = {
            Column {
                Text("Вход в аккаунт пока не подключён.")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Чтобы он заработал, нужен сервер, который будет хранить учётные записи " +
                        "и выдавать токены — либо вход через готового провайдера (например, Яндекс ID). " +
                        "Форму логина, которая ни к чему не подключена, добавлять смысла нет.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Понятно") }
        }
    )
}

/**
 * Настройки. Радиус поиска — настоящая работающая настройка: сохраняется между
 * запусками и сразу применяется к поиску мест.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    currentRadiusMeters: Float,
    onRadiusSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(20.dp))

            Text("Радиус поиска", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "В каком радиусе от вас искать места.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.RADIUS_OPTIONS.forEach { radius ->
                    FilterChip(
                        selected = currentRadiusMeters == radius,
                        onClick = { onRadiusSelected(radius) },
                        label = { Text(formatRadius(radius)) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Список мест сохраняется между запусками: при открытии приложения показываются " +
                    "результаты прошлого сеанса, пока не обновится геолокация.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** «500 м» для сотен метров и «2 км» для километров — без «0 км». */
private fun formatRadius(radiusMeters: Float): String =
    if (radiusMeters < 1000f) {
        "${radiusMeters.toInt()} м"
    } else {
        "${(radiusMeters / 1000).toInt()} км"
    }
