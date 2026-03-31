// Top-level build file - ИСПРАВЛЕННАЯ ВЕРСИЯ
// Убраны репозитории из buildscript - они уже в settings.gradle.kts

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.22" apply false
    id("com.android.library") version "8.2.2" apply false
}

// УБРАНО: buildscript с repositories - это вызывало конфликт
// Все репозитории теперь только в settings.gradle.kts

// Clean task
tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}

// Version constants
extra["compileSdkVersion"] = 34
extra["minSdkVersion"] = 26
extra["targetSdkVersion"] = 34
extra["kotlinVersion"] = "1.9.22"
extra["composeCompilerVersion"] = "1.5.8"
