package kotobaya.auth

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * Outstanding pairing codes, in memory (openapi `createPairing`; contracts/api/SUMMARY.md "Known gaps": there is no
 * table, so a restart invalidates every outstanding code and `POST /devices/pair` then answers
 * `401 invalid_pairing_code`; the user creates a new code. Fine for one API replica only).
 *
 * A code is 8 Crockford base32 characters (40 bits), valid for [lifetime], usable once. Pairing [take]s the code
 * before its transaction (so two racing requests cannot both get it) and [restore]s it if the transaction fails
 * without consuming it (a `422` such as `hlc_regressed` leaves the code valid).
 */
class PairingCodes(
    private val lifetime: Duration = Duration.ofMinutes(10),
    private val maxOutstanding: Int = 1000,
) {
    private val random = SecureRandom()
    private val codes = HashMap<String, Instant>()

    /** A new code and its expiry. */
    @Synchronized
    fun create(now: Instant): Pair<String, Instant> {
        prune(now)
        if (codes.size >= maxOutstanding) codes.minByOrNull { it.value }?.let { codes.remove(it.key) }
        var code: String
        do {
            code = String(CharArray(8) { ALPHABET[random.nextInt(32)] })
        } while (code in codes)
        val expires = now.plus(lifetime)
        codes[code] = expires
        return code to expires
    }

    /** True if [code] is outstanding and unexpired (does not consume it). */
    @Synchronized
    fun isValid(code: String, now: Instant): Boolean = codes[normalise(code)]?.isAfter(now) == true

    /** Removes [code] and returns its expiry if it was valid; null otherwise. */
    @Synchronized
    fun take(code: String, now: Instant): Instant? {
        val c = normalise(code)
        val expires = codes.remove(c) ?: return null
        return expires.takeIf { it.isAfter(now) }
    }

    /** Puts a [take]n code back (the pairing transaction did not consume it). */
    @Synchronized
    fun restore(code: String, expires: Instant) {
        codes[normalise(code)] = expires
    }

    private fun prune(now: Instant) {
        codes.values.removeIf { !it.isAfter(now) }
    }

    companion object {
        /** Crockford base32: digits and letters without I, L, O, U. */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** Case-insensitive; hyphens and spaces typed by the user are ignored. */
        fun normalise(code: String): String = code.filterNot { it == '-' || it == ' ' }.uppercase()
    }
}
