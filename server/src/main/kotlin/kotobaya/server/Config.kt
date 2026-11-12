package kotobaya.server

/** Environment contract (names fixed by the Helm chart and compose; no aliases). */
data class Config(
    val port: Int,
    /** JDBC URL, credentials included as the deployment passes them (`jdbc:postgresql://...?user=..&password=..`). */
    val databaseUrl: String,
    val s3Endpoint: String,
    val s3Region: String?,
    val s3Bucket: String,
    val s3AccessKey: String,
    val s3SecretKey: String,
    /** `MIGRATE_ON_START=true` runs Flyway before the startup sequence. Off by default (the Helm hook migrates). */
    val migrateOnStart: Boolean,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun req(name: String): String =
                env[name]?.takeIf { it.isNotBlank() } ?: throw ConfigException("missing environment variable $name")
            return Config(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                databaseUrl = req("DATABASE_URL"),
                s3Endpoint = req("S3_ENDPOINT"),
                s3Region = env["S3_REGION"]?.takeIf { it.isNotBlank() },
                s3Bucket = req("S3_BUCKET"),
                s3AccessKey = req("S3_ACCESS_KEY"),
                s3SecretKey = req("S3_SECRET_KEY"),
                migrateOnStart = env["MIGRATE_ON_START"]?.lowercase() in setOf("1", "true", "yes"),
            )
        }

        /** Only what `migrate` needs. */
        fun databaseUrlFromEnv(env: Map<String, String> = System.getenv()): String =
            env["DATABASE_URL"]?.takeIf { it.isNotBlank() } ?: throw ConfigException("missing environment variable DATABASE_URL")
    }
}

class ConfigException(message: String) : RuntimeException(message)
