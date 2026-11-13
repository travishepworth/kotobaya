plugins {
    alias(libs.plugins.kotlin.jvm)
}

// T313: offline/sync chaos simulation. JVM only, tests only: the real core client stack (`:core` jvm)
// against the real server sync code (`:server`, Postgres via Testcontainers) or, without Docker, an
// in-memory reference server. Sources live under `offline/` (the owned path of T313).
kotlin {
    jvmToolchain(17)
    sourceSets["test"].kotlin.srcDir("offline/src/test/kotlin")
}

// Same versions as `server/build.gradle.kts` (no new artefacts; declared because the server keeps them
// `implementation`, so they are not on this module's compile classpath otherwise).
val jacksonVersion = "2.18.2"
val jooqVersion = "3.19.16"
val testcontainersVersion = "1.21.4"

dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":server"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation("org.jooq:jooq:$jooqVersion")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    testImplementation("com.zaxxer:HikariCP:6.2.1")
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
}

tasks.test {
    useJUnitPlatform()
    // pgjdbc sends the JVM zone to Postgres; legacy ids such as "Japan" are rejected by the postgres image.
    systemProperty("user.timezone", "UTC")
    // -Pseeds=<n> (number of randomised runs, default 100), -Pseeds=<a>..<b> or -Pseeds=<a>,<b>,...
    // -Pserver=memory forces the in-memory reference server (default: Postgres when Docker is available).
    project.findProperty("seeds")?.let { systemProperty("kotobaya.seeds", it.toString()) }
    project.findProperty("server")?.let { systemProperty("kotobaya.server", it.toString()) }
    systemProperty("kotobaya.reportDir", layout.buildDirectory.dir("reports/offline-chaos").get().asFile.absolutePath)
    inputs.property("seeds", project.findProperty("seeds")?.toString() ?: "")
    inputs.property("server", project.findProperty("server")?.toString() ?: "")
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
