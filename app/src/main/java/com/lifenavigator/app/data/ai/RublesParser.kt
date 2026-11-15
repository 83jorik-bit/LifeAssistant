package com.lifenavigator.app.data.ai

import java.util.Locale

/**
 * Независимый парсер денежных сумм в рублях.
 */
object RublesParser {

    private val unicodeStart = "(?<![\\p{L}\\p{N}_])"
    private val unicodeEnd = "(?![\\p{L}\\p{N}_])"

    fun parse(text: String): Int? {
        val normalized = text.lowercase(Locale.ROOT)

        // Числовая форма: 2 тысячи, 2 тыс., 2 тысячи 500.
        val thousands = Regex(
            unicodeStart +
                "(\\d{1,3}(?:[ \\u00A0]\\d{3})*|\\d+)\\s*" +
                "(тыс(?:яч(?:а|и)?)?\\.?|тысяч(?:а|и)?)" +
                unicodeEnd
        ).find(normalized)

        if (thousands != null) {
            val base = thousands.groupValues[1]
                .replace(" ", "")
                .replace("\u00A0", "")
                .toLongOrNull() ?: return null

            val extra = trailingRemainder(
                normalized.substring(thousands.range.last + 1)
            )

            return combineThousands(base, extra)
        }

        // Словесная форма: две тысячи, три тысячи и т.д.
        val words = Regex(
            unicodeStart +
                "(одна|одну|один|две|два|три|четыре|" +
                "пять|шесть|семь|восемь|девять|десять)" +
                "\\s+тысяч(?:а|и)?" +
                unicodeEnd
        ).find(normalized)

        if (words != null) {
            val base = when (words.groupValues[1]) {
                "одна", "одну", "один" -> 1L
                "две", "два" -> 2L
                "три" -> 3L
                "четыре" -> 4L
                "пять" -> 5L
                "шесть" -> 6L
                "семь" -> 7L
                "восемь" -> 8L
                "девять" -> 9L
                else -> 10L
            }

            val extra = trailingRemainder(
                normalized.substring(words.range.last + 1)
            )

            return combineThousands(base, extra)
        }

        // Обычные и сгруппированные числовые суммы.
        val number = Regex(
            unicodeStart +
                "(\\d{1,3}(?:[ \\u00A0]\\d{3})+|\\d{1,9})" +
                unicodeEnd
        ).find(normalized) ?: return null

        val value = number.groupValues[1]
            .replace(" ", "")
            .replace("\u00A0", "")
            .toIntOrNull()

        return value?.takeIf { it > 0 }
    }

    private fun trailingRemainder(text: String): Long {
        val match = Regex(
            "^\\s*(\\d{1,3})(?![\\p{L}\\p{N}_])"
        ).find(text) ?: return 0L

        return match.groupValues[1].toLongOrNull() ?: 0L
    }

    private fun combineThousands(base: Long, extra: Long): Int? {
        if (base <= 0L || extra !in 0L..999L) return null

        val maxBase = (Int.MAX_VALUE.toLong() - extra) / 1000L

        if (base > maxBase) return null

        return (base * 1000L + extra).toInt()
    }
}