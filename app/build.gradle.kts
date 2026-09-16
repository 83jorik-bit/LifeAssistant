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

android {
    namespace = "com.lifenavigator.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lifenavigator.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"

        buildConfigField("String", "MAPKIT_API_KEY", "\"$mapkitApiKey\"")
        buildConfigField("String", "RASP_API_KEY", "\"$raspApiKey\"")
    }

    // Постоянный ключ подписи для debug-сборок.
    // Без него Gradle подписывает каждую сборку локальным ~/.android/debug.keystore,
    // который на вашем компьютере и на раннере GitHub Actions разный — из-за этого
    // Android отказывается ставить новую версию поверх старой ("signatures do not match")
    // и приложение приходится удалять. С общим ключом обновления просто накатываются.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/lifenavigator-debug.keystore")
            storePassword = "lifenavigator"
            keyAlias = "lifenavigator"
            keyPassword = "lifenavigator"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // Yandex MapKit SDK (нативные карты) + Google Play Services Location для геолокации
    // Full-версия: даёт карту + поиск по организациям, геокодирование, маршрутизацию (нужно для реальных точек).
    implementation("com.yandex.android:maps.mobile:4.42.0-full")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.compose.runtime:runtime-livedata:1.6.8")

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
