plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.appsonly.filter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.appsonly.filter"
        minSdk = 29 // Android 10
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Fixed debug key, committed on purpose: every CI build is signed the same way, so a
        // new test build installs over the old one (a Device Owner app is hard to uninstall).
        // Debug keys are not secret. Release builds will get a real key kept out of the repo.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Debug builds are installed as test-only so the Device Owner can be
            // removed with `adb shell dpm remove-active-admin` while testing.
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
