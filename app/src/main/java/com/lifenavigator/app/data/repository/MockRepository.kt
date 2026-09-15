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

    suspend fun getNearbyPlaces(category: NeedCategory? = null): List<PlacePoint> {
        delay(150) // имитация сетевой задержки
        // Координаты — тестовые, разбросаны вокруг центра Москвы.
        // В реальной версии придут от бэкенда/каталога поставщиков вместе с адресом.
        val all = listOf(
            PlacePoint("p1", "Здравсити", NeedCategory.PHARMACY, 0.4, 4.7, 1520, true, true, 650, "ул. Ленина, 12", 55.7530, 37.6215),
            PlacePoint("p2", "Аптека №1", NeedCategory.PHARMACY, 0.9, 4.3, 340, true, false, 500, "пр. Мира, 5", 55.7601, 37.6280),
            PlacePoint("p3", "Пятёрочка", NeedCategory.GROCERY, 0.3, 4.1, 980, true, false, 1200, "ул. Ленина, 8", 55.7508, 37.6165),
            PlacePoint("p4", "ВкусВилл", NeedCategory.GROCERY, 0.7, 4.6, 2100, true, true, 1800, "ул. Садовая, 3", 55.7550, 37.6320),
            PlacePoint("p5", "Клиника Мед-Сити", NeedCategory.CLINIC, 1.2, 4.8, 640, true, true, 2500, "ул. Победы, 21", 55.7455, 37.6100),
            PlacePoint("p6", "СберЗдоровье", NeedCategory.CLINIC, 2.0, 4.5, 1200, true, true, 1900, "онлайн + офис", 55.7680, 37.6400),
            PlacePoint("p7", "Салон Виктория", NeedCategory.BEAUTY, 1.5, 4.4, 210, true, false, 2200, "ул. Гагарина, 15", 55.7400, 37.6250),
            PlacePoint("p8", "Постамат Wildberries", NeedCategory.PICKUP_POINT, 0.2, 4.2, 89, false, false, 0, "ул. Ленина, 10", 55.7515, 37.6190),
            PlacePoint("p9", "Додо Пицца", NeedCategory.CAFE, 0.6, 4.5, 3400, true, true, 900, "пр. Мира, 1", 55.7590, 37.6350),
            PlacePoint("p10", "Кофемания", NeedCategory.CAFE, 0.5, 4.6, 780, true, false, 450, "ул. Садовая, 7", 55.7545, 37.6280)
        )
        return if (category == null) all else all.filter { it.category == category }
    }

    suspend fun getBudgetCategories(): List<BudgetCategory> {
        delay(150)
        return listOf(
            BudgetCategory(NeedCategory.GROCERY, monthlyLimitRub = 25000, spentRub = 18400),
            BudgetCategory(NeedCategory.PHARMACY, monthlyLimitRub = 4000, spentRub = 4650),
            BudgetCategory(NeedCategory.CLINIC, monthlyLimitRub = 6000, spentRub = 2500),
            BudgetCategory(NeedCategory.BEAUTY, monthlyLimitRub = 5000, spentRub = 2200),
            BudgetCategory(NeedCategory.CAFE, monthlyLimitRub = 8000, spentRub = 7100),
            BudgetCategory(NeedCategory.TRANSPORT, monthlyLimitRub = 3500, spentRub = 3500),
            BudgetCategory(NeedCategory.EDUCATION, monthlyLimitRub = 4000, spentRub = 1500)
        )
    }

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
