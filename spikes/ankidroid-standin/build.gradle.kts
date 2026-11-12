plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

kotlin { jvmToolchain(17) }

android {
    namespace = "kotobaya.spike.ankistandin"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        targetSdk = 35
        // High version code: some AnkiDroid API clients gate features on the host's versionCode.
        versionCode = 21_900_000
        versionName = "0.0.1-spike"
    }
    flavorDimensions += "id"
    productFlavors {
        // Default: installs next to anything; Takoboto finds it via the provider authority only.
        create("standin") {
            dimension = "id"
            isDefault = true
            applicationId = "kotobaya.spike.ankistandin"
        }
        // Fallback for clients that check AnkiDroid's package name (sideload; AnkiDroid must be absent).
        create("ankidroid") {
            dimension = "id"
            applicationId = "com.ichi2.anki"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
