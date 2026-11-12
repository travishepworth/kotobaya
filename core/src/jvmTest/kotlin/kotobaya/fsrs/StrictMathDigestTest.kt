package kotobaya.fsrs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the hard-coded digests of [PortableMathTest] to `java.lang.StrictMath` (= fdlibm 5.3): the
 * constants must be what StrictMath gives on the corpus, so an edit of the port cannot silently
 * regenerate them from itself.
 */
class StrictMathDigestTest {
    private fun hex(v: Long): String = "0x" + v.toULong().toString(16)

    @Test
    fun powConstantIsStrictMathDigest() {
        val strict = PortableMathCorpus.powDigest(StrictMath::pow)
        assertEquals(hex(PortableMathTest.STRICT_POW_DIGEST), hex(strict))
        assertEquals("0xf5b5db2be1f363aa", hex(strict)) // reviewer-verified at T107a
    }

    @Test
    fun expConstantIsStrictMathDigest() {
        val strict = PortableMathCorpus.expDigest(StrictMath::exp)
        assertEquals(hex(PortableMathTest.STRICT_EXP_DIGEST), hex(strict))
        assertEquals("0xce32c80fa780e052", hex(strict)) // reviewer-verified at T107a
    }

    @Test
    fun lnConstantIsStrictMathDigest() {
        val strict = PortableMathCorpus.lnDigest(StrictMath::log)
        assertEquals(hex(PortableMathTest.STRICT_LN_DIGEST), hex(strict))
        assertEquals("0x3bdace39bbb903ce", hex(strict)) // reviewer-verified at T107a
    }
}
