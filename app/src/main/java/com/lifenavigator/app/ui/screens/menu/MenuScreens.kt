package com.lifenavigator.app.ui.screens.menu

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.lifenavigator.app.data.auth.AuthResult
import com.lifenavigator.app.data.auth.OAuthProvider
import com.lifenavigator.app.data.auth.PkceOAuthClient
import com.lifenavigator.app.data.local.AppSettings
import com.lifenavigator.app.data.local.ConsentStore
import com.lifenavigator.app.data.local.ConsentText
import com.lifenavigator.app.data.local.LocalProfileStore
import com.lifenavigator.app.data.local.ThemeController
import com.lifenavigator.app.data.local.ThemeMode

/**
 * Вход в приложение.
 *
 * Порядок принципиальный: сначала согласие на обработку персональных данных, потом вход.
 * Иначе мы начали бы обрабатывать данные до получения согласия, а это прямо запрещено.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val profile by LocalProfileStore.profile.collectAsState()
    val consent by ConsentStore.consent.collectAsState()
    val redirect by PkceOAuthClient.redirect.collectAsState()

    var showConsent by remember { mutableStateOf(false) }
    var pendingProvider by remember { mutableStateOf<OAuthProvider?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val consentGiven = consent != null && consent?.version == ConsentText.VERSION

    // Возврат из браузера: меняем код на токен и достаём имя пользователя.
    LaunchedEffect(redirect) {
        val uri = redirect ?: return@LaunchedEffect
        busy = true
        when (val result = PkceOAuthClient.handleRedirect(uri)) {
            is AuthResult.Success -> {
                LocalProfileStore.save(context, result.account.name, result.account.email)
                status = "Вход выполнен через ${result.account.provider.title}"
            }
            is AuthResult.Failure -> status = "Не удалось войти: ${result.message}"
            AuthResult.Cancelled -> status = "Вход отменён"
        }
        busy = false
        PkceOAuthClient.consumeRedirect()
    }

    val startLogin: (OAuthProvider) -> Unit = { provider ->
        val url = PkceOAuthClient.buildAuthorizeUrl(provider)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            status = "Не нашлось браузера, чтобы открыть страницу входа"
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
            Text("Вход", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            if (profile != null) {
                // Вход выполнен — незачем снова показывать шаги согласия и кнопки
                // провайдеров, только профиль и выход.
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Вы вошли как", style = MaterialTheme.typography.bodySmall)
                        Text(profile!!.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        profile!!.email?.let { mail ->
                            Text(mail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { LocalProfileStore.clear(context) }) {
                            Text("Выйти")
                        }
                    }
                }
                return@Column
            }

            Spacer(Modifier.height(16.dp))

            // Шаг 1 — согласие.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (consentGiven) "Согласие подписано" else "Шаг 1. Согласие на обработку данных",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (consentGiven) {
                            "Вы подписали согласие. Его можно отозвать в любой момент."
                        } else {
                            "Без согласия вход недоступен: обрабатывать данные до его получения нельзя."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (consentGiven) {
                        OutlinedButton(onClick = { ConsentStore.revoke(context) }) {
                            Text("Отозвать согласие")
                        }
                    } else {
                        Button(onClick = { showConsent = true }) {
                            Text("Прочитать и подписать")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Шаг 2. Вход через существующий ID", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            OAuthProvider.entries.forEach { provider ->
                BrandedSignInButton(
                    provider = provider,
                    onClick = {
                        if (!consentGiven) {
                            pendingProvider = provider
                            showConsent = true
                        } else {
                            startLogin(provider)
                        }
                    },
                    enabled = provider.isConfigured && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                if (!provider.isConfigured) {
                    Text(
                        "Не настроено: добавьте ${if (provider == OAuthProvider.YANDEX) "YANDEX_CLIENT_ID" else "VK_CLIENT_ID"} " +
                            "в local.properties и укажите в кабинете адрес возврата ${provider.redirectUri}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            if (busy) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Завершаем вход…", style = MaterialTheme.typography.bodySmall)
                }
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Пароль вводится на странице Яндекса или VK — приложение его не видит " +
                    "и не хранит. Свой сервер регистрации пока не поднят: почему так " +
                    "и что для него потребуется, написано в README.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            GosuslugiInfoCard()
        }
    }

    if (showConsent) {
        ConsentSheet(
            onDismiss = {
                showConsent = false
                pendingProvider = null
            },
            onSigned = {
                showConsent = false
                pendingProvider?.let { startLogin(it) }
                pendingProvider = null
            }
        )
    }
}

/**
 * Вход через Госуслуги (ЕСИА) не реализован и не появится в виде обычной кнопки —
 * условия делают это несоразмерным задаче. Честное объяснение вместо неработающей кнопки.
 *
 * Требования на 2026 год: учётная запись ЮРИДИЧЕСКОГО лица на Госуслугах, квалифицированная
 * электронная подпись юрлица, регистрация информационной системы в технологическом портале
 * ЕСИА и её аттестация по требованиям информационной безопасности. С 31.12.2026 добавляется
 * обязательная поддержка OpenID Connect с подтверждением соответствия требованиям ФСБ.
 * Проверено на дату этого комментария; условия ЕСИА могут измениться — стоит сверяться
 * с технологическим порталом ЕСИА перед принятием решения.
 */
@Composable
private fun GosuslugiInfoCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Вход через Госуслуги", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Не реализован — и вряд ли появится в ближайшее время. Подключение к ЕСИА " +
                    "требует юридического лица (физлицу недоступно в принципе), квалифицированной " +
                    "электронной подписи организации, регистрации информационной системы в " +
                    "технологическом портале ЕСИА и её аттестации по требованиям информационной " +
                    "безопасности. С 31.12.2026 добавляется ещё и обязательное подтверждение " +
                    "соответствия требованиям ФСБ. Для приложения такого масштаба это несоразмерно " +
                    "затратам — Яндекс ID и VK ID закрывают ту же задачу без этого пути.",
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
