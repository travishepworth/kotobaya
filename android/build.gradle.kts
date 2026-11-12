plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(17) }

// The contract .sq files are compiled in place (never copied) so drift is impossible.
// Extra queries live in android/src/main/sqldelight/ and share the contract's package.
sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("kotobaya.db.app")
            srcDirs.setFrom(
                rootProject.layout.projectDirectory.dir("contracts/db/sqldelight/app"),
                layout.projectDirectory.dir("src/main/sqldelight/app"),
            )
        }
        create("RefDatabase") {
            packageName.set("kotobaya.db.ref")
            srcDirs.setFrom(rootProject.layout.projectDirectory.dir("contracts/db/sqldelight/ref"))
        }
    }
}

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
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Copies the shared card stylesheet from assets/card/ into build/generated/cardAssets/card/.
abstract class CopyCardAssets : DefaultTask() {
    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dest = outputDir.get().dir("card").asFile
        dest.deleteRecursively()
        source.get().asFile.copyRecursively(dest, overwrite = true)
    }
}

// Registered as a generated assets source so every consumer (merge, lint, bundle) depends on it.
val copyCardAssets = tasks.register<CopyCardAssets>("copyCardAssets") {
    source.set(rootProject.layout.projectDirectory.dir("assets/card"))
    outputDir.set(layout.buildDirectory.dir("generated/cardAssets"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyCardAssets) { it.outputDir }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.sqldelight.android.driver)
    // T301b: HTTP client and QR scanner (see HANDOFF)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
