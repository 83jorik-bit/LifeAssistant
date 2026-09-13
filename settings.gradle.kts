pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Репозиторий Yandex MapKit SDK
        maven { url = uri("https://maven.yandex.net/repository/maven-public/") }
    }
}

rootProject.name = "LifeNavigator"
include(":app")
