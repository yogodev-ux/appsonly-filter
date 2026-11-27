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
        // -PprobeV2 builds "version 2", which has one more web address built in
        // (the escape test installs it as an update to check the app is scanned again).
        val v2 = project.hasProperty("probeV2")
        versionCode = if (v2) 2 else 1
        versionName = if (v2) "2.0" else "1.0"
        resValue("string", "update_server", if (v2) "https://news.update-only-host.net/v2" else "none")
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
