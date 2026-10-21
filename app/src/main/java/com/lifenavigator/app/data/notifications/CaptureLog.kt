package com.lifenavigator.app.data.notifications

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Одно событие сбора: откуда пришло и что с ним стало. */
data class CaptureEvent(
    val time: String,
    /** PUSH или SMS. */
    val source: String,
    /** Пакет приложения или отправитель SMS. */
    val origin: String,
    /** Что произошло: «распознано 350 ₽», «банк, но не распознано», «не банк». */
    val verdict: String
)

/**
 * Журнал диагностики сбора операций — отвечает на вопрос «почему не фиксируется».
 *
 * Разработчик не видит телефон пользователя, а цепочка длинная: доступ → сервис →
 * фильтр банка → разбор → очередь. Журнал показывает, на каком звене она рвётся.
 *
 * Приватность: текст сообщений НЕ сохраняется — только источник и итог. События от
 * банков пишутся всегда (их приложение и так обрабатывает). Имена прочих приложений —
 * только в режиме диагностики, который включается вручную: иначе нарушилось бы
 * обещание из настроек, что уведомления не от банков никуда не записываются.
 * Всё хранится только на устройстве и стирается вместе с остальными данными.
 */
object CaptureLog {

    private const val MAX_EVENTS = 20
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

    fun add(context: Context, source: String, origin: String, verdict: String) {
        val events = load(context).toMutableList()
        events.add(0, CaptureEvent(LocalDateTime.now().format(TIME_FORMAT), source, origin, verdict))
        val array = JSONArray()
        events.take(MAX_EVENTS).forEach { event ->
            array.put(
                JSONObject()
                    .put("time", event.time)
                    .put("source", event.source)
                    .put("origin", event.origin)
                    .put("verdict", event.verdict)
            )
        }
        prefs(context).edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    fun load(context: Context): List<CaptureEvent> {
        val raw = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                CaptureEvent(
                    time = item.optString("time"),
                    source = item.optString("source"),
                    origin = item.optString("origin"),
                    verdict = item.optString("verdict")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Итог разбора одной строкой для журнала. */
    fun verdictOf(payment: ParsedPayment?): String = when {
        payment == null -> "банк, но текст не распознан как трата"
        payment.kind == PaymentKind.TRANSFER -> "перевод ${payment.amountRub} ₽ → на вопрос"
        payment.kind == PaymentKind.SUBSCRIPTION -> "подписка ${payment.subscriptionService}, ${payment.amountRub} ₽"
        else -> "покупка ${payment.amountRub} ₽" + (payment.merchant?.let { ", $it" } ?: "")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("capture_log", Context.MODE_PRIVATE)

    private const val KEY_EVENTS = "events_json"
}
