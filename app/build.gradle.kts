plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val appCodename = "Gust"

android {
    namespace = "nl.icthorse.vphjoystick"
    compileSdk = 35

    // Release signt bewust met de debug-signingconfig: persoonlijk gebruik,
    // distributie via HorseAPK (geen Play Store). Zie README.md.
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "nl.icthorse.vphjoystick"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-Gust"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val suffix = if (variant.buildType.name == "release") "release" else "debug"
            output.outputFileName =
                "VideopacHorseJoystick-v${variant.versionName}-${suffix}.apk"
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

dependencies {
    // Bewust leeg: alleen Kotlin-stdlib + platform-SDK.
    // Geen AndroidX, geen Compose — de app is één Activity met custom Views
    // en de BLE-API's zitten volledig in het platform.
}
