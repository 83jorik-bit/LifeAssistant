package com.lifenavigator.app.data.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.lifenavigator.app.data.local.AppSettings

/**
 * Приём банковских SMS.
 *
 * Используется RECEIVE_SMS (только новые входящие), а не READ_SMS (доступ ко всему
 * архиву переписки). Нам нужны лишь свежие сообщения банка о списаниях — читать
 * историю личных сообщений незачем, и меньший доступ проще обосновать при модерации
 * RuStore: SMS там — «чувствительное» разрешение, требующее декларации.
 *
 * Как и с пушами: отправитель сверяется со списком банков ПЕРВЫМ, до чтения текста.
 * Сообщения не от банков не разбираются и никуда не записываются.
 */
class BankSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AppSettings(context).bankSmsEnabled) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress
        if (!BankNotificationParser.isBankSmsSender(sender)) return

        // Длинное SMS приходит частями — склеиваем, иначе сумма и магазин
        // могут оказаться в разных частях и разбор их не найдёт.
        val body = messages.joinToString("") { it.messageBody.orEmpty() }

        val payment = BankNotificationParser.parseSms(sender, body) ?: return
        PaymentIntake.accept(context, payment, source = "SMS")
    }
}
