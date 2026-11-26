// TEST ONLY: a tiny app that owns a fake account, so CI can check that the Windows
// installer gets Device Owner on a phone that already has accounts (the "account trick").
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.appsonly.testaccount"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.appsonly.testaccount"
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
