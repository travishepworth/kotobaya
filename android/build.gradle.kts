plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

kotlin { jvmToolchain(17) }

android {
    namespace = "kotobaya.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "kotobaya.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/cardAssets"))
}

// Shared card stylesheet from assets/card/ is bundled into the app's assets (used by the review WebView).
val copyCardAssets by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("assets/card"))
    into(layout.buildDirectory.dir("generated/cardAssets/card"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyCardAssets)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.kotlin.test)
}
