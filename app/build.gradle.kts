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
        // Step 4 license server. Release builds take the real values from gradle properties
        // (-PlicenseServer=https://... -PlicensePublicKey=...); debug builds use the local test
        // server (10.0.2.2 is the PC as seen from the Android emulator) and its TEST key.
        val testKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYA7/AYYnnR0AonKsE0SwWxIAB/Xx1Gg5oiY4ecEl9vNDELm1L9Bsy6K0CANFTTpjZJOKtEVsZCGG8U7/ZXaurg=="
        buildConfigField("String", "LICENSE_SERVER",
            "\"" + (project.findProperty("licenseServer") ?: "http://10.0.2.2:8787") + "\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY",
            "\"" + (project.findProperty("licensePublicKey") ?: testKey) + "\"")
    }

    buildFeatures { buildConfig = true }

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
            // Until the real server exists (hosting is chosen later), release builds point
            // nowhere and stay unpaid. Pass -PlicenseServer=https://... to build a real one.
            buildConfigField("String", "LICENSE_SERVER",
                "\"" + (project.findProperty("licenseServer") ?: "https://license.invalid") + "\"")
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
