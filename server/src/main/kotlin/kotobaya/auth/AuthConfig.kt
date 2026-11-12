package kotobaya.auth

import kotobaya.server.ConfigException

/**
 * Login secrets, read only from the environment (Kubernetes Secret; there is no user table, openapi `login`):
 *
 * - `AUTH_PASSWORD_HASH` (required): Argon2id PHC string. Create it with
 *   `java -cp server-all.jar kotobaya.auth.HashPasswordKt` (see [PasswordHasher]).
 * - `AUTH_TOTP_SECRET` (optional): RFC 4648 base32 TOTP key, at least 128 bits. Absent or blank = TOTP off.
 */
class AuthConfig(val passwordHash: PasswordHasher.Parsed, val totpKey: ByteArray?) {
    val totpEnabled: Boolean get() = totpKey != null

    companion object {
        const val PASSWORD_HASH_ENV = "AUTH_PASSWORD_HASH"
        const val TOTP_SECRET_ENV = "AUTH_TOTP_SECRET"

        fun fromEnv(env: Map<String, String> = System.getenv()): AuthConfig {
            val raw = env[PASSWORD_HASH_ENV]?.takeIf { it.isNotBlank() }
                ?: throw ConfigException("missing environment variable $PASSWORD_HASH_ENV (see kotobaya.auth.HashPasswordKt)")
            val hash = try {
                PasswordHasher.parse(raw)
            } catch (e: IllegalArgumentException) {
                throw ConfigException("$PASSWORD_HASH_ENV is not a valid Argon2id PHC string: ${e.message}")
            }
            val totp = env[TOTP_SECRET_ENV]?.takeIf { it.isNotBlank() }?.let {
                val key = try {
                    Totp.decodeBase32(it)
                } catch (e: IllegalArgumentException) {
                    throw ConfigException("$TOTP_SECRET_ENV: ${e.message}")
                }
                if (key.size < 16) throw ConfigException("$TOTP_SECRET_ENV must be at least 128 bits (26 base32 characters)")
                key
            }
            return AuthConfig(hash, totp)
        }
    }
}
