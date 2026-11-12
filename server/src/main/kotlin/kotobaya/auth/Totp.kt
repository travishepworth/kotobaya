package kotobaya.auth

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP as `POST /auth/login` specifies: HMAC-SHA1, 6 digits, 30 s step, T0 = 0; a code is
 * accepted for the current and the previous step, and only once.
 *
 * "Only once" is enforced by remembering the last accepted step in memory: a code for a step at or
 * before it is refused. With [startedAt] (the server passes its start time) the step current at start counts
 * as used, so a code used just before a restart cannot be replayed after it; the cost is that the first
 * login after a restart may have to wait for the next code (at most 30 s).
 */
class Totp(
    private val key: ByteArray,
    private val digits: Int = 6,
    private val stepSeconds: Long = 30,
    startedAt: Instant? = null,
) {
    private val lock = Any()
    private var lastAcceptedStep = startedAt?.let { step(it) } ?: Long.MIN_VALUE

    fun step(at: Instant): Long = Math.floorDiv(at.epochSecond, stepSeconds)

    /** The code for one step (RFC 4226 dynamic truncation). */
    fun codeAt(step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val o = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or
            ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** Which accepted step [code] matches (current first, then previous), or null. Constant-time per candidate. */
    fun matchingStep(code: String, at: Instant): Long? {
        val now = step(at)
        var found: Long? = null
        // Compare against both candidates every time so timing does not reveal which one matched.
        for (s in longArrayOf(now, now - 1)) {
            if (MessageDigest.isEqual(codeAt(s).toByteArray(), code.toByteArray()) && found == null) found = s
        }
        return found
    }

    /** Marks [step] used; false if it (or a later step) was already used, i.e. a replay. */
    fun consume(step: Long): Boolean = synchronized(lock) {
        if (step <= lastAcceptedStep) return false
        lastAcceptedStep = step
        true
    }

    companion object {
        /** RFC 4648 base32 (case-insensitive; spaces, dashes and `=` padding ignored), the format authenticator apps use. */
        fun decodeBase32(s: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val clean = s.uppercase().filter { it != ' ' && it != '-' && it != '=' }
            require(clean.isNotEmpty()) { "empty TOTP secret" }
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (c in clean) {
                val v = alphabet.indexOf(c)
                require(v >= 0) { "TOTP secret is not base32" }
                buffer = (buffer shl 5) or v
                bits += 5
                if (bits >= 8) {
                    out.write((buffer shr (bits - 8)) and 0xFF)
                    bits -= 8
                }
            }
            return out.toByteArray()
        }
    }
}
