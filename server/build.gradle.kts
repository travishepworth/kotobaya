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
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
}

// The API and DB contracts are served from the frozen files, never copied into the source tree.
tasks.processResources {
    from(rootProject.file("contracts/api")) {
        include("openapi.yaml")
        into("contracts")
    }
    from(rootProject.file("contracts/db")) {
        include("V1__init.sql")
        into("db/migration")
    }
}

tasks.test {
    useJUnitPlatform()
    // pgjdbc sends the JVM zone to Postgres; legacy ids such as "Japan" are rejected by the postgres image.
    systemProperty("user.timezone", "UTC")
}
