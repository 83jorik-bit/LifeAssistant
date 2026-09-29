package com.lifenavigator.app.ui.screens.menu

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.lifenavigator.app.BuildConfig
import com.lifenavigator.app.data.auth.AuthResult
import com.lifenavigator.app.data.auth.OAuthProvider
import com.lifenavigator.app.data.auth.PkceOAuthClient
import com.lifenavigator.app.data.backend.BackendApi
import com.lifenavigator.app.data.backend.BackendResult
import com.lifenavigator.app.data.backend.BudgetSnapshot
import com.lifenavigator.app.data.local.AppSettings
import com.lifenavigator.app.data.local.BudgetExporter
import com.lifenavigator.app.data.local.BudgetNotifier
import com.lifenavigator.app.data.local.BudgetStorage
import com.lifenavigator.app.data.local.ConsentStore
import com.lifenavigator.app.data.local.ConsentText
import com.lifenavigator.app.data.local.LocalProfileStore
import com.lifenavigator.app.data.money.StatementImporter
import com.lifenavigator.app.data.notifications.BankNotificationListener
import com.lifenavigator.app.data.local.SessionStore
import com.lifenavigator.app.data.local.ThemeController
import com.lifenavigator.app.data.local.ThemeMode
import com.lifenavigator.app.data.local.UserDataEraser
import com.lifenavigator.app.data.model.NeedCategory
import kotlinx.coroutines.launch

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

                // Сервер — не обязательное условие входа: без него приложение
                // продолжает работать полностью локально, как и раньше.
                if (BackendApi.isConfigured) {
                    val providerKey = if (result.account.provider == OAuthProvider.YANDEX) "yandex" else "vk"
                    when (val login = BackendApi.login(providerKey, result.account.accessToken)) {
                        is BackendResult.Success -> {
                            SessionStore.save(context, login.value)
                            status = "${status}. Данные подключены к серверу."
                        }
                        is BackendResult.Failure -> {
                            // Не считаем это провалом входа: профиль уже сохранён локально,
                            // сервер — это только необязательная синхронизация поверх него.
                            status = "${status}. Сервер сейчас недоступен: ${login.message}"
                        }
                    }
                }
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
                        OutlinedButton(onClick = {
                            LocalProfileStore.clear(context)
                            SessionStore.clear(context)
                        }) {
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
 * Настройки. Радиус поиска — настоящая работающая настройка: сохраняется между
 * запусками и сразу применяется к поиску мест.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    currentRadiusMeters: Float,
    onRadiusSelected: (Float) -> Unit,
    searchCategories: Set<NeedCategory>,
    onSearchCategoriesChanged: (Set<NeedCategory>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val eraseScope = rememberCoroutineScope()
    var showAbout by remember { mutableStateOf(false) }
    var showEraseConfirm by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Отказ в системном диалоге — тоже осознанный выбор пользователя, поэтому
        // переключатель остаётся в том положении, которое реально соответствует
        // разрешённому состоянию, а не в том, что нажал палец.
        AppSettings(context).budgetAlertsEnabled = granted
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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

            Text("Категории поиска", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Каждая категория — это несколько запросов к Яндексу при каждом обновлении. " +
                    "Выключите ненужные, чтобы список обновлялся быстрее.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            NeedCategory.entries.forEach { category ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${category.emoji} ${category.displayName}", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = category in searchCategories,
                        onCheckedChange = { checked ->
                            val updated = if (checked) searchCategories + category else searchCategories - category
                            onSearchCategoriesChanged(updated)
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text("Оформление", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
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

            Text("Бюджет", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Уведомлять о превышении лимита", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Разовое уведомление в момент, когда трата переводит категорию за лимит.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var budgetAlertsEnabled by remember { mutableStateOf(AppSettings(context).budgetAlertsEnabled) }
                Switch(
                    checked = budgetAlertsEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            if (BudgetNotifier.hasPermission(context)) {
                                AppSettings(context).budgetAlertsEnabled = true
                                budgetAlertsEnabled = true
                            } else {
                                // Разрешение запросится асинхронно — саму настройку включит
                                // колбэк лаунчера выше, только если пользователь согласится.
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                budgetAlertsEnabled = true
                            }
                        } else {
                            AppSettings(context).budgetAlertsEnabled = false
                            budgetAlertsEnabled = false
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = BudgetExporter.buildShareIntent(context)
                    context.startActivity(Intent.createChooser(intent, "Экспорт бюджета"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Экспортировать бюджет в CSV")
            }

            var importResult by remember { mutableStateOf<String?>(null) }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val result = StatementImporter.import(context, uri)
                    importResult = "Добавлено трат: ${result.added}. " +
                        "Пропущено (поступления и дубли): ${result.skipped}. " +
                        "Не разобрано строк: ${result.failed}."
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Импортировать выписку из CSV")
            }
            importResult?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            Text("Автоматические траты", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Приложение может читать уведомления банков и превращать их в траты. " +
                    "Разрешение выдаётся вручную в системных настройках — программно его " +
                    "запросить нельзя. Уведомления не из банковских приложений отбрасываются " +
                    "сразу и никуда не записываются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            var bankEnabled by remember { mutableStateOf(AppSettings(context).bankNotificationsEnabled) }
            var bankAutoAdd by remember { mutableStateOf(AppSettings(context).bankNotificationsAutoAdd) }
            val hasNotificationAccess = BankNotificationListener.hasAccess(context)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Читать уведомления банков", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = bankEnabled && hasNotificationAccess,
                    onCheckedChange = { checked ->
                        AppSettings(context).bankNotificationsEnabled = checked
                        bankEnabled = checked
                        if (checked && !hasNotificationAccess) {
                            BankNotificationListener.openAccessSettings(context)
                        }
                    }
                )
            }

            if (bankEnabled && !hasNotificationAccess) {
                Text(
                    "Доступ к уведомлениям ещё не выдан. Откройте системные настройки и " +
                        "включите «Жизненный навигатор» в списке.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { BankNotificationListener.openAccessSettings(context) }) {
                    Text("Открыть настройки доступа")
                }
            }

            // --- SMS ---
            var smsEnabled by remember { mutableStateOf(AppSettings(context).bankSmsEnabled) }
            val smsPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                // Настройка включается, только если пользователь реально дал разрешение —
                // переключатель не должен показывать «включено», когда система отказала.
                AppSettings(context).bankSmsEnabled = granted
                smsEnabled = granted
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Читать SMS банков", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Только новые входящие SMS от банков о списаниях. Архив переписки " +
                            "не читается, сообщения не от банков отбрасываются сразу.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = smsEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                        } else {
                            AppSettings(context).bankSmsEnabled = false
                            smsEnabled = false
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Добавлять без подтверждения", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Выключено — распознанные траты ждут подтверждения во вкладке «Бюджет». " +
                            "Переводы спрашиваются всегда: по тексту нельзя понять, оплата это " +
                            "или деньги близким.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = bankAutoAdd,
                    enabled = bankEnabled,
                    onCheckedChange = { checked ->
                        AppSettings(context).bankNotificationsAutoAdd = checked
                        bankAutoAdd = checked
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("Синхронизация с сервером", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))

            val sessionToken by SessionStore.token.collectAsState()
            var syncStatus by remember { mutableStateOf<String?>(null) }
            var syncBusy by remember { mutableStateOf(false) }
            val syncScope = rememberCoroutineScope()

            when {
                !BackendApi.isConfigured -> Text(
                    "Не настроено: добавьте BACKEND_BASE_URL в local.properties, если хотите " +
                        "хранить профиль, согласие и бюджет ещё и на своём сервере (см. папку backend/ " +
                        "в репозитории). Без этого всё продолжает работать только на устройстве.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                sessionToken == null -> Text(
                    "Сервер настроен, но вы не входили через Яндекс ID или VK ID — синхронизация " +
                        "включается вместе с входом.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    Text(
                        "Синхронизация целым снимком: «Отправить» заменяет бюджет на сервере тем, что " +
                            "на этом телефоне; «Получить» — наоборот. Автоматически ничего не отправляется.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val token = sessionToken ?: return@OutlinedButton
                                syncBusy = true
                                syncScope.launch {
                                    val snapshot = BudgetStorage(context).let {
                                        BudgetSnapshot(
                                            income = it.loadIncome(),
                                            limits = it.loadLimits(),
                                            customCategories = it.loadCustomCategories(),
                                            expenses = it.loadExpenses()
                                        )
                                    }
                                    syncStatus = when (val result = BackendApi.pushBudget(token, snapshot)) {
                                        is BackendResult.Success -> "Бюджет отправлен на сервер"
                                        is BackendResult.Failure -> "Не удалось отправить: ${result.message}"
                                    }
                                    syncBusy = false
                                }
                            },
                            enabled = !syncBusy,
                            modifier = Modifier.weight(1f)
                        ) { Text("Отправить") }

                        OutlinedButton(
                            onClick = {
                                val token = sessionToken ?: return@OutlinedButton
                                syncBusy = true
                                syncScope.launch {
                                    when (val result = BackendApi.fetchBudget(token)) {
                                        is BackendResult.Success -> {
                                            val storage = BudgetStorage(context)
                                            val snapshot = result.value
                                            storage.replaceIncome(snapshot.income)
                                            storage.replaceLimits(snapshot.limits)
                                            storage.saveCustomCategories(snapshot.customCategories)
                                            storage.saveExpenses(snapshot.expenses)
                                            syncStatus = "Бюджет получен с сервера. " +
                                                "Откройте вкладку «Бюджет» заново, чтобы увидеть изменения."
                                        }
                                        is BackendResult.Failure -> syncStatus = "Не удалось получить: ${result.message}"
                                    }
                                    syncBusy = false
                                }
                            },
                            enabled = !syncBusy,
                            modifier = Modifier.weight(1f)
                        ) { Text("Получить") }
                    }
                    if (syncBusy) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Синхронизация…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    syncStatus?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Список мест сохраняется между запусками: при открытии приложения показываются " +
                    "результаты прошлого сеанса, пока не обновится геолокация.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) {
                Text("О приложении")
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showEraseConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Удалить мои данные")
            }
        }
    }

    if (showAbout) {
        AboutSheet(onDismiss = { showAbout = false })
    }

    if (showEraseConfirm) {
        AlertDialog(
            onDismissRequest = { showEraseConfirm = false },
            title = { Text("Удалить все данные?") },
            text = {
                Text(
                    "Сотрутся профиль, согласие на обработку данных, весь бюджет и список " +
                        "мест из прошлого сеанса. Действие необратимо. После удаления " +
                        "приложение перезапустится."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Если сервер настроен и есть сессия — просим удалить и там.
                        // Best-effort и намеренно НЕ блокирует локальное удаление:
                        // сервер может быть недоступен, а данные на телефоне должны
                        // стереться в любом случае — это то, что реально в руках
                        // пользователя, в отличие от доступности стороннего сервера.
                        val sessionToken = SessionStore.token.value
                        if (sessionToken != null && BackendApi.isConfigured) {
                            eraseScope.launch { BackendApi.deleteAccount(sessionToken) }
                        }
                        UserDataEraser.eraseAll(context)
                        showEraseConfirm = false
                        (context as? Activity)?.recreate()
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEraseConfirm = false }) { Text("Отмена") }
            }
        )
    }
}

/**
 * «О приложении»: версия, источники данных (обязательная атрибуция по условиям Яндекса)
 * и полный текст политики обработки данных — той же самой, что подписывают при входе.
 * Отдельного хостинга для политики нет, поэтому показываем тот же текст, а не выдуманную
 * ссылку на несуществующую страницу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    var showPolicy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("О приложении", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(4.dp))
            Text(
                "Жизненный навигатор",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Версия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Text("Источники данных", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Карта, поиск мест и маршруты — сервис Яндекс Карты.\n" +
                    "Расписание вокзалов и станций — сервис Яндекс Расписания.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { showPolicy = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Обработка персональных данных")
            }
        }
    }

    if (showPolicy) {
        ModalBottomSheet(onDismissRequest = { showPolicy = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(ConsentText.TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(ConsentText.BODY, style = MaterialTheme.typography.bodySmall)
            }
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
