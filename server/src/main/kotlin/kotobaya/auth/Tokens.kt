package kotobaya.auth

import java.security.MessageDigest
import java.security.SecureRandom

/** `app.api_token.kind`, with the token-string prefix of openapi §2. */
enum class TokenKind(val wire: String, val prefix: String) {
    SESSION("session", "kbs_"),
    DEVICE("device", "kbd_"),
    PERSONAL("personal", "kbp_"),
    ;

    companion object {
        fun fromWire(s: String): TokenKind = entries.firstOrNull { it.wire == s } ?: error("unknown token kind $s")
    }
}

/** The five scopes in the order the API reports them (Principal.scopes). */
val ALL_SCOPES: List<String> = listOf("read", "write", "review", "import", "admin")

/** Token strings per openapi §2: `<kind prefix>_<40 chars of [A-Za-z0-9]>` from a CSPRNG; only the hash is stored. */
object Tokens {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val BODY_LENGTH = 40
    private val random = SecureRandom()
    val FORMAT = Regex("^kb[sdp]_[A-Za-z0-9]{40}$")

    fun generate(kind: TokenKind): String {
        val sb = StringBuilder(kind.prefix)
        // 62 symbols: rejection sampling on bytes < 248 (= 4 * 62) keeps the distribution uniform.
        while (sb.length < kind.prefix.length + BODY_LENGTH) {
            val b = random.nextInt(256)
            if (b < 248) sb.append(ALPHABET[b % 62])
        }
        return sb.toString()
    }

    /** `token_hash`: lowercase hex SHA-256 of the whole string. */
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).toHex()

    /** `token_prefix`: the first 8 characters. */
    fun prefix(token: String): String = token.take(8)

    fun isWellFormed(token: String): Boolean = FORMAT.matches(token)
}

internal fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val out = CharArray(size * 2)
    for ((i, b) in withIndex()) {
        out[2 * i] = hex[(b.toInt() shr 4) and 0xF]
        out[2 * i + 1] = hex[b.toInt() and 0xF]
    }
    return String(out)
}
