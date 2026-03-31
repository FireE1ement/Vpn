// Top-level build file
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.22" apply false
    id("com.android.library") version "8.2.2" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

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