plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()
    androidTarget()
    js(IR) {
        // browser() keeps the library build the web consumes; its Karma test task is off because
        // `:core:jsTest` runs on node (no headless browser needed).
        browser {
            testTask { enabled = false }
        }
        nodejs {
            testTask {
                // the PortableMath bit-identity corpus (> 1e6 arguments) is slow on js Longs
                useMocha { timeout = "300s" }
            }
        }
        binaries.library()
        generateTypeScriptDefinitions()
        useEsModules()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "kotobaya.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Packages the JS library build as the npm package `@kotobaya/core` (consumed by web/ via a file: dependency).
val npmPackage by tasks.registering(Copy::class) {
    dependsOn("jsProductionLibraryCompileSync")
    from(layout.buildDirectory.dir("compileSync/js/main/productionLibrary/kotlin"))
    into(layout.buildDirectory.dir("npm/core"))
    doLast {
        layout.buildDirectory.file("npm/core/package.json").get().asFile.writeText(
            """
            {
              "name": "@kotobaya/core",
              "version": "0.0.0",
              "type": "module",
              "main": "kotobaya-core.mjs",
              "types": "kotobaya-core.d.ts"
            }
            """.trimIndent() + "\n",
        )
    }
}
