package com.lifenavigator.app.ui.screens.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.lifenavigator.app.data.local.AppSettings
import com.lifenavigator.app.data.local.LocalProfileStore
import com.lifenavigator.app.data.local.ThemeController
import com.lifenavigator.app.data.local.ThemeMode

/**
 * Способ входа через внешний ID и что реально нужно, чтобы его подключить.
 *
 * Тексты не выдуманы: это то, что пишут сами провайдеры. Ни один из способов
 * не включён, потому что для каждого нужны свои ключи и согласования, а кнопка,
 * которая ничего не делает, хуже её отсутствия.
 */
private data class IdProvider(
    val title: String,
    val requirement: String,
    val feasibility: String
)

private val ID_PROVIDERS = listOf(
    IdProvider(
        title = "VK ID",
        requirement = "Регистрация приложения в кабинете VK ID, client_id и client_secret, " +
            "отпечаток сертификата подписи. Подключается VK ID SDK для Android (OAuth 2.1), " +
            "через него доступен и вход по аккаунтам Одноклассников и Mail.",
        feasibility = "Доступно физическому лицу — регистрироваться как компания не нужно."
    ),
    IdProvider(
        title = "Яндекс ID",
        requirement = "Регистрация приложения в сервисе Яндекс OAuth, получение client_id " +
            "и работа по OAuth 2.0.",
        feasibility = "Обычная регистрация приложения. Для отдельных сценариев Яндекса " +
            "(например Яндекс Пэй) аккаунт дополнительно должен состоять в организации " +
            "с правами разработчика."
    ),
    IdProvider(
        title = "Сбер ID",
        requirement = "Заявка на подключение и настройка получения кодов авторизации; " +
            "есть SDK для Android. Сервис предназначен для входа физических лиц.",
        feasibility = "Здесь источники расходятся: в справке Сбера сказано, что отдельных " +
            "бумажных документов нет и условия принимаются при интеграции, а в обзорных " +
            "материалах упоминаются анкета и договор. Это нужно уточнять напрямую у Сбера."
    ),
    IdProvider(
        title = "Госуслуги (ЕСИА)",
        requirement = "Учётная запись ЮРИДИЧЕСКОГО лица на Госуслугах, квалифицированная " +
            "электронная подпись юрлица, регистрация информационной системы в технологическом " +
            "портале ЕСИА, аттестация системы по требованиям информационной безопасности. " +
            "С 31.12.2026 добавляется поддержка OpenID Connect с подтверждением соответствия " +
            "требованиям ФСБ.",
        feasibility = "Самый тяжёлый путь. Физлицу недоступен в принципе: нужна организация, " +
            "КЭП и аттестованная система. Для приложения такого масштаба это несоразмерно."
    )
)

/**
 * Вход в аккаунт.
 *
 * Работает локальный профиль — имя хранится на устройстве. Внешние ID показаны
 * списком с условиями подключения: так видно, что для чего понадобится,
 * и ни одна кнопка не притворяется рабочей.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val profile by LocalProfileStore.profile.collectAsState()

    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var email by remember { mutableStateOf(profile?.email.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Профиль", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Имя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(60) },
                label = { Text("Почта (необязательно)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        LocalProfileStore.save(context, name.trim(), email.trim())
                        onDismiss()
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Сохранить")
                }
                if (profile != null) {
                    OutlinedButton(
                        onClick = {
                            LocalProfileStore.clear(context)
                            name = ""
                            email = ""
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Удалить")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Профиль хранится только на этом устройстве и никуда не отправляется. " +
                    "Это не регистрация: чтобы аккаунт был доступен с другого телефона, " +
                    "нужен сервер, который будет хранить учётные записи.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            Text("Вход через существующий ID", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Ни один способ не подключён — каждому нужны свои ключи и согласования. " +
                    "Ниже честно указано, что потребуется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            ID_PROVIDERS.forEach { provider ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(provider.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(provider.requirement, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            provider.feasibility,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Дополнительных разрешений Android для входа не нужно: хватает доступа " +
                    "в интернет, который у приложения уже есть. Автоподстановка кода из SMS " +
                    "потребовала бы либо сервисов Google, от которых мы отказались ради RuStore, " +
                    "либо чтения SMS — опасного разрешения, которое магазины требуют обосновывать.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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

            Text("Оформление", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            val context = LocalContext.current
            val currentTheme by ThemeController.mode.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentTheme == mode,
                        onClick = { ThemeController.set(context, mode) },
                        label = { Text(mode.title) }
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
