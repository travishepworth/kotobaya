plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("kotobaya.server.MainKt")
}

// Versions are local to the server module (the shared catalog is not ours to edit).
val jacksonVersion = "2.18.2"
val flywayVersion = "11.1.0"
val jooqVersion = "3.19.16"
val testcontainersVersion = "1.21.4"

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation("io.ktor:ktor-server-status-pages-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-metrics-micrometer-jvm:${libs.versions.ktor.get()}")
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    implementation("io.minio:minio:8.5.14")
    implementation(libs.logback.classic)
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    // --- T105a (auth): Argon2id password hashing. Already on the runtime classpath via minio; declared so
    // auth does not depend on minio's transitive graph. Same version, no new artifact.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // --- end T105a
    // T411c: zstd for media snapshot archives (contracts/db/SNAPSHOT.md 2.8; same artifact as the Android dict installer).
    implementation("com.github.luben:zstd-jni:1.5.6-4")
    // T608: writes the Anki `collection.anki21` SQLite file of the .apkg export (same driver family as the Android app's SQLite).
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
}

// F-16: build version (KOTOBAYA_VERSION env or -PkotobayaVersion; "dev" when absent) baked into a generated resource.
val buildVersion: String = (providers.gradleProperty("kotobayaVersion").orNull ?: System.getenv("KOTOBAYA_VERSION"))
    ?.takeIf { it.isNotBlank() } ?: "dev"
val generateVersionResource by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", buildVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().asFile.resolve("kotobaya-version.properties")
        f.parentFile.mkdirs()
        f.writeText("version=$buildVersion\n")
    }
}
sourceSets.main { resources.srcDir(generateVersionResource) }

// The API and DB contracts are served from the frozen files, never copied into the source tree.
tasks.processResources {
    from(rootProject.file("contracts/api")) {
        include("openapi.yaml")
        into("contracts")
    }
    from(rootProject.file("contracts/db")) {
        include("V*__*.sql")
        into("db/migration")
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // pgjdbc sends the JVM zone to Postgres; legacy ids such as "Japan" are rejected by the postgres image.
    systemProperty("user.timezone", "UTC")
    systemProperty("kotobaya.expectedVersion", buildVersion)
}
