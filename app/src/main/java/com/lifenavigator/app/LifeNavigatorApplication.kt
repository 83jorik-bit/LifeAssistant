package com.lifenavigator.app

import android.app.Application
import com.yandex.mapkit.MapKitFactory

class LifeNavigatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Ключ подставляется из local.properties при сборке (см. app/build.gradle.kts).
        // Если ключ пустой — карта не загрузится, MapKit покажет соответствующую ошибку в логах.
        MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY)
        MapKitFactory.initialize(this)
        // Отдельного SearchFactory.initialize() в текущей версии SDK нет (это был метод
        // старой версии 3.x) — модуль поиска инициализируется автоматически вместе с MapKit,
        // достаточно SearchFactory.getInstance() в момент первого использования (см. YandexSearchRepository).
    }
}
