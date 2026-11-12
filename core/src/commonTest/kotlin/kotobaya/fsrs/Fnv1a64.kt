package kotobaya.fsrs

/**
 * FNV-1a 64 digest for cross-platform bit-identity tests (contracts/core README, T107).
 *
 * Each 64-bit word is folded as its 8 bytes, least significant first. A double is folded as its
 * `toRawBits()`, except that every NaN is folded as the canonical `0x7FF8000000000000`: NaN sign
 * and payload bits are not portable (x86 produces a negative default NaN, JS engines may
 * canonicalize), and no cache value is ever NaN.
 *
 * Pattern for hard-coded digests (compute once on jvm, paste): run the digest on jvm against the
 * reference (e.g. `StrictMath`, see [PortableMathCorpus]), print it with
 * `"0x" + value.toULong().toString(16)`, and assert `assertEquals(0x….toULong().toLong(), …)` in
 * `commonTest` so that jvm, js and android all check the same constant.
 */
internal class Fnv1a64 {
    var value: Long = OFFSET_BASIS
        private set

    fun addLong(word: Long): Fnv1a64 {
        var h = value
        var w = word
        repeat(8) {
            h = (h xor (w and 0xFF)) * PRIME
            w = w ushr 8
        }
        value = h
        return this
    }

    fun addDouble(d: Double): Fnv1a64 = addLong(if (d.isNaN()) CANONICAL_NAN else d.toRawBits())

    fun addInt(i: Int): Fnv1a64 = addLong(i.toLong())

    companion object {
        const val OFFSET_BASIS: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        const val PRIME: Long = 0x100000001b3L
        const val CANONICAL_NAN: Long = 0x7FF8000000000000L

        /** Byte-wise FNV-1a 64 of [bytes] (only used to check the constants against published vectors). */
        fun ofBytes(bytes: ByteArray): Long {
            var h = OFFSET_BASIS
            for (b in bytes) h = (h xor (b.toLong() and 0xFF)) * PRIME
            return h
        }
    }
}
