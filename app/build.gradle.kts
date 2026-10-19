plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aiwriter.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aiwriter.app"
        minSdk = 26          // Android 8.0+ (Galaxy A8 dahil) ... Android 16/17 (S26, S26+, Ultra)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Sabit imza: her derlemede aynı anahtar kullanılır, böylece APK'yı üstüne güncelleyebilirsin.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
