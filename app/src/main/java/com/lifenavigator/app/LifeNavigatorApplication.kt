package com.lifenavigator.app

import android.app.Application
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.search.SearchFactory

class LifeNavigatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Ключ подставляется из local.properties при сборке (см. app/build.gradle.kts).
        // Если ключ пустой — карта не загрузится, MapKit покажет соответствующую ошибку в логах.
        MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY)
        MapKitFactory.initialize(this)
        // Отдельная инициализация поискового модуля (доступен только в full-версии SDK).
        SearchFactory.initialize(this)
    }
}
