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
        // T312: set by the release workflow from the v<major>.<minor>.<patch> tag; local builds keep the defaults.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.0.0"
    }
    // T305a: AnkiDroid stand-in flavors. Code namespace stays kotobaya.android in both; only one app
    // per device can own the provider authority and permission (see ankiapi/provider/AnkiContract.kt).
    flavorDimensions += "appId"
    productFlavors {
        // Default: Takoboto (AddContentApi) finds AnkiDroid by this package name. Sideload only.
        create("ankidroid") {
            dimension = "appId"
            isDefault = true
            applicationId = "com.ichi2.anki"
        }
        // Fallback: own package name; Takoboto finds it via the provider authority only.
        create("own") {
            dimension = "appId"
            applicationId = "kotobaya.android"
        }
    }
    // T312: release signing comes only from env (CLAUDE.md rule 7); see android/signing/README.md.
    // Without ANDROID_KEYSTORE_PATH the release build stays unsigned, so local builds need no secrets.
    val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
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

// --- T305a: flavor verification, aggregate test task, APK names --------------------------------

// Fails unless a variant's merged manifest has the expected package and declares AnkiDroid's
// provider authority and permission (the two things Takoboto's AddContentApi looks for).
abstract class VerifyAnkiApiManifest : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val expectedApplicationId: Property<String>

    @TaskAction
    fun verify() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)
        fun elements(tag: String): List<org.w3c.dom.Element> =
            doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
        val pkg = doc.documentElement.getAttribute("package")
        val permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        val provider = elements("provider")
            .firstOrNull { it.getAttributeNS(ns, "authorities") == "com.ichi2.anki.flashcards" }
        val problems = buildList {
            if (pkg != expectedApplicationId.get()) add("package is '$pkg', expected '${expectedApplicationId.get()}'")
            if (elements("permission").none { it.getAttributeNS(ns, "name") == permission }) {
                add("permission $permission not declared")
            }
            if (provider == null) {
                add("provider com.ichi2.anki.flashcards not declared")
            } else {
                if (provider.getAttributeNS(ns, "exported") != "true") add("provider not exported")
                if (provider.getAttributeNS(ns, "readPermission") != permission) add("provider readPermission wrong")
                if (provider.getAttributeNS(ns, "writePermission") != permission) add("provider writePermission wrong")
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("${mergedManifest.get().asFile}: " + problems.joinToString("; "))
        }
        logger.lifecycle("AnkiApi manifest OK: $pkg")
    }
}

val expectedAppIds = mapOf("ankidroid" to "com.ichi2.anki", "own" to "kotobaya.android")
androidComponents {
    onVariants { variant ->
        val expected = expectedAppIds[variant.flavorName] ?: return@onVariants
        val taskName = "verify${variant.name.replaceFirstChar { it.uppercase() }}AnkiApiManifest"
        val verify = tasks.register<VerifyAnkiApiManifest>(taskName) {
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            expectedApplicationId.set(expected)
        }
        // Every APK, debug or release (`make apk`), is checked as it is assembled.
        val assembleName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
        tasks.matching { it.name == assembleName }.configureEach { dependsOn(verify) }
    }
}

// `make check MODULE=android` runs `:android:testDebugUnitTest`, which flavors would remove. Keep the
// name as one aggregate: unit tests of the default flavor, plus both debug flavors built and all four manifests verified.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Unit tests (ankidroid flavor); builds both debug flavors; verifies all four merged manifests."
    dependsOn(
        "testAnkidroidDebugUnitTest",
        "assembleAnkidroidDebug",
        "assembleOwnDebug",
        "compileOwnDebugUnitTestKotlin",
        "verifyAnkidroidDebugAnkiApiManifest",
        "verifyOwnDebugAnkiApiManifest",
        "verifyAnkidroidReleaseAnkiApiManifest",
        "verifyOwnReleaseAnkiApiManifest",
    )
}

// The default flavor's APK is named plainly; the fallback carries its flavor name.
@Suppress("DEPRECATION")
android.applicationVariants.all {
    val variant = this
    outputs.all {
        val suffix = if (variant.flavorName == "ankidroid") "" else "-${variant.flavorName}"
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "kotobaya$suffix-${variant.buildType.name}.apk"
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
    // T302: zstd for dictionary packages (the aar carries the Android natives, the plain jar the host natives
    // for unit tests) and WorkManager for the update job
    implementation("com.github.luben:zstd-jni:1.5.6-4@aar")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // T307: Glance home-screen widget (and its composition-test helper)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    testImplementation("androidx.glance:glance-appwidget-testing:1.1.1")
    // T309: UnifiedPush connector (ntfy app as distributor)
    implementation("org.unifiedpush.android:connector:3.0.10")
    // T303c: WorkManager test driver for the sync work scheduling tests
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("com.github.luben:zstd-jni:1.5.6-4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
