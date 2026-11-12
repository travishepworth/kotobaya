package kotobaya.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id (RFC 9106) password hashes in the PHC string format
 * `$argon2id$v=19$m=<KiB>,t=<iterations>,p=<lanes>$<salt b64>$<hash b64>` (unpadded standard base64),
 * the format `argon2` CLI, libsodium and most libraries produce, so a hash made elsewhere also works.
 *
 * Defaults for new hashes: RFC 9106 §4 second recommended option, m = 64 MiB, t = 3, p = 4,
 * 16-byte salt, 32-byte tag. Verification takes the parameters from the stored string.
 */
object PasswordHasher {
    data class Params(val memoryKiB: Int = 65_536, val iterations: Int = 3, val parallelism: Int = 4)

    val DEFAULT = Params()
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32

    /** Refuse hashes that would let a misconfigured secret exhaust memory or CPU on every login. */
    const val MAX_MEMORY_KiB = 262_144 // 256 MiB
    private const val MAX_ITERATIONS = 64
    private const val MAX_PARALLELISM = 16

    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    fun hash(password: String, params: Params = DEFAULT): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val tag = derive(password, salt, params, HASH_BYTES)
        return "\$argon2id\$v=19\$m=${params.memoryKiB},t=${params.iterations},p=${params.parallelism}" +
            "\$${b64.encodeToString(salt)}\$${b64.encodeToString(tag)}"
    }

    /** Constant-time comparison of the derived tag. */
    fun verify(password: String, phc: Parsed): Boolean {
        val tag = derive(password, phc.salt, phc.params, phc.hash.size)
        return MessageDigest.isEqual(tag, phc.hash)
    }

    class Parsed(val params: Params, val salt: ByteArray, val hash: ByteArray)

    /** Parses and validates a PHC string; throws [IllegalArgumentException] naming what is wrong (never the value). */
    fun parse(phc: String): Parsed {
        val parts = phc.trim().split('$')
        require(parts.size == 6 && parts[0].isEmpty()) { "not a PHC string" }
        require(parts[1] == "argon2id") { "only argon2id is accepted" }
        require(parts[2] == "v=19") { "only Argon2 version 19 is accepted" }
        val kv = parts[3].split(',').associate {
            val (k, v) = it.split('=', limit = 2).also { p -> require(p.size == 2) { "bad parameter list" } }
            k to (v.toIntOrNull() ?: throw IllegalArgumentException("bad parameter $k"))
        }
        val params = Params(
            memoryKiB = kv["m"] ?: throw IllegalArgumentException("missing m"),
            iterations = kv["t"] ?: throw IllegalArgumentException("missing t"),
            parallelism = kv["p"] ?: throw IllegalArgumentException("missing p"),
        )
        require(params.parallelism in 1..MAX_PARALLELISM) { "p out of range" }
        require(params.iterations in 1..MAX_ITERATIONS) { "t out of range" }
        require(params.memoryKiB in 8 * params.parallelism..MAX_MEMORY_KiB) { "m out of range" }
        val salt = runCatching { b64d.decode(parts[4]) }.getOrElse { throw IllegalArgumentException("bad salt") }
        val hash = runCatching { b64d.decode(parts[5]) }.getOrElse { throw IllegalArgumentException("bad hash") }
        require(salt.size >= 8) { "salt too short" }
        require(hash.size >= 16) { "hash too short" }
        return Parsed(params, salt, hash)
    }

    private fun derive(password: String, salt: ByteArray, p: Params, length: Int): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(p.memoryKiB)
                .withIterations(p.iterations)
                .withParallelism(p.parallelism)
                .build(),
        )
        val out = ByteArray(length)
        gen.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }
}
