package com.lifenavigator.app.data.money

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Что оказалось в отсканированном QR. */
sealed class ScannedQr {
    /**
     * Кассовый чек по формату ФНС. Сумма и дата лежат прямо в самом QR —
     * поэтому записать трату можно сразу, не обращаясь ни к какому серверу.
     */
    data class Receipt(val amountRub: Int, val date: LocalDate, val raw: String) : ScannedQr()

    /** QR для оплаты по СБП: ссылка, которую открывает приложение банка. */
    data class PaymentLink(val url: String) : ScannedQr()

    data class Unknown(val raw: String) : ScannedQr()
}

/**
 * Разбор QR-кодов.
 *
 * Чек ФНС выглядит так:
 *   t=20260920T1830&s=1234.56&fn=9999…&i=12345&fp=987654321&n=1
 * где t — дата и время, s — сумма в рублях с копейками.
 *
 * Важно: состав покупки (какие именно товары) в QR НЕ содержится — там только
 * итоговая сумма и реквизиты чека. Чтобы получить перечень товаров, нужен запрос
 * в API ФНС с отдельной регистрацией. Поэтому трату записываем по сумме,
 * а детализацию оставляем на будущее.
 */
object QrParser {

    private val RECEIPT_DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")
    )

    fun parse(raw: String): ScannedQr {
        val text = raw.trim()

        // Платёжный QR СБП — обычная ссылка, её открывает банковское приложение.
        if (text.startsWith("https://qr.nspk.ru", ignoreCase = true) ||
            text.startsWith("https://sbp.nspk.ru", ignoreCase = true)
        ) {
            return ScannedQr.PaymentLink(text)
        }

        val params = text.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
            }
            .toMap()

        val sum = params["s"]?.replace(',', '.')?.toDoubleOrNull()
        val time = params["t"]
        if (sum != null && time != null && params.containsKey("fn")) {
            val date = parseReceiptDate(time)
            // Округляем до рубля: бюджет в приложении ведётся в целых рублях.
            return ScannedQr.Receipt(amountRub = Math.round(sum).toInt(), date = date, raw = text)
        }

        return ScannedQr.Unknown(text)
    }

    private fun parseReceiptDate(value: String): LocalDate {
        RECEIPT_DATE_FORMATS.forEach { format ->
            runCatching { return LocalDateTime.parse(value, format).toLocalDate() }
        }
        // Формат времени в чеке бывает нестандартным — тогда считаем чек сегодняшним,
        // это честнее, чем отбросить трату целиком из-за разбора даты.
        return LocalDate.now()
    }
}
