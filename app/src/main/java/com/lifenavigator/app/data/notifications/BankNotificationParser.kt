package com.lifenavigator.app.data.notifications

import com.lifenavigator.app.data.model.NeedCategory

/**
 * Разобранная трата из банковского уведомления.
 * merchant может быть null: не во всех уведомлениях он есть, и выдумывать его нельзя.
 */
data class ParsedPayment(
    val amountRub: Int,
    val merchant: String?,
    val suggestedCategory: NeedCategory?
)

/**
 * Разбор банковских пуш-уведомлений.
 *
 * Вынесен отдельно от сервиса намеренно: это единственная в приложении логика,
 * которая работает с чужим непредсказуемым текстом, и её нужно уметь править
 * и проверять, не трогая работу с системой уведомлений.
 *
 * Честно о точности: единого формата уведомлений у банков нет, каждый пишет
 * по-своему и меняет формулировки без предупреждения. Поэтому разбор — набор
 * эвристик, а не гарантия. Из-за этого по умолчанию распознанные траты попадают
 * в список на подтверждение, а не сразу в бюджет (см. настройку автодобавления).
 */
object BankNotificationParser {

    /**
     * Пакеты банковских приложений, чьи уведомления вообще рассматриваем.
     * Всё остальное игнорируется прямо на входе: сервис уведомлений технически
     * видит весь поток, и ограничение списком — главная защита приватности здесь.
     */
    val BANK_PACKAGES = setOf(
        "ru.sberbankmobile",                 // Сбербанк
        "ru.sberbank.android",
        "com.idamob.tinkoff.android",        // Т-Банк
        "ru.vtb24.mobilebanking.android",    // ВТБ
        "ru.alfabank.mobile.android",        // Альфа-Банк
        "ru.gazprombank.android.mobilebank.app",
        "ru.psbank.mobile",                  // ПСБ
        "ru.raiffeisen.mobile.new",          // Райффайзен
        "ru.rshb.mbank",                     // Россельхозбанк
        "ru.openbank.mobile",                // Открытие
        "com.yandex.bank",                   // Яндекс Банк
        "ru.mts.money",                      // МТС Банк
        "ru.sovcombank.sovcombank",          // Совкомбанк
        "ru.akbars.mobile",                  // Ак Барс
        "ru.uralsib.mobile"                  // Уралсиб
    )

    /** Слова, по которым понимаем, что это трата, а не поступление. */
    private val DEBIT_WORDS = listOf("покупка", "оплата", "списание", "снятие", "платёж", "платеж")

    /** Слова поступлений — такие уведомления пропускаем, это не трата. */
    private val CREDIT_WORDS = listOf(
        "зачисление", "пополнение", "возврат", "поступление",
        "кэшбэк", "кешбэк", "начисление", "перевод вам"
    )

    /**
     * Сумма: «1 250,50 ₽», «350 р.», «1000 руб».
     * Разделителем тысяч бывает и обычный пробел, и неразрывный — учитываем оба.
     */
    private val AMOUNT_REGEX = Regex(
        """(\d{1,3}(?:[ \u00A0]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(?:₽|руб\.?|р\.|RUB)""",
        RegexOption.IGNORE_CASE
    )

    /** Подсказка категории по названию магазина. Не угадали — оставим пустой. */
    private val CATEGORY_HINTS: List<Pair<List<String>, NeedCategory>> = listOf(
        listOf("пятероч", "pyaterochka", "магнит", "magnit", "перекрёст", "перекрест",
            "perekrest", "лента", "lenta", "дикси", "dixy", "ашан", "auchan",
            "вкусвилл", "vkusvill", "окей", "metro") to NeedCategory.GROCERY,
        listOf("аптек", "apteka", "ригла", "rigla", "здравсити", "апрель",
            "планета здоровья", "farmac") to NeedCategory.PHARMACY,
        listOf("кофе", "coffee", "кафе", "cafe", "ресторан", "пицц", "pizza",
            "burger", "додо", "dodo", "шаурм", "kfc", "вкусно и точка") to NeedCategory.CAFE,
        listOf("аптека36", "клиник", "медцентр", "инвитро", "гемотест", "ситилаб") to NeedCategory.CLINIC,
        listOf("wildberries", "ozon", "озон", "вайлдберриз", "сдэк", "cdek",
            "boxberry", "пункт выдачи") to NeedCategory.PICKUP_POINT,
        listOf("zara", "h&m", "спортмастер", "sportmaster", "обув", "одежд",
            "kari", "befree", "gloria") to NeedCategory.CLOTHING,
        listOf("метро ", "metropolit", "тройка", "проездной", "ржд", "rzd",
            "автобус", "такси") to NeedCategory.TRANSPORT
    )

    /**
     * Пытается разобрать уведомление. Возвращает null, если это не трата:
     * не тот банк, поступление вместо списания, или сумму найти не удалось.
     */
    fun parse(packageName: String, title: String?, text: String?): ParsedPayment? {
        if (packageName !in BANK_PACKAGES) return null

        val full = listOfNotNull(title, text).joinToString(" ").trim()
        if (full.isBlank()) return null
        val lower = full.lowercase()

        // Поступления отсеиваем раньше трат: в тексте вроде «Возврат покупки»
        // встречаются оба слова, и приоритет должен быть у поступления.
        if (CREDIT_WORDS.any { it in lower }) return null
        if (DEBIT_WORDS.none { it in lower }) return null

        val amount = AMOUNT_REGEX.find(full)
            ?.groupValues?.get(1)
            ?.replace(" ", "")
            ?.replace("\u00A0", "")
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?: return null

        // Бюджет хранит суммы целыми рублями, копейки округляем к ближайшему рублю.
        val amountRub = Math.round(amount).toInt()
        if (amountRub <= 0) return null

        val merchant = extractMerchant(full)
        return ParsedPayment(
            amountRub = amountRub,
            merchant = merchant,
            suggestedCategory = merchant?.let { guessCategory(it) } ?: guessCategory(full)
        )
    }

    /**
     * Название магазина: берём кусок после суммы — банки обычно пишут его следом
     * («Покупка 350 ₽ PYATEROCHKA 1234»). Эвристика, поэтому результат
     * показывается пользователю для подтверждения, а не применяется молча.
     */
    private fun extractMerchant(text: String): String? {
        val match = AMOUNT_REGEX.find(text) ?: return null
        val tail = text.substring(match.range.last + 1)
            .substringBefore(". ")
            .substringBefore("Баланс")
            .substringBefore("Доступно")
            .trim(' ', ',', '.', ';', '·', '-')
        return tail.takeIf { it.isNotBlank() && it.length in 2..40 }
    }

    private fun guessCategory(text: String): NeedCategory? {
        val lower = text.lowercase()
        return CATEGORY_HINTS.firstOrNull { (keys, _) -> keys.any { it in lower } }?.second
    }
}
