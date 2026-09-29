package com.lifenavigator.app.data.notifications

import com.lifenavigator.app.data.model.NeedCategory

/** Вид операции. От него зависит, куда она попадёт и что спросить у пользователя. */
enum class PaymentKind {
    /** Покупка в магазине, кафе и т. п. */
    PURCHASE,

    /**
     * Исходящий перевод. По тексту уведомления нельзя понять, что это: оплата услуги
     * (мастеру, репетитору) или перевод родственнику и между своими счетами.
     * Поэтому такие операции всегда уходят на вопрос пользователю, а не в бюджет молча.
     */
    TRANSFER,

    /** Списание за известную подписку (Яндекс Плюс, Кинопоиск, Okko и т. п.). */
    SUBSCRIPTION
}

/**
 * Разобранная операция из банковского уведомления или SMS.
 * Необязательные поля бывают null: не каждый банк их пишет, выдумывать нельзя.
 */
data class ParsedPayment(
    val kind: PaymentKind,
    val amountRub: Int,
    val merchant: String?,
    val suggestedCategory: NeedCategory?,
    /** Последние 4 цифры карты или счёта, если банк их указал. */
    val cardTail: String?,
    /** Название подписки, если списание узнано как подписка. */
    val subscriptionService: String?
)

/**
 * Разбор банковских пуш-уведомлений и SMS.
 *
 * Логика вынесена отдельно от сервисов намеренно: это единственное место, работающее
 * с чужим непредсказуемым текстом, и его нужно уметь проверять и править, не трогая
 * работу с системой. Каждое правило ниже проверено на типовых формулировках банков.
 *
 * Честно о точности: единого формата у банков нет, и они меняют формулировки без
 * предупреждения. Это эвристика — поэтому по умолчанию операции идут на подтверждение.
 */
object BankNotificationParser {

    /**
     * Пакеты банковских приложений. Всё остальное отбрасывается прямо на входе:
     * сервис уведомлений видит весь поток, и этот список — главная защита приватности.
     */
    val BANK_PACKAGES = setOf(
        "ru.sberbankmobile", "ru.sberbank.android",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.gazprombank.android.mobilebank.app",
        "ru.psbank.mobile",
        "ru.raiffeisen.mobile.new",
        "ru.rshb.mbank",
        "ru.openbank.mobile",
        "com.yandex.bank",
        "ru.mts.money",
        "ru.sovcombank.sovcombank",
        "ru.akbars.mobile",
        "ru.uralsib.mobile",
        "ru.pochta.bank"
    )

    /**
     * Отправители банковских SMS. У SMS нет имени пакета — только короткое имя
     * отправителя, которое банк регистрирует у операторов. Сравнение без учёта регистра.
     * Список неполный: небольшие банки шлют с иных имён — их легко добавить сюда.
     */
    val BANK_SMS_SENDERS = setOf(
        "900", "sberbank", "tinkoff", "t-bank", "tbank", "vtb", "alfa-bank", "alfabank",
        "gazprombank", "psb", "raiffeisen", "rshb", "otkritie", "yandexbank", "mts-bank",
        "mtsbank", "sovcombank", "pochtabank", "akbars", "uralsib"
    )

    /** Поступления — всегда пропускаем. Проверяются ПЕРВЫМИ, см. parseText. */
    private val CREDIT_WORDS = listOf(
        "зачисление", "пополнение", "возврат", "поступление", "поступил",
        "входящий перевод", "кэшбэк", "кешбэк", "начисление", "перевод вам", "вам перевели"
    )

    /** Признаки исходящего перевода. */
    private val TRANSFER_WORDS = listOf("перевод", "сбп")

    /** Признаки покупки/списания. */
    private val DEBIT_WORDS = listOf("покупка", "оплата", "списание", "снятие", "платёж", "платеж")

    private val AMOUNT_REGEX = Regex(
        """(\d{1,3}(?:[ \u00A0]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(?:₽|руб\.?|р\.|RUB)""",
        RegexOption.IGNORE_CASE
    )

