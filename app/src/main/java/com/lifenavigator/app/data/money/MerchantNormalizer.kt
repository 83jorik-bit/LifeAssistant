package com.lifenavigator.app.data.money

/**
 * Приводит название магазина из банковского уведомления к одному виду.
 *
 * Зачем: банк пишет «PYATEROCHKA 5432», «PYATEROCHKA 1120», «Пятёрочка» — для человека
 * это одна сеть, а для группировки три разные строки. Без нормализации блок
 * «где вы тратите» развалился бы на десятки строк по номерам кассовых терминалов.
 *
 * Как устроено: сначала сверяемся со справочником известных сетей (латиница банков →
 * привычное название), иначе чистим строку от юрформы (ООО, ИП), номеров и значков.
 * Проверено на типовых строках: номера терминалов, юрформы, латиница, кириллица.
 */
object MerchantNormalizer {

    /**
     * Названия городов, содержащие название сети. Без этого «KAFE UYUT MAGNITOGORSK»
     * превратилось бы в «Магнит»: слово «magnit» стоит внутри названия города.
     * Банки дописывают город к названию магазина не всегда, но достаточно часто.
     */
    private val CITY_TRAPS = listOf("magnitogorsk", "магнитогорск")

    private val CHAINS: List<Pair<List<String>, String>> = listOf(
        listOf("pyaterochka", "пятероч", "пятёроч", "5ka") to "Пятёрочка",
        listOf("magnit", "магнит") to "Магнит",
        listOf("perekrestok", "перекрест", "перекрёст") to "Перекрёсток",
        listOf("vkusvill", "вкусвилл") to "ВкусВилл",
        listOf("dixy", "дикси") to "Дикси",
        listOf("lenta", "лента") to "Лента",
        listOf("auchan", "ашан") to "Ашан",
        listOf("chizhik", "чижик") to "Чижик",
        listOf("svetofor", "светофор") to "Светофор",
        listOf("rigla", "ригла") to "Ригла",
        listOf("aprel", "апрель") to "Аптека Апрель",
        listOf("zdravcity", "здравсити") to "Здравсити",
        listOf("36,6", "36.6") to "Аптека 36,6",
        listOf("dodo", "додо") to "Додо Пицца",
        listOf("vkusno i tochka", "вкусно и точка", "vkusnoitochka") to "Вкусно — и точка",
        listOf("rostics", "ростикс", "kfc") to "Ростикс",
        listOf("shokoladnica", "шоколадница") to "Шоколадница",
        listOf("cofix", "кофикс") to "Cofix",
        listOf("wildberries", "вайлдберриз") to "Wildberries",
        listOf("ozon", "озон") to "Ozon",
        listOf("sportmaster", "спортмастер") to "Спортмастер"
    )

    private val LEGAL_FORMS = Regex(
        """(?<![\p{L}\d])(ooo|ооо|oao|оао|pao|пао|ao|ао|ip|ип|zao|зао)(?![\p{L}\d])\.?""",
        RegexOption.IGNORE_CASE
    )
    private val NUMBERS = Regex("""(?<![\p{L}\d])\d{2,}(?![\p{L}\d])""")

    /**
     * Возвращает узнаваемое название или null, если в строке нет ничего осмысленного
     * (например, одни цифры терминала).
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        var lower = " " + raw.lowercase() + " "
        CITY_TRAPS.forEach { city -> lower = lower.replace(city, " ") }

        CHAINS.firstOrNull { (keys, _) -> keys.any { it in lower } }?.let { return it.second }

        var cleaned = raw
        CITY_TRAPS.forEach { city -> cleaned = cleaned.replace(city, " ", ignoreCase = true) }
        cleaned = cleaned
            .replace(LEGAL_FORMS, " ")
            .replace(Regex("[*#№]"), " ")
            .replace(NUMBERS, " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', ',', '-', ';', ':')

        if (cleaned.length < 2) return null
        // Банки пишут КАПСОМ — для чтения привычнее обычный регистр.
        return if (cleaned == cleaned.uppercase()) {
            cleaned.lowercase().split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
        } else {
            cleaned
        }
    }
}
