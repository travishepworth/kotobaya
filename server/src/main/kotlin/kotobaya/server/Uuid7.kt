package kotobaya.server

import java.security.SecureRandom
import java.util.UUID

/** UUIDv7 (RFC 9562): 48-bit unix ms, version 7, variant 10, random remainder. Canonical lowercase text via [UUID.toString]. */
object Uuid7 {
    private val random = SecureRandom()

    fun generate(nowMs: Long = System.currentTimeMillis()): UUID {
        val rand = ByteArray(10).also(random::nextBytes)
        var msb = (nowMs and 0xFFFFFFFFFFFFL) shl 16
        msb = msb or 0x7000L or (((rand[0].toLong() and 0x0F) shl 8) or (rand[1].toLong() and 0xFF))
        var lsb = 0L
        for (i in 2 until 10) lsb = (lsb shl 8) or (rand[i].toLong() and 0xFF)
        lsb = (lsb and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }
}
