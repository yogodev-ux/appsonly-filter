// TEST ONLY: a stand-in for an "allowed app" that misbehaves. The escape test
// (scripts/escape-test.sh) allows it, then makes it open other apps' screens inside its
// own task, the way a "Rate us" button or a help link inside a real app would.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.appsonly.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.appsonly.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { debug { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
