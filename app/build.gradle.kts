import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ читается из local.properties, чтобы никогда не попадать в git.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val mapkitApiKey: String = localProperties.getProperty("MAPKIT_API_KEY") ?: ""
val raspApiKey: String = localProperties.getProperty("RASP_API_KEY") ?: ""
// client_id провайдеров входа. Пусто — кнопка входа показывается неактивной.
val yandexClientId: String = localProperties.getProperty("YANDEX_CLIENT_ID") ?: ""
val vkClientId: String = localProperties.getProperty("VK_CLIENT_ID") ?: ""
// Адрес возврата у Яндекса можно переопределить, если кабинет потребует другой вид.
// У VK ID для Android такого адреса вообще не задаётся — он фиксирован, см. ниже.
val yandexRedirect: String = localProperties.getProperty("YANDEX_REDIRECT_URI") ?: ""
// Параметры РЕЛИЗНОГО ключа подписи. Берутся из local.properties (локально) либо из
// переменных окружения (сборка в CI). В репозиторий релизный ключ не кладётся никогда:
// потеря или утечка этого ключа означает, что обновлять приложение в RuStore
// под тем же package больше не получится.
fun secret(name: String): String? =
    localProperties.getProperty(name) ?: System.getenv(name)

// Адрес собственного сервера (папка backend/). Пусто — приложение работает
// полностью локально, синхронизация просто не предлагается.
val backendBaseUrl: String = secret("BACKEND_BASE_URL") ?: ""

// YandexGPT для ИИ-помощника. Пусто — помощник честно скажет, что не подключён.
val yandexGptApiKey: String = secret("YANDEX_GPT_API_KEY") ?: ""
val yandexGptFolderId: String = secret("YANDEX_GPT_FOLDER_ID") ?: ""

val releaseStoreFile = secret("RELEASE_STORE_FILE")
val releaseStorePassword = secret("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secret("RELEASE_KEY_ALIAS")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

// Номер версии можно задать снаружи, чтобы CI выпускал новую сборку без правки файла.
// RuStore не принимает повторно versionCode, который уже публиковался.
val appVersionCode = (secret("VERSION_CODE") ?: "1").toInt()
val appVersionName = secret("VERSION_NAME") ?: "1.0.0"

android {
    namespace = "com.lifenavigator.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lifenavigator.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "MAPKIT_API_KEY", "\"$mapkitApiKey\"")
        buildConfigField("String", "RASP_API_KEY", "\"$raspApiKey\"")
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$yandexClientId\"")
        buildConfigField("String", "VK_CLIENT_ID", "\"$vkClientId\"")
        buildConfigField("String", "YANDEX_REDIRECT_URI", "\"$yandexRedirect\"")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "YANDEX_GPT_API_KEY", "\"$yandexGptApiKey\"")
        buildConfigField("String", "YANDEX_GPT_FOLDER_ID", "\"$yandexGptFolderId\"")

        // VK ID для Android не даёт задать свой redirect_uri — он вычисляется как
        // vk{clientId}://vk.ru/blank.html. Схема статична в манифесте, а client_id
        // известен только на сборке, поэтому подставляем его через manifestPlaceholders.
        // Пока клиент не задан, ставим заведомо нерабочую схему вместо пустой строки —
        // пустая схема в манифесте не проходит валидацию и ломает сборку целиком.
        manifestPlaceholders["vkRedirectScheme"] =
            if (vkClientId.isNotBlank()) "vk$vkClientId" else "vk-not-configured"
    }

    // Постоянный ключ подписи для debug-сборок.
    // Без него Gradle подписывает каждую сборку локальным ~/.android/debug.keystore,
    // который на вашем компьютере и на раннере GitHub Actions разный — из-за этого
    // Android отказывается ставить новую версию поверх старой ("signatures do not match")
    // и приложение приходится удалять. С общим ключом обновления просто накатываются.
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }

        getByName("debug") {
            storeFile = rootProject.file("keystore/lifenavigator-debug.keystore")
            storePassword = "lifenavigator"
            keyAlias = "lifenavigator"
            keyPassword = "lifenavigator"
        }
    }

    buildTypes {
        release {
            // Сжатие кода пока выключено намеренно: MapKit активно использует нативный код
            // и рефлексию, и включать R8 без проверки на живом устройстве рискованно —
            // приложение соберётся, но может падать уже у пользователей.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Если ключ не задан, release просто не подписывается — тогда собирать
            // релиз бессмысленно, RuStore неподписанный файл не примет.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Yandex MapKit SDK, full-версия: карта, поиск по организациям, геокодирование.
    implementation("com.yandex.android:maps.mobile:4.42.0-full")
    // Google Play Services сознательно НЕ подключены. Это важно именно для RuStore:
    // приложение ставят в том числе на устройства без сервисов Google, где
    // FusedLocationProviderClient просто не работает. Геолокацию даёт сам MapKit
    // (MapKitFactory.getInstance().createLocationManager()) — см. MapScreen.
    implementation("androidx.compose.runtime:runtime-livedata:1.6.8")

    // Сканер QR. Взят ZXing, а не ML Kit от Google: ML Kit тянет сервисы Google,
    // от которых мы отказались ради RuStore (см. раздел про геолокацию).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
