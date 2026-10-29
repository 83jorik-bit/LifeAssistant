pluginManagement {
    repositories {
        google()
        // Reduce direct Maven Central traffic via the public Google Cloud Storage mirror.
        // Keep Maven Central as fallback for artifacts not yet available on the mirror.
        maven {
            name = "GoogleCloudMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            name = "GoogleCloudMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
    }
}

rootProject.name = "LifeNavigator"
include(":app")