    /** Хвост карты/счёта: «*1234», «Карта *1234», «MIR-5678», «счёт *4321». */
    private val CARD_REGEX = Regex(
        """(?:\*|карт[аеуы]?\s*\*?|MIR-?|VISA-?|MC-?|сч[её]т[а]?\s*\*?)(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Известные подписки. Ключи со «\b» проверяются как целое слово — иначе
     * «ivi» совпало бы внутри «vivid», а «start» — внутри «RESTART CAFE».
     * Поэтому Start распознаётся только по «start.ru».
     */
    private val SUBSCRIPTIONS: List<Pair<List<String>, String>> = listOf(
        listOf("yandex plus", "яндекс плюс", "yandex.plus", "yandex*plus", "plus.yandex") to "Яндекс Плюс",
        listOf("kinopoisk", "кинопоиск") to "Кинопоиск",
        listOf("sberprime", "сберпрайм", "sber prime") to "СберПрайм",
        listOf("okko") to "Okko",
        listOf("ivi.ru", "\\bivi\\b", "\\bиви\\b") to "Иви",
        listOf("\\bwink\\b") to "Wink",
        listOf("\\bkion\\b", "\\bкион\\b") to "Кион",
        listOf("premier.one", "\\bpremier\\b") to "Premier",
        listOf("start.ru") to "Start",
        listOf("ozon premium") to "Ozon Premium",
        listOf("mts premium", "мтс premium") to "МТС Premium",
        listOf("vk combo", "vk музыка", "vk music", "\\bboom\\b") to "VK Combo / VK Музыка",
        listOf("litres", "литрес") to "Литрес",
        listOf("amediateka", "амедиатека") to "Amediateka"
    )

    private val CATEGORY_HINTS: List<Pair<List<String>, NeedCategory>> = listOf(
        listOf("пятероч", "pyaterochka", "магнит", "magnit", "перекрёст", "перекрест",
            "perekrest", "лента", "lenta", "дикси", "dixy", "ашан", "auchan",
            "вкусвилл", "vkusvill", "окей", "metro") to NeedCategory.GROCERY,
        listOf("аптек", "apteka", "ригла", "rigla", "здравсити", "апрель",
            "планета здоровья", "farmac") to NeedCategory.PHARMACY,
        listOf("кофе", "coffee", "кафе", "cafe", "ресторан", "пицц", "pizza",
            "burger", "додо", "dodo", "шаурм", "kfc", "вкусно и точка") to NeedCategory.CAFE,
        listOf("клиник", "медцентр", "инвитро", "гемотест", "ситилаб") to NeedCategory.CLINIC,
        listOf("wildberries", "ozon", "озон", "вайлдберриз", "сдэк", "cdek",
            "boxberry", "пункт выдачи") to NeedCategory.PICKUP_POINT,
        listOf("zara", "h&m", "спортмастер", "sportmaster", "обув", "одежд",
            "kari", "befree", "gloria") to NeedCategory.CLOTHING,
        listOf("метрополитен", "metropolit", "тройка", "проездной", "ржд", "rzd",
            "автобус", "такси") to NeedCategory.TRANSPORT
    )

    /** Разбор пуш-уведомления банковского приложения. */
    fun parse(packageName: String, title: String?, text: String?): ParsedPayment? {
        if (packageName !in BANK_PACKAGES) return null
        return parseText(listOfNotNull(title, text).joinToString(" "))
    }

    /** Разбор SMS. Отправитель сверяется со списком банковских имён. */
    fun parseSms(sender: String?, body: String?): ParsedPayment? {
        val from = sender?.trim()?.lowercase() ?: return null
        if (BANK_SMS_SENDERS.none { from == it || from.contains(it) }) return null
        return parseText(body.orEmpty())
    }

    fun isBankSmsSender(sender: String?): Boolean {
        val from = sender?.trim()?.lowercase() ?: return false
        return BANK_SMS_SENDERS.any { from == it || from.contains(it) }
    }

    private fun parseText(raw: String): ParsedPayment? {
        val full = raw.trim()
        if (full.isBlank()) return null
        val lower = full.lowercase()

        // Поступления отсеиваем раньше всего: «Возврат покупки» содержит и «покупку»,
        // а «Вам поступил перевод» — и «перевод»; приоритет должен быть у поступления.
        if (CREDIT_WORDS.any { it in lower }) return null

        val amountRub = AMOUNT_REGEX.find(full)
            ?.groupValues?.get(1)
            ?.replace(" ", "")?.replace("\u00A0", "")?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.let { Math.round(it).toInt() }
            ?.takeIf { it > 0 }
            ?: return null

        val subscription = detectSubscription(lower)
        val isTransfer = TRANSFER_WORDS.any { it in lower } &&
            // «Оплата товаров по СБП» — это покупка, а не перевод человеку.
            DEBIT_WORDS.none { it in lower && it != "платёж" && it != "платеж" }

        val kind = when {
            subscription != null -> PaymentKind.SUBSCRIPTION
            isTransfer -> PaymentKind.TRANSFER
            DEBIT_WORDS.any { it in lower } -> PaymentKind.PURCHASE
            else -> return null
        }

        val merchant = if (kind == PaymentKind.TRANSFER) null else extractMerchant(full)
        return ParsedPayment(
            kind = kind,
            amountRub = amountRub,
            merchant = merchant,
            suggestedCategory = if (kind == PaymentKind.PURCHASE) {
                merchant?.let { guessCategory(it) } ?: guessCategory(full)
            } else {
                null
            },
            cardTail = CARD_REGEX.find(full)?.groupValues?.get(1),
            subscriptionService = subscription
        )
    }

    private fun detectSubscription(lower: String): String? =
        SUBSCRIPTIONS.firstOrNull { (keys, _) ->
            keys.any { key ->
                if (key.startsWith("\\b")) Regex(key).containsMatchIn(lower) else key in lower
            }
        }?.second

    /**
     * Название магазина: кусок после суммы, до служебных слов банка.
     * Эвристика — поэтому результат показывается пользователю для подтверждения.
     */
    private fun extractMerchant(text: String): String? {
        val match = AMOUNT_REGEX.find(text) ?: return null
        val tail = text.substring(match.range.last + 1)
            .substringBefore(". ")
            .substringBefore("Баланс")
            .substringBefore("Доступно")
            .substringBefore("Карта")
            .substringBefore("карта")
            .replace(CARD_REGEX, "")
            .trim(' ', ',', '.', ';', '·', '-')
        return tail.takeIf { it.isNotBlank() && it.length in 2..40 }
    }

    private fun guessCategory(text: String): NeedCategory? {
        val lower = text.lowercase()
        return CATEGORY_HINTS.firstOrNull { (keys, _) -> keys.any { it in lower } }?.second
    }
}
