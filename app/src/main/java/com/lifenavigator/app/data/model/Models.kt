package com.lifenavigator.app.data.model

/**
 * Категория повседневных потребностей — используется и на карте, и в бюджете.
 */
enum class NeedCategory(val displayName: String, val emoji: String) {
    PHARMACY("Аптеки", "💊"),
    GROCERY("Продукты", "🛒"),
    CLINIC("Клиники", "🏥"),
    BEAUTY("Красота", "💇"),
    PICKUP_POINT("Пункты выдачи", "📦"),
    CAFE("Кафе и рестораны", "☕"),
    CLOTHING("Одежда и обувь", "👕"),
    EDUCATION("Образование", "🎓"),
    TRANSPORT("Транспорт", "🚌")
}

/**
 * Способы оплаты, которые удалось распознать в данных Яндекса.
 * Показываются иконками рядом с названием места.
 */
enum class PaymentMethod(val label: String) {
    CARD("Картой"),
    SBP("СБП"),
    CASH("Наличными")
}

/**
 * Точка на карте потребностей — поставщик услуги/товара рядом с пользователем.
 */
data class PlacePoint(
    val id: String,
    val name: String,
    val category: NeedCategory,
    val distanceKm: Double,
    val rating: Double,
    val reviewsCount: Int,
    val onlinePaymentAvailable: Boolean,
    val hasSubscriptionOption: Boolean,
    val approxAvgCheckRub: Int,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    /** Количество оценок (рейтинг приходит в поле [rating]). 0 — данных нет. */
    val ratingsCount: Int = 0,
    /** Часы работы одной строкой, как их отдаёт Яндекс ("ежедневно, 9:00–21:00"). */
    val workingHours: String? = null,
    /** Телефон в человекочитаемом виде. */
    val phone: String? = null,
    /** Идентификатор организации в Яндекс Картах — нужен, чтобы открыть её карточку. */
    val yandexOid: String? = null,
    /** Рубрики Яндекса одной строкой: «Магазин продуктов», «Парикмахерская, Ногтевая студия». */
    val categoryDescription: String? = null,
    /** Распознанные способы оплаты (см. PaymentMethod). Пустое множество — данных нет. */
    val paymentMethods: Set<PaymentMethod> = emptySet()
)

/**
 * Статья бюджета пользователя.
 */
data class BudgetCategory(
    val category: NeedCategory,
    val monthlyLimitRub: Int,
    val spentRub: Int
) {
    val progress: Float
        get() = if (monthlyLimitRub == 0) 0f else (spentRub.toFloat() / monthlyLimitRub.toFloat()).coerceIn(0f, 1.5f)

    val isOverLimit: Boolean
        get() = spentRub > monthlyLimitRub
}

/**
 * Периодичность списания подписки.
 */
enum class BillingCycle(val displayName: String) {
    MONTHLY("Ежемесячно"),
    EVERY_2_MONTHS("Раз в 2 месяца"),
    YEARLY("Ежегодно")
}

/**
 * Активная подписка / автоплатёж пользователя.
 */
data class Subscription(
    val id: String,
    val serviceName: String,
    val category: NeedCategory,
    val priceRub: Int,
    val billingCycle: BillingCycle,
    val nextChargeDaysLeft: Int,
    val isActive: Boolean
)

/**
 * Сообщение в чате с ИИ-помощником.
 */
data class AssistantMessage(
    val id: String,
    val text: String,
    val isFromUser: Boolean
)
