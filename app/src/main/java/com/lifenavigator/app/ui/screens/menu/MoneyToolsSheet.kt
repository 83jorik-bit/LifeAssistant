package com.lifenavigator.app.ui.screens.menu

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lifenavigator.app.data.local.AppSettings
import com.lifenavigator.app.data.money.ExpenseRecorder
import com.lifenavigator.app.data.money.QrParser
import com.lifenavigator.app.data.money.ScannedQr
import com.lifenavigator.app.data.money.StatementImporter

/**
 * Экран работы с деньгами: автоматические траты, сканирование QR и импорт выписки.
 *
 * Вынесен отдельно от общих настроек: тут всё связано с доступом к чувствительным
 * вещам (уведомления, камера, файлы), и смешивать это с выбором темы не стоит.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyToolsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var autoExpenses by remember { mutableStateOf(AppSettings(context).autoExpensesEnabled) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            status = "Сканирование отменено"
            return@rememberLauncherForActivityResult
        }
        status = when (val parsed = QrParser.parse(contents)) {
            is ScannedQr.Receipt -> {
                val added = ExpenseRecorder.record(
                    context = context,
                    amountRub = parsed.amountRub,
                    note = "Чек",
                    date = parsed.date,
                    categoryKey = ExpenseRecorder.UNCATEGORIZED_KEY
                )
                if (added) {
                    "Записана трата ${parsed.amountRub} ₽ от ${parsed.date}. " +
                        "Категорию можно поправить во вкладке «Бюджет»."
                } else {
                    "Такая трата уже записана"
                }
            }

            is ScannedQr.PaymentLink -> {
                // Платёж выполняет приложение банка: оно открывается по ссылке из QR.
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(parsed.url)))
                }.fold(
                    onSuccess = { "Открываем приложение банка для оплаты" },
                    onFailure = { "Не нашлось приложения, которое откроет этот платёж" }
                )
            }

            is ScannedQr.Unknown ->
                "Это не чек и не платёжный QR — распознать не удалось"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching { StatementImporter.import(context, uri) }.getOrNull()
        status = if (result == null) {
            "Не удалось прочитать файл"
        } else {
            "Импортировано: ${result.added}. Пропущено: ${result.skipped}. " +
                "Не разобрано строк: ${result.failed}."
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Деньги", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(20.dp))
            Text("Сканировать QR", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Чек — запишем трату по сумме из кода. Платёжный QR по СБП — откроем " +
                    "приложение банка для оплаты.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setPrompt("Наведите на QR чека или платёжный QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Открыть сканер")
            }

            Spacer(Modifier.height(24.dp))
            Text("Импорт выписки", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "CSV-выписка из банковского приложения. Формат у каждого банка свой, " +
                    "поэтому часть строк может не разобраться — их количество будет показано.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Выбрать файл выписки")
            }

            Spacer(Modifier.height(24.dp))
            Text("Траты из уведомлений банка", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Приложение будет читать пуши банков и само записывать траты. " +
                    "Android не умеет давать доступ к уведомлениям выборочно — технически " +
                    "служба видит все уведомления, поэтому приложение сразу отбрасывает всё, " +
                    "что пришло не от банковских приложений, и сохраняет только сумму и магазин. " +
                    "Текст уведомлений никуда не записывается.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Записывать автоматически", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = autoExpenses,
                    onCheckedChange = { checked ->
                        AppSettings(context).autoExpensesEnabled = checked
                        autoExpenses = checked
                        if (checked) {
                            status = "Включите «Жизненный навигатор» в списке доступа к уведомлениям"
                            // Системное разрешение на чтение уведомлений выдаётся только
                            // в настройках Android — диалогом его запросить нельзя.
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        }
                    }
                )
            }

            status?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Оплата картой через NFC в приложении невозможна: эмуляция банковской карты " +
                    "доступна только приложениям самих банков и платёжных систем — им выдают " +
                    "ключи и сертификаты, сторонним приложениям их не дают. Оплату по QR " +
                    "через СБП мы поддерживаем — она этих ограничений не имеет.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
