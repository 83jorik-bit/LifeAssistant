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
}
