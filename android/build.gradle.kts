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
    testImplementation(libs.kotlin.test)
}
