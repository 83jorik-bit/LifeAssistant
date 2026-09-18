package com.lifenavigator.app.data.repository

import com.lifenavigator.app.data.model.*
import kotlinx.coroutines.delay

/**
 * MVP-заглушка вместо реальных интеграций (Яндекс Карты, банковские API, маркетплейсы).
 *
 * Когда появятся реальные ключи/договоры с провайдерами — этот класс заменяется на
 * реализацию, которая ходит в сеть (Retrofit/Ktor) и возвращает те же модели данных,
 * так что UI-слой менять не придётся.
 */
object MockRepository {

    suspend fun getSubscriptions(): List<Subscription> {
        delay(150)
        return listOf(
            Subscription("s1", "МТС Premium", NeedCategory.TRANSPORT, 399, BillingCycle.MONTHLY, 4, true),
            Subscription("s2", "IVI", NeedCategory.EDUCATION, 299, BillingCycle.MONTHLY, 2, true),
            Subscription("s3", "Здравсити: автозаказ витаминов", NeedCategory.PHARMACY, 890, BillingCycle.MONTHLY, 9, true),
            Subscription("s4", "Яндекс Плюс", NeedCategory.EDUCATION, 399, BillingCycle.MONTHLY, 15, true),
            Subscription("s5", "Онлайн-курс английского", NeedCategory.EDUCATION, 1990, BillingCycle.MONTHLY, 20, true),
            Subscription("s6", "Папа Джонс: клуб скидок", NeedCategory.CAFE, 199, BillingCycle.YEARLY, 120, false)
        )
    }

    /**
     * Очень простой мок "ИИ-помощника": подбирает готовый ответ по ключевым словам.
     * В реальной версии здесь будет вызов LLM с контекстом бюджета/подписок пользователя.
     */
    suspend fun getAssistantReply(userText: String): String {
        delay(400)
        val text = userText.lowercase()
        return when {
            "аптек" in text -> "Ближайшая аптека с онлайн-оплатой — «Здравсити», 0.4 км от вас, рейтинг 4.7. Есть доставка и автозаказ."
            "подписк" in text -> "У вас 5 активных подписок на 3376 ₽/мес. Скоро спишется IVI (через 2 дня) — хотите отменить или сохранить?"
            "бюджет" in text || "трат" in text -> "По категории «Аптеки» вы уже превысили лимит на 650 ₽. Остальные категории в норме, кроме «Кафе» — почти на пределе."
            "маршрут" in text -> "Могу построить маршрут: аптека → продукты → пункт выдачи. Ближайшие точки — на карте, все с онлайн-оплатой."
            else -> "Пока я работаю на тестовых данных, но в реальной версии смогу планировать бюджет, напоминать о подписках и подбирать маршруты по вашим потребностям."
        }
    }
}
