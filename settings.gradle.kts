pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // ИЗМЕНЕНО: разрешаем проектные репозитории для совместимости
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "UniversalTunnel"
include(":app")

// Enable Gradle configuration cache
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
