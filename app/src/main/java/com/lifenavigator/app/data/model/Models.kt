package com.lifenavigator.app.data.model

/**
 * Категория повседневных потребностей — используется и на карте, и в бюджете.
 */
/**
 * Категория повседневных потребностей.
 *
 * Порядок объявления = порядок чипов на экране и в списках.
 * Цвет используется единообразно: кайма метки на карте, подпись метки и надпись категории.
 */
enum class NeedCategory(val displayName: String, val emoji: String, val colorArgb: Long) {
    GROCERY("Продукты", "🛒", 0xFF1A6E8E),          // тёмно-голубой
    CAFE("Кафе и рестораны", "☕", 0xFFEF6C00),      // оранжевый
    PHARMACY("Аптеки", "💊", 0xFF2E9E4F),           // зелёный
    CLOTHING("Одежда и обувь", "👕", 0xFF4FA8D8),   // светло-голубой
    BEAUTY("Красота", "💇", 0xFF7E57C2),            // фиалковый
    PICKUP_POINT("Пункты выдачи", "📦", 0xFF795548), // коричневый
    ATM("Банкоматы", "🏧", 0xFF37474F),             // тёмно-графитовый
    TRANSPORT("Транспорт", "🚌", 0xFF1A3E8C),       // тёмно-синий
    EDUCATION("Образование", "🎓", 0xFF1B5E20),     // тёмно-зелёный
    CLINIC("Клиники", "🏥", 0xFFA14A54)             // светло-бордовый
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
    /** Работает круглосуточно (определяется по тексту графика работы от Яндекса). */
    val isOpen24: Boolean = false,
    /**
     * Собственный URI объекта в Яндексе (вида `ymapsbm1://…`). Для остановки это ссылка
     * именно на карточку остановки, а не на организацию — по ней открываются маршруты.
     */
    val yandexUri: String? = null
)

/**
 * Статья бюджета пользователя.
 */
/**
 * Одна трата, введённая пользователем.
 *
 * Дата хранится числом дней от начала эпохи: так её просто сохранять и сравнивать,
 * не таща в хранилище форматы и часовые пояса.
 */
data class Expense(
    val id: String,
    val category: NeedCategory,
    val amountRub: Int,
    val note: String?,
    val dateEpochDay: Long
)

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
