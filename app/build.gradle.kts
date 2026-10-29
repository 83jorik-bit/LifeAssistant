import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun configValue(name: String): String =
    localProperties.getProperty(name) ?: System.getenv(name) ?: ""

fun secret(name: String): String? =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val mapkitApiKey = configValue("MAPKIT_API_KEY")
val raspApiKey = configValue("RASP_API_KEY")
val yandexClientId = configValue("YANDEX_CLIENT_ID")
val vkClientId = configValue("VK_CLIENT_ID")
val yandexRedirect = configValue("YANDEX_REDIRECT_URI")
val backendBaseUrl = configValue("BACKEND_BASE_URL")
val yandexGptFolderId = configValue("YANDEX_GPT_FOLDER_ID")

// API keys included in BuildConfig are extractable from the APK. Do not inject
// YANDEX_GPT_API_KEY into the mobile app; cloud calls must go through a backend.
val releaseStoreFile = secret("RELEASE_STORE_FILE")
val releaseStorePassword = secret("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secret("RELEASE_KEY_ALIAS")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

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
        buildConfigField("String", "YANDEX_GPT_FOLDER_ID", "\"$yandexGptFolderId\"")
        manifestPlaceholders["vkRedirectScheme"] =
            if (vkClientId.isNotBlank()) "vk$vkClientId" else "vk-not-configured"
    }

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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("com.yandex.android:maps.mobile:4.42.0-full")
    implementation("androidx.compose.runtime:runtime-livedata:1.6.8")
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
